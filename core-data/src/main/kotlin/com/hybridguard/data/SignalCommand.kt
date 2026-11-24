package com.hybridguard.data

import com.hybridguard.obd.ObdRequest
import com.hybridguard.obd.ReadOnlyPolicy

/** One signal of a command. [id] is an opaque string: never compare it to a literal in Kotlin. */
data class Signal(
    val id: String,
    val name: String,
    val format: SignalFormat,
    val description: String? = null,
    val path: String? = null,
    val hidden: Boolean = false,
    val suggestedMetric: String? = null,
)

/**
 * Model-year filter of an OBDb command. Same rules as the OBDb reference,
 * including: `from > to` wraps around, and an unknown model year never
 * matches a filtered command.
 */
data class CommandFilter(val from: Int? = null, val to: Int? = null, val years: Set<Int>? = null) {
    fun matches(modelYear: Int?): Boolean {
        if (modelYear == null) return false
        if (from != null && to != null) {
            if (from < to) {
                if (modelYear in from..to) return true
            } else if (modelYear >= from || modelYear <= to) {
                return true
            }
        } else if (to != null) {
            if (modelYear <= to) return true
        } else if (from != null) {
            if (modelYear >= from) return true
        }
        return years != null && modelYear in years
    }
}

/** One OBDb command: a request to one ECU and the signals in its reply. */
data class SignalCommand(
    val header: Int,
    val receiveAddress: Int?,
    val service: Int,
    /** Identifier after the service byte as hex: 2 digits, or 4 for service 22. */
    val parameterHex: String,
    val periodMs: Long,
    val signals: List<Signal>,
    /** `fcm1`: the reply is multi-frame and needs custom flow control. */
    val forceFlowControl: Boolean = false,
    /** `dbg`: marked as debug / not yet verified upstream. */
    val debug: Boolean = false,
    val filter: CommandFilter? = null,
    val extendedAddress: Int? = null,
    val testerAddress: Int? = null,
) {
    val payloadHex: String get() = "%02X".format(service) + parameterHex

    /** `"7D2:221F9A"`: matches a [com.hybridguard.obd.RawSample]'s header and request. */
    val key: String get() = commandKey(header, payloadHex)

    /** Number of identifier bytes the ECU echoes before the data. */
    val echoLength: Int get() = parameterHex.length / 2

    fun appliesTo(modelYear: Int?): Boolean = filter == null || filter.matches(modelYear)

    /** Why the current adapter code cannot poll this command, or null if it can. */
    val unsupportedReason: String?
        get() = when {
            !ReadOnlyPolicy.isAllowed(service) -> "service %02X is not on the read-only allow-list".format(service)
            extendedAddress != null -> "extended addressing (eax) is not supported yet"
            testerAddress != null -> "tester address (tst) is not supported yet"
            receiveAddress != null && receiveAddress != header + 8 ->
                "reply address %03X is not header + 8".format(receiveAddress)
            else -> null
        }

    fun toRequest(): ObdRequest? =
        if (unsupportedReason != null) null else ObdRequest.fromHex(header, payloadHex, periodMs, forceFlowControl)
}

fun commandKey(header: Int, payloadHex: String): String = "%03X:%s".format(header, payloadHex.uppercase())
