package com.hybridguard.data

import com.hybridguard.obd.FakeElmTransport
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryTest {

    private val dir: File = Files.createTempDirectory("hybridguard-discovery").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    // ---- Plan ----

    private val planJson = """
        { "ranges": [ {"service": "22", "from": "0100", "to": "011F"} ],
          "ecus": [ {"hdr": "7E4", "name": "bms"},
                    {"hdr": "7E5", "name": "quiet", "ranges": [ {"service": "21", "from": "00", "to": "3F"} ]} ] }
    """

    @Test
    fun planIsOrderedByEcuThenRange() {
        val plan = DiscoveryPlan.parse(planJson, "test")
        assertEquals(32 + 64, plan.size)
        assertEquals("220100", plan.payloadAt(0))
        assertEquals("22011F", plan.payloadAt(31))
        assertEquals(0x7E4, plan.rangeAt(31).header)
        assertEquals("2100", plan.payloadAt(32))
        assertEquals(0x7E5, plan.rangeAt(32).header)
        assertEquals("213F", plan.payloadAt(95))
        assertEquals(32, plan.endOfRange(5))
        assertEquals(0x22, plan.requestAt(3).service)
        assertEquals(plan.fingerprint, DiscoveryPlan.parse(planJson, "again").fingerprint)
        assertNotEquals(plan.fingerprint, DiscoveryPlan.parse(planJson.replace("011F", "012F"), "changed").fingerprint)
    }

    @Test
    fun planOnlyScansReadServices() {
        for (service in listOf("2E", "31", "10", "27", "01")) {
            val e = assertFailsWith<ProfileFormatException> {
                DiscoveryPlan.parse("""{ "ranges": [ {"service": "$service", "from": "00", "to": "FF"} ], "ecus": [ {"hdr": "7E0"} ] }""", "bad")
            }
            assertTrue("only 21 and 22" in e.message!!, e.message)
        }
        assertFailsWith<ProfileFormatException>("broadcast") {
            DiscoveryPlan.parse("""{ "ranges": [ {"service": "22", "from": "0100", "to": "01FF"} ], "ecus": [ {"hdr": "7DF"} ] }""", "bad")
        }
        assertFailsWith<ProfileFormatException>("2 digits for service 22") {
            DiscoveryPlan.parse("""{ "ranges": [ {"service": "22", "from": "01", "to": "FF"} ], "ecus": [ {"hdr": "7E0"} ] }""", "bad")
        }
        assertFailsWith<ProfileFormatException>("reversed") {
            DiscoveryPlan.parse("""{ "ranges": [ {"service": "21", "from": "FF", "to": "00"} ], "ecus": [ {"hdr": "7E0"} ] }""", "bad")
        }
    }

    @Test
    fun bundledCorollaPlanIsReadOnlyAndStartsWithTheHybridEcu() {
        val loader = ProfileLoader()
        val plan = assertNotNull(loader.discoveryPlan("toyota-corolla-hybrid-e210"))
        assertTrue(plan.ranges.all { it.service == 0x21 || it.service == 0x22 })
        assertEquals(0x7D2, plan.ranges.first().header)
        assertEquals(2048, plan.ranges.filter { it.header == 0x7D2 }.sumOf { it.size })
        assertNull(loader.discoveryPlan("sae-j1979"), "the generic profile has no plan")
    }

    // ---- Progress ----

    private fun sample(header: Int, request: String, reply: String, status: RawSample.Status = RawSample.Status.OK) =
        RawSample(0, header, request, reply, status)

    @Test
    fun repliesAreSortedAndSilentOrUnsupportedPartsAreSkipped() {
        val plan = DiscoveryPlan.parse(planJson, "test")
        var p = DiscoveryProgress(plan.fingerprint)
        fun rec(index: Int, reply: String, status: RawSample.Status = RawSample.Status.OK): String? {
            val r = plan.requestAt(index)
            val (next, note) = p.record(plan, index, sample(r.header, r.payloadHex, reply, status))
            p = next
            return note
        }
        rec(0, "7EC0462010012AAAAAA")
        rec(1, "7EC037F2231AAAAAAAA", RawSample.Status.NEGATIVE)
        rec(2, "7EC037F2222AAAAAAAA", RawSample.Status.NEGATIVE)
        assertEquals(listOf("7E4:220100"), p.answered.map { it.key })
        assertEquals("12", p.answered.single().dataHex)
        assertEquals(1, p.notFound)
        assertEquals(listOf(0x22), p.refused.map { it.negativeCode })
        assertEquals(3, p.nextToSend(plan))

        val note = rec(3, "7EC037F2211AAAAAAAA", RawSample.Status.NEGATIVE)
        assertNotNull(note, "service 22 not supported on 7E4: skipped")
        assertEquals(32, p.nextToSend(plan), "jumps to the next ECU")

        repeat(DiscoveryProgress.SILENT_LIMIT - 1) { assertNull(rec(32 + it, "NO DATA", RawSample.Status.NO_DATA)) }
        assertNotNull(rec(32 + 15, "NO DATA", RawSample.Status.NO_DATA))
        assertTrue(p.finished(plan))
        assertEquals(0, p.remaining(plan))
        assertEquals(2, p.skippedDescriptions(plan).size)

        val restored = DiscoveryProgress.fromJson(p.toJson())
        assertEquals(p, restored)
    }

    @Test
    fun fileStoreKeepsProgressPerCarAndProfile() {
        val store = FileDiscoveryStore(dir)
        val p = DiscoveryProgress("abc", nextIndex = 7, notFound = 3)
        store.put("TST1ABCDEK1234567", "test-ev", p)
        assertEquals(p, store.get("TST1ABCDEK1234567", "test-ev"))
        assertNull(store.get("TST1ABCDEK1234567", "generic"))
        store.clear("TST1ABCDEK1234567", "test-ev")
        assertNull(store.get("TST1ABCDEK1234567", "test-ev"))
    }

    // ---- Analysis ----

    @Test
    fun bytesThatFollowTheMarkersAreFound() {
        fun s(t: Long, gear: Int, noise: Int) =
            RawSample(t, 0x7E4, "220110", FakeElmTransport.frames(0x7EC, byteArrayOf(0x62, 0x01, 0x10, gear.toByte(), noise.toByte(), 0x55)), RawSample.Status.OK)
        val samples = listOf(s(0, 1, 9), s(500, 1, 3), s(1500, 8, 7), s(1800, 8, 1), s(2500, 0x20, 4), s(2900, 0x20, 2))
        val markers = listOf(1_000L to "R", 2_000L to "D")
        val found = DiscoveryAnalysis.candidates(samples, markers)
        assertEquals(1, found.size, found.joinToString { it.describe() })
        val c = found.single()
        assertEquals(0, c.byteIndex)
        assertEquals(listOf("(start)" to listOf(1), "R" to listOf(8), "D" to listOf(0x20)), c.values)
    }

    // ---- In a session ----

    private val loader = ProfileLoader(FakeProfiles.source(mapOf("test-ev/discovery.json" to planJson)))
    private val vin = "TST1ABCDEK1234567"

    private class Knobs {
        @Volatile var speed = 0
        @Volatile var gear = 1
        val requests = java.util.concurrent.ConcurrentLinkedQueue<String>()
    }

    private fun TestScope.car(knobs: Knobs) = SimulatedCar(
        loader.load("test-ev").forModelYear(2019),
        clock = { testScheduler.currentTime },
        vin = vin,
        negativeForUnknown = true,
        extra = { header, payload ->
            knobs.requests += "%03X:%s".format(header, payload)
            when {
                payload == "010D" -> FakeElmTransport.frames(0x7E8, byteArrayOf(0x41, 0x0D, knobs.speed.toByte()))
                header == 0x7E5 -> "NO DATA"
                header == 0x7E4 && payload == "220110" ->
                    FakeElmTransport.frames(0x7EC, byteArrayOf(0x62, 0x01, 0x10, knobs.gear.toByte(), 0x00))
                else -> null
            }
        },
    )

    private fun TestScope.runner(car: () -> SimulatedCar, store: DiscoveryStore) = SessionRunner(
        transportFactory = car,
        loader = loader,
        selector = ProfileSelector(loader, InMemoryVehicleStore()) { 2026 },
        logDir = dir,
        config = SessionConfig(appVersion = "test", source = "simulated"),
        monotonicMs = { testScheduler.currentTime },
        epochMs = { 1_763_000_000_000 + testScheduler.currentTime },
        discoveryStore = store,
    )

    @Test
    fun scanRunsOnlyWhenParkedFindsHiddenIdentifiersAndWatchesThem() = runTest {
        val knobs = Knobs()
        val store = InMemoryDiscoveryStore()
        val car = car(knobs)
        val runner = runner({ car }, store)
        val job = async { runner.run() }

        advanceTimeBy(20_000)
        val ready = assertNotNull(runner.state.value.discovery)
        assertTrue(ready.available, ready.unavailableReason)
        assertEquals(96, ready.total)
        assertFalse(runner.startWatch(), "nothing found yet")

        knobs.speed = 12
        assertTrue(runner.startScan())
        advanceTimeBy(10_000)
        val paused = runner.state.value.discovery!!
        assertEquals(0, paused.done, "nothing sent while moving")
        assertTrue(paused.message!!.contains("moving"), paused.message)
        assertTrue(knobs.requests.none { it.startsWith("7E5:") || it == "7E4:220110" })

        knobs.speed = 0
        advanceTimeBy(60_000)
        val done = runner.state.value.discovery!!
        assertTrue(done.finished, "done ${done.done}/${done.total}: ${done.message}")
        assertEquals(DiscoveryMode.IDLE, done.mode)
        assertTrue("7E4:220110" in done.answered.map { it.key }, done.answered.toString())
        assertTrue("7E4:220101" in done.answered.map { it.key }, "catalog identifiers in the range answer too")
        assertEquals(DiscoveryProgress.SILENT_LIMIT, knobs.requests.count { it.startsWith("7E5:") }, "silent ECU skipped after 16")
        assertEquals(1, done.skipped.size)
        assertTrue(runner.state.value.live.keys.none { it == "7E4:22011F" }, "scan replies stay off the Live tab")
        assertEquals(done.answered, store.get(vin, "test-ev")!!.answered, "progress saved for the car")

        assertEquals(listOf(0x7E4 to done.answered.size, 0x7E5 to 0), done.ecus.map { it.header to it.answered })
        assertFalse(runner.startWatch(setOf(0x7E5)), "nothing to watch on a silent ECU")
        assertTrue(runner.startWatch(setOf(0x7E4)))
        advanceTimeBy(3_000)
        val watching = runner.state.value.discovery!!
        assertEquals(DiscoveryMode.WATCH, watching.mode)
        assertEquals(done.answered.size, watching.watchCount, "catalog identifiers on the ECU are watched too")
        assertNull(watching.watchMarker)

        knobs.requests.clear()
        runner.marker("P")
        assertFalse(runner.state.value.discovery!!.roundComplete)
        advanceTimeBy(4_000)
        assertTrue(runner.state.value.discovery!!.roundComplete, "every watched identifier read since the marker")
        assertTrue(knobs.requests.none { it.endsWith(":010C") }, "normal polling (rpm) paused while watching")
        assertTrue(knobs.requests.any { it.endsWith(":010D") }, "speed still read")
        knobs.gear = 8
        runner.marker("R")
        assertEquals(0, runner.state.value.discovery!!.watchReadSinceMarker)
        advanceTimeBy(4_000)
        knobs.gear = 0x20
        runner.marker("D")
        advanceTimeBy(4_000)
        assertTrue("7E4:220110" in runner.state.value.live.keys, "watched identifiers show on the Live tab")
        runner.stopDiscovery()
        advanceTimeBy(3_000)
        assertEquals(0, runner.state.value.discovery!!.watchCount)
        knobs.requests.clear()
        advanceTimeBy(3_000)
        assertTrue(knobs.requests.any { it.endsWith(":010C") }, "normal polling resumed")

        job.cancel()
        job.join()

        val file = runner.state.value.logFile!!
        val entries = SessionLogReader.readAll(file)
        val samples = entries.filterIsInstance<LogEntry.Sample>().map { it.sample }
        assertTrue(samples.all { it.request.take(2) in setOf("01", "09", "21", "22") }, "read-only services only")
        val markers = entries.filterIsInstance<LogEntry.Event>().filter { it.name == "marker" }.map { it.tMs to it.text!! }
        val best = DiscoveryAnalysis.candidates(samples, markers, setOf("7E4:220110")).first()
        assertEquals("7E4:220110", best.key)
        assertEquals(0, best.byteIndex)
        assertEquals(3, best.distinctSteadyValues)
        assertTrue(DiscoveryAnalysis.report(listOf(file), loader).contains("7E4 220110 byte 0"))
    }

    @Test
    fun aLaterSessionResumesWhereTheScanStopped() = runTest {
        val knobs = Knobs()
        val store = InMemoryDiscoveryStore()
        val first = runner({ car(knobs) }, store)
        val job1 = async { first.run() }
        advanceTimeBy(20_000)
        assertTrue(first.startScan())
        advanceTimeBy(1_500)
        first.stopDiscovery()
        advanceTimeBy(1_000)
        val stoppedAt = first.state.value.discovery!!.done
        assertTrue(stoppedAt in 1 until 96, "stopped part way: $stoppedAt")
        job1.cancel()
        job1.join()

        val second = runner({ car(knobs) }, store)
        val job2 = async { second.run() }
        advanceTimeBy(20_000)
        assertEquals(stoppedAt, second.state.value.discovery!!.done)
        knobs.requests.clear()
        assertTrue(second.startScan())
        advanceTimeBy(60_000)
        assertTrue(second.state.value.discovery!!.finished)
        assertTrue(knobs.requests.none { it == "7E4:220100" }, "the first requests are not sent again")

        assertTrue(second.resetScan())
        assertEquals(0, second.state.value.discovery!!.done)
        assertNull(store.get(vin, "test-ev"))
        job2.cancel()
        job2.join()
    }
}
