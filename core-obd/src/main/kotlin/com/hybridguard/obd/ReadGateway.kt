package com.hybridguard.obd

/**
 * The only path from the app to the car in Phases 1 to 3.
 *
 * Implementations must check every payload with [ReadOnlyPolicy] before it
 * is written to the transport ([ObdRequest] already refuses forbidden
 * services at construction; the second check guards the byte level).
 */
interface ReadGateway {
    /** Sends [request] once and returns the raw reply. Never throws for car-side errors: they become a [RawSample.Status]. */
    suspend fun read(request: ObdRequest): RawSample
}
