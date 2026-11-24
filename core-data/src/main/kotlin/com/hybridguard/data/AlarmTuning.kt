package com.hybridguard.data

/*
 * The driver's own alarm settings on top of the profile's `alarms.json`:
 * each check can be switched on or off, made a CAUTION or a STOP, and have
 * its numbers changed. The profile stays the source of what is checked
 * (roles, never signal ids); the app only stores the differences.
 */

/** One number of a check the settings screen can change. */
data class AlarmParam(
    /** Stable key, stored in the settings ("below", "from", "forSeconds", ...). */
    val key: String,
    val label: String,
    val unit: String,
    val value: Double,
    val min: Double,
    val max: Double,
    val step: Double,
)

/** What the driver changed on one check; null fields keep the profile's value. */
data class AlarmOverride(
    val enabled: Boolean? = null,
    val level: AlarmLevel? = null,
    val values: Map<String, Double> = emptyMap(),
) {
    val isEmpty: Boolean get() = enabled == null && level == null && values.isEmpty()
}

/** The numbers of [this] check the driver may tune, with the current values. */
fun AlarmCheck.params(): List<AlarmParam> {
    val delay = AlarmParam("forSeconds", "Must last", "s", forMs / 1000.0, 0.0, 600.0, 1.0)
    return when (this) {
        is AlarmCheck.PumpLow -> listOfNotNull(
            below?.let { AlarmParam("below", "Alarm below", unit, it, 0.0, 10_000.0, 50.0) },
            belowShareOfTarget?.let { AlarmParam("belowSharePct", "Alarm below share of the asked speed", "%", it * 100, 5.0, 95.0, 5.0) },
            // READY is a yes / no: nothing to tune.
            AlarmParam("runsAbove", "Checked while ${runsWhile.label} is at least", "", runsAbove, 0.0, 10_000.0, 50.0)
                .takeUnless { runsWhile == Role.READY },
            AlarmParam("runningForSeconds", "Start checking after", "s", runningForMs / 1000.0, 0.0, 300.0, 1.0),
            delay,
        )
        is AlarmCheck.TooHot -> listOf(
            AlarmParam("from", "Alarm from", unit, from, -40.0, 200.0, 1.0),
            delay,
        )
        is AlarmCheck.TooLow -> listOfNotNull(
            AlarmParam("below", "Alarm below", unit, below, 0.0, 10_000.0, if (below < 50) 0.1 else 50.0),
            runsWhile?.let { AlarmParam("runningForSeconds", "Start checking after", "s", runningForMs / 1000.0, 0.0, 300.0, 1.0) },
            delay,
        )
        is AlarmCheck.RisingFast -> listOf(
            AlarmParam("from", "Only above", unit, from, -40.0, 200.0, 1.0),
            AlarmParam("perMinute", "Rising at least", "$unit/min", perMinute, 0.1, 50.0, 0.5),
            AlarmParam("overSeconds", "Measured over", "s", overMs / 1000.0, 10.0, 900.0, 10.0),
            delay,
        )
    }
}

/** [this] check with [o] applied (numbers outside their range are clamped). */
fun AlarmCheck.with(o: AlarmOverride): AlarmCheck {
    val limits = params().associateBy { it.key }
    fun v(key: String, current: Double): Double {
        val p = limits[key] ?: return current
        return (o.values[key] ?: return current).coerceIn(p.min, p.max)
    }
    fun ms(key: String, currentMs: Long): Long = (v(key, currentMs / 1000.0) * 1000).toLong()
    val level = o.level ?: level
    val forMs = ms("forSeconds", forMs)
    return when (this) {
        is AlarmCheck.PumpLow -> copy(
            level = level, forMs = forMs,
            below = below?.let { v("below", it) },
            belowShareOfTarget = belowShareOfTarget?.let { v("belowSharePct", it * 100) / 100 },
            runsAbove = v("runsAbove", runsAbove),
            runningForMs = ms("runningForSeconds", runningForMs),
        )
        is AlarmCheck.TooHot -> copy(level = level, forMs = forMs, from = v("from", from))
        is AlarmCheck.TooLow -> copy(
            level = level, forMs = forMs,
            below = v("below", below),
            runningForMs = ms("runningForSeconds", runningForMs),
        )
        is AlarmCheck.RisingFast -> copy(
            level = level, forMs = forMs,
            from = v("from", from),
            perMinute = v("perMinute", perMinute),
            overMs = ms("overSeconds", overMs).coerceAtLeast(10_000),
        )
    }
}

/** Whether [check] runs with the driver's [overrides]. */
fun isEnabled(check: AlarmCheck, overrides: Map<String, AlarmOverride>): Boolean =
    overrides[check.id]?.enabled ?: check.enabledByDefault

/**
 * The rules actually run: the driver's [overrides] applied (keyed by check
 * id; unknown ids are ignored), disabled checks left out.
 */
fun AlarmRules.withOverrides(overrides: Map<String, AlarmOverride>): AlarmRules =
    copy(
        checks = checks.filter { isEnabled(it, overrides) }
            .map { c -> overrides[c.id]?.let(c::with) ?: c },
    )

/** A readable name for a role, used in the settings and the logs. */
val Role.label: String
    get() = when (this) {
        Role.SPEED -> "Speed"
        Role.ENGINE_RPM -> "Engine rpm"
        Role.HV_VOLTAGE -> "HV battery voltage"
        Role.HV_CURRENT -> "HV battery current"
        Role.SOC -> "HV battery charge"
        Role.FUEL_LITRES -> "Fuel in the tank"
        Role.GEAR -> "Gear"
        Role.READY -> "READY"
        Role.COOLANT_TEMP -> "Engine coolant"
        Role.OIL_TEMP -> "Engine oil"
        Role.ENGINE_TORQUE_ACTUAL -> "Engine torque"
        Role.ENGINE_TORQUE_FRICTION -> "Engine friction torque"
        Role.ENGINE_TORQUE_REFERENCE -> "Engine reference torque"
        Role.FUEL_MASS_RATE -> "Fuel rate"
        Role.ACCELERATOR -> "Accelerator"
        Role.HV_BATTERY_TEMP -> "HV battery temperature"
        Role.HV_BATTERY_FAN -> "HV battery fan"
        Role.AMBIENT_TEMP -> "Outside temperature"
        Role.AUX_VOLTAGE -> "12 V battery"
        Role.INVERTER_PUMP -> "Inverter water pump"
        Role.ENGINE_WATER_PUMP -> "Engine water pump"
        Role.ENGINE_WATER_PUMP_TARGET -> "Engine pump asked speed"
        Role.MOTOR_INVERTER_TEMP -> "Motor inverter"
        Role.GENERATOR_INVERTER_TEMP -> "Generator inverter"
        Role.INVERTER_COOLANT_TEMP -> "Inverter coolant"
        Role.BOOST_CONVERTER_TEMP -> "Boost converter"
        Role.BOOST_INPUT_VOLTAGE -> "Boost input voltage"
        Role.BOOST_OUTPUT_VOLTAGE -> "Boost output voltage"
        Role.MOTOR_RPM -> "Motor rpm"
        Role.MOTOR_TORQUE -> "Motor torque"
        Role.HV_CELL_VOLTAGE -> "Cell voltage"
        Role.HV_CELL_RESISTANCE -> "Cell resistance"
        Role.HV_BLOCK_VOLTAGE -> "Block voltage"
        Role.HV_SOC_MAX -> "HV battery charge, highest"
        Role.HV_SOC_MIN -> "HV battery charge, lowest"
        Role.HV_CHARGE_LIMIT -> "Charge power limit"
        Role.HV_DISCHARGE_LIMIT -> "Discharge power limit"
        Role.HV_INTAKE_TEMP -> "Battery cooling air"
        Role.HV_FAN_LEVEL -> "Battery fan level"
        Role.AUX_CURRENT -> "12 V current"
        Role.AUX_TEMP -> "12 V battery temperature"
        Role.AUX_CAPACITY -> "12 V capacity"
    }

/** A short name for the notification and chart legends. */
val Role.shortLabel: String
    get() = when (this) {
        Role.ENGINE_WATER_PUMP -> "Eng. pump"
        Role.ENGINE_WATER_PUMP_TARGET -> "Eng. pump asked"
        Role.INVERTER_PUMP -> "Inv. pump"
        Role.COOLANT_TEMP -> "Coolant"
        Role.INVERTER_COOLANT_TEMP -> "Inv. coolant"
        Role.MOTOR_INVERTER_TEMP -> "Motor inv."
        Role.GENERATOR_INVERTER_TEMP -> "Gen. inv."
        Role.HV_BATTERY_TEMP -> "HV bat. temp"
        Role.AUX_VOLTAGE -> "12 V"
        Role.SOC -> "SOC"
        Role.OIL_TEMP -> "Oil"
        Role.ENGINE_RPM -> "RPM"
        Role.AMBIENT_TEMP -> "Outside"
        else -> label
    }
