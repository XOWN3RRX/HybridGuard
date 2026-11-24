package com.hybridguard.obd

/**
 * One request and the adapter's reply, exactly as received.
 *
 * [response] is always the raw text from the adapter (hex frames or a text
 * error such as `NO DATA`), never a decoded value, so old logs stay
 * re-decodable when formulas are corrected.
 *
 * @param tMs milliseconds since session start.
 * @param header transmit header the request was sent with.
 * @param request request payload as hex, e.g. `"221F9A"`.
 */
data class RawSample(
    val tMs: Long,
    val header: Int,
    val request: String,
    val response: String,
    val status: Status,
) {
    enum class Status {
        /** Positive reply (service + 0x40). */
        OK,

        /** Negative reply `7F <svc> <code>`. */
        NEGATIVE,

        /** Adapter answered `NO DATA`. */
        NO_DATA,

        /** Any other adapter text error (`CAN ERROR`, `BUFFER FULL`, `?`, ...) or an unexpected reply. */
        ADAPTER_ERROR,

        /** CAN frames arrived but ISO-TP reassembly failed (missing / out-of-order frames). */
        MALFORMED,

        /** No prompt `>` received in time. */
        TIMEOUT,
    }
}
