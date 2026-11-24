package com.hybridguard.data

import com.hybridguard.obd.RawSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dashboard and Logger sessions (docs/05 Phase 2 task 4) and what the dashboard shows between readings. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardPollingTest {

    private val dir: File = Files.createTempDirectory("hybridguard-dashpoll").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    // test-ev without the "soc" role: 7E4 22 0102 is then a request no dashboard value needs.
    private val loader = ProfileLoader(
        FakeProfiles.source(mapOf("test-ev/derived.json" to """{ "hvVoltage": ["TEST_HV_V"], "hvCurrent": ["TEST_HV_A"] }""")),
    )
    private val vin = "TST1ABCDEK1234567"
    private val store = InMemoryVehicleStore()

    private class Run(val requests: Map<String, Int>, val probed: Set<String>, val events: List<LogEntry.Event>, val state: SessionState)

    private suspend fun TestScope.session(mode: SessionMode): Run {
        val requests = mutableListOf<String>()
        val car = SimulatedCar(
            loader.load("test-ev").forModelYear(2019),
            clock = { testScheduler.currentTime },
            vin = vin,
            extra = { header, payload -> requests += "%03X:%s".format(header, payload); null },
        )
        val runner = SessionRunner(
            transportFactory = { car },
            loader = loader,
            selector = ProfileSelector(loader, store) { 2026 },
            logDir = dir,
            config = SessionConfig(appVersion = "test", source = "simulated", mode = mode),
            monotonicMs = { testScheduler.currentTime },
            epochMs = { 1_763_000_000_000 + testScheduler.currentTime },
        )
        val job = async { runner.run() }
        advanceTimeBy(20_000) // connect + probe
        val probed = requests.toSet()
        requests.clear()
        advanceTimeBy(60_000)
        val state = runner.state.value
        job.cancel()
        job.join()
        val events = SessionLogReader.readAll(runner.state.value.logFile!!).filterIsInstance<LogEntry.Event>()
        return Run(requests.groupingBy { it }.eachCount(), probed, events, state)
    }

    @Test
    fun dashboardModeReadsOnlyWhatTheDashboardNeeds() = runTest {
        val run = session(SessionMode.DASHBOARD)
        assertTrue((run.requests["7E4:220101"] ?: 0) >= 50, "dashboard request at its 1 s period: ${run.requests}")
        assertNull(run.requests["7E4:220102"], "not polled")
        assertFalse("7E4:220102" in run.probed, "not even probed")
        assertTrue(run.events.single { it.name == "pollPlan" }.text!!.startsWith("dashboard mode"))
        assertTrue(run.state.discovery!!.unavailableReason!!.contains("Logger"))
    }

    @Test
    fun loggerModeReadsEverything() = runTest {
        val run = session(SessionMode.LOGGER)
        assertTrue((run.requests["7E4:220102"] ?: 0) >= 50, "${run.requests}")
        assertTrue(run.events.single { it.name == "pollPlan" }.text!!.startsWith("logger mode"))
    }

    @Test
    fun aDashboardSessionAddsToTheSavedProbeAndALoggerSessionProbesOnlyTheRest() = runTest {
        val dashboard = session(SessionMode.DASHBOARD)
        assertTrue("7E4:220101" in dashboard.probed)

        assertEquals(3, dashboard.state.probe!!.total, "speed, rpm and 7E4 22 0101")

        val logger = session(SessionMode.LOGGER)
        assertTrue("7E4:220102" in logger.probed, "the request the dashboard skipped is probed now")
        assertEquals(1, logger.state.probe!!.total, "only the request not known from the dashboard session")

        val again = session(SessionMode.DASHBOARD)
        assertTrue(again.events.any { it.name == "probeCached" }, "everything known: no probe")
        assertNull(again.state.probe, "the probe did not run")
    }

    @Test
    fun advancedValuesArePolledOnlyWhileTheAdvancedScreenIsOpen() = runTest {
        // 7E4 22 0102 now carries an Advanced-screen role only.
        val loader = ProfileLoader(
            FakeProfiles.source(
                mapOf("test-ev/derived.json" to """{ "hvVoltage": ["TEST_HV_V"], "hvCurrent": ["TEST_HV_A"], "inverterPump": ["TEST_SOC"] }"""),
            ),
        )
        val catalog = loader.load("test-ev").forModelYear(2019)
        assertEquals(setOf("7E4:220102"), catalog.advancedCommandKeys)
        assertFalse("7E4:220102" in catalog.roleCommandKeys)

        val requests = mutableListOf<String>()
        val car = SimulatedCar(
            catalog,
            clock = { testScheduler.currentTime },
            vin = vin,
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
        val job = async { runner.run() }
        advanceTimeBy(20_000) // connect + probe
        assertTrue("7E4:220102" in requests, "probed with the dashboard's requests, so opening the screen needs no probe")

        requests.clear()
        advanceTimeBy(20_000)
        assertNull(requests.groupingBy { it }.eachCount()["7E4:220102"], "screen closed: not polled")
        assertNull(runner.state.value.dashboard!!.inverterPump)

        runner.setAdvanced(true)
        requests.clear()
        advanceTimeBy(20_000)
        assertTrue((requests.groupingBy { it }.eachCount()["7E4:220102"] ?: 0) >= 10, "screen open: at its 1 s period")
        assertNotNull(runner.state.value.dashboard!!.inverterPump)
        assertEquals("%", runner.state.value.dashboard!!.inverterPumpUnit)

        runner.setAdvanced(false)
        advanceTimeBy(1_000)
        requests.clear()
        advanceTimeBy(20_000)
        assertNull(requests.groupingBy { it }.eachCount()["7E4:220102"], "closed again: stops")

        job.cancel()
        job.join()
    }

    @Test
    fun aRoleReadsOnlyItsFirstSignalThatAnswered() {
        val catalog = ProfileLoader().load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val main = { r: Role -> !r.advanced && !r.detail }
        val all = catalog.pollKeys(main) { true }
        assertTrue("7D2:221F5B" in all, "hybrid ECU SOC, the first choice")
        assertFalse("7E0:015B" in all, "the SAE SOC fallback is not read while the first choice answers")
        assertFalse("7C0:221022" in all, "fuel level is a detail value")

        val noHybridSoc = catalog.pollKeys(main) { it != "7D2:221F5B" }
        assertTrue("7E0:015B" in noHybridSoc, "the fallback takes over")

        val nothing = catalog.pollKeys(main) { false }
        assertTrue("7D2:221F5B" in nothing && "7E0:015B" in nothing, "nothing answered: keep them all")

        val details = catalog.pollKeys({ it.detail }) { true }
        assertTrue("7C0:221022" in details && "747:221814" in details)
    }

    @Test
    fun detailValuesArePolledOnlyWhileADesignShowsThem() = runTest {
        // 7E4 22 0102 now carries a detail role only.
        val loader = ProfileLoader(
            FakeProfiles.source(
                mapOf("test-ev/derived.json" to """{ "hvVoltage": ["TEST_HV_V"], "hvCurrent": ["TEST_HV_A"], "fuelLitres": ["TEST_SOC"] }"""),
            ),
        )
        val catalog = loader.load("test-ev").forModelYear(2019)
        val requests = mutableListOf<String>()
        val car = SimulatedCar(
            catalog,
            clock = { testScheduler.currentTime },
            vin = vin,
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
        val job = async { runner.run() }
        advanceTimeBy(20_000) // connect + probe
        assertTrue("7E4:220102" in requests, "probed with the dashboard's requests")

        requests.clear()
        advanceTimeBy(20_000)
        assertNull(requests.groupingBy { it }.eachCount()["7E4:220102"], "not shown: not polled")

        runner.setDetails(true)
        requests.clear()
        advanceTimeBy(20_000)
        assertTrue((requests.groupingBy { it }.eachCount()["7E4:220102"] ?: 0) >= 10, "shown: at its 1 s period")
        assertNotNull(runner.state.value.dashboard!!.fuelLitres)

        runner.setDetails(false)
        advanceTimeBy(1_000)
        requests.clear()
        advanceTimeBy(20_000)
        assertNull(requests.groupingBy { it }.eachCount()["7E4:220102"], "hidden again: stops")

        job.cancel()
        job.join()
    }

    @Test
    fun batteryTabValuesArePolledOnlyWhileTheTabIsOpen() = runTest {
        // 7E4 22 0102 now carries a Battery tab role only (cell voltages).
        val loader = ProfileLoader(
            FakeProfiles.source(
                mapOf("test-ev/derived.json" to """{ "hvVoltage": ["TEST_HV_V"], "hvCurrent": ["TEST_HV_A"], "hvCellVoltage": ["TEST_SOC"] }"""),
            ),
        )
        val catalog = loader.load("test-ev").forModelYear(2019)
        val requests = mutableListOf<String>()
        val car = SimulatedCar(
            catalog,
            clock = { testScheduler.currentTime },
            vin = vin,
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
        val job = async { runner.run() }
        advanceTimeBy(20_000) // connect + probe
        assertTrue("7E4:220102" in requests, "probed once, so opening the tab needs no probe")

        requests.clear()
        advanceTimeBy(20_000)
        assertNull(requests.groupingBy { it }.eachCount()["7E4:220102"], "tab closed: not polled")

        runner.setBattery(true)
        requests.clear()
        advanceTimeBy(20_000)
        assertTrue((requests.groupingBy { it }.eachCount()["7E4:220102"] ?: 0) >= 10, "tab open: at its 1 s period")
        assertNotNull(runner.state.value.dashboard!!.lists[Role.HV_CELL_VOLTAGE])

        runner.setBattery(false)
        advanceTimeBy(1_000)
        requests.clear()
        advanceTimeBy(20_000)
        assertNull(requests.groupingBy { it }.eachCount()["7E4:220102"], "tab closed again: stops")

        job.cancel()
        job.join()
    }

    @Test
    fun aTileValueIsPolledOnlyWhileATileShowsIt() = runTest {
        // 7E4 22 0102 carries the engine water pump (an Advanced-screen role).
        val loader = ProfileLoader(
            FakeProfiles.source(
                mapOf(
                    "test-ev/derived.json" to
                        """{ "hvVoltage": ["TEST_HV_V"], "hvCurrent": ["TEST_HV_A"], "engineWaterPump": ["TEST_SOC"]}""",
                ),
            ),
        )
        val catalog = loader.load("test-ev").forModelYear(2019)
        val requests = mutableListOf<String>()
        val car = SimulatedCar(
            catalog,
            clock = { testScheduler.currentTime },
            vin = vin,
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
        val job = async { runner.run() }
        advanceTimeBy(20_000) // connect + probe
        requests.clear()
        advanceTimeBy(20_000)
        assertNull(requests.groupingBy { it }.eachCount()["7E4:220102"], "no tile shows it: not polled")

        runner.setExtraRoles(setOf(Role.ENGINE_WATER_PUMP))
        requests.clear()
        advanceTimeBy(20_000)
        val counts = requests.groupingBy { it }.eachCount()
        assertTrue((counts["7E4:220102"] ?: 0) >= 10, "on a tile: at its 1 s period")
        assertNotNull(runner.state.value.dashboard!!.engineWaterPump)

        // The Advanced screen opening and closing does not stop the tile's value.
        runner.setAdvanced(true)
        advanceTimeBy(5_000)
        runner.setAdvanced(false)
        advanceTimeBy(1_000)
        requests.clear()
        advanceTimeBy(20_000)
        assertTrue((requests.groupingBy { it }.eachCount()["7E4:220102"] ?: 0) >= 10, "still on the tile")

        runner.setExtraRoles(emptySet())
        advanceTimeBy(1_000)
        requests.clear()
        advanceTimeBy(20_000)
        assertNull(requests.groupingBy { it }.eachCount()["7E4:220102"], "tile changed: stops")

        job.cancel()
        job.join()
    }

    @Test
    fun theTripIsIntegratedForTheSummary() {
        val catalog = ProfileLoader().load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val e = DashboardEngine(catalog)
        fun at(t: Long, kmh: Double, rpm: Double, amps: Double) = e.update(
            DecodedSample(
                t, "x", RawSample.Status.OK,
                mapOf(
                    "VSS" to SignalValue.Number(kmh, "kilometersPerHour"),
                    "RPM" to SignalValue.Number(rpm, "rpm"),
                    "COROLLAHYBRID_HVBAT_V" to SignalValue.Number(200.0, "volts"),
                    "COROLLAHYBRID_HVBAT_C" to SignalValue.Number(amps, "amps"),
                ),
            ),
        )
        // 60 s at 36 km/h on the battery (+10 A = 2 kW), then 60 s at 72 km/h on the engine, charging (-5 A = -1 kW).
        for (s in 0..60) at(s * 1_000L, 36.0, 0.0, 10.0)
        for (s in 61..120) at(s * 1_000L, 72.0, 1500.0, -5.0)
        val trip = e.state(120_000).trip
        assertEquals(120_000, trip.durationMs)
        assertEquals(0.6 + 0.015 + 1.18, trip.km, 1e-9) // the 60 -> 61 s step averages 54 km/h
        assertEquals(0.615, trip.evKm, 1e-9) // that step still counts as EV: its rpm arrives after the speed
        assertEquals(72.0, trip.maxSpeedKmh)
        assertEquals(2.0 * 60 / 3600 + 2.0 / 3600, trip.batteryOutKwh!!, 1e-9) // the first engine step still holds +10 A
        assertEquals(1.0 * 59 / 3600, trip.batteryInKwh!!, 1e-9)
        assertNull(trip.fuelLitres, "no fuel rate: unknown, not 0")
        assertEquals(trip.km / (120.0 / 3600), trip.avgSpeedKmh!!, 1e-9)
    }

    @Test
    fun aValueReadEvery8SecondsStaysOnScreenAndGoesWhenItStops() {
        val catalog = ProfileLoader().load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val e = DashboardEngine(catalog)
        fun soc(t: Long) = e.update(DecodedSample(t, "x", RawSample.Status.OK, mapOf("COROLLAHYBRID_SOC" to SignalValue.Number(55.0, "percent"))))
        soc(0)
        soc(8_000)
        soc(16_000)
        assertNotNull(e.state(23_000).socPct, "7 s old, but it arrives every 8 s")
        assertNotNull(e.state(39_000).socPct, "up to 3 gaps late")
        assertNull(e.state(41_000).socPct, "stopped arriving")
    }

    @Test
    fun theGearIsHeldWhileTheLeverIsBetweenPositions() {
        val catalog = ProfileLoader().load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val e = DashboardEngine(catalog)
        fun gear(t: Long, v: SignalValue) = e.update(DecodedSample(t, "x", RawSample.Status.OK, mapOf("COROLLAHYBRID_SHIFT_POS" to v)))
        gear(0, SignalValue.Enumerated("D", "Drive"))
        gear(500, SignalValue.NoValue) // an unmapped code while shifting
        assertEquals("D", e.state(600).gear)
        gear(1_000, SignalValue.Enumerated("R", "Reverse"))
        assertEquals("R", e.state(1_100).gear)
        // A value that keeps coming back empty disappears once the last good one is stale.
        for (t in 1_500L..20_000L step 500) gear(t, SignalValue.NoValue)
        assertNull(e.state(20_000).gear)
    }
}
