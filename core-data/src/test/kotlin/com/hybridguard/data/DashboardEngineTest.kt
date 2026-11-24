package com.hybridguard.data

import com.hybridguard.obd.RawSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardEngineTest {

    private val loader = ProfileLoader()
    private val catalog = loader.load("toyota-corolla-hybrid-e210").forModelYear(2019)
    private val rules = loader.warmUpRules("toyota-corolla-hybrid-e210")

    private fun n(v: Double, unit: String = "scalar") = SignalValue.Number(v, unit)

    private fun sample(t: Long, vararg values: Pair<String, SignalValue>) =
        DecodedSample(t, "test", RawSample.Status.OK, values.toMap())

    @Test
    fun warmUpRulesComeFromTheProfile() {
        assertEquals(WarmUpRules(50.0, 80.0, 40.0, 70.0, 50.0, 3000.0), rules)
        assertNull(loader.warmUpRules("sae-j1979"))
    }

    @Test
    fun warmUpAdviceFollowsTheColderInput() {
        // The generic profile maps oil (01 5C); the Corolla does not (it gives none, docs/02).
        val e = DashboardEngine(loader.load("sae-j1979").forModelYear(null), rules)
        e.update(sample(0, "ECT" to n(30.0), "EOT" to n(20.0)))
        assertEquals(WarmUpLevel.COLD, e.state(100).warmUp)
        e.update(sample(1_000, "ECT" to n(65.0), "EOT" to n(45.0)))
        assertEquals(WarmUpLevel.COLD, e.state(1_100).warmUp, "coolant warming, oil still cold")
        e.update(sample(2_000, "ECT" to n(75.0), "EOT" to n(55.0)))
        assertEquals(WarmUpLevel.WARM, e.state(2_100).warmUp)
        e.update(sample(3_000, "ECT" to n(85.0), "EOT" to n(82.0)))
        assertEquals(WarmUpLevel.OK, e.state(3_100).warmUp)
    }

    @Test
    fun warmUpAdviceUsesCoolantWithoutOil() {
        val e = DashboardEngine(catalog, rules)
        e.update(sample(0, "ECT" to n(45.0)))
        assertEquals(WarmUpLevel.WARM, e.state(100).warmUp)
        assertNull(DashboardEngine(catalog).state(100).warmUp, "no rules in the profile: no advice")
    }

    @Test
    fun warmUpAdviceOnlyGoesUpUntilTheTemperaturesStop() {
        val e = DashboardEngine(catalog, rules)
        e.update(sample(0, "ECT" to n(85.0), "EOT" to n(82.0)))
        assertEquals(WarmUpLevel.OK, e.state(100).warmUp)
        // A long EV stretch cools the engine a little: still OK.
        e.update(sample(1_000, "ECT" to n(66.0), "EOT" to n(76.0)))
        assertEquals(WarmUpLevel.OK, e.state(1_100).warmUp)
        // Car off: nothing arrives, the advice goes; the next start begins from the real temperatures.
        assertNull(e.state(300_000).warmUp)
        e.update(sample(300_000, "ECT" to n(35.0), "EOT" to n(40.0)))
        assertEquals(WarmUpLevel.COLD, e.state(300_100).warmUp)
    }

    @Test
    fun pushingHardWhileColdIsFlagged() {
        val e = DashboardEngine(catalog, rules)
        e.update(sample(0, "ECT" to n(30.0), "EOT" to n(20.0), "RPM" to n(1200.0), "COROLLAHYBRID_APP_V2" to n(20.0)))
        assertFalse(e.state(100).warmUpTooHard)
        e.update(sample(1_000, "COROLLAHYBRID_APP_V2" to n(60.0)))
        assertTrue(e.state(1_100).warmUpTooHard, "pedal over the cold limit")
        e.update(sample(2_000, "COROLLAHYBRID_APP_V2" to n(20.0), "RPM" to n(3200.0)))
        assertTrue(e.state(2_100).warmUpTooHard, "engine speed over the cold limit")
        e.update(sample(3_000, "ECT" to n(75.0), "EOT" to n(55.0)))
        assertFalse(e.state(3_100).warmUpTooHard, "WARM: no longer flagged")
    }

    @Test
    fun valuesArePickedByRoleAndDerived() {
        val e = DashboardEngine(catalog, rules)
        e.update(sample(0, "TQ_REF" to n(140.0, "newtonMeters")))
        e.update(
            sample(
                1_000,
                "VSS" to n(50.0), "RPM" to n(2000.0), "ECT" to n(80.0), "EOT" to n(90.0),
                "TQ_ACT" to n(40.0), "TQ_FR" to n(10.0),
                "COROLLAHYBRID_HVBAT_V" to n(200.0), "COROLLAHYBRID_HVBAT_C" to n(-25.0),
                "COROLLAHYBRID_SOC" to n(55.0),
                "COROLLAHYBRID_SHIFT_POS" to SignalValue.Enumerated("D", "Drive"),
                "COROLLAHYBRID_READY" to n(1.0),
                "COROLLAHYBRID_BAT_T_01" to n(21.0), "COROLLAHYBRID_BAT_T_02" to n(24.5), "COROLLAHYBRID_BAT_T_03" to SignalValue.NoValue,
                "COROLLAHYBRID_HYBRID_BAT_1_FAN_747" to n(12.0),
            ),
        )
        val s = e.state(1_200, sourceBadge = "simulated")
        assertEquals("simulated", s.sourceBadge)
        assertEquals(50.0, s.speedKmh)
        assertEquals("D", s.gear)
        assertEquals(true, s.ready)
        assertEquals(80.0, s.coolantC)
        assertEquals(24.5, s.batteryTempC, "hottest sensor")
        assertEquals(12.0, s.batteryFanPct)
        // (40 - 10) % of 140 N·m = 42 N·m at 2000 rpm = 8.797 kW
        assertEquals(42.0 * 2000 / 9549, s.enginePowerKw!!, 1e-9)
        // 200 V × -25 A = -5 kW: charging
        assertEquals(-5.0, s.batteryPowerKw!!, 1e-9)
        assertEquals(BatteryFlow.CHARGING, s.batteryFlow)
        assertEquals(BatteryFlow.USING, s.copy(batteryPowerKw = 7.0).batteryFlow)
        assertEquals(BatteryFlow.IDLE, s.copy(batteryPowerKw = -0.4).batteryFlow, "within ±1 kW")
        assertEquals(DriveMode.CHARGE, s.driveMode, "engine on, battery charging")
        assertEquals(WarmUpLevel.OK, s.warmUp)
        assertNull(s.fuelLitres, "not read")
    }

    @Test
    fun staleValuesDisappear() {
        val e = DashboardEngine(catalog, rules)
        e.update(sample(0, "VSS" to n(30.0)))
        assertEquals(30.0, e.state(4_000).speedKmh)
        assertNull(e.state(60_000).speedKmh)
    }

    @Test
    fun tripDistanceFuelAndEvShareAreIntegrated() {
        val e = DashboardEngine(catalog, rules)
        // 36 km/h for 100 s = 1 km; engine off for the first 50 s, on for the rest.
        for (i in 0..100) {
            val t = i * 1_000L
            val rpm = if (i < 50) 0.0 else 1500.0
            e.update(sample(t, "RPM" to n(rpm), "ECT" to n(80.0), "COROLLAHYBRID_READY" to n(1.0)))
            e.update(sample(t, "VSS" to n(36.0), "FUEL_RATE_ALT" to n(if (i < 50) 0.0 else 0.745)))
        }
        val s = e.state(100_000)
        assertEquals(1.0, s.tripKm, 1e-9)
        // Each 1 s step counts as EV when the engine reads stopped at its end: steps 1..49 of 100.
        assertEquals(49.0, s.evSharePct!!, 1e-6)
        // 0.745 g/s for 50 s (trapezoid: 49.5 s full + half a step) / 745 g/l
        val litres = 0.745 * 50.5 / 745
        assertEquals(1.0 / litres, s.avgKmPerL!!, 1e-6)
        assertEquals(DriveMode.ENGINE, s.driveMode, "engine on, no battery reading")
        assertEquals(WarmUpLevel.OK, s.warmUp)
    }

    @Test
    fun driveModeFollowsTheEngineAndTheBattery() {
        val e = DashboardEngine(catalog, rules)
        fun mode(t: Long, rpm: Double, amps: Double, kmh: Double, ready: Double = 1.0): DriveMode? {
            e.update(
                sample(
                    t, "RPM" to n(rpm), "VSS" to n(kmh), "COROLLAHYBRID_READY" to n(ready),
                    "COROLLAHYBRID_HVBAT_V" to n(200.0), "COROLLAHYBRID_HVBAT_C" to n(amps),
                ),
            )
            return e.state(t + 100).driveMode
        }
        // 200 V: 1 kW = 5 A.
        assertEquals(DriveMode.EV, mode(0, 0.0, 40.0, 30.0), "engine off, battery driving")
        assertEquals(DriveMode.REGEN, mode(1_000, 0.0, -40.0, 30.0), "engine off, charging while moving")
        assertEquals(DriveMode.EV, mode(2_000, 0.0, -40.0, 0.0), "standing still: not regen")
        assertEquals(DriveMode.ENGINE, mode(3_000, 1500.0, 2.0, 60.0), "battery within ±1 kW: idle")
        assertEquals(DriveMode.BOOST, mode(4_000, 2500.0, 60.0, 60.0), "both driving")
        assertEquals(DriveMode.CHARGE, mode(5_000, 1500.0, -30.0, 60.0), "engine filling the battery")
        assertNull(mode(6_000, 0.0, 0.0, 0.0, ready = 0.0), "not READY and standing: car off")
    }

    @Test
    fun evWhileReadyAndStopped() {
        val e = DashboardEngine(catalog, rules)
        e.update(sample(0, "RPM" to n(0.0), "VSS" to n(0.0), "COROLLAHYBRID_READY" to n(1.0), "ECT" to n(30.0)))
        val s = e.state(500)
        assertEquals(DriveMode.EV, s.driveMode)
        assertEquals(0.0, s.enginePowerKw)
        assertEquals(WarmUpLevel.COLD, s.warmUp)
        assertEquals(0.0, s.tripKm)
        assertNull(s.avgKmPerL)
    }

    @Test
    fun gapsAreNotIntegrated() {
        val e = DashboardEngine(catalog, rules)
        e.update(sample(0, "VSS" to n(100.0)))
        e.update(sample(60_000, "VSS" to n(100.0)))
        assertEquals(0.0, e.state(60_000).tripKm, "a 60 s dropout adds no distance")
        assertFalse(e.state(60_000).tripKm > 0)
        assertNotNull(e.state(60_000).speedKmh)
    }
}
