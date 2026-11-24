package com.hybridguard.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/*
 * Cooling alarms (docs/02 C.7): a stopped or slow electric water pump, and
 * coolant that is too hot or climbing too fast, which also catches a pump
 * that turns but moves no water (broken impeller), in the engine and the
 * inverter cooling loops. The checks and their thresholds are
 * profile data (`alarms.json`), written in roles, so Kotlin knows no car.
 */

/** How bad it is. */
enum class AlarmLevel {
    /** Something is wrong: drive gently and keep an eye on it. Amber band, said once. */
    CAUTION,

    /** Damage is near: stop the car safely. Red full screen, repeated until seen. */
    STOP,
}

/** An alarm that is on now. */
data class Alarm(
    /** The check's id from `alarms.json` (stable: acknowledgements follow it). */
    val id: String,
    val level: AlarmLevel,
    val title: String,
    val advice: String,
    /** The readings behind it, e.g. "pump 0 rpm (asked 2900 rpm) · coolant 96 °C". */
    val detail: String,
    /** Monotonic time it came on. */
    val sinceMs: Long,
)

/** One group of checks for the alarm list ("Engine water pump: watching"). */
data class AlarmWatch(
    val group: String,
    /** True when every input of at least one of its checks is being read now. */
    val watching: Boolean,
    /** Why it is not watched, for groups the car cannot report at all (from `notWatched`). */
    val why: String? = null,
)

/** A check from `alarms.json`. */
sealed class AlarmCheck {
    abstract val id: String
    abstract val group: String
    abstract val level: AlarmLevel
    abstract val title: String
    abstract val advice: String

    /** How long the condition must hold before the alarm comes on. */
    abstract val forMs: Long

    /** Roles it reads. */
    abstract val roles: Set<Role>

    /** False for optional checks the driver switches on (`"enabled": false` in `alarms.json`). */
    abstract val enabledByDefault: Boolean

    /** Display unit of the check's main value ("rpm", "°C", "V"), for the settings screen. */
    abstract val unit: String

    /**
     * An electric pump slower than it should be while what drives it runs:
     * below [below] (the signal's own unit) or below [belowShareOfTarget] of
     * the speed the ECU asks for. Checked only after [runsWhile] has been at
     * or above [runsAbove] for [runningForMs] (the pump spins up within
     * seconds), and only while the target, if the car reports one, is at
     * least [targetFrom].
     */
    data class PumpLow(
        override val id: String,
        override val group: String,
        override val level: AlarmLevel,
        override val title: String,
        override val advice: String,
        override val forMs: Long,
        val pump: Role,
        val target: Role?,
        val runsWhile: Role,
        val runsAbove: Double,
        val runningForMs: Long,
        val targetFrom: Double,
        val below: Double?,
        val belowShareOfTarget: Double?,
        override val enabledByDefault: Boolean = true,
        override val unit: String = "",
    ) : AlarmCheck() {
        override val roles: Set<Role> get() = setOfNotNull(pump, target, runsWhile)
    }

    /** A temperature at or above [from] °C. */
    data class TooHot(
        override val id: String,
        override val group: String,
        override val level: AlarmLevel,
        override val title: String,
        override val advice: String,
        override val forMs: Long,
        val temp: Role,
        val from: Double,
        override val enabledByDefault: Boolean = true,
        override val unit: String = "°C",
    ) : AlarmCheck() {
        override val roles: Set<Role> get() = setOf(temp)
    }

    /**
     * A value below [below] (the signal's own unit), e.g. the 12 V system
     * while the car is READY. With [runsWhile], checked only after it has
     * been at or above [runsAbove] for [runningForMs].
     */
    data class TooLow(
        override val id: String,
        override val group: String,
        override val level: AlarmLevel,
        override val title: String,
        override val advice: String,
        override val forMs: Long,
        val value: Role,
        val below: Double,
        val runsWhile: Role?,
        val runsAbove: Double,
        val runningForMs: Long,
        override val enabledByDefault: Boolean = true,
        override val unit: String = "",
    ) : AlarmCheck() {
        override val roles: Set<Role> get() = setOfNotNull(value, runsWhile)
    }

    /** A temperature at or above [from] °C that rose at least [perMinute] °C per minute over the last [overMs]. */
    data class RisingFast(
        override val id: String,
        override val group: String,
        override val level: AlarmLevel,
        override val title: String,
        override val advice: String,
        override val forMs: Long,
        val temp: Role,
        val from: Double,
        val perMinute: Double,
        val overMs: Long,
        override val enabledByDefault: Boolean = true,
        override val unit: String = "°C",
    ) : AlarmCheck() {
        override val roles: Set<Role> get() = setOf(temp)
    }
}

/** A profile's `alarms.json`. */
data class AlarmRules(
    val checks: List<AlarmCheck>,
    /** Groups the car cannot report yet, with the reason: listed so the driver knows. */
    val notWatched: List<AlarmWatch> = emptyList(),
) {
    /** Every role the checks read: polled all the time while the alarms are on. */
    val roles: Set<Role> get() = checks.flatMap { it.roles }.toSet()

    companion object {
        fun parse(text: String, path: String): AlarmRules {
            val root = SignalSetParser.parseJson(text, path)
            val checks = (root["checks"] as? JsonArray ?: throw ProfileFormatException("$path: missing \"checks\""))
                .mapIndexed { i, e -> parseCheck(e.jsonObject, "$path checks[$i]") }
            checks.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
                throw ProfileFormatException("$path: check id \"$it\" is used twice")
            }
            val notWatched = (root["notWatched"] as? JsonArray).orEmpty().mapIndexed { i, e ->
                val o = e.jsonObject
                val where = "$path notWatched[$i]"
                AlarmWatch(
                    group = o.string("group") ?: throw ProfileFormatException("$where: missing \"group\""),
                    watching = false,
                    why = o.string("why") ?: throw ProfileFormatException("$where: missing \"why\""),
                )
            }
            return AlarmRules(checks, notWatched)
        }

        private fun parseCheck(o: kotlinx.serialization.json.JsonObject, where: String): AlarmCheck {
            fun text(key: String) = o.string(key) ?: throw ProfileFormatException("$where: missing \"$key\"")
            fun need(key: String) = o.number(key) ?: throw ProfileFormatException("$where: missing \"$key\"")
            fun role(key: String) = Role.fromKey(text(key)) ?: throw ProfileFormatException("$where: unknown role \"${o.string(key)}\" in \"$key\"")
            fun ms(key: String, default: Double? = null) = ((o.number(key) ?: default ?: need(key)) * 1000).toLong()
            val id = text("id")
            val group = text("group")
            val level = when (text("level")) {
                "caution" -> AlarmLevel.CAUTION
                "stop" -> AlarmLevel.STOP
                else -> throw ProfileFormatException("$where: level must be \"caution\" or \"stop\"")
            }
            val title = text("title")
            val advice = text("advice")
            val enabled = o.bool("enabled") ?: true
            val unit = o.string("unit")
            return when (text("kind")) {
                "pumpLow" -> {
                    val below = o.number("below")
                    val share = o.number("belowShareOfTarget")
                    val target = o.string("target")?.let { role("target") }
                    if (below == null && share == null) throw ProfileFormatException("$where: needs \"below\" or \"belowShareOfTarget\"")
                    if (share != null && (target == null || share <= 0 || share >= 1)) {
                        throw ProfileFormatException("$where: \"belowShareOfTarget\" needs a \"target\" and a share between 0 and 1")
                    }
                    AlarmCheck.PumpLow(
                        id, group, level, title, advice, ms("forSeconds"),
                        pump = role("pump"),
                        target = target,
                        runsWhile = role("runsWhile"),
                        runsAbove = need("runsAbove"),
                        runningForMs = ms("runningForSeconds", 0.0),
                        targetFrom = o.number("targetFrom") ?: 0.0,
                        below = below,
                        belowShareOfTarget = share,
                        enabledByDefault = enabled,
                        unit = unit ?: "",
                    )
                }
                "tooHot" -> AlarmCheck.TooHot(
                    id, group, level, title, advice, ms("forSeconds"), role("temp"), need("from"),
                    enabledByDefault = enabled, unit = unit ?: "°C",
                )
                "tooLow" -> AlarmCheck.TooLow(
                    id, group, level, title, advice, ms("forSeconds"),
                    value = role("value"),
                    below = need("below"),
                    runsWhile = o.string("runsWhile")?.let { role("runsWhile") },
                    runsAbove = o.number("runsAbove") ?: 0.5,
                    runningForMs = ms("runningForSeconds", 0.0),
                    enabledByDefault = enabled,
                    unit = unit ?: "",
                )
                "risingFast" -> {
                    val over = ms("overSeconds")
                    if (over < 10_000) throw ProfileFormatException("$where: \"overSeconds\" must be at least 10")
                    AlarmCheck.RisingFast(
                        id, group, level, title, advice, ms("forSeconds", 0.0),
                        temp = role("temp"), from = need("from"), perMinute = need("perMinute"), overMs = over,
                        enabledByDefault = enabled, unit = unit ?: "°C",
                    )
                }
                else -> throw ProfileFormatException("$where: kind must be pumpLow, tooHot, tooLow or risingFast")
            }
        }
    }
}

/** Fresh readings for the [AlarmMonitor]: the value of a role now and its display unit, or null. */
interface RoleReadings {
    fun number(role: Role): Double?
    fun unit(role: Role): String?
}

/**
 * Runs the [AlarmRules] over the readings as they arrive. A condition must
 * hold for its check's time before the alarm comes on (one odd reply is not
 * an alarm); once on, it stays until the condition has been gone for
 * [clearMs], and a temperature must fall [hysteresisC] below the threshold
 * to count as gone. A check whose inputs are not being read raises nothing.
 *
 * Pure and single-threaded, like [DashboardEngine], which owns it.
 */
class AlarmMonitor(
    val rules: AlarmRules,
    private val clearMs: Long = 20_000,
    private val hysteresisC: Double = 2.0,
) {
    private class Track {
        var trueSince: Long? = null
        var falseSince: Long? = null
        var onSince: Long? = null
    }

    private val tracks = rules.checks.associate { it.id to Track() }
    private val runningSince = mutableMapOf<String, Long>()

    /** Temperature history for the rising checks: (time, value), at most one per [HISTORY_STEP_MS]. */
    private val history = mutableMapOf<Role, ArrayDeque<Pair<Long, Double>>>()
    private val historyMs: Long = rules.checks.filterIsInstance<AlarmCheck.RisingFast>().maxOfOrNull { it.overMs + 30_000 } ?: 0

    /** The alarms on at [nowMs], worst first, and which groups are being watched. */
    fun update(nowMs: Long, r: RoleReadings): Pair<List<Alarm>, List<AlarmWatch>> {
        record(nowMs, r)
        val alarms = mutableListOf<Alarm>()
        val watched = mutableMapOf<String, Boolean>()
        for (check in rules.checks) {
            val track = tracks.getValue(check.id)
            val inputs = inputsRead(check, r)
            watched[check.group] = (watched[check.group] ?: false) || inputs
            val condition = inputs && holds(check, nowMs, r, on = track.onSince != null)
            if (condition) {
                track.falseSince = null
                val since = track.trueSince ?: nowMs.also { track.trueSince = it }
                if (track.onSince == null && nowMs - since >= check.forMs) track.onSince = nowMs
            } else {
                track.trueSince = null
                val since = track.falseSince ?: nowMs.also { track.falseSince = it }
                if (track.onSince != null && nowMs - since >= clearMs) track.onSince = null
            }
            track.onSince?.let { alarms += Alarm(check.id, check.level, check.title, check.advice, detail(check, nowMs, r), it) }
        }
        val watches = watched.map { (group, on) -> AlarmWatch(group, on) } + rules.notWatched
        return sortAlarms(alarms) to watches
    }

    private fun inputsRead(check: AlarmCheck, r: RoleReadings): Boolean = when (check) {
        is AlarmCheck.PumpLow -> r.number(check.pump) != null && r.number(check.runsWhile) != null
        is AlarmCheck.TooHot -> r.number(check.temp) != null
        is AlarmCheck.TooLow -> r.number(check.value) != null && (check.runsWhile == null || r.number(check.runsWhile) != null)
        is AlarmCheck.RisingFast -> r.number(check.temp) != null
    }

    private fun holds(check: AlarmCheck, nowMs: Long, r: RoleReadings, on: Boolean): Boolean = when (check) {
        is AlarmCheck.PumpLow -> pumpLow(check, nowMs, r)
        is AlarmCheck.TooHot -> r.number(check.temp)!! >= check.from - if (on) hysteresisC else 0.0
        is AlarmCheck.TooLow -> tooLow(check, nowMs, r)
        is AlarmCheck.RisingFast -> {
            val t = r.number(check.temp)!!
            val rate = ratePerMinute(check.temp, nowMs, check.overMs)
            t >= check.from - (if (on) hysteresisC else 0.0) && rate != null && rate >= check.perMinute
        }
    }

    private fun pumpLow(c: AlarmCheck.PumpLow, nowMs: Long, r: RoleReadings): Boolean {
        val running = (r.number(c.runsWhile) ?: return false) >= c.runsAbove
        if (!running) {
            runningSince.remove(c.id)
            return false
        }
        val since = runningSince.getOrPut(c.id) { nowMs }
        if (nowMs - since < c.runningForMs) return false
        val pump = r.number(c.pump) ?: return false
        val target = c.target?.let(r::number)
        if (target != null && target < c.targetFrom) return false
        val belowAbsolute = c.below != null && pump < c.below
        val belowShare = c.belowShareOfTarget != null && target != null && pump < target * c.belowShareOfTarget
        return belowAbsolute || belowShare
    }

    private fun tooLow(c: AlarmCheck.TooLow, nowMs: Long, r: RoleReadings): Boolean {
        if (c.runsWhile != null) {
            val running = (r.number(c.runsWhile) ?: return false) >= c.runsAbove
            if (!running) {
                runningSince.remove(c.id)
                return false
            }
            val since = runningSince.getOrPut(c.id) { nowMs }
            if (nowMs - since < c.runningForMs) return false
        }
        return (r.number(c.value) ?: return false) < c.below
    }

    private fun record(nowMs: Long, r: RoleReadings) {
        if (historyMs <= 0) return
        for (role in rules.checks.filterIsInstance<AlarmCheck.RisingFast>().map { it.temp }.toSet()) {
            val list = history.getOrPut(role) { ArrayDeque() }
            val v = r.number(role)
            if (v == null) {
                list.clear() // a gap: start the slope again
                continue
            }
            if (list.isEmpty() || nowMs - list.last().first >= HISTORY_STEP_MS) list.addLast(nowMs to v)
            while (list.isNotEmpty() && list.first().first < nowMs - historyMs) list.removeFirst()
        }
    }

    /** °C per minute from the oldest reading at least [overMs] old to now; null until the history covers [overMs]. */
    private fun ratePerMinute(role: Role, nowMs: Long, overMs: Long): Double? {
        val list = history[role] ?: return null
        val newest = list.lastOrNull() ?: return null
        val start = list.lastOrNull { nowMs - it.first >= overMs } ?: return null
        val minutes = (newest.first - start.first) / 60_000.0
        return if (minutes > 0) (newest.second - start.second) / minutes else null
    }

    private fun detail(check: AlarmCheck, nowMs: Long, r: RoleReadings): String {
        fun v(role: Role, label: String): String? = r.number(role)?.let { "$label ${num(it)} ${r.unit(role) ?: ""}".trim() }
        return when (check) {
            is AlarmCheck.PumpLow -> listOfNotNull(
                v(check.pump, "pump")?.let { pump -> check.target?.let { v(it, "asked") }?.let { "$pump ($it)" } ?: pump },
                // READY is a yes / no: "ready 1" says nothing the alarm does not.
                if (check.runsWhile == Role.READY) null
                else v(check.runsWhile, if (check.runsWhile == Role.ENGINE_RPM) "engine" else check.runsWhile.key),
                when (check.pump) {
                    Role.ENGINE_WATER_PUMP -> v(Role.COOLANT_TEMP, "coolant")
                    Role.INVERTER_PUMP -> v(Role.INVERTER_COOLANT_TEMP, "inverter coolant")
                    else -> null
                },
            )
            is AlarmCheck.TooHot -> listOfNotNull(v(check.temp, "now"))
            is AlarmCheck.TooLow -> listOfNotNull(v(check.value, "now"))
            is AlarmCheck.RisingFast -> listOfNotNull(
                v(check.temp, "now"),
                ratePerMinute(check.temp, nowMs, check.overMs)?.let { "rising ${num(it)} °C a minute" },
            )
        }.joinToString(" · ")
    }

    private fun num(v: Double): String = String.format(Locale.US, "%.0f", v)

    private companion object {
        const val HISTORY_STEP_MS = 1_000L
    }
}

/** STOP before CAUTION, then the oldest first. */
fun sortAlarms(alarms: List<Alarm>): List<Alarm> =
    alarms.sortedWith(compareByDescending<Alarm> { it.level }.thenBy { it.sinceMs })

/**
 * What the screens and the sound should do with the alarms that are on:
 * [unacknowledged] until the driver taps OK; [announce] is the alarm to
 * sound and say now (once per [AlarmGate.update] at most).
 */
data class AlarmView(
    val alarms: List<Alarm> = emptyList(),
    val unacknowledged: Boolean = false,
    val announce: Alarm? = null,
) {
    val top: Alarm? get() = alarms.firstOrNull()

    /** A STOP alarm the driver has not seen yet: it takes the whole screen. */
    val fullScreen: Boolean get() = unacknowledged && top?.level == AlarmLevel.STOP
}

/**
 * Acknowledgement and repeats (docs/02 C.7). A new alarm, or one that got
 * worse (CAUTION to STOP), is announced at once and waits for OK. A STOP
 * alarm is announced again every [repeatMs] until OK, and every [remindMs]
 * after OK while it stays on; a CAUTION is announced once. An alarm that
 * went off and comes back is new again.
 */
class AlarmGate(
    private val repeatMs: Long = 30_000,
    private val remindMs: Long = 5 * 60_000,
) {
    private class Track(var level: AlarmLevel) {
        var acknowledged = false
        var announcedAt: Long? = null
    }

    private val tracks = mutableMapOf<String, Track>()

    fun update(alarms: List<Alarm>, nowMs: Long): AlarmView {
        val sorted = sortAlarms(alarms)
        tracks.keys.retainAll(sorted.map { it.id }.toSet())
        for (a in sorted) {
            val track = tracks.getOrPut(a.id) { Track(a.level) }
            if (a.level > track.level) {
                track.acknowledged = false
                track.announcedAt = null
            }
            track.level = a.level
        }
        val due = sorted.filter { a ->
            val track = tracks.getValue(a.id)
            val last = track.announcedAt
            when {
                last == null -> true
                a.level != AlarmLevel.STOP -> false
                track.acknowledged -> nowMs - last >= remindMs
                else -> nowMs - last >= repeatMs
            }
        }
        due.forEach { tracks.getValue(it.id).announcedAt = nowMs }
        return AlarmView(
            alarms = sorted,
            unacknowledged = sorted.any { !tracks.getValue(it.id).acknowledged },
            announce = due.firstOrNull(),
        )
    }

    /** OK: every alarm on now is seen (no more repeats until a reminder or a worse level). */
    fun acknowledge(nowMs: Long) {
        tracks.values.forEach {
            it.acknowledged = true
            it.announcedAt = nowMs
        }
    }
}
