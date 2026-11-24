package com.hybridguard.data

import com.hybridguard.obd.RawSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoleAnswersTest {

    private val catalog = ProfileLoader().load("toyota-corolla-hybrid-e210").forModelYear(2019)

    private fun report(results: Map<String, CapabilityResult>) =
        CapabilityReport(catalog.profile.id, catalog.profile.version, 0L, results)

    @Test
    fun requestsAreListedInPreferenceOrder() {
        val answers = catalog.answersFor(Role.AMBIENT_TEMP, report(emptyMap()))
        assertEquals(listOf("7C4 21 3D", "7E0 01 46", "7D2 22 1F46"), answers.map { it.request })
    }

    @Test
    fun aRoleNoRequestAnsweredIsNotGiven() {
        val r = report(
            mapOf(
                "7C4:213D" to CapabilityResult(RawSample.Status.NO_DATA),
                "7E0:0146" to CapabilityResult(RawSample.Status.NEGATIVE, negativeCode = 0x31),
                // 7D2 22 1F46: not asked.
                "7E0:0105" to CapabilityResult(RawSample.Status.OK),
            ),
        )
        val missing = catalog.notGiven(listOf(Role.AMBIENT_TEMP, Role.COOLANT_TEMP), r)
        assertEquals(setOf(Role.AMBIENT_TEMP), missing.keys)
        assertEquals(
            listOf(
                "7C4 21 3D: no reply",
                "7E0 01 46: refused (NRC 31)",
                "7D2 22 1F46: not asked (the car does not list it)",
            ),
            missing.getValue(Role.AMBIENT_TEMP).map { it.text },
        )
    }

    @Test
    fun oneAnsweredRequestIsEnoughAndNoReportMeansUnknown() {
        val r = report(mapOf("7E0:0146" to CapabilityResult(RawSample.Status.OK)))
        assertTrue(catalog.notGiven(listOf(Role.AMBIENT_TEMP), r).isEmpty())
        assertTrue(catalog.notGiven(listOf(Role.AMBIENT_TEMP), null).isEmpty())
    }

    @Test
    fun theCorollaSendsNoOilRequest() {
        // The owner's car gives no oil temperature (docs/02): the role maps to nothing.
        assertEquals(emptyList(), catalog.roles[Role.OIL_TEMP])
        assertTrue(catalog.pollKeys({ it == Role.OIL_TEMP }) { true }.isEmpty())
        assertTrue(catalog.notGiven(listOf(Role.OIL_TEMP), report(emptyMap())).isEmpty())
    }
}
