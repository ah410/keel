package com.arceusvault.keel.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.ui.theme.LocalPalette
import com.arceusvault.keel.ui.theme.PlexSans

/** Ring gauge with the value in the middle, like the web UI's CPU widget. */
@Composable
fun Gauge(fraction: Float?, center: String, label: String? = null, size: Dp = 120.dp, color: Color? = null) {
    val p = LocalPalette.current
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), label = "gauge")
    val stroke = usageColor(animated)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.minDimension * 0.1f
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            // 270 degree arc opening at the bottom.
            drawArc(p.lines, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Butt))
            if (fraction != null) {
                drawArc(color ?: stroke, 135f, 270f * animated, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Butt))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold))
            label?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = p.muted) }
        }
    }
}

data class Slice(val label: String, val value: Double, val color: Color)

/** Donut chart with a legend beside it, as used for memory and pool usage. */
@Composable
fun Donut(slices: List<Slice>, center: String, modifier: Modifier = Modifier, size: Dp = 110.dp, legendValue: (Slice) -> String) {
    val p = LocalPalette.current
    val total = slices.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val w = this.size.minDimension * 0.14f
                val arc = Size(this.size.width - w, this.size.height - w)
                var start = -90f
                drawArc(p.lines, 0f, 360f, false, Offset(w / 2, w / 2), arc, style = Stroke(w))
                slices.forEach { s ->
                    val sweep = (s.value / total * 360).toFloat()
                    if (sweep > 0) drawArc(s.color, start, sweep, false, Offset(w / 2, w / 2), arc, style = Stroke(w))
                    start += sweep
                }
            }
            Text(center, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(s.color)
                    Column {
                        Text(s.label, style = MaterialTheme.typography.bodySmall, color = p.muted)
                        Text(legendValue(s), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Small line chart without axes. Values are drawn relative to [max] (or their own peak). */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier, max: Double? = null, fill: Boolean = true) {
    Canvas(modifier.fillMaxWidth().height(40.dp)) {
        if (values.size < 2) return@Canvas
        val top = (max ?: values.max()).takeIf { it > 0 } ?: 1.0
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - (v / top).toFloat().coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            val area = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, color.copy(alpha = 0.18f))
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}

/** Multi-series line chart with a peak label, for reporting history. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineChart(
    series: List<Pair<String, List<Double>>>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    format: (Double) -> String,
    height: Dp = 160.dp,
) {
    val p = LocalPalette.current
    val all = series.flatMap { it.second }.filter { !it.isNaN() }
    val peak = (all.maxOrNull() ?: 0.0).takeIf { it > 0 } ?: 1.0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            Canvas(Modifier.fillMaxWidth().height(height)) {
                for (i in 0..3) {
                    val y = size.height * i / 3
                    drawLine(p.lines, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                series.forEachIndexed { si, (_, values) ->
                    if (values.size < 2) return@forEachIndexed
                    val step = size.width / (values.size - 1)
                    val path = Path()
                    var started = false
                    values.forEachIndexed { i, v ->
                        if (v.isNaN()) { started = false; return@forEachIndexed }
                        val x = i * step
                        val y = size.height - (v / peak).toFloat().coerceIn(0f, 1f) * size.height
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    }
                    drawPath(path, colors[si % colors.size], style = Stroke(1.5.dp.toPx()))
                }
            }
            Text(format(peak), style = MaterialTheme.typography.labelSmall, color = p.muted, modifier = Modifier.align(Alignment.TopEnd))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            series.forEachIndexed { i, (name, values) ->
                val last = values.lastOrNull { !it.isNaN() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusDot(colors[i % colors.size])
                    Text("$name ${last?.let(format) ?: ""}".trim(), style = MaterialTheme.typography.labelSmall, color = p.muted)
                }
            }
        }
    }
}
