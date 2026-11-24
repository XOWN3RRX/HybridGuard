package com.hybridguard.data

import com.hybridguard.obd.FakeElmTransport
import com.hybridguard.obd.ObdRequest
import com.hybridguard.obd.RawSample
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * A simulated car built only from a [VehicleCatalog]: every catalog request
 * gets a positive reply long enough for all its signals, with byte values
 * drifting slowly over time. It knows nothing about any particular model,
 * so values are plausible in shape only ("simulated data" in the UI).
 *
 * Also answers the standard VIN (`09 02`) and supported-PID bitmap requests.
 */
class SimulatedCar(
    private val catalog: VehicleCatalog,
    private val clock: () -> Long,
    private val vin: String = SIMULATED_VIN,
    responseDelayMs: Long = 40,
    /** ECUs that ignore requests to their own address and only answer the broadcast address. */
    private val ignoredPhysicalHeaders: Set<Int> = emptySet(),
    /**
     * Answer service 21 / 22 requests outside the catalog the way real ECUs
     * do (NRC 12 / 31, "does not exist") instead of `NO DATA`, so a
     * simulated discovery scan runs at a realistic pace.
     */
    private val negativeForUnknown: Boolean = false,
    /** Catalog commands (by key) whose data bytes are all zero, e.g. speed for a parked car. */
    private val zeroCommandKeys: Set<String> = emptySet(),
    /** Asked first for every request: a reply text, or null to fall through (tests: hidden identifiers, controlled values). */
    private val extra: ((header: Int, payloadHex: String) -> String?)? = null,
    /**
     * A chosen value for a signal (by id) at a time, in the signal's unit,
     * or null for the drifting default. Linear signals only; used by the
     * app's simulator scenarios (normal drive, pump failure).
     */
    private val scripted: ((signalId: String, nowMs: Long) -> Double?)? = null,
    /**
     * Data bytes a command (by key) answers with when shorter than all its
     * signals need, or null for the full length: e.g. a pack with fewer
     * cells than the request's list, as real battery ECUs answer.
     */
    private val replyBytes: ((commandKey: String) -> Int?)? = null,
) : FakeElmTransport(responseDelayMs, "ELM327 v1.5 (simulated)") {

    /** When true the car stops answering (ignition off), for tests and demos. */
    @Volatile
    var silent: Boolean = false

    /** Request payloads (e.g. "220102") that get NO DATA, as from an ECU that is asleep. */
    val unanswered: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val service01Pids: Set<Int> = catalog.commands.filter { it.service == 0x01 }.map { it.parameterHex.toInt(16) }.toSet()

    override fun respond(header: Int, payloadHex: String): String? {
        if (silent || header in ignoredPhysicalHeaders || payloadHex in unanswered) return "NO DATA"
        extra?.invoke(header, payloadHex)?.let { return it }
        val replyId = if (header == ObdRequest.FUNCTIONAL_HEADER) 0x7E8 else header + 8
        val bytes = hexToBytes(payloadHex)

        if (payloadHex == "0902") {
            return frames(replyId, byteArrayOf(0x49, 0x02, 0x01) + vin.toByteArray(Charsets.US_ASCII))
        }
        if (bytes.size == 2 && bytes[0].toInt() == 0x01 && (bytes[1].toInt() and 0xFF) in SupportedPids.BITMAP_PIDS) {
            return bitmap(replyId, bytes[1].toInt() and 0xFF)
        }

        val command = catalog.commandFor(header, payloadHex)
        if (command == null) {
            val service = bytes[0].toInt() and 0xFF
            if (!negativeForUnknown || header == ObdRequest.FUNCTIONAL_HEADER || (service != 0x21 && service != 0x22)) return "NO DATA"
            return frames(replyId, byteArrayOf(0x7F, service.toByte(), if (service == 0x22) 0x31 else 0x12))
        }
        val full = command.signals.maxOfOrNull { (it.format.bitOffset + it.format.bitLength + 7) / 8 }?.coerceAtLeast(1) ?: 1
        val length = replyBytes?.invoke(command.key)?.coerceIn(1, full) ?: full
        val now = clock()
        val zero = command.key in zeroCommandKeys
        val data = ByteArray(length) { i ->
            if (zero) return@ByteArray 0
            val seed = (command.key.hashCode() * 31 + i)
            val periodMs = 6_000 + Math.floorMod(seed, 20_000)
            val phase = Math.floorMod(seed * 7, 360) * PI / 180
            (128 + 100 * sin(2 * PI * now / periodMs + phase)).toInt().toByte()
        }
        if (!zero && scripted != null) {
            for (signal in command.signals) {
                val format = signal.format as? Scaling ?: continue
                val value = scripted.invoke(signal.id, now) ?: continue
                insertBits(data, format.bitOffset, format.bitLength, encode(format, value))
            }
        }
        val payload = byteArrayOf((command.service + 0x40).toByte()) + hexToBytes(command.parameterHex) + data
        return frames(command.receiveAddress ?: replyId, payload)
    }

    private fun bitmap(replyId: Int, base: Int): String {
        val inRange = service01Pids.filter { it in base + 1..base + 0x20 }
        val hasMore = service01Pids.any { it > base + 0x20 }
        if (inRange.isEmpty() && !hasMore && base != 0) return "NO DATA"
        var bits = 0L
        for (pid in inRange) bits = bits or (1L shl (32 - (pid - base)))
        if (hasMore) bits = bits or 1L
        val b = ByteArray(4) { ((bits shr (24 - 8 * it)) and 0xFF).toByte() }
        return frames(replyId, byteArrayOf(0x41, base.toByte()) + b)
    }

    companion object {
        /** Raw bits for [value] in a linear [format] (the inverse of [Scaling.decode], clamped to the field). */
        internal fun encode(format: Scaling, value: Double): Long {
            val raw = Math.round((value - format.add) * format.div / format.mul)
            val bits = format.bitLength
            return if (format.signed) {
                raw.coerceIn(-(1L shl (bits - 1)), (1L shl (bits - 1)) - 1) and ((1L shl bits) - 1)
            } else {
                raw.coerceIn(0, (1L shl bits) - 1)
            }
        }

        /** Big-endian, bit 0 = MSB of the first byte, as [extractBits] reads it. */
        internal fun insertBits(data: ByteArray, bitOffset: Int, bitLength: Int, raw: Long) {
            for (i in 0 until bitLength) {
                val pos = bitOffset + i
                if (pos / 8 >= data.size) return
                val bit = (raw shr (bitLength - 1 - i)) and 1L
                val mask = 1 shl (7 - pos % 8)
                val byte = data[pos / 8].toInt() and 0xFF
                data[pos / 8] = (if (bit == 1L) byte or mask else byte and mask.inv()).toByte()
            }
        }

        /** A syntactically valid VIN that matches no real profile. */
        const val SIMULATED_VIN = "HDSMLTDCARK000001"
    }
}

/**
 * Plays a recorded session back: each request is answered with the raw
 * replies recorded for it, in order, looping. Timing follows the live
 * scheduler, not the recording.
 */
class ReplayCar(samples: List<RawSample>, responseDelayMs: Long = 20) :
    FakeElmTransport(responseDelayMs, "ELM327 (replay)") {

    private val byKey: Map<String, List<RawSample>> = samples.groupBy { commandKey(it.header, it.request) }
    private val positions = mutableMapOf<String, Int>()

    override fun respond(header: Int, payloadHex: String): String? {
        val key = commandKey(header, payloadHex)
        val recorded = byKey[key] ?: return "NO DATA"
        val i = positions.getOrDefault(key, 0)
        positions[key] = i + 1
        val sample = recorded[i % recorded.size]
        return if (sample.status == RawSample.Status.TIMEOUT) null else sample.response
    }

    companion object {
        fun fromLog(file: File): ReplayCar =
            ReplayCar(SessionLogReader.readAll(file).filterIsInstance<LogEntry.Sample>().map { it.sample })
    }
}

internal fun hexToBytes(hex: String): ByteArray {
    val s = hex.filterNot { it.isWhitespace() }
    require(s.length % 2 == 0) { "odd-length hex '$hex'" }
    return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
