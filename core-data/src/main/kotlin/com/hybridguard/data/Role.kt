package com.hybridguard.data

/**
 * What the dashboard and derived metrics ask for, independent of the car.
 *
 * Each vehicle profile's `derived.json` maps a role [key] to that profile's
 * signal ids, in order of preference (docs/07 section 1). A role with no
 * working signal on the current car is shown as unavailable. Roles marked
 * "all listed" use every listed signal instead (e.g. the hottest of several
 * battery temperature sensors).
 *
 * Extend this list as the dashboard needs more roles; never reference a
 * signal id from Kotlin instead.
 */
enum class Role(
    val key: String,
    val advanced: Boolean = false,
    /** Shown only by designs with detail values (Classic): polled only while one of them is on screen. */
    val detail: Boolean = false,
    val allListed: Boolean = false,
    /** Shown only by the Battery tab: polled only while it is open, so the cell requests never slow the cooling watch. */
    val battery: Boolean = false,
) {

    SPEED("speed"),
    ENGINE_RPM("engineRpm"),
    HV_VOLTAGE("hvVoltage"),

    /** Positive = the battery is discharging (driving the car), negative = charging. */
    HV_CURRENT("hvCurrent"),
    SOC("soc"),
    FUEL_LITRES("fuelLitres", detail = true),
    GEAR("gear"),

    /** Hybrid system READY (car can drive), as opposed to ignition on only. */
    READY("ready"),

    COOLANT_TEMP("coolantTemp"),

    /** Main input of the warm-up advice (docs/02 C.6), so polled on every design. */
    OIL_TEMP("oilTemp"),

    /** Engine torque as % of the reference torque (SAE `01 62`). */
    ENGINE_TORQUE_ACTUAL("engineTorqueActual"),

    /** Engine friction torque as % of the reference torque (SAE `01 8E`). */
    ENGINE_TORQUE_FRICTION("engineTorqueFriction"),

    /** Engine reference torque in N·m (SAE `01 63`). */
    ENGINE_TORQUE_REFERENCE("engineTorqueReference"),

    /** Engine fuel mass rate in g/s. */
    FUEL_MASS_RATE("fuelMassRate"),
    ACCELERATOR("accelerator"),

    /** All listed: the highest reading is shown. */
    HV_BATTERY_TEMP("hvBatteryTemp", detail = true, allListed = true),
    HV_BATTERY_FAN("hvBatteryFan", detail = true),
    AMBIENT_TEMP("ambientTemp", detail = true),

    /** 12 V system voltage. */
    AUX_VOLTAGE("auxVoltage", detail = true),

    // ---- Advanced screen (inverter and pumps). Polled only while that screen is open,
    // ---- so they never slow the main dashboard ([advanced] = true).

    /** Electric pump of the inverter cooling loop: speed or duty, in the signal's own unit. */
    INVERTER_PUMP("inverterPump", advanced = true),

    /** Electric engine water pump: actual speed or duty, in the signal's own unit. */
    ENGINE_WATER_PUMP("engineWaterPump", advanced = true),

    /** Speed the engine ECU asks of the electric water pump, in the signal's own unit. */
    ENGINE_WATER_PUMP_TARGET("engineWaterPumpTarget", advanced = true),
    MOTOR_INVERTER_TEMP("motorInverterTemp", advanced = true),
    GENERATOR_INVERTER_TEMP("generatorInverterTemp", advanced = true),
    INVERTER_COOLANT_TEMP("inverterCoolantTemp", advanced = true),

    /** All listed: the highest reading is shown. */
    BOOST_CONVERTER_TEMP("boostConverterTemp", advanced = true, allListed = true),

    /** HV voltage before the boost converter (the battery side), "VL". */
    BOOST_INPUT_VOLTAGE("boostInputVoltage", advanced = true),

    /** HV voltage after the boost converter (the motor side), "VH". */
    BOOST_OUTPUT_VOLTAGE("boostOutputVoltage", advanced = true),

    /** The electric motor the hybrid ECU reports (which of MG1 / MG2 is not known yet, docs/02 D item 6). */
    MOTOR_RPM("motorRpm", advanced = true),
    MOTOR_TORQUE("motorTorque", advanced = true),

    // ---- Battery tab (docs/02 section B.3). Polled only while that tab is open ([battery] = true).

    /** All listed: one per cell (or per block on packs that report blocks), in cell order. */
    HV_CELL_VOLTAGE("hvCellVoltage", battery = true, allListed = true),

    /** All listed: internal resistance per cell, in cell order. */
    HV_CELL_RESISTANCE("hvCellResistance", battery = true, allListed = true),

    /** All listed: average voltage per block or stack of cells, as the battery ECU groups them. */
    HV_BLOCK_VOLTAGE("hvBlockVoltage", battery = true, allListed = true),
    HV_SOC_MAX("hvSocMax", battery = true),
    HV_SOC_MIN("hvSocMin", battery = true),

    /** Power the battery may take now (charge limit, "Win"), kW. */
    HV_CHARGE_LIMIT("hvChargeLimit", battery = true),

    /** Power the battery may give now (discharge limit, "Wout"), kW. */
    HV_DISCHARGE_LIMIT("hvDischargeLimit", battery = true),

    /** Cooling air temperature at the battery's intake. */
    HV_INTAKE_TEMP("hvIntakeTemp", battery = true),
    HV_FAN_LEVEL("hvFanLevel", battery = true),

    /** 12 V battery current. */
    AUX_CURRENT("auxCurrent", battery = true),
    AUX_TEMP("auxTemp", battery = true),

    /** 12 V battery state of charge or capacity as the car reports it. */
    AUX_CAPACITY("auxCapacity", battery = true),
    ;

    /** Not one of the main values: polled only while something on screen (or an alarm) needs it. */
    val onDemand: Boolean get() = advanced || detail || battery

    companion object {
        private val byKey = entries.associateBy { it.key }

        /** Everything the Battery tab reads: its own roles and the battery's detail values. */
        val BATTERY_TAB: Set<Role> = entries.filter { it.battery }.toSet() + setOf(HV_BATTERY_TEMP, HV_BATTERY_FAN, AUX_VOLTAGE)

        /** Role for a `derived.json` key, or null if this app version does not know it. */
        fun fromKey(key: String): Role? = byKey[key]
    }
}
