package com.hybridguard.data

import com.hybridguard.obd.RawSample
import com.hybridguard.obd.ResponseParser
import java.io.File

/**
 * Offline analysis of discovery logs (docs/02 section D):
 *
 *     ./gradlew :core-data:run --args="discovery session.jsonl.gz [more.jsonl.gz ...]"
 *
 * 1. Lists every identifier that answered and is not in the profile catalog.
 * 2. Splits the log at the owner's markers ("P", "R", "N", "D", "B", ...)
 *    and lists the reply bytes that hold one value within each marker
 *    segment and change between segments: the candidates for gear, EV mode
 *    and the other missing values. A candidate is a lead, not a confirmed
 *    signal: check it in another session before adding it to a profile.
 */
object DiscoveryAnalysis {

    data class Segment(val label: String, val fromMs: Long, val toMs: Long)

    /** One reply byte and its values per marker segment. */
    data class ByteCandidate(
        val key: String,
        val byteIndex: Int,
        /** Segment label -> values seen (in order of first appearance), for segments with samples. */
        val values: List<Pair<String, List<Int>>>,
    ) {
        /** Segments where the byte held exactly one value. */
        val steadySegments: Int get() = values.count { it.second.size == 1 }
        val distinctSteadyValues: Int get() = values.filter { it.second.size == 1 }.map { it.second[0] }.distinct().size

        fun describe(): String = "%s byte %d: %s".format(
            key.replace(':', ' '),
            byteIndex,
            values.joinToString("  ") { (label, v) -> "[$label] " + v.take(4).joinToString("/") { "%02X".format(it) } + if (v.size > 4) "/..." else "" },
        )
    }

    /** Marker segments: from each marker to the next one; samples before the first marker form "(start)". */
    fun segments(markers: List<Pair<Long, String>>, endMs: Long): List<Segment> {
        val sorted = markers.sortedBy { it.first }
        val out = mutableListOf<Segment>()
        val firstAt = sorted.firstOrNull()?.first ?: endMs + 1
        out += Segment("(start)", Long.MIN_VALUE, firstAt)
        sorted.forEachIndexed { i, (t, text) ->
            out += Segment(text, t, sorted.getOrNull(i + 1)?.first ?: Long.MAX_VALUE)
        }
        return out
    }

    /** Data bytes of a positive reply (after the echoed service and identifier), or null. */
    fun dataBytes(sample: RawSample): IntArray? {
        if (sample.status != RawSample.Status.OK) return null
        val message = ResponseParser.parse(sample.request, sample.response).messages.firstOrNull() ?: return null
        val payload = message.payload
        val echo = sample.request.length / 2
        if (payload.size <= echo) return null
        return IntArray(payload.size - echo) { payload[echo + it].toInt() and 0xFF }
    }

    /**
     * Bytes that are steady within at least two segments and take different
     * steady values there, best first (most segments told apart).
     *
     * @param keys only these requests (e.g. not the catalog ones); all when null.
     */
    fun candidates(samples: List<RawSample>, markers: List<Pair<Long, String>>, keys: Set<String>? = null): List<ByteCandidate> {
        if (markers.isEmpty()) return emptyList()
        val segs = segments(markers, samples.maxOfOrNull { it.tMs } ?: 0)
        val byKey = samples.filter { keys == null || commandKey(it.header, it.request) in keys }
            .groupBy { commandKey(it.header, it.request) }
        val out = mutableListOf<ByteCandidate>()
        for ((key, list) in byKey) {
            val decoded = list.mapNotNull { s -> dataBytes(s)?.let { s.tMs to it } }
            if (decoded.size < 2) continue
            val width = decoded.maxOf { it.second.size }
            for (i in 0 until width) {
                val perSegment = segs.mapNotNull { seg ->
                    val values = decoded.filter { (t, d) -> t >= seg.fromMs && t < seg.toMs && i < d.size }.map { it.second[i] }
                    if (values.isEmpty()) null else seg.label to values.distinct()
                }
                val candidate = ByteCandidate(key, i, perSegment)
                if (candidate.steadySegments >= 2 && candidate.distinctSteadyValues >= 2) out += candidate
            }
        }
        return out.sortedWith(
            compareByDescending<ByteCandidate> { it.distinctSteadyValues }
                .thenByDescending { it.steadySegments }
                .thenBy { it.key }
                .thenBy { it.byteIndex },
        )
    }

    fun report(files: List<File>, loader: ProfileLoader): String = buildString {
        val samples = mutableListOf<RawSample>()
        val markers = mutableListOf<Pair<Long, String>>()
        var catalog: VehicleCatalog? = null
        var offset = 0L
        for (file in files) {
            var last = 0L
            SessionLogReader.read(file) { e ->
                when (e) {
                    // Several logs are laid end to end so their marker segments do not overlap.
                    is LogEntry.Sample -> e.sample.let { s -> samples += s.copy(tMs = s.tMs + offset); last = maxOf(last, s.tMs) }
                    is LogEntry.Event -> if (e.name == "marker") markers += (e.tMs + offset) to (e.text ?: "marker")
                    is LogEntry.Vehicle -> if (catalog == null) catalog = loader.load(e.info.profileId).forModelYear(e.info.modelYear)
                    else -> Unit
                }
            }
            offset += last + 1
        }
        fun inCatalog(s: RawSample) = catalog?.commandFor(s.header, s.request) != null

        val answered = samples.filter { it.status == RawSample.Status.OK && !inCatalog(it) }
            .groupBy { commandKey(it.header, it.request) }
        appendLine("Identifiers outside the catalog that answered: ${answered.size}")
        answered.entries.groupBy { it.key.substringBefore(':') }.toSortedMap().forEach { (ecu, entries) ->
            appendLine("  ECU $ecu: ${entries.size}")
            for ((key, list) in entries.sortedBy { it.key }) {
                val data = dataBytes(list.first())?.joinToString(" ") { "%02X".format(it) } ?: ""
                appendLine("    ${key.substringAfter(':')}  ${list.size}x  len ${dataBytes(list.first())?.size ?: 0}  $data")
            }
        }

        val refused = samples.filter { it.status == RawSample.Status.NEGATIVE }.mapNotNull { s ->
            val code = ResponseParser.parse(s.request, s.response).negativeCode ?: return@mapNotNull null
            if (code in DiscoveryProgress.NOT_FOUND_CODES || code == DiscoveryProgress.NRC_SERVICE_NOT_SUPPORTED) null
            else commandKey(s.header, s.request) to code
        }.distinct()
        if (refused.isNotEmpty()) {
            appendLine()
            appendLine("Refused (may need another state): ${refused.size}")
            refused.forEach { (key, code) -> appendLine("  ${key.replace(':', ' ')}  NRC %02X".format(code)) }
        }

        appendLine()
        if (markers.isEmpty()) {
            appendLine("No markers in the log: run watch mode and tap the marker buttons to find gear and mode bytes.")
            return@buildString
        }
        appendLine("Markers: " + markers.joinToString(", ") { "%.1fs %s".format(it.first / 1000.0, it.second) })
        // Catalog requests too: a known reply can carry an undocumented byte (gear, mode flags).
        val readKeys = samples.filter { it.status == RawSample.Status.OK && it.request.take(2) in setOf("21", "22") }
            .map { commandKey(it.header, it.request) }.toSet()
        val found = candidates(samples, markers, readKeys)
        appendLine("Bytes that change with the markers (steady within a segment): ${found.size}")
        found.take(MAX_CANDIDATES).forEach { appendLine("  " + it.describe()) }
        if (found.size > MAX_CANDIDATES) appendLine("  ... ${found.size - MAX_CANDIDATES} more")
    }

    private const val MAX_CANDIDATES = 60
}
