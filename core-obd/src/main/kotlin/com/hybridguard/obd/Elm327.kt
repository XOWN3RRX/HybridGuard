package com.hybridguard.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class Elm327Config(
    /** `ATZ` reset; docs/03 says wait up to 2 s. */
    val resetTimeoutMs: Long = 2_000,
    val commandTimeoutMs: Long = 1_000,
    val readTimeoutMs: Long = 2_000,
)

/** One init command and the adapter's reply, for the session log. */
data class InitStep(val command: String, val reply: String, val timedOut: Boolean)

/** Adapter identification; null where the adapter answered `?` (e.g. `STI` on a plain ELM327). */
data class AdapterInfo(val elm: String?, val stn: String?, val stnDevice: String?, val voltage: String?)

data class InitReport(val steps: List<InitStep>, val adapter: AdapterInfo)

class AdapterInitException(val command: String, val reply: String, val steps: List<InitStep>) :
    Exception("Adapter rejected '$command': '${reply.ifEmpty { "<no reply>" }}'")

/**
 * ELM327 / STN driver over any [ObdTransport] (docs/03).
 *
 * The only things it can send are the fixed `AT` setup commands in this file
 * and [ObdRequest] payloads, which pass [ReadOnlyPolicy] again right before
 * they are written. There is deliberately no public "send raw line" method.
 *
 * @param scope where the transport reader runs; cancelled work stops with it.
 * @param clock milliseconds since session start, stamped on every [RawSample].
 */
class Elm327(
    private val transport: ObdTransport,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val config: Elm327Config = Elm327Config(),
) : ReadGateway {

    private val rx = Channel<ByteArray>(Channel.UNLIMITED)
    private val lock = Mutex()
    private var reader: Job? = null

    /** Transmit header the adapter is currently set to, or null if unknown. */
    private var currentHeader: Int? = null

    /** Receive filter currently set: a CAN id, [NO_FILTER], or null if unknown. */
    private var currentFilter: Int? = null

    /** Whether ATFCSD/ATFCSM1 (custom flow control for physical requests) is active. */
    private var customFlowControl = false

    /** Flow-control header set with ATFCSH (kept while mode 0 is active), or null if unknown. */
    private var flowControlHeader: Int? = null

    /** Opens the transport and runs the init sequence. Throws [AdapterInitException] if a setup command is rejected. */
    suspend fun open(): InitReport {
        transport.open()
        reader = scope.launch {
            try {
                transport.incoming().collect { rx.send(it) }
            } catch (e: IOException) {
                // A blocking socket read cannot be cancelled: closing the socket (Stop,
                // close()) makes it fail with "socket closed". That is not a dropout.
                if (isActive) throw e
            }
        }
        return lock.withLock { initialize() }
    }

    suspend fun close() {
        reader?.cancel()
        reader = null
        currentHeader = null
        currentFilter = null
        customFlowControl = false
        flowControlHeader = null
        transport.close()
    }

    override suspend fun read(request: ObdRequest): RawSample = lock.withLock {
        val payload = request.payloadHex
        val wire = request.wirePayload
        ReadOnlyPolicy.requireAllowedPayload(wire)

        selectEcu(request)?.let { failure ->
            return@withLock RawSample(clock(), request.header, payload, failure, RawSample.Status.ADAPTER_ERROR)
        }

        val t = clock()
        val reply = exchange(wire, config.readTimeoutMs)
        val status = if (reply.timedOut) RawSample.Status.TIMEOUT else ResponseParser.parse(payload, reply.text).status
        RawSample(t, request.header, payload, reply.text, status)
    }

    private suspend fun initialize(): InitReport {
        val steps = mutableListOf<InitStep>()

        suspend fun step(command: String, timeoutMs: Long, mustBeOk: Boolean): Reply {
            val reply = exchange(command, timeoutMs)
            steps += InitStep(command, reply.text, reply.timedOut)
            if (mustBeOk && !reply.isOk) throw AdapterInitException(command, reply.text, steps.toList())
            return reply
        }

        suspend fun identify(command: String): String? {
            val reply = step(command, config.commandTimeoutMs, mustBeOk = false)
            return reply.text.takeUnless { reply.timedOut || it.isEmpty() || it == "?" }
        }

        step("ATZ", config.resetTimeoutMs, mustBeOk = false) // reply is the version banner
        for (command in SETUP_COMMANDS) step(command, config.commandTimeoutMs, mustBeOk = true)

        val info = AdapterInfo(
            elm = identify("ATI"),
            stn = identify("STI"),
            stnDevice = identify("STDI"),
            voltage = identify("ATRV"),
        )
        currentHeader = null
        currentFilter = null
        customFlowControl = false
        flowControlHeader = null
        return InitReport(steps, info)
    }

    /**
     * Sets transmit header, receive filter and flow control, sending only what
     * changed. Returns a failure description, or null on success.
     *
     * Flow control only matters for multi-frame replies. A broadcast request
     * known to get one single-frame reply ([ObdRequest.expectedResponses] = 1)
     * leaves it as it is, so going between it and a physical ECU costs two
     * commands (`ATSH`, `ATCRA`) instead of five.
     */
    private suspend fun selectEcu(request: ObdRequest): String? {
        val header = request.header
        val functional = header == ObdRequest.FUNCTIONAL_HEADER
        val filter = request.receiveHeader ?: if (functional) NO_FILTER else header + 8
        val flowControlAsIs = functional && request.expectedResponses == 1

        val commands = buildList {
            if (header != currentHeader) add("ATSH%03X".format(header))
            if (filter != currentFilter) add(if (filter == NO_FILTER) "ATCRA" else "ATCRA%03X".format(filter))
            if (functional) {
                if (customFlowControl && !flowControlAsIs) add("ATFCSM0") // automatic flow control for replies to a broadcast
            } else {
                if (header != flowControlHeader) add("ATFCSH%03X".format(header))
                if (!customFlowControl) {
                    add("ATFCSD300000")
                    add("ATFCSM1")
                }
            }
        }
        for (command in commands) {
            val reply = exchange(command, config.commandTimeoutMs)
            if (!reply.isOk) {
                currentHeader = null // unknown state: redo everything next time
                currentFilter = null
                flowControlHeader = null
                return "$command: ${if (reply.timedOut) "TIMEOUT" else reply.text}"
            }
            when {
                command == "ATFCSM1" -> customFlowControl = true
                command == "ATFCSM0" -> customFlowControl = false
                command.startsWith("ATFCSH") -> flowControlHeader = header
            }
        }
        currentHeader = header
        currentFilter = filter
        return null
    }

    private suspend fun exchange(line: String, timeoutMs: Long): Reply {
        while (rx.tryReceive().isSuccess) {
            // drop stale bytes left over from a previous timeout
        }
        transport.write("$line\r".toByteArray(Charsets.US_ASCII))
        return readUntilPrompt(timeoutMs)
    }

    private suspend fun readUntilPrompt(timeoutMs: Long): Reply {
        val text = StringBuilder()
        val completed = withTimeoutOrNull(timeoutMs) {
            var prompt = false
            while (!prompt) {
                for (b in rx.receive()) {
                    val c = (b.toInt() and 0xFF).toChar()
                    if (c == '>') {
                        prompt = true
                        break
                    }
                    if (c != '\u0000') text.append(c)
                }
            }
            true
        }
        return Reply(normalize(text), timedOut = completed == null)
    }

    private class Reply(val text: String, val timedOut: Boolean) {
        val isOk: Boolean get() = !timedOut && text.lineSequence().any { it.trim() == "OK" }
    }

    companion object {
        private const val NO_FILTER = -1
        private val VOLTS = Regex("""(\d{1,2}(?:\.\d{1,2})?)\s*V""")

        /**
         * The adapter's supply voltage (the car's 12 V system) from an `ATRV`
         * reply such as `12.4V`, or null.
         */
        fun parseVolts(reply: String): Double? = VOLTS.find(reply)?.groupValues?.get(1)?.toDoubleOrNull()

        /**
         * A quick look at the car without talking to it: opens [transport],
         * sends only `ATRV` (answered by the adapter itself, nothing goes on
         * the car's bus) and closes again. Returns the 12 V system voltage,
         * which says whether the car is READY (its DC-DC converter charges
         * the 12 V battery, about 13.5 to 14.5 V) or parked (about 12.0 to 12.8 V).
         * Throws if the adapter cannot be reached (asleep, out of range).
         */
        suspend fun quickVoltage(transport: ObdTransport, scope: CoroutineScope, timeoutMs: Long = 2_500): Double? {
            transport.open()
            val rx = Channel<ByteArray>(Channel.UNLIMITED)
            val reader = scope.launch {
                try {
                    transport.incoming().collect { rx.send(it) }
                } catch (e: IOException) {
                    if (isActive) rx.close(e)
                }
            }
            try {
                // No bare CR first: an ELM327 repeats its last command on an empty line,
                // which could be an OBD request. ATRV alone never reaches the car.
                transport.write("ATRV\r".toByteArray(Charsets.US_ASCII))
                val text = withTimeoutOrNull(timeoutMs) { drainUntilPrompt(rx) } ?: return null
                return parseVolts(text)
            } finally {
                reader.cancel()
                transport.close()
            }
        }

        private suspend fun drainUntilPrompt(rx: Channel<ByteArray>): String {
            val text = StringBuilder()
            while (true) {
                for (b in rx.receive()) {
                    val c = (b.toInt() and 0xFF).toChar()
                    if (c == '>') return text.toString()
                    text.append(c)
                }
            }
        }

        /** docs/03 init sequence after ATZ; each must answer OK. */
        private val SETUP_COMMANDS = listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATSP6")

        /** CR/LF to '\n', trimmed lines, blank lines dropped: lossless for parsing, stable for logs. */
        fun normalize(raw: CharSequence): String =
            raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
