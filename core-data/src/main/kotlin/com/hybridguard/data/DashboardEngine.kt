package com.hybridguard.data

/**
 * Turns decoded samples into a [DashboardState] (docs/02 section C, docs/05
 * Phase 2 task 1): keeps the newest reading per signal, resolves roles,
 * derives power, and integrates the trip (distance, fuel, EV distance).
 *
 * Pure and single-threaded: the session feeds it in sample order.
 *
 * @param petrolDensityGPerL grams of petrol per litre, to turn the fuel mass
 *   rate into litres (docs/02 C.4; tune against real fill-ups).
 * @param alarms the profile's cooling alarms (`alarms.json`); null = none.
 */
class DashboardEngine(
    private val catalog: VehicleCatalog,
    private val warmUp: WarmUpRules? = null,
    private val ranges: DisplayRanges = DisplayRanges(),
    private val petrolDensityGPerL: Double = 745.0,
    alarms: AlarmRules? = null,
) {
    /** Cooling alarms (docs/02 C.7), run on every state. Replaced when the driver changes the settings. */
    private var alarmMonitor = alarms?.let(::AlarmMonitor)

    /** New alarm rules (the driver's settings); alarms on now start over. */
    fun setAlarmRules(rules: AlarmRules?) {
        alarmMonitor = rules?.let(::AlarmMonitor)
    }
    private val roles = catalog.roleResolver
    private val latest = mutableMapOf<String, SignalReading>()

    /** Time between the last two readings of each signal: how often it really arrives. */
    private val interval = mutableMapOf<String, Long>()
    private val periodBySignal: Map<String, Long> =
        catalog.commands.flatMap { c -> c.signals.map { it.id to c.periodMs } }.toMap()

    private var lastSpeed: SignalReading? = null
    private var lastFuel: SignalReading? = null
    private var tripKm = 0.0
    private var evKm = 0.0
    private var fuelGrams = 0.0
    private var fuelSeen = false
    private var durationMs = 0L
    private var movingMs = 0L
    private var maxSpeed: Double? = null
    private var batteryOutKwh = 0.0
    private var batteryInKwh = 0.0
    private var batterySeen = false

    /** Highest warm-up level reached while the temperatures kept arriving: the advice only goes up. */
    private var warmUpReached: WarmUpLevel? = null

    /** READY's signal ids, and the requests that carry them. */
    private val readyIds: Set<String> = roles.signalsFor(Role.READY).toSet()
    private val readyIdsByCommand: Map<String, List<String>> = catalog.commands
        .associate { c -> c.key to c.signals.map { it.id }.filter { it in readyIds } }
        .filterValues { it.isNotEmpty() }

    /** Failed replies in a row, per request that carries READY. */
    private val readyFailures = mutableMapOf<String, Int>()

    /**
     * The request [commandKey] got no usable reply (no data, timeout, refused).
     * Values are normally held until they go stale, but READY is not held through
     * silence: a car switched off often stops answering instead of saying "not
     * READY", so after [READY_FAILED_REPLIES] failed replies in a row READY is
     * unknown, and the checks that run only while READY stop.
     */
    fun noReply(commandKey: String) {
        val ids = readyIdsByCommand[commandKey] ?: return
        val failures = (readyFailures[commandKey] ?: 0) + 1
        readyFailures[commandKey] = failures
        if (failures >= READY_FAILED_REPLIES) ids.forEach { latest.remove(it) }
    }

    fun update(decoded: DecodedSample) {
        readyFailures.remove(decoded.commandKey)
        for ((id, value) in decoded.values) {
            val reading = SignalReading(decoded.tMs, id, value)
            // Hold the last good value through a momentary "no value" (e.g. the shift lever
            // between positions sends a code that maps to nothing): it still goes stale normally.
            val previous = latest[id]
            if (!usable(value) && previous != null && usable(previous.value) && fresh(previous, decoded.tMs)) continue
            latest[id]?.let { previous -> if (decoded.tMs > previous.tMs) interval[id] = decoded.tMs - previous.tMs }
            latest[id] = reading
            if (id in roles.signalsFor(Role.SPEED) && id == firstWithValue(Role.SPEED)) onSpeed(reading)
            if (id in roles.signalsFor(Role.FUEL_MASS_RATE) && id == firstWithValue(Role.FUEL_MASS_RATE)) onFuel(reading)
        }
    }

    private fun usable(v: SignalValue) = v is SignalValue.Number || v is SignalValue.Enumerated

    private fun firstWithValue(role: Role): String? =
        roles.signalsFor(role).firstOrNull { (latest[it]?.value as? SignalValue.Number) != null }

    private fun onSpeed(reading: SignalReading) {
        val v = (reading.value as? SignalValue.Number)?.value ?: return
        val previous = lastSpeed
        lastSpeed = reading
        maxSpeed = maxOf(maxSpeed ?: v, v)
        val v0 = (previous?.value as? SignalValue.Number)?.value ?: return
        val dtMs = reading.tMs - previous.tMs
        if (dtMs <= 0 || dtMs > MAX_GAP_MS) return
        val km = (v0 + v) / 2 * dtMs / 3_600_000.0
        tripKm += km
        durationMs += dtMs
        if (v0 > 0 || v > 0) movingMs += dtMs
        val rpm = number(Role.ENGINE_RPM, reading.tMs)
        if (rpm != null && rpm < ENGINE_STOPPED_RPM) evKm += km
        // Battery energy over the same stretch, at the newest power reading.
        val volts = number(Role.HV_VOLTAGE, reading.tMs)
        val amps = number(Role.HV_CURRENT, reading.tMs)
        if (volts != null && amps != null) {
            batterySeen = true
            val kwh = volts * amps / 1000 * dtMs / 3_600_000.0
            if (kwh > 0) batteryOutKwh += kwh else batteryInKwh -= kwh
        }
    }

    private fun onFuel(reading: SignalReading) {
        val gps = (reading.value as? SignalValue.Number)?.value ?: return
        val previous = lastFuel
        lastFuel = reading
        val g0 = (previous?.value as? SignalValue.Number)?.value ?: return
        val dtMs = reading.tMs - previous.tMs
        if (dtMs <= 0 || dtMs > MAX_GAP_MS) return
        fuelGrams += (g0 + gps) / 2 * dtMs / 1000.0
        fuelSeen = true
    }

    /** Fresh numeric value for [role] at [nowMs], or null. */
    private fun number(role: Role, nowMs: Long): Double? =
        roles.signalsFor(role).firstNotNullOfOrNull { id ->
            latest[id]?.takeIf { fresh(it, nowMs) }?.let { (it.value as? SignalValue.Number)?.value }
        }

    private fun text(role: Role, nowMs: Long): String? =
        roles.signalsFor(role).firstNotNullOfOrNull { id ->
            latest[id]?.takeIf { fresh(it, nowMs) }?.let { (it.value as? SignalValue.Enumerated)?.value }
        }

    /** Fresh value of each of [role]'s signals in order, trailing gaps dropped (a pack shorter than the list). */
    private fun list(role: Role, nowMs: Long): List<Double?> =
        roles.signalsFor(role).map { id ->
            latest[id]?.takeIf { fresh(it, nowMs) }?.let { (it.value as? SignalValue.Number)?.value }
        }.dropLastWhile { it == null }

    /** Highest fresh value among all of [role]'s signals. */
    private fun max(role: Role, nowMs: Long): Double? =
        roles.signalsFor(role).mapNotNull { id ->
            latest[id]?.takeIf { fresh(it, nowMs) }?.let { (it.value as? SignalValue.Number)?.value }
        }.maxOrNull()

    /**
     * A reading counts while younger than 3 times the gap at which it really
     * arrives (or its polling period, if longer), at least [MIN_FRESH_MS] and
     * at most [MAX_FRESH_MS]. A busy link that reads a value every 8 s keeps
     * it on screen; a value that stops arriving disappears.
     */
    private fun fresh(reading: SignalReading, nowMs: Long): Boolean {
        val period = maxOf(periodBySignal[reading.signalId] ?: 1_000, interval[reading.signalId] ?: 0)
        return nowMs - reading.tMs <= (3 * period).coerceIn(MIN_FRESH_MS, MAX_FRESH_MS)
    }

    fun state(nowMs: Long, sourceBadge: String? = null, status: String? = null): DashboardState {
        val speed = number(Role.SPEED, nowMs)
        val rpm = number(Role.ENGINE_RPM, nowMs)
        val ready = number(Role.READY, nowMs)?.let { it != 0.0 }

        val enginePowerKw = enginePowerKw(nowMs, rpm)
        val volts = number(Role.HV_VOLTAGE, nowMs)
        val amps = number(Role.HV_CURRENT, nowMs)
        val batteryPowerKw = if (volts != null && amps != null) volts * amps / 1000 else null
        val litres = fuelGrams / petrolDensityGPerL
        val accelerator = number(Role.ACCELERATOR, nowMs)
        val warm = warmUpLevel(nowMs)
        val tooHard = warm == WarmUpLevel.COLD && warmUp != null &&
            ((accelerator ?: 0.0) >= warmUp.coldMaxAcceleratorPct || (rpm ?: 0.0) >= warmUp.coldMaxRpm)
        val (alarms, alarmWatch) = alarmMonitor?.update(
            nowMs,
            object : RoleReadings {
                override fun number(role: Role): Double? = if (role.allListed) max(role, nowMs) else number(role, nowMs)
                override fun unit(role: Role): String? = unit(role, nowMs)
            },
        ) ?: (emptyList<Alarm>() to emptyList())

        return DashboardState(
            sourceBadge = sourceBadge,
            status = status,
            speedKmh = speed,
            gear = text(Role.GEAR, nowMs),
            warmUp = warm,
            warmUpTooHard = tooHard,
            ready = ready,
            engineRpm = rpm,
            coolantC = number(Role.COOLANT_TEMP, nowMs),
            oilC = number(Role.OIL_TEMP, nowMs),
            enginePowerKw = enginePowerKw,
            batteryPowerKw = batteryPowerKw,
            acceleratorPct = accelerator,
            socPct = number(Role.SOC, nowMs),
            batteryTempC = max(Role.HV_BATTERY_TEMP, nowMs),
            hvVolts = volts,
            hvAmps = amps,
            batteryFanPct = number(Role.HV_BATTERY_FAN, nowMs),
            fuelLitres = number(Role.FUEL_LITRES, nowMs),
            ambientC = number(Role.AMBIENT_TEMP, nowMs),
            auxVolts = number(Role.AUX_VOLTAGE, nowMs),
            driveMode = driveMode(rpm, batteryPowerKw, speed, ready),
            tripKm = tripKm,
            evSharePct = if (tripKm >= MIN_TRIP_KM) evKm / tripKm * 100 else null,
            avgKmPerL = if (litres >= MIN_LITRES && tripKm >= MIN_TRIP_KM) tripKm / litres else null,
            trip = TripStats(
                km = tripKm,
                evKm = evKm,
                durationMs = durationMs,
                movingMs = movingMs,
                maxSpeedKmh = maxSpeed,
                fuelLitres = litres.takeIf { fuelSeen },
                batteryOutKwh = batteryOutKwh.takeIf { batterySeen },
                batteryInKwh = batteryInKwh.takeIf { batterySeen },
            ),
            inverterPump = number(Role.INVERTER_PUMP, nowMs),
            inverterPumpUnit = unit(Role.INVERTER_PUMP, nowMs),
            engineWaterPump = number(Role.ENGINE_WATER_PUMP, nowMs),
            engineWaterPumpUnit = unit(Role.ENGINE_WATER_PUMP, nowMs),
            engineWaterPumpTarget = number(Role.ENGINE_WATER_PUMP_TARGET, nowMs),
            motorInverterC = number(Role.MOTOR_INVERTER_TEMP, nowMs),
            generatorInverterC = number(Role.GENERATOR_INVERTER_TEMP, nowMs),
            inverterCoolantC = number(Role.INVERTER_COOLANT_TEMP, nowMs),
            boostConverterC = max(Role.BOOST_CONVERTER_TEMP, nowMs),
            boostInputVolts = number(Role.BOOST_INPUT_VOLTAGE, nowMs),
            boostOutputVolts = number(Role.BOOST_OUTPUT_VOLTAGE, nowMs),
            motorRpm = number(Role.MOTOR_RPM, nowMs),
            motorTorqueNm = number(Role.MOTOR_TORQUE, nowMs),
            alarms = alarms,
            alarmWatch = alarmWatch,
            ranges = ranges,
            values = Role.entries.mapNotNull { role ->
                (if (role.allListed) max(role, nowMs) else number(role, nowMs))?.let { role to it }
            }.toMap(),
            units = Role.entries.mapNotNull { role -> unit(role, nowMs)?.let { role to it } }.toMap(),
            lists = LISTED_BATTERY_ROLES.associateWith { list(it, nowMs) }.filterValues { l -> l.any { it != null } },
        )
    }

    /** Display unit of [role]'s current value ("%", "rpm", ...), or null. */
    private fun unit(role: Role, nowMs: Long): String? =
        roles.signalsFor(role).firstNotNullOfOrNull { id ->
            latest[id]?.takeIf { fresh(it, nowMs) }?.let { (it.value as? SignalValue.Number)?.unit }
        }?.let(::displayUnit)

    private fun displayUnit(unit: String): String = when (unit) {
        "percent" -> "%"
        "rpm" -> "rpm"
        "celsius" -> "°C"
        "volts" -> "V"
        "amps" -> "A"
        "litersPerMinute" -> "l/min"
        "kilometersPerHour" -> "km/h"
        "kilometers" -> "km"
        "kilowatts" -> "kW"
        "kilowattHours" -> "kWh"
        "newtonMeters" -> "N·m"
        "kilopascal" -> "kPa"
        "gramsPerSecond" -> "g/s"
        "liters" -> "l"
        "litersPerHour" -> "l/h"
        "seconds" -> "s"
        "minutes" -> "min"
        "hours" -> "h"
        "milliamps" -> "mA"
        "milliohms" -> "mΩ"
        "ampereHours" -> "Ah"
        "degrees" -> "°"
        "noyes", "yesno", "offon", "onoff", "scalar" -> ""
        else -> unit
    }

    /**
     * docs/02 C.5: engine on or off, then the battery's direction. Without a
     * battery reading the battery counts as idle. Null when engine speed is
     * unknown, or the car is neither READY nor moving.
     */
    private fun driveMode(rpm: Double?, batteryKw: Double?, speed: Double?, ready: Boolean?): DriveMode? {
        rpm ?: return null
        val moving = speed != null && speed > 0
        val battery = batteryKw ?: 0.0
        return if (rpm < ENGINE_STOPPED_RPM) {
            when {
                ready == false && !moving -> null
                battery <= -BATTERY_IDLE_KW && moving -> DriveMode.REGEN
                else -> DriveMode.EV
            }
        } else {
            when {
                battery >= BATTERY_IDLE_KW -> DriveMode.BOOST
                battery <= -BATTERY_IDLE_KW -> DriveMode.CHARGE
                else -> DriveMode.ENGINE
            }
        }
    }

    /** docs/02 C.1: torque = (actual % − friction %) / 100 × reference; kW = N·m × rpm / 9549. */
    private fun enginePowerKw(nowMs: Long, rpm: Double?): Double? {
        rpm ?: return null
        if (rpm < ENGINE_STOPPED_RPM) return 0.0
        val actual = number(Role.ENGINE_TORQUE_ACTUAL, nowMs) ?: return null
        val friction = number(Role.ENGINE_TORQUE_FRICTION, nowMs) ?: 0.0
        // The reference torque is read once a day: any reading is good.
        val reference = roles.signalsFor(Role.ENGINE_TORQUE_REFERENCE)
            .firstNotNullOfOrNull { (latest[it]?.value as? SignalValue.Number)?.value } ?: return null
        val torqueNm = (actual - friction) / 100 * reference
        return (torqueNm * rpm / 9549).coerceAtLeast(0.0)
    }

    /**
     * docs/02 C.6: the lowest level among the available inputs (oil,
     * coolant), never lower than the level already reached. Engine-off
     * stretches cool the engine a little, but they don't make it cold
     * again. When neither temperature is arriving (car off, link lost), the
     * level resets, so the next start begins from the real temperatures.
     */
    private fun warmUpLevel(nowMs: Long): WarmUpLevel? {
        val rules = warmUp ?: return null
        val oil = number(Role.OIL_TEMP, nowMs)
        val coolant = number(Role.COOLANT_TEMP, nowMs)
        if (oil == null && coolant == null) {
            warmUpReached = null
            return null
        }
        fun level(t: Double, warmFrom: Double, okFrom: Double) = when {
            t >= okFrom -> WarmUpLevel.OK
            t >= warmFrom -> WarmUpLevel.WARM
            else -> WarmUpLevel.COLD
        }
        val now = listOfNotNull(
            oil?.let { level(it, rules.oilWarmFrom, rules.oilOkFrom) },
            coolant?.let { level(it, rules.coolantWarmFrom, rules.coolantOkFrom) },
        ).min()
        val reached = warmUpReached?.let { maxOf(it, now) } ?: now
        warmUpReached = reached
        return reached
    }

    private companion object {
        val LISTED_BATTERY_ROLES = Role.BATTERY_TAB.filter { it.allListed }

        /** Gaps longer than this (dropout, pause) are not integrated. */
        const val MAX_GAP_MS = 5_000L
        const val MIN_FRESH_MS = 5_000L

        /** Failed replies in a row to READY's request before READY counts as unknown (one odd reply is not the car off). */
        const val READY_FAILED_REPLIES = 2
        const val MAX_FRESH_MS = 120_000L
        const val ENGINE_STOPPED_RPM = 100.0
        const val BATTERY_IDLE_KW = DashboardState.BATTERY_IDLE_KW
        const val MIN_TRIP_KM = 0.05
        const val MIN_LITRES = 0.005
    }
}
