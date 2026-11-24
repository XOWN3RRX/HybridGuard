package com.hybridguard.obd

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the poll schedule against a [ReadGateway] and hands every sample to [onSample].
 *
 * **Priority lane:** [runPriority] queues a job that runs between two
 * requests while polling is paused, then polling resumes. Phase 1 uses it
 * for exclusive read work (capability probe, discovery scan). Future
 * [CommandGateway] actions will use the same lane (docs/07 section 2).
 */
class PollingLoop(
    private val gateway: ReadGateway,
    private val scheduler: PollScheduler,
    private val clock: () -> Long,
    private val onSample: suspend (RawSample) -> Unit,
) {
    private val priority = Channel<PriorityJob<*>>(Channel.UNLIMITED)

    /**
     * Wakes an idle loop. Jobs are only ever taken with tryReceive: receiving
     * a job inside withTimeoutOrNull can lose it when the timeout fires at the
     * same moment, while losing a doorbell ring is harmless.
     */
    private val doorbell = Channel<Unit>(Channel.CONFLATED)

    /** Polls until cancelled. Transport failures propagate to the caller (which reconnects). */
    suspend fun run(): Nothing {
        while (true) {
            val queued = priority.tryReceive().getOrNull()
            if (queued != null) {
                queued.execute()
                continue
            }

            val now = clock()
            val request = scheduler.next(now)
            if (request != null) {
                onSample(gateway.read(request))
                continue
            }

            // Idle until the next request is due, but wake at once for priority work.
            val waitMs = scheduler.nextDueMs()?.let { (it - now).coerceAtLeast(1) } ?: Long.MAX_VALUE
            withTimeoutOrNull(waitMs) { doorbell.receive() }
        }
    }

    /** Runs [block] exclusively (polling paused) and returns its result. Its exceptions are rethrown here, not in [run]. */
    suspend fun <T> runPriority(block: suspend () -> T): T {
        val job = PriorityJob(block)
        priority.send(job)
        doorbell.trySend(Unit)
        return job.result.await()
    }

    private class PriorityJob<T>(private val block: suspend () -> T) {
        val result = CompletableDeferred<T>()

        suspend fun execute() {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
                currentCoroutineContext().ensureActive() // if the loop itself was cancelled, stop
            }
        }
    }
}
