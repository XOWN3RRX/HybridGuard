package com.hybridguard.data

/**
 * Everything a dashboard design can show, already derived and in display
 * units (metric, power in kW). Immutable; null means "not available now"
 * (no signal on this car, or no fresh reading), drawn as `--`.
 *
 * Designs read only this, never signals or roles, so any design works with
 * any vehicle profile (docs/07) and on any screen (phone, Android Auto).
 */
data class DashboardState(
    /** Null for live car data, else "simulated" or "replay": designs show a badge. */
    val sourceBadge: String? = null,
    /** Short status when not logging (e.g. "Connecting", "No session"), else null. */
    val status: String? = null,

    val speedKmh: Double? = null,
    /** "P", "R", "N", "D", "B". */
    val gear: String? = null,
    /** How hard the car may be driven while the engine warms up (docs/02 C.6); null when unknown. */
    val warmUp: WarmUpLevel? = null,
    /** [WarmUpLevel.COLD] and the pedal or engine speed is above the cold limit: designs highlight the label. */
    val warmUpTooHard: Boolean = false,
    val ready: Boolean? = null,

    val engineRpm: Double? = null,
    val coolantC: Double? = null,
    val oilC: Double? = null,
    /** Engine output power, kW (docs/02 C.1). */
    val enginePowerKw: Double? = null,

    /** HV battery power, kW: positive = discharging (driving the car), negative = charging (regen). */
    val batteryPowerKw: Double? = null,
    val acceleratorPct: Double? = null,

    val socPct: Double? = null,
    /** Hottest HV battery temperature sensor. */
    val batteryTempC: Double? = null,
    val hvVolts: Double? = null,
    /** Positive = discharging. */
    val hvAmps: Double? = null,
    val batteryFanPct: Double? = null,

    val fuelLitres: Double? = null,
    val ambientC: Double? = null,
    val auxVolts: Double? = null,

    /** What drives the car right now (docs/02 C.5); null without engine speed. */
    val driveMode: DriveMode? = null,
    val tripKm: Double = 0.0,
    /** Share of the trip distance driven with the engine stopped, %. */
    val evSharePct: Double? = null,
    /** Trip average, km per litre of fuel. */
    val avgKmPerL: Double? = null,
    /** The whole trip so far, for the summary shown when parking (docs/02 C.4, C.5). */
    val trip: TripStats = TripStats(),

    // ---- Advanced screen: only filled while it is open (its requests are polled then).

    /** Inverter cooling pump, in [inverterPumpUnit] ("%", "rpm", ... from the signal). */
    val inverterPump: Double? = null,
    val inverterPumpUnit: String? = null,
    /** Engine water pump, in [engineWaterPumpUnit]. */
    val engineWaterPump: Double? = null,
    val engineWaterPumpUnit: String? = null,
    /** What the engine ECU asks of the water pump, in [engineWaterPumpUnit]. */
    val engineWaterPumpTarget: Double? = null,
    val motorInverterC: Double? = null,
    val generatorInverterC: Double? = null,
    val inverterCoolantC: Double? = null,
    /** Hotter of the boost converter's sensors. */
    val boostConverterC: Double? = null,
    /** HV before the boost converter (battery side, "VL"). */
    val boostInputVolts: Double? = null,
    /** HV after the boost converter (motor side, "VH"). */
    val boostOutputVolts: Double? = null,
    val motorRpm: Double? = null,
    val motorTorqueNm: Double? = null,

    /** Cooling alarms on now, worst first (docs/02 C.7); the screens show and sound them through [AlarmGate]. */
    val alarms: List<Alarm> = emptyList(),
    /** Which alarm groups are being watched now (for the alarm list on the car screen). */
    val alarmWatch: List<AlarmWatch> = emptyList(),

    /** Full-scale values for gauges and bars, from the vehicle profile. */
    val ranges: DisplayRanges = DisplayRanges(),

    /** Every numeric role with a fresh reading (all-listed roles: the highest), for logs and charts. */
    val values: Map<Role, Double> = emptyMap(),
    /** Display unit per role in [values] ("rpm", "°C", ...). */
    val units: Map<Role, String> = emptyMap(),
    /**
     * Every reading of the Battery tab's all-listed roles (cells, resistances,
     * blocks, pack temperatures), in the profile's order; null where a signal
     * has no fresh value. Only filled while those roles are polled.
     */
    val lists: Map<Role, List<Double?>> = emptyMap(),
) {
    /** Which way the HV battery's power flows; designs show it as a word and a colour, never a sign. */
    val batteryFlow: BatteryFlow?
        get() = batteryPowerKw?.let {
            when {
                it >= BATTERY_IDLE_KW -> BatteryFlow.USING
                it <= -BATTERY_IDLE_KW -> BatteryFlow.CHARGING
                else -> BatteryFlow.IDLE
            }
        }

    companion object {
        /** Battery power within ± this counts as idle (a display choice, docs/02 C.5). */
        const val BATTERY_IDLE_KW = 1.0
    }
}

/**
 * The trip since the connection started, integrated from the samples as
 * read: distance from speed, fuel from the fuel mass rate, battery energy
 * from HV voltage × current. Null where the car gave no input for it.
 */
data class TripStats(
    val km: Double = 0.0,
    /** Distance driven with the engine stopped. */
    val evKm: Double = 0.0,
    /** Time covered by speed readings (stops included, dropouts left out). */
    val durationMs: Long = 0,
    /** Part of [durationMs] spent moving. */
    val movingMs: Long = 0,
    val maxSpeedKmh: Double? = null,
    val fuelLitres: Double? = null,
    /** Energy the HV battery gave (driving the car), kWh. */
    val batteryOutKwh: Double? = null,
    /** Energy the HV battery took (regen, engine charging), kWh. */
    val batteryInKwh: Double? = null,
) {
    val iceKm: Double get() = (km - evKm).coerceAtLeast(0.0)
    val evSharePct: Double? get() = if (km > 0) evKm / km * 100 else null

    /** Over the whole trip time, stops included (as the car's own trip computer). */
    val avgSpeedKmh: Double? get() = if (durationMs > 0) km / (durationMs / 3_600_000.0) else null
    val kmPerLitre: Double? get() = fuelLitres?.takeIf { it > 0.005 }?.let { km / it }
    val litresPer100Km: Double? get() = fuelLitres?.takeIf { km > 0 }?.let { it / km * 100 }
}

/** HV battery power direction (docs/02 C.2). */
enum class BatteryFlow {
    /** The battery gives power (drives the car). */
    USING,

    /** The battery takes power (regen, or the engine charging it). */
    CHARGING,

    /** Within ±[DashboardState.BATTERY_IDLE_KW]. */
    IDLE,
}

/**
 * What drives the car right now, from engine speed and HV battery power
 * (docs/02 C.5). The battery counts as idle within a small band around 0.
 */
enum class DriveMode(val label: String) {
    /** Engine off, the battery drives the car (or the car stands READY). */
    EV("EV"),

    /** Engine (ICE) on, battery about idle. */
    ENGINE("ICE"),

    /** Engine on and the battery also giving power. */
    BOOST("BOOST"),

    /** Engine on and the battery charging. */
    CHARGE("CHARGE"),

    /** Engine off, battery charging while moving: braking or coasting. */
    REGEN("REGEN"),
}

/**
 * Full scale of gauges and bars, from a profile's `display.json`; the
 * defaults suit a generic car. Display choices, not decoding facts.
 */
data class DisplayRanges(
    val speedMaxKmh: Double = 200.0,
    val enginePowerMaxKw: Double = 110.0,
    /** Battery power bar: ± this value. */
    val batteryPowerMaxKw: Double = 75.0,
)

/**
 * Driving advice while the engine warms up (docs/02 C.6), weakest first.
 * Advice for the driver about engine wear, not the hybrid ECU's own
 * warm-up stage.
 */
enum class WarmUpLevel {
    /** Slow driving, light pedal only. Shown as "COLD". */
    COLD,

    /** Normal traffic, no full throttle. Shown as "WARM". */
    WARM,

    /** The car may be used fully. Shown as "GOOD". */
    OK,
}

/**
 * Thresholds from a profile's `warmup.json` (docs/02 C.6), all °C except the
 * cold limits. Each level is reached when every available input (oil,
 * coolant) has reached it; oil is the main input, coolant also covers a car
 * without an oil temperature.
 */
data class WarmUpRules(
    val oilWarmFrom: Double,
    val oilOkFrom: Double,
    val coolantWarmFrom: Double,
    val coolantOkFrom: Double,
    /** While [WarmUpLevel.COLD], pedal at or above this % counts as too hard. */
    val coldMaxAcceleratorPct: Double,
    /** While [WarmUpLevel.COLD], engine speed at or above this counts as too hard. */
    val coldMaxRpm: Double,
)
