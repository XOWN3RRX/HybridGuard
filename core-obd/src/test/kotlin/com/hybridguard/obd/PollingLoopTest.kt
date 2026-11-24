package com.hybridguard.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PollingLoopTest {

    /** Gateway where each read takes 100 ms of virtual time. */
    private class SlowGateway(private val scope: TestScope) : ReadGateway {
        val readTimes = mutableListOf<Long>()
        override suspend fun read(request: ObdRequest): RawSample {
            delay(100)
            val t = scope.testScheduler.currentTime
            readTimes += t
            return RawSample(t, request.header, request.payloadHex, "7DA05621F9A1234", RawSample.Status.OK)
        }
    }

    private fun TestScope.loop(gateway: ReadGateway, samples: MutableList<RawSample> = mutableListOf()) = PollingLoop(
        gateway,
        PollScheduler(listOf(ObdRequest.fromHex(0x7D2, "221F9A", 250))),
        clock = { testScheduler.currentTime },
        onSample = { samples += it },
    )

    @Test
    fun pollsAtTheRequestedPeriod() = runTest {
        val gateway = SlowGateway(this)
        val samples = mutableListOf<RawSample>()
        backgroundScope.launch { loop(gateway, samples).run() }

        advanceTimeBy(2_001)
        // Sent at 0, 250, 500, ...: each read finishes 100 ms after it is sent.
        assertEquals(listOf(100L, 350, 600, 850, 1100, 1350, 1600, 1850), gateway.readTimes)
        assertEquals(gateway.readTimes.size, samples.size)
    }

    @Test
    fun priorityJobPausesPollingThenPollingResumes() = runTest {
        val gateway = SlowGateway(this)
        val loop = loop(gateway)
        backgroundScope.launch { loop.run() }
        advanceTimeBy(1_000)

        val result = async { loop.runPriority { delay(2_000); 42 } }
        advanceTimeBy(150) // let any in-flight read finish; the job is now running
        val readsAtStart = gateway.readTimes.size
        advanceTimeBy(1_500)
        assertEquals(readsAtStart, gateway.readTimes.size, "no reads while the priority job runs")

        assertEquals(42, result.await())
        advanceTimeBy(1_000)
        assertTrue(gateway.readTimes.size > readsAtStart, "polling resumed")
    }

    @Test
    fun failingPriorityJobFailsOnlyItsCaller() = runTest {
        val gateway = SlowGateway(this)
        val loop = loop(gateway)
        backgroundScope.launch { loop.run() }
        // Stop exactly when the idle wait ends (next read due at 500 ms): the job arrives
        // in the same instant as the timeout. Regression test for a lost-job race.
        advanceTimeBy(500)

        assertFailsWith<IllegalStateException> { loop.runPriority { error("probe failed") } }
        val before = gateway.readTimes.size
        advanceTimeBy(1_000)
        assertTrue(gateway.readTimes.size > before, "loop kept polling")
    }
}
