package com.hybridguard.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where profile files come from. Paths are relative to the vehicles folder, e.g. `"sae-j1979/signals.json"`. */
fun interface ResourceSource {
    fun read(path: String): String?
}

/** Profiles packaged as JVM resources under `vehicles/` (works on Android: they are merged into the APK). */
class ClasspathSource(
    private val root: String = "vehicles",
    private val classLoader: ClassLoader = ClasspathSource::class.java.classLoader,
) : ResourceSource {
    override fun read(path: String): String? =
        classLoader.getResourceAsStream("$root/$path")?.use { it.readBytes().toString(Charsets.UTF_8) }
}

/** In-memory files, for tests. */
class MapSource(private val files: Map<String, String>) : ResourceSource {
    override fun read(path: String): String? = files[path]
}

data class ProfileIndex(val profileIds: List<String>, val fallbackId: String)

/**
 * Loads vehicle profiles by id (docs/07 section 1). Nothing here knows any
 * particular car: everything comes from the profile folders.
 *
 * Per profile folder: `profile.json` (required), `signals.json` (OBDb
 * signalset), `overrides.json` (local corrections) and `derived.json`
 * (role mapping), the last three optional. Includes are loaded first, so a
 * profile's own overrides and role mapping win.
 */
class ProfileLoader(private val source: ResourceSource = ClasspathSource()) {

    fun index(): ProfileIndex {
        val root = SignalSetParser.parseJson(readRequired("index.json"), "index.json")
        val ids = root["profiles"]?.stringList() ?: throw ProfileFormatException("index.json: missing \"profiles\"")
        val fallback = root.string("fallback") ?: ids.firstOrNull()
            ?: throw ProfileFormatException("index.json: no profiles")
        if (fallback !in ids) throw ProfileFormatException("index.json: fallback \"$fallback\" is not listed")
        return ProfileIndex(ids, fallback)
    }

    fun info(id: String): ProfileInfo {
        val path = "$id/profile.json"
        val o = SignalSetParser.parseJson(readRequired(path), path)
        val declared = o.string("id")
        if (declared != id) throw ProfileFormatException("$path: id is \"$declared\", folder is \"$id\"")
        val match = o["match"] as? JsonObject
        val years = (o["years"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }
        // Each entry is a plain prefix, or { "prefix": "...", "modelYear": 2019 } for VINs that do not encode the year.
        val vinEntries = (match?.get("vinPrefixes") as? JsonArray).orEmpty().map { e ->
            if (e is JsonObject) {
                val prefix = e.string("prefix") ?: throw ProfileFormatException("$path: vinPrefixes entry without \"prefix\"")
                prefix.uppercase() to e.int("modelYear")
            } else {
                e.jsonPrimitive.content.uppercase() to null
            }
        }
        return ProfileInfo(
            id = id,
            name = o.string("name") ?: id,
            version = o.int("version") ?: throw ProfileFormatException("$path: missing \"version\""),
            years = years?.takeIf { it.size == 2 }?.let { it[0]..it[1] },
            vinPrefixes = vinEntries.map { it.first },
            vinModelYears = vinEntries.mapNotNull { (prefix, year) -> year?.let { prefix to it } }.toMap(),
            manualSelectable = match?.bool("manual") ?: true,
            protocol = (o["bus"] as? JsonObject)?.string("protocol"),
            includes = o["includes"]?.stringList().orEmpty(),
            ecus = (o["ecus"] as? JsonObject)?.mapValues { (name, v) ->
                v.jsonPrimitive.content.toIntOrNull(16) ?: throw ProfileFormatException("$path: bad header for ecu \"$name\"")
            }.orEmpty(),
        )
    }

    fun load(id: String): ResolvedProfile {
        val info = info(id)
        val chain = includeChain(id, mutableListOf(), mutableSetOf())

        // 1. Signalsets of the whole chain, includes first.
        val commands = mutableListOf<JsonObject>()
        for (member in chain) {
            val path = "$member/signals.json"
            val text = source.read(path) ?: continue
            SignalSetParser.commandsOf(SignalSetParser.parseJson(text, path), path).mapTo(commands) { it.jsonObject }
        }

        // 2. Local corrections, in chain order.
        for (member in chain) {
            val path = "$member/overrides.json"
            val text = source.read(path) ?: continue
            Overrides.apply(commands, SignalSetParser.parseJson(text, path), path)
        }

        val parsed = SignalSetParser.parseCommands(JsonArray(commands), "profile $id")

        // 3. Role mapping, later chain members override earlier ones per role.
        val roles = mutableMapOf<Role, List<String>>()
        for (member in chain) {
            val path = "$member/derived.json"
            val text = source.read(path) ?: continue
            for ((key, value) in SignalSetParser.parseJson(text, path)) {
                val role = Role.fromKey(key) ?: throw ProfileFormatException("$path: unknown role \"$key\"")
                roles[role] = value.stringList()
            }
        }

        val knownIds = parsed.flatMap { c -> c.signals.map { it.id } }.toSet()
        for ((role, ids) in roles) {
            val missing = ids.filterNot { it in knownIds }
            if (missing.isNotEmpty()) {
                throw ProfileFormatException("profile $id: role \"${role.key}\" names unknown signals $missing")
            }
        }

        return ResolvedProfile(info, parsed, roles, warnings(parsed))
    }

    /**
     * The profile's discovery scan plan (`discovery.json`, docs/05 Phase 1
     * task 7): its own, else the nearest include's. Null when there is none.
     */
    fun discoveryPlan(id: String): DiscoveryPlan? {
        for (member in includeChain(id, mutableListOf(), mutableSetOf()).asReversed()) {
            val path = "$member/discovery.json"
            val text = source.read(path) ?: continue
            return DiscoveryPlan.parse(text, path)
        }
        return null
    }

    /** The profile's warm-up advice thresholds (`warmup.json`, docs/02 C.6), own or nearest include's; null if none. */
    fun warmUpRules(id: String): WarmUpRules? {
        for (member in includeChain(id, mutableListOf(), mutableSetOf()).asReversed()) {
            val path = "$member/warmup.json"
            val text = source.read(path) ?: continue
            val o = SignalSetParser.parseJson(text, path)
            fun need(key: String) = o.number(key) ?: throw ProfileFormatException("$path: missing \"$key\"")
            val r = WarmUpRules(
                oilWarmFrom = need("oilWarmFrom"),
                oilOkFrom = need("oilOkFrom"),
                coolantWarmFrom = need("coolantWarmFrom"),
                coolantOkFrom = need("coolantOkFrom"),
                coldMaxAcceleratorPct = need("coldMaxAcceleratorPct"),
                coldMaxRpm = need("coldMaxRpm"),
            )
            if (r.oilOkFrom <= r.oilWarmFrom) throw ProfileFormatException("$path: oilOkFrom must be above oilWarmFrom")
            if (r.coolantOkFrom <= r.coolantWarmFrom) throw ProfileFormatException("$path: coolantOkFrom must be above coolantWarmFrom")
            if (r.coldMaxAcceleratorPct <= 0 || r.coldMaxRpm <= 0) throw ProfileFormatException("$path: cold limits must be positive")
            return r
        }
        return null
    }

    /** The profile's cooling alarms (`alarms.json`, docs/02 C.7), own or nearest include's; null if none. */
    fun alarmRules(id: String): AlarmRules? {
        for (member in includeChain(id, mutableListOf(), mutableSetOf()).asReversed()) {
            val path = "$member/alarms.json"
            val text = source.read(path) ?: continue
            return AlarmRules.parse(text, path)
        }
        return null
    }

    /** The Battery tab's pack facts and limits (`battery.json`), own or nearest include's; defaults if none. */
    fun batteryProfile(id: String): BatteryProfile {
        for (member in includeChain(id, mutableListOf(), mutableSetOf()).asReversed()) {
            val path = "$member/${BatteryProfile.FILE}"
            val text = source.read(path) ?: continue
            return BatteryProfile.parse(text, path)
        }
        return BatteryProfile()
    }

    /** Gauge full-scale values (`display.json`), own or nearest include's; defaults if none. */
    fun displayRanges(id: String): DisplayRanges {
        for (member in includeChain(id, mutableListOf(), mutableSetOf()).asReversed()) {
            val path = "$member/display.json"
            val text = source.read(path) ?: continue
            val o = SignalSetParser.parseJson(text, path)
            val d = DisplayRanges()
            val r = DisplayRanges(
                speedMaxKmh = o.number("speedMaxKmh") ?: d.speedMaxKmh,
                enginePowerMaxKw = o.number("enginePowerMaxKw") ?: d.enginePowerMaxKw,
                batteryPowerMaxKw = o.number("batteryPowerMaxKw") ?: d.batteryPowerMaxKw,
            )
            if (r.speedMaxKmh <= 0 || r.enginePowerMaxKw <= 0 || r.batteryPowerMaxKw <= 0) {
                throw ProfileFormatException("$path: ranges must be positive")
            }
            return r
        }
        return DisplayRanges()
    }

    /** Ids in load order: includes (depth first, each once) then [id] itself. */
    private fun includeChain(id: String, out: MutableList<String>, visiting: MutableSet<String>): List<String> {
        if (id in out) return out
        if (!visiting.add(id)) throw ProfileFormatException("include cycle through \"$id\"")
        for (included in info(id).includes) includeChain(included, out, visiting)
        visiting.remove(id)
        out += id
        return out
    }

    private fun warnings(commands: List<SignalCommand>): List<String> {
        val warnings = mutableListOf<String>()
        commands.flatMap { c -> c.signals.map { it.id to c.key } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .forEach { (signalId, keys) -> warnings += "signal $signalId is defined by several commands: $keys" }
        commands.groupBy { it.key }
            .filterValues { list -> list.size > 1 && list.any { it.filter == null } }
            .forEach { (key, list) -> warnings += "request $key is defined ${list.size} times" }
        return warnings
    }

    private fun readRequired(path: String): String =
        source.read(path) ?: throw ProfileFormatException("missing file vehicles/$path")
}
