package com.hybridguard.data

import com.hybridguard.obd.RawSample
import com.hybridguard.obd.ResponseParser

/** Every signal of one reply, decoded. [values] is empty unless [status] is OK. */
data class DecodedSample(
    val tMs: Long,
    val commandKey: String,
    val status: RawSample.Status,
    val values: Map<String, SignalValue>,
    val negativeCode: Int? = null,
    val errorText: String? = null,
)

/**
 * Turns [RawSample]s into signal values using a [VehicleCatalog].
 *
 * Works only from the sample's raw text (re-parsed every time), so a log
 * recorded today decodes with tomorrow's corrected formulas.
 */
class Decoder(private val catalog: VehicleCatalog) {

    /** Null when the sample's request is not in the catalog (e.g. a discovery-scan request). */
    fun decode(sample: RawSample): DecodedSample? {
        val command = catalog.commandFor(sample.header, sample.request) ?: return null
        return decode(sample, command)
    }

    fun decode(sample: RawSample, command: SignalCommand): DecodedSample {
        if (sample.status == RawSample.Status.TIMEOUT) {
            return DecodedSample(sample.tMs, command.key, sample.status, emptyMap())
        }
        val parsed = ResponseParser.parse(sample.request, sample.response)
        if (parsed.status != RawSample.Status.OK) {
            return DecodedSample(sample.tMs, command.key, parsed.status, emptyMap(), parsed.negativeCode, parsed.errorText)
        }

        val message = parsed.messages.firstOrNull { it.canId == command.receiveAddress } ?: parsed.messages.first()
        val payload = message.payload
        val data = payload.copyOfRange(minOf(1 + command.echoLength, payload.size), payload.size)
        val values = command.signals.associate { it.id to it.format.decode(data) }
        return DecodedSample(sample.tMs, command.key, RawSample.Status.OK, values, errorText = parsed.errorText)
    }
}

/** The latest decoded value of one signal. */
data class SignalReading(val tMs: Long, val signalId: String, val value: SignalValue)

/**
 * Resolves dashboard [Role]s to signals using the profile's `derived.json`
 * preference lists: the first signal with a usable, fresh value wins.
 */
class RoleResolver(private val roles: Map<Role, List<String>>) {

    fun signalsFor(role: Role): List<String> = roles[role].orEmpty()

    /**
     * @param latest newest reading per signal id.
     * @param nowMs / [maxAgeMs] readings older than this are ignored (stale values are never shown).
     */
    fun resolve(role: Role, latest: Map<String, SignalReading>, nowMs: Long? = null, maxAgeMs: Long? = null): SignalReading? =
        signalsFor(role).firstNotNullOfOrNull { id ->
            latest[id]?.takeIf { reading ->
                val usable = reading.value is SignalValue.Number || reading.value is SignalValue.Enumerated
                val fresh = nowMs == null || maxAgeMs == null || nowMs - reading.tMs <= maxAgeMs
                usable && fresh
            }
        }
}
