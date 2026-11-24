package com.hybridguard.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import com.hybridguard.app.Sample
import com.hybridguard.data.Role
import com.hybridguard.data.shortLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A horizontal line for an alarm threshold. */
data class Threshold(val value: Double, val label: String, val critical: Boolean)

/** Values that share a unit, drawn on one chart (never two y-axes). */
data class ChartGroup(val title: String, val roles: List<Role>)

private val GROUPS = listOf(
    ChartGroup("Water pumps", listOf(Role.ENGINE_WATER_PUMP, Role.ENGINE_WATER_PUMP_TARGET, Role.INVERTER_PUMP)),
    ChartGroup("Coolant", listOf(Role.COOLANT_TEMP, Role.INVERTER_COOLANT_TEMP)),
    ChartGroup("Inverter", listOf(Role.MOTOR_INVERTER_TEMP, Role.GENERATOR_INVERTER_TEMP, Role.BOOST_CONVERTER_TEMP)),
)

/** The charts for [roles]: the usual groups (at most three lines each), every other value on its own. */
fun chartGroups(roles: Collection<Role>): List<ChartGroup> {
    val set = roles.toSet()
    val grouped = GROUPS.map { g -> g.copy(roles = g.roles.filter { it in set }) }.filter { it.roles.isNotEmpty() }
    val inGroups = grouped.flatMap { it.roles }.toSet()
    return grouped + roles.filter { it !in inGroups }.map { ChartGroup(it.shortLabel, listOf(it)) }
}

/**
 * A time chart of one [ChartGroup]: 2 px lines in the categorical order,
 * a legend, dashed alarm thresholds, and a crosshair with the values where
 * the finger is. Gaps (no reading) break the line.
 */
@Composable
fun TimeChart(
    group: ChartGroup,
    samples: List<Sample>,
    units: Map<Role, String>,
    thresholds: List<Threshold> = emptyList(),
    height: Int = 180,
    fromMs: Long? = null,
    toMs: Long? = null,
) {
    val c = ChartColors.current()
    val measurer = rememberTextMeasurer()
    var touchX by remember { mutableStateOf<Float?>(null) }
    val unit = group.roles.firstNotNullOfOrNull { units[it] }.orEmpty()
    val t0 = fromMs ?: samples.firstOrNull()?.epochMs ?: 0L
    val t1 = toMs ?: samples.lastOrNull()?.epochMs ?: 1L
    val values = samples.flatMap { s -> group.roles.mapNotNull { s.values[it] } }
    val thresholdValues = thresholds.map { it.value }
    // Thresholds count for the scale only when near the data, so a far-away limit does not flatten the lines.
    val lo0 = values.minOrNull()
    val hi0 = values.maxOrNull()
    val near = if (lo0 != null && hi0 != null) {
        val span = (hi0 - lo0).coerceAtLeast(1.0)
        thresholdValues.filter { it in (lo0 - span)..(hi0 + span) }
    } else emptyList()
    val (yMin, yMax, step) = niceScale((values + near).minOrNull() ?: 0.0, (values + near).maxOrNull() ?: 1.0)

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.title, style = MaterialTheme.typography.titleSmall, color = c.text)
            if (unit.isNotEmpty()) Text("  $unit", style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        if (group.roles.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 2.dp)) {
                group.roles.forEachIndexed { i, role ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 12.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(c.series[i % c.series.size]))
                        Spacer(Modifier.width(4.dp))
                        Text(role.shortLabel, style = MaterialTheme.typography.labelSmall, color = c.muted)
                    }
                }
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(height.dp).padding(top = 6.dp)
                // Keyed on Unit: keying on the samples restarted the detector on every new
                // live value (about once a second) and dropped the finger mid-swipe, so the
                // page scrolled instead. Touching shows the values at once; a sideways move
                // past the touch slop keeps the finger for the chart until it lifts; a move
                // up or down first is left to the page's scroll.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        touchX = down.position.x
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                            touchX = change.position.x
                        }
                        if (drag != null) {
                            horizontalDrag(drag.id) { change ->
                                change.consume()
                                touchX = change.position.x
                            }
                        }
                        touchX = null
                    }
                },
        ) {
            val left = 44.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val right = size.width - 4.dp.toPx()
            val top = 4.dp.toPx()
            fun x(t: Long) = left + (right - left) * ((t - t0).toFloat() / (t1 - t0).coerceAtLeast(1))
            fun y(v: Double) = (bottom - (bottom - top) * ((v - yMin) / (yMax - yMin))).toFloat()
            val label = TextStyle(color = c.muted, fontSize = 10.sp)

            // Grid and y labels: recessive.
            var g = yMin
            while (g <= yMax + step / 2) {
                drawLine(c.grid, Offset(left, y(g)), Offset(right, y(g)), 1f)
                text(measurer, formatNumber(g), Offset(0f, y(g) - 7.dp.toPx()), label)
                g += step
            }
            // Time labels.
            val span = t1 - t0
            val fmt = SimpleDateFormat(
                when {
                    span > 36 * 3_600_000L -> "d MMM HH:mm"
                    span < 6 * 60_000L -> "HH:mm:ss"
                    else -> "HH:mm"
                },
                Locale.getDefault(),
            )
            for (i in 0..3) {
                val t = t0 + span * i / 3
                val w = measurer.measure(fmt.format(Date(t)), label).size.width
                val px = (x(t) - w / 2f).coerceIn(left, right - w)
                text(measurer, fmt.format(Date(t)), Offset(px, bottom + 3.dp.toPx()), label)
            }
            // Thresholds.
            for (th in thresholds) {
                if (th.value < yMin || th.value > yMax) continue
                val color = if (th.critical) c.critical else c.warning
                drawLine(
                    color, Offset(left, y(th.value)), Offset(right, y(th.value)), 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
                // Which value the limit is for, at the right end, above the line.
                val tl = measurer.measure(th.label, TextStyle(color = c.muted, fontSize = 9.sp))
                drawText(tl, topLeft = Offset(right - tl.size.width, y(th.value) - tl.size.height))
            }
            // Lines.
            group.roles.forEachIndexed { i, role ->
                val path = Path()
                var pen = false
                var prevT = 0L
                for (s in samples) {
                    val v = s.values[role]
                    if (v == null || (pen && s.epochMs - prevT > 15_000)) {
                        pen = false
                        if (v == null) continue
                    }
                    if (!pen) path.moveTo(x(s.epochMs), y(v)) else path.lineTo(x(s.epochMs), y(v))
                    pen = true
                    prevT = s.epochMs
                }
                drawPath(path, c.series[i % c.series.size], style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // Crosshair and the values at the finger.
            val tx = touchX
            if (tx != null && samples.isNotEmpty() && tx >= left) {
                val t = t0 + ((tx - left) / (right - left) * (t1 - t0)).toLong()
                val nearest = samples.minBy { abs(it.epochMs - t) }
                val cx = x(nearest.epochMs)
                drawLine(c.text.copy(alpha = 0.5f), Offset(cx, top), Offset(cx, bottom), 1f)
                val lines = listOf(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nearest.epochMs))) +
                    group.roles.map { r -> "${r.shortLabel}: ${nearest.values[r]?.let(::formatNumber) ?: "--"}" }
                group.roles.forEachIndexed { i, r ->
                    nearest.values[r]?.let { v ->
                        drawCircle(c.surface, 6.dp.toPx(), Offset(cx, y(v)))
                        drawCircle(c.series[i % c.series.size], 4.dp.toPx(), Offset(cx, y(v)))
                    }
                }
                val style = TextStyle(color = c.text, fontSize = 11.sp)
                val layouts = lines.map { measurer.measure(it, style) }
                val w = layouts.maxOf { it.size.width } + 12.dp.toPx()
                val h = layouts.sumOf { it.size.height } + 8.dp.toPx()
                val bx = if (cx + 8.dp.toPx() + w < right) cx + 8.dp.toPx() else cx - 8.dp.toPx() - w
                drawRoundRect(c.tooltip, Offset(bx, top), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                var ly = top + 4.dp.toPx()
                layouts.forEach { l ->
                    drawText(l, topLeft = Offset(bx + 6.dp.toPx(), ly))
                    ly += l.size.height
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.text(m: TextMeasurer, s: String, at: Offset, style: TextStyle) {
    drawText(m.measure(s, style), topLeft = at)
}

fun formatNumber(v: Double): String = when {
    abs(v) >= 100 -> String.format(Locale.US, "%.0f", v)
    abs(v) >= 10 -> String.format(Locale.US, "%.1f", v).removeSuffix(".0")
    else -> String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
}

/** Round axis limits and a step giving about four grid lines. */
private fun niceScale(min: Double, max: Double): Triple<Double, Double, Double> {
    var lo = min
    var hi = max
    if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
    val raw = (hi - lo) / 4
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    return Triple(floor(lo / step) * step, ceil(hi / step) * step, step)
}

/** Chart colours: the validated reference palette's first three categorical slots, light and dark. */
data class ChartColors(
    val series: List<Color>,
    val text: Color,
    val muted: Color,
    val grid: Color,
    val surface: Color,
    val tooltip: Color,
    val warning: Color,
    val critical: Color,
) {
    companion object {
        @Composable
        fun current(): ChartColors = if (LocalDark.current) DARK else LIGHT

        val LIGHT = ChartColors(
            series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A)),
            text = Color(0xFF0B0B0B), muted = Color(0xFF52514E), grid = Color(0x1F000000),
            surface = Color(0xFFFCFCFB), tooltip = Color(0xF0FFFFFF),
            warning = Color(0xFFC98500), critical = Color(0xFFD03B3B),
        )
        val DARK = ChartColors(
            series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70)),
            text = Color(0xFFF0EFEC), muted = Color(0xFFC3C2B7), grid = Color(0x26FFFFFF),
            surface = Color(0xFF1A1A19), tooltip = Color(0xF0262624),
            warning = Color(0xFFE3A008), critical = Color(0xFFE66767),
        )
    }
}
