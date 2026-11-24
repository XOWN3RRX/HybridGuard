package com.hybridguard.app

import com.hybridguard.data.Role
import com.hybridguard.data.SimulatedCar
import com.hybridguard.data.VehicleCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The simulated car for testing without a car (the emulator has no
 * Bluetooth). The "car" is switched on and off from the Live screen; while
 * on, it answers like a READY Corolla and plays the chosen [SimScenario].
 * Values are plausible in shape only.
 */
object Simulator {
    private val _carOn = MutableStateFlow(false)
    val carOn: StateFlow<Boolean> = _carOn.asStateFlow()

    /** Monotonic time the simulated car was switched on. */
    @Volatile private var onSinceMs = 0L

    @Volatile private var car: SimulatedCar? = null

    fun switch(on: Boolean, nowMs: Long) {
        if (on && !_carOn.value) onSinceMs = nowMs
        _carOn.value = on
        car?.let { applyPower(it) }
    }

    /** 12 V system voltage the simulated adapter reports: charging while READY. */
    val volts: Double get() = if (_carOn.value) 14.1 else 12.5

    /** Cells of the simulated HV pack: replies to the cell requests stop after these, as a real pack's do. */
    private const val SIM_CELLS = 56

    /** The cell that weakens in [SimScenario.WEAK_CELL]. */
    private const val WEAK_CELL = 23

    fun newCar(catalog: VehicleCatalog, scenario: SimScenario, clock: () -> Long): SimulatedCar {
        // Each signal's role and its position in that role's list (cell number − 1 for the cell roles).
        val roleOf = mutableMapOf<String, Pair<Role, Int>>()
        val resolver = catalog.roleResolver
        for (role in Role.entries) resolver.signalsFor(role).forEachIndexed { i, id -> roleOf.putIfAbsent(id, role to i) }
        // Cell requests answer only the simulated pack's cells.
        val shortReplies = mutableMapOf<String, Int>()
        for (role in listOf(Role.HV_CELL_VOLTAGE, Role.HV_CELL_RESISTANCE)) {
            val ids = resolver.signalsFor(role).take(SIM_CELLS).toSet()
            for (command in catalog.commands) {
                val kept = command.signals.filter { it.id in ids }
                if (kept.isEmpty() || kept.size == command.signals.size) continue
                shortReplies[command.key] = kept.maxOf { (it.format.bitOffset + it.format.bitLength + 7) / 8 }
            }
        }
        val c = SimulatedCar(catalog, clock = clock, scripted = { id, now ->
            roleOf[id]?.let { (role, index) -> value(role, index, scenario, (now - onSinceMs) / 1000.0) }
        }, replyBytes = { shortReplies[it] })
        applyPower(c)
        car = c
        return c
    }

    private fun applyPower(c: SimulatedCar) {
        c.silent = !_carOn.value
        c.batteryVolts = volts
    }

    /** The value of [role] (entry [index] of a listed role) [t] seconds after the car was switched on. */
    private fun value(role: Role, index: Int, s: SimScenario, t: Double): Double? {
        fun wave(period: Double, amp: Double, phase: Double = 0.0) = amp * sin(2 * PI * t / period + phase)
        // The engine runs 40 s, then rests 30 s (hybrid EV stretches).
        val engineOn = t % 70 < 40
        val rpm = if (engineOn) 1600 + wave(23.0, 500.0) else 0.0
        val failAt = 60.0
        val coolantNormal = min(88.0, 15 + t * 0.6) + wave(40.0, 1.5)
        val coolant = when {
            s == SimScenario.OVERHEAT && t > 120 -> min(118.0, coolantNormal + (t - 120) * 0.25)
            s == SimScenario.ENGINE_PUMP_FAILS && t > failAt + 20 -> min(115.0, coolantNormal + (t - failAt - 20) * 0.15)
            else -> coolantNormal
        }
        val inverterCoolantNormal = min(44.0, 12 + t * 0.12) + wave(60.0, 0.8)
        val inverterCoolant = if (s == SimScenario.INVERTER_PUMP_FAILS && t > failAt) {
            min(85.0, inverterCoolantNormal + (t - failAt) * 0.08)
        } else {
            inverterCoolantNormal
        }
        val enginePumpTarget = if (engineOn) 1500 + rpm * 0.35 else 0.0
        val soc = 56 + wave(120.0, 8.0)
        // HV battery: charging while the engine runs, a little load in the EV stretches.
        val hvAmps = if (engineOn) -12 + wave(23.0, 20.0) else 2.5 + wave(9.0, 1.5)
        val cellNumber = index + 1
        // Fixed small differences between cells (a few mV, a few hundredths of a milliohm).
        val cellOffsetMv = (Math.floorMod(index * 37, 11) - 5) * 1.3
        val weak = s == SimScenario.WEAK_CELL && cellNumber == WEAK_CELL
        val weakness = if (s == SimScenario.WEAK_CELL) min(1.0, max(0.0, (t - 30) / 90)) else 0.0
        val cellVolts = 3.55 + (soc - 50) * 0.004 - hvAmps * 0.0006 + cellOffsetMv / 1000 - (if (weak) 0.075 * weakness else 0.0)
        return when (role) {
            Role.READY -> 1.0
            Role.SPEED -> max(0.0, 55 + wave(90.0, 35.0))
            Role.ENGINE_RPM -> rpm
            Role.ENGINE_WATER_PUMP -> when {
                !engineOn -> 0.0
                s == SimScenario.ENGINE_PUMP_FAILS && t > failAt -> max(0.0, enginePumpTarget * (1 - (t - failAt) / 8))
                else -> enginePumpTarget - 75
            }
            Role.ENGINE_WATER_PUMP_TARGET -> enginePumpTarget
            Role.INVERTER_PUMP -> when {
                s == SimScenario.INVERTER_PUMP_FAILS && t > failAt -> max(0.0, 3450 * (1 - (t - failAt) / 6))
                else -> 3450 + wave(17.0, 20.0)
            }
            Role.COOLANT_TEMP -> coolant
            Role.OIL_TEMP -> max(15.0, coolant - 12 + min(t * 0.02, 8.0))
            Role.INVERTER_COOLANT_TEMP -> inverterCoolant
            Role.MOTOR_INVERTER_TEMP -> inverterCoolant + 9 + wave(30.0, 2.0)
            Role.GENERATOR_INVERTER_TEMP -> inverterCoolant + 6 + wave(35.0, 2.0)
            Role.BOOST_CONVERTER_TEMP -> inverterCoolant + 7
            Role.SOC -> soc
            Role.HV_BATTERY_TEMP -> 18 + min(t * 0.02, 10.0) + Math.floorMod(index * 5, 7) * 0.3
            Role.HV_CURRENT -> hvAmps
            Role.HV_CELL_VOLTAGE -> if (index < SIM_CELLS) cellVolts else null
            Role.HV_CELL_RESISTANCE -> if (index < SIM_CELLS) (3.8 + Math.floorMod(index * 13, 7) * 0.05) * (if (weak) 1 + 0.7 * weakness else 1.0) else null
            Role.HV_BLOCK_VOLTAGE -> 3.55 + (soc - 50) * 0.004 - hvAmps * 0.0006 + index * 0.002
            Role.HV_SOC_MAX -> soc + 1.5
            Role.HV_SOC_MIN -> soc - 1.5
            Role.HV_CHARGE_LIMIT -> -25 + wave(60.0, 2.0)
            Role.HV_DISCHARGE_LIMIT -> 21 + wave(60.0, 2.0)
            Role.HV_INTAKE_TEMP -> 14.0 + min(t * 0.01, 4.0)
            Role.HV_FAN_LEVEL -> if (t > 90) 2.0 else 1.0
            Role.HV_BATTERY_FAN -> if (t > 90) 25.0 else 12.0
            Role.AUX_CURRENT -> 9 + wave(30.0, 3.0)
            Role.AUX_TEMP -> 12.0
            Role.AUX_CAPACITY -> 38.0
            Role.HV_VOLTAGE -> 212 + wave(20.0, 8.0)
            Role.AUX_VOLTAGE -> 14.0 + wave(50.0, 0.1)
            Role.AMBIENT_TEMP -> 3.0
            Role.FUEL_MASS_RATE -> if (engineOn) 1.2 + wave(23.0, 0.6) else 0.0
            Role.FUEL_LITRES -> 30.0
            else -> null
        }
    }
}
