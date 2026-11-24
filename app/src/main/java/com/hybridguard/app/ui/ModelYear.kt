package com.hybridguard.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hybridguard.app.DataSource
import com.hybridguard.app.HybridGuardApp
import com.hybridguard.app.Monitor
import com.hybridguard.app.app
import java.util.Calendar

/** What to ask about one car's model year: the profile's name and the years it covers. */
data class ModelYearQuestion(val vin: String, val carName: String, val years: List<Int>, val current: Int?)

/** The years to offer for this car (the profile's range, else 1996 to next year), newest first. */
fun modelYearQuestion(app: HybridGuardApp, vin: String): ModelYearQuestion {
    val selection = app.profileSelector.select(vin)
    val info = app.profileSelector.info(selection.profileId)
    val range = info?.years ?: 1996..(Calendar.getInstance().get(Calendar.YEAR) + 1)
    return ModelYearQuestion(vin, info?.name ?: "car", range.reversed().toList(), selection.modelYear)
}

/** Pick one model year from the list. */
@Composable
fun ModelYearDialog(q: ModelYearQuestion, onDismiss: () -> Unit, dismissLabel: String = "Cancel", onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model year of your car") },
        text = {
            Column {
                Text(
                    "${q.carName}: the VIN does not say the model year (most European VINs do not), " +
                        "and some values are read differently per year. It is in the registration " +
                        "document, or ask the dealer.",
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(q.years) { y ->
                        Row(
                            Modifier.fillMaxWidth().selectable(y == q.current) { onPick(y) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(y == q.current, onClick = null)
                            Text(y.toString(), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/**
 * Asks once per app start for the model year of the last car seen, when
 * nothing else gives it (the VIN, the profile, an earlier answer). Shown
 * only in the app, never over the driving notification.
 */
@Composable
fun ModelYearPrompt() {
    val app = LocalContext.current.app
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val status by Monitor.status.collectAsStateWithLifecycle()
    val vin = status.session?.vin?.takeIf { s.source == DataSource.BLUETOOTH } ?: s.lastVin
    var handled by rememberSaveable { mutableStateOf<String?>(null) }
    val needed = remember(vin, status.session?.modelYearAssumed) {
        vin != null && s.source == DataSource.BLUETOOTH && s.manualModelYear == null &&
            runCatching { app.profileSelector.needsModelYear(vin) }.getOrDefault(false)
    }
    if (vin == null || !needed || handled == vin) return
    val q = remember(vin) { modelYearQuestion(app, vin) }
    ModelYearDialog(q, onDismiss = { handled = vin }, dismissLabel = "Later") { year ->
        handled = vin
        Monitor.setModelYear(vin, year)
    }
}
