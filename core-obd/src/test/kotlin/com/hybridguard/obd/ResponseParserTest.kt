package com.hybridguard.obd

import com.hybridguard.obd.RawSample.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hand-written fixtures in ATH1 ATS0 format. Spaces are for readability; the parser ignores them. */
class ResponseParserTest {

    private fun parse(request: String, vararg lines: String) = ResponseParser.parse(request, lines.joinToString("\n"))

    @Test
    fun singleFrame() {
        val r = parse("221F9A", "7DA 05 62 1F 9A 12 34")
        assertEquals(Status.OK, r.status)
        assertEquals(listOf(EcuMessage(0x7DA, "621F9A1234".hexToBytesOrNull()!!)), r.messages)
    }

    @Test
    fun singleFrameWithoutSpacesAndWithPaddingIsTrimmedToItsLength() {
        val r = parse("010D", "7E803410D32AAAAAAAA")
        assertEquals(Status.OK, r.status)
        assertEquals("410D32", r.messages.single().payloadHex)
    }

    @Test
    fun multiFrameVin() {
        // 49 02 01 + "TESTVIN0123456789" = 20 bytes (0x14)
        val r = parse(
            "0902",
            "7E8 10 14 49 02 01 54 45 53",
            "7E8 21 54 56 49 4E 30 31 32",
            "7E8 22 33 34 35 36 37 38 39",
        )
        assertEquals(Status.OK, r.status)
        val payload = r.messages.single().payload
        assertEquals(20, payload.size)
        assertEquals("TESTVIN0123456789", String(payload.copyOfRange(3, 20), Charsets.US_ASCII))
    }

    @Test
    fun multiFrameIsTrimmedToDeclaredLength() {
        // 12 bytes declared; the last consecutive frame carries one padding byte (55).
        val r = parse(
            "2101",
            "7DA 10 0C 61 01 AA BB CC DD",
            "7DA 21 EE FF 11 22 33 44 55",
        )
        assertEquals(Status.OK, r.status)
        assertEquals("6101AABBCCDDEEFF11223344", r.messages.single().payloadHex)
    }

    @Test
    fun multiFrameSequenceWrapsAfterF() {
        // 6 + 16 * 7 = 118 bytes: frames 21..2F then 20.
        val total = 6 + 16 * 7
        val data = ByteArray(total) { (it + 1).toByte() }.also { it[0] = 0x61; it[1] = 0x01 }
        val lines = mutableListOf("7DA 10 %02X ".format(total) + data.copyOfRange(0, 6).toHex())
        var offset = 6
        var seq = 1
        while (offset < total) {
            lines += "7DA 2%X ".format(seq) + data.copyOfRange(offset, offset + 7).toHex()
            offset += 7
            seq = (seq + 1) and 0x0F
        }
        val r = parse("2101", *lines.toTypedArray())
        assertEquals(Status.OK, r.status)
        assertEquals(data.toHex(), r.messages.single().payloadHex)
    }

    @Test
    fun outOfOrderConsecutiveFrameIsMalformed() {
        val r = parse(
            "0902",
            "7E8 10 14 49 02 01 54 45 53",
            "7E8 22 33 34 35 36 37 38 39",
        )
        assertEquals(Status.MALFORMED, r.status)
        assertTrue(r.errorText!!.contains("out of order"))
    }

    @Test
    fun missingConsecutiveFrameIsMalformed() {
        val r = parse("0902", "7E8 10 14 49 02 01 54 45 53")
        assertEquals(Status.MALFORMED, r.status)
        assertTrue(r.errorText!!.contains("incomplete"))
    }

    @Test
    fun negativeReply() {
        val r = parse("221F9A", "7DA 03 7F 22 31 00 00 00 00")
        assertEquals(Status.NEGATIVE, r.status)
        assertEquals(0x31, r.negativeCode)
        assertTrue(r.messages.isEmpty())
    }

    @Test
    fun responsePendingFollowedByAnswer() {
        val r = parse("221F9A", "7DA 03 7F 22 78", "7DA 05 62 1F 9A 12 34")
        assertEquals(Status.OK, r.status)
        assertEquals("621F9A1234", r.messages.single().payloadHex)
    }

    @Test
    fun responsePendingOnly() {
        val r = parse("221F9A", "7DA 03 7F 22 78")
        assertEquals(Status.NEGATIVE, r.status)
        assertEquals(0x78, r.negativeCode)
    }

    @Test
    fun responsePendingThenFinalNegative() {
        val r = parse("221F9A", "7DA 03 7F 22 78", "7DA 03 7F 22 22")
        assertEquals(Status.NEGATIVE, r.status)
        assertEquals(0x22, r.negativeCode)
    }

    @Test
    fun functionalRequestAnsweredBySeveralEcus() {
        val r = parse("010C", "7E8 04 41 0C 1A F8", "7EA 04 41 0C 00 00")
        assertEquals(Status.OK, r.status)
        assertEquals(listOf(0x7E8, 0x7EA), r.messages.map { it.canId })
    }

    @Test
    fun noData() {
        val r = parse("221F9A", "NO DATA")
        assertEquals(Status.NO_DATA, r.status)
        assertEquals("NO DATA", r.errorText)
    }

    @Test
    fun adapterTextErrorsAreKeptVerbatim() {
        for (text in listOf("CAN ERROR", "BUFFER FULL", "STOPPED", "?", "UNABLE TO CONNECT", "BUS INIT: ...ERROR")) {
            val r = parse("221F9A", text)
            assertEquals(Status.ADAPTER_ERROR, r.status, text)
            assertEquals(text, r.errorText)
        }
    }

    @Test
    fun emptyReply() {
        val r = parse("221F9A", "")
        assertEquals(Status.ADAPTER_ERROR, r.status)
        assertNotNull(r.errorText)
    }

    @Test
    fun echoAndSearchingLinesAreIgnored() {
        val r = parse("221F9A", "221F9A", "SEARCHING...", "7DA 05 62 1F 9A 12 34")
        assertEquals(Status.OK, r.status)
        assertNull(r.errorText)
    }

    @Test
    fun replyForADifferentDidIsNotAccepted() {
        val r = parse("221F9A", "7DA 05 62 1F 5B 12 34")
        assertEquals(Status.ADAPTER_ERROR, r.status)
        assertTrue(r.errorText!!.contains("unexpected reply"))
    }

    @Test
    fun replyForADifferentServiceIsNotAccepted() {
        val r = parse("2101", "7DA 04 62 01 12 34")
        assertEquals(Status.ADAPTER_ERROR, r.status)
    }
}
