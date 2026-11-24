package com.hybridguard.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PollSchedulerTest {

    private fun req(header: Int, hex: String, periodMs: Long) = ObdRequest.fromHex(header, hex, periodMs)

    @Test
    fun tiersFollowThePeriods() {
        assertEquals(PollTier.FAST, PollTier.of(250))
        assertEquals(PollTier.MEDIUM, PollTier.of(1_000))
        assertEquals(PollTier.SLOW, PollTier.of(5_000))
    }

    @Test
    fun requestsOnTheSameEcuAreGrouped() {
        val a1 = req(0x7E0, "2101", 1_000)
        val b1 = req(0x7D2, "221F9A", 1_000)
        val a2 = req(0x7E0, "2102", 1_000)
        val b2 = req(0x7D2, "221F5B", 1_000)
        val s = PollScheduler(listOf(a1, b1, a2, b2))

        assertEquals(listOf(a1, a2, b1, b2), List(4) { s.next(0) })
    }

    @Test
    fun fasterTierGoesFirst() {
        val slow = req(0x7E0, "2101", 5_000)
        val fast = req(0x7D2, "221F9A", 250)
        val s = PollScheduler(listOf(slow, fast))

        assertEquals(fast, s.next(0))
        assertEquals(slow, s.next(0))
    }

    @Test
    fun aboutAsUrgentRequestsOnTheCurrentEcuGoBeforeASwitch() {
        val dash = req(0x7D2, "221F9A", 1_000)
        val engine = req(0x7DF, "010D", 250)
        val s = PollScheduler(listOf(engine, dash))
        assertEquals(engine, s.next(0))
        assertEquals(dash, s.next(0))
        // At 300 ms the speed is 50 ms overdue, but switching back costs more than that wait.
        val other = req(0x7D2, "221F5B", 1_000)
        s.add(other, 300)
        assertEquals(other, s.next(300))
        assertEquals(engine, s.next(300))
    }

    @Test
    fun overloadSlowsEveryRequestAlikeAndSwitchesEcuRarely() {
        // Demand 16 req/s on two ECUs, the link manages 10 req/s (100 ms per read).
        val a = List(4) { req(0x7DF, "01%02X".format(0x0C + it), 250) }
        val b = List(2) { req(0x7D2, "221F%02X".format(it), 1_000) }
        val s = PollScheduler(a + b)
        val picks = mutableListOf<ObdRequest>()
        var t = 0L
        while (t < 30_000) {
            val r = s.next(t) ?: run { t = s.nextDueMs()!!; null } ?: continue
            picks += r
            t += 100
        }
        val switches = picks.zipWithNext().count { (x, y) -> x.header != y.header }
        assertTrue(switches < picks.size / 3, "$switches ECU switches in ${picks.size} reads")
        // Each request slows by a similar factor: a 1 s request is not starved by the 250 ms ones.
        val gapA = 30_000.0 / picks.count { it == a[0] }
        val gapB = 30_000.0 / picks.count { it == b[0] }
        assertTrue(gapB / 1_000 < 2.5 * gapA / 250, "250 ms request every $gapA ms, 1 s request every $gapB ms")
    }

    @Test
    fun requestIsNotSentBeforeItsPeriod() {
        val r = req(0x7D2, "221F9A", 250)
        val s = PollScheduler(listOf(r))

        assertEquals(r, s.next(0))
        assertNull(s.next(100))
        assertEquals(250, s.nextDueMs())
        assertEquals(r, s.next(250))
    }

    @Test
    fun slowRequestsAreNotStarvedWhenTheFastTierSaturatesTheBus() {
        // Four 250 ms requests need 16 req/s; the link manages 10 req/s (100 ms per read).
        val fast = List(4) { req(0x7D2, "22100%X".format(it), 250) }
        val slow = req(0x7E0, "2101", 5_000)
        val s = PollScheduler(fast + slow)

        val picks = mutableListOf<Pair<Long, ObdRequest>>()
        var t = 0L
        while (t < 30_000) {
            val r = s.next(t)
            if (r == null) {
                t = s.nextDueMs()!!
                continue
            }
            picks += t to r
            t += 100
        }

        // Starving after one period, then served about every two periods (5 s, 15 s, 25 s).
        val slowTimes = picks.filter { it.second == slow }.map { it.first }
        assertTrue(slowTimes.size >= 3, "slow request served at $slowTimes")
        assertTrue(slowTimes.first() <= 5_100, "first slow read at ${slowTimes.first()} ms")
        slowTimes.zipWithNext { a, b -> assertTrue(b - a <= 10_100, "gap ${b - a} ms") }
        // ~300 reads in 30 s, shared evenly by the four fast requests.
        fast.forEach { f -> assertTrue(picks.count { it.second == f } > 55, "fast request $f starved") }
    }

    @Test
    fun replacedRequestRunsAtTheNewPeriodFromNow() {
        val slow = req(0x7D2, "221F9A", 30_000)
        val s = PollScheduler(listOf(slow))
        assertEquals(slow, s.next(0))
        val fast = slow.withPeriod(250)
        assertTrue(s.replace(slow, fast, nowMs = 1_000))
        assertNull(s.next(1_100), "not before one new period")
        assertEquals(fast, s.next(1_250), "no longer waits for the old 30 s turn")
        assertEquals(fast, s.next(1_500))
        assertFalse(s.replace(slow, fast, nowMs = 2_000), "old request no longer scheduled")
    }

    @Test
    fun removedRequestIsNoLongerPolled() {
        val keep = req(0x7D2, "221F9A", 250)
        val dead = req(0x7D2, "221F5B", 250)
        val s = PollScheduler(listOf(keep, dead))
        s.remove(dead)

        assertEquals(keep, s.next(0))
        assertNull(s.next(0))
    }

    @Test
    fun addedRequestIsPolledFromTheGivenTime() {
        val base = req(0x7D2, "221F9A", 1_000)
        val added = req(0x7D2, "221234", 1_000)
        val s = PollScheduler(listOf(base))
        assertEquals(base, s.next(0))

        assertTrue(s.add(added, nowMs = 500))
        assertFalse(s.add(added, nowMs = 600), "already scheduled")
        assertEquals(added, s.next(500))
        assertNull(s.next(600))
        s.remove(added)
        assertEquals(base, s.next(1_000))
        assertNull(s.next(1_600), "removed again")
    }
}
