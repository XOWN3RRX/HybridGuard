package com.hybridguard.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalSetParserTest {

    private val json = """
        { "ecu": [ { "hdr": "7E1", "type": "transmission" } ],
          "commands": [
            { "hdr": "7D2", "rax": "7DA", "fcm1": true, "cmd": {"22": "1F9A"}, "freq": 0.5,
              "signals": [
                {"id": "V", "path": "Battery", "fmt": {"bix": 16, "len": 16, "max": 1023, "mul": 156.25, "div": 10000, "unit": "volts"}, "name": "HV voltage"},
                {"id": "A", "fmt": {"bix": 32, "len": 16, "max": 3276.7, "min": -3276.8, "div": 10, "sign": true, "unit": "amps"}, "name": "HV current"}
              ]},
            { "hdr": "7E0", "rax": "7E8", "cmd": {"01": "0D"}, "freq": 0.25,
              "signals": [ {"id": "VSS", "fmt": {"len": 8, "max": 255, "nullmax": 255, "unit": "kilometersPerHour"}, "name": "Speed"} ]},
            { "hdr": "750", "rax": "758", "eax": "2A", "dbg": true, "cmd": {"22": "2021"}, "freq": 3600, "filter": {"from": 2023},
              "signals": [ {"id": "TID", "fmt": {"len": 8, "map": {"1": {"description": "Front left", "value": "FL"}}}, "name": "Tire 1"} ]}
          ] }
    """.trimIndent()

    @Test
    fun parsesCommandsAndSignals() {
        val commands = SignalSetParser.parse(json, "test")
        assertEquals(3, commands.size)

        val hv = commands[0]
        assertEquals("7D2:221F9A", hv.key)
        assertEquals(0x7DA, hv.receiveAddress)
        assertEquals(500, hv.periodMs)
        assertTrue(hv.forceFlowControl)
        assertEquals(2, hv.echoLength)
        val amps = assertIs<Scaling>(hv.signals[1].format)
        assertTrue(amps.signed)
        assertEquals(-3276.8, amps.min)

        val speed = commands[1]
        assertEquals("7E0:010D", speed.key)
        assertEquals(250, speed.periodMs)
        assertEquals(1, speed.echoLength)
        assertEquals(255.0, assertIs<Scaling>(speed.signals[0].format).nullMax)

        val tire = commands[2]
        assertEquals(0x2A, tire.extendedAddress)
        assertTrue(tire.debug)
        assertEquals(CommandFilter(from = 2023), tire.filter)
        assertEquals(EnumEntry("FL", "Front left"), assertIs<Enumeration>(tire.signals[0].format).map[1L])
    }

    @Test
    fun requestsAreBuiltOnlyForSupportedCommands() {
        val commands = SignalSetParser.parse(json, "test")
        val request = assertNotNull(commands[0].toRequest())
        assertEquals("221F9A", request.payloadHex)
        assertEquals(0x7D2, request.header)
        assertTrue(request.multiFrame)

        assertNull(commands[2].toRequest())
        assertTrue(commands[2].unsupportedReason!!.contains("eax"))
    }

    @Test
    fun badInputFailsWithItsLocation() {
        val noLen = """{"commands": [{"hdr": "7E0", "cmd": {"01": "0D"}, "freq": 1, "signals": [{"id": "X", "fmt": {"max": 1, "unit": "scalar"}}]}]}"""
        val e = assertFailsWith<ProfileFormatException> { SignalSetParser.parse(noLen, "bad.json") }
        assertTrue(e.message!!.contains("bad.json commands[0]"), e.message)
        assertTrue(e.message!!.contains("len"), e.message)

        val writeService = """{"commands": [{"hdr": "7E0", "cmd": {"2E": "1234"}, "freq": 1, "signals": []}]}"""
        assertFailsWith<ProfileFormatException> { SignalSetParser.parse(writeService, "bad.json") }

        assertFailsWith<ProfileFormatException> { SignalSetParser.parse("[]", "bad.json") }
    }
}
