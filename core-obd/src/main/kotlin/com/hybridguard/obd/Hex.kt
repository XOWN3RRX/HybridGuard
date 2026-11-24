package com.hybridguard.obd

/** `"221F9A"` -> bytes; whitespace ignored. Null if not an even-length hex string. */
internal fun String.hexToBytesOrNull(): ByteArray? {
    val s = filterNot { it.isWhitespace() }
    if (s.isEmpty() || s.length % 2 != 0) return null
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(s[2 * i], 16)
        val lo = Character.digit(s[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

internal fun Byte.u(): Int = toInt() and 0xFF
