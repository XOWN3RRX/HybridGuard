package com.hybridguard.obd

/**
 * Future control path (door lock automations and similar), docs/07 section 2.
 *
 * **Interface only. No implementation may ship in Phases 1 to 3.**
 *
 * Commands are never free-form bytes: callers name an action id from the
 * vehicle profile's `actions.json`, and only actions marked verified for the
 * matched profile may be executed. Running a command takes the scheduler's
 * priority lane: polling pauses, the command sequence runs, polling resumes.
 */
interface CommandGateway {
    suspend fun execute(actionId: String): CommandResult
}

/** Outcome of a [CommandGateway.execute] call, with the raw exchange for the log. */
data class CommandResult(
    val actionId: String,
    val success: Boolean,
    val exchanges: List<RawSample>,
)
