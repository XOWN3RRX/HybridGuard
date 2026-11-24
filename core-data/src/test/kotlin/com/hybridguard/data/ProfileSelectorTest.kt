package com.hybridguard.data

import com.hybridguard.obd.RawSample
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileSelectorTest {

    private val loader = ProfileLoader(FakeProfiles.source())
    private fun selector(store: VehicleStore = InMemoryVehicleStore()) = ProfileSelector(loader, store) { 2026 }

    // Made-up VINs: "TST1AB" prefix, year code K (2019) at position 10.
    private val testVin = "TST1ABCDEK1234567"
    private val unknownVin = "ZZZ9ZZZZZK1234567"

    @Test
    fun vinModelYear() {
        assertEquals(2019, Vin.modelYear(testVin, currentYear = 2026))
        assertEquals(2026, Vin.modelYear("TST1ABCDET1234567", currentYear = 2026))
        assertEquals(2027, Vin.modelYear("TST1ABCDEV1234567", currentYear = 2026)) // next year's models
        assertEquals(1997, Vin.modelYear("TST1ABCDEV1234567", currentYear = 2025))
        assertNull(Vin.modelYear("TOO-SHORT", 2026))
        assertFalse(Vin.isValid("TST1ABCDEK123456O")) // letter O is never used
    }

    @Test
    fun vinPrefixSelectsTheProfile() {
        assertEquals(ProfileSelection("test-ev", 2019, SelectionReason.VIN_MATCH), selector().select(testVin))
    }

    @Test
    fun prefixGivesTheYearWhenTheVinDoesNot() {
        // European style: position 10 is "0", so only the prefix entry knows the year.
        val euVin = "TST2EUCDE01234567"
        assertNull(Vin.modelYear(euVin, 2026))
        assertEquals(ProfileSelection("test-ev", 2021, SelectionReason.VIN_MATCH), selector().select(euVin))

        // The prefix year is preferred over position 10, and the user's entry over both.
        assertEquals(2021, selector().select("TST2EUCDEK1234567").modelYear)
        val store = InMemoryVehicleStore()
        selector(store).chooseManually(euVin, "test-ev", modelYear = 2020)
        assertEquals(ProfileSelection("test-ev", 2020, SelectionReason.MANUAL), selector(store).select(euVin))
        selector(store).chooseManually(euVin, "test-ev")
        assertEquals(2021, selector(store).select(euVin).modelYear, "manual profile without a year keeps the prefix year")
    }

    @Test
    fun ownersModelYearIsKeptWithTheProfileChoice() {
        val store = InMemoryVehicleStore()
        val euVin = "TST2EUCDE01234567"
        selector(store).setModelYear(euVin, 2023)
        assertEquals(ProfileSelection("test-ev", 2023, SelectionReason.VIN_MATCH), selector(store).select(euVin))
        assertEquals(2023, selector(store).ownerModelYear(euVin))

        selector(store).chooseManually(euVin, "generic", selector(store).ownerModelYear(euVin))
        selector(store).setModelYear(euVin, 2024)
        assertEquals(ProfileSelection("generic", 2024, SelectionReason.MANUAL), selector(store).select(euVin), "profile choice kept")

        selector(store).setModelYear(euVin, null)
        assertNull(selector(store).ownerModelYear(euVin))
    }

    @Test
    fun theYearIsAskedOnlyWhenItMatters() {
        // No year anywhere, but these fake profiles have no year-limited signals ("years" not set).
        assertFalse(selector().needsModelYear("ZZZ9ZZZZZ01234567"))
        assertFalse(selector().needsModelYear(testVin))
    }

    @Test
    fun unknownCarFallsBackToGeneric() {
        assertEquals(ProfileSelection("generic", 2019, SelectionReason.FALLBACK), selector().select(unknownVin))
        assertEquals(ProfileSelection("generic", null, SelectionReason.FALLBACK), selector().select(null))
    }

    @Test
    fun manualChoiceIsRememberedPerVin() {
        val store = InMemoryVehicleStore()
        selector(store).chooseManually(unknownVin, "test-ev", modelYear = 2021)

        assertEquals(ProfileSelection("test-ev", 2021, SelectionReason.MANUAL), selector(store).select(unknownVin))
        assertEquals(SelectionReason.FALLBACK, selector(store).select("ZZZ9ZZZZZK7654321").reason, "other cars unaffected")

        selector(store).chooseManually(unknownVin, null)
        assertEquals(SelectionReason.FALLBACK, selector(store).select(unknownVin).reason)
    }

    @Test
    fun manualChoiceMustBeAKnownProfile() {
        assertFailsWith<IllegalArgumentException> { selector().chooseManually(testVin, "no-such-profile") }
    }

    @Test
    fun capabilityReportIsCachedPerVinAndSurvivesARestart() {
        val dir = Files.createTempDirectory("hybridguard-store").toFile()
        try {
            val info = loader.info("test-ev")
            val report = CapabilityReport.fromSamples(
                info,
                probedAtEpochMs = 1_700_000_000_000,
                samples = listOf(
                    RawSample(0, 0x7E4, "220101", "7EC 07 62 01 01 08 40 00 7D", RawSample.Status.OK),
                    RawSample(5, 0x7E4, "220102", "7EC 03 7F 22 31", RawSample.Status.NEGATIVE),
                    RawSample(9, 0x7E0, "010D", "", RawSample.Status.TIMEOUT),
                ),
            )
            assertTrue(report.isDead("7E4:220102"))
            assertFalse(report.isDead("7E4:220101"))
            assertFalse(report.isDead("7E0:010D"), "a timeout is not proof the request is unsupported")

            selector(FileVehicleStore(dir)).apply {
                chooseManually(testVin, "test-ev", modelYear = 2019)
                saveCapabilities(testVin, report)
            }

            // New store instance = app restart.
            val reloaded = ProfileSelector(loader, FileVehicleStore(dir)) { 2026 }
            assertEquals(report, reloaded.capabilities(testVin))
            assertEquals(SelectionReason.MANUAL, reloaded.select(testVin).reason)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deadRequestsAreNotPolled() {
        val catalog = loader.load("test-ev").forModelYear(2019)
        val info = loader.info("test-ev")
        val report = CapabilityReport(
            info.id,
            info.version,
            0,
            mapOf("7E4:220102" to CapabilityResult(RawSample.Status.NEGATIVE, 0x31)),
        )
        val polled = catalog.requests(report).map { it.payloadHex }
        assertFalse("220102" in polled)
        assertTrue("220101" in polled)

        // A report made with another profile version is ignored.
        val stale = report.copy(profileVersion = info.version - 1)
        assertTrue("220102" in catalog.requests(stale).map { it.payloadHex })
    }
}
