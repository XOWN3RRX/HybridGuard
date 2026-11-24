package com.hybridguard.data

/** Result of decoding one signal from one reply. */
sealed interface SignalValue {
    data class Number(val value: Double, val unit: String) : SignalValue

    data class Enumerated(val value: String, val description: String) : SignalValue

    /** Decoded correctly but means "no value": on a null bound, or an enum code with no mapping. */
    data object NoValue : SignalValue

    /** The reply was too short to contain this signal. */
    data class Missing(val reason: String) : SignalValue
}

/**
 * How a signal sits in the reply data and turns into a value: the OBDb
 * `fmt` object. Semantics follow the OBDb reference decoder
 * (OBDb/.schemas `python/can/signals.py`), see docs/02 "Decode rule".
 *
 * `data` is the reply payload **after** the echoed service and identifier.
 */
sealed interface SignalFormat {
    val bitOffset: Int
    val bitLength: Int
    fun decode(data: ByteArray): SignalValue
}

data class Scaling(
    override val bitOffset: Int = 0,
    override val bitLength: Int,
    val max: Double,
    val unit: String,
    val min: Double = 0.0,
    val mul: Double = 1.0,
    val div: Double = 1.0,
    val add: Double = 0.0,
    val signed: Boolean = false,
    /** `blsb`: the bytes holding the value are little-endian. */
    val bytesLsb: Boolean = false,
    val nullMin: Double? = null,
    val nullMax: Double? = null,
) : SignalFormat {

    init {
        require(bitLength in 1..63) { "len must be 1..63, was $bitLength" }
        require(bitOffset >= 0) { "bix must be >= 0, was $bitOffset" }
        require(div != 0.0) { "div must not be 0" }
    }

    override fun decode(data: ByteArray): SignalValue {
        var raw = extractBits(data, bitOffset, bitLength, bytesLsb)
            ?: return SignalValue.Missing("need ${bitOffset + bitLength} bits, reply has ${data.size * 8}")
        if (signed && (raw and (1L shl (bitLength - 1))) != 0L) raw -= 1L shl bitLength

        var value = raw * mul / div + add
        if (max > min) value = value.coerceIn(min, max)

        if (nullMin != null && value <= nullMin + EPSILON) return SignalValue.NoValue
        if (nullMax != null && value >= nullMax - EPSILON) return SignalValue.NoValue
        return SignalValue.Number(value, unit)
    }

    private companion object {
        val EPSILON = Math.ulp(1.0) // Python's sys.float_info.epsilon
    }
}

data class EnumEntry(val value: String, val description: String)

data class Enumeration(
    override val bitOffset: Int = 0,
    override val bitLength: Int,
    val map: Map<Long, EnumEntry>,
) : SignalFormat {

    init {
        require(bitLength in 1..63) { "len must be 1..63, was $bitLength" }
    }

    override fun decode(data: ByteArray): SignalValue {
        val raw = extractBits(data, bitOffset, bitLength, bytesLsb = false)
            ?: return SignalValue.Missing("need ${bitOffset + bitLength} bits, reply has ${data.size * 8}")
        val entry = map[raw] ?: return SignalValue.NoValue
        return SignalValue.Enumerated(entry.value, entry.description)
    }
}

/** Big-endian bit extraction, bit 0 = MSB of the first byte. Null if [data] is too short. */
internal fun extractBits(data: ByteArray, bitOffset: Int, bitLength: Int, bytesLsb: Boolean): Long? {
    val end = bitOffset + bitLength
    if (end > data.size * 8) return null

    val bytes = if (bytesLsb && bitLength > 8) {
        val startByte = bitOffset / 8
        val endByte = minOf(startByte + (bitLength + 7) / 8, data.size)
        data.copyOf().also { it.reverse(startByte, endByte) }
    } else {
        data
    }

    var result = 0L
    for (i in bitOffset until end) {
        val byte = bytes[i / 8].toInt() and 0xFF
        if ((byte and (1 shl (7 - i % 8))) != 0) result = result or (1L shl (end - i - 1))
    }
    return result
}
