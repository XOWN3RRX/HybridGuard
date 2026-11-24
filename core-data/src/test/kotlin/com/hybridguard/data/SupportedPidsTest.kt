package com.hybridguard.data

import com.hybridguard.obd.RawSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportedPidsTest {

    @Test
    fun bitmapBitsMapToPids() {
        // BE 1F A8 13: the classic ELM327 datasheet example.
        val pids = SupportedPids.parse(listOf(RawSample(0, 0x7E0, "0100", "7E8 06 41 00 BE 1F A8 13", RawSample.Status.OK)))!!
        assertEquals(
            setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20),
            pids,
        )
        assertTrue(SupportedPids.nextBitmapSupported(pids, 0x00))
    }

    @Test
    fun noAnswerMeansUnknownNotNothingSupported() {
        assertNull(SupportedPids.parse(listOf(RawSample(0, 0x7E0, "0100", "NO DATA", RawSample.Status.NO_DATA))))
    }

    @Test
    fun simulatedCarAdvertisesItsCatalogPids() {
        val catalog = ProfileLoader(FakeProfiles.source()).load("test-ev").forModelYear(2019)
        val car = SimulatedCar(catalog, clock = { 0 })
        val reply = car.javaClass.getDeclaredMethod("respond", Int::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(car, 0x7E0, "0100") as String
        assertEquals(setOf(0x0C, 0x0D), SupportedPids.parse(listOf(RawSample(0, 0x7E0, "0100", reply, RawSample.Status.OK))))
    }
}
