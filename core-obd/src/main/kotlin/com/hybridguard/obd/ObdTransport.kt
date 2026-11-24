package com.hybridguard.obd

import kotlinx.coroutines.flow.Flow

/**
 * A raw byte pipe to an ELM327-compatible adapter.
 *
 * Implementations: Bluetooth SPP (in :app), replay of a recorded log, and a
 * simulated car. Nothing above this layer knows which one is in use.
 */
interface ObdTransport {
    suspend fun open()
    suspend fun write(bytes: ByteArray)
    fun incoming(): Flow<ByteArray>
    suspend fun close()
}
