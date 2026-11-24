package com.hybridguard.data

import com.hybridguard.obd.RawSample
import com.hybridguard.obd.ResponseParser

/**
 * SAE J1979 "supported PIDs" bitmaps (`01 00`, `01 20`, `01 40`, ...). Each
 * reply carries 32 bits: bit n (MSB first) set = PID base + n + 1 supported;
 * the last bit says whether the next bitmap exists. Standard, not car-specific.
 */
object SupportedPids {
    val BITMAP_PIDS: List<Int> = (0x00..0xE0 step 0x20).toList()

    /** Supported service 01 PIDs from the bitmap samples, or null if no bitmap was answered. */
    fun parse(samples: List<RawSample>): Set<Int>? {
        val supported = mutableSetOf<Int>()
        var any = false
        for (sample in samples) {
            val base = sample.request.takeIf { it.length == 4 && it.startsWith("01") }?.substring(2)?.toIntOrNull(16) ?: continue
            if (base !in BITMAP_PIDS) continue
            val parsed = ResponseParser.parse(sample.request, sample.response)
            for (message in parsed.messages) {
                val p = message.payload
                if (p.size < 6) continue
                any = true
                for (bit in 0 until 32) {
                    val byte = p[2 + bit / 8].toInt() and 0xFF
                    if ((byte and (0x80 ushr (bit % 8))) != 0) supported += base + bit + 1
                }
            }
        }
        return if (any) supported else null
    }

    /** Whether the bitmap at [base] says the next one (base + 0x20) exists. */
    fun nextBitmapSupported(supported: Set<Int>, base: Int): Boolean = (base + 0x20) in supported
}
