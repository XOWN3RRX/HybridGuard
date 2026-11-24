package com.hybridguard.data

import com.hybridguard.obd.ObdRequest
import com.hybridguard.obd.RawSample
import com.hybridguard.obd.ResponseParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/*
 * Discovery scan (docs/05 Phase 1 task 7, docs/02 section D).
 *
 * Which ECUs and identifier ranges to sweep is vehicle data: a profile's
 * `discovery.json`. Only services 21 and 22 (read data by identifier) can be
 * scanned; anything else in the file is a load error, on top of the
 * read-only allow-list every request passes anyway.
 */

/** One identifier range on one ECU, e.g. `7D2` service `22` `1000..10FF`. */
data class DiscoveryRange(
    val header: Int,
    val ecuName: String,
    val service: Int,
    val from: Int,
    val to: Int,
) {
    /** Identifier bytes after the service byte: 1 for service 21, 2 for 22. */
    val idBytes: Int get() = if (service == 0x22) 2 else 1
    val size: Int get() = to - from + 1

    fun payloadAt(offset: Int): String = "%02X".format(service) + "%0${idBytes * 2}X".format(from + offset)

    /** e.g. `7D2 hybrid 22 1000-10FF`. */
    val label: String get() = "%03X %s %02X %s-%s".format(header, ecuName, service, idHex(from), idHex(to))

    private fun idHex(id: Int) = "%0${idBytes * 2}X".format(id)
}

/**
 * The ordered list of requests a scan sends: ECU by ECU, range by range.
 * Indexed, so progress is a single number and a scan can resume.
 *
 * @param fingerprint changes whenever the plan does; saved progress for
 *   another fingerprint is discarded.
 */
class DiscoveryPlan(val ranges: List<DiscoveryRange>) {
    private val starts: IntArray = IntArray(ranges.size).also { s ->
        var acc = 0
        ranges.forEachIndexed { i, r -> s[i] = acc; acc += r.size }
    }

    val size: Int = ranges.sumOf { it.size }

    val fingerprint: String = sha256(ranges.joinToString(";") { "%03X:%02X:%X-%X".format(it.header, it.service, it.from, it.to) }).take(16)

    fun rangeIndexAt(index: Int): Int {
        require(index in 0 until size) { "index $index out of 0..${size - 1}" }
        var lo = 0
        var hi = ranges.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= index) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun rangeAt(index: Int): DiscoveryRange = ranges[rangeIndexAt(index)]

    /** First index of the range after the one holding [index]. */
    fun endOfRange(index: Int): Int = rangeIndexAt(index).let { starts[it] + ranges[it].size }

    fun payloadAt(index: Int): String {
        val i = rangeIndexAt(index)
        return ranges[i].payloadAt(index - starts[i])
    }

    fun requestAt(index: Int): ObdRequest = ObdRequest.fromHex(rangeAt(index).header, payloadAt(index), periodMs = 1_000, multiFrame = true)

    companion object {
        val SCANNABLE_SERVICES = setOf(0x21, 0x22)

        /**
         * Parses `discovery.json`:
         * ```
         * { "ranges": [ {"service": "21", "from": "00", "to": "FF"}, {"service": "22", "from": "1000", "to": "10FF"} ],
         *   "ecus":   [ {"hdr": "7D2", "name": "hybrid"}, {"hdr": "747", "name": "battery", "ranges": [...]} ] }
         * ```
         * An ECU's own `ranges` replace the shared ones.
         */
        fun parse(text: String, source: String): DiscoveryPlan {
            val root = SignalSetParser.parseJson(text, source)
            val shared = (root["ranges"] as? JsonArray)?.let { parseRanges(it, source) }.orEmpty()
            val ecus = root["ecus"] as? JsonArray ?: throw ProfileFormatException("$source: missing \"ecus\"")
            val ranges = ecus.flatMap { e ->
                val o = e as? JsonObject ?: throw ProfileFormatException("$source: ecus entries must be objects")
                val hdrText = o.string("hdr") ?: throw ProfileFormatException("$source: ecu without \"hdr\"")
                val header = hdrText.toIntOrNull(16)?.takeIf { hdrText.length == 3 && it in 0..0x7FF }
                    ?: throw ProfileFormatException("$source: bad header \"$hdrText\"")
                if (header == ObdRequest.FUNCTIONAL_HEADER) throw ProfileFormatException("$source: the broadcast address 7DF cannot be scanned")
                val name = o.string("name") ?: hdrText
                val own = (o["ranges"] as? JsonArray)?.let { parseRanges(it, source) }
                (own ?: shared).map { (service, from, to) -> DiscoveryRange(header, name, service, from, to) }
            }
            if (ranges.isEmpty()) throw ProfileFormatException("$source: no ranges to scan")
            return DiscoveryPlan(ranges)
        }

        private fun parseRanges(array: JsonArray, source: String): List<Triple<Int, Int, Int>> = array.map { e ->
            val o = e.jsonObject
            val serviceText = o.string("service") ?: throw ProfileFormatException("$source: range without \"service\"")
            val service = serviceText.toIntOrNull(16) ?: throw ProfileFormatException("$source: bad service \"$serviceText\"")
            if (service !in SCANNABLE_SERVICES) {
                throw ProfileFormatException("$source: service $serviceText cannot be scanned (only 21 and 22)")
            }
            val digits = if (service == 0x22) 4 else 2
            fun id(key: String): Int {
                val text = o.string(key) ?: throw ProfileFormatException("$source: range without \"$key\"")
                if (text.length != digits) throw ProfileFormatException("$source: \"$key\" must have $digits hex digits for service $serviceText")
                return text.toIntOrNull(16) ?: throw ProfileFormatException("$source: bad id \"$text\"")
            }
            val from = id("from")
            val to = id("to")
            if (from > to) throw ProfileFormatException("$source: range $serviceText ${o.string("from")}-${o.string("to")} is reversed")
            Triple(service, from, to)
        }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** A reply worth keeping from the scan: positive, or negative with a code that says the identifier exists. */
data class DiscoveryHit(
    val header: Int,
    val request: String,
    val status: RawSample.Status,
    val negativeCode: Int? = null,
    /** Raw adapter reply, as in the log. */
    val response: String,
) {
    val key: String get() = commandKey(header, request)

    /** Data bytes after the echoed service and identifier, as hex; null unless positive. */
    val dataHex: String?
        get() {
            if (status != RawSample.Status.OK) return null
            val message = ResponseParser.parse(request, response).messages.firstOrNull() ?: return null
            val echo = request.length / 2 // service byte + identifier bytes
            return message.payloadHex.drop(echo * 2)
        }
}

/**
 * How far a scan got on one car, and what it found. Immutable: each reply
 * makes a new one. Saved per car so a scan resumes in a later session.
 */
data class DiscoveryProgress(
    val planFingerprint: String,
    val nextIndex: Int = 0,
    /** Positive replies (and malformed ones, which still prove something answered). */
    val answered: List<DiscoveryHit> = emptyList(),
    /** Negative replies other than "does not exist": the identifier may exist but needs another state. */
    val refused: List<DiscoveryHit> = emptyList(),
    val notFound: Int = 0,
    val silent: Int = 0,
    /** ECUs that never replied to their first [SILENT_LIMIT] requests. */
    val skippedHeaders: Set<Int> = emptySet(),
    /** `"7C0:21"`: services an ECU said it does not support (NRC 11). */
    val skippedServices: Set<String> = emptySet(),
    val repliesByHeader: Map<Int, Int> = emptyMap(),
    val silentByHeader: Map<Int, Int> = emptyMap(),
) {
    private fun skipped(range: DiscoveryRange) =
        range.header in skippedHeaders || serviceKey(range.header, range.service) in skippedServices

    /** Next index to send at or after [nextIndex], jumping over skipped ECUs and services; `plan.size` when done. */
    fun nextToSend(plan: DiscoveryPlan): Int {
        var i = nextIndex
        while (i < plan.size && skipped(plan.rangeAt(i))) i = plan.endOfRange(i)
        return i
    }

    fun finished(plan: DiscoveryPlan): Boolean = nextToSend(plan) >= plan.size

    /** Requests still to send, not counting skipped ECUs and services. */
    fun remaining(plan: DiscoveryPlan): Int {
        var n = 0
        var i = nextToSend(plan)
        while (i < plan.size) {
            val end = plan.endOfRange(i)
            if (!skipped(plan.rangeAt(i))) n += end - i
            i = end
        }
        return n
    }

    /** Human descriptions of what was skipped and why. */
    fun skippedDescriptions(plan: DiscoveryPlan): List<String> {
        val names = plan.ranges.associate { it.header to it.ecuName }
        return skippedHeaders.sorted().map { "%03X %s: no reply to the first %d requests".format(it, names[it] ?: "", SILENT_LIMIT) } +
            skippedServices.sorted().map { key ->
                val (h, s) = key.split(':')
                "$h ${names[h.toInt(16)] ?: ""}: service $s not supported (NRC 11)"
            }
    }

    /**
     * Records the reply to request [index] and returns the new progress, plus
     * a note for the log when an ECU or service is skipped from now on.
     */
    fun record(plan: DiscoveryPlan, index: Int, sample: RawSample): Pair<DiscoveryProgress, String?> {
        val range = plan.rangeAt(index)
        val parsed = if (sample.status == RawSample.Status.TIMEOUT) null else ResponseParser.parse(sample.request, sample.response)
        val status = parsed?.status ?: RawSample.Status.TIMEOUT
        val hit = DiscoveryHit(sample.header, sample.request, status, parsed?.negativeCode, sample.response)
        val h = range.header
        var next = copy(nextIndex = maxOf(nextIndex, index + 1))
        var note: String? = null
        when (status) {
            RawSample.Status.OK, RawSample.Status.MALFORMED ->
                next = next.copy(answered = answered + hit, repliesByHeader = repliesByHeader.inc(h))
            RawSample.Status.NEGATIVE -> {
                next = next.copy(repliesByHeader = repliesByHeader.inc(h))
                val code = parsed?.negativeCode
                when (code) {
                    NRC_SERVICE_NOT_SUPPORTED -> {
                        next = next.copy(skippedServices = skippedServices + serviceKey(h, range.service))
                        note = "%03X: service %02X not supported (NRC 11), rest of it skipped".format(h, range.service)
                    }
                    in NOT_FOUND_CODES -> next = next.copy(notFound = notFound + 1)
                    else -> next = next.copy(refused = refused + hit)
                }
            }
            else -> {
                val silentNow = silentByHeader.getOrDefault(h, 0) + 1
                next = next.copy(silent = silent + 1, silentByHeader = silentByHeader + (h to silentNow))
                if (repliesByHeader.getOrDefault(h, 0) == 0 && silentNow >= SILENT_LIMIT && h !in skippedHeaders) {
                    next = next.copy(skippedHeaders = skippedHeaders + h)
                    note = "%03X: no reply to %d requests, ECU skipped".format(h, silentNow)
                }
            }
        }
        return next to note
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("plan", planFingerprint)
        put("next", nextIndex)
        put("answered", hitsJson(answered))
        put("refused", hitsJson(refused))
        put("notFound", notFound)
        put("silent", silent)
        put("skippedHeaders", buildJsonArray { skippedHeaders.sorted().forEach { add(JsonPrimitive("%03X".format(it))) } })
        put("skippedServices", buildJsonArray { skippedServices.sorted().forEach { add(JsonPrimitive(it)) } })
        put("replies", countsJson(repliesByHeader))
        put("silentBy", countsJson(silentByHeader))
    }

    companion object {
        /** Requests without any reply after which an ECU that never answered is skipped. */
        const val SILENT_LIMIT = 16

        const val NRC_SERVICE_NOT_SUPPORTED = 0x11

        /** 12 sub-function not supported, 31 request out of range: the identifier does not exist. */
        val NOT_FOUND_CODES = setOf(0x12, 0x31)

        fun serviceKey(header: Int, service: Int) = "%03X:%02X".format(header, service)

        private fun Map<Int, Int>.inc(key: Int) = this + (key to getOrDefault(key, 0) + 1)

        private fun hitsJson(hits: List<DiscoveryHit>) = buildJsonArray {
            for (hit in hits) add(buildJsonObject {
                put("h", "%03X".format(hit.header))
                put("q", hit.request)
                put("s", LogFormat.statusName(hit.status))
                hit.negativeCode?.let { put("nrc", it) }
                put("r", hit.response)
            })
        }

        private fun countsJson(m: Map<Int, Int>) = buildJsonObject { m.toSortedMap().forEach { (h, n) -> put("%03X".format(h), n) } }

        fun fromJson(o: JsonObject): DiscoveryProgress {
            fun hits(key: String) = (o[key] as? JsonArray).orEmpty().map {
                val h = it.jsonObject
                DiscoveryHit(h.string("h")!!.toInt(16), h.string("q")!!, LogFormat.parseStatus(h.string("s")!!), h.int("nrc"), h.string("r") ?: "")
            }
            fun counts(key: String) = (o[key] as? JsonObject).orEmpty().map { (h, n) -> h.toInt(16) to n.jsonPrimitive.int }.toMap()
            return DiscoveryProgress(
                planFingerprint = o.string("plan")!!,
                nextIndex = o.int("next") ?: 0,
                answered = hits("answered"),
                refused = hits("refused"),
                notFound = o.int("notFound") ?: 0,
                silent = o.int("silent") ?: 0,
                skippedHeaders = (o["skippedHeaders"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content.toInt(16) }.toSet(),
                skippedServices = (o["skippedServices"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet(),
                repliesByHeader = counts("replies"),
                silentByHeader = counts("silentBy"),
            )
        }
    }
}

/** Saved scan progress, per car and profile. */
interface DiscoveryStore {
    fun get(vin: String, profileId: String): DiscoveryProgress?
    fun put(vin: String, profileId: String, progress: DiscoveryProgress)
    fun clear(vin: String, profileId: String)
}

class InMemoryDiscoveryStore : DiscoveryStore {
    private val map = mutableMapOf<String, DiscoveryProgress>()
    override fun get(vin: String, profileId: String) = map["$vin/$profileId"]
    override fun put(vin: String, profileId: String, progress: DiscoveryProgress) {
        map["$vin/$profileId"] = progress
    }
    override fun clear(vin: String, profileId: String) {
        map.remove("$vin/$profileId")
    }
}

/** One JSON file per car and profile in [dir] (app-private storage on the phone). */
class FileDiscoveryStore(private val dir: File) : DiscoveryStore {
    override fun get(vin: String, profileId: String): DiscoveryProgress? {
        val file = fileFor(vin, profileId)
        if (!file.isFile) return null
        return runCatching { DiscoveryProgress.fromJson(Json.parseToJsonElement(file.readText()).jsonObject) }.getOrNull()
    }

    override fun put(vin: String, profileId: String, progress: DiscoveryProgress) {
        dir.mkdirs()
        val file = fileFor(vin, profileId)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(progress.toJson().toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not write $file" }
        }
    }

    override fun clear(vin: String, profileId: String) {
        fileFor(vin, profileId).delete()
    }

    private fun fileFor(vin: String, profileId: String): File {
        require(Vin.isValid(vin)) { "not a valid VIN" }
        require(profileId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "bad profile id" }
        return File(dir, "$vin-$profileId.json")
    }
}

enum class DiscoveryMode {
    IDLE,

    /** Sweeping the plan (parked only). */
    SCAN,

    /** Polling every identifier the scan found, while the owner drops markers. */
    WATCH,
}

/** One ECU of the scan plan and how many of its identifiers answered. */
data class DiscoveryEcu(val header: Int, val name: String, val answered: Int)

/** What the Discover screen shows. */
data class DiscoveryState(
    /** Null when discovery can be used in this session, else why not. */
    val unavailableReason: String? = null,
    val mode: DiscoveryMode = DiscoveryMode.IDLE,
    val message: String? = null,
    /** Requests sent or skipped so far, of [total]. */
    val done: Int = 0,
    val total: Int = 0,
    val currentRange: String? = null,
    val finished: Boolean = false,
    val answered: List<DiscoveryHit> = emptyList(),
    val refused: List<DiscoveryHit> = emptyList(),
    val notFound: Int = 0,
    val silent: Int = 0,
    val skipped: List<String> = emptyList(),
    val watchCount: Int = 0,
    val speedKmh: Double? = null,
    /** ECUs in plan order (most promising first). */
    val ecus: List<DiscoveryEcu> = emptyList(),
    /** ECUs being watched (or last watched). */
    val watchHeaders: Set<Int> = emptySet(),
    /** Last marker tapped while watching, and how many watched identifiers were read since. */
    val watchMarker: String? = null,
    val watchReadSinceMarker: Int = 0,
) {
    /** Every watched identifier has been read at least once since the last marker. */
    val roundComplete: Boolean get() = watchMarker != null && watchCount > 0 && watchReadSinceMarker >= watchCount

    val available: Boolean get() = unavailableReason == null
}
