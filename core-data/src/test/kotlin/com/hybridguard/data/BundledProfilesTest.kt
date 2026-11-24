package com.hybridguard.data

import com.hybridguard.obd.RawSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The profiles shipped in src/main/resources/vehicles load, validate and
 * decode. Decoding checks use the OBDb formulas, which are the docs/02
 * formulas; whether they are right for this car is for the Phase 1 logs.
 */
class BundledProfilesTest {

    private val loader = ProfileLoader() // classpath

    @Test
    fun everyIndexedProfileLoads() {
        val index = loader.index()
        assertEquals("sae-j1979", index.fallbackId)
        for (id in index.profileIds) {
            val p = loader.load(id)
            assertTrue(p.commands.isNotEmpty(), id)
        }
    }

    @Test
    fun europeanCorollaIsDetectedByVinAndTheYearIsAsked() {
        // UK-built (TMUK) Corolla 1.8 hybrid hatchback prefix; made-up serial. Position 10 is "0": no model year in the VIN.
        val vin = "SB1K93BE00E000001"
        val store = InMemoryVehicleStore()
        val selector = ProfileSelector(loader, store) { 2026 }
        assertEquals(
            ProfileSelection("toyota-corolla-hybrid-e210", null, SelectionReason.VIN_MATCH),
            selector.select(vin),
        )
        assertTrue(selector.needsModelYear(vin), "the Corolla's signals depend on the year: ask the owner")

        selector.setModelYear(vin, 2022)
        assertEquals(
            ProfileSelection("toyota-corolla-hybrid-e210", 2022, SelectionReason.VIN_MATCH),
            selector.select(vin),
        )
        assertTrue(!selector.needsModelYear(vin))
    }

    @Test
    fun corollaIncludesTheStandardPids() {
        val p = loader.load("toyota-corolla-hybrid-e210")
        val keys = p.commands.map { it.key }.toSet()
        assertTrue("7E0:010D" in keys, "SAE J1979 speed")
        assertTrue("7D2:221F9A" in keys, "hybrid ECU HV battery")
        assertTrue("7C0:221022" in keys, "meter fuel litres")
        assertEquals(listOf("COROLLAHYBRID_SOC", "BAT_SOC"), p.roles[Role.SOC])
    }

    @Test
    fun catalogFor2019() {
        val catalog = loader.load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val keys = catalog.commands.map { it.key }.toSet()
        assertTrue("747:221814" in keys, "battery temps are filtered from 2019")
        assertTrue("7D2:221040" in keys, "transaxle oil temp is filtered to 2022")
        assertTrue("750:221004" !in keys, "TPMS commands filtered from 2023")

        val requests = catalog.requests()
        assertTrue(requests.size > 100, "polled requests: ${requests.size}")
        assertTrue(requests.none { it.header == 0x750 }, "extended addressing (TPMS) is not polled yet")
        assertTrue(catalog.unsupported.all { (c, _) -> c.header == 0x750 }, "${catalog.unsupported.map { it.first.key }}")
    }

    @Test
    fun hvBatteryVoltageAndCurrentFromAMultiFrameReply() {
        val catalog = loader.load("toyota-corolla-hybrid-e210").forModelYear(2019)
        // 62 1F 9A | 00 00 | 34 00 (13312 * 156.25 / 10000 = 208.0 V) | FF 38 (-200 / 10 = -20.0 A)
        val raw = "7DA1009621F9A000034\n7DA2100FF38AAAAAAAA"
        val decoded = Decoder(catalog).decode(RawSample(1_234, 0x7D2, "221F9A", raw, RawSample.Status.OK))
        assertNotNull(decoded)
        assertEquals(RawSample.Status.OK, decoded.status)

        val volts = assertIs<SignalValue.Number>(decoded.values["COROLLAHYBRID_HVBAT_V"])
        assertEquals(208.0, volts.value, 1e-9)
        assertEquals("volts", volts.unit)
        assertEquals(-20.0, assertIs<SignalValue.Number>(decoded.values["COROLLAHYBRID_HVBAT_C"]).value, 1e-9)

        val resolver = catalog.roleResolver
        val latest = decoded.values.mapValues { (id, v) -> SignalReading(decoded.tMs, id, v) }
        assertEquals("COROLLAHYBRID_HVBAT_V", resolver.resolve(Role.HV_VOLTAGE, latest)!!.signalId)
    }

    @Test
    fun shiftPositionFromRepliesRecordedOnTheOwnersCar() {
        // session-20261113-181624: one reply per lever position, exactly as the adapter sent it.
        val catalog = loader.load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val decoder = Decoder(catalog)
        val recorded = mapOf(
            "7DA056210610001" to "P",
            "7DA056210610202" to "R",
            "7DA056210610404" to "N",
            "7DA056210610608" to "D",
            "7DA056210610810" to "B",
        )
        for ((raw, gear) in recorded) {
            val decoded = decoder.decode(RawSample(0, 0x7D2, "221061", raw, RawSample.Status.OK))!!
            assertEquals(gear, assertIs<SignalValue.Enumerated>(decoded.values["COROLLAHYBRID_SHIFT_POS"]).value, raw)
            assertEquals(gear, assertIs<SignalValue.Enumerated>(decoded.values["COROLLAHYBRID_SHIFT_POS_FLAGS"]).value, raw)
            val latest = decoded.values.mapValues { (id, v) -> SignalReading(0, id, v) }
            assertEquals(gear, assertIs<SignalValue.Enumerated>(catalog.roleResolver.resolve(Role.GEAR, latest)!!.value).value)
        }
    }

    @Test
    fun readyFlagFromRepliesRecordedOnTheOwnersCar() {
        val catalog = loader.load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val decoder = Decoder(catalog)
        // Both recorded in READY (session-20261113-174656 / -181624); the last one is made up: bit 15 clear.
        for ((raw, ready) in listOf("7DA056210760F03" to 1.0, "7DA056210760F0B" to 1.0, "7DA056210760F02" to 0.0)) {
            val decoded = decoder.decode(RawSample(0, 0x7D2, "221076", raw, RawSample.Status.OK))!!
            val value = assertIs<SignalValue.Number>(decoded.values["COROLLAHYBRID_READY"])
            assertEquals(ready, value.value, raw)
            assertEquals("noyes", value.unit)
        }
        assertEquals(listOf("COROLLAHYBRID_READY"), catalog.roleResolver.signalsFor(Role.READY))
    }

    @Test
    fun venzaDefinitionsDecodeTheRepliesRecordedOnTheOwnersCar() {
        // session-20261113-174656 (discovery scan), raw as the adapter sent it.
        val catalog = loader.load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val decoder = Decoder(catalog)
        fun number(header: Int, request: String, raw: String, id: String): Double {
            val decoded = decoder.decode(RawSample(0, header, request, raw, RawSample.Status.OK))!!
            return assertIs<SignalValue.Number>(decoded.values[id], "$id from $raw").value
        }
        assertEquals(111_382.4, number(0x7D2, "221FA6", "7DA07621FA60010FEE0", "COROLLAHYBRID_ODO_1FA6"), 1e-6)
        // Same log, 7D2 22 0103: 01 B3 16 = 111382 km.
        assertEquals(111_382.0, number(0x7D2, "220103", "7DA076201030101B316", "COROLLAHYBRID_ODO_V2_V2"), 1e-6)
        val stats = listOf("7DA10146215E8000015", "7DA2140000008F98180", "7DA2200250000071C5A").joinToString("\n")
        assertEquals(544.0, number(0x7D2, "2215E8", stats, "COROLLAHYBRID_12V_AH_IN"), 1e-6)
        assertEquals(229.7, number(0x7D2, "2215E8", stats, "COROLLAHYBRID_12V_AH_OUT"), 1e-6)
        assertEquals(37.0, number(0x7D2, "2215E8", stats, "COROLLAHYBRID_READY_HOURS"), 1e-6)
        assertEquals(54.0, number(0x7D2, "2215E5", "7DA046215E56C", "COROLLAHYBRID_12V_CAP"), 1e-6)
        assertEquals(0.0, number(0x7D2, "221060", "7DA0462106000", "COROLLAHYBRID_APP_V2"), 1e-6)
        assertEquals(69 / 2.55, number(0x747, "221F5B", "74F04621F5B45", "COROLLAHYBRID_SOC_747"), 1e-6)
        // From the Prius list: fan as is; intake air temperature converted from the Prius °F formula to °C.
        assertEquals(0.0, number(0x747, "221802", "74F0762180200000000", "COROLLAHYBRID_HYBRID_BAT_1_FAN_747"), 1e-6)
        val celsius = number(0x747, "22180B", "74F0562180B3DE3", "COROLLAHYBRID_HYBRID_BAT_IAT_1")
        val fahrenheit = 0x3DE3 * 458.0 / 65535 - 58
        assertEquals((fahrenheit - 32) * 5 / 9, celsius, 1e-9)
        assertEquals(11.5, celsius, 0.05)
    }

    @Test
    fun engineWaterPumpFromRepliesRecordedOnTheOwnersCar() {
        // HybridLogger session-20261114-101039 (docs/02 C8), engine running:
        // 74 6F = target 2900 / actual 2775 rpm, 2827 45 = 2760 rpm five seconds later; 00 00 with the engine stopped.
        val catalog = loader.load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val decoder = Decoder(catalog)
        fun number(request: String, raw: String, id: String): Double {
            val decoded = decoder.decode(RawSample(0, 0x700, request, raw, RawSample.Status.OK))!!
            return assertIs<SignalValue.Number>(decoded.values[id], "$id from $raw").value
        }
        assertEquals(2900.0, number("221043", "70805621043746F", "COROLLAHYBRID_EWP_TARGET_VSS"), 1e-9)
        assertEquals(2775.0, number("221043", "70805621043746F", "COROLLAHYBRID_EWP_RPM"), 1e-9)
        assertEquals(2760.0, number("222827", "7080462282745", "COROLLAHYBRID_ELECTRIC_WATER_PUMP"), 1e-9)
        assertEquals(0.0, number("221043", "708056210430000", "COROLLAHYBRID_EWP_RPM"), 1e-9)
        assertEquals(
            listOf("COROLLAHYBRID_EWP_RPM", "COROLLAHYBRID_ELECTRIC_WATER_PUMP"),
            catalog.roleResolver.signalsFor(Role.ENGINE_WATER_PUMP),
        )
        assertEquals(listOf("COROLLAHYBRID_EWP_TARGET_VSS"), catalog.roleResolver.signalsFor(Role.ENGINE_WATER_PUMP_TARGET))
    }

    @Test
    fun inverterWaterPumpFromRepliesRecordedOnTheOwnersCar() {
        // 7D2 22 1039 bytes 2-3, x1 rpm, confirmed 2019 (HybridLogger profile 15, docs/02 C13 there).
        // Frames exactly as recorded: session-20261114-180641 (READY, inverter coolant 16-18 C;
        // 3432 and 3450 rpm), session-20261114-101039 (READY, 5-12 C; and before READY).
        val catalog = loader.load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val decoder = Decoder(catalog)
        fun number(raw: String): Double {
            val decoded = decoder.decode(RawSample(0, 0x7D2, "221039", raw, RawSample.Status.OK))!!
            return assertIs<SignalValue.Number>(decoded.values["COROLLAHYBRID_INV_WP_RPM"], raw).value
        }
        assertEquals(3432.0, number("7DA0762103921480D68"), 1e-9)
        assertEquals(3450.0, number("7DA0762103921480D7A"), 1e-9)
        assertEquals(4248.0, number("7DA0762103927AE1098"), 1e-9)
        assertEquals(160.0, number("7DA07621039033300A0"), 1e-9)
        assertEquals(listOf("COROLLAHYBRID_INV_WP_RPM"), catalog.roleResolver.signalsFor(Role.INVERTER_PUMP))
        // The OBDb "Front/rear motor torque ratio" on the same request is for the AWD models: disabled, one command left.
        val command = assertNotNull(catalog.command("7D2:221039"))
        assertTrue(command.signals.none { it.id == "COROLLAHYBRID_FRONT_REAR_MOTOR_TQ" })
        assertEquals(1, catalog.commands.count { it.key == "7D2:221039" })
    }

    @Test
    fun standardSpeedAndRpm() {
        val catalog = loader.load("toyota-corolla-hybrid-e210").forModelYear(2019)
        val decoder = Decoder(catalog)
        val speed = decoder.decode(RawSample(0, 0x7E0, "010D", "7E803410D32", RawSample.Status.OK))!!
        assertEquals(50.0, assertIs<SignalValue.Number>(speed.values["VSS"]).value)
        val rpm = decoder.decode(RawSample(0, 0x7E0, "010C", "7E804410C1AF8", RawSample.Status.OK))!!
        assertEquals(1726.0, assertIs<SignalValue.Number>(rpm.values["RPM"]).value)
    }
}
