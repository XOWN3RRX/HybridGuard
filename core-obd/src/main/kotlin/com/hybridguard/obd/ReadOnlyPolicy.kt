package com.hybridguard.obd

/** Thrown when anything tries to send an OBD service outside the read-only allow-list. */
class ForbiddenServiceException(val service: Int) :
    IllegalArgumentException("OBD service 0x%02X is not allowed: HybridGuard is read-only".format(service))

/**
 * The read-only allow-list (CLAUDE.md, docs/03 "Safety").
 *
 * Only these services may reach the car through [ReadGateway]:
 * `01` current data, `09` vehicle info, `21` / `22` read data by id,
 * `3E` tester present. Everything else throws [ForbiddenServiceException].
 *
 * Do not widen this list. Future control features go through
 * [CommandGateway], which has its own rules (docs/07 section 2).
 */
object ReadOnlyPolicy {
    val ALLOWED_SERVICES: Set<Int> = setOf(0x01, 0x09, 0x21, 0x22, 0x3E)

    fun isAllowed(service: Int): Boolean = service in ALLOWED_SERVICES

    fun requireAllowed(service: Int) {
        if (!isAllowed(service)) throw ForbiddenServiceException(service)
    }

    /**
     * Checks an OBD payload as hex text, the form it is written to the adapter
     * (for example `"221F9A"` or `"01 0C"`). The ELM327 driver calls this on
     * every non-`AT`/`ST` line right before writing it, as a last line of defence.
     */
    fun requireAllowedPayload(payloadHex: String) {
        val compact = payloadHex.filterNot { it.isWhitespace() }
        require(compact.length >= 2) { "Empty OBD payload" }
        val service = compact.substring(0, 2).toIntOrNull(16)
            ?: throw IllegalArgumentException("Not a hex OBD payload: '$payloadHex'")
        requireAllowed(service)
    }
}
