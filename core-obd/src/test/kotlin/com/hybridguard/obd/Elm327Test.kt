package com.hybridguard.obd

import com.hybridguard.obd.RawSample.Status
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class Elm327Test {

    private fun TestScope.elm(transport: ObdTransport) = Elm327(transport, backgroundScope, clock = { testScheduler.currentTime })

    private val carReplies = mapOf(
        "221F9A" to "7DA05621F9A1234",
        "2101" to "7E8037F2111",
        "010C" to "7E804410C1AF8",
    )

    private fun fakeCar(overrides: Map<String, String?> = emptyMap()) = ScriptedElmTransport { line ->
        if (line in overrides) overrides[line] else carReplies[line] ?: ScriptedElmTransport.adapterReplies(line)
    }

    @Test
    fun initSendsTheDocumentedSequenceAndIdentifiesTheAdapter() = runTest {
        val transport = fakeCar()
        val report = elm(transport).open()

        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATSP6", "ATI", "STI", "STDI", "ATRV"),
            transport.written,
        )
        assertEquals(transport.written, report.steps.map { it.command })
        assertEquals(AdapterInfo("ELM327 v2.3", "STN2255 v5.10.3", "Test STN adapter", "12.6V"), report.adapter)
    }

    @Test
    fun plainElmWithoutStnCommandsStillInitialises() = runTest {
        val report = elm(fakeCar(mapOf("STI" to "?", "STDI" to "?"))).open()
        assertNull(report.adapter.stn)
        assertNull(report.adapter.stnDevice)
        assertEquals("ELM327 v2.3", report.adapter.elm)
    }

    @Test
    fun echoAfterResetDoesNotBreakInit() = runTest {
        // Right after ATZ echo is still on, so the ATE0 reply contains the echo.
        elm(fakeCar(mapOf("ATE0" to "ATE0\nOK"))).open()
    }

    @Test
    fun initFailsWhenProtocolIsRejected() = runTest {
        val e = assertFailsWith<AdapterInitException> { elm(fakeCar(mapOf("ATSP6" to "?"))).open() }
        assertEquals("ATSP6", e.command)
    }

    @Test
    fun initFailsWhenAdapterIsSilent() = runTest {
        val e = assertFailsWith<AdapterInitException> { elm(ScriptedElmTransport { null }).open() }
        assertEquals("ATE0", e.command) // ATZ silence is tolerated, ATE0 is not
        assertEquals(true, e.steps.last().timedOut)
    }

    @Test
    fun headerIsOnlySwitchedWhenTheEcuChanges() = runTest {
        val transport = fakeCar()
        val elm = elm(transport)
        elm.open()
        transport.written.clear()

        val samples = listOf(
            elm.read(ObdRequest.fromHex(0x7D2, "221F9A", 1000)),
            elm.read(ObdRequest.fromHex(0x7D2, "221F9A", 1000)),
            elm.read(ObdRequest.fromHex(0x7E0, "2101", 1000)),
            elm.read(ObdRequest.fromHex(ObdRequest.FUNCTIONAL_HEADER, "010C", 250)),
            elm.read(ObdRequest.fromHex(0x7D2, "221F9A", 1000)),
        )

        assertEquals(
            listOf(
                "ATSH7D2", "ATCRA7DA", "ATFCSH7D2", "ATFCSD300000", "ATFCSM1", "221F9A",
                "221F9A",
                "ATSH7E0", "ATCRA7E8", "ATFCSH7E0", "2101",
                "ATSH7DF", "ATCRA", "ATFCSM0", "010C",
                "ATSH7D2", "ATCRA7DA", "ATFCSH7D2", "ATFCSD300000", "ATFCSM1", "221F9A",
            ),
            transport.written,
        )
        assertEquals(listOf(Status.OK, Status.OK, Status.NEGATIVE, Status.OK, Status.OK), samples.map { it.status })
        assertEquals("7DA05621F9A1234", samples[0].response) // raw text kept
        assertEquals("221F9A", samples[0].request)
        assertEquals(0x7D2, samples[0].header)
    }

    @Test
    fun broadcastWithReceiveFilterAndResponseCount() = runTest {
        val transport = fakeCar(mapOf("010D1" to "7E803410D2A"))
        val elm = elm(transport)
        elm.open()
        elm.read(ObdRequest.fromHex(0x7D2, "221F9A", 1000)) // physical first: custom flow control active
        transport.written.clear()

        val routed = ObdRequest.fromHex(ObdRequest.FUNCTIONAL_HEADER, "010D", 250, receiveHeader = 0x7E8, expectedResponses = 1)
        val sample = elm.read(routed)
        elm.read(routed)

        // One single-frame reply expected: flow control is left as it is (docs/03).
        assertEquals(listOf("ATSH7DF", "ATCRA7E8", "010D1", "010D1"), transport.written)
        assertEquals("010D", sample.request, "the response count is not part of the logged request")
        assertEquals(ObdRequest.FUNCTIONAL_HEADER, sample.header)
        assertEquals(Status.OK, sample.status)

        // Back to the physical ECU: header and filter only, flow control is still set up for it.
        transport.written.clear()
        elm.read(ObdRequest.fromHex(0x7D2, "221F9A", 1000))
        assertEquals(listOf("ATSH7D2", "ATCRA7DA", "221F9A"), transport.written)

        // A broadcast without a response count may get a multi-frame reply: automatic flow control.
        transport.written.clear()
        elm.read(ObdRequest.fromHex(ObdRequest.FUNCTIONAL_HEADER, "010C", 250))
        assertEquals(listOf("ATSH7DF", "ATCRA", "ATFCSM0", "010C"), transport.written)
    }

    @Test
    fun silentEcuGivesTimeoutAndTheNextRequestStillWorks() = runTest {
        val transport = fakeCar(mapOf("221F9A" to null))
        val elm = elm(transport)
        elm.open()

        val timedOut = elm.read(ObdRequest.fromHex(0x7D2, "221F9A", 1000))
        assertEquals(Status.TIMEOUT, timedOut.status)
        assertEquals("", timedOut.response)

        assertEquals(Status.NEGATIVE, elm.read(ObdRequest.fromHex(0x7E0, "2101", 1000)).status)
    }

    @Test
    fun noDataIsLoggedVerbatim() = runTest {
        val elm = elm(fakeCar(mapOf("221F9A" to "NO DATA")))
        elm.open()
        val sample = elm.read(ObdRequest.fromHex(0x7D2, "221F9A", 1000))
        assertEquals(Status.NO_DATA, sample.status)
        assertEquals("NO DATA", sample.response)
    }

    @Test
    fun rejectedHeaderSwitchNeverSendsTheRequest() = runTest {
        val transport = fakeCar(mapOf("ATSH7D2" to "?"))
        val elm = elm(transport)
        elm.open()
        transport.written.clear()

        val sample = elm.read(ObdRequest.fromHex(0x7D2, "221F9A", 1000))
        assertEquals(Status.ADAPTER_ERROR, sample.status)
        assertEquals(listOf("ATSH7D2"), transport.written)
        assertFalse("221F9A" in transport.written)
    }

    @Test
    fun samplesAreStampedWithTheSessionClock() = runTest {
        val elm = elm(fakeCar())
        elm.open()
        testScheduler.advanceTimeBy(5_000)
        val sample = elm.read(ObdRequest.fromHex(0x7D2, "221F9A", 1000))
        assertEquals(5_000, sample.tMs)
    }
}
