package com.hybridguard.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hybridguard.app.Monitor
import com.hybridguard.app.MonitorPhase
import com.hybridguard.app.app
import com.hybridguard.data.BatteryAnalysis
import com.hybridguard.data.BatteryLimits
import com.hybridguard.data.BatteryReport
import com.hybridguard.data.FindingLevel
import com.hybridguard.data.Spread
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * The HV battery: charge, power, limits, cell balance and resistance,
 * temperatures and cooling, and the 12 V battery. Its requests (the long
 * cell replies above all) are polled only while this tab is on screen, so
 * they never slow the cooling watch.
 */
@Composable
fun BatteryScreen() {
    val app = LocalContext.current.app
    LifecycleStartEffect(Unit) {
        Monitor.setBatteryTab(true)
        onStopOrDispose { Monitor.setBatteryTab(false) }
    }
    val st by Monitor.status.collectAsStateWithLifecycle()
    val s by app.settings.flow.collectAsStateWithLifecycle()
    val profileId = st.session?.profile?.id ?: app.settingsProfileId()
    val profile = remember(profileId) { app.profileBattery(profileId) }
    val dash = st.dashboard
    if (st.phase != MonitorPhase.MONITORING || dash == null) {
        Message(
            "The battery is shown while the car is READY and the app is monitoring it.",
            "Its cell readings are asked for only while this tab is open, so they never slow the cooling watch.",
        )
        return
    }
    val report = BatteryAnalysis.analyze(dash, profile, s.batteryChemistry) { chem, limits -> s.batteryLimits(chem, limits) }
    if (!report.hasData) {
        Message("Waiting for the first battery values", "The cell readings are long replies: the first ones take a few seconds.")
        return
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PackCard(report)
        if (report.findings.isNotEmpty()) HealthCard(report)
        if (report.units.count > 0 || report.resistances.count > 0) CellsCard(report)
        if (report.temps.count > 0 || report.intakeC != null) TemperatureCard(report)
        AuxCard(report)
        Text(
            "The health lines use guide values for the pack type, not Toyota limits; change them in Settings → Battery tab. " +
                "Long replies (cells, resistances) refresh slower than the rest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Message(title: String, detail: String) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Section(title: String, sub: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}

private fun fmt(v: Double?, pattern: String, unit: String = ""): String =
    v?.let { String.format(Locale.US, pattern, it) + unit } ?: "--"

@Composable
private fun PackCard(r: BatteryReport) {
    val type = r.chemistry?.let { c -> c.label + (r.chemistrySource?.let { " (${it.label})" } ?: "") } ?: "Pack type unknown"
    Section("HV battery", type) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(120.dp)) {
                Text(fmt(r.soc, "%.0f", " %"), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("charge", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                SocBar(r.soc, r.socMin, r.socMax)
                if (r.socMin != null && r.socMax != null) {
                    Text(
                        "cells from ${fmt(r.socMin, "%.0f")} to ${fmt(r.socMax, "%.0f")} %",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Voltage", fmt(r.voltage, "%.1f", " V"), Modifier.weight(1f))
            Metric("Current", fmt(r.current?.let(::abs), "%.1f", " A"), Modifier.weight(1f))
            val p = r.powerKw
            Metric(
                when {
                    p == null -> "Power"
                    p >= 1.0 -> "Giving"
                    p <= -1.0 -> "Charging"
                    else -> "Idle"
                },
                fmt(p?.let(::abs), "%.1f", " kW"), Modifier.weight(1f),
            )
        }
        if (r.chargeLimitKw != null || r.dischargeLimitKw != null) {
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("May take now", fmt(r.chargeLimitKw?.let(::abs), "%.1f", " kW"), Modifier.weight(1f))
                Metric("May give now", fmt(r.dischargeLimitKw?.let(::abs), "%.1f", " kW"), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SocBar(soc: Double?, min: Double?, max: Double?) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(18.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)),
    ) {
        val w = maxWidth
        if (soc != null) {
            Box(
                Modifier.width(w * (soc / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)),
            )
        }
        if (min != null && max != null && max > min) {
            Box(
                Modifier.offset(x = w * (min / 100).toFloat().coerceIn(0f, 1f)).width(w * ((max - min) / 100).toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight().border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), RoundedCornerShape(9.dp)),
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun FindingLevel.color(): Color? = when (this) {
    FindingLevel.OK -> StatusColors.ok
    FindingLevel.WATCH -> StatusColors.caution
    FindingLevel.CHECK -> StatusColors.stop
    FindingLevel.INFO -> null
}

private val FindingLevel.word: String
    get() = when (this) {
        FindingLevel.OK -> "OK"
        FindingLevel.WATCH -> "WATCH"
        FindingLevel.CHECK -> "CHECK"
        FindingLevel.INFO -> "NOTE"
    }

@Composable
private fun HealthCard(r: BatteryReport) {
    Section("Health check", r.atRest?.let { if (it) "Pack at rest: the balance reading counts" else "Pack under load: the balance is judged at rest" }) {
        r.findings.forEach { f ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                val c = f.level.color() ?: MaterialTheme.colorScheme.outline
                Text(
                    f.level.word, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    modifier = Modifier.width(56.dp).background(c, RoundedCornerShape(4.dp)).padding(vertical = 2.dp),
                )
                Column(Modifier.padding(start = 10.dp)) {
                    Text(f.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private enum class CellView { VOLTAGE, RESISTANCE }

@Composable
private fun CellsCard(r: BatteryReport) {
    var chosen by rememberSaveable { mutableStateOf(CellView.VOLTAGE) }
    val view = if (r.units.count == 0) CellView.RESISTANCE else chosen
    var picked by remember { mutableStateOf<Int?>(null) }
    val word = r.unitWord.replaceFirstChar { it.uppercase() } + "s"
    Section("$word (${r.units.count})") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.units.count > 0) FilterChip(view == CellView.VOLTAGE, onClick = { chosen = CellView.VOLTAGE }, label = { Text("Voltage") })
            if (r.resistances.count > 0) FilterChip(view == CellView.RESISTANCE, onClick = { chosen = CellView.RESISTANCE }, label = { Text("Resistance") })
        }
        val spread = if (view == CellView.VOLTAGE) r.units else r.resistances
        Text(
            when (view) {
                CellView.VOLTAGE -> "lowest ${fmt(spread.min, "%.3f", " V")} (#${spread.minIndex}) · highest ${fmt(spread.max, "%.3f", " V")} (#${spread.maxIndex}) · " +
                    "spread ${fmt(spread.range?.times(1000), "%.0f", " mV")}"
                CellView.RESISTANCE -> "median ${fmt(spread.median, "%.2f", " mΩ")} · highest ${fmt(spread.max, "%.2f", " mΩ")} (#${spread.maxIndex})"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        val limits = r.limits
        if (view == CellView.VOLTAGE && limits != null && spread.count >= 2) {
            Spacer(Modifier.size(8.dp))
            DeviationChart(spread, limits)
        }
        Spacer(Modifier.size(8.dp))
        CellGrid(spread, view, r.limits, picked) { picked = if (picked == it) null else it }
        picked?.let { n ->
            val v = r.units.values.getOrNull(n - 1)
            val res = r.resistances.values.getOrNull(n - 1)
            val median = r.units.median
            Text(
                "${r.unitWord.replaceFirstChar { it.uppercase() }} $n: ${fmt(v, "%.4f", " V")}" +
                    (if (v != null && median != null) String.format(Locale.US, " (%+.1f mV from the median)", (v - median) * 1000) else "") +
                    (res?.let { String.format(Locale.US, " · %.2f mΩ", it) } ?: ""),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (r.blocks.count > 0 && r.units !== r.blocks) {
            Text(
                "Block averages: " + r.blocks.values.mapIndexedNotNull { i, v -> v?.let { String.format(Locale.US, "#%d %.3f V", i + 1, it) } }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Each cell's difference from the pack median, mV, as bars; the balance
 * limits (half the allowed spread each side) dashed in the status colours.
 */
@Composable
private fun DeviationChart(spread: Spread, limits: BatteryLimits) {
    val median = spread.median ?: return
    val devs = spread.values.map { v -> v?.let { (it - median) * 1000 } }
    val worst = devs.filterNotNull().maxOfOrNull { abs(it) } ?: 0.0
    val scale = max(worst, limits.spreadBadMv / 2) * 1.15
    val bar = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val mid = size.height / 2
        fun y(mv: Double) = (mid - mv / scale * mid).toFloat()
        val step = size.width / devs.size
        devs.forEachIndexed { i, d ->
            d ?: return@forEachIndexed
            val top = minOf(y(d), mid)
            val h = abs(y(d) - mid).coerceAtLeast(1f)
            drawRect(bar, topLeft = Offset(i * step + step * 0.15f, top), size = Size(step * 0.7f, h))
        }
        drawLine(axis, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        for ((mv, color) in listOf(limits.spreadWarnMv / 2 to StatusColors.caution, limits.spreadBadMv / 2 to StatusColors.stop)) {
            for (sign in listOf(1, -1)) {
                drawLine(color, Offset(0f, y(sign * mv)), Offset(size.width, y(sign * mv)), strokeWidth = 2f, pathEffect = dash)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
        LegendItem(bar, "mV from the median")
        LegendItem(StatusColors.caution, String.format(Locale.US, "watch ±%.0f", limits.spreadWarnMv / 2), dashed = true)
        LegendItem(StatusColors.stop, String.format(Locale.US, "check ±%.0f", limits.spreadBadMv / 2), dashed = true)
    }
}

@Composable
private fun LegendItem(color: Color, text: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(16.dp).height(8.dp)) {
            if (dashed) {
                drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
            } else {
                drawRect(color)
            }
        }
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun CellGrid(spread: Spread, view: CellView, limits: BatteryLimits?, picked: Int?, onPick: (Int) -> Unit) {
    val perRow = 8
    val median = spread.median
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        spread.values.withIndex().chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (i, v) ->
                    val flag: Color? = when {
                        v == null || median == null || limits == null -> null
                        view == CellView.VOLTAGE -> {
                            val dev = abs(v - median) * 1000
                            when {
                                dev >= limits.spreadBadMv / 2 -> StatusColors.stop
                                dev >= limits.spreadWarnMv / 2 -> StatusColors.caution
                                else -> null
                            }
                        }
                        median > 0 -> when {
                            v / median >= limits.resistanceRatioBad -> StatusColors.stop
                            v / median >= limits.resistanceRatioWarn -> StatusColors.caution
                            else -> null
                        }
                        else -> null
                    }
                    val label = when {
                        v == null -> "--"
                        view == CellView.VOLTAGE && median != null -> String.format(Locale.US, "%+.0f", (v - median) * 1000)
                        else -> String.format(Locale.US, "%.1f", v)
                    }
                    CellTile(i + 1, label, flag, picked == i + 1, Modifier.weight(1f)) { onPick(i + 1) }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Text(
        (if (view == CellView.VOLTAGE) "Tiles: mV from the median." else "Tiles: mΩ.") + " Orange: watch, red: check. Tap a tile for its values.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun CellTile(n: Int, label: String, flag: Color?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = flag ?: MaterialTheme.colorScheme.surfaceVariant
    val fg = if (flag != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.background(bg, RoundedCornerShape(6.dp))
            .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$n", fontSize = 9.sp, color = fg.copy(alpha = 0.75f))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun TemperatureCard(r: BatteryReport) {
    Section("Temperatures and cooling") {
        if (r.temps.count > 0) {
            r.temps.values.withIndex().chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    row.forEach { (i, t) -> Metric("Sensor ${i + 1}", fmt(t, "%.1f", " °C"), Modifier.weight(1f)) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Cooling air", fmt(r.intakeC, "%.1f", " °C"), Modifier.weight(1f))
            Metric("Fan", fmt(r.fanPct, "%.0f", " %"), Modifier.weight(1f))
            Metric("Fan level", fmt(r.fanLevel, "%.0f"), Modifier.weight(1f))
        }
    }
}

@Composable
private fun AuxCard(r: BatteryReport) {
    if (listOf(r.auxVolts, r.auxAmps, r.auxC, r.auxCapacity).all { it == null }) return
    Section("12 V battery") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Voltage", fmt(r.auxVolts, "%.2f", " V"), Modifier.weight(1f))
            Metric("Current", fmt(r.auxAmps, "%.1f", " A"), Modifier.weight(1f))
        }
        Spacer(Modifier.size(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Temperature", fmt(r.auxC, "%.0f", " °C"), Modifier.weight(1f))
            Metric("Capacity", fmt(r.auxCapacity, "%.0f", " Ah"), Modifier.weight(1f))
        }
    }
}
