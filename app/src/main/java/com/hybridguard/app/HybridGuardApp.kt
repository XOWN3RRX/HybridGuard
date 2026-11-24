package com.hybridguard.app

import android.app.Application
import android.content.Context
import com.hybridguard.data.AlarmRules
import com.hybridguard.data.BatteryProfile
import com.hybridguard.data.FileVehicleStore
import com.hybridguard.data.ProfileLoader
import com.hybridguard.data.ProfileSelector
import java.io.File
import java.util.Calendar
import kotlin.concurrent.thread

class HybridGuardApp : Application() {

    val profileLoader by lazy { ProfileLoader() }
    val vehicleStore by lazy { FileVehicleStore(File(filesDir, "vehicles")) }
    val profileSelector by lazy { ProfileSelector(profileLoader, vehicleStore) { Calendar.getInstance().get(Calendar.YEAR) } }
    val settings by lazy { SettingsStore(getSharedPreferences("settings", Context.MODE_PRIVATE)) }
    val archive by lazy { SessionArchive(File(filesDir, "sessions").apply { mkdirs() }) }
    val rawLogDir: File get() = File(filesDir, "raw").apply { mkdirs() }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        thread(name = "recover") { runCatching { archive.recover(settings.value.minSessionSec * 1000L) } }
        Monitor.init(this)
        if (settings.value.monitorEnabled) MonitorService.sync(this)
    }

    /**
     * The car model whose alarms the settings show: the one set by hand, the
     * last car seen, or the first car profile (the generic SAE profile has no
     * pump alarms).
     */
    fun settingsProfileId(): String {
        val s = settings.value
        val index = profileLoader.index()
        return s.manualProfileId ?: s.lastProfileId ?: index.profileIds.firstOrNull { it != index.fallbackId } ?: index.fallbackId
    }

    /** The profile's own alarm checks (before the owner's settings). */
    fun profileAlarmRules(profileId: String = settingsProfileId()): AlarmRules? =
        runCatching { profileLoader.alarmRules(profileId) }.getOrNull()

    /** The profile's battery facts and health-check limits (before the owner's settings). */
    fun profileBattery(profileId: String = settingsProfileId()): BatteryProfile =
        runCatching { profileLoader.batteryProfile(profileId) }.getOrDefault(BatteryProfile())
}

val Context.app: HybridGuardApp get() = applicationContext as HybridGuardApp
