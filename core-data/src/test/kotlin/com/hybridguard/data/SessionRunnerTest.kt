package com.hybridguard.data

import com.hybridguard.obd.ObdTransport
import com.hybridguard.obd.RawSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRunnerTest {

    private val dir: File = Files.createTempDirectory("hybridguard-session").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val loader = ProfileLoader(FakeProfiles.source())
    private val store = InMemoryVehicleStore()
    private val selector = ProfileSelector(loader, store) { 2026 }
    private val testVin = "TST1ABCDEK1234567"

    private fun TestScope.simulatedCar(vin: String = testVin) =
        SimulatedCar(loader.load("test-ev").forModelYear(2019), clock = { testScheduler.currentTime }, vin = vin)

    private fun TestScope.runner(
        transport: () -> ObdTransport,
        choice: ProfileChoice = ProfileChoice.Auto,
        source: String = "simulated",
    ) = SessionRunner(
        transportFactory = transport,
        loader = loader,
        selector = selector,
        logDir = dir,
        config = SessionConfig(appVersion = "test", source = source, profileChoice = choice),
        monotonicMs = { testScheduler.currentTime },
        epochMs = { 1_763_000_000_000 + testScheduler.currentTime },
    )

    private fun samplesIn(file: File) = SessionLogReader.readAll(file).filterIsInstance<LogEntry.Sample>().map { it.sample }

    @Test
    fun fullSessionWithTheSimulatedCar() = runTest {
        val car = simulatedCar()
        val runner = runner({ car })
        val job = async { runner.run() }

        advanceTimeBy(60_000)
        val state = runner.state.value
        assertEquals(SessionPhase.POLLING, state.phase)
        assertEquals(testVin, state.vin)
        assertEquals("test-ev", state.profile!!.id)
        assertEquals(SelectionReason.VIN_MATCH, state.selectionReason)
        assertEquals(2019, state.modelYear)
        assertEquals(4, state.polledRequests, "010D 010C 220101 220102; filtered and disabled ones left out")
        assertTrue(state.requestsPerSecond > 10, "rate ${state.requestsPerSecond}")
        val hv = assertNotNull(state.live["7E4:220101"]?.decoded)
        assertIs<SignalValue.Number>(hv.values["TEST_HV_V"])

        job.cancel()
        job.join()

        val file = runner.state.value.logFile!!
        assertTrue(file.name.endsWith(".jsonl.gz"), file.name)
        val entries = SessionLogReader.readAll(file)
        val meta = assertIs<LogEntry.Meta>(entries.first()).meta
        assertEquals("simulated", meta.source)
        val vehicle = entries.filterIsInstance<LogEntry.Vehicle>().single().info
        assertEquals(testVin, vehicle.vin)
        assertEquals("test-ev", vehicle.profileId)
        assertEquals(setOf(0x0C, 0x0D), vehicle.supportedPids)
        assertTrue(entries.indexOfFirst { it is LogEntry.Sample } < entries.indexOfFirst { it is LogEntry.Vehicle }, "VIN request logged before the vehicle line")
        assertTrue(entries.any { it is LogEntry.Probe })
        val end = assertIs<LogEntry.End>(entries.last()).summary
        assertEquals("\"stopped\"", end["reason"].toString())
        assertTrue(samplesIn(file).size > 500)
        assertEquals(store.get(testVin)!!.capabilities, runner.state.value.capabilities, "probe cached for the VIN")
    }

    @Test
    fun secondSessionReusesTheCachedProbe() = runTest {
        val first = runner({ simulatedCar() })
        val job1 = async { first.run() }
        advanceTimeBy(20_000)
        job1.cancel()
        job1.join()

        val second = runner({ simulatedCar() })
        val job2 = async { second.run() }
        advanceTimeBy(20_000)
        assertTrue(second.state.value.probeWasCached)
        job2.cancel()
        job2.join()
        assertTrue(SessionLogReader.readAll(second.state.value.logFile!!).any { it is LogEntry.Event && it.name == "probeCached" })
    }

    @Test
    fun sessionEndsByItselfWhenTheCarGoesSilent() = runTest {
        val car = simulatedCar()
        val runner = runner({ car })
        val job = async { runner.run() }
        advanceTimeBy(30_000)
        car.silent = true
        advanceTimeBy(60_000)

        assertTrue(job.isCompleted, "session ended without being cancelled")
        assertEquals(SessionPhase.ENDED, runner.state.value.phase)
        val end = assertIs<LogEntry.End>(SessionLogReader.readAll(runner.state.value.logFile!!).last()).summary
        assertTrue(end["reason"].toString().contains("car stopped answering"), end.toString())
    }

    @Test
    fun repliesWhileWaitingForTheCarAreShownAndLogged() = runTest {
        val car = simulatedCar().apply { silent = true }
        val runner = runner({ car })
        val job = async { runner.run() }
        advanceTimeBy(20_000)

        val waiting = runner.state.value
        assertEquals(SessionPhase.WAITING_FOR_CAR, waiting.phase)
        assertNotNull(waiting.logFile, "the log starts with the adapter")
        assertTrue(waiting.recent.isNotEmpty())
        assertTrue(waiting.recent.any { it.header == 0x7DF && it.request == "0100" }, "broadcast address tried too")
        assertTrue(waiting.recent.all { it.response == "NO DATA" }, "${waiting.recent}")
        assertTrue(waiting.message!!.contains("not answering"))

        car.silent = false
        advanceTimeBy(20_000)
        assertEquals(SessionPhase.POLLING, runner.state.value.phase)
        job.cancel()
        job.join()

        val entries = SessionLogReader.readAll(runner.state.value.logFile!!)
        val firstVehicle = entries.indexOfFirst { it is LogEntry.Vehicle }
        val waitingReplies = entries.take(firstVehicle).filterIsInstance<LogEntry.Sample>()
        assertTrue(waitingReplies.count { it.sample.status == RawSample.Status.NO_DATA } >= 10, "waiting replies are in the log")
    }

    @Test
    fun carThatNeverAnswersStillLeavesALog() = runTest {
        val runner = runner({ simulatedCar().apply { silent = true } })
        val job = async { runner.run() }
        advanceTimeBy(15_000)
        job.cancel()
        job.join()

        val entries = SessionLogReader.readAll(runner.state.value.logFile!!)
        assertIs<LogEntry.Meta>(entries.first())
        assertTrue(entries.none { it is LogEntry.Vehicle })
        assertTrue(entries.any { it is LogEntry.Sample })
        assertIs<LogEntry.End>(entries.last())
    }

    @Test
    fun carAnsweringOnlyTheBroadcastAddressIsDetected() = runTest {
        val broadcastOnly = object : com.hybridguard.obd.FakeElmTransport() {
            override fun respond(header: Int, payloadHex: String): String =
                if (header == 0x7DF && payloadHex == "0100") frames(0x7E8, byteArrayOf(0x41, 0x00, 0x00, 0x18, 0x00, 0x00)) else "NO DATA"
        }
        val runner = runner({ broadcastOnly })
        val job = async { runner.run() }
        advanceTimeBy(30_000)
        assertTrue(runner.state.value.phase in setOf(SessionPhase.PROBING, SessionPhase.POLLING), "${runner.state.value.phase}")
        job.cancel()
        job.join()

        val entries = SessionLogReader.readAll(runner.state.value.logFile!!)
        assertTrue(entries.any { it is LogEntry.Event && it.name == "note" && it.text!!.contains("7DF") })
        assertEquals(setOf(0x0C, 0x0D), entries.filterIsInstance<LogEntry.Vehicle>().single().info.supportedPids)
    }

    @Test
    fun reconnectsIntoTheSameLogAfterADropout() = runTest {
        val car = simulatedCar()
        var connections = 0
        val runner = runner({
            connections++
            if (connections == 1) DropsAfter(car, writes = 200) else car
        })
        val job = async { runner.run() }
        advanceTimeBy(60_000)

        val state = runner.state.value
        assertEquals(SessionPhase.POLLING, state.phase)
        assertEquals(2, connections)
        assertEquals(1, state.reconnects)
        job.cancel()
        job.join()

        val events = SessionLogReader.readAll(runner.state.value.logFile!!).filterIsInstance<LogEntry.Event>().map { it.name }
        assertTrue(events.containsAll(listOf("disconnected", "reconnected")), "$events")
        assertEquals(1, dir.listFiles()!!.size, "one session, one file")
    }

    @Test
    fun manualProfileChoiceIsUsedAndRemembered() = runTest {
        val unknownVin = "ZZZ9ZZZZZK1234567"
        val runner = runner({ simulatedCar(unknownVin) }, choice = ProfileChoice.Manual("test-ev", 2019))
        val job = async { runner.run() }
        advanceTimeBy(20_000)
        assertEquals("test-ev", runner.state.value.profile!!.id)
        assertEquals(SelectionReason.MANUAL, runner.state.value.selectionReason)
        job.cancel()
        job.join()
        assertEquals(ProfileSelection("test-ev", 2019, SelectionReason.MANUAL), selector.select(unknownVin))
    }

    @Test
    fun replayOfARecordedSessionGivesTheSameReplies() = runTest {
        val recorder = runner({ simulatedCar() })
        val job1 = async { recorder.run() }
        advanceTimeBy(20_000)
        job1.cancel()
        job1.join()
        val recorded = recorder.state.value.logFile!!

        val replay = runner({ ReplayCar.fromLog(recorded) }, source = "replay")
        val job2 = async { replay.run() }
        advanceTimeBy(20_000)
        assertEquals(testVin, replay.state.value.vin)
        assertEquals(SessionPhase.POLLING, replay.state.value.phase)
        job2.cancel()
        job2.join()

        val recordedReplies = samplesIn(recorded).groupBy({ it.request }, { it.response })
        for (s in samplesIn(replay.state.value.logFile!!)) {
            assertTrue(s.response in recordedReplies[s.request].orEmpty(), "${s.request} -> ${s.response}")
        }
    }

    @Test
    fun engineEcuThatOnlyAnswersTheBroadcastAddressIsReachedThroughIt() = runTest {
        // Like the owner's car: 7E0 is silent, the same ECU answers 7DF.
        val car = SimulatedCar(
            loader.load("test-ev").forModelYear(2019), clock = { testScheduler.currentTime },
            vin = testVin, ignoredPhysicalHeaders = setOf(0x7E0),
        )
        val recording = Recording(car)
        val runner = runner({ recording })
        val job = async { runner.run() }
        advanceTimeBy(60_000)

        val state = runner.state.value
        assertEquals(SessionPhase.POLLING, state.phase)
        assertEquals(testVin, state.vin, "VIN read through the broadcast address")
        assertEquals(SelectionReason.VIN_MATCH, state.selectionReason)
        val speed = assertNotNull(state.live["7E0:010D"], "listed under its catalog command")
        assertEquals(0x7DF, speed.sample.header)
        assertIs<SignalValue.Number>(speed.decoded!!.values["VSS"])
        assertTrue(state.capabilities!!.results["7E0:010D"]!!.answered)
        assertTrue(state.requestsPerSecond > 10, "rate ${state.requestsPerSecond}")

        // Standard requests go to 7DF with the engine's reply id as filter and a response count.
        assertTrue("ATCRA7E8" in recording.writes)
        assertTrue("010D1" in recording.writes, "single-frame reply -> response count 1")
        job.cancel()
        job.join()
    }

    @Test
    fun requestsWithoutReplyArePolledSlowlyUntilTheyAnswer() = runTest {
        val car = simulatedCar().apply { unanswered += "220102" }
        val runner = runner({ car })
        val job = async { runner.run() }
        advanceTimeBy(5_000) // probe done
        val probeEnd = testScheduler.currentTime
        advanceTimeBy(60_000)
        job.cancel()
        job.join()
        val log = SessionLogReader.readAll(runner.state.value.logFile!!)
        val polls = log.filterIsInstance<LogEntry.Sample>().map { it.sample }.filter { it.request == "220102" && it.tMs > probeEnd }
        assertTrue(polls.size in 1..3, "silent request polled ${polls.size} times in 60 s")
        assertTrue(log.any { it is LogEntry.Event && it.name == "slowPolling" })
    }

    @Test
    fun slowRequestReturnsToItsPeriodOnceItAnswers() = runTest {
        val car = simulatedCar().apply { unanswered += "220102" }
        val runner = runner({ car })
        val job = async { runner.run() }
        advanceTimeBy(10_000)
        car.unanswered.clear()
        advanceTimeBy(60_000) // the slow retry (every 30 s) now gets an answer
        job.cancel()
        job.join()

        val log = SessionLogReader.readAll(runner.state.value.logFile!!)
        val resumed = log.filterIsInstance<LogEntry.Event>().single { it.name == "resumed" }
        assertTrue(resumed.text!!.contains("7E4:220102"))
        val after = log.filterIsInstance<LogEntry.Sample>()
            .filter { it.sample.request == "220102" && it.sample.tMs > resumed.tMs && it.sample.tMs <= resumed.tMs + 20_000 }
        assertTrue(after.size >= 15, "polled ${after.size} times in the 20 s after it answered, at its 1 s period")
    }

    @Test
    fun responseCountOnlyForSingleFrameReplies() = runTest {
        val bundled = ProfileLoader()
        val catalog = bundled.load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val recording = Recording(SimulatedCar(catalog, clock = { testScheduler.currentTime }))
        val runner = SessionRunner(
            transportFactory = { recording },
            loader = bundled,
            selector = ProfileSelector(bundled, InMemoryVehicleStore()) { 2026 },
            logDir = dir,
            config = SessionConfig(appVersion = "test", source = "simulated", profileChoice = ProfileChoice.Manual("toyota-corolla-hybrid-e210", 2019)),
            monotonicMs = { testScheduler.currentTime },
            epochMs = { 1_763_000_000_000 + testScheduler.currentTime },
        )
        val job = async { runner.run() }
        advanceTimeBy(120_000)
        assertEquals(SessionPhase.POLLING, runner.state.value.phase)
        job.cancel()
        job.join()

        assertTrue("221F5B1" in recording.writes, "SOC reply is a single frame")
        assertTrue("221F9A" in recording.writes && "221F9A1" !in recording.writes, "HV battery reply is multi-frame: no count")
    }

    @Test
    fun unknownModelYearIsAssumedAndFlagged() = runTest {
        val bundled = ProfileLoader()
        val vin = "SB1K93BE00E000001" // made up; European style, no year at position 10
        val catalog = bundled.load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val bundledStore = InMemoryVehicleStore()
        fun session() = SessionRunner(
            transportFactory = { SimulatedCar(catalog, clock = { testScheduler.currentTime }, vin = vin) },
            loader = bundled,
            selector = ProfileSelector(bundled, bundledStore) { 2026 },
            logDir = dir,
            config = SessionConfig(appVersion = "test", source = "bluetooth", profileChoice = ProfileChoice.Auto),
            monotonicMs = { testScheduler.currentTime },
            epochMs = { 1_763_000_000_000 + testScheduler.currentTime },
        )

        val first = session()
        val job1 = async { first.run() }
        advanceTimeBy(30_000)
        assertEquals("toyota-corolla-hybrid-e210", first.state.value.profile!!.id)
        assertEquals(2019, first.state.value.modelYear, "the profile's first year until the owner says")
        assertTrue(first.state.value.modelYearAssumed)
        job1.cancel()
        job1.join()

        ProfileSelector(bundled, bundledStore) { 2026 }.setModelYear(vin, 2022)
        val second = session()
        val job2 = async { second.run() }
        advanceTimeBy(30_000)
        assertEquals(2022, second.state.value.modelYear)
        assertFalse(second.state.value.modelYearAssumed)
        job2.cancel()
        job2.join()
    }

    @Test
    fun replayCreatesNoSessionFileAndForgetsNothing() = runTest {
        val recorder = runner({ simulatedCar() })
        val job1 = async { recorder.run() }
        advanceTimeBy(20_000)
        job1.cancel()
        job1.join()
        val recorded = recorder.state.value.logFile!!
        val storedBefore = store.get(testVin)

        val replay = SessionRunner(
            transportFactory = { ReplayCar.fromLog(recorded) },
            loader = loader,
            selector = selector,
            logDir = dir,
            config = SessionConfig(
                appVersion = "test", source = "replay",
                profileChoice = ProfileChoice.Manual("generic", 2020), // would be remembered for the VIN if allowed
                forceProbe = true, saveLog = false, rememberCar = false,
            ),
            monotonicMs = { testScheduler.currentTime },
            epochMs = { 1_763_000_000_000 + testScheduler.currentTime },
        )
        val job2 = async { replay.run() }
        advanceTimeBy(20_000)
        assertEquals(SessionPhase.POLLING, replay.state.value.phase)
        assertTrue(replay.state.value.live.isNotEmpty(), "values still shown")
        job2.cancel()
        job2.join()

        assertNull(replay.state.value.logFile)
        assertEquals(listOf(recorded.name), dir.listFiles()!!.map { it.name }, "only the original session file")
        assertEquals(storedBefore, store.get(testVin), "profile choice and probe for the car unchanged")
    }

    @Test
    fun stopIsNotLoggedAsADisconnect() = runTest {
        // Both first car logs ended with a fake "disconnected": Stop closed the socket under a blocking read.
        val runner = runner({ SocketLike(simulatedCar()) })
        val job = async { runner.run() }
        advanceTimeBy(30_000)
        job.cancel()
        job.join()

        val state = runner.state.value
        assertEquals(0, state.reconnects)
        assertEquals("stopped", state.endReason)
        val log = SessionLogReader.readAll(state.logFile!!)
        assertTrue(log.none { it is LogEntry.Event && it.name == "disconnected" }, "${log.filterIsInstance<LogEntry.Event>()}")
        assertEquals("0", assertIs<LogEntry.End>(log.last()).summary["reconnects"].toString())
    }

    @Test
    fun realDropoutOnASocketStillReconnects() = runTest {
        val car = simulatedCar()
        var connections = 0
        val runner = runner({
            connections++
            if (connections == 1) SocketLike(DropsAfter(car, writes = 200)) else SocketLike(car)
        })
        val job = async { runner.run() }
        advanceTimeBy(60_000)
        assertEquals(1, runner.state.value.reconnects)
        assertEquals(SessionPhase.POLLING, runner.state.value.phase)
        job.cancel()
        job.join()
    }

    /**
     * Behaves like a Bluetooth socket: its blocking read cannot be cancelled,
     * so cancelling it surfaces as "socket closed" instead.
     */
    private class SocketLike(private val inner: ObdTransport) : ObdTransport by inner {
        override fun incoming(): kotlinx.coroutines.flow.Flow<ByteArray> = kotlinx.coroutines.flow.flow {
            try {
                inner.incoming().collect { emit(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw IOException("bt socket closed, read return: -1")
            }
        }
    }

    /** Records every line written to the adapter. */
    private class Recording(private val inner: ObdTransport) : ObdTransport by inner {
        val writes = mutableListOf<String>()
        override suspend fun write(bytes: ByteArray) {
            writes += String(bytes, Charsets.US_ASCII).trim()
            inner.write(bytes)
        }
    }

    /** Wraps a transport and fails like a lost Bluetooth link after [writes] writes. */
    private class DropsAfter(private val inner: ObdTransport, private val writes: Int) : ObdTransport by inner {
        private var count = 0
        override suspend fun write(bytes: ByteArray) {
            if (++count > writes) throw IOException("socket closed")
            inner.write(bytes)
        }
    }

    @Suppress("unused")
    private val keepImport = RawSample.Status.OK
}
