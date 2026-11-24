package com.hybridguard.app

import android.content.SharedPreferences
import com.hybridguard.data.AlarmLevel
import com.hybridguard.data.AlarmOverride
import com.hybridguard.data.BatteryChemistry
import com.hybridguard.data.BatteryLimits
import com.hybridguard.data.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class DataSource { NONE, BLUETOOTH, SIMULATED }

/** When the app looks for the car. */
enum class TriggerMode(val label: String) {
    /** Every few seconds, all day: simplest, costs some battery. */
    ALWAYS("Always, every few seconds"),

    /** Only while the phone is connected to the car's own Bluetooth (stereo, hands-free, Android Auto). */
    CAR_BLUETOOTH("Only while connected to the car's Bluetooth"),
}

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** What the simulated car does (for testing on the emulator, no car needed). */
enum class SimScenario(val label: String) {
    NORMAL("Normal drive"),
    INVERTER_PUMP_FAILS("Inverter water pump fails after 1 min"),
    ENGINE_PUMP_FAILS("Engine water pump fails after 1 min"),
    OVERHEAT("Engine coolant overheats after 2 min"),
    WEAK_CELL("One HV battery cell weakens after 30 s"),
}

/**
 * Everything the owner can change. Stored as one JSON object; unknown or
 * missing keys fall back to the defaults here, so new settings never break
 * an older install.
 */
data class Settings(
    // ---- Adapter
    val source: DataSource = DataSource.NONE,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val simScenario: SimScenario = SimScenario.NORMAL,
    /**
     * Car model: null = chosen by the car's VIN (the default), else a vehicle
     * profile id. More Toyota models are added as profile folders
     * (core-data resources/vehicles), with no code change.
     */
    val manualProfileId: String? = null,
    /** Model year for [manualProfileId]; null = from the VIN. */
    val manualModelYear: Int? = null,

    // ---- Automatic connection
    /** Watch for the car in the background (the persistent notification). */
    val monitorEnabled: Boolean = true,
    val startOnBoot: Boolean = true,
    val triggerMode: TriggerMode = TriggerMode.ALWAYS,
    val carDeviceAddress: String? = null,
    val carDeviceName: String? = null,
    /** Also look at once when the phone starts charging (a car charger). */
    val tryOnCharging: Boolean = true,
    /** Seconds between tries while the adapter does not answer (asleep or out of range). */
    val wakeIntervalSec: Int = 20,
    /** Seconds between tries while the adapter answers but the car is not READY. */
    val parkedIntervalSec: Int = 60,
    /** Seconds between tries while the phone is connected to the car's Bluetooth and the car is not READY yet. */
    val carLinkIntervalSec: Int = 5,
    /** Start reading the car only when the 12 V system shows the car is READY (charging voltage). */
    val voltageGate: Boolean = true,
    val readyVolts: Double = 13.2,

    // ---- Alarms
    val alarmsEnabled: Boolean = true,
    val alarmOverrides: Map<String, AlarmOverride> = emptyMap(),
    val alarmSound: Boolean = true,
    val alarmVibrate: Boolean = true,
    /** Say the alarm (through the car speakers when the phone is connected to the car). */
    val alarmVoice: Boolean = true,
    /** STOP alarms also ring on the alarm volume, even with the ringer silent. */
    val loudStopAlarm: Boolean = true,
    /** Pause the music (or anything else playing) while an alarm sounds and speaks; it resumes after. */
    val pauseMusic: Boolean = true,
    /** During a STOP alarm the alarm volume is raised to at least this share of its maximum, then put back. */
    val stopVolumePercent: Int = 100,
    /** During a STOP alarm the spoken warning's volume (media) is raised to at least this share, then put back. 0 = leave it. */
    val voiceVolumePercent: Int = 80,
    val stopRepeatSec: Int = 30,
    val remindMin: Int = 5,
    /** Warn when the connection is lost while driving. */
    val lostAlert: Boolean = true,
    val lostAfterSec: Int = 30,
    /** Warn when a watched value (a pump) stops arriving while READY. */
    val missingAlert: Boolean = true,
    val missingAfterSec: Int = 60,

    // ---- Notification
    val notifRoles: List<Role> = DEFAULT_NOTIF_ROLES,
    val notifIntervalSec: Int = 2,

    // ---- Logs and charts
    val logRoles: List<Role> = DEFAULT_LOG_ROLES,
    val logIntervalSec: Int = 1,
    val keepDays: Int = 365,
    val maxSessions: Int = 500,
    /** Shorter sessions without alarms are not kept (e.g. moving the car in the drive). */
    val minSessionSec: Int = 60,
    /** Also keep the raw adapter replies (for finding problems, larger files). */
    val rawLog: Boolean = false,
    val liveWindowMin: Int = 10,

    // ---- Battery tab
    /** Pack type of the car; null = from the car profile, else told from the cell voltages. */
    val batteryChemistry: BatteryChemistry? = null,
    /** The owner's health-check limits per pack type ([BatteryChemistry.key] → limit key → value); missing = the profile's. */
    val batteryLimits: Map<String, Map<String, Double>> = emptyMap(),

    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Profile of the last car seen: its alarm checks are shown in the settings. */
    val lastProfileId: String? = null,
    /** VIN of the last car seen over Bluetooth: its model year can be set in the settings. */
    val lastVin: String? = null,
) {
    val hasAdapter: Boolean get() = source == DataSource.SIMULATED || (source == DataSource.BLUETOOTH && deviceAddress != null)

    /** Roles that must be polled beyond the main ones: what the notification and the log show. */
    val shownRoles: Set<Role> get() = (notifRoles + logRoles).toSet()

    /** [limits] for [chemistry] with the owner's changes. */
    fun batteryLimits(chemistry: BatteryChemistry, limits: BatteryLimits): BatteryLimits =
        limits.withOverrides(batteryLimits[chemistry.key].orEmpty())

    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source.name)
        put("deviceAddress", deviceAddress)
        put("deviceName", deviceName)
        put("simScenario", simScenario.name)
        put("manualProfileId", manualProfileId)
        put("manualModelYear", manualModelYear)
        put("monitorEnabled", monitorEnabled)
        put("startOnBoot", startOnBoot)
        put("triggerMode", triggerMode.name)
        put("carDeviceAddress", carDeviceAddress)
        put("carDeviceName", carDeviceName)
        put("tryOnCharging", tryOnCharging)
        put("wakeIntervalSec", wakeIntervalSec)
        put("parkedIntervalSec", parkedIntervalSec)
        put("carLinkIntervalSec", carLinkIntervalSec)
        put("voltageGate", voltageGate)
        put("readyVolts", readyVolts)
        put("alarmsEnabled", alarmsEnabled)
        put("alarmOverrides", JSONObject().apply {
            alarmOverrides.forEach { (id, o) ->
                put(id, JSONObject().apply {
                    o.enabled?.let { put("enabled", it) }
                    o.level?.let { put("level", it.name) }
                    put("values", JSONObject(o.values))
                })
            }
        })
        put("alarmSound", alarmSound)
        put("alarmVibrate", alarmVibrate)
        put("alarmVoice", alarmVoice)
        put("loudStopAlarm", loudStopAlarm)
        put("pauseMusic", pauseMusic)
        put("stopVolumePercent", stopVolumePercent)
        put("voiceVolumePercent", voiceVolumePercent)
        put("stopRepeatSec", stopRepeatSec)
        put("remindMin", remindMin)
        put("lostAlert", lostAlert)
        put("lostAfterSec", lostAfterSec)
        put("missingAlert", missingAlert)
        put("missingAfterSec", missingAfterSec)
        put("notifRoles", JSONArray(notifRoles.map { it.key }))
        put("notifIntervalSec", notifIntervalSec)
        put("logRoles", JSONArray(logRoles.map { it.key }))
        put("logIntervalSec", logIntervalSec)
        put("keepDays", keepDays)
        put("maxSessions", maxSessions)
        put("minSessionSec", minSessionSec)
        put("rawLog", rawLog)
        put("liveWindowMin", liveWindowMin)
        put("batteryChemistry", batteryChemistry?.key)
        put("batteryLimits", JSONObject().apply {
            batteryLimits.forEach { (chem, values) -> put(chem, JSONObject(values)) }
        })
        put("theme", theme.name)
        put("lastProfileId", lastProfileId)
        put("lastVin", lastVin)
        put("rolesVersion", ROLES_VERSION)
    }

    companion object {
        val DEFAULT_NOTIF_ROLES = listOf(
            Role.ENGINE_WATER_PUMP, Role.INVERTER_PUMP, Role.COOLANT_TEMP, Role.INVERTER_COOLANT_TEMP,
        )
        val DEFAULT_LOG_ROLES = listOf(
            Role.ENGINE_WATER_PUMP, Role.ENGINE_WATER_PUMP_TARGET, Role.INVERTER_PUMP,
            Role.COOLANT_TEMP, Role.INVERTER_COOLANT_TEMP,
            Role.MOTOR_INVERTER_TEMP, Role.GENERATOR_INVERTER_TEMP,
            Role.HV_BATTERY_TEMP, Role.SOC, Role.AUX_VOLTAGE,
            Role.SPEED, Role.ENGINE_RPM, Role.AMBIENT_TEMP,
        )

        /** Roles that make sense as numbers in a log, chart or notification (the Battery tab's own are read only while it is open). */
        val NUMERIC_ROLES: List<Role> = Role.entries.filter {
            !it.battery && it != Role.GEAR && it != Role.READY && it != Role.ENGINE_TORQUE_REFERENCE && it != Role.ENGINE_TORQUE_FRICTION
        }

        /** 2: oil temperature dropped (2026-11-23, the Corolla gives none). */
        private const val ROLES_VERSION = 2

        fun fromJson(o: JSONObject): Settings {
            val d = Settings()
            val oldRoles = o.optInt("rolesVersion", 1) < 2
            fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
            fun int(k: String, def: Int) = if (o.has(k)) o.optInt(k, def) else def
            fun dbl(k: String, def: Double) = if (o.has(k)) o.optDouble(k, def) else def
            fun bool(k: String, def: Boolean) = if (o.has(k)) o.optBoolean(k, def) else def
            fun roles(k: String, def: List<Role>): List<Role> {
                val a = o.optJSONArray(k) ?: return def
                return (0 until a.length()).mapNotNull { Role.fromKey(a.getString(it)) }
                    .filterNot { (oldRoles && it == Role.OIL_TEMP) || it.battery }
            }
            fun <T : Enum<T>> enum(k: String, def: T, values: Array<T>): T =
                str(k)?.let { name -> values.firstOrNull { it.name == name } } ?: def
            val overrides = mutableMapOf<String, AlarmOverride>()
            o.optJSONObject("alarmOverrides")?.let { all ->
                all.keys().forEach { id ->
                    val e = all.getJSONObject(id)
                    val values = mutableMapOf<String, Double>()
                    e.optJSONObject("values")?.let { v -> v.keys().forEach { values[it] = v.getDouble(it) } }
                    overrides[id] = AlarmOverride(
                        enabled = if (e.has("enabled")) e.getBoolean("enabled") else null,
                        level = e.optString("level").takeIf { it.isNotEmpty() }?.let { n -> AlarmLevel.entries.firstOrNull { it.name == n } },
                        values = values,
                    )
                }
            }
            val batteryLimits = mutableMapOf<String, Map<String, Double>>()
            o.optJSONObject("batteryLimits")?.let { all ->
                all.keys().forEach { chem ->
                    val v = all.optJSONObject(chem) ?: return@forEach
                    batteryLimits[chem] = v.keys().asSequence().associateWith { v.getDouble(it) }
                }
            }
            return Settings(
                source = enum("source", d.source, DataSource.entries.toTypedArray()),
                deviceAddress = str("deviceAddress"),
                deviceName = str("deviceName"),
                simScenario = enum("simScenario", d.simScenario, SimScenario.entries.toTypedArray()),
                manualProfileId = str("manualProfileId"),
                manualModelYear = if (o.has("manualModelYear") && !o.isNull("manualModelYear")) o.getInt("manualModelYear") else null,
                monitorEnabled = bool("monitorEnabled", d.monitorEnabled),
                startOnBoot = bool("startOnBoot", d.startOnBoot),
                triggerMode = enum("triggerMode", d.triggerMode, TriggerMode.entries.toTypedArray()),
                carDeviceAddress = str("carDeviceAddress"),
                carDeviceName = str("carDeviceName"),
                tryOnCharging = bool("tryOnCharging", d.tryOnCharging),
                wakeIntervalSec = int("wakeIntervalSec", d.wakeIntervalSec),
                parkedIntervalSec = int("parkedIntervalSec", d.parkedIntervalSec),
                carLinkIntervalSec = int("carLinkIntervalSec", d.carLinkIntervalSec),
                voltageGate = bool("voltageGate", d.voltageGate),
                readyVolts = dbl("readyVolts", d.readyVolts),
                alarmsEnabled = bool("alarmsEnabled", d.alarmsEnabled),
                alarmOverrides = overrides,
                alarmSound = bool("alarmSound", d.alarmSound),
                alarmVibrate = bool("alarmVibrate", d.alarmVibrate),
                alarmVoice = bool("alarmVoice", d.alarmVoice),
                loudStopAlarm = bool("loudStopAlarm", d.loudStopAlarm),
                pauseMusic = bool("pauseMusic", d.pauseMusic),
                stopVolumePercent = int("stopVolumePercent", d.stopVolumePercent),
                voiceVolumePercent = int("voiceVolumePercent", d.voiceVolumePercent),
                stopRepeatSec = int("stopRepeatSec", d.stopRepeatSec),
                remindMin = int("remindMin", d.remindMin),
                lostAlert = bool("lostAlert", d.lostAlert),
                lostAfterSec = int("lostAfterSec", d.lostAfterSec),
                missingAlert = bool("missingAlert", d.missingAlert),
                missingAfterSec = int("missingAfterSec", d.missingAfterSec),
                notifRoles = roles("notifRoles", d.notifRoles),
                notifIntervalSec = int("notifIntervalSec", d.notifIntervalSec),
                logRoles = roles("logRoles", d.logRoles),
                logIntervalSec = int("logIntervalSec", d.logIntervalSec),
                keepDays = int("keepDays", d.keepDays),
                maxSessions = int("maxSessions", d.maxSessions),
                minSessionSec = int("minSessionSec", d.minSessionSec),
                rawLog = bool("rawLog", d.rawLog),
                liveWindowMin = int("liveWindowMin", d.liveWindowMin),
                batteryChemistry = BatteryChemistry.fromKey(str("batteryChemistry")),
                batteryLimits = batteryLimits,
                theme = enum("theme", d.theme, ThemeMode.entries.toTypedArray()),
                lastProfileId = str("lastProfileId"),
                lastVin = str("lastVin"),
            )
        }
    }
}

/** The saved [Settings], observable. */
class SettingsStore(private val prefs: SharedPreferences) {
    private val _settings = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    @Synchronized
    fun update(change: (Settings) -> Settings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        prefs.edit().putString(KEY, next.toJson().toString()).apply()
        _settings.value = next
    }

    private fun load(): Settings =
        prefs.getString(KEY, null)?.let { runCatching { Settings.fromJson(JSONObject(it)) }.getOrNull() } ?: Settings()

    private companion object {
        const val KEY = "settings.v1"
    }
}
