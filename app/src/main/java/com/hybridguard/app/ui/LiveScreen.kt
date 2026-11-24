package com.hybridguard.app.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.SystemClock
import com.hybridguard.app.DataSource
import com.hybridguard.app.Monitor
import com.hybridguard.app.MonitorPhase
import com.hybridguard.app.MonitorService
import com.hybridguard.app.Notifier
import com.hybridguard.app.Simulator
import com.hybridguard.app.app
import com.hybridguard.data.AlarmLevel
import com.hybridguard.data.Role
import com.hybridguard.data.label
import com.hybridguard.data.withOverrides
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun LiveScreen(onOpenSettings: () -> Unit, onOpenSession: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val st by Monitor.status.collectAsStateWithLifecycle()
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val history by Monitor.history.collectAsStateWithLifecycle()
    val simOn by Simulator.carOn.collectAsStateWithLifecycle()
    val profileId = st.session?.profile?.id ?: app.settingsProfileId()
    val rules = remember(profileId, s.alarmOverrides, s.alarmsEnabled) {
        if (s.alarmsEnabled) app.profileAlarmRules(profileId)?.withOverrides(s.alarmOverrides) else null
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ---- Alarms first: they matter most.
        val view = st.alarms
        for (a in view.alarms) {
            val stop = a.level == AlarmLevel.STOP
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (stop) StatusColors.stop else StatusColors.caution, contentColor = Color.White),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (stop) "STOP" else "CAUTION", fontWeight = FontWeight.Black, fontSize = 14.sp)
                    Text(a.title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(a.advice, fontSize = 16.sp)
                    if (a.detail.isNotBlank()) Text(a.detail, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
                    if (view.unacknowledged && a == view.top) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { Monitor.acknowledge() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        ) { Text("OK, seen") }
                    }
                }
            }
        }

        // ---- What the app is doing.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val title = when {
                            st.phase == MonitorPhase.MONITORING && view.alarms.isEmpty() -> if (st.ready) "READY · all OK" else "Monitoring · all OK"
                            else -> st.phase.label
                        }
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        st.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Switch(s.monitorEnabled, onCheckedChange = { on ->
                        app.settings.update { it.copy(monitorEnabled = on) }
                        MonitorService.sync(context)
                    })
                }
                val details = buildList {
                    st.volts?.let { add("12 V: %.1f V".format(it) + (st.voltsEpochMs?.let { t -> " (" + ago(now - t) + ")" } ?: "")) }
                    st.session?.profile?.let { p -> add(p.name + (st.session?.modelYear?.let { " $it" } ?: "") + (if (st.session?.modelYearAssumed == true) " (year assumed: set it in Settings)" else "")) }
                    st.session?.let { if (it.requestsPerSecond > 0) add("%.0f readings/s".format(it.requestsPerSecond)) }
                    st.sessionStartEpochMs?.let { add("Session since " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) }
                    st.awakeParkedSinceEpochMs?.let { if (now - it > 30 * 60_000L) add("The adapter has stayed awake for ${(now - it) / 60_000} min with the car parked: it may not be going to sleep. Try a longer 'parked' interval.") }
                    if (st.phase != MonitorPhase.MONITORING) st.nextCheckEpochMs?.let { if (it > now) add("Next look in ${(it - now) / 1000 + 1} s") }
                }
                details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (!s.hasAdapter) {
                        Button(onClick = onOpenSettings) { Text("Choose the adapter") }
                    } else if (s.monitorEnabled && st.phase != MonitorPhase.MONITORING) {
                        OutlinedButton(onClick = { Monitor.poke(carEvent = true) }) { Text("Check now") }
                    }
                }
                if (s.source == DataSource.SIMULATED) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Simulated car (${s.simScenario.label})", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        FilterChip(simOn, onClick = { Simulator.switch(!simOn, SystemClock.elapsedRealtime()); Monitor.poke(carEvent = true) }, label = {
                            Text(if (simOn) "Car ON (tap to switch off)" else "Car OFF (tap to start)")
                        })
                    }
                }
            }
        }
        BatteryOptimizationHint()

        // ---- The values.
        val dash = st.dashboard
        if (dash != null) {
            val alarmLevels = view.alarms.associate { it.id to it.level }
            val roles = (s.notifRoles + s.logRoles).distinct()
            roles.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { role ->
                        ValueTile(
                            role, dash.values[role], dash.units[role], levelOf(role, alarmLevels, rules), big = role in s.notifRoles,
                            notGiven = role in st.notGiven, modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            // ---- Live charts.
            val from = now - s.liveWindowMin * 60_000L
            val recent = history.filter { it.epochMs >= from }
            if (recent.size >= 2) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Last ${s.liveWindowMin} min", style = MaterialTheme.typography.titleMedium)
                        for (g in chartGroups(s.logRoles)) {
                            TimeChart(g, recent, dash.units, g.roles.flatMap { thresholdsFor(rules, it) }, height = 150, fromMs = from, toMs = now)
                        }
                    }
                }
            }
        } else {
            st.lastSession?.let { last ->
                Card(onClick = { onOpenSession(last.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Last session", style = MaterialTheme.typography.titleMedium)
                        SessionLine(last)
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueTile(role: Role, value: Double?, unit: String?, level: AlarmLevel?, big: Boolean, notGiven: Boolean, modifier: Modifier) {
    val bg = when (level) {
        AlarmLevel.STOP -> StatusColors.stop
        AlarmLevel.CAUTION -> StatusColors.caution
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (level != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Card(modifier, colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(role.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (value == null && notGiven) {
                // The probe found no request for it that this car answers (details in the session).
                Text("Not given by this car", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            } else {
                Text(
                    Notifier.valueText(role, value, unit),
                    fontSize = if (big) 28.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                )
            }
            if (level != null) Text(if (level == AlarmLevel.STOP) "STOP" else "CAUTION", fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
    }
}

/** Android may stop the monitor in the background unless the app may ignore battery optimisation. */
@Composable
private fun BatteryOptimizationHint() {
    val context = LocalContext.current
    val pm = context.getSystemService(PowerManager::class.java)
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(3_000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text("Let the app run in the background", style = MaterialTheme.typography.titleSmall)
            Text(
                "Without this, Android may stop watching the car while the phone is in your pocket.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                @Suppress("BatteryLife")
                val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                runCatching { context.startActivity(intent) }
            }, modifier = Modifier.padding(top = 6.dp)) { Text("Allow") }
        }
    }
}

fun ago(ms: Long): String = when {
    ms < 60_000 -> "${ms / 1000} s ago"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3_600_000} h ago"
}
