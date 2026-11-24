package com.hybridguard.obd

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Fake adapter: [respond] maps each written line to a reply (lines separated by
 * '\n'), or null to stay silent and force a timeout. Replies end with the prompt.
 */
class ScriptedElmTransport(private val respond: (String) -> String?) : ObdTransport {
    val written = mutableListOf<String>()
    private val out = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun open() = Unit

    override suspend fun write(bytes: ByteArray) {
        val line = String(bytes, Charsets.US_ASCII).trimEnd('\r')
        written += line
        val reply = respond(line) ?: return
        out.send((reply.replace("\n", "\r") + "\r\r>").toByteArray(Charsets.US_ASCII))
    }

    override fun incoming(): Flow<ByteArray> = out.receiveAsFlow()

    override suspend fun close() {
        out.close()
    }

    companion object {
        /** Answers like an STN-based adapter: version banners, OK to every other AT/ST command. */
        fun adapterReplies(line: String): String? = when (line) {
            "ATZ" -> "ELM327 v2.3"
            "ATI" -> "ELM327 v2.3"
            "STI" -> "STN2255 v5.10.3"
            "STDI" -> "Test STN adapter"
            "ATRV" -> "12.6V"
            else -> if (line.startsWith("AT") || line.startsWith("ST")) "OK" else null
        }
    }
}
