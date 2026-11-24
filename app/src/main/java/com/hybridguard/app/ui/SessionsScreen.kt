package com.hybridguard.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.hybridguard.app.Monitor
import com.hybridguard.app.RecordedSession
import com.hybridguard.app.Sample
import com.hybridguard.app.app
import com.hybridguard.data.AlarmLevel
import com.hybridguard.data.label
import com.hybridguard.data.withOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(onOpen: (String) -> Unit) {
    val app = LocalContext.current.app
    val status by Monitor.status.collectAsState()
    // Reload when a session ends.
    val sessions by produceState(emptyList<RecordedSession>(), status.lastSession?.id) {
        value = withContext(Dispatchers.IO) { app.archive.list() }
    }
    if (sessions.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No sessions yet. A session is saved every time the car is READY and the app reads it.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("${sessions.size} sessions", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp)) }
        items(sessions, key = { it.id }) { s ->
            Card(onClick = { onOpen(s.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) { SessionLine(s) }
            }
        }
    }
}

@Composable
fun SessionLine(s: RecordedSession) {
    val f = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(f.format(Date(s.startEpochMs)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        val worst = s.worstLevel
        Text(
            when (worst) {
                AlarmLevel.STOP -> "STOP · ${s.alarms.size} alarm" + if (s.alarms.size > 1) "s" else ""
                AlarmLevel.CAUTION -> "Caution · ${s.alarms.size} alarm" + if (s.alarms.size > 1) "s" else ""
                null -> "OK"
            },
            color = when (worst) {
                AlarmLevel.STOP -> StatusColors.stop
                AlarmLevel.CAUTION -> StatusColors.caution
                null -> StatusColors.ok
            },
            fontWeight = FontWeight.Bold,
        )
    }
    val parts = listOfNotNull(
        duration(s.durationMs),
        if (s.km > 0.05) String.format(Locale.US, "%.1f km", s.km) else null,
        s.stats[com.hybridguard.data.Role.COOLANT_TEMP]?.let { "coolant max ${num(it.max)} °C" },
        s.stats[com.hybridguard.data.Role.INVERTER_PUMP]?.let { "inv. pump min ${num(it.min)}" },
        if (s.simulated) "simulated" else null,
    )
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
}

fun duration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else if (m > 0) "$m min" else "${ms / 1000} s"
}

@Composable
fun SessionDetailScreen(id: String, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val settings by app.settings.flow.collectAsState()
    val session = remember(id) { app.archive.get(id) }
    val samples by produceState<List<Sample>?>(null, id) { value = withContext(Dispatchers.IO) { app.archive.samples(id) } }
    var confirmDelete by remember { mutableStateOf(false) }
    if (session == null) {
        LaunchedEffect(Unit) { onDeleted() }
        return
    }
    val rules = remember(session.profileId, settings.alarmOverrides) {
        session.profileId?.let { app.profileAlarmRules(it) }?.withOverrides(settings.alarmOverrides)
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                SessionLine(session)
                val f = DateFormat.getTimeInstance(DateFormat.SHORT)
                val lines = listOfNotNull(
                    "From ${f.format(Date(session.startEpochMs))} to ${f.format(Date(session.endEpochMs))} (${duration(session.durationMs)})",
                    session.profileName?.let { it + (session.modelYear?.let { y -> " · $y" } ?: "") },
                    if (session.km > 0) String.format(Locale.US, "%.1f km, %.1f km electric", session.km, session.evKm) else null,
                    session.fuelLitres?.let { String.format(Locale.US, "Fuel %.2f l", it) },
                    session.maxSpeedKmh?.let { "Top speed ${num(it)} km/h" },
                    session.startVolts?.let { String.format(Locale.US, "12 V at start %.1f V", it) },
                    session.adapter?.let { "Adapter $it" },
                    "Ended: ${session.endReason}",
                )
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = {
                        val file = app.archive.valuesFile(id)
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "HybridGuard session $id")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Share the values (CSV)"))
                    }) { Text("Share CSV") }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                }
            }
        }
        if (session.alarms.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Alarms", style = MaterialTheme.typography.titleMedium)
                    val f = DateFormat.getTimeInstance(DateFormat.MEDIUM)
                    session.alarms.forEach { a ->
                        Text(
                            (if (a.level == AlarmLevel.STOP) "STOP  " else "Caution  ") + a.title,
                            color = if (a.level == AlarmLevel.STOP) StatusColors.stop else StatusColors.caution,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            f.format(Date(a.startEpochMs)) + (a.endEpochMs?.let { " – " + f.format(Date(it)) } ?: "") +
                                (a.detail.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (session.notGiven.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Not given by this car", style = MaterialTheme.typography.titleMedium)
                    Text("What the car answered when the app asked for these values:", style = MaterialTheme.typography.bodySmall)
                    session.notGiven.forEach { (role, answers) ->
                        Text(role.label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        answers.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        // The table view: every value's minimum, average and maximum.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Values", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.padding(top = 6.dp)) {
                    Text("", Modifier.weight(1f))
                    listOf("min", "avg", "max").forEach { Text(it, Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall) }
                }
                HorizontalDivider()
                session.roles.forEach { role ->
                    val st = session.stats[role] ?: return@forEach
                    val unit = session.units[role].orEmpty()
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(role.label + if (unit.isNotEmpty()) " ($unit)" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        listOf(st.min, st.avg, st.max).forEach { Text(formatNumber(it), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Charts", style = MaterialTheme.typography.titleMedium)
                Text("Touch and slide over a chart to read the values. Dashed lines: alarm limits.", style = MaterialTheme.typography.bodySmall)
                val data = samples
                when {
                    data == null -> Text("Loading…")
                    data.size < 2 -> Text("Not enough values for a chart.")
                    else -> for (g in chartGroups(session.roles.filter { r -> session.stats.containsKey(r) })) {
                        TimeChart(g, data, session.units, g.roles.flatMap { thresholdsFor(rules, it) })
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this session?") },
            text = { Text("Its values and alarms are removed from the phone. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { app.archive.delete(id); confirmDelete = false; onDeleted() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
