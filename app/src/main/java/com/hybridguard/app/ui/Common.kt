package com.hybridguard.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

val LocalDark = staticCompositionLocalOf { false }

/** Status colours (reserved: never used for a chart series), always shown with a word. */
object StatusColors {
    val ok = Color(0xFF2E7D32)
    val caution = Color(0xFFEF8F00)
    val stop = Color(0xFFC62828)
}

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFF8AB4F8), surface = Color(0xFF1A1A19), background = Color(0xFF121211))
    } else {
        lightColorScheme(primary = Color(0xFF1A5FB4), surface = Color(0xFFFCFCFB), background = Color(0xFFF4F4F2))
    }
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, onClick = { onChange(!checked) }) { Switch(checked, onCheckedChange = onChange) }
}

fun num(v: Double, unit: String = ""): String {
    val s = if (v == Math.rint(v)) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    return if (unit.isEmpty()) s else "$s $unit"
}

/** A number setting: tap to type a new value, kept within [min]..[max]. */
@Composable
fun NumberRow(title: String, value: Double, unit: String, min: Double, max: Double, subtitle: String? = null, onChange: (Double) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    SettingRow(title, subtitle ?: num(value, unit), onClick = { editing = true })
    if (editing) {
        NumberDialog(title, value, unit, min, max, onDismiss = { editing = false }) {
            editing = false
            onChange(it)
        }
    }
}

@Composable
fun NumberDialog(title: String, value: Double, unit: String, min: Double, max: Double, onDismiss: () -> Unit, onDone: (Double) -> Unit) {
    var text by remember { mutableStateOf(num(value)) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && parsed in min..max
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    text, { text = it }, singleLine = true, suffix = { if (unit.isNotEmpty()) Text(unit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = !valid,
                )
                Text("From ${num(min)} to ${num(max)}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onDone) }, enabled = valid) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One of several choices. */
@Composable
fun <T> ChoiceRow(title: String, value: T, options: List<T>, label: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, label(value), onClick = { open = true })
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(options) { o ->
                        Row(
                            Modifier.fillMaxWidth().selectable(o == value) { open = false; onChange(o) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(o == value, onClick = null)
                            Text(label(o), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

/** Several of several choices, in the order chosen. */
@Composable
fun <T> MultiChoiceDialog(title: String, chosen: List<T>, options: List<T>, label: (T) -> String, onDismiss: () -> Unit, onDone: (List<T>) -> Unit) {
    var picked by remember { mutableStateOf(chosen) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(options) { o ->
                    val on = o in picked
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (on) picked - o else picked + o }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, onCheckedChange = null)
                        Text(label(o), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(picked) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
