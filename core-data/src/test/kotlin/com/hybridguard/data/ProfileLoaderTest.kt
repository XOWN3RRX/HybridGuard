package com.hybridguard.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileLoaderTest {

    private val loader = ProfileLoader(FakeProfiles.source())

    @Test
    fun indexAndInfo() {
        assertEquals(ProfileIndex(listOf("generic", "test-ev"), "generic"), loader.index())
        val info = loader.info("test-ev")
        assertEquals(3, info.version)
        assertEquals(listOf("generic"), info.includes)
        assertEquals(mapOf("bms" to 0x7E4), info.ecus)
        assertEquals(listOf("TST1", "TST1AB", "TST2EU"), info.vinPrefixes)
        assertEquals(mapOf("TST2EU" to 2021), info.vinModelYears)
    }

    @Test
    fun includesAreMergedBaseFirst() {
        val p = loader.load("test-ev")
        assertEquals(
            listOf("7E0:010D", "7E0:010C", "7E4:220101", "7E4:220102", "7E4:220103"),
            p.commands.map { it.key },
        )
        // Role mapping: base roles kept, own roles added.
        assertEquals(listOf("VSS"), p.roles[Role.SPEED])
        assertEquals(listOf("TEST_HV_V"), p.roles[Role.HV_VOLTAGE])
        assertEquals(emptyList(), p.roles[Role.GEAR])
    }

    @Test
    fun overridesAreApplied() {
        val p = loader.load("test-ev")
        val current = p.commands.flatMap { it.signals }.single { it.id == "TEST_HV_A" }.format
        assertEquals(-1.0, assertIs<Scaling>(current).mul)
        assertEquals(10.0, current.div) // untouched fields kept
        assertEquals(1000, p.commands.single { it.key == "7E4:220102" }.periodMs)
        assertTrue(p.commands.none { c -> c.signals.any { it.id == "TEST_UNUSED" } })
        assertTrue(p.commands.none { it.key == "7E4:220104" }, "command left without signals is dropped")
    }

    @Test
    fun overrideTyposFailLoudly() {
        fun loadWith(overrides: String) =
            ProfileLoader(FakeProfiles.source(mapOf("test-ev/overrides.json" to overrides))).load("test-ev")

        assertFailsWith<ProfileFormatException> { loadWith("""{ "signals": { "NO_SUCH_SIGNAL": { "fmt": { "mul": 2 } } } }""") }
        assertFailsWith<ProfileFormatException> { loadWith("""{ "commands": { "7E4:229999": { "freq": 1 } } }""") }
        assertFailsWith<ProfileFormatException> { loadWith("""{ "disableSignals": ["NO_SUCH_SIGNAL"] }""") }
        assertFailsWith<ProfileFormatException> { loadWith("""{ "signal": {} }""") }
        assertFailsWith<ProfileFormatException> { loadWith("""{ "commands": { "7E4:220101": { "cmd": {"22": "0105"} } } }""") }
    }

    @Test
    fun overrideCanAddACommandAndRemoveAField() {
        val p = ProfileLoader(
            FakeProfiles.source(
                mapOf(
                    "test-ev/overrides.json" to """
                        { "addCommands": [ { "hdr": "7E4", "rax": "7EC", "cmd": {"22": "0110"}, "freq": 1,
                            "signals": [ {"id": "TEST_NEW", "fmt": {"len": 8, "max": 255, "nullmax": 255, "unit": "scalar"}, "name": "New"} ] } ],
                          "signals": { "TEST_NEW": { "fmt": { "nullmax": null } } } }
                    """,
                ),
            ),
        ).load("test-ev")
        val added = p.commands.single { it.key == "7E4:220110" }.signals.single().format
        assertNull(assertIs<Scaling>(added).nullMax)
    }

    @Test
    fun roleNamingAnUnknownSignalFails() {
        val bad = FakeProfiles.source(mapOf("test-ev/derived.json" to """{ "soc": ["TEST_SOC", "TYPO_SOC"] }"""))
        val e = assertFailsWith<ProfileFormatException> { ProfileLoader(bad).load("test-ev") }
        assertTrue(e.message!!.contains("TYPO_SOC"))
    }

    @Test
    fun unknownRoleFails() {
        val bad = FakeProfiles.source(mapOf("test-ev/derived.json" to """{ "warpSpeed": ["TEST_SOC"] }"""))
        assertFailsWith<ProfileFormatException> { ProfileLoader(bad).load("test-ev") }
    }

    @Test
    fun includeCycleFails() {
        val cyclic = FakeProfiles.source(
            mapOf(
                "generic/profile.json" to """{ "id": "generic", "name": "Generic", "version": 1, "includes": ["test-ev"] }""",
            ),
        )
        assertFailsWith<ProfileFormatException> { ProfileLoader(cyclic).load("test-ev") }
    }

    @Test
    fun folderAndIdMustAgree() {
        val wrong = FakeProfiles.source(mapOf("test-ev/profile.json" to """{ "id": "other", "name": "x", "version": 1 }"""))
        assertFailsWith<ProfileFormatException> { ProfileLoader(wrong).info("test-ev") }
    }

    @Test
    fun catalogIsNarrowedToTheModelYear() {
        val p = loader.load("test-ev")
        assertFalse(p.forModelYear(2019).commands.any { it.key == "7E4:220103" })
        assertTrue(p.forModelYear(2031).commands.any { it.key == "7E4:220103" })
        assertFalse(p.forModelYear(null).commands.any { it.key == "7E4:220103" }, "unknown year never matches a filter")
    }
}
