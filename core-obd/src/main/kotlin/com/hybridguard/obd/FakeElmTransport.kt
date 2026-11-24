package com.hybridguard.obd

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * An in-process ELM327 for running without a car (simulated and replay
 * sources, tests). It answers the `AT`/`ST` setup commands itself, tracks the
 * transmit header, and asks [respond] for every OBD request.
 *
 * Replies use the `ATH1 ATS0` format, one CAN frame per line.
 *
 * @param responseDelayMs simulated round-trip time per request, so request
 *   rates look like a real adapter's.
 */
abstract class FakeElmTransport(
    private val responseDelayMs: Long = 0,
    private val adapterName: String = "ELM327 v1.5 (simulated)",
) : ObdTransport {

    private var out = Channel<ByteArray>(Channel.UNLIMITED)

    /** What `ATRV` answers: the car's 12 V system (about 14 V while READY, 12.5 V parked). */
    @Volatile
    var batteryVolts: Double = 14.1
    private var header: Int = 0x7DF

    /**
     * Reply text for an OBD request (lines separated by '\n'), or null for no
     * reply at all, which the driver sees as a timeout.
     */
    protected abstract fun respond(header: Int, payloadHex: String): String?

    override suspend fun open() {
        out = Channel(Channel.UNLIMITED)
        header = 0x7DF
    }

    override suspend fun write(bytes: ByteArray) {
        val line = String(bytes, Charsets.US_ASCII).trim().uppercase()
        val reply = when {
            line == "ATZ" -> "ELM327 v1.5"
            line == "ATI" -> adapterName
            line == "ATRV" -> "%.1fV".format(java.util.Locale.US, batteryVolts)
            line.startsWith("ATSH") -> {
                header = line.removePrefix("ATSH").toIntOrNull(16) ?: return send("?")
                "OK"
            }
            line.startsWith("AT") -> "OK"
            line.startsWith("ST") -> "?"
            !ReadOnlyPolicy.isAllowed(line.take(2).toIntOrNull(16) ?: -1) -> "?"
            else -> {
                if (responseDelayMs > 0) delay(responseDelayMs)
                // An odd length means a trailing ELM response count ("010D1"): not part of the request.
                val payload = if (line.length % 2 == 1) line.dropLast(1) else line
                respond(header, payload) ?: return
            }
        }
        send(reply)
    }

    private suspend fun send(reply: String) {
        out.send((reply.replace("\n", "\r") + "\r\r>").toByteArray(Charsets.US_ASCII))
    }

    override fun incoming(): Flow<ByteArray> = out.receiveAsFlow()

    override suspend fun close() {
        out.close()
    }

    companion object {
        /**
         * Formats a diagnostic reply [payload] (e.g. `62 1F 9A ...`) from CAN id
         * [canId] as ISO-TP frames: a single frame up to 7 bytes, else a first
         * frame plus consecutive frames, each padded to 8 bytes.
         */
        fun frames(canId: Int, payload: ByteArray): String {
            val id = "%03X".format(canId)
            fun hex(bytes: List<Int>) = bytes.joinToString("") { "%02X".format(it) }
            val p = payload.map { it.toInt() and 0xFF }
            if (p.size <= 7) return id + hex(listOf(p.size) + p + List(7 - p.size) { 0xAA })

            val lines = mutableListOf(id + hex(listOf(0x10 or (p.size shr 8), p.size and 0xFF) + p.take(6)))
            var offset = 6
            var seq = 1
            while (offset < p.size) {
                val chunk = p.subList(offset, minOf(offset + 7, p.size))
                lines += id + hex(listOf(0x20 or seq) + chunk + List(7 - chunk.size) { 0xAA })
                offset += 7
                seq = (seq + 1) and 0x0F
            }
            return lines.joinToString("\n")
        }
    }
}
