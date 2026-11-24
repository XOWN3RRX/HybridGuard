package com.hybridguard.obd

/** Priority tier derived from a request's period (docs/05: fast 250 ms, medium 1 s, slow 5-10 s). */
enum class PollTier {
    FAST,
    MEDIUM,
    SLOW,
    ;

    companion object {
        fun of(periodMs: Long): PollTier = when {
            periodMs <= 250 -> FAST
            periodMs <= 1_000 -> MEDIUM
            else -> SLOW
        }
    }
}

/**
 * Decides which request to send next. Pure and single-threaded: owned by one [PollingLoop].
 *
 * Rules:
 * 1. Only requests whose period has elapsed are candidates; each request
 *    waits at least its `periodMs` between two sends.
 * 2. Among them the most *urgent* goes first: the time since it was last
 *    sent, in periods of its own (1.0 = right on time, 2.0 = it waited two
 *    periods). A fast request's urgency grows quicker, so it wins more
 *    often; every waiting request's urgency keeps growing, so none starves.
 *    When the link cannot keep up, every request slows down by about the
 *    same factor instead of the slow ones stopping.
 * 3. A request on another ECU than the one the adapter is set to counts
 *    [SWITCH_COST_MS] less waiting: switching costs several adapter
 *    commands (docs/03), so requests on the current ECU are served first
 *    while they are about as urgent.
 * 4. Ties go to the shorter period, then to catalog order.
 *
 * [PollTier] only names the period ranges; it no longer decides the order.
 */
class PollScheduler(requests: List<ObdRequest>, startMs: Long = 0, private val switchCostMs: Long = SWITCH_COST_MS) {

    private class Entry(var request: ObdRequest, val order: Int, var nextDueMs: Long)

    private val entries = requests.distinct().mapIndexed { i, r -> Entry(r, i, startMs) }.toMutableList()
    private var lastHeader: Int? = null

    val requests: List<ObdRequest> get() = entries.map { it.request }

    fun next(nowMs: Long): ObdRequest? {
        var pick: Entry? = null
        var pickUrgency = 0.0
        for (e in entries) {
            if (e.nextDueMs > nowMs) continue
            val period = e.request.periodMs
            val switchCost = if (lastHeader == null || e.request.header == lastHeader) 0 else switchCostMs
            val urgency = (nowMs - e.nextDueMs + period - switchCost).toDouble() / period
            val better = pick == null ||
                urgency > pickUrgency ||
                urgency == pickUrgency && (period < pick.request.periodMs || period == pick.request.periodMs && e.order < pick.order)
            if (better) {
                pick = e
                pickUrgency = urgency
            }
        }
        pick ?: return null
        pick.nextDueMs = nowMs + pick.request.periodMs
        lastHeader = pick.request.header
        return pick.request
    }

    /** Earliest time any request becomes due, or null when there are no requests. */
    fun nextDueMs(): Long? = entries.minOfOrNull { it.nextDueMs }

    /**
     * Starts polling [request] (e.g. the discovery watch list), first due at
     * [nowMs]. Returns false if it is already scheduled.
     */
    fun add(request: ObdRequest, nowMs: Long): Boolean {
        if (entries.any { it.request == request }) return false
        entries += Entry(request, (entries.maxOfOrNull { it.order } ?: -1) + 1, nowMs)
        return true
    }

    /** Stops polling a request, e.g. one the capability probe found dead on this car. */
    fun remove(request: ObdRequest) {
        entries.removeAll { it.request == request }
    }

    /**
     * Swaps [old] for [new] in place (e.g. a new period). Its next turn comes
     * no later than one new period after [nowMs]. Returns false if [old] is
     * not scheduled.
     */
    fun replace(old: ObdRequest, new: ObdRequest, nowMs: Long): Boolean {
        val entry = entries.firstOrNull { it.request == old } ?: return false
        entry.request = new
        entry.nextDueMs = minOf(entry.nextDueMs, nowMs + new.periodMs)
        return true
    }

    companion object {
        /** About what switching ECU costs on the link: two or three adapter commands (docs/03). */
        const val SWITCH_COST_MS = 150L
    }
}
