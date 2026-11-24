package com.hybridguard.app

import com.hybridguard.data.Alarm
import com.hybridguard.data.AlarmLevel
import com.hybridguard.data.DashboardState
import com.hybridguard.data.Role
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** min / average / max of one value over a session. */
data class Stat(val min: Double, val max: Double, val avg: Double, val count: Int)

data class LoggedAlarm(
    val id: String,
    val level: AlarmLevel,
    val title: String,
    val detail: String,
    val startEpochMs: Long,
    val endEpochMs: Long?,
)

/** What a session's `summary.json` holds: shown in the session list without reading the values. */
data class RecordedSession(
    val id: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val km: Double,
    val evKm: Double,
    val fuelLitres: Double?,
    val maxSpeedKmh: Double?,
    val profileId: String?,
    val profileName: String?,
    val modelYear: Int?,
    val vin: String?,
    val adapter: String?,
    val startVolts: Double?,
    val endReason: String,
    val roles: List<Role>,
    val units: Map<Role, String>,
    val stats: Map<Role, Stat>,
    val alarms: List<LoggedAlarm>,
    val appVersion: String,
    val simulated: Boolean,
    /** Values this car did not give, with what each request answered ("7E0 01 5C: no reply"). */
    val notGiven: Map<Role, List<String>> = emptyMap(),
) {
    val durationMs: Long get() = endEpochMs - startEpochMs
    val worstLevel: AlarmLevel? get() = alarms.maxOfOrNull { it.level }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startEpochMs", startEpochMs)
        put("endEpochMs", endEpochMs)
        put("km", km)
        put("evKm", evKm)
        put("fuelLitres", fuelLitres)
        put("maxSpeedKmh", maxSpeedKmh)
        put("profileId", profileId)
        put("profileName", profileName)
        put("modelYear", modelYear)
        put("vin", vin)
        put("adapter", adapter)
        put("startVolts", startVolts)
        put("endReason", endReason)
        put("roles", JSONArray(roles.map { it.key }))
        put("units", JSONObject(units.mapKeys { it.key.key }))
        put("stats", JSONObject().apply {
            stats.forEach { (role, s) ->
                put(role.key, JSONObject().put("min", s.min).put("max", s.max).put("avg", s.avg).put("count", s.count))
            }
        })
        put("alarms", SessionRecorder.alarmsJson(alarms))
        put("appVersion", appVersion)
        put("simulated", simulated)
        put("notGiven", JSONObject().apply { notGiven.forEach { (role, texts) -> put(role.key, JSONArray(texts)) } })
    }

    companion object {
        fun fromJson(o: JSONObject): RecordedSession {
            fun dbl(k: String): Double? = if (o.isNull(k) || !o.has(k)) null else o.getDouble(k)
            fun str(k: String): String? = if (o.isNull(k) || !o.has(k)) null else o.getString(k)
            val roles = o.optJSONArray("roles")?.let { a -> (0 until a.length()).mapNotNull { Role.fromKey(a.getString(it)) } }.orEmpty()
            val units = o.optJSONObject("units")?.let { u -> u.keys().asSequence().mapNotNull { k -> Role.fromKey(k)?.let { it to u.getString(k) } }.toMap() }.orEmpty()
            val stats = o.optJSONObject("stats")?.let { st ->
                st.keys().asSequence().mapNotNull { k ->
                    val s = st.getJSONObject(k)
                    Role.fromKey(k)?.let { it to Stat(s.getDouble("min"), s.getDouble("max"), s.getDouble("avg"), s.getInt("count")) }
                }.toMap()
            }.orEmpty()
            val alarms = o.optJSONArray("alarms")?.let(SessionRecorder::parseAlarms).orEmpty()
            val notGiven = o.optJSONObject("notGiven")?.let { n ->
                n.keys().asSequence().mapNotNull { k ->
                    val a = n.getJSONArray(k)
                    Role.fromKey(k)?.let { it to (0 until a.length()).map(a::getString) }
                }.toMap()
            }.orEmpty()
            return RecordedSession(
                id = o.getString("id"),
                startEpochMs = o.getLong("startEpochMs"),
                endEpochMs = o.getLong("endEpochMs"),
                km = o.optDouble("km", 0.0),
                evKm = o.optDouble("evKm", 0.0),
                fuelLitres = dbl("fuelLitres"),
                maxSpeedKmh = dbl("maxSpeedKmh"),
                profileId = str("profileId"),
                profileName = str("profileName"),
                modelYear = if (o.isNull("modelYear") || !o.has("modelYear")) null else o.getInt("modelYear"),
                vin = str("vin"),
                adapter = str("adapter"),
                startVolts = dbl("startVolts"),
                endReason = o.optString("endReason"),
                roles = roles,
                units = units,
                stats = stats,
                alarms = alarms,
                appVersion = o.optString("appVersion"),
                simulated = o.optBoolean("simulated"),
                notGiven = notGiven,
            )
        }
    }
}

/** One row of a session's values (or of the live history). */
class Sample(val epochMs: Long, val values: Map<Role, Double>)

/** Metadata known when the car first answers. */
data class SessionStart(
    val startEpochMs: Long,
    val profileId: String?,
    val profileName: String?,
    val modelYear: Int?,
    val vin: String?,
    val adapter: String?,
    val startVolts: Double?,
    val simulated: Boolean,
    val appVersion: String,
)

/**
 * Writes one session while it runs: a row of [roles] every [intervalMs] to
 * `values.csv` (flushed every few rows, so a crash loses little), and
 * `summary.json` at the end.
 */
class SessionRecorder(
    private val dir: File,
    private val start: SessionStart,
    private val roles: List<Role>,
    private val intervalMs: Long,
) {
    val id: String = dir.name
    private val writer: BufferedWriter
    private var lastRowMs = 0L
    private var rows = 0
    private val units = mutableMapOf<Role, String>()
    private val acc = mutableMapOf<Role, DoubleArray>() // min, max, sum, count
    private val alarms = mutableListOf<LoggedAlarm>()
    private val openAlarms = mutableMapOf<String, Int>() // alarm id -> index in [alarms]
    private var last: DashboardState? = null

    /** Values the car did not give (from the probe), kept in the summary. */
    var notGiven: Map<Role, List<String>> = emptyMap()

    init {
        dir.mkdirs()
        File(dir, START_FILE).writeText(startJson(start).toString())
        writer = File(dir, VALUES_FILE).bufferedWriter()
        writer.write("epoch_ms,time," + roles.joinToString(",") { it.key })
        writer.newLine()
    }

    fun sample(epochMs: Long, state: DashboardState) {
        last = state
        state.units.forEach { (r, u) -> units.putIfAbsent(r, u) }
        if (epochMs - lastRowMs < intervalMs) return
        lastRowMs = epochMs
        val cells = roles.map { role ->
            val v = state.values[role] ?: return@map ""
            val a = acc.getOrPut(role) { doubleArrayOf(v, v, 0.0, 0.0) }
            a[0] = minOf(a[0], v); a[1] = maxOf(a[1], v); a[2] += v; a[3] += 1
            fmt(v)
        }
        writer.write("$epochMs,${TIME.get()!!.format(Date(epochMs))}," + cells.joinToString(","))
        writer.newLine()
        if (++rows % 10 == 0) writer.flush()
    }

    /** The alarms on now (dashboard and connection alarms): starts and ends are kept with their times. */
    fun alarms(epochMs: Long, on: List<Alarm>) {
        var changed = false
        val ids = on.map { it.id }.toSet()
        for (id in openAlarms.keys.toList() - ids) {
            val i = openAlarms.remove(id)!!
            alarms[i] = alarms[i].copy(endEpochMs = epochMs)
            changed = true
        }
        for (a in on) {
            val i = openAlarms[a.id]
            if (i == null) {
                openAlarms[a.id] = alarms.size
                alarms += LoggedAlarm(a.id, a.level, a.title, a.detail, epochMs, null)
                changed = true
            } else if (a.level > alarms[i].level) {
                alarms[i] = alarms[i].copy(level = a.level, detail = a.detail)
                changed = true
            }
        }
        // Written at once, so a session the app could not close still has its alarms.
        if (changed) runCatching { File(dir, ALARMS_FILE).writeText(alarmsJson(alarms).toString()) }
    }

    /** Closes the files; returns the session, or null if it was too short to keep (and had no alarm). */
    fun finish(endEpochMs: Long, endReason: String, minDurationMs: Long): RecordedSession? {
        runCatching { writer.close() }
        alarms(endEpochMs, emptyList())
        val trip = last?.trip
        val session = RecordedSession(
            id = id,
            startEpochMs = start.startEpochMs,
            endEpochMs = endEpochMs,
            km = trip?.km ?: 0.0,
            evKm = trip?.evKm ?: 0.0,
            fuelLitres = trip?.fuelLitres,
            maxSpeedKmh = trip?.maxSpeedKmh,
            profileId = start.profileId,
            profileName = start.profileName,
            modelYear = start.modelYear,
            vin = start.vin,
            adapter = start.adapter,
            startVolts = start.startVolts,
            endReason = endReason,
            roles = roles,
            units = units.toMap(),
            stats = acc.mapValues { (_, a) -> Stat(a[0], a[1], a[2] / a[3], a[3].toInt()) },
            alarms = alarms.toList(),
            appVersion = start.appVersion,
            simulated = start.simulated,
            notGiven = notGiven,
        )
        if (session.durationMs < minDurationMs && session.alarms.isEmpty()) {
            dir.deleteRecursively()
            return null
        }
        File(dir, SUMMARY_FILE).writeText(session.toJson().toString(1))
        return session
    }

    companion object {
        const val VALUES_FILE = "values.csv"
        const val SUMMARY_FILE = "summary.json"
        const val START_FILE = "start.json"
        const val ALARMS_FILE = "alarms.json"

        internal fun alarmsJson(list: List<LoggedAlarm>) = JSONArray(list.map {
            JSONObject().put("id", it.id).put("level", it.level.name).put("title", it.title).put("detail", it.detail)
                .put("start", it.startEpochMs).put("end", it.endEpochMs)
        })

        internal fun parseAlarms(a: JSONArray): List<LoggedAlarm> = (0 until a.length()).map { i ->
            val e = a.getJSONObject(i)
            LoggedAlarm(
                e.getString("id"),
                AlarmLevel.entries.firstOrNull { it.name == e.optString("level") } ?: AlarmLevel.CAUTION,
                e.optString("title"), e.optString("detail"),
                e.getLong("start"), if (e.isNull("end") || !e.has("end")) null else e.getLong("end"),
            )
        }
        val TIME: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
        private fun fmt(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

        internal fun startJson(s: SessionStart) = JSONObject()
            .put("startEpochMs", s.startEpochMs).put("profileId", s.profileId).put("profileName", s.profileName)
            .put("modelYear", s.modelYear).put("vin", s.vin).put("adapter", s.adapter).put("startVolts", s.startVolts)
            .put("simulated", s.simulated).put("appVersion", s.appVersion)
    }
}

/** The saved sessions: `files/sessions/<yyyyMMdd-HHmmss>/`. */
class SessionArchive(val root: File) {

    fun newDir(startEpochMs: Long): File {
        val base = DIR_NAME.get()!!.format(Date(startEpochMs))
        var dir = File(root, base)
        var n = 1
        while (dir.exists()) dir = File(root, "$base-${n++}")
        return dir
    }

    /** Newest first. */
    fun list(): List<RecordedSession> =
        root.listFiles().orEmpty()
            .mapNotNull { dir -> File(dir, SessionRecorder.SUMMARY_FILE).takeIf { it.exists() } }
            .mapNotNull { runCatching { RecordedSession.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.startEpochMs }

    fun get(id: String): RecordedSession? =
        File(File(root, id), SessionRecorder.SUMMARY_FILE).takeIf { it.exists() }
            ?.let { runCatching { RecordedSession.fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun valuesFile(id: String): File = File(File(root, id), SessionRecorder.VALUES_FILE)

    fun delete(id: String) {
        File(root, id).takeIf { it.parentFile == root }?.deleteRecursively()
    }

    /** The rows of a session's `values.csv`. */
    fun samples(id: String): List<Sample> {
        val file = valuesFile(id)
        if (!file.exists()) return emptyList()
        val out = ArrayList<Sample>()
        file.bufferedReader().useLines { lines ->
            var roles: List<Role?> = emptyList()
            for ((i, line) in lines.withIndex()) {
                val cells = line.split(',')
                if (i == 0) {
                    roles = cells.drop(2).map { Role.fromKey(it) }
                    continue
                }
                val t = cells.getOrNull(0)?.toLongOrNull() ?: continue
                val values = HashMap<Role, Double>()
                for ((j, role) in roles.withIndex()) {
                    val v = cells.getOrNull(j + 2)?.toDoubleOrNull() ?: continue
                    if (role != null) values[role] = v
                }
                out += Sample(t, values)
            }
        }
        return out
    }

    /**
     * Sessions the app could not close (it was killed, the phone restarted):
     * their summary is rebuilt from the values that were written.
     */
    fun recover(minDurationMs: Long) {
        for (dir in root.listFiles().orEmpty()) {
            if (!dir.isDirectory || File(dir, SessionRecorder.SUMMARY_FILE).exists()) continue
            val startFile = File(dir, SessionRecorder.START_FILE)
            val samples = samples(dir.name)
            if (!startFile.exists() || samples.isEmpty()) {
                dir.deleteRecursively()
                continue
            }
            val st = runCatching { JSONObject(startFile.readText()) }.getOrNull() ?: continue
            val roles = samples.flatMap { it.values.keys }.distinct()
            val stats = roles.associateWith { role ->
                val vs = samples.mapNotNull { it.values[role] }
                Stat(vs.min(), vs.max(), vs.average(), vs.size)
            }
            val end = samples.last().epochMs
            val start = st.getLong("startEpochMs")
            // Alarms still on when the app stopped end with the last values written.
            val alarms = File(dir, SessionRecorder.ALARMS_FILE).takeIf { it.exists() }
                ?.let { f -> runCatching { SessionRecorder.parseAlarms(JSONArray(f.readText())) }.getOrNull() }.orEmpty()
                .map { if (it.endEpochMs == null) it.copy(endEpochMs = end) else it }
            if (end - start < minDurationMs && alarms.isEmpty()) {
                dir.deleteRecursively()
                continue
            }
            val s = RecordedSession(
                id = dir.name, startEpochMs = start, endEpochMs = end, km = 0.0, evKm = 0.0, fuelLitres = null,
                maxSpeedKmh = stats[Role.SPEED]?.max,
                profileId = st.optString("profileId").ifEmpty { null }, profileName = st.optString("profileName").ifEmpty { null },
                modelYear = if (st.isNull("modelYear")) null else st.optInt("modelYear"),
                vin = st.optString("vin").ifEmpty { null }, adapter = st.optString("adapter").ifEmpty { null },
                startVolts = if (st.isNull("startVolts")) null else st.optDouble("startVolts"),
                endReason = "the app was closed before the session ended", roles = roles, units = emptyMap(),
                stats = stats, alarms = alarms, appVersion = st.optString("appVersion"),
                simulated = st.optBoolean("simulated"),
            )
            File(dir, SessionRecorder.SUMMARY_FILE).writeText(s.toJson().toString(1))
        }
    }

    /** Keeps at most [maxSessions], none older than [keepDays] days. */
    fun prune(keepDays: Int, maxSessions: Int, nowMs: Long) {
        val all = list()
        val cutoff = nowMs - keepDays * 86_400_000L
        all.withIndex().filter { (i, s) -> i >= maxSessions || s.startEpochMs < cutoff }.forEach { delete(it.value.id) }
    }

    private companion object {
        val DIR_NAME: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US) }
    }
}
