package com.hybridguard.data

import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/**
 * Hybrid battery pack types the Battery tab knows. Toyota hybrids use one of
 * the two; which one a car has is profile data, the owner's choice in the
 * settings, or told from the cell voltages ([BatteryAnalysis.detect]).
 */
enum class BatteryChemistry(val key: String, val label: String) {
    LITHIUM_ION("lithiumIon", "Lithium-ion"),
    NIMH("nimh", "Nickel-metal hydride (NiMH)"),
    ;

    companion object {
        fun fromKey(key: String?): BatteryChemistry? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Health-check limits for one pack type. Guide values, not manufacturer
 * specifications: the profile's `battery.json` sets them (with a note), and
 * the owner can change each one in the settings.
 *
 * @param spreadWarnMv highest minus lowest cell (or block) voltage at rest
 *   from which the balance is worth watching, mV.
 * @param restCurrentA balance and spread are judged only while the HV current
 *   is below this (both ways): under load the voltages spread by themselves.
 */
data class BatteryLimits(
    val spreadWarnMv: Double,
    val spreadBadMv: Double,
    val resistanceRatioWarn: Double,
    val resistanceRatioBad: Double,
    val tempSpreadWarnC: Double,
    val tempSpreadBadC: Double,
    val intakeDeltaWarnC: Double,
    val restCurrentA: Double,
) {
    /** This limit set with the owner's changes, keys as in [FIELDS] (missing = unchanged). */
    fun withOverrides(values: Map<String, Double>): BatteryLimits = BatteryLimits(
        spreadWarnMv = values["spreadWarnMv"] ?: spreadWarnMv,
        spreadBadMv = values["spreadBadMv"] ?: spreadBadMv,
        resistanceRatioWarn = values["resistanceRatioWarn"] ?: resistanceRatioWarn,
        resistanceRatioBad = values["resistanceRatioBad"] ?: resistanceRatioBad,
        tempSpreadWarnC = values["tempSpreadWarnC"] ?: tempSpreadWarnC,
        tempSpreadBadC = values["tempSpreadBadC"] ?: tempSpreadBadC,
        intakeDeltaWarnC = values["intakeDeltaWarnC"] ?: intakeDeltaWarnC,
        restCurrentA = values["restCurrentA"] ?: restCurrentA,
    )

    fun value(key: String): Double = when (key) {
        "spreadWarnMv" -> spreadWarnMv
        "spreadBadMv" -> spreadBadMv
        "resistanceRatioWarn" -> resistanceRatioWarn
        "resistanceRatioBad" -> resistanceRatioBad
        "tempSpreadWarnC" -> tempSpreadWarnC
        "tempSpreadBadC" -> tempSpreadBadC
        "intakeDeltaWarnC" -> intakeDeltaWarnC
        "restCurrentA" -> restCurrentA
        else -> throw IllegalArgumentException("unknown battery limit \"$key\"")
    }

    /** One editable limit: its key, label, unit and allowed range (for the settings screen). */
    data class Field(val key: String, val label: String, val unit: String, val min: Double, val max: Double)

    companion object {
        val FIELDS = listOf(
            Field("spreadWarnMv", "Voltage spread: watch from", "mV", 1.0, 2000.0),
            Field("spreadBadMv", "Voltage spread: check from", "mV", 1.0, 3000.0),
            Field("resistanceRatioWarn", "Resistance vs median: watch from", "×", 1.01, 5.0),
            Field("resistanceRatioBad", "Resistance vs median: check from", "×", 1.01, 10.0),
            Field("tempSpreadWarnC", "Temperature spread: watch from", "°C", 0.5, 50.0),
            Field("tempSpreadBadC", "Temperature spread: check from", "°C", 0.5, 50.0),
            Field("intakeDeltaWarnC", "Pack above cooling air: watch from", "°C", 1.0, 60.0),
            Field("restCurrentA", "At rest below", "A", 0.5, 50.0),
        )

        /** Used when a profile has no `battery.json`; the same guide values as the bundled profile. */
        val LITHIUM_ION_DEFAULT = BatteryLimits(30.0, 60.0, 1.25, 1.5, 5.0, 8.0, 10.0, 5.0)
        val NIMH_DEFAULT = BatteryLimits(200.0, 300.0, 1.25, 1.5, 5.0, 8.0, 10.0, 5.0)

        internal fun parse(o: JsonObject?, defaults: BatteryLimits, source: String): BatteryLimits {
            o ?: return defaults
            val values = FIELDS.mapNotNull { f -> o.number(f.key)?.let { f.key to it } }.toMap()
            val r = defaults.withOverrides(values)
            if (FIELDS.any { r.value(it.key) <= 0 }) throw ProfileFormatException("$source: battery limits must be positive")
            if (r.spreadBadMv < r.spreadWarnMv || r.resistanceRatioBad < r.resistanceRatioWarn || r.tempSpreadBadC < r.tempSpreadWarnC) {
                throw ProfileFormatException("$source: each 'Bad' limit must be at or above its 'Warn' limit")
            }
            return r
        }
    }
}

/**
 * A profile's `battery.json`: what is known about its pack and the
 * health-check limits per pack type. Every key is optional.
 *
 * @param chemistry null when the profile covers cars with either pack: the
 *   app then tells it from the cell voltages.
 * @param cells how many cells (or blocks) the pack reports, when known.
 */
data class BatteryProfile(
    val chemistry: BatteryChemistry? = null,
    val cells: Int? = null,
    val lithiumIon: BatteryLimits = BatteryLimits.LITHIUM_ION_DEFAULT,
    val nimh: BatteryLimits = BatteryLimits.NIMH_DEFAULT,
) {
    fun limits(chemistry: BatteryChemistry): BatteryLimits = when (chemistry) {
        BatteryChemistry.LITHIUM_ION -> lithiumIon
        BatteryChemistry.NIMH -> nimh
    }

    companion object {
        const val FILE = "battery.json"

        fun parse(text: String, source: String): BatteryProfile {
            val o = SignalSetParser.parseJson(text, source)
            val chemistry = o.string("chemistry")?.let { key ->
                BatteryChemistry.fromKey(key) ?: throw ProfileFormatException("$source: unknown chemistry \"$key\" (lithiumIon or nimh)")
            }
            val cells = o.int("cells")
            if (cells != null && cells <= 0) throw ProfileFormatException("$source: cells must be positive")
            return BatteryProfile(
                chemistry = chemistry,
                cells = cells,
                lithiumIon = BatteryLimits.parse(o["lithiumIon"] as? JsonObject, BatteryLimits.LITHIUM_ION_DEFAULT, "$source lithiumIon"),
                nimh = BatteryLimits.parse(o["nimh"] as? JsonObject, BatteryLimits.NIMH_DEFAULT, "$source nimh"),
            )
        }
    }
}

enum class FindingLevel { OK, WATCH, CHECK, INFO }

/** One line of the health check. */
data class Finding(val level: FindingLevel, val title: String, val detail: String)

/** Statistics over per-cell (or per-sensor) values; position in [values] = number − 1, null = no reading. */
data class Spread(val values: List<Double?>) {
    private val present = values.withIndex().mapNotNull { (i, v) -> v?.let { i to it } }
    val count: Int get() = present.size
    val min: Double? get() = present.minOfOrNull { it.second }
    val max: Double? get() = present.maxOfOrNull { it.second }
    val mean: Double? get() = if (present.isEmpty()) null else present.sumOf { it.second } / present.size
    val median: Double?
        get() {
            val s = present.map { it.second }.sorted()
            return when {
                s.isEmpty() -> null
                s.size % 2 == 1 -> s[s.size / 2]
                else -> (s[s.size / 2 - 1] + s[s.size / 2]) / 2
            }
        }
    val range: Double? get() = max?.let { hi -> min?.let { lo -> hi - lo } }

    /** 1-based number of the lowest / highest entry. */
    val minIndex: Int? get() = present.minByOrNull { it.second }?.first?.plus(1)
    val maxIndex: Int? get() = present.maxByOrNull { it.second }?.first?.plus(1)
}

/** Where the pack type shown came from. */
enum class ChemistrySource(val label: String) {
    SETTINGS("set in Settings"),
    PROFILE("from the car profile"),
    READINGS("told from the cell voltages"),
}

/** Everything the Battery tab shows, from the newest readings. */
data class BatteryReport(
    val chemistry: BatteryChemistry?,
    val chemistrySource: ChemistrySource?,
    val limits: BatteryLimits?,
    val soc: Double?,
    val socMin: Double?,
    val socMax: Double?,
    val voltage: Double?,
    /** Positive = discharging. */
    val current: Double?,
    val powerKw: Double?,
    val chargeLimitKw: Double?,
    val dischargeLimitKw: Double?,
    /** What the balance is judged on: the cells, or the blocks when the pack reports no cells. */
    val units: Spread,
    /** "cell" or "block". */
    val unitWord: String,
    val cells: Spread,
    val resistances: Spread,
    val blocks: Spread,
    val temps: Spread,
    val intakeC: Double?,
    val fanPct: Double?,
    val fanLevel: Double?,
    val auxVolts: Double?,
    val auxAmps: Double?,
    val auxC: Double?,
    val auxCapacity: Double?,
    val atRest: Boolean?,
    val findings: List<Finding>,
) {
    val hasData: Boolean get() = soc != null || voltage != null || cells.count > 0 || blocks.count > 0 || temps.count > 0
}

/**
 * The Battery tab's health check (docs/02 section B.3): cell balance,
 * resistance, temperature spread and cooling air, judged against the limits
 * for the pack type. Pure: it reads a [DashboardState] only.
 */
object BatteryAnalysis {

    /**
     * Pack type from the cell readings: a lithium-ion cell sits around
     * 3.2-4.2 V; a NiMH cell around 1.2 V, a NiMH module or block of several
     * cells well above 5 V. Null when nothing fits (or nothing was read).
     */
    fun detect(cells: Spread, blocks: Spread): BatteryChemistry? {
        val m = (if (cells.count > 0) cells.median else blocks.median) ?: return null
        return when {
            m in 2.5..4.6 -> BatteryChemistry.LITHIUM_ION
            m in 0.9..1.7 || m > 5.0 -> BatteryChemistry.NIMH
            else -> null
        }
    }

    /**
     * @param chosen the owner's choice in the settings; null = automatic.
     * @param tune the owner's changes to the limits of a pack type.
     */
    fun analyze(
        dash: DashboardState,
        profile: BatteryProfile,
        chosen: BatteryChemistry? = null,
        tune: (BatteryChemistry, BatteryLimits) -> BatteryLimits = { _, l -> l },
    ): BatteryReport {
        fun one(role: Role) = dash.values[role]
        fun all(role: Role) = Spread(dash.lists[role].orEmpty())
        val cells = all(Role.HV_CELL_VOLTAGE)
        val blocks = all(Role.HV_BLOCK_VOLTAGE)
        val resistances = all(Role.HV_CELL_RESISTANCE)
        val temps = all(Role.HV_BATTERY_TEMP)
        val detected = detect(cells, blocks)
        val (chemistry, source) = when {
            chosen != null -> chosen to ChemistrySource.SETTINGS
            profile.chemistry != null -> profile.chemistry to ChemistrySource.PROFILE
            detected != null -> detected to ChemistrySource.READINGS
            else -> null to null
        }
        val limits = chemistry?.let { tune(it, profile.limits(it)) }
        val voltage = dash.hvVolts
        val current = dash.hvAmps
        val atRest = if (limits != null && current != null) abs(current) < limits.restCurrentA else null
        // NiMH packs often report blocks only; a lithium-ion pack reports its cells.
        val units = if (cells.count >= 2) cells else blocks
        val unitWord = if (units === blocks || (units.median ?: 0.0) > 5.0) "block" else "cell"
        val intake = one(Role.HV_INTAKE_TEMP)

        val findings = buildList {
            if (chemistry == null && (cells.count > 0 || blocks.count > 0)) {
                add(Finding(FindingLevel.INFO, "Battery type unknown", "Choose it in Settings → Battery tab to get the health check."))
            }
            if (limits == null) return@buildList
            units.range?.takeIf { units.count >= 2 }?.let { r ->
                val mv = r * 1000
                val where = "$unitWord ${units.minIndex} lowest (%.3f V), $unitWord ${units.maxIndex} highest (%.3f V)".format(units.min, units.max)
                when {
                    atRest != true -> add(
                        Finding(
                            FindingLevel.INFO, "Balance %.0f mV (under load)".format(mv),
                            "$where. Balance is judged at rest, below %.0f A.".format(limits.restCurrentA),
                        ),
                    )
                    mv >= limits.spreadBadMv -> add(Finding(FindingLevel.CHECK, "Imbalance %.0f mV".format(mv), where))
                    mv >= limits.spreadWarnMv -> add(Finding(FindingLevel.WATCH, "Imbalance %.0f mV".format(mv), where))
                    else -> add(Finding(FindingLevel.OK, "Balanced (%.0f mV spread)".format(mv), where))
                }
            }
            val median = resistances.median
            val worst = resistances.max
            if (median != null && worst != null && median > 0 && resistances.count >= 2) {
                val ratio = worst / median
                val detail = "cell ${resistances.maxIndex}: %.2f mΩ, pack median %.2f mΩ (×%.2f)".format(worst, median, ratio)
                when {
                    ratio >= limits.resistanceRatioBad -> add(Finding(FindingLevel.CHECK, "High-resistance cell", detail))
                    ratio >= limits.resistanceRatioWarn -> add(Finding(FindingLevel.WATCH, "Raised-resistance cell", detail))
                    else -> add(Finding(FindingLevel.OK, "Cell resistance even", detail))
                }
            }
            temps.range?.takeIf { temps.count >= 2 }?.let { r ->
                val detail = "${temps.count} sensors, %.1f to %.1f °C".format(temps.min, temps.max)
                when {
                    r >= limits.tempSpreadBadC -> add(Finding(FindingLevel.CHECK, "Temperature spread %.1f °C".format(r), detail))
                    r >= limits.tempSpreadWarnC -> add(Finding(FindingLevel.WATCH, "Temperature spread %.1f °C".format(r), detail))
                    else -> add(Finding(FindingLevel.OK, "Temperature even (%.1f °C)".format(r), detail))
                }
            }
            val packMean = temps.mean
            if (intake != null && packMean != null) {
                val delta = packMean - intake
                val detail = "pack %.1f °C, cooling air %.1f °C".format(packMean, intake)
                if (delta >= limits.intakeDeltaWarnC) {
                    add(
                        Finding(
                            FindingLevel.WATCH, "Pack %.0f °C above its cooling air".format(delta),
                            "$detail. Normal just after a hard drive; if it stays, check the battery fan and its intake filter.",
                        ),
                    )
                } else {
                    add(Finding(FindingLevel.OK, "Cooling air OK", detail))
                }
            }
            val expected = profile.cells
            if (expected != null && cells.count > 0 && cells.count != expected) {
                add(Finding(FindingLevel.INFO, "${cells.count} of $expected cell voltages read", "Some cells have no value yet (slow request or short reply)."))
            }
        }

        return BatteryReport(
            chemistry = chemistry,
            chemistrySource = source,
            limits = limits,
            soc = dash.socPct,
            socMin = one(Role.HV_SOC_MIN),
            socMax = one(Role.HV_SOC_MAX),
            voltage = voltage,
            current = current,
            powerKw = dash.batteryPowerKw,
            chargeLimitKw = one(Role.HV_CHARGE_LIMIT),
            dischargeLimitKw = one(Role.HV_DISCHARGE_LIMIT),
            units = units,
            unitWord = unitWord,
            cells = cells,
            resistances = resistances,
            blocks = blocks,
            temps = temps,
            intakeC = intake,
            fanPct = dash.batteryFanPct,
            fanLevel = one(Role.HV_FAN_LEVEL),
            auxVolts = dash.auxVolts,
            auxAmps = one(Role.AUX_CURRENT),
            auxC = one(Role.AUX_TEMP),
            auxCapacity = one(Role.AUX_CAPACITY),
            atRest = atRest,
            findings = findings,
        )
    }
}
