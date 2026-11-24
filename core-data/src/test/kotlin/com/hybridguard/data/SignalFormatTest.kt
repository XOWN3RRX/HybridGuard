package com.hybridguard.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SignalFormatTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun scaling8(nullMin: Double? = null, nullMax: Double? = null, min: Double = 0.0, add: Double = 0.0) =
        Scaling(bitLength = 8, max = 255.0, unit = "scalar", min = min, add = add, nullMin = nullMin, nullMax = nullMax)

    private fun SignalValue.number() = assertIs<SignalValue.Number>(this).value

    // Cases ported from OBDb/.schemas python/test_null_bounds.py
    @Test
    fun nullBounds() {
        assertEquals(SignalValue.NoValue, scaling8(nullMax = 255.0).decode(bytes(0xFF)))
        assertEquals(SignalValue.NoValue, scaling8(nullMax = 250.0).decode(bytes(0xFB)))
        assertEquals(SignalValue.NoValue, scaling8(nullMin = 0.0).decode(bytes(0x00)))
        assertEquals(SignalValue.NoValue, scaling8(min = -40.0, add = -40.0, nullMin = -40.0).decode(bytes(0x00)))
        assertEquals(254.0, scaling8(nullMax = 255.0).decode(bytes(0xFE)).number())
        assertEquals(1.0, scaling8(nullMin = 0.0).decode(bytes(0x01)).number())
        assertEquals(255.0, scaling8().decode(bytes(0xFF)).number())
    }

    @Test
    fun bitIndexIsMsbFirst() {
        val data = bytes(0b1010_0000, 0x12, 0x34)
        assertEquals(1.0, Scaling(bitOffset = 0, bitLength = 1, max = 1.0, unit = "offon").decode(data).number())
        assertEquals(0.0, Scaling(bitOffset = 1, bitLength = 1, max = 1.0, unit = "offon").decode(data).number())
        assertEquals(0b101.toDouble(), Scaling(bitOffset = 0, bitLength = 3, max = 7.0, unit = "scalar").decode(data).number())
        assertEquals(0x1234.toDouble(), Scaling(bitOffset = 8, bitLength = 16, max = 65535.0, unit = "scalar").decode(data).number())
        // Across a byte boundary: bits 4..11 = 0000_0001
        assertEquals(1.0, Scaling(bitOffset = 4, bitLength = 8, max = 255.0, unit = "scalar").decode(data).number())
    }

    @Test
    fun scaleAndOffset() {
        // docs/02: engine rpm, len 16, /4
        val rpm = Scaling(bitLength = 16, max = 16383.75, div = 4.0, unit = "rpm")
        assertEquals(1726.0, rpm.decode(bytes(0x1A, 0xF8)).number())
        // docs/02: coolant, len 8, -40
        val coolant = Scaling(bitLength = 8, min = -40.0, max = 215.0, add = -40.0, unit = "celsius")
        assertEquals(50.0, coolant.decode(bytes(90)).number())
    }

    @Test
    fun signedTwosComplement() {
        val current = Scaling(bitLength = 16, min = -3276.8, max = 3276.7, div = 10.0, signed = true, unit = "amps")
        assertEquals(-20.0, current.decode(bytes(0xFF, 0x38)).number(), 1e-9)
        assertEquals(20.0, current.decode(bytes(0x00, 0xC8)).number(), 1e-9)
    }

    @Test
    fun valueIsClampedToMinMaxLikeTheReference() {
        val s = Scaling(bitLength = 8, max = 100.0, mul = 100.0, div = 255.0, unit = "percent")
        assertEquals(100.0, s.decode(bytes(0xFF)).number(), 1e-9)
        // Signed value without "min": the reference clamps it to 0.
        val noMin = Scaling(bitLength = 8, max = 127.0, signed = true, unit = "scalar")
        assertEquals(0.0, noMin.decode(bytes(0xFF)).number())
    }

    @Test
    fun littleEndianBytes() {
        val s = Scaling(bitLength = 16, max = 65535.0, bytesLsb = true, unit = "scalar")
        assertEquals(0x3412.toDouble(), s.decode(bytes(0x12, 0x34)).number())
    }

    @Test
    fun shortReplyIsMissingNotZero() {
        val s = Scaling(bitOffset = 16, bitLength = 16, max = 65535.0, unit = "scalar")
        assertIs<SignalValue.Missing>(s.decode(bytes(0x00, 0x01, 0x02)))
    }

    @Test
    fun enumeration() {
        val e = Enumeration(bitLength = 8, map = mapOf(1L to EnumEntry("FL", "Front left"), 2L to EnumEntry("FR", "Front right")))
        assertEquals(SignalValue.Enumerated("FR", "Front right"), e.decode(bytes(2)))
        assertEquals(SignalValue.NoValue, e.decode(bytes(9)))
    }
}
