package com.hybridguard.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TripSummaryGateTest {

    @Test
    fun showsWhenParkedAfterAKilometreUntilDoneOrDrivingOff() {
        val g = TripSummaryGate()
        assertFalse(g.update("P", 0.0), "start in P: nothing to sum up")
        assertFalse(g.update("D", 0.5))
        assertFalse(g.update("P", 0.8), "under 1 km: no summary")
        assertFalse(g.update("D", 0.9))
        assertTrue(g.update("P", 1.2), "parked after 1.2 km")
        assertTrue(g.update(null, 1.2), "a missing gear reading changes nothing")
        assertTrue(g.update("P", 1.2), "stays while parked")
        g.dismiss()
        assertFalse(g.update("P", 1.2), "Done: back to the dashboard")
        assertFalse(g.update("R", 1.2))
        assertTrue(g.update("P", 1.25), "parked again: shown again")
        assertFalse(g.update("D", 1.3), "driving off closes it")
    }

    @Test
    fun aNewTripStartsFromScratch() {
        val g = TripSummaryGate()
        g.update("D", 5.0)
        assertTrue(g.update("P", 5.0))
        assertFalse(g.update("P", 0.0), "new connection (distance back to 0) while parked: closed")
        assertFalse(g.update("P", 0.0))
    }
}
