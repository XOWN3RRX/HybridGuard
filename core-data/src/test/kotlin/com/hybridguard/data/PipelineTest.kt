package com.hybridguard.data

import com.hybridguard.obd.ObdRequest
import com.hybridguard.obd.PollScheduler
import com.hybridguard.obd.RawSample
import com.hybridguard.obd.ReadGateway
import com.hybridguard.obd.ResponseParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/07: "load a tiny fake second profile and run the full pipeline on it".
 * VIN -> profile -> catalog -> scheduler -> gateway -> raw sample -> decoder -> roles,
 * with no line of Kotlin knowing anything about the fake car.
 */
class PipelineTest {

    /** Stands in for the ELM327 + car: fixed raw replies in ATH1 ATS0 format. */
    private class FakeCar : ReadGateway {
        val replies = mapOf(
            "010D" to "7E8 03 41 0D 2A",                // 42 km/h
            "010C" to "7E8 04 41 0C 00 00",             // engine off
            "220101" to "7EC 07 62 01 01 07 E0 FF 83",  // 201.6 V, raw -12.5 A
            "220102" to "7EC 04 62 01 02 80",           // 50.2 %
        )
        override suspend fun read(request: ObdRequest): RawSample {
            val raw = replies[request.payloadHex] ?: "NO DATA"
            return RawSample(0, request.header, request.payloadHex, raw, ResponseParser.parse(request.payloadHex, raw).status)
        }
    }

    @Test
    fun fakeProfileRunsEndToEnd() = runBlocking {
        val loader = ProfileLoader(FakeProfiles.source())
        val selection = ProfileSelector(loader, InMemoryVehicleStore()) { 2026 }.select("TST1ABCDEK1234567")
        assertEquals("test-ev", selection.profileId)

        val catalog = loader.load(selection.profileId).forModelYear(selection.modelYear)
        val requests = catalog.requests()
        assertEquals(setOf("010D", "010C", "220101", "220102"), requests.map { it.payloadHex }.toSet())

        val scheduler = PollScheduler(requests)
        val car = FakeCar()
        val decoder = Decoder(catalog)
        val latest = mutableMapOf<String, SignalReading>()
        repeat(requests.size) {
            val sample = car.read(scheduler.next(0)!!)
            val decoded = decoder.decode(sample)!!
            for ((id, value) in decoded.values) latest[id] = SignalReading(sample.tMs, id, value)
        }

        val roles = catalog.roleResolver
        fun number(role: Role) = assertIs<SignalValue.Number>(roles.resolve(role, latest)!!.value).value

        assertEquals(42.0, number(Role.SPEED))
        assertEquals(0.0, number(Role.ENGINE_RPM))
        assertEquals(201.6, number(Role.HV_VOLTAGE), 1e-9)
        assertEquals(12.5, number(Role.HV_CURRENT), 1e-9) // sign corrected by overrides.json
        assertEquals(50.196, number(Role.SOC), 1e-3)
        assertNull(roles.resolve(Role.GEAR, latest), "a role with no signal is unavailable")
        assertNull(roles.resolve(Role.FUEL_LITRES, latest), "a role the profile does not map is unavailable")
    }

    @Test
    fun staleOrFailedValuesDoNotResolve() {
        val roles = RoleResolver(mapOf(Role.SOC to listOf("A", "B")))
        val latest = mapOf(
            "A" to SignalReading(0, "A", SignalValue.NoValue),
            "B" to SignalReading(1_000, "B", SignalValue.Number(55.0, "percent")),
        )
        assertEquals("B", roles.resolve(Role.SOC, latest)!!.signalId, "falls back to the next signal")
        assertNull(roles.resolve(Role.SOC, latest, nowMs = 5_000, maxAgeMs = 3_000), "older than 3 s is not shown")
    }

    @Test
    fun negativeReplyDecodesToNoValues() {
        val catalog = ProfileLoader(FakeProfiles.source()).load("test-ev").forModelYear(2019)
        val decoded = Decoder(catalog).decode(RawSample(7, 0x7E4, "220102", "7EC037F2231", RawSample.Status.NEGATIVE))!!
        assertEquals(RawSample.Status.NEGATIVE, decoded.status)
        assertEquals(0x31, decoded.negativeCode)
        assertTrue(decoded.values.isEmpty())
    }

    @Test
    fun requestOutsideTheCatalogIsIgnored() {
        val catalog = ProfileLoader(FakeProfiles.source()).load("test-ev").forModelYear(2019)
        assertNull(Decoder(catalog).decode(RawSample(0, 0x7E4, "22FFFF", "NO DATA", RawSample.Status.NO_DATA)))
    }
}
