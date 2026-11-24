package com.hybridguard.obd

/**
 * One read request to one ECU.
 *
 * A request with a service outside [ReadOnlyPolicy] cannot even be constructed.
 *
 * @param header 11-bit CAN transmit header, e.g. `0x7D2`, or `0x7DF` for functional (broadcast) requests.
 * @param service OBD service, e.g. `0x22`.
 * @param pid PID / DID bytes after the service byte, e.g. `[0x1F, 0x9A]`.
 * @param periodMs how often the scheduler should repeat it.
 * @param multiFrame whether the reply is expected to be ISO-TP multi-frame.
 * @param receiveHeader only listen to replies from this CAN id. Default: header + 8 for
 *   physical requests, every ECU for functional ones.
 * @param expectedResponses ELM327 response count (1..15) appended to the request, so the
 *   adapter returns as soon as that many replies arrived instead of waiting for its timeout
 *   (docs/03). Only safe when the reply is known to be that many single frames.
 */
class ObdRequest(
    val header: Int,
    val service: Int,
    pid: ByteArray,
    val periodMs: Long,
    val multiFrame: Boolean,
    val receiveHeader: Int? = null,
    val expectedResponses: Int? = null,
) {
    private val pidBytes: ByteArray = pid.copyOf()

    val pid: ByteArray get() = pidBytes.copyOf()

    init {
        require(header in 0..0x7FF) { "Header must be an 11-bit CAN id, was 0x%X".format(header) }
        require(receiveHeader == null || receiveHeader in 0..0x7FF) { "Receive header must be an 11-bit CAN id" }
        require(periodMs > 0) { "periodMs must be positive, was $periodMs" }
        require(expectedResponses == null || expectedResponses in 1..15) { "expectedResponses must be 1..15" }
        ReadOnlyPolicy.requireAllowed(service)
    }

    /** Payload as logged and parsed, e.g. `"221F9A"`. */
    val payloadHex: String
        get() = buildString {
            append("%02X".format(service))
            pidBytes.forEach { append("%02X".format(it.toInt() and 0xFF)) }
        }

    /** What is written to the adapter: the payload plus the optional response count, e.g. `"010D1"`. */
    val wirePayload: String get() = payloadHex + (expectedResponses?.let { "%X".format(it) } ?: "")

    val headerHex: String get() = "%03X".format(header)

    /** Copy with another period (same request otherwise). */
    fun withPeriod(periodMs: Long): ObdRequest =
        ObdRequest(header, service, pidBytes, periodMs, multiFrame, receiveHeader, expectedResponses)

    override fun equals(other: Any?): Boolean =
        other is ObdRequest &&
            header == other.header &&
            service == other.service &&
            pidBytes.contentEquals(other.pidBytes) &&
            periodMs == other.periodMs &&
            multiFrame == other.multiFrame &&
            receiveHeader == other.receiveHeader &&
            expectedResponses == other.expectedResponses

    override fun hashCode(): Int {
        var result = header
        result = 31 * result + service
        result = 31 * result + pidBytes.contentHashCode()
        result = 31 * result + periodMs.hashCode()
        result = 31 * result + multiFrame.hashCode()
        result = 31 * result + (receiveHeader ?: -1)
        result = 31 * result + (expectedResponses ?: -1)
        return result
    }

    override fun toString(): String =
        "ObdRequest($headerHex:$wirePayload" +
            (receiveHeader?.let { " -> %03X".format(it) } ?: "") +
            ", every ${periodMs}ms${if (multiFrame) ", multi-frame" else ""})"

    companion object {
        /** Functional (broadcast) header for service `01` / `09` requests. */
        const val FUNCTIONAL_HEADER: Int = 0x7DF

        /** Builds a request from its hex payload, e.g. `fromHex(0x7D2, "221F9A", 1000)`. */
        fun fromHex(
            header: Int,
            payloadHex: String,
            periodMs: Long,
            multiFrame: Boolean = false,
            receiveHeader: Int? = null,
            expectedResponses: Int? = null,
        ): ObdRequest {
            val bytes = payloadHex.hexToBytesOrNull()
                ?: throw IllegalArgumentException("Not a hex OBD payload: '$payloadHex'")
            return ObdRequest(header, bytes[0].u(), bytes.copyOfRange(1, bytes.size), periodMs, multiFrame, receiveHeader, expectedResponses)
        }
    }
}
