package com.hybridguard.data

import com.hybridguard.obd.AdapterInfo
import com.hybridguard.obd.InitStep
import com.hybridguard.obd.RawSample
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.Writer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Session log: JSON Lines, one file per session, gzipped on close (docs/05 task 5).
 *
 * ```
 * {"meta": {...}}                                          line 1: app, adapter, init replies
 * {"t":123,"h":"7D2","q":"221F9A","r":"7DA...","s":"ok"}   one per request, from the first one
 * {"t":300,"vehicle":{"vin":...,"profile":{...},...}}      once the car has answered
 * {"t":456,"event":"marker","text":"now in R"}             markers, reconnects, ...
 * {"t":789,"gps":{"kmh":48.2,"acc":4.0}}                   phone GPS, 1 Hz
 * {"t":900,"probe":{"7D2:221F9A":{"s":"ok"},...}}          capability probe result
 * {"end": {...}}                                           last line: duration, request rate
 * ```
 *
 * The log starts as soon as the adapter is initialised, so a car that never
 * answers still leaves a log showing what it replied. `t` is milliseconds
 * since session start. `r` is always the adapter's raw reply text, never a
 * decoded value, so old logs stay re-decodable.
 *
 * Format 1 (first simulated sessions) had the vehicle fields inside `meta`;
 * the reader still accepts it.
 */
object LogFormat {
    const val VERSION = 2
    const val OPEN_SUFFIX = ".jsonl"
    const val CLOSED_SUFFIX = ".jsonl.gz"

    fun statusName(status: RawSample.Status): String = status.name.lowercase()
    fun parseStatus(name: String): RawSample.Status = RawSample.Status.valueOf(name.uppercase())
}

/** Written as line 1, right after the adapter is initialised. */
data class SessionMeta(
    val appVersion: String,
    /** "bluetooth", "simulated" or "replay". */
    val source: String,
    val startEpochMs: Long,
    val adapter: AdapterInfo?,
    val initSteps: List<InitStep>,
    val deviceName: String? = null,
    val formatVersion: Int = LogFormat.VERSION,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("format", formatVersion)
        put("app", appVersion)
        put("source", source)
        put("startEpochMs", startEpochMs)
        deviceName?.let { put("device", it) }
        adapter?.let { a ->
            put("adapter", buildJsonObject {
                a.elm?.let { put("elm", it) }
                a.stn?.let { put("stn", it) }
                a.stnDevice?.let { put("stnDevice", it) }
                a.voltage?.let { put("voltage", it) }
            })
        }
        put("init", buildJsonArray {
            for (s in initSteps) add(buildJsonObject {
                put("c", s.command)
                put("r", s.reply)
                if (s.timedOut) put("timeout", true)
            })
        })
    }

    companion object {
        fun fromJson(o: JsonObject): SessionMeta {
            val adapter = o["adapter"] as? JsonObject
            return SessionMeta(
                appVersion = o.string("app") ?: "?",
                source = o.string("source") ?: "?",
                startEpochMs = o.primitive("startEpochMs")!!.content.toLong(),
                adapter = adapter?.let { AdapterInfo(it.string("elm"), it.string("stn"), it.string("stnDevice"), it.string("voltage")) },
                initSteps = (o["init"] as? JsonArray).orEmpty().map {
                    val s = it.jsonObject
                    InitStep(s.string("c")!!, s.string("r") ?: "", s.bool("timeout") ?: false)
                },
                deviceName = o.string("device"),
                formatVersion = o.int("format") ?: 1,
            )
        }
    }
}

/** What the car turned out to be, written once it has answered. */
data class VehicleInfo(
    val vin: String?,
    val profileId: String,
    val profileVersion: Int,
    val modelYear: Int?,
    val selectionReason: String,
    /** Service 01 PIDs from the supported-PID bitmaps, null if the car did not answer them. */
    val supportedPids: Set<Int>?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        vin?.let { put("vin", it) }
        put("profile", buildJsonObject {
            put("id", profileId)
            put("version", profileVersion)
            put("selection", selectionReason)
        })
        modelYear?.let { put("modelYear", it) }
        supportedPids?.let { pids -> put("supportedPids", buildJsonArray { pids.sorted().forEach { add(JsonPrimitive("%02X".format(it))) } }) }
    }

    companion object {
        fun fromJson(o: JsonObject): VehicleInfo {
            val profile = o["profile"]!!.jsonObject
            return VehicleInfo(
                vin = o.string("vin"),
                profileId = profile.string("id")!!,
                profileVersion = profile.int("version")!!,
                modelYear = o.int("modelYear"),
                selectionReason = profile.string("selection") ?: "?",
                supportedPids = (o["supportedPids"] as? JsonArray)?.map { it.jsonPrimitive.content.toInt(16) }?.toSet(),
            )
        }
    }
}

data class SessionSummary(
    val durationMs: Long,
    val samples: Int,
    val okSamples: Int,
    val reconnects: Int,
    val endReason: String,
) {
    val requestsPerSecond: Double get() = if (durationMs > 0) samples * 1000.0 / durationMs else 0.0
    val okPerSecond: Double get() = if (durationMs > 0) okSamples * 1000.0 / durationMs else 0.0

    fun toJson(): JsonObject = buildJsonObject {
        put("durationMs", durationMs)
        put("samples", samples)
        put("okSamples", okSamples)
        put("requestsPerSecond", Math.round(requestsPerSecond * 100) / 100.0)
        put("okPerSecond", Math.round(okPerSecond * 100) / 100.0)
        put("reconnects", reconnects)
        put("reason", endReason)
    }
}

/** Writes one session. Every line is flushed, so a crash loses at most the line being written. */
class SessionLogWriter(private val file: File, meta: SessionMeta) : AutoCloseable {
    private val out: Writer = file.bufferedWriter()
    private var closed = false

    init {
        require(file.name.endsWith(LogFormat.OPEN_SUFFIX)) { "log file must end with ${LogFormat.OPEN_SUFFIX}" }
        line(buildJsonObject { put("meta", meta.toJson()) })
    }

    @Synchronized
    fun sample(s: RawSample) = line(buildJsonObject {
        put("t", s.tMs)
        put("h", "%03X".format(s.header))
        put("q", s.request)
        put("r", s.response)
        put("s", LogFormat.statusName(s.status))
    })

    @Synchronized
    fun vehicle(tMs: Long, info: VehicleInfo) = line(buildJsonObject {
        put("t", tMs)
        put("vehicle", info.toJson())
    })

    @Synchronized
    fun event(tMs: Long, name: String, text: String? = null) = line(buildJsonObject {
        put("t", tMs)
        put("event", name)
        text?.let { put("text", it) }
    })

    @Synchronized
    fun gps(tMs: Long, speedKmh: Double?, accuracyM: Double?) = line(buildJsonObject {
        put("t", tMs)
        put("gps", buildJsonObject {
            speedKmh?.let { put("kmh", Math.round(it * 10) / 10.0) }
            accuracyM?.let { put("acc", Math.round(it * 10) / 10.0) }
        })
    })

    @Synchronized
    fun probe(tMs: Long, report: CapabilityReport) = line(buildJsonObject {
        put("t", tMs)
        put("probe", buildJsonObject {
            for ((key, r) in report.results) put(key, buildJsonObject {
                put("s", LogFormat.statusName(r.status))
                r.negativeCode?.let { put("nrc", "%02X".format(it)) }
                r.frames?.let { put("f", it) }
            })
        })
    })

    /** Writes the end line, gzips the file and returns the `.jsonl.gz` file. */
    @Synchronized
    fun close(summary: SessionSummary): File {
        if (!closed) line(buildJsonObject { put("end", summary.toJson()) })
        close()
        return LogFiles.compress(file)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        out.close()
    }

    private fun line(o: JsonObject) {
        check(!closed) { "log already closed" }
        out.write(o.toString())
        out.write("\n")
        out.flush()
    }
}

sealed interface LogEntry {
    data class Meta(val meta: SessionMeta) : LogEntry
    data class Vehicle(val tMs: Long, val info: VehicleInfo) : LogEntry
    data class Sample(val sample: RawSample) : LogEntry
    data class Event(val tMs: Long, val name: String, val text: String?) : LogEntry
    data class Gps(val tMs: Long, val speedKmh: Double?, val accuracyM: Double?) : LogEntry
    data class Probe(val tMs: Long, val results: JsonObject) : LogEntry
    data class End(val summary: JsonObject) : LogEntry
}

object SessionLogReader {

    fun read(file: File, onEntry: (LogEntry) -> Unit) {
        open(file).use { reader ->
            // Parse each line once the next one has been seen: only the very last line
            // may be cut short (crash, killed app) and is then skipped.
            var pending: IndexedValue<String>? = null
            for (line in reader.lineSequence().withIndex()) {
                if (line.value.isBlank()) continue
                pending?.let { parseLine(it, file.name).forEach(onEntry) }
                pending = line
            }
            pending?.let { last ->
                val entries = try {
                    parseLine(last, file.name)
                } catch (e: ProfileFormatException) {
                    emptyList()
                }
                entries.forEach(onEntry)
            }
        }
    }

    private fun parseLine(line: IndexedValue<String>, name: String): List<LogEntry> {
        val o = try {
            Json.parseToJsonElement(line.value).jsonObject
        } catch (e: Exception) {
            throw ProfileFormatException("$name line ${line.index + 1}: not JSON", e)
        }
        if ("meta" in o) {
            val meta = o["meta"]!!.jsonObject
            val entry = LogEntry.Meta(SessionMeta.fromJson(meta))
            // Format 1: the vehicle fields were part of the meta line.
            return if ("profile" in meta) listOf(entry, LogEntry.Vehicle(0, VehicleInfo.fromJson(meta))) else listOf(entry)
        }
        return listOf(parse(o, name, line.index + 1))
    }

    fun readAll(file: File): List<LogEntry> = buildList { read(file) { add(it) } }

    private fun parse(o: JsonObject, name: String, lineNo: Int): LogEntry = when {
        "vehicle" in o -> LogEntry.Vehicle(o.primitive("t")!!.content.toLong(), VehicleInfo.fromJson(o["vehicle"]!!.jsonObject))
        "q" in o -> LogEntry.Sample(
            RawSample(
                tMs = o.primitive("t")!!.content.toLong(),
                header = o.string("h")!!.toInt(16),
                request = o.string("q")!!,
                response = o.string("r") ?: "",
                status = LogFormat.parseStatus(o.string("s")!!),
            ),
        )
        "event" in o -> LogEntry.Event(o.primitive("t")!!.content.toLong(), o.string("event")!!, o.string("text"))
        "gps" in o -> o["gps"]!!.jsonObject.let { LogEntry.Gps(o.primitive("t")!!.content.toLong(), it.number("kmh"), it.number("acc")) }
        "probe" in o -> LogEntry.Probe(o.primitive("t")!!.content.toLong(), o["probe"]!!.jsonObject)
        "end" in o -> LogEntry.End(o["end"]!!.jsonObject)
        else -> throw ProfileFormatException("$name line $lineNo: unknown entry ${o.keys}")
    }

    private fun open(file: File): BufferedReader =
        if (file.name.endsWith(".gz")) GZIPInputStream(file.inputStream()).bufferedReader() else file.bufferedReader()
}

object LogFiles {
    /** Gzips [file] to `<name>.gz` and deletes the original. */
    fun compress(file: File): File {
        val target = File(file.parentFile, file.name + ".gz")
        file.inputStream().use { input -> GZIPOutputStream(target.outputStream()).use { input.copyTo(it) } }
        file.delete()
        return target
    }

    /** Compresses logs left open by a crash or a killed app. Returns the recovered files. */
    fun recoverOpenLogs(dir: File, except: File? = null): List<File> =
        dir.listFiles { f -> f.name.endsWith(LogFormat.OPEN_SUFFIX) && f != except }.orEmpty().map(::compress)
}
