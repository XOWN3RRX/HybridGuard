package com.hybridguard.obd

import java.io.ByteArrayOutputStream

/** One complete (reassembled) diagnostic message from one ECU. [payload] starts with the response service byte, e.g. `62 1F 9A ...`. */
class EcuMessage(val canId: Int, payload: ByteArray) {
    private val bytes = payload.copyOf()
    val payload: ByteArray get() = bytes.copyOf()
    val payloadHex: String get() = bytes.toHex()

    override fun equals(other: Any?): Boolean =
        other is EcuMessage && canId == other.canId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * canId + bytes.contentHashCode()
    override fun toString(): String = "EcuMessage(%03X: %s)".format(canId, payloadHex)
}

/**
 * Result of parsing one adapter reply.
 *
 * @param messages positive replies to the request (several for functional `7DF` requests).
 * @param negativeCode NRC of a `7F <svc> <code>` reply when [status] is NEGATIVE.
 * @param errorText adapter text and reassembly problems, verbatim, for the log and UI.
 */
data class ParsedResponse(
    val status: RawSample.Status,
    val messages: List<EcuMessage> = emptyList(),
    val negativeCode: Int? = null,
    val errorText: String? = null,
)

/**
 * Parses ELM327 replies in `ATH1 ATS0` mode (docs/03 "Response parsing").
 *
 * Pure function of (request hex, raw reply text), so logged samples can be
 * re-parsed at any time.
 */
object ResponseParser {
    /** Line = 3-hex-digit 11-bit CAN id followed by whole bytes. */
    private val FRAME = Regex("^[0-9A-F]{3}(?:[0-9A-F]{2})+$")

    private const val NEGATIVE_SID = 0x7F
    private const val NRC_RESPONSE_PENDING = 0x78

    fun parse(requestHex: String, raw: String): ParsedResponse {
        val requestBytes = requestHex.hexToBytesOrNull()
            ?: throw IllegalArgumentException("Not a hex OBD payload: '$requestHex'")
        val requestCompact = requestBytes.toHex()
        val service = requestBytes[0].u()

        val framesById = LinkedHashMap<Int, MutableList<ByteArray>>()
        val texts = mutableListOf<String>()
        for (line in raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            val compact = line.replace(" ", "").uppercase()
            when {
                compact == requestCompact -> Unit // echo (ATE0 not applied)
                line.uppercase().startsWith("SEARCHING") -> Unit
                FRAME.matches(compact) -> {
                    val id = compact.substring(0, 3).toInt(16)
                    framesById.getOrPut(id) { mutableListOf() } += compact.substring(3).hexToBytesOrNull()!!
                }
                else -> texts += line
            }
        }

        val messages = mutableListOf<EcuMessage>()
        val problems = mutableListOf<String>()
        for ((id, frames) in framesById) {
            reassemble(id, frames, messages)?.let { problems += it }
        }

        val echo = requestBytes.copyOfRange(1, 1 + minOf(echoLength(service), requestBytes.size - 1))
        val positives = messages.filter { it.isPositiveReplyTo(service, echo) }
        val negatives = messages.filter { m ->
            val p = m.payload
            p.size >= 3 && p[0].u() == NEGATIVE_SID && p[1].u() == service
        }
        val errorText = (texts + problems).joinToString("; ").ifEmpty { null }

        return when {
            positives.isNotEmpty() -> ParsedResponse(RawSample.Status.OK, positives, errorText = errorText)
            problems.isNotEmpty() -> ParsedResponse(RawSample.Status.MALFORMED, errorText = errorText)
            negatives.isNotEmpty() -> {
                // "Response pending" (78) is followed by the real answer; report the last final one.
                val final = negatives.lastOrNull { it.payload[2].u() != NRC_RESPONSE_PENDING } ?: negatives.last()
                ParsedResponse(RawSample.Status.NEGATIVE, negativeCode = final.payload[2].u(), errorText = errorText)
            }
            messages.isNotEmpty() -> ParsedResponse(
                RawSample.Status.ADAPTER_ERROR,
                errorText = listOfNotNull(errorText, "unexpected reply: " + messages.joinToString()).joinToString("; "),
            )
            texts.any { it.equals("NO DATA", ignoreCase = true) } -> ParsedResponse(RawSample.Status.NO_DATA, errorText = errorText)
            texts.isNotEmpty() -> ParsedResponse(RawSample.Status.ADAPTER_ERROR, errorText = errorText)
            else -> ParsedResponse(RawSample.Status.ADAPTER_ERROR, errorText = "empty reply")
        }
    }

    /** How many request bytes after the service byte the ECU echoes back. */
    private fun echoLength(service: Int): Int = if (service == 0x22) 2 else 1

    private fun EcuMessage.isPositiveReplyTo(service: Int, echo: ByteArray): Boolean {
        val p = payload
        if (p.size < 1 + echo.size || p[0].u() != service + 0x40) return false
        return echo.indices.all { p[1 + it] == echo[it] }
    }

    /** ISO-TP reassembly of all frames from one CAN id. Returns a problem description, or null. */
    private fun reassemble(id: Int, frames: List<ByteArray>, out: MutableList<EcuMessage>): String? {
        val idHex = "%03X".format(id)
        var buffer: ByteArrayOutputStream? = null
        var expectedLength = 0
        var nextSeq = 0

        for (f in frames) {
            val pci = f[0].u()
            when (pci shr 4) {
                0x0 -> { // single frame
                    if (buffer != null) return "$idHex: single frame inside an unfinished multi-frame message"
                    var length = pci and 0x0F
                    var start = 1
                    if (length == 0) { // CAN FD escape: length in the next byte
                        if (f.size < 2) return "$idHex: truncated single frame"
                        length = f[1].u()
                        start = 2
                    }
                    if (f.size < start + length) return "$idHex: single frame shorter than its length $length"
                    out += EcuMessage(id, f.copyOfRange(start, start + length))
                }
                0x1 -> { // first frame
                    if (buffer != null) return "$idHex: first frame before the previous message completed"
                    if (f.size < 2) return "$idHex: truncated first frame"
                    expectedLength = ((pci and 0x0F) shl 8) or f[1].u()
                    buffer = ByteArrayOutputStream().apply { write(f, 2, f.size - 2) }
                    nextSeq = 1
                }
                0x2 -> { // consecutive frame
                    val b = buffer ?: return "$idHex: consecutive frame without a first frame"
                    val seq = pci and 0x0F
                    if (seq != nextSeq) return "$idHex: consecutive frame out of order (expected %X, got %X)".format(nextSeq, seq)
                    b.write(f, 1, f.size - 1)
                    nextSeq = (nextSeq + 1) and 0x0F
                }
                0x3 -> continue // flow control: sent by the adapter, ignore
                else -> return "$idHex: unknown ISO-TP frame type %02X".format(pci)
            }
            val b = buffer
            if (b != null && b.size() >= expectedLength) {
                out += EcuMessage(id, b.toByteArray().copyOf(expectedLength))
                buffer = null
            }
        }
        return buffer?.let { "$idHex: multi-frame message incomplete (${it.size()} of $expectedLength bytes)" }
    }
}
