package com.hybridguard.data

import com.hybridguard.obd.AdapterInfo
import com.hybridguard.obd.InitStep
import com.hybridguard.obd.RawSample
import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionLogTest {

    private val dir: File = Files.createTempDirectory("hybridguard-log").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val meta = SessionMeta(
        appVersion = "0.1.0",
        source = "simulated",
        startEpochMs = 1_763_000_000_000,
        adapter = AdapterInfo("ELM327 v2.3", "STN2255 v5.10.3", "Test STN adapter", "12.6V"),
        initSteps = listOf(InitStep("ATZ", "ELM327 v2.3", false), InitStep("ATE0", "OK", false)),
    )

    private val vehicle = VehicleInfo(
        vin = "TST1ABCDEK1234567",
        profileId = "test-ev",
        profileVersion = 3,
        modelYear = 2019,
        selectionReason = "VIN_MATCH",
        supportedPids = setOf(0x0C, 0x0D),
    )

    @Test
    fun writeCloseAndReadBack() {
        val writer = SessionLogWriter(File(dir, "s.jsonl"), meta)
        writer.sample(RawSample(10, 0x7E0, "0902", "NO DATA", RawSample.Status.NO_DATA))
        writer.vehicle(50, vehicle)
        val multiLine = RawSample(100, 0x7D2, "221F9A", "7DA1009621F9A000034\n7DA2100FF38AAAAAAAA", RawSample.Status.OK)
        writer.sample(multiLine)
        writer.sample(RawSample(150, 0x7D2, "221F5B", "NO DATA", RawSample.Status.NO_DATA))
        writer.event(200, "marker", "now in R")
        writer.gps(210, 48.24, 4.0)
        writer.probe(300, CapabilityReport("test-ev", 3, 0, mapOf("7E4:220102" to CapabilityResult(RawSample.Status.NEGATIVE, 0x31))))
        val closed = writer.close(SessionSummary(durationMs = 10_000, samples = 120, okSamples = 100, reconnects = 1, endReason = "stopped"))

        assertEquals("s.jsonl.gz", closed.name)
        assertFalse(File(dir, "s.jsonl").exists())

        val entries = SessionLogReader.readAll(closed)
        assertEquals(meta, assertIs<LogEntry.Meta>(entries[0]).meta)
        assertEquals(RawSample.Status.NO_DATA, assertIs<LogEntry.Sample>(entries[1]).sample.status, "samples before the car answered are kept")
        assertEquals(LogEntry.Vehicle(50, vehicle), entries[2])
        assertEquals(multiLine, assertIs<LogEntry.Sample>(entries[3]).sample, "raw reply kept exactly, including line breaks")
        assertEquals(RawSample.Status.NO_DATA, assertIs<LogEntry.Sample>(entries[4]).sample.status)
        assertEquals(LogEntry.Event(200, "marker", "now in R"), entries[5])
        assertEquals(LogEntry.Gps(210, 48.2, 4.0), entries[6])
        assertIs<LogEntry.Probe>(entries[7])
        val end = assertIs<LogEntry.End>(entries[8]).summary
        assertEquals("12.0", end["requestsPerSecond"].toString())
        assertEquals("\"stopped\"", end["reason"].toString())
    }

    @Test
    fun sampleLineMatchesTheDocumentedFormat() {
        val file = File(dir, "f.jsonl")
        SessionLogWriter(file, meta).use { it.sample(RawSample(123456, 0x7D2, "221F9A", "7DA07621F9A", RawSample.Status.OK)) }
        assertEquals("""{"t":123456,"h":"7D2","q":"221F9A","r":"7DA07621F9A","s":"ok"}""", file.readLines()[1])
    }

    @Test
    fun formatOneLogsWithTheVehicleInsideMetaStillRead() {
        val file = File(dir, "v1.jsonl")
        file.writeText(
            """{"meta":{"format":1,"app":"0.1.0","source":"simulated","startEpochMs":1794573650210,"init":[{"c":"ATZ","r":"ELM327 v1.5"}],""" +
                """"vin":"HDSMLTDCARK000001","profile":{"id":"test-ev","version":1,"selection":"MANUAL"},"modelYear":2019,"supportedPids":["0C","0D"]}}""" + "\n" +
                """{"t":19,"h":"7E0","q":"010D","r":"7E803410D2A","s":"ok"}""" + "\n",
        )
        val entries = SessionLogReader.readAll(file)
        assertEquals(1, assertIs<LogEntry.Meta>(entries[0]).meta.formatVersion)
        val v = assertIs<LogEntry.Vehicle>(entries[1]).info
        assertEquals("HDSMLTDCARK000001", v.vin)
        assertEquals("test-ev", v.profileId)
        assertEquals(setOf(0x0C, 0x0D), v.supportedPids)
        assertIs<LogEntry.Sample>(entries[2])
    }

    @Test
    fun crashedLogIsRecoveredAndATruncatedLastLineIsSkipped() {
        val file = File(dir, "crashed.jsonl")
        SessionLogWriter(file, meta).use { it.sample(RawSample(1, 0x7E0, "010D", "7E803410D2A", RawSample.Status.OK)) }
        file.appendText("""{"t":2,"h":"7E0","q":"010C","r":"7E8""") // killed mid-line

        val recovered = LogFiles.recoverOpenLogs(dir)
        assertEquals(listOf("crashed.jsonl.gz"), recovered.map { it.name })
        val entries = SessionLogReader.readAll(recovered.single())
        assertEquals(2, entries.size)
    }

    @Suppress("unused")
    private val keepImport: AdapterInfo? = null

    @Test
    fun decodeToolWritesCsv() {
        val writer = SessionLogWriter(File(dir, "d.jsonl"), meta)
        writer.vehicle(0, vehicle)
        writer.sample(RawSample(10, 0x7E0, "010D", "7E8 03 41 0D 2A", RawSample.Status.OK))
        writer.sample(RawSample(20, 0x7E4, "220101", "7EC 07 62 01 01 07 E0 FF 83", RawSample.Status.OK))
        writer.sample(RawSample(30, 0x7E4, "220102", "7EC 03 7F 22 31", RawSample.Status.NEGATIVE))
        writer.sample(RawSample(40, 0x7D2, "22ABCD", "NO DATA", RawSample.Status.NO_DATA))
        val file = writer.close(SessionSummary(40, 4, 2, 0, "stopped"))

        val csv = StringWriter()
        val stats = DecodeTool.decodeToCsv(file, csv, ProfileLoader(FakeProfiles.source()))
        val lines = csv.toString().trim().lines()

        assertEquals(DecodeTool.HEADER, lines[0])
        assertTrue(lines.contains("10,7E0,010D,ok,VSS,Speed,42.0,kilometersPerHour,7E8 03 41 0D 2A"), lines.joinToString("\n"))
        assertTrue(lines.contains("20,7E4,220101,ok,TEST_HV_A,Pack current,12.5,amps,7EC 07 62 01 01 07 E0 FF 83"))
        assertTrue(lines.contains("30,7E4,220102,negative,,,,,7EC 03 7F 22 31"))
        assertTrue(lines.contains("40,7D2,22ABCD,no_data,,,,,NO DATA"))
        assertEquals(DecodeTool.Stats(samples = 4, rows = 5, unknownRequests = 1), stats)
    }
}
