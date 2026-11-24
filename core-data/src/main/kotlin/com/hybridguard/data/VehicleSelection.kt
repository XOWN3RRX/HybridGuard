package com.hybridguard.data

import com.hybridguard.obd.RawSample
import com.hybridguard.obd.ResponseParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

object Vin {
    /** Position-10 model-year codes; index 0 is 1980 (and 2010, 2040, ...). */
    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    fun isValid(vin: String): Boolean =
        vin.length == 17 && vin.all { it in '0'..'9' || it in 'A'..'Z' } && vin.none { it in "IOQ" }

    /**
     * Model year from VIN position 10, choosing the latest cycle not after
     * next year. Not every market encodes the year there (European VINs
     * need not), so the user can override it.
     */
    fun modelYear(vin: String, currentYear: Int): Int? {
        if (!isValid(vin)) return null
        val index = YEAR_CODES.indexOf(vin[9])
        if (index < 0) return null
        var year = 1980 + index
        while (year + 30 <= currentYear + 1) year += 30
        return year
    }
}

/**
 * What one request returned during the capability probe.
 *
 * @param frames CAN frames in a positive reply (1 = single frame), so later
 *   requests can tell the adapter how many replies to wait for.
 */
data class CapabilityResult(val status: RawSample.Status, val negativeCode: Int? = null, val frames: Int? = null) {
    /** The ECU said it does not support this request at all: never poll it on this car. */
    val isDead: Boolean get() = status == RawSample.Status.NEGATIVE && negativeCode in DEAD_CODES

    val answered: Boolean get() = status == RawSample.Status.OK

    /** No reply at all (ECU silent or asleep): worth retrying now and then, not every cycle. */
    val silent: Boolean get() = status == RawSample.Status.NO_DATA || status == RawSample.Status.TIMEOUT

    private companion object {
        /** 11 service not supported, 12 sub-function not supported, 31 request out of range. */
        val DEAD_CODES = setOf(0x11, 0x12, 0x31)
    }
}

/** Capability probe results for one car, keyed by [SignalCommand.key]. Only valid for the profile version it was made with. */
data class CapabilityReport(
    val profileId: String,
    val profileVersion: Int,
    val probedAtEpochMs: Long,
    val results: Map<String, CapabilityResult>,
) {
    fun isDead(commandKey: String): Boolean = results[commandKey]?.isDead == true

    companion object {
        /** Keys each sample by its own header and request. */
        fun fromSamples(profile: ProfileInfo, probedAtEpochMs: Long, samples: List<RawSample>): CapabilityReport =
            fromResults(profile, probedAtEpochMs, samples.map { commandKey(it.header, it.request) to it })

        /** Keys given explicitly, e.g. the catalog key of a request that was sent to the broadcast address. */
        fun fromResults(profile: ProfileInfo, probedAtEpochMs: Long, results: List<Pair<String, RawSample>>): CapabilityReport =
            CapabilityReport(
                profileId = profile.id,
                profileVersion = profile.version,
                probedAtEpochMs = probedAtEpochMs,
                results = results.associate { (key, s) -> key to resultOf(s) },
            )

        fun resultOf(s: RawSample): CapabilityResult {
            if (s.status == RawSample.Status.TIMEOUT) return CapabilityResult(RawSample.Status.TIMEOUT)
            val parsed = ResponseParser.parse(s.request, s.response)
            val frames = if (parsed.status == RawSample.Status.OK) {
                s.response.lineSequence().map { it.replace(" ", "") }.count { FRAME_LINE.matches(it) }
            } else {
                null
            }
            return CapabilityResult(parsed.status, parsed.negativeCode, frames)
        }

        private val FRAME_LINE = Regex("^[0-9A-Fa-f]{3}(?:[0-9A-Fa-f]{2})+$")
    }
}

/** What the app remembers about one car. */
data class VehicleRecord(
    val vin: String,
    val manualProfileId: String? = null,
    val manualModelYear: Int? = null,
    val capabilities: CapabilityReport? = null,
)

interface VehicleStore {
    fun get(vin: String): VehicleRecord?
    fun put(record: VehicleRecord)
}

class InMemoryVehicleStore : VehicleStore {
    private val records = mutableMapOf<String, VehicleRecord>()
    override fun get(vin: String): VehicleRecord? = records[vin]
    override fun put(record: VehicleRecord) {
        records[record.vin] = record
    }
}

/** One JSON file per VIN in [dir] (app-private storage on the phone). */
class FileVehicleStore(private val dir: File) : VehicleStore {

    override fun get(vin: String): VehicleRecord? {
        val file = fileFor(vin)
        if (!file.isFile) return null
        return fromJson(Json.parseToJsonElement(file.readText()).jsonObject)
    }

    override fun put(record: VehicleRecord) {
        dir.mkdirs()
        val file = fileFor(record.vin)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(toJson(record).toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not write $file" }
        }
    }

    private fun fileFor(vin: String): File {
        require(Vin.isValid(vin)) { "not a valid VIN" }
        return File(dir, "$vin.json")
    }

    private fun toJson(r: VehicleRecord): JsonObject = buildJsonObject {
        put("vin", r.vin)
        r.manualProfileId?.let { put("manualProfileId", it) }
        r.manualModelYear?.let { put("manualModelYear", it) }
        r.capabilities?.let { c ->
            put("capabilities", buildJsonObject {
                put("profileId", c.profileId)
                put("profileVersion", c.profileVersion)
                put("probedAtEpochMs", c.probedAtEpochMs)
                put("results", buildJsonObject {
                    for ((key, result) in c.results) {
                        put(key, buildJsonObject {
                            put("status", result.status.name)
                            result.negativeCode?.let { put("nrc", it) }
                            result.frames?.let { put("frames", it) }
                        })
                    }
                })
            })
        }
    }

    private fun fromJson(o: JsonObject): VehicleRecord = VehicleRecord(
        vin = o.string("vin")!!,
        manualProfileId = o.string("manualProfileId"),
        manualModelYear = o.int("manualModelYear"),
        capabilities = (o["capabilities"] as? JsonObject)?.let { c ->
            CapabilityReport(
                profileId = c.string("profileId")!!,
                profileVersion = c.int("profileVersion")!!,
                probedAtEpochMs = (c["probedAtEpochMs"] as JsonPrimitive).content.toLong(),
                results = c["results"]!!.jsonObject.mapValues { (_, v) ->
                    val r = v.jsonObject
                    CapabilityResult(RawSample.Status.valueOf(r.string("status")!!), r.int("nrc"), r.int("frames"))
                },
            )
        },
    )
}

enum class SelectionReason { MANUAL, VIN_MATCH, FALLBACK }

data class ProfileSelection(val profileId: String, val modelYear: Int?, val reason: SelectionReason)

/**
 * Picks the profile for a car (docs/07 "Selection and probing"):
 * the user's remembered choice for this VIN, else the longest matching VIN
 * prefix, else the index fallback (generic SAE J1979).
 */
class ProfileSelector(
    private val loader: ProfileLoader,
    private val store: VehicleStore,
    private val currentYear: () -> Int,
) {
    private val index by lazy { loader.index() }
    private val infos by lazy { index.profileIds.associateWith { loader.info(it) } }

    /** Profiles the user can pick from. */
    fun selectable(): List<ProfileInfo> = infos.values.filter { it.manualSelectable }

    /**
     * Model year: the user's entry for this VIN, else the year the matching
     * VIN prefix gives (curated data, only for prefixes that fix the year),
     * else VIN position 10 (North American and many other VINs; European
     * VINs need not encode it, Toyota's put `0` there). Null when none of
     * them knows: the app then asks the owner ([needsModelYear]).
     */
    fun select(vin: String?): ProfileSelection {
        val record = vin?.let(store::get)
        val match = vin?.let { bestVinMatch(it.uppercase()) }
        val year = record?.manualModelYear
            ?: match?.let { (info, prefix) -> info.vinModelYears[prefix] }
            ?: vin?.let { Vin.modelYear(it, currentYear()) }

        record?.manualProfileId?.takeIf { it in infos }?.let {
            return ProfileSelection(it, year, SelectionReason.MANUAL)
        }
        match?.let { return ProfileSelection(it.first.id, year, SelectionReason.VIN_MATCH) }
        return ProfileSelection(index.fallbackId, year, SelectionReason.FALLBACK)
    }

    /** The profile with the longest VIN prefix matching [vin], and that prefix. */
    private fun bestVinMatch(vin: String): Pair<ProfileInfo, String>? =
        infos.values
            .mapNotNull { info -> info.vinPrefixes.filter { vin.startsWith(it) }.maxByOrNull { it.length }?.let { info to it } }
            .maxByOrNull { it.second.length }

    /** Remembers the user's choice for this VIN; null clears it (back to automatic). */
    fun chooseManually(vin: String, profileId: String?, modelYear: Int? = null) {
        require(profileId == null || profileId in infos) { "unknown profile $profileId" }
        val record = store.get(vin) ?: VehicleRecord(vin)
        store.put(record.copy(manualProfileId = profileId, manualModelYear = modelYear))
    }

    /** The model year the owner entered for this VIN, if any. */
    fun ownerModelYear(vin: String): Int? = store.get(vin)?.manualModelYear

    /** Remembers the owner's model year for this VIN (null forgets it), keeping the profile choice. */
    fun setModelYear(vin: String, modelYear: Int?) {
        val record = store.get(vin) ?: VehicleRecord(vin)
        store.put(record.copy(manualModelYear = modelYear))
    }

    /**
     * The owner must be asked for the model year: neither the owner, the
     * profile's VIN prefixes nor VIN position 10 give it, and the chosen
     * profile has year-dependent signals (a profile with a `years` range).
     */
    fun needsModelYear(vin: String): Boolean {
        val selection = select(vin)
        return selection.modelYear == null && infos[selection.profileId]?.years != null
    }

    fun info(profileId: String): ProfileInfo? = infos[profileId]

    fun capabilities(vin: String): CapabilityReport? = store.get(vin)?.capabilities

    fun saveCapabilities(vin: String, report: CapabilityReport) {
        val record = store.get(vin) ?: VehicleRecord(vin)
        store.put(record.copy(capabilities = report))
    }
}
