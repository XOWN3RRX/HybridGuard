package com.hybridguard.data

import com.hybridguard.obd.AdapterInfo
import com.hybridguard.obd.Elm327
import com.hybridguard.obd.Elm327Config
import com.hybridguard.obd.InitReport
import com.hybridguard.obd.ObdRequest
import com.hybridguard.obd.ObdTransport
import com.hybridguard.obd.PollScheduler
import com.hybridguard.obd.PollingLoop
import com.hybridguard.obd.RawSample
import com.hybridguard.obd.ResponseParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ProfileChoice {
    /** By VIN (remembered manual choice, VIN prefix, or the generic fallback). */
    data object Auto : ProfileChoice

    /** The user's pick; remembered for the car's VIN. */
    data class Manual(val profileId: String, val modelYear: Int? = null) : ProfileChoice
}

/**
 * What a session is for (owner's request 2026-11-13: keep the dashboard
 * apart from the logger).
 */
enum class SessionMode {
    /** Only the requests the dashboard designs need: quick start, fast updates. Discovery off. */
    DASHBOARD,

    /** Every catalog request at its own period, and discovery: for tests and finding new values. */
    LOGGER,
}

data class SessionConfig(
    val appVersion: String,
    /** "bluetooth", "simulated" or "replay": recorded in the log. */
    val source: String,
    val profileChoice: ProfileChoice = ProfileChoice.Auto,
    val deviceName: String? = null,
    /** End the session when no request has succeeded for this long (car switched off). */
    val carSilentAfterMs: Long = 30_000,
    /** Retry delay while the car does not answer at session start. */
    val waitForCarRetryMs: Long = 3_000,
    /** Delays between reconnect attempts; the last one repeats. */
    val reconnectDelaysMs: List<Long> = listOf(2_000, 5_000, 10_000),
    /** Probe again even if this car has a cached probe for the profile version. */
    val forceProbe: Boolean = false,
    /** Tell the adapter to expect one reply for requests the probe saw answer in a single frame (docs/03). */
    val useResponseCount: Boolean = true,
    /** Write a session file. Off for replays: replaying a log must not create another one. */
    val saveLog: Boolean = true,
    /** Remember the profile choice and probe result for the car's VIN. Off for replays. */
    val rememberCar: Boolean = true,
    /** Offer the discovery scan and watch mode (docs/05 Phase 1 task 7). Off for replays. */
    val allowDiscovery: Boolean = true,
    val mode: SessionMode = SessionMode.LOGGER,
    val elm: Elm327Config = Elm327Config(),
)

enum class SessionPhase { CONNECTING, WAITING_FOR_CAR, PROBING, POLLING, RECONNECTING, ENDED }

data class ProbeProgress(val done: Int, val total: Int)

/** Newest sample for one request, decoded if the request is in the catalog. */
data class LiveEntry(val sample: RawSample, val decoded: DecodedSample?)

data class SessionState(
    val source: String,
    val phase: SessionPhase = SessionPhase.CONNECTING,
    val message: String? = null,
    val adapter: AdapterInfo? = null,
    val vin: String? = null,
    val profile: ProfileInfo? = null,
    val modelYear: Int? = null,
    /** [modelYear] is a guess (the profile's first year): nothing told the year, the owner should be asked. */
    val modelYearAssumed: Boolean = false,
    val selectionReason: SelectionReason? = null,
    val catalog: VehicleCatalog? = null,
    val probe: ProbeProgress? = null,
    val capabilities: CapabilityReport? = null,
    val probeWasCached: Boolean = false,
    /** Keyed by [SignalCommand.key]. */
    val live: Map<String, LiveEntry> = emptyMap(),
    val nowMs: Long = 0,
    val samples: Int = 0,
    val okSamples: Int = 0,
    /** Over the last 10 s. */
    val requestsPerSecond: Double = 0.0,
    val polledRequests: Int = 0,
    val reconnects: Int = 0,
    val logFile: File? = null,
    val endReason: String? = null,
    /** The last few requests and raw replies, newest last: shown while waiting so problems are visible in the car. */
    val recent: List<RawSample> = emptyList(),
    /** Discovery scan / watch mode; null until the car has answered. */
    val discovery: DiscoveryState? = null,
    /** What the dashboards show; null until the car has answered. */
    val dashboard: DashboardState? = null,
)

private class CarSilentException : Exception("the car stopped answering")

/**
 * One logging session (docs/05 Phase 1): connect, wait for the car, read VIN
 * and supported PIDs, choose the profile, open the log, run (or reuse) the
 * capability probe, then poll until the car goes silent or the caller
 * cancels. Transport failures reconnect into the same log file.
 *
 * Pure Kotlin: the Android service only supplies the transport and storage.
 */
class SessionRunner(
    private val transportFactory: () -> ObdTransport,
    private val loader: ProfileLoader,
    private val selector: ProfileSelector,
    private val logDir: File,
    private val config: SessionConfig,
    private val monotonicMs: () -> Long,
    private val epochMs: () -> Long,
    private val discoveryStore: DiscoveryStore = InMemoryDiscoveryStore(),
) {
    private val _state = MutableStateFlow(SessionState(source = config.source))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var startMono = 0L
    private fun t(): Long = monotonicMs() - startMono

    @Volatile private var writer: SessionLogWriter? = null
    private var connectedOnce = false
    private var vehicle: VehicleInfo? = null

    /** The engine ECU ignores its own address but answers the broadcast one (seen on a 2019 Corolla Hybrid). */
    private var standardViaBroadcast = false

    /** Catalog key -> (slow request being polled, normal request to restore once it answers). */
    private val slowRequests = mutableMapOf<String, Pair<ObdRequest, ObdRequest>>()
    private var decoder: Decoder? = null
    private var requests: List<ObdRequest> = emptyList()
    private val recentSampleTimes = ArrayDeque<Long>()

    private var catalog: VehicleCatalog? = null
    private var dashboardEngine: DashboardEngine? = null
    private var capabilities: CapabilityReport? = null
    private var discoveryPlan: DiscoveryPlan? = null
    private var discoveryUnavailable: String? = null
    @Volatile private var discoveryProgress: DiscoveryProgress? = null
    private val discoveryMode = MutableStateFlow(DiscoveryMode.IDLE)
    @Volatile private var watchRequests: List<ObdRequest> = emptyList()
    @Volatile private var watchHeaders: Set<Int> = emptySet()
    private val watchLock = Any()
    private var watchKeys: Set<String> = emptySet()
    private var watchMarker: String? = null
    private var watchMarkerT = 0L
    private val watchSeen = mutableSetOf<String>()
    @Volatile private var lastSpeedKmh: Double? = null

    /**
     * Requests polled only while something on screen shows them (dashboard
     * mode): per advanced or detail role, the requests that role reads. The
     * Advanced screen wants every advanced role, a design with detail values
     * every detail role, a chosen tile just its own.
     */
    private var extraByRole: Map<Role, List<ObdRequest>> = emptyMap()
    @Volatile private var advancedWanted = false
    @Volatile private var detailsWanted = false
    @Volatile private var batteryWanted = false
    @Volatile private var rolesWanted: Set<Role> = emptySet()
    @Volatile private var alarmsWanted = false

    /** Roles the cooling alarms read (docs/02 C.7). */
    @Volatile private var alarmRoles: Set<Role> = emptySet()

    /** The profile's own alarm rules, before the driver's settings. */
    private var profileAlarmRules: AlarmRules? = null

    /** Turns the profile's rules into the ones run (the driver's settings); replaced live by [setAlarmRules]. */
    @Volatile private var alarmTransform: (AlarmRules?) -> AlarmRules? = { it?.withOverrides(emptyMap()) }
    @Volatile private var alarmTransformVersion = 0
    private var appliedAlarmTransform = 0
    @Volatile private var wantedVersion = 0
    private var appliedVersion = -1
    private var appliedExtra: Set<ObdRequest> = emptySet()

    /** Runs until the car goes silent or the coroutine is cancelled; returns the closed log file, if any. */
    suspend fun run(): File? {
        startMono = monotonicMs()
        var endReason = "stopped"
        var failures = 0
        try {
            while (true) {
                try {
                    connectAndPoll { failures = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CarSilentException) {
                    endReason = "car stopped answering for ${config.carSilentAfterMs / 1000} s"
                    break
                } catch (e: ProfileFormatException) {
                    endReason = "profile error: ${e.message}"
                    break
                } catch (e: Exception) {
                    // Stopped by the user while a transport error was in flight: that is a stop, not a dropout.
                    currentCoroutineContext().ensureActive()
                    val delayMs = config.reconnectDelaysMs[minOf(failures, config.reconnectDelaysMs.lastIndex)]
                    failures++
                    val reason = e.message ?: e.javaClass.simpleName
                    writer?.event(t(), "disconnected", reason)
                    _state.update {
                        it.copy(
                            phase = SessionPhase.RECONNECTING,
                            message = "Connection lost ($reason). Retrying in ${delayMs / 1000} s",
                            reconnects = it.reconnects + if (connectedOnce) 1 else 0,
                        )
                    }
                    delay(delayMs)
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { saveDiscovery() }
                val file = writer?.close(
                    SessionSummary(t(), _state.value.samples, _state.value.okSamples, _state.value.reconnects, endReason),
                )
                writer = null
                _state.update { it.copy(phase = SessionPhase.ENDED, message = endReason, endReason = endReason, logFile = file ?: it.logFile) }
            }
        }
        return _state.value.logFile
    }

    /** Adds a marker line to the log ("now in R", "EV button pressed"). */
    fun marker(text: String) {
        val now = t()
        writer?.event(now, "marker", text)
        if (discoveryMode.value == DiscoveryMode.WATCH) {
            synchronized(watchLock) {
                watchMarker = text
                watchMarkerT = now
                watchSeen.clear()
            }
            publishDiscovery(_state.value.discovery?.message)
        }
    }

    /**
     * Starts or resumes the discovery scan. It only sends requests while the
     * car reports 0 km/h, checked right before every chunk. Returns false
     * if discovery is not available in this session yet.
     */
    fun startScan(): Boolean = setDiscoveryMode(DiscoveryMode.SCAN)

    /**
     * Polls every identifier the scan found on the ECUs [headers] (all when
     * null) until [stopDiscovery]. Normal polling pauses meanwhile, except
     * speed, so each identifier comes round as often as the link allows.
     */
    fun startWatch(headers: Set<Int>? = null): Boolean {
        if (discoveryMode.value == DiscoveryMode.WATCH) return false
        val found = discoveryProgress?.answered.orEmpty()
        val chosen = headers ?: found.map { it.header }.toSet()
        if (found.none { it.header in chosen }) return false
        watchHeaders = chosen
        return setDiscoveryMode(DiscoveryMode.WATCH)
    }

    /** Pauses the scan (progress is kept) or ends watch mode. */
    fun stopDiscovery() {
        discoveryMode.value = DiscoveryMode.IDLE
    }

    /** Forgets this car's scan progress and results (the session logs keep every reply). */
    fun resetScan(): Boolean {
        val plan = discoveryPlan ?: return false
        if (discoveryMode.value != DiscoveryMode.IDLE) return false
        discoveryProgress = DiscoveryProgress(plan.fingerprint)
        val v = vehicle
        if (v?.vin != null && config.rememberCar) discoveryStore.clear(v.vin, v.profileId)
        writer?.event(t(), "discovery", "scan progress reset")
        publishDiscovery(null)
        return true
    }

    private fun setDiscoveryMode(mode: DiscoveryMode): Boolean {
        val progress = discoveryProgress
        if (discoveryUnavailable != null || progress == null || _state.value.phase == SessionPhase.ENDED) return false
        if (mode == DiscoveryMode.WATCH && progress.answered.isEmpty()) return false
        discoveryMode.value = mode
        return true
    }

    /** Phone GPS cross-check, about once per second. */
    fun gps(speedKmh: Double?, accuracyM: Double?) {
        writer?.gps(t(), speedKmh, accuracyM)
    }

    private suspend fun connectAndPoll(onPolling: () -> Unit): Nothing = coroutineScope {
        _state.update { it.copy(phase = if (connectedOnce) SessionPhase.RECONNECTING else SessionPhase.CONNECTING, message = "Connecting to the adapter") }
        val elm = Elm327(transportFactory(), this, ::t, config.elm)
        try {
            val init = elm.open()
            _state.update { it.copy(adapter = init.adapter) }
            if (!connectedOnce) {
                connectedOnce = true
                openLog(init)
            } else {
                writer?.event(t(), "reconnected")
            }
            if (vehicle == null) startSession(elm)
            onPolling()
            poll(elm)
        } finally {
            withContext(NonCancellable) { runCatching { elm.close() } }
        }
    }

    /** The log starts with the adapter, so a car that never answers still leaves a record of its replies. */
    private fun openLog(init: InitReport) {
        if (!config.saveLog) return
        logDir.mkdirs()
        val file = File(logDir, "session-" + FILE_DATE.get()!!.format(Date(epochMs())) + LogFormat.OPEN_SUFFIX)
        val meta = SessionMeta(
            appVersion = config.appVersion,
            source = config.source,
            startEpochMs = epochMs(),
            adapter = init.adapter,
            initSteps = init.steps,
            deviceName = config.deviceName,
        )
        writer = SessionLogWriter(file, meta)
        _state.update { it.copy(logFile = file) }
    }

    private suspend fun startSession(elm: Elm327) {
        // 1. Wait until the car answers the standard VIN or PID-bitmap request.
        var vinSample: RawSample
        var bitmaps: List<RawSample>
        var attempt = 0
        _state.update { it.copy(phase = SessionPhase.WAITING_FOR_CAR, message = "Asking the car for its VIN") }
        while (true) {
            attempt++
            vinSample = elm.read(ObdRequest.fromHex(ENGINE_HEADER, "0902", 1_000, multiFrame = true)).also(::record)
            bitmaps = readBitmaps(elm)
            if (vinSample.status == RawSample.Status.OK || bitmaps.any { it.status == RawSample.Status.OK }) break

            // Also try the broadcast address: separates "car silent" from "engine ECU address not answering".
            val broadcast = elm.read(ObdRequest.fromHex(ObdRequest.FUNCTIONAL_HEADER, "0100", 1_000)).also(::record)
            if (broadcast.status == RawSample.Status.OK) {
                writer?.event(
                    t(), "note",
                    "the car answers the broadcast address 7DF but not the engine ECU address 7E0: " +
                        "standard requests (services 01, 09) go to 7DF for this session",
                )
                standardViaBroadcast = true
                vinSample = elm.read(ObdRequest.fromHex(ObdRequest.FUNCTIONAL_HEADER, "0902", 1_000, multiFrame = true)).also(::record)
                bitmaps = readBitmaps(elm, ObdRequest.FUNCTIONAL_HEADER)
                break
            }
            _state.update {
                it.copy(message = "The car is not answering (attempt $attempt). Is it in READY? The last replies are below.")
            }
            delay(config.waitForCarRetryMs)
        }

        // 2. Profile.
        val vin = parseVin(vinSample)
        val selection = when (val choice = config.profileChoice) {
            ProfileChoice.Auto -> selector.select(vin)
            is ProfileChoice.Manual -> {
                val year = choice.modelYear ?: vin?.let { selector.select(it).modelYear }
                // A manual profile without a year keeps the year the owner gave for this car.
                if (vin != null && config.rememberCar) {
                    selector.chooseManually(vin, choice.profileId, choice.modelYear ?: selector.ownerModelYear(vin))
                }
                ProfileSelection(choice.profileId, year, SelectionReason.MANUAL)
            }
        }
        val profile = loader.load(selection.profileId)
        // Nothing gives the model year (most European VINs do not encode it) and the owner has not
        // said it yet: assume the profile's first year, so year-limited signals are still read,
        // until the owner answers the app's question.
        val modelYearAssumed = selection.modelYear == null && profile.info.years != null
        val modelYear = selection.modelYear ?: profile.info.years?.first
        val catalog = profile.forModelYear(modelYear)
        val supported = SupportedPids.parse(bitmaps)
        decoder = Decoder(catalog)
        this.catalog = catalog
        profileAlarmRules = loader.alarmRules(profile.info.id)
        appliedAlarmTransform = alarmTransformVersion
        val alarmRules = alarmTransform(profileAlarmRules)
        alarmRoles = alarmRules?.roles.orEmpty()
        dashboardEngine = DashboardEngine(
            catalog, loader.warmUpRules(profile.info.id), loader.displayRanges(profile.info.id), alarms = alarmRules,
        )

        // 3. Record what the car is.
        val w = writer // null when the session keeps no log (replay)
        val info = VehicleInfo(
            vin = vin,
            profileId = profile.info.id,
            profileVersion = profile.info.version,
            modelYear = modelYear,
            selectionReason = selection.reason.name,
            supportedPids = supported,
        )
        w?.vehicle(t(), info)
        vehicle = info
        profile.warnings.forEach { w?.event(t(), "profileWarning", it) }
        if (modelYearAssumed) w?.event(t(), "note", "model year unknown (not in the VIN): assuming $modelYear until the owner says")
        _state.update {
            it.copy(
                vin = vin, profile = profile.info, modelYear = modelYear, modelYearAssumed = modelYearAssumed,
                selectionReason = selection.reason, catalog = catalog,
            )
        }

        // 4. Capability probe of the requests this mode uses, or the cached result for this
        //    car and profile version. A dashboard session probes only its own requests and
        //    merges them into the saved report, so a later logger session probes only the rest.
        val roleKeys = catalog.roleCommandKeys
        val advancedKeys = catalog.advancedCommandKeys
        // The Advanced screen's requests are probed too, so opening it later needs no probe.
        val inScope = catalog.pollable(null)
            .filter { pidSupported(it.service, hexToBytes(it.parameterHex), supported) }
            .filter { config.mode == SessionMode.LOGGER || it.key in roleKeys || it.key in advancedKeys }
        val stored = vin?.takeIf { config.rememberCar }?.let(selector::capabilities)
            ?.takeIf { it.profileId == profile.info.id && it.profileVersion == profile.info.version }
        val cached = stored?.takeIf { !config.forceProbe }
        val toProbe = inScope.filter { cached?.results?.containsKey(it.key) != true }
        val report = if (cached != null && toProbe.isEmpty()) {
            w?.event(t(), "probeCached", "probed at ${cached.probedAtEpochMs}")
            cached
        } else {
            val probed = probe(elm, toProbe, profile.info)
            val merged = if (stored != null) probed.copy(results = stored.results + probed.results) else probed
            vin?.takeIf { config.rememberCar }?.let { selector.saveCapabilities(it, merged) }
            merged
        }
        w?.probe(t(), report)
        capabilities = report
        _state.update { it.copy(capabilities = report, probeWasCached = cached != null) }
        setUpDiscovery(vin, profile.info.id, catalog)

        // 5. What to poll. Requests that got no reply at all are retried slowly, so they
        //    do not eat the link's time; they return to their own period once they answer.
        //    Unless full-rate logging is on, requests no dashboard value needs are only
        //    logged in the background, so the dashboard's values come round quickly.
        slowRequests.clear()
        val candidates = catalog.pollable(report)
            .filter { pidSupported(it.service, hexToBytes(it.parameterHex), supported) }
        // Dashboard mode: each role reads only its first signal that answered; the fallbacks
        // wait. The main values are always polled; the detail values only while a design
        // that shows them is on screen, the Advanced screen's only while it is open.
        val answered = { key: String -> report.results[key]?.answered == true }
        val mainKeys = catalog.pollKeys({ !it.onDemand }, answered)
        fun extra(keys: Set<String>) =
            candidates.filter { it.key in keys && answered(it.key) }.mapNotNull { requestFor(it, report.results[it.key]) }
        extraByRole = if (config.mode == SessionMode.LOGGER) {
            emptyMap()
        } else {
            Role.entries.filter { it.onDemand }
                .associateWith { role -> extra(catalog.pollKeys({ it == role }, answered) - mainKeys) }
                .filterValues { it.isNotEmpty() }
        }
        requests = candidates
            .filter { config.mode == SessionMode.LOGGER || it.key in mainKeys }
            .mapNotNull { command ->
                val result = report.results[command.key]
                val request = requestFor(command, result) ?: return@mapNotNull null
                if (result?.silent == true && request.periodMs < SILENT_PERIOD_MS) {
                    request.withPeriod(SILENT_PERIOD_MS).also { slowRequests[command.key] = it to request }
                } else {
                    request
                }
            }
            .ifEmpty { listOf(ObdRequest.fromHex(ENGINE_HEADER, "0100", 1_000)) } // keep car-off detection alive
        if (slowRequests.isNotEmpty()) {
            w?.event(t(), "slowPolling", "${slowRequests.size} requests without reply are retried every ${SILENT_PERIOD_MS / 1000} s")
        }
        w?.event(
            t(), "pollPlan",
            when (config.mode) {
                SessionMode.DASHBOARD -> "dashboard mode: ${requests.size} requests the dashboard needs"
                SessionMode.LOGGER -> "logger mode: all ${requests.size} requests at their own period"
            },
        )
    }

    /**
     * The request actually sent for a catalog command: routed to the broadcast
     * address if the engine ECU ignores its own, and with a response count of 1
     * when the probe saw a single-frame reply (the adapter then returns at once).
     */
    private fun requestFor(command: SignalCommand, result: CapabilityResult?): ObdRequest? {
        val base = command.toRequest() ?: return null
        val routed = standardViaBroadcast && command.header == ENGINE_HEADER && (command.service == 0x01 || command.service == 0x09)
        val count = if (config.useResponseCount && result?.answered == true && result.frames == 1) 1 else null
        if (!routed && count == null) return base
        return ObdRequest(
            header = if (routed) ObdRequest.FUNCTIONAL_HEADER else base.header,
            service = base.service,
            pid = base.pid,
            periodMs = base.periodMs,
            multiFrame = base.multiFrame,
            receiveHeader = if (routed) command.receiveAddress ?: (ENGINE_HEADER + 8) else null,
            expectedResponses = count,
        )
    }

    private suspend fun readBitmaps(elm: Elm327, header: Int = ENGINE_HEADER): List<RawSample> {
        val samples = mutableListOf<RawSample>()
        for (base in SupportedPids.BITMAP_PIDS) {
            val s = elm.read(ObdRequest.fromHex(header, "01%02X".format(base), 1_000))
            record(s)
            samples += s
            val supported = SupportedPids.parse(listOf(s)) ?: break
            if (!SupportedPids.nextBitmapSupported(supported, base)) break
        }
        return samples
    }

    private suspend fun probe(elm: Elm327, toProbe: List<SignalCommand>, info: ProfileInfo): CapabilityReport {
        val results = mutableListOf<Pair<String, RawSample>>()
        _state.update { it.copy(phase = SessionPhase.PROBING, message = "Checking which requests this car answers", probe = ProbeProgress(0, toProbe.size)) }
        for ((i, command) in toProbe.withIndex()) {
            val s = elm.read(requestFor(command, null)!!)
            record(s)
            results += command.key to s
            _state.update { it.copy(probe = ProbeProgress(i + 1, toProbe.size)) }
        }
        return CapabilityReport.fromResults(info, epochMs(), results)
    }

    private suspend fun poll(elm: Elm327): Nothing = coroutineScope {
        _state.update {
            it.copy(phase = SessionPhase.POLLING, message = "Reading ${requests.size} requests", polledRequests = requests.size)
        }
        var lastOk = t()
        val scheduler = PollScheduler(requests, t())
        appliedExtra = emptySet()
        appliedVersion = -1
        val loop = PollingLoop(elm, scheduler, ::t) { sample ->
            // Runs in the polling loop itself, so the scheduler is only ever touched from here.
            syncAlarmRules()
            syncExtra(scheduler)
            val decoded = record(sample)
            if (sample.status == RawSample.Status.OK) {
                lastOk = sample.tMs
                decoded?.let { resumeIfSlow(it.commandKey, scheduler) }
                watchSampleSeen(sample)
            } else if (t() - lastOk > config.carSilentAfterMs) {
                throw CarSilentException()
            }
        }
        launch { discoveryDriver(elm, loop, scheduler) }
        loop.run()
    }

    /**
     * Opens or closes the Advanced screen's polling (inverter, pumps). Dashboard
     * mode only; takes effect with the next reply, and again after a reconnect.
     */
    fun setAdvanced(on: Boolean) {
        advancedWanted = on
        wantedVersion++
    }

    /**
     * Starts or stops polling the detail values (fuel level, oil, battery
     * temperature and fan, outside temperature, 12 V): on while a design that
     * shows them is on screen. Dashboard mode only; takes effect with the next
     * reply, and again after a reconnect.
     */
    fun setDetails(on: Boolean) {
        detailsWanted = on
        wantedVersion++
    }

    /**
     * Starts or stops polling the Battery tab's values (cell voltages and
     * resistances, power limits, 12 V details): on only while that tab is
     * open, so the long cell replies never slow the cooling watch. Dashboard
     * mode only; takes effect with the next reply, and again after a reconnect.
     */
    fun setBattery(on: Boolean) {
        batteryWanted = on
        wantedVersion++
    }

    /**
     * Advanced or detail roles a screen shows on its own (e.g. a dashboard
     * tile the driver chose): polled on top of the main values. Dashboard
     * mode only; takes effect with the next reply, and again after a reconnect.
     */
    fun setExtraRoles(roles: Set<Role>) {
        rolesWanted = roles
        wantedVersion++
    }

    /**
     * Cooling alarms on or off (docs/02 C.7): while on, the roles the alarms
     * read (pump speeds, inverter coolant) are polled all the time, whatever
     * is on screen. Dashboard mode only; takes effect with the next reply,
     * and again after a reconnect.
     */
    fun setAlarms(on: Boolean) {
        alarmsWanted = on
        wantedVersion++
    }

    /**
     * The driver changed the alarm settings: [transform] turns the profile's
     * rules into the ones run. Applied with the next reply; the roles the new
     * rules read are polled from then on.
     */
    fun setAlarmRules(transform: (AlarmRules?) -> AlarmRules?) {
        alarmTransform = transform
        alarmTransformVersion++
    }

    private fun syncAlarmRules() {
        val version = alarmTransformVersion
        if (version == appliedAlarmTransform) return
        val engine = dashboardEngine ?: return
        appliedAlarmTransform = version
        val rules = alarmTransform(profileAlarmRules)
        engine.setAlarmRules(rules)
        alarmRoles = rules?.roles.orEmpty()
        wantedVersion++
        writer?.event(t(), "alarms", "settings changed: ${rules?.checks?.size ?: 0} checks")
    }

    private fun syncExtra(scheduler: PollScheduler) {
        val version = wantedVersion
        if (version == appliedVersion) return
        appliedVersion = version
        val roles = rolesWanted +
            (if (alarmsWanted) alarmRoles else emptySet()) +
            (if (advancedWanted) Role.entries.filter { it.advanced } else emptyList()) +
            (if (detailsWanted) Role.entries.filter { it.detail } else emptyList()) +
            (if (batteryWanted) Role.BATTERY_TAB else emptySet())
        val wanted = roles.flatMap { extraByRole[it].orEmpty() }.toSet()
        if (wanted == appliedExtra) return
        (appliedExtra - wanted).forEach { scheduler.remove(it) }
        (wanted - appliedExtra).forEach { scheduler.add(it, t()) }
        appliedExtra = wanted
        writer?.event(t(), "extra", "polling ${wanted.size} on-screen requests: ${roles.joinToString { it.key }.ifEmpty { "none" }}")
    }

    /** A request that was silent in the probe has answered: poll it at its own period again. */
    private fun resumeIfSlow(key: String, scheduler: PollScheduler) {
        val (slow, normal) = slowRequests.remove(key) ?: return
        scheduler.replace(slow, normal, t())
        requests = requests.map { if (it == slow) normal else it }
        writer?.event(t(), "resumed", "$key answers now: polled every ${normal.periodMs} ms again")
    }

    /** @param live false for scan replies: thousands of one-off requests would bury the Live tab. */
    private fun record(sample: RawSample, live: Boolean = true): DecodedSample? {
        writer?.sample(sample)
        val decoded = decoder?.decode(sample)
        val now = t()
        recentSampleTimes.addLast(now)
        while (recentSampleTimes.isNotEmpty() && recentSampleTimes.first() < now - RATE_WINDOW_MS) recentSampleTimes.removeFirst()
        val window = minOf(now, RATE_WINDOW_MS).coerceAtLeast(1)
        // Keyed by catalog command, so a request sent via the broadcast address shows with its signals.
        val key = decoded?.commandKey ?: commandKey(sample.header, sample.request)
        val engine = dashboardEngine
        if (engine != null && decoded != null && decoded.status == RawSample.Status.OK) engine.update(decoded)
        val dashboard = engine?.state(now, sourceBadge = config.source.takeIf { it != LIVE_SOURCE })
        _state.update {
            it.copy(
                live = if (live) it.live + (key to LiveEntry(sample, decoded)) else it.live,
                nowMs = now,
                samples = it.samples + 1,
                okSamples = it.okSamples + if (sample.status == RawSample.Status.OK) 1 else 0,
                requestsPerSecond = recentSampleTimes.size * 1000.0 / window,
                recent = (it.recent + sample).takeLast(RECENT_SAMPLES),
                dashboard = dashboard ?: it.dashboard,
            )
        }
        return decoded
    }

    // ---- Discovery (docs/05 Phase 1 task 7) ----

    private fun setUpDiscovery(vin: String?, profileId: String, catalog: VehicleCatalog) {
        val allowed = config.allowDiscovery && config.mode == SessionMode.LOGGER
        val plan = if (allowed) loader.discoveryPlan(profileId) else null
        discoveryPlan = plan
        discoveryUnavailable = when {
            !config.allowDiscovery -> "Not available in a replay"
            config.mode != SessionMode.LOGGER -> "Discovery runs in a Logger session: choose Logger on the Connect tab"
            plan == null -> "The profile ${catalog.profile.name} has no discovery plan"
            catalog.commandForRole(Role.SPEED) == null -> "The profile has no speed signal: the scan needs 0 km/h to run"
            else -> null
        }
        if (plan != null) {
            val saved = vin?.takeIf { config.rememberCar }?.let { discoveryStore.get(it, profileId) }
            discoveryProgress = saved?.takeIf { it.planFingerprint == plan.fingerprint } ?: DiscoveryProgress(plan.fingerprint)
            if (saved != null && saved.planFingerprint != plan.fingerprint) {
                writer?.event(t(), "discovery", "the scan plan changed: saved progress discarded")
            }
        }
        publishDiscovery(null)
    }

    private fun saveDiscovery() {
        val p = discoveryProgress ?: return
        val v = vehicle ?: return
        if (v.vin != null && config.rememberCar) discoveryStore.put(v.vin, v.profileId, p)
    }

    private fun publishDiscovery(message: String?) {
        val plan = discoveryPlan
        val p = discoveryProgress
        val state = if (plan == null || p == null || discoveryUnavailable != null) {
            DiscoveryState(unavailableReason = discoveryUnavailable ?: "No discovery plan")
        } else {
            val next = p.nextToSend(plan)
            DiscoveryState(
                mode = discoveryMode.value,
                message = message,
                done = plan.size - p.remaining(plan),
                total = plan.size,
                currentRange = if (next < plan.size) plan.rangeAt(next).label else null,
                finished = next >= plan.size,
                answered = p.answered,
                refused = p.refused,
                notFound = p.notFound,
                silent = p.silent,
                skipped = p.skippedDescriptions(plan),
                watchCount = watchRequests.size,
                speedKmh = lastSpeedKmh,
                ecus = plan.ranges.map { it.header to it.ecuName }.distinct().map { (h, name) ->
                    DiscoveryEcu(h, name, p.answered.count { it.header == h })
                },
                watchHeaders = watchHeaders,
                watchMarker = synchronized(watchLock) { watchMarker },
                watchReadSinceMarker = synchronized(watchLock) { watchSeen.size },
            )
        }
        _state.update { it.copy(discovery = state) }
    }

    /**
     * Runs the requested discovery mode for one connection. Lives inside the
     * connection's scope: a dropout cancels it and the reconnect starts it
     * again where it was. Everything that touches the scheduler runs in the
     * polling loop's priority lane, between two polling requests.
     */
    private suspend fun discoveryDriver(elm: Elm327, loop: PollingLoop, scheduler: PollScheduler) {
        if (discoveryUnavailable != null) return
        watchRequests = emptyList() // a new connection has a new scheduler
        discoveryMode.collectLatest { mode ->
            if (mode != DiscoveryMode.WATCH && watchRequests.isNotEmpty()) {
                val stale = watchRequests
                loop.runPriority {
                    stale.forEach(scheduler::remove)
                    val now = t()
                    requests.forEach { scheduler.add(it, now) } // normal polling resumes
                }
                watchRequests = emptyList()
                synchronized(watchLock) {
                    watchKeys = emptySet()
                    watchMarker = null
                    watchSeen.clear()
                }
                writer?.event(t(), "discovery", "watch stopped")
            }
            when (mode) {
                DiscoveryMode.IDLE -> publishDiscovery(null)
                DiscoveryMode.SCAN -> scan(elm, loop)
                DiscoveryMode.WATCH -> watch(loop, scheduler)
            }
        }
    }

    private sealed interface ChunkResult {
        data object Sent : ChunkResult
        data object NoSpeed : ChunkResult
        data class Moving(val kmh: Double) : ChunkResult
    }

    private suspend fun scan(elm: Elm327, loop: PollingLoop) {
        val plan = discoveryPlan ?: return
        writer?.event(t(), "discovery", "scan started at request ${discoveryProgress!!.nextToSend(plan)} of ${plan.size}")
        publishDiscovery("Scanning")
        while (true) {
            if (discoveryProgress!!.finished(plan)) {
                saveDiscovery()
                val p = discoveryProgress!!
                writer?.event(t(), "discovery", "scan complete: ${p.answered.size} answered, ${p.refused.size} refused")
                discoveryMode.value = DiscoveryMode.IDLE
                publishDiscovery("Scan complete")
                return
            }
            when (val result = loop.runPriority { scanChunk(elm, plan) }) {
                ChunkResult.Sent -> {
                    publishDiscovery("Scanning")
                    delay(SCAN_GAP_MS) // let normal polling run: live values and car-off detection
                }
                ChunkResult.NoSpeed -> {
                    publishDiscovery("Paused: no speed reading from the car. The scan only runs at 0 km/h.")
                    delay(SCAN_PAUSED_RETRY_MS)
                }
                is ChunkResult.Moving -> {
                    publishDiscovery("Paused: the car is moving (%.0f km/h). The scan continues at 0 km/h.".format(result.kmh))
                    delay(SCAN_PAUSED_RETRY_MS)
                }
            }
        }
    }

    /** Runs in the priority lane: a fresh speed reading, then up to [SCAN_CHUNK] scan requests if it is 0 km/h. */
    private suspend fun scanChunk(elm: Elm327, plan: DiscoveryPlan): ChunkResult {
        val speed = readSpeed(elm)
        lastSpeedKmh = speed
        if (speed == null) return ChunkResult.NoSpeed
        if (speed > 0.0) return ChunkResult.Moving(speed)
        try {
            repeat(SCAN_CHUNK) {
                val p = discoveryProgress!!
                val index = p.nextToSend(plan)
                if (index >= plan.size) return ChunkResult.Sent
                val sample = elm.read(plan.requestAt(index))
                record(sample, live = false)
                val (next, note) = p.record(plan, index, sample)
                discoveryProgress = next
                note?.let { writer?.event(t(), "discovery", it) }
            }
        } finally {
            // Here, not in scan(): a chunk finishes even when the scan was paused while it ran.
            saveDiscovery()
            publishDiscovery(_state.value.discovery?.message)
        }
        return ChunkResult.Sent
    }

    /** Speed from the profile's speed role, read now; null if the car gave no usable value. */
    private suspend fun readSpeed(elm: Elm327): Double? {
        val (command, signalId) = catalog?.commandForRole(Role.SPEED) ?: return null
        val request = requestFor(command, capabilities?.results?.get(command.key)) ?: return null
        val decoded = record(elm.read(request)) ?: return null
        return (decoded.values[signalId] as? SignalValue.Number)?.value
    }

    private suspend fun watch(loop: PollingLoop, scheduler: PollScheduler) {
        val headers = watchHeaders
        val found = discoveryProgress?.answered.orEmpty().filter { it.header in headers }
        val watch = found.map { ObdRequest.fromHex(it.header, it.request, WATCH_PERIOD_MS, multiFrame = true) }
        // Keep speed (car moving or not, in the log); everything else waits until watching stops.
        val speedKey = catalog?.commandForRole(Role.SPEED)?.first?.key
        val paused = requests.filter { r -> catalog?.commandFor(r.header, r.payloadHex)?.key != speedKey }
        synchronized(watchLock) {
            watchKeys = found.map { it.key }.toSet()
            watchMarker = null
            watchSeen.clear()
        }
        loop.runPriority {
            paused.forEach(scheduler::remove)
            val now = t()
            watch.forEach { scheduler.add(it, now) }
        }
        watchRequests = watch
        writer?.event(
            t(), "discovery",
            "watch started: ${watch.size} requests on " + headers.sorted().joinToString(" ") { "%03X".format(it) } +
                "; ${paused.size} normal requests paused",
        )
        publishDiscovery("Watching ${watch.size} identifiers")
        awaitCancellation()
    }

    /** Counts the watched identifiers read since the last marker, so the owner knows when a round is complete. */
    private fun watchSampleSeen(sample: RawSample) {
        if (watchRequests.isEmpty()) return
        val changed = synchronized(watchLock) {
            val key = commandKey(sample.header, sample.request)
            watchMarker != null && sample.tMs >= watchMarkerT && key in watchKeys && watchSeen.add(key)
        }
        if (changed) publishDiscovery(_state.value.discovery?.message)
    }

    private fun pidSupported(service: Int, pid: ByteArray, supported: Set<Int>?): Boolean =
        service != 0x01 || supported == null || pid.isEmpty() || (pid[0].toInt() and 0xFF) in supported

    /** VIN from a `09 02` reply; with several ECUs answering, the engine ECU's (7E8) is preferred. */
    private fun parseVin(sample: RawSample): String? {
        if (sample.status != RawSample.Status.OK) return null
        val messages = ResponseParser.parse(sample.request, sample.response).messages.sortedBy { if (it.canId == ENGINE_HEADER + 8) 0 else 1 }
        return messages.firstNotNullOfOrNull { message ->
            val text = message.payload.drop(3).map { (it.toInt() and 0xFF).toChar() }.filter { it.isLetterOrDigit() }.joinToString("")
            text.takeLast(17).takeIf { Vin.isValid(it) }
        }
    }

    private companion object {
        /** ISO 15765-4 physical address of the engine ECU: where every OBD-II car answers VIN and PID bitmaps. */
        const val ENGINE_HEADER = 0x7E0
        const val RATE_WINDOW_MS = 10_000L
        const val RECENT_SAMPLES = 8

        /** [SessionConfig.source] for a real car: no "simulated" / "replay" badge. */
        const val LIVE_SOURCE = "bluetooth"
        const val SCAN_CHUNK = 20
        const val SCAN_GAP_MS = 300L
        const val SCAN_PAUSED_RETRY_MS = 2_000L
        const val WATCH_PERIOD_MS = 1_000L

        /** Retry period for requests that got no reply in the probe. */
        const val SILENT_PERIOD_MS = 30_000L
        val FILE_DATE: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US) }
    }
}
