package com.hybridguard.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class FakeElmTransportTest {

    @Test
    fun framesRoundTripThroughTheParser() {
        for (size in listOf(3, 7, 8, 20, 118)) {
            val payload = ByteArray(size) { (it * 7).toByte() }.also { it[0] = 0x62; it[1] = 0x1F; it[2] = 0x9A.toByte() }
            val parsed = ResponseParser.parse("221F9A", FakeElmTransport.frames(0x7DA, payload))
            assertEquals(RawSample.Status.OK, parsed.status, "size $size")
            assertEquals(EcuMessage(0x7DA, payload), parsed.messages.single(), "size $size")
        }
    }

    @Test
    fun driverTalksToTheFakeAdapter() = runTest {
        val seen = mutableListOf<Pair<Int, String>>()
        val fake = object : FakeElmTransport(responseDelayMs = 50) {
            override fun respond(header: Int, payloadHex: String): String? {
                seen += header to payloadHex
                return if (payloadHex == "221F9A") frames(header + 8, byteArrayOf(0x62, 0x1F, 0x9A.toByte(), 0x12)) else null
            }
        }
        val elm = Elm327(fake, backgroundScope, clock = { testScheduler.currentTime })
        val report = elm.open()
        assertEquals("ELM327 v1.5 (simulated)", report.adapter.elm)
        assertEquals(null, report.adapter.stn)

        val ok = elm.read(ObdRequest.fromHex(0x7D2, "221F9A", 1000))
        assertEquals(RawSample.Status.OK, ok.status)
        assertEquals(RawSample.Status.TIMEOUT, elm.read(ObdRequest.fromHex(0x7D2, "221F5B", 1000)).status)
        assertEquals(listOf(0x7D2 to "221F9A", 0x7D2 to "221F5B"), seen)
    }
}
