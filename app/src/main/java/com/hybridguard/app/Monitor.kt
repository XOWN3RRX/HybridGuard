package com.hybridguard.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.hybridguard.data.Alarm
import com.hybridguard.data.AlarmGate
import com.hybridguard.data.AlarmLevel
import com.hybridguard.data.AlarmView
import com.hybridguard.data.CapabilityReport
import com.hybridguard.data.DashboardState
import com.hybridguard.data.ProfileChoice
import com.hybridguard.data.notGiven
import com.hybridguard.data.Role
import com.hybridguard.data.SessionConfig
import com.hybridguard.data.SessionEnd
import com.hybridguard.data.SessionMode
import com.hybridguard.data.SessionPhase
import com.hybridguard.data.SessionRunner
import com.hybridguard.data.SessionState
import com.hybridguard.data.withOverrides
import com.hybridguard.obd.Elm327
import com.hybridguard.obd.ObdTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the monitor is doing, for the screen and the notification. */
enum class MonitorPhase(val label: String) {
    OFF("Monitoring is off"),
    NO_ADAPTER("Choose the adapter"),
    NEED_PERMISSION("Allow Bluetooth"),
    BLUETOOTH_OFF("Bluetooth is off"),
    WAITING_FOR_CAR_LINK("Waiting for the car's Bluetooth"),
    CHECKING("Looking for the adapter"),
    ADAPTER_ASLEEP("Waiting for the car"),
    PARKED("Car parked"),
    CONNECTING("Connecting to the car"),
    MONITORING("Monitoring"),
}

data class MonitorStatus(
    val phase: MonitorPhase = MonitorPhase.OFF,
    val message: String? = null,
    /** Last 12 V reading from the adapter, and when. */
    val volts: Double? = null,
    val voltsEpochMs: Long? = null,
    val nextCheckEpochMs: Long? = null,
    /** Since when the adapter answers while the car is parked (it should fall asleep after ~15 min). */
    val awakeParkedSinceEpochMs: Long? = null,
    val sessionStartEpochMs: Long? = null,
    val session: SessionState? = null,
    val dashboard: DashboardState? = null,
    val alarms: AlarmView = AlarmView(),
    val lastSession: RecordedSession? = null,
    /** Values the car does not give, with what each request answered (from the probe). */
    val notGiven: Map<Role, List<String>> = emptyMap(),
) {
    val ready: Boolean get() = dashboard?.ready == true
}

/**
 * Watches for the car and monitors it. One loop in the app process, kept
 * alive by [MonitorService]:
 *
 * 1. Wait (a timer, the car's Bluetooth connecting, the phone starting to charge, "Check now").
 * 2. Look at the adapter: connect and read only its supply voltage (`ATRV`,
 *    nothing is sent on the car's bus). Unreachable: it sleeps (car off for
 *    more than ~15 min) or is out of range. Below the READY voltage: parked.
 * 3. The car is READY: run a [SessionRunner] until the car goes quiet,
 *    recording a session and raising the alarms.
 * 4. The car went quiet: the 12 V battery often stays above the READY
 *    voltage for minutes after charging, so no new session starts until the
 *    voltage drops below it, rises again (the car charges it: READY), the
 *    driver does something (car's Bluetooth, charger, "Check now"), or the
 *    hold time passes.
 */
object Monitor {
    private const val TAG = "HybridGuard"
    private const val HISTORY_MS = 60 * 60_000L
    /** Give up on a session whose adapter stays unreachable this long (it fell asleep). */
    private const val GIVE_UP_RECONNECT_MS = 3 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(MonitorStatus())
    val status: StateFlow<MonitorStatus> = _status.asStateFlow()

    /** The last hour of values, one sample per second at most, for the live charts. */
    private val _history = MutableStateFlow<List<Sample>>(emptyList())
    val history: StateFlow<List<Sample>> = _history.asStateFlow()

    private val pokes = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private val gateLock = Any()
    private var gate = AlarmGate()
    private var gateSettings: Pair<Int, Int>? = null

    /** The car went quiet: the 12 V must show it is READY again before the next session. */
    private data class AfterOff(val sinceMono: Long, val lowestVolts: Double? = null)

    @Volatile private var afterOff: AfterOff? = null

    @Volatile private var acknowledgedAt = 0L
    /** The Battery tab is on screen: its cell requests are polled (only then). */
    @Volatile private var batteryTab = false
    @Volatile private var activeRunner: SessionRunner? = null
    /** Ends the running session so it starts again (the model year changed); null when none runs. */
    @Volatile private var restartSession: (() -> Unit)? = null
    private lateinit var app: HybridGuardApp

    val running: Boolean get() = loop?.isActive == true

    fun init(context: HybridGuardApp) {
        app = context
        // Settings that change what to wait for: look again at once.
        scope.launch {
            app.settings.flow.map { Triple(it.source to it.deviceAddress, it.triggerMode to it.carDeviceAddress, it.voltageGate to it.readyVolts) }
                .distinctUntilChanged().collect { poke() }
        }
        scope.launch {
            app.archive.list().firstOrNull()?.let { last -> _status.update { it.copy(lastSession = last) } }
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        loop = scope.launch { runLoop() }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        _status.update { MonitorStatus(phase = MonitorPhase.OFF, lastSession = it.lastSession) }
        Notifier.clearAlarms(app)
    }

    /**
     * Look for the car now. [carEvent]: something says the car may have been
     * switched on (its Bluetooth connected, the phone started charging,
     * "Check now"), so the hold after the car went quiet ends; the timer and
     * settings changes only look.
     */
    fun poke(carEvent: Boolean = false) {
        if (carEvent) afterOff = null
        pokes.trySend(Unit)
    }

    /**
     * The owner gave the model year of the car with this VIN. Remembered for
     * that car; a session running with a different (assumed) year starts
     * again, so the right signals are read.
     */
    fun setModelYear(vin: String, year: Int) {
        scope.launch {
            app.profileSelector.setModelYear(vin, year)
            Notifier.clearModelYearQuestion(app)
            val st = _status.value.session
            if (st?.vin == vin && st.modelYear != year) restartSession?.invoke()
        }
    }

    /**
     * The Battery tab opened or closed. Its requests (cell voltages and
     * resistances, power limits, 12 V details) are polled only while it is
     * open, so they never slow the cooling watch.
     */
    fun setBatteryTab(open: Boolean) {
        batteryTab = open
        activeRunner?.setBattery(open)
    }

    /** OK on an alarm: no more repeats until a reminder or a worse level. */
    fun acknowledge() {
        acknowledgedAt = SystemClock.elapsedRealtime()
        synchronized(gateLock) { gate.acknowledge(acknowledgedAt) }
        _status.update { it.copy(alarms = it.alarms.copy(unacknowledged = false)) }
        Notifier.alarmsAcknowledged(app, _status.value.alarms)
    }

    private suspend fun runLoop() {
        val wakeLocks = app.getSystemService(PowerManager::class.java)
        while (scope.isActive) {
            val s = app.settings.value
            val waitMs: Long = when {
                !s.hasAdapter -> {
                    setPhase(MonitorPhase.NO_ADAPTER, "Open the app and choose the OBD adapter in Settings")
                    10 * 60_000L
                }
                s.source == DataSource.BLUETOOTH && !bluetoothGranted(app) -> {
                    setPhase(MonitorPhase.NEED_PERMISSION, "Open the app to allow Nearby devices")
                    10 * 60_000L
                }
                s.source == DataSource.BLUETOOTH && !bluetoothOn(app) -> {
                    setPhase(MonitorPhase.BLUETOOTH_OFF, "Turn Bluetooth on to watch the car")
                    60_000L
                }
                s.source == DataSource.BLUETOOTH && s.triggerMode == TriggerMode.CAR_BLUETOOTH &&
                    s.carDeviceAddress != null && !CarLink.isConnected(s.carDeviceAddress) -> {
                    setPhase(MonitorPhase.WAITING_FOR_CAR_LINK, "Starts as soon as the phone connects to ${s.carDeviceName ?: "the car"}")
                    30 * 60_000L
                }
                else -> {
                    val wl = wakeLocks.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HybridGuard:check").apply { acquire(60_000) }
                    try {
                        checkAndRun(s)
                    } finally {
                        if (wl.isHeld) wl.release()
                    }
                }
            }
            // Waiting for an event (the car's Bluetooth, a permission, an adapter choice):
            // the long timer is only a fallback, so no countdown is shown.
            val eventWait = _status.value.phase in setOf(
                MonitorPhase.WAITING_FOR_CAR_LINK, MonitorPhase.NO_ADAPTER, MonitorPhase.NEED_PERMISSION, MonitorPhase.BLUETOOTH_OFF,
            )
            _status.update { it.copy(nextCheckEpochMs = if (eventWait) null else System.currentTimeMillis() + waitMs) }
            scheduleWake(waitMs)
            withTimeoutOrNull(waitMs) { pokes.receive() }
        }
    }

    /** One look at the adapter, and a session if the car is READY. Returns how long to wait before the next look. */
    private suspend fun checkAndRun(s: Settings): Long {
        setPhase(MonitorPhase.CHECKING, null)
        // The phone is connected to the car's own Bluetooth: someone is in the car and it is
        // about to be READY (the stereo connects a few seconds before), so look often.
        val carLinked = s.source == DataSource.BLUETOOTH && CarLink.isConnected(s.carDeviceAddress)
        fun interval(normalSec: Int) = if (carLinked) minOf(normalSec, s.carLinkIntervalSec) else normalSec
        val volts: Double? = try {
            coroutineScope { Elm327.quickVoltage(newTransport(s, forCheck = true), this) }
        } catch (e: Exception) {
            Log.i(TAG, "Adapter not reachable: ${e.message}")
            _status.update { it.copy(awakeParkedSinceEpochMs = null) }
            val wait = interval(s.wakeIntervalSec)
            setPhase(MonitorPhase.ADAPTER_ASLEEP, "Adapter asleep or out of range. Next look in $wait s")
            return wait * 1000L
        }
        val now = System.currentTimeMillis()
        _status.update { it.copy(volts = volts, voltsEpochMs = now) }
        if (s.voltageGate && volts != null && volts < s.readyVolts) afterOff = null
        val hold = afterOff
        if (hold != null && s.voltageGate && volts != null) {
            val mono = SystemClock.elapsedRealtime()
            val lowest = minOf(hold.lowestVolts ?: volts, volts)
            val holdMs = s.afterOffHoldMin * 60_000L
            if (volts >= lowest + s.restartRiseVolts || mono - hold.sinceMono >= holdMs) {
                afterOff = null
            } else {
                afterOff = hold.copy(lowestVolts = lowest)
                _status.update { it.copy(awakeParkedSinceEpochMs = it.awakeParkedSinceEpochMs ?: now) }
                val wait = interval(s.parkedIntervalSec)
                val left = ((holdMs - (mono - hold.sinceMono)) / 60_000L + 1).coerceAtLeast(1)
                setPhase(
                    MonitorPhase.PARKED,
                    "The car went quiet. 12 V at %.1f V: still above %.1f V from charging, it falls slowly. ".format(volts, s.readyVolts) +
                        "Reading the car again when it rises by %.1f V (READY), or in %d min. Next look in %d s".format(s.restartRiseVolts, left, wait),
                )
                return wait * 1000L
            }
        }
        if (s.voltageGate && volts != null && volts < s.readyVolts) {
            _status.update { it.copy(awakeParkedSinceEpochMs = it.awakeParkedSinceEpochMs ?: now) }
            val wait = interval(s.parkedIntervalSec)
            val why = if (carLinked) " Car's Bluetooth connected: looking every $wait s" else ""
            setPhase(MonitorPhase.PARKED, "12 V at %.1f V: the car is not READY (from %.1f V).".format(volts, s.readyVolts) + why)
            return wait * 1000L
        }
        _status.update { it.copy(awakeParkedSinceEpochMs = null) }
        runSession(s, volts)
        // Right after a drive the car is usually off: look again soon, the adapter is still awake.
        return 5_000L
    }

    private fun newTransport(s: Settings, forCheck: Boolean): ObdTransport = when (s.source) {
        DataSource.SIMULATED -> SimulatedCheck()
        else -> SppTransport(app, s.deviceAddress ?: error("no adapter"))
    }

    /** The simulator's adapter: only the voltage, for the check. */
    private class SimulatedCheck : com.hybridguard.obd.FakeElmTransport(20) {
        init { batteryVolts = Simulator.volts }
        override fun respond(header: Int, payloadHex: String): String = "NO DATA"
    }

    private suspend fun runSession(s: Settings, startVolts: Double?) = coroutineScope {
        val wl = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HybridGuard:session").apply { acquire(12 * 60 * 60_000L) }
        val runner = buildRunner(s)
        var recorder: SessionRecorder? = null
        var endReason = "stopped"
        var reconnectingSince: Long? = null
        var wasReady = false
        val missingSince = mutableMapOf<String, Long>()
        var lastHistoryMs = 0L
        var lastAlarmKey = ""
        var lastPublishedAlarmKey = ""
        var lastPublishMs = 0L
        var lastPhase: SessionPhase? = null
        var probeSeen: CapabilityReport? = null
        var notGiven: Map<Role, List<String>> = emptyMap()
        var yearAsked = false
        var restarting = false
        val sessionStart = System.currentTimeMillis()
        _status.update {
            it.copy(phase = MonitorPhase.CONNECTING, message = "Connecting", sessionStartEpochMs = sessionStart, session = null, dashboard = null, notGiven = emptyMap())
        }

        // The driver's settings, applied live.
        val settingsJob = launch {
            app.settings.flow.collect { cur ->
                runner.setAlarms(cur.alarmsEnabled)
                runner.setAlarmRules { rules -> if (cur.alarmsEnabled) rules?.withOverrides(cur.alarmOverrides) else null }
                runner.setExtraRoles(cur.shownRoles.filter { it.onDemand }.toSet())
            }
        }
        val stateJob = launch {
            runner.state.collect { st ->
                val cur = app.settings.value
                val nowEpoch = System.currentTimeMillis()
                val mono = SystemClock.elapsedRealtime()
                val dash = st.dashboard
                if (dash?.ready == true) wasReady = true
                // Connection lost while driving: the pumps are not watched.
                reconnectingSince = if (st.phase == SessionPhase.RECONNECTING) reconnectingSince ?: mono else null
                val extra = mutableListOf<Alarm>()
                val lostFor = reconnectingSince?.let { mono - it } ?: 0
                if (cur.lostAlert && wasReady && lostFor >= cur.lostAfterSec * 1000L) {
                    extra += Alarm(
                        "monitor.lost", AlarmLevel.CAUTION, "Monitoring interrupted",
                        "The adapter stopped answering: the pumps are not being watched", st.message ?: "", reconnectingSince!!,
                    )
                }
                // A watched value stopped arriving while READY.
                if (cur.alarmsEnabled && cur.missingAlert && st.phase == SessionPhase.POLLING && dash?.ready == true) {
                    for (w in dash.alarmWatch.filter { it.why == null }) {
                        if (w.watching) missingSince.remove(w.group) else {
                            val since = missingSince.getOrPut(w.group) { mono }
                            if (mono - since >= cur.missingAfterSec * 1000L) {
                                extra += Alarm(
                                    "monitor.missing.${w.group}", AlarmLevel.CAUTION, "Not reading: ${w.group}",
                                    "The car stopped reporting it, so its alarms cannot work", "", since,
                                )
                            }
                        }
                    }
                } else {
                    missingSince.clear()
                }
                val view = updateGate((dash?.alarms.orEmpty()) + extra, cur, mono)
                val alarmKey = view.alarms.joinToString { it.id + it.level } + view.unacknowledged
                if (view.announce != null) {
                    Notifier.announce(app, view.announce!!, view, cur)
                } else if (alarmKey != lastAlarmKey) {
                    // Only when something changed: replies arrive many times a second.
                    if (view.alarms.isEmpty()) Notifier.clearAlarms(app) else Notifier.showAlarms(app, view)
                }
                lastAlarmKey = alarmKey

                if (st.phase == SessionPhase.POLLING && dash != null) {
                    if (recorder == null) {
                        recorder = SessionRecorder(
                            app.archive.newDir(sessionStart),
                            SessionStart(
                                startEpochMs = sessionStart, profileId = st.profile?.id, profileName = st.profile?.name,
                                modelYear = st.modelYear, vin = st.vin, adapter = st.adapter?.stnDevice ?: st.adapter?.elm,
                                startVolts = startVolts, simulated = s.source == DataSource.SIMULATED,
                                appVersion = BuildConfig.VERSION_NAME,
                            ),
                            cur.logRoles, cur.logIntervalSec * 1000L,
                        )
                        st.profile?.id?.let { id -> if (cur.lastProfileId != id) app.settings.update { it.copy(lastProfileId = id) } }
                        val vin = st.vin
                        if (s.source == DataSource.BLUETOOTH && vin != null && cur.lastVin != vin) app.settings.update { it.copy(lastVin = vin) }
                    }
                    // The VIN does not say the model year: ask, quietly (answered in the app, when parked).
                    if (!yearAsked && st.modelYearAssumed && s.source == DataSource.BLUETOOTH && st.vin != null) {
                        yearAsked = true
                        Notifier.askModelYear(app, st.profile?.name, st.modelYear)
                    }
                    if (st.capabilities !== probeSeen) {
                        probeSeen = st.capabilities
                        notGiven = st.catalog?.notGiven((cur.notifRoles + cur.logRoles).distinct(), st.capabilities)
                            ?.mapValues { (_, answers) -> answers.map { it.text } }.orEmpty()
                        recorder?.notGiven = notGiven
                    }
                    recorder?.sample(nowEpoch, dash)
                    if (nowEpoch - lastHistoryMs >= 1000) {
                        lastHistoryMs = nowEpoch
                        _history.update { h -> (h + Sample(nowEpoch, dash.values)).dropWhile { it.epochMs < nowEpoch - HISTORY_MS } }
                    }
                }
                recorder?.alarms(nowEpoch, view.alarms)
                // The screens need a few updates a second, not one per reply.
                val phaseChanged = st.phase != lastPhase
                lastPhase = st.phase
                if (!phaseChanged && alarmKey == lastPublishedAlarmKey && mono - lastPublishMs < 250) return@collect
                lastPublishMs = mono
                lastPublishedAlarmKey = alarmKey
                _status.update {
                    it.copy(
                        phase = if (st.phase == SessionPhase.POLLING) MonitorPhase.MONITORING else MonitorPhase.CONNECTING,
                        message = st.message,
                        session = st,
                        dashboard = dash ?: it.dashboard,
                        alarms = view,
                        notGiven = notGiven,
                    )
                }
            }
        }
        activeRunner = runner
        runner.setBattery(batteryTab)
        val runJob = launch { runner.run() }
        restartSession = { restarting = true; runJob.cancel() }
        var gaveUp = false
        // The adapter fell asleep (or the phone left the car) in the middle of a session.
        val watchdog = launch {
            while (true) {
                delay(5_000)
                val since = reconnectingSince ?: continue
                if (SystemClock.elapsedRealtime() - since > GIVE_UP_RECONNECT_MS) {
                    gaveUp = true
                    runJob.cancel()
                    break
                }
            }
        }
        try {
            endReason = "monitoring stopped"
            runJob.join()
            endReason = when {
                gaveUp -> "the adapter stopped answering"
                restarting -> "model year changed: reading the car again"
                else -> runner.state.value.endReason ?: "stopped"
            }
            val cause = runner.state.value.endCause
            if (!restarting && (gaveUp || cause == SessionEnd.CAR_SILENT || cause == SessionEnd.CAR_OFF || cause == SessionEnd.NO_ANSWER)) {
                afterOff = AfterOff(SystemClock.elapsedRealtime())
            }
        } finally {
            restartSession = null
            activeRunner = null
            settingsJob.cancel()
            stateJob.cancel()
            watchdog.cancel()
            val cur = app.settings.value
            val saved = recorder?.finish(System.currentTimeMillis(), endReason, cur.minSessionSec * 1000L)
            runCatching { app.archive.prune(cur.keepDays, cur.maxSessions, System.currentTimeMillis()) }
            synchronized(gateLock) { gate = AlarmGate(cur.stopRepeatSec * 1000L, cur.remindMin * 60_000L) }
            Notifier.clearAlarms(app)
            _status.update {
                it.copy(
                    session = null, dashboard = null, alarms = AlarmView(), sessionStartEpochMs = null,
                    lastSession = saved ?: it.lastSession,
                )
            }
            if (wl.isHeld) wl.release()
        }
    }

    private fun updateGate(alarms: List<Alarm>, s: Settings, nowMs: Long): AlarmView = synchronized(gateLock) {
        val wanted = s.stopRepeatSec to s.remindMin
        if (gateSettings != wanted) {
            gateSettings = wanted
            gate = AlarmGate(s.stopRepeatSec * 1000L, s.remindMin * 60_000L)
        }
        gate.update(alarms, nowMs)
    }

    private fun buildRunner(s: Settings): SessionRunner {
        var choice: ProfileChoice = s.manualProfileId?.let { ProfileChoice.Manual(it, s.manualModelYear) } ?: ProfileChoice.Auto
        val transportFactory: () -> ObdTransport = when (s.source) {
            DataSource.SIMULATED -> {
                // The simulator answers for one profile, so the session uses the same one.
                val index = app.profileLoader.index()
                val profileId = s.manualProfileId ?: index.profileIds.firstOrNull { it != index.fallbackId } ?: index.fallbackId
                val profile = app.profileLoader.load(profileId)
                val year = s.manualModelYear ?: profile.info.years?.first
                choice = ProfileChoice.Manual(profileId, year)
                val catalog = profile.forModelYear(year)
                fun() = Simulator.newCar(catalog, s.simScenario) { SystemClock.elapsedRealtime() }
            }
            else -> {
                val address = s.deviceAddress ?: error("No adapter selected")
                fun() = SppTransport(app, address)
            }
        }
        return SessionRunner(
            transportFactory = transportFactory,
            loader = app.profileLoader,
            selector = app.profileSelector,
            logDir = app.rawLogDir,
            config = SessionConfig(
                appVersion = BuildConfig.VERSION_NAME,
                source = if (s.source == DataSource.SIMULATED) "simulated" else "bluetooth",
                profileChoice = choice,
                deviceName = s.deviceName,
                readyVolts = s.readyVolts.takeIf { s.voltageGate },
                saveLog = s.rawLog,
                rememberCar = s.source == DataSource.BLUETOOTH,
                allowDiscovery = false,
                mode = SessionMode.DASHBOARD,
            ),
            monotonicMs = { SystemClock.elapsedRealtime() },
            epochMs = { System.currentTimeMillis() },
        )
    }

    private fun setPhase(phase: MonitorPhase, message: String?) {
        _status.update { it.copy(phase = phase, message = message) }
    }

    /**
     * Wakes the phone for the next look even if it went to sleep meanwhile
     * (a coroutine delay alone stops while the CPU sleeps).
     */
    private fun scheduleWake(delayMs: Long) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            app, 10, Intent(app, WakeReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    fun shutdown() = scope.cancel()
}
