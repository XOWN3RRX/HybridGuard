package com.hybridguard.data

import com.hybridguard.obd.ObdRequest

/** Contents of a profile's `profile.json` (docs/07 section 1). */
data class ProfileInfo(
    val id: String,
    val name: String,
    /** Bumped when the profile's data changes; recorded in log metadata. */
    val version: Int,
    val years: IntRange? = null,
    /** VIN prefixes that select this profile automatically (longest match wins). */
    val vinPrefixes: List<String> = emptyList(),
    /**
     * Model year to assume for a VIN prefix when the VIN itself does not say
     * (European VINs leave position 10 as `0`). Keyed by prefix.
     */
    val vinModelYears: Map<String, Int> = emptyMap(),
    /** Whether the user may pick this profile by hand. */
    val manualSelectable: Boolean = true,
    val protocol: String? = null,
    val includes: List<String> = emptyList(),
    /** ECU name -> request header, e.g. "hybrid" -> 0x7D2. */
    val ecus: Map<String, Int> = emptyMap(),
)

/** A profile with its includes, overrides and role mapping applied, for every model year. */
class ResolvedProfile(
    val info: ProfileInfo,
    val commands: List<SignalCommand>,
    val roles: Map<Role, List<String>>,
    /** Non-fatal oddities found while loading (shown in the debug screen, written to the log). */
    val warnings: List<String>,
) {
    fun forModelYear(modelYear: Int?): VehicleCatalog =
        VehicleCatalog(info, modelYear, commands.filter { it.appliesTo(modelYear) }, roles)
}

/** What can be asked of one particular car: a profile narrowed to its model year. */
class VehicleCatalog(
    val profile: ProfileInfo,
    val modelYear: Int?,
    val commands: List<SignalCommand>,
    val roles: Map<Role, List<String>>,
) {
    private val byKey: Map<String, SignalCommand> = commands.groupBy { it.key }.mapValues { it.value.first() }
    private val signalsById: Map<String, Signal> = commands.flatMap { it.signals }.associateBy { it.id }

    fun command(key: String): SignalCommand? = byKey[key]

    /**
     * The command a sent request belongs to. A standard request that went to
     * the broadcast address (because the ECU ignored its own address) maps
     * back to the catalog command with the same payload.
     */
    fun commandFor(header: Int, payloadHex: String): SignalCommand? =
        byKey[commandKey(header, payloadHex)]
            ?: if (header == ObdRequest.FUNCTIONAL_HEADER) byPayload[payloadHex.uppercase()] else null

    private val byPayload: Map<String, SignalCommand> =
        commands.filter { it.service == 0x01 || it.service == 0x09 }.groupBy { it.payloadHex }.mapValues { it.value.first() }

    fun signal(id: String): Signal? = signalsById[id]

    val roleResolver: RoleResolver get() = RoleResolver(roles)

    /** Keys of the commands that carry a signal some main-dashboard role uses: what the dashboards need. */
    val roleCommandKeys: Set<String>
        get() = commandKeysFor { !it.advanced }

    /**
     * Keys of the commands only the Advanced screen needs (inverter, pumps):
     * polled while that screen is open, on top of [roleCommandKeys].
     */
    val advancedCommandKeys: Set<String>
        get() = commandKeysFor { it.advanced } - roleCommandKeys

    /**
     * What to poll for the roles [include] picks, once the probe is known:
     * each role takes only its first signal whose command [answered]; its
     * fallbacks are not read while the first choice works (fewer requests,
     * quicker updates). A role none of whose signals answered keeps them
     * all, and roles marked [Role.allListed] always use every signal.
     */
    fun pollKeys(include: (Role) -> Boolean, answered: (commandKey: String) -> Boolean): Set<String> {
        val keyBySignal = commands.flatMap { c -> c.signals.map { it.id to c.key } }.toMap()
        val ids = roles.filterKeys(include).flatMap { (role, signals) ->
            if (role.allListed) {
                signals
            } else {
                signals.firstOrNull { id -> keyBySignal[id]?.let(answered) == true }?.let(::listOf) ?: signals
            }
        }
        return ids.mapNotNull { keyBySignal[it] }.toSet()
    }

    private fun commandKeysFor(include: (Role) -> Boolean): Set<String> {
        val ids = roles.filterKeys(include).values.flatten().toSet()
        return commands.filter { c -> c.signals.any { it.id in ids } }.map { it.key }.toSet()
    }

    /** The command carrying [role]'s preferred signal, with that signal's id; null if the role has none in this catalog. */
    fun commandForRole(role: Role): Pair<SignalCommand, String>? =
        roles[role].orEmpty().firstNotNullOfOrNull { id ->
            commands.firstOrNull { c -> c.unsupportedReason == null && c.signals.any { it.id == id } }?.let { it to id }
        }

    /** Commands the adapter code cannot send yet, with the reason. */
    val unsupported: List<Pair<SignalCommand, String>>
        get() = commands.mapNotNull { c -> c.unsupportedReason?.let { c to it } }

    /**
     * Requests to poll. With a capability report for this profile version,
     * requests the car rejected as unsupported are left out.
     */
    fun requests(capabilities: CapabilityReport? = null): List<ObdRequest> = pollable(capabilities).mapNotNull { it.toRequest() }

    /**
     * Commands that can be polled: one per request, supported by the adapter
     * code and, with a capability report for this profile version, not
     * rejected by the car as unsupported.
     */
    fun pollable(capabilities: CapabilityReport?): List<SignalCommand> {
        val report = capabilities?.takeIf { it.profileId == profile.id && it.profileVersion == profile.version }
        return byKey.values.filter { it.unsupportedReason == null && report?.isDead(it.key) != true }
    }
}
