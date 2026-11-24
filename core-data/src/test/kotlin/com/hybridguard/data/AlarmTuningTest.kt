package com.hybridguard.data

import com.hybridguard.obd.Elm327
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmTuningTest {

    private val rules = ProfileLoader().alarmRules("toyota-corolla-hybrid-e210")!!

    @Test
    fun optionalChecksAreOffUntilSwitchedOn() {
        val run = rules.withOverrides(emptyMap())
        assertTrue(run.checks.none { it.id == "auxVoltageLow" })
        assertTrue(run.checks.any { it.id == "inverterWaterPumpStopped" })
        val on = rules.withOverrides(mapOf("auxVoltageLow" to AlarmOverride(enabled = true)))
        assertTrue(on.checks.any { it.id == "auxVoltageLow" })
        assertTrue(Role.AUX_VOLTAGE in on.roles)
    }

    @Test
    fun aCheckCanBeSwitchedOff() {
        val run = rules.withOverrides(mapOf("coolantHot" to AlarmOverride(enabled = false)))
        assertTrue(run.checks.none { it.id == "coolantHot" })
    }

    @Test
    fun numbersAndLevelAreApplied() {
        val run = rules.withOverrides(
            mapOf(
                "inverterWaterPumpSlow" to AlarmOverride(level = AlarmLevel.STOP, values = mapOf("below" to 3000.0, "forSeconds" to 5.0)),
                "engineWaterPumpSlow" to AlarmOverride(values = mapOf("belowSharePct" to 70.0)),
                "coolantHot" to AlarmOverride(values = mapOf("from" to 500.0)), // clamped
            ),
        )
        val inv = run.checks.first { it.id == "inverterWaterPumpSlow" } as AlarmCheck.PumpLow
        assertEquals(AlarmLevel.STOP, inv.level)
        assertEquals(3000.0, inv.below)
        assertEquals(5_000, inv.forMs)
        val eng = run.checks.first { it.id == "engineWaterPumpSlow" } as AlarmCheck.PumpLow
        assertEquals(0.7, eng.belowShareOfTarget!!, 1e-9)
        val hot = run.checks.first { it.id == "coolantHot" } as AlarmCheck.TooHot
        assertEquals(200.0, hot.from)
    }

    @Test
    fun paramsRoundTrip() {
        for (check in rules.checks) {
            val same = check.with(AlarmOverride(values = check.params().associate { it.key to it.value }))
            assertEquals(check, same, check.id)
        }
    }

    @Test
    fun tooLowWaitsForReadyThenRaises() {
        val check = rules.checks.first { it.id == "auxVoltageLow" } as AlarmCheck.TooLow
        val monitor = AlarmMonitor(AlarmRules(listOf(check)))
        val readings = mutableMapOf(Role.READY to 1.0, Role.AUX_VOLTAGE to 12.4)
        val r = object : RoleReadings {
            override fun number(role: Role) = readings[role]
            override fun unit(role: Role) = "V"
        }
        var t = 0L
        repeat(29) { assertTrue(monitor.update(t, r).first.isEmpty()); t += 1_000 }
        // Running for 30 s, then the condition must last 60 s.
        repeat(65) { monitor.update(t, r); t += 1_000 }
        assertEquals("auxVoltageLow", monitor.update(t, r).first.single().id)
        readings[Role.READY] = 0.0
        repeat(25) { monitor.update(t, r); t += 1_000 }
        assertTrue(monitor.update(t, r).first.isEmpty())
    }

    @Test
    fun simulatorEncodesLinearValues() {
        val f = Scaling(bitOffset = 8, bitLength = 16, max = 10_000.0, unit = "rpm")
        val data = ByteArray(4)
        SimulatedCar.insertBits(data, f.bitOffset, f.bitLength, SimulatedCar.encode(f, 3450.0))
        assertEquals(3450.0, (f.decode(data) as SignalValue.Number).value)

        val t = Scaling(bitLength = 8, max = 215.0, min = -40.0, add = -40.0, unit = "celsius")
        val d2 = ByteArray(1)
        SimulatedCar.insertBits(d2, 0, 8, SimulatedCar.encode(t, 88.0))
        assertEquals(88.0, (t.decode(d2) as SignalValue.Number).value)
    }

    @Test
    fun voltsAreParsed() {
        assertEquals(12.4, Elm327.parseVolts("ATRV\r12.4V\r\r"))
        assertEquals(14.12, Elm327.parseVolts("14.12V"))
        assertNull(Elm327.parseVolts("?"))
    }

    @Test
    fun dashboardStateCarriesRoleValues() {
        val profile = ProfileLoader().load("toyota-corolla-hybrid-e210")
        val catalog = profile.forModelYear(2019)
        val engine = DashboardEngine(catalog)
        val state = engine.state(0)
        assertTrue(state.values.isEmpty())
        assertNotNull(catalog.roleResolver.signalsFor(Role.INVERTER_PUMP).firstOrNull())
        assertFalse(Role.INVERTER_PUMP.label.isEmpty())
    }
}
