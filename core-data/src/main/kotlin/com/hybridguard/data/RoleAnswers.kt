package com.hybridguard.data

import com.hybridguard.obd.RawSample

/** How the car answered one request behind a role in the capability probe. */
data class RequestAnswer(val commandKey: String, val request: String, val result: CapabilityResult?) {
    val answered: Boolean get() = result?.answered == true

    /** E.g. "7E0 01 5C: no reply", "700 22 1F5C: refused (NRC 31)". */
    val text: String
        get() = "$request: " + when {
            result == null -> "not asked (the car does not list it)"
            result.answered -> "answered"
            result.status == RawSample.Status.NEGATIVE ->
                "refused" + (result.negativeCode?.let { " (NRC %02X)".format(it) } ?: "")
            result.silent -> "no reply"
            else -> "error (${result.status.name.lowercase()})"
        }
}

/**
 * The requests behind [role], in the profile's order of preference, with what
 * the car answered in the probe. Empty while no probe [report] is known.
 */
fun VehicleCatalog.answersFor(role: Role, report: CapabilityReport?): List<RequestAnswer> {
    if (report == null) return emptyList()
    val ids = roles[role].orEmpty()
    return ids.mapNotNull { id -> commands.firstOrNull { c -> c.signals.any { it.id == id } } }
        .distinctBy { it.key }
        .map { c ->
            val request = "%03X %02X %s".format(c.header, c.service, c.parameterHex.uppercase())
            RequestAnswer(c.key, request, report.results[c.key])
        }
}

/**
 * The [roles] this car does not give: every request behind them was probed or
 * skipped and none answered. Shown instead of an empty value, and kept with the
 * session, so a missing value says why.
 */
fun VehicleCatalog.notGiven(roles: Collection<Role>, report: CapabilityReport?): Map<Role, List<RequestAnswer>> =
    roles.associateWith { answersFor(it, report) }
        .filterValues { answers -> answers.isNotEmpty() && answers.none { it.answered } }
