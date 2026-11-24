package com.hybridguard.app.ui

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hybridguard.app.BuildConfig
import com.hybridguard.app.DataSource
import com.hybridguard.app.Monitor
import com.hybridguard.app.MonitorService
import com.hybridguard.app.Notifier
import com.hybridguard.app.PairedDevice
import com.hybridguard.app.Settings
import com.hybridguard.app.SimScenario
import com.hybridguard.app.ThemeMode
import com.hybridguard.app.TriggerMode
import com.hybridguard.app.app
import com.hybridguard.app.bluetoothGranted
import com.hybridguard.app.pairedDevices
import com.hybridguard.app.runtimePermissions
import com.hybridguard.data.Alarm
import com.hybridguard.data.AlarmCheck
import com.hybridguard.data.AlarmLevel
import com.hybridguard.data.AlarmOverride
import com.hybridguard.data.AlarmView
import com.hybridguard.data.BatteryChemistry
import com.hybridguard.data.BatteryLimits
import com.hybridguard.data.Role
import com.hybridguard.data.isEnabled
import com.hybridguard.data.label
import com.hybridguard.data.params
import com.hybridguard.data.with

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.app
    val s by app.settings.flow.collectAsStateWithLifecycle()
    fun set(change: (Settings) -> Settings) = app.settings.update(change)
    var pickAdapter by remember { mutableStateOf(false) }
    var pickCar by remember { mutableStateOf(false) }
    var editNotif by remember { mutableStateOf(false) }
    var editLog by remember { mutableStateOf(false) }
    var editCheck by remember { mutableStateOf<AlarmCheck?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Monitor.poke()
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        // ------------------------------------------------------------ adapter
        SectionTitle("Adapter and car")
        SettingRow(
            "OBD adapter",
            when (s.source) {
                DataSource.NONE -> "Not chosen yet: tap to choose your OBD adapter"
                DataSource.SIMULATED -> "Simulated car (no adapter, for testing)"
                DataSource.BLUETOOTH -> s.deviceName ?: s.deviceAddress
            },
            onClick = { if (!bluetoothGranted(context)) askPermissions.launch(runtimePermissions()); pickAdapter = true },
        )
        if (s.source == DataSource.SIMULATED) {
            ChoiceRow("Simulated scenario", s.simScenario, SimScenario.entries, { it.label }) { v -> set { it.copy(simScenario = v) } }
        }
        val index = remember { app.profileLoader.index() }
        val profiles = remember { index.profileIds.map { id -> id to runCatching { app.profileLoader.info(id) }.getOrNull() } }
        ChoiceRow(
            "Car model", s.manualProfileId, listOf<String?>(null) + profiles.map { it.first },
            { id -> if (id == null) "Automatic, from the car's VIN" else profiles.firstOrNull { it.first == id }?.second?.let { p -> p.name + (p.years?.let { y -> " (${y.first}–${y.last})" } ?: "") } ?: id },
        ) { v -> set { it.copy(manualProfileId = v, manualModelYear = null) } }
        if (s.manualProfileId != null) {
            val years = profiles.firstOrNull { it.first == s.manualProfileId }?.second?.years
            NumberRow(
                "Model year", (s.manualModelYear ?: years?.first ?: 2019).toDouble(), "",
                (years?.first ?: 1996).toDouble(), (years?.last ?: 2035).toDouble(),
                subtitle = s.manualModelYear?.toString() ?: "From the VIN",
            ) { v -> set { it.copy(manualModelYear = v.toInt()) } }
        }
        val lastVin = s.lastVin
        if (s.manualProfileId == null && lastVin != null) {
            var askYear by remember { mutableStateOf(false) }
            var yearShown by remember(lastVin) { mutableStateOf(app.profileSelector.select(lastVin).modelYear) }
            val owner = remember(lastVin, yearShown) { app.profileSelector.ownerModelYear(lastVin) }
            SettingRow(
                "Model year of your car",
                when {
                    owner != null -> "$owner (you chose it)"
                    yearShown != null -> "$yearShown (from the VIN)"
                    else -> "Not known from the VIN: tap to choose"
                },
                onClick = { askYear = true },
            )
            if (askYear) {
                val q = remember(lastVin) { modelYearQuestion(app, lastVin) }
                ModelYearDialog(q.copy(current = yearShown), onDismiss = { askYear = false }) { year ->
                    askYear = false
                    yearShown = year
                    Monitor.setModelYear(lastVin, year)
                }
            }
        }
        Text(
            "New Toyota models (Corolla 2020+, C-HR, ...) are added as vehicle profiles: the right one is picked from the VIN.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp),
        )

        // ------------------------------------------------------------ connection
        SectionTitle("Automatic connection")
        SwitchRow("Watch for the car", "Runs in the background with a notification", s.monitorEnabled) { on ->
            set { it.copy(monitorEnabled = on) }
            MonitorService.sync(context)
        }
        SwitchRow("Start when the phone starts", null, s.startOnBoot) { v -> set { it.copy(startOnBoot = v) } }
        ChoiceRow("When to look for the car", s.triggerMode, TriggerMode.entries, { it.label }) { v -> set { it.copy(triggerMode = v) } }
        SettingRow(
            "The car's Bluetooth",
            s.carDeviceName ?: if (s.triggerMode == TriggerMode.CAR_BLUETOOTH) "Tap to choose (stereo, hands-free)" else "Optional: tap to choose (stereo, hands-free)",
            onClick = { pickCar = true },
        )
        if (s.carDeviceAddress != null) {
            NumberRow(
                "Look every (car's Bluetooth connected)", s.carLinkIntervalSec.toDouble(), "s", 2.0, 120.0,
                subtitle = "${s.carLinkIntervalSec} s until the car is READY: the stereo connects a few seconds before",
            ) { v -> set { it.copy(carLinkIntervalSec = v.toInt()) } }
        }
        SwitchRow("Look when the phone starts charging", "Useful with a car charger", s.tryOnCharging) { v -> set { it.copy(tryOnCharging = v) } }
        NumberRow("Look every (adapter asleep)", s.wakeIntervalSec.toDouble(), "s", 5.0, 600.0) { v -> set { it.copy(wakeIntervalSec = v.toInt()) } }
        NumberRow("Look every (car parked, adapter awake)", s.parkedIntervalSec.toDouble(), "s", 10.0, 1800.0) { v -> set { it.copy(parkedIntervalSec = v.toInt()) } }
        SwitchRow(
            "Wait for READY by the 12 V voltage",
            "Reads only the adapter's voltage until the car charges its 12 V battery: nothing is sent to the parked car",
            s.voltageGate,
        ) { v -> set { it.copy(voltageGate = v) } }
        if (s.voltageGate) {
            NumberRow("READY from", s.readyVolts, "V", 12.6, 14.5) { v -> set { it.copy(readyVolts = v) } }
            NumberRow(
                "After the car goes quiet: READY again on a rise of", s.restartRiseVolts, "V", 0.1, 1.5,
                subtitle = "%.1f V: the 12 V stays high for minutes after a drive, so the car is read again only when the voltage rises".format(s.restartRiseVolts),
            ) { v -> set { it.copy(restartRiseVolts = v) } }
            NumberRow(
                "After the car goes quiet: try again anyway after", s.afterOffHoldMin.toDouble(), "min", 1.0, 60.0,
                subtitle = "${s.afterOffHoldMin} min (sooner when the car's Bluetooth connects, the phone starts charging, or Check now)",
            ) { v -> set { it.copy(afterOffHoldMin = v.toInt()) } }
        }

        // ------------------------------------------------------------ alarms
        SectionTitle("Alarms")
        SwitchRow("Alarms on", null, s.alarmsEnabled) { v -> set { it.copy(alarmsEnabled = v) } }
        val rules = remember(s.manualProfileId, s.lastProfileId) { app.profileAlarmRules() }
        if (rules == null) {
            Text("This car model has no alarm checks.", Modifier.padding(16.dp))
        } else if (s.alarmsEnabled) {
            rules.checks.groupBy { it.group }.forEach { (group, checks) ->
                Text(group, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                checks.forEach { base ->
                    val o = s.alarmOverrides[base.id]
                    val enabled = isEnabled(base, s.alarmOverrides)
                    val check = o?.let(base::with) ?: base
                    SettingRow(
                        check.title,
                        (if (check.level == AlarmLevel.STOP) "STOP · " else "Caution · ") + summary(check) + if (o?.isEmpty == false) " · changed" else "",
                        onClick = { editCheck = base },
                    ) {
                        Switch(enabled, onCheckedChange = { on ->
                            set { st -> st.copy(alarmOverrides = st.alarmOverrides + (base.id to (o ?: AlarmOverride()).copy(enabled = on))) }
                        })
                    }
                }
            }
            rules.notWatched.forEach { Text("${it.group}: not watched (${it.why})", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        }
        SwitchRow("Sound", null, s.alarmSound) { v -> set { it.copy(alarmSound = v) } }
        SwitchRow("Loud STOP alarm", "On the alarm volume, heard even with the ringer silent", s.loudStopAlarm) { v -> set { it.copy(loudStopAlarm = v) } }
        SwitchRow("Vibrate", null, s.alarmVibrate) { v -> set { it.copy(alarmVibrate = v) } }
        SwitchRow("Say the alarm", "Spoken like navigation: through the car speakers when connected", s.alarmVoice) { v -> set { it.copy(alarmVoice = v) } }
        SwitchRow("Pause the music during an alarm", "It resumes after the warning", s.pauseMusic) { v -> set { it.copy(pauseMusic = v) } }
        NumberRow(
            "STOP alarm volume", s.stopVolumePercent.toDouble(), "%", 0.0, 100.0,
            subtitle = "${s.stopVolumePercent} % of the alarm volume at least (put back after); 0 = leave it",
        ) { v -> set { it.copy(stopVolumePercent = v.toInt()) } }
        NumberRow(
            "STOP voice volume", s.voiceVolumePercent.toDouble(), "%", 0.0, 100.0,
            subtitle = "${s.voiceVolumePercent} % of the media volume at least (the car speakers too); 0 = leave it",
        ) { v -> set { it.copy(voiceVolumePercent = v.toInt()) } }
        NumberRow("Repeat a STOP alarm until OK, every", s.stopRepeatSec.toDouble(), "s", 10.0, 600.0) { v -> set { it.copy(stopRepeatSec = v.toInt()) } }
        NumberRow("Remind after OK while it lasts, every", s.remindMin.toDouble(), "min", 1.0, 120.0) { v -> set { it.copy(remindMin = v.toInt()) } }
        SwitchRow("Warn when the connection is lost while driving", null, s.lostAlert) { v -> set { it.copy(lostAlert = v) } }
        if (s.lostAlert) NumberRow("Lost for at least", s.lostAfterSec.toDouble(), "s", 5.0, 600.0) { v -> set { it.copy(lostAfterSec = v.toInt()) } }
        SwitchRow("Warn when a pump value stops arriving", "While READY: its alarms could not work", s.missingAlert) { v -> set { it.copy(missingAlert = v) } }
        if (s.missingAlert) NumberRow("Missing for at least", s.missingAfterSec.toDouble(), "s", 10.0, 600.0) { v -> set { it.copy(missingAfterSec = v.toInt()) } }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(onClick = {
                val a = Alarm("test", AlarmLevel.STOP, "Test alarm", "This is only a test: tap OK", "", SystemClock.elapsedRealtime())
                Notifier.announce(context, a, AlarmView(listOf(a), unacknowledged = true, announce = a), s)
            }) { Text("Test the alarm") }
        }
        SettingRow("Alarm notification settings", "E.g. let STOP alarms through Do Not Disturb", onClick = {
            runCatching {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        })

        // ------------------------------------------------------------ notification
        SectionTitle("Notification")
        SettingRow("Values in the notification", s.notifRoles.joinToString { it.label }.ifEmpty { "None" }, onClick = { editNotif = true })
        NumberRow("Update every", s.notifIntervalSec.toDouble(), "s", 1.0, 60.0) { v -> set { it.copy(notifIntervalSec = v.toInt()) } }
        SettingRow(
            "Notification while waiting",
            "Turn \"Waiting for the car\" off to hide it while the car is off. The app keeps watching; live values and alarms still show.",
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, Notifier.CHANNEL_WAITING),
                    )
                }
            },
        )

        // ------------------------------------------------------------ logs
        SectionTitle("Sessions and charts")
        SettingRow("Values saved and charted", "${s.logRoles.size} values", onClick = { editLog = true })
        NumberRow("Save a row every", s.logIntervalSec.toDouble(), "s", 1.0, 60.0) { v -> set { it.copy(logIntervalSec = v.toInt()) } }
        NumberRow("Live chart window", s.liveWindowMin.toDouble(), "min", 1.0, 60.0) { v -> set { it.copy(liveWindowMin = v.toInt()) } }
        NumberRow("Keep sessions for", s.keepDays.toDouble(), "days", 1.0, 3650.0) { v -> set { it.copy(keepDays = v.toInt()) } }
        NumberRow("Keep at most", s.maxSessions.toDouble(), "sessions", 1.0, 10_000.0) { v -> set { it.copy(maxSessions = v.toInt()) } }
        NumberRow("Do not keep sessions shorter than", s.minSessionSec.toDouble(), "s", 0.0, 3600.0, subtitle = "${s.minSessionSec} s (sessions with an alarm are always kept)") { v -> set { it.copy(minSessionSec = v.toInt()) } }
        SwitchRow("Also keep the raw adapter replies", "For finding problems; larger files", s.rawLog) { v -> set { it.copy(rawLog = v) } }
        SettingRow("Delete all sessions", null, onClick = { confirmWipe = true })

        // ------------------------------------------------------------ battery tab
        SectionTitle("Battery tab")
        val batteryProfile = remember(s.lastProfileId, s.manualProfileId) { app.profileBattery() }
        val chemistryAuto = batteryProfile.chemistry?.let { "From the car profile (${it.label})" } ?: "Automatic (from the cell voltages)"
        ChoiceRow("Battery type", s.batteryChemistry, listOf(null) + BatteryChemistry.entries, { it?.label ?: chemistryAuto }) { v ->
            set { it.copy(batteryChemistry = v) }
        }
        Text(
            "The tab reads the cells only while it is open. Limits: guide values for the health check, not Toyota limits.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        val shownChemistries = s.batteryChemistry?.let(::listOf) ?: batteryProfile.chemistry?.let(::listOf) ?: BatteryChemistry.entries
        for (chem in shownChemistries) {
            val limits = s.batteryLimits(chem, batteryProfile.limits(chem))
            val changed = s.batteryLimits[chem.key].orEmpty()
            Text(
                chem.label + " limits", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
            )
            for (f in BatteryLimits.FIELDS) {
                val v = limits.value(f.key)
                NumberRow(f.label, v, f.unit, f.min, f.max, subtitle = num(v, f.unit) + if (f.key in changed) " (changed)" else "") { nv ->
                    set { cur ->
                        val mine = cur.batteryLimits[chem.key].orEmpty() + (f.key to nv)
                        cur.copy(batteryLimits = cur.batteryLimits + (chem.key to mine))
                    }
                }
            }
            if (changed.isNotEmpty()) {
                SettingRow("Back to the profile's ${chem.label} limits", null, onClick = { set { it.copy(batteryLimits = it.batteryLimits - chem.key) } })
            }
        }

        // ------------------------------------------------------------ look
        SectionTitle("Appearance")
        ChoiceRow("Theme", s.theme, ThemeMode.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { v -> set { it.copy(theme = v) } }
        SettingRow("Version", BuildConfig.VERSION_NAME)
    }

    if (pickAdapter) {
        DevicePicker(
            title = "Choose the OBD adapter",
            devices = remember { pairedDevices(context) },
            withSimulated = true,
            onDismiss = { pickAdapter = false },
        ) { d ->
            pickAdapter = false
            set { if (d == null) it.copy(source = DataSource.SIMULATED) else it.copy(source = DataSource.BLUETOOTH, deviceAddress = d.address, deviceName = d.name) }
            MonitorService.sync(context)
            Monitor.poke()
        }
    }
    if (pickCar) {
        DevicePicker(
            title = "The car's Bluetooth",
            devices = remember { pairedDevices(context)?.sortedByDescending { it.likelyCar } },
            withSimulated = false,
            onDismiss = { pickCar = false },
        ) { d ->
            pickCar = false
            if (d != null) set { it.copy(carDeviceAddress = d.address, carDeviceName = d.name) }
        }
    }
    val numeric = Settings.NUMERIC_ROLES
    if (editNotif) {
        MultiChoiceDialog("Values in the notification", s.notifRoles, numeric, { it.label }, { editNotif = false }) { v ->
            editNotif = false
            set { it.copy(notifRoles = v) }
        }
    }
    if (editLog) {
        MultiChoiceDialog("Values saved and charted", s.logRoles, numeric, { it.label }, { editLog = false }) { v ->
            editLog = false
            set { it.copy(logRoles = v) }
        }
    }
    editCheck?.let { base ->
        CheckEditor(base, s.alarmOverrides[base.id], onDismiss = { editCheck = null }) { o ->
            editCheck = null
            set { st -> st.copy(alarmOverrides = if (o.isEmpty) st.alarmOverrides - base.id else st.alarmOverrides + (base.id to o)) }
        }
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all sessions?") },
            text = { Text("Every saved session (values and alarms) is removed from the phone. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    app.archive.list().forEach { app.archive.delete(it.id) }
                    confirmWipe = false
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

private fun summary(c: AlarmCheck): String = when (c) {
    is AlarmCheck.PumpLow -> listOfNotNull(
        c.below?.let { "below ${num(it, c.unit)}" },
        c.belowShareOfTarget?.let { "below ${num(it * 100)} % of asked" },
    ).joinToString(" or ") + " for ${c.forMs / 1000} s"
    is AlarmCheck.TooHot -> "from ${num(c.from, c.unit)} for ${c.forMs / 1000} s"
    is AlarmCheck.TooLow -> "below ${num(c.below, c.unit)} for ${c.forMs / 1000} s"
    is AlarmCheck.RisingFast -> "above ${num(c.from, c.unit)}, rising ${num(c.perMinute)} ${c.unit}/min"
}

/** Level and numbers of one check; Reset goes back to the car profile's values. */
@Composable
private fun CheckEditor(base: AlarmCheck, current: AlarmOverride?, onDismiss: () -> Unit, onDone: (AlarmOverride) -> Unit) {
    var o by remember { mutableStateOf(current ?: AlarmOverride()) }
    val check = base.with(o)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(base.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(base.advice, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    FilterChip(check.level == AlarmLevel.CAUTION, onClick = { o = o.copy(level = AlarmLevel.CAUTION) }, label = { Text("Caution") })
                    Text("  ")
                    FilterChip(check.level == AlarmLevel.STOP, onClick = { o = o.copy(level = AlarmLevel.STOP) }, label = { Text("STOP") })
                }
                check.params().forEach { p ->
                    val default = base.params().firstOrNull { it.key == p.key }?.value
                    NumberRow(
                        p.label, p.value, p.unit, p.min, p.max,
                        subtitle = num(p.value, p.unit) + if (default != null && default != p.value) " (car profile: ${num(default, p.unit)})" else "",
                    ) { v -> o = o.copy(values = o.values + (p.key to v)) }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(o) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onDone(AlarmOverride(enabled = current?.enabled)) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Paired devices only (pair the OBD adapter in the phone's Bluetooth settings first). */
@Composable
private fun DevicePicker(
    title: String,
    devices: List<PairedDevice>?,
    withSimulated: Boolean,
    onDismiss: () -> Unit,
    onPick: (PairedDevice?) -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                if (devices == null) item { Text("Allow Nearby devices (Bluetooth) to see the paired devices.") }
                else if (devices.isEmpty()) item { Text("No paired devices. Pair the OBD adapter in the phone's Bluetooth settings first (PIN usually 1234 or 0000).") }
                items(devices.orEmpty()) { d ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 10.dp)) {
                        Text(d.name + if (d.likelyObd) "  · OBD adapter" else "")
                        Text(d.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (withSimulated) item {
                    Column(Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 10.dp)) {
                        Text("Simulated car")
                        Text("No car needed: for trying the app (and the emulator)", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    TextButton(onClick = { runCatching { context.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) } }) {
                        Text("Open Bluetooth settings")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
