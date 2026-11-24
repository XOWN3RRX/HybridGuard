package com.hybridguard.data

import com.hybridguard.obd.RawSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cooling alarms (docs/02 C.7): the checks, the acknowledgement and repeats, and their polling. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmsTest {

    private val corolla = ProfileLoader().alarmRules("toyota-corolla-hybrid-e210")!!

    /** Readings set by the test, all fresh. */
    private class Now : RoleReadings {
        val values = mutableMapOf<Role, Double>()
        override fun number(role: Role) = values[role]
        override fun unit(role: Role) = when (role) {
            Role.ENGINE_WATER_PUMP, Role.ENGINE_WATER_PUMP_TARGET, Role.ENGINE_RPM, Role.INVERTER_PUMP -> "rpm"
            Role.READY -> ""
            else -> "°C"
        }
    }

    /** Runs [monitor] once a second from [from] to [to] s; returns the alarm ids on at the end. */
    private fun run(monitor: AlarmMonitor, r: Now, from: Int, to: Int, each: (Int) -> Unit = {}): List<String> {
        var ids = emptyList<String>()
        for (s in from..to) {
            each(s)
            ids = monitor.update(s * 1_000L, r).first.map { it.id }
        }
        return ids
    }

    @Test
    fun theCorollaRulesLoadAndNameTheirRoles() {
        assertEquals(13, corolla.checks.size)
        // Three optional checks (HV battery, motor inverter, 12 V) are off by default.
        assertEquals(10, corolla.checks.count { it.enabledByDefault })
        assertTrue(Role.ENGINE_WATER_PUMP in corolla.roles)
        assertTrue(Role.ENGINE_WATER_PUMP_TARGET in corolla.roles)
        assertTrue(Role.INVERTER_PUMP in corolla.roles)
        assertTrue(Role.READY in corolla.roles)
        assertTrue(Role.INVERTER_COOLANT_TEMP in corolla.roles)
        assertTrue(Role.COOLANT_TEMP in corolla.roles)
        assertEquals(emptyList(), corolla.notWatched, "both pumps are readable on the Corolla (profile 9)")
        // The sae-j1979 base profile has none.
        assertNull(ProfileLoader().alarmRules("sae-j1979"))
    }

    @Test
    fun aStoppedEnginePumpIsAStopAlarmAfterTenSeconds() {
        val m = AlarmMonitor(corolla)
        val r = Now()
        r.values += mapOf(Role.ENGINE_RPM to 0.0, Role.ENGINE_WATER_PUMP to 0.0, Role.ENGINE_WATER_PUMP_TARGET to 0.0, Role.COOLANT_TEMP to 85.0)
        assertEquals(emptyList(), run(m, r, 0, 30), "engine stopped: the pump is off as it should be")

        // The engine starts and the pump follows (logged: 1525 rpm 6 s after the start).
        r.values += mapOf(Role.ENGINE_RPM to 1200.0, Role.ENGINE_WATER_PUMP_TARGET to 1575.0)
        assertEquals(emptyList(), run(m, r, 31, 36), "pump still spinning up")
        r.values[Role.ENGINE_WATER_PUMP] = 1525.0
        assertEquals(emptyList(), run(m, r, 37, 90), "pump working")

        // It stops while the engine runs.
        r.values[Role.ENGINE_WATER_PUMP] = 0.0
        assertEquals(emptyList(), run(m, r, 91, 99), "under 10 s: not yet")
        val (alarms, watch) = m.update(101_000, r)
        val stop = alarms.single { it.id == "engineWaterPumpStopped" }
        assertEquals(AlarmLevel.STOP, stop.level)
        assertEquals("pump 0 rpm (asked 1575 rpm) · engine 1200 rpm · coolant 85 °C", stop.detail)
        assertTrue(watch.single { it.group == "Engine water pump" }.watching)
        assertFalse(watch.single { it.group == "Inverter water pump" }.watching, "no inverter pump reading in this test")

        // The slow-pump caution comes after its 30 s as well; both stay until the pump runs again for 20 s.
        assertEquals(listOf("engineWaterPumpStopped", "engineWaterPumpSlow"), run(m, r, 102, 125))
        r.values[Role.ENGINE_WATER_PUMP] = 1500.0
        assertEquals(2, run(m, r, 126, 140).size, "still on just after it recovers")
        assertEquals(emptyList(), run(m, r, 141, 150))
    }

    @Test
    fun anEngineStopIsNotAPumpFailure() {
        val m = AlarmMonitor(corolla)
        val r = Now()
        r.values += mapOf(Role.ENGINE_RPM to 1500.0, Role.ENGINE_WATER_PUMP to 1500.0, Role.ENGINE_WATER_PUMP_TARGET to 1550.0, Role.COOLANT_TEMP to 80.0)
        run(m, r, 0, 60)
        // EV stretch: engine, pump and target all go to 0 (logged within one sample).
        assertEquals(emptyList(), run(m, r, 61, 200) { r.values += mapOf(Role.ENGINE_RPM to 0.0, Role.ENGINE_WATER_PUMP to 0.0, Role.ENGINE_WATER_PUMP_TARGET to 0.0) })
        // The engine restarts and the pump reads 0 for the first seconds.
        r.values += mapOf(Role.ENGINE_RPM to 1300.0, Role.ENGINE_WATER_PUMP_TARGET to 1975.0)
        assertEquals(emptyList(), run(m, r, 201, 208))
        r.values[Role.ENGINE_WATER_PUMP] = 1875.0
        assertEquals(emptyList(), run(m, r, 209, 260))
    }

    @Test
    fun aPumpWithoutReadingsRaisesNothing() {
        val m = AlarmMonitor(corolla)
        val r = Now()
        r.values += mapOf(Role.ENGINE_RPM to 2000.0, Role.COOLANT_TEMP to 90.0)
        assertEquals(emptyList(), run(m, r, 0, 120), "no pump reading (not answering): no alarm")
        val watch = m.update(121_000, r).second
        assertFalse(watch.single { it.group == "Engine water pump" }.watching)
        assertTrue(watch.single { it.group == "Engine coolant" }.watching)
        assertFalse(watch.single { it.group == "Inverter coolant" }.watching)
    }

    @Test
    fun hotCoolantIsACautionThenAStopWithHysteresis() {
        val m = AlarmMonitor(corolla)
        val r = Now()
        r.values[Role.COOLANT_TEMP] = 102.0
        assertEquals(emptyList(), run(m, r, 0, 100))
        r.values[Role.COOLANT_TEMP] = 104.0
        assertEquals(emptyList(), run(m, r, 101, 119))
        assertEquals(listOf("coolantHot"), run(m, r, 120, 121))
        r.values[Role.COOLANT_TEMP] = 111.0
        val ids = run(m, r, 122, 133)
        assertEquals("coolantVeryHot", ids.first(), "STOP first")
        // 109 °C is within the 2 °C hysteresis: still on long after the clear time.
        r.values[Role.COOLANT_TEMP] = 109.0
        assertEquals("coolantVeryHot", run(m, r, 134, 200).first())
        r.values[Role.COOLANT_TEMP] = 100.0
        assertEquals(emptyList(), run(m, r, 201, 230))
    }

    @Test
    fun coolantClimbingFastWhileWarmIsACaution() {
        val m = AlarmMonitor(corolla)
        val r = Now()
        // Warm-up from cold climbs fast too (logged 5 -> 43 °C in a few minutes) but is below 97 °C.
        assertEquals(emptyList(), run(m, r, 0, 300) { s -> r.values[Role.COOLANT_TEMP] = 20.0 + s * 0.2 })
        // Steady at 92 °C, then 5 °C a minute from 95 °C.
        r.values[Role.COOLANT_TEMP] = 92.0
        run(m, r, 301, 400)
        val ids = run(m, r, 401, 470) { s -> r.values[Role.COOLANT_TEMP] = 95.0 + (s - 400) * 5.0 / 60 }
        assertEquals(listOf("coolantRising"), ids)
        assertEquals("now 101 °C · rising 5 °C a minute", m.update(471_000, r).first.single().detail)
    }

    @Test
    fun aStoppedInverterPumpIsAStopAlarmWhileReady() {
        val m = AlarmMonitor(corolla)
        val r = Now()
        // Before READY the pump idles (logged: 160 rpm): not a failure.
        r.values += mapOf(Role.READY to 0.0, Role.INVERTER_PUMP to 160.0, Role.INVERTER_COOLANT_TEMP to 12.0)
        assertEquals(emptyList(), run(m, r, 0, 60), "not READY")
        // READY: it spins up within the 15 s allowed, to the speeds logged on the Corolla.
        r.values[Role.READY] = 1.0
        assertEquals(emptyList(), run(m, r, 61, 70), "spinning up")
        r.values[Role.INVERTER_PUMP] = 4248.0
        assertEquals(emptyList(), run(m, r, 71, 200), "cold inverter: about 4250 rpm")
        r.values[Role.INVERTER_PUMP] = 3432.0
        assertEquals(emptyList(), run(m, r, 201, 400), "warm: about 3450 rpm")

        // It stops while READY: STOP after 10 s, the slow caution after 30 s.
        r.values[Role.INVERTER_PUMP] = 0.0
        assertEquals(emptyList(), run(m, r, 401, 409), "under 10 s: not yet")
        val (alarms, watch) = m.update(411_000, r)
        val stop = alarms.single()
        assertEquals("inverterWaterPumpStopped", stop.id)
        assertEquals(AlarmLevel.STOP, stop.level)
        assertEquals("pump 0 rpm · inverter coolant 12 °C", stop.detail)
        assertTrue(watch.single { it.group == "Inverter water pump" }.watching)
        assertEquals(listOf("inverterWaterPumpStopped", "inverterWaterPumpSlow"), run(m, r, 412, 435))

        // READY off: the pump idles again and both alarms clear after their 20 s.
        r.values += mapOf(Role.READY to 0.0, Role.INVERTER_PUMP to 160.0)
        assertEquals(emptyList(), run(m, r, 436, 460))
    }

    @Test
    fun aSlowInverterPumpIsACaution() {
        val m = AlarmMonitor(corolla)
        val r = Now()
        r.values += mapOf(Role.READY to 1.0, Role.INVERTER_PUMP to 3450.0)
        run(m, r, 0, 60)
        r.values[Role.INVERTER_PUMP] = 2000.0
        assertEquals(emptyList(), run(m, r, 61, 89), "under 30 s: not yet")
        assertEquals(listOf("inverterWaterPumpSlow"), run(m, r, 90, 92))
        assertEquals(AlarmLevel.CAUTION, m.update(93_000, r).first.single().level)
    }

    @Test
    fun theInverterLoopIsAlsoWatchedThroughItsCoolant() {
        val m = AlarmMonitor(corolla)
        val r = Now()
        r.values[Role.INVERTER_COOLANT_TEMP] = 40.0
        assertEquals(emptyList(), run(m, r, 0, 200))
        // A failed pump: the coolant inside the inverter climbs 3 °C a minute.
        val climb = { s: Int -> r.values[Role.INVERTER_COOLANT_TEMP] = 40.0 + (s - 200) * 3.0 / 60 }
        assertEquals(listOf("inverterCoolantRising"), run(m, r, 201, 600, climb), "60 °C at 600 s: rising, not hot yet")
        assertEquals("inverterCoolantVeryHot", run(m, r, 601, 1000, climb).first(), "75 °C from 900 s: stop")
    }

    @Test
    fun badRulesAreRefused() {
        fun parse(check: String) = AlarmRules.parse("""{ "checks": [ $check ] }""", "x/alarms.json")
        assertFailsWith<ProfileFormatException> { parse("""{ "id": "a", "group": "g", "kind": "tooHot", "level": "loud", "temp": "coolantTemp", "from": 1, "forSeconds": 1, "title": "t", "advice": "a" }""") }
        assertFailsWith<ProfileFormatException> { parse("""{ "id": "a", "group": "g", "kind": "tooHot", "level": "stop", "temp": "noSuchRole", "from": 1, "forSeconds": 1, "title": "t", "advice": "a" }""") }
        assertFailsWith<ProfileFormatException> { parse("""{ "id": "a", "group": "g", "kind": "pumpLow", "level": "stop", "pump": "engineWaterPump", "runsWhile": "engineRpm", "runsAbove": 1, "forSeconds": 1, "title": "t", "advice": "a" }""") }
        assertFailsWith<ProfileFormatException> { parse("""{ "id": "a", "group": "g", "kind": "pumpLow", "level": "stop", "pump": "engineWaterPump", "runsWhile": "engineRpm", "runsAbove": 1, "belowShareOfTarget": 0.5, "forSeconds": 1, "title": "t", "advice": "a" }""") }
    }

    private fun alarm(id: String, level: AlarmLevel, since: Long = 0) = Alarm(id, level, id, "advice", "", since)

    @Test
    fun aStopAlarmRepeatsUntilOkThenReminds() {
        val g = AlarmGate()
        val stop = listOf(alarm("pump", AlarmLevel.STOP))
        val v = g.update(stop, 0)
        assertEquals("pump", v.announce?.id)
        assertTrue(v.fullScreen)
        assertNull(g.update(stop, 1_000).announce, "once per repeat time")
        assertNull(g.update(stop, 29_000).announce)
        assertEquals("pump", g.update(stop, 30_000).announce?.id, "said again after 30 s")
        g.acknowledge(35_000)
        val seen = g.update(stop, 36_000)
        assertFalse(seen.unacknowledged)
        assertFalse(seen.fullScreen, "after OK: the band only")
        assertEquals(1, seen.alarms.size)
        assertNull(g.update(stop, 300_000).announce)
        assertEquals("pump", g.update(stop, 335_000).announce?.id, "reminder 5 min after OK")
        assertFalse(g.update(stop, 336_000).unacknowledged, "a reminder does not take the screen again")
    }

    @Test
    fun aCautionIsSaidOnceAndAWorseLevelStartsOver() {
        val g = AlarmGate()
        val caution = listOf(alarm("hot", AlarmLevel.CAUTION))
        assertEquals("hot", g.update(caution, 0).announce?.id)
        assertNull(g.update(caution, 60_000).announce)
        assertTrue(g.update(caution, 60_000).unacknowledged)
        assertFalse(g.update(caution, 60_000).fullScreen, "a caution never takes the whole screen")
        g.acknowledge(61_000)
        assertFalse(g.update(caution, 62_000).unacknowledged)

        // A second alarm comes on: the screen waits for OK again, it is said, and the STOP goes first.
        val both = listOf(alarm("hot", AlarmLevel.CAUTION), alarm("pump", AlarmLevel.STOP, since = 63_000))
        val v = g.update(both, 63_000)
        assertEquals("pump", v.announce?.id)
        assertEquals("pump", v.top?.id)
        assertTrue(v.fullScreen)

        // Gone and back: new again.
        g.acknowledge(64_000)
        assertTrue(g.update(emptyList(), 65_000).alarms.isEmpty())
        assertEquals("hot", g.update(caution, 66_000).announce?.id)
    }

    @Test
    fun theEngineRaisesTheAlarmFromDecodedSamples() {
        val catalog = ProfileLoader().load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val e = DashboardEngine(catalog, alarms = corolla)
        fun at(s: Int, rpm: Double, pump: Double, target: Double) {
            e.update(
                DecodedSample(
                    s * 1_000L, "x", RawSample.Status.OK,
                    mapOf(
                        "RPM" to SignalValue.Number(rpm, "rpm"),
                        "COROLLAHYBRID_EWP_RPM" to SignalValue.Number(pump, "rpm"),
                        "COROLLAHYBRID_EWP_TARGET_VSS" to SignalValue.Number(target, "rpm"),
                        "ECT" to SignalValue.Number(88.0, "celsius"),
                    ),
                ),
            )
            e.state(s * 1_000L)
        }
        for (s in 0..30) at(s, 1800.0, 2775.0, 2900.0)
        assertEquals(emptyList(), e.state(30_000).alarms)
        for (s in 31..45) at(s, 1800.0, 0.0, 2900.0)
        val alarm = e.state(45_000).alarms.single()
        assertEquals("engineWaterPumpStopped", alarm.id)
        assertEquals("pump 0 rpm (asked 2900 rpm) · engine 1800 rpm · coolant 88 °C", alarm.detail)
        // Readings stop (link lost): the inputs go stale and the alarm clears after its time.
        assertEquals(1, e.state(80_000).alarms.size)
        assertTrue(e.state(100_000).alarms.isEmpty())
    }

    private val dir: File = Files.createTempDirectory("hybridguard-alarms").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun thePumpIsPolledAllTheTimeWhileTheAlarmsAreOn() = runTest {
        // 7E4 22 0102 stands in for the pump (an Advanced-screen role), polled only for the alarms.
        val loader = ProfileLoader(
            FakeProfiles.source(
                mapOf(
                    "test-ev/derived.json" to
                        """{ "hvVoltage": ["TEST_HV_V"], "hvCurrent": ["TEST_HV_A"], "engineWaterPump": ["TEST_SOC"]}""",
                    "test-ev/alarms.json" to """{ "checks": [
                        { "id": "p", "group": "Pump", "kind": "pumpLow", "level": "stop", "pump": "engineWaterPump",
                          "runsWhile": "engineRpm", "runsAbove": 500, "below": 1, "forSeconds": 10, "title": "Pump stopped", "advice": "Stop" } ] }""",
                ),
            ),
        )
        val requests = mutableListOf<String>()
        val car = SimulatedCar(
            loader.load("test-ev").forModelYear(2019),
            clock = { testScheduler.currentTime },
            vin = "TST1ABCDEK1234567",
            extra = { header, payload -> requests += "%03X:%s".format(header, payload); null },
        )
        val runner = SessionRunner(
            transportFactory = { car },
            loader = loader,
            selector = ProfileSelector(loader, InMemoryVehicleStore()) { 2026 },
            logDir = dir,
            config = SessionConfig(appVersion = "test", source = "simulated", mode = SessionMode.DASHBOARD),
            monotonicMs = { testScheduler.currentTime },
            epochMs = { 1_763_000_000_000 + testScheduler.currentTime },
        )
        runner.setAlarms(true)
        val job = async { runner.run() }
        advanceTimeBy(20_000) // connect + probe
        requests.clear()
        advanceTimeBy(20_000)
        assertTrue((requests.groupingBy { it }.eachCount()["7E4:220102"] ?: 0) >= 10, "alarms on: polled")
        assertNotNull(runner.state.value.dashboard!!.alarmWatch.singleOrNull { it.group == "Pump" })

        runner.setAlarms(false)
        advanceTimeBy(1_000)
        requests.clear()
        advanceTimeBy(20_000)
        assertNull(requests.groupingBy { it }.eachCount()["7E4:220102"], "alarms off and not on screen: not polled")

        job.cancel()
        job.join()
    }
}
