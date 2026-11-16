package app.penny.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.penny.ui.theme.ChartOtherDark
import app.penny.ui.theme.ChartOtherLight
import app.penny.ui.theme.LocalPenny
import kotlin.math.max

/** One labelled value; colour follows the entity key, never its rank. */
data class Datum(val key: String, val label: String, val value: Long, val valueText: String)

/** Stable colour for an entity key; "Other" and unknowns get the neutral. */
@Composable
fun seriesColor(key: String, index: Int? = null): Color {
    val p = LocalPenny.current
    if (key == OTHER) return if (p.isDark) ChartOtherDark else ChartOtherLight
    val i = index ?: (key.hashCode() and 0x7fffffff)
    return p.entityPalette[i % p.entityPalette.size]
}

const val OTHER = "_other"

/** Top [n] plus "Other" so a chart never needs a generated sixth hue. */
fun topWithOther(data: List<Datum>, n: Int, otherLabel: String, format: (Long) -> String): List<Datum> {
    val pos = data.filter { it.value > 0 }.sortedByDescending { it.value }
    if (pos.size <= n) return pos
    val rest = pos.drop(n).sumOf { it.value }
    return pos.take(n) + Datum(OTHER, otherLabel, rest, format(rest))
}

/** IN-02 category donut: thin ring, 2 dp surface gaps, legend with values beside; tap a legend row to drill down. */
@Composable
fun Donut(data: List<Datum>, centerTop: String, centerValue: String, modifier: Modifier = Modifier, onSelect: ((Datum) -> Unit)? = null) {
    val total = data.sumOf { it.value }.coerceAtLeast(1)
    val colors = data.mapIndexed { i, d -> seriesColor(d.key, i) }
    val surface = app.penny.ui.theme.Penny.card
    val track = LocalPenny.current.status.track
    val summary = data.joinToString { "${it.label} ${it.valueText}" }
    Row(modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) { contentDescription = summary }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(150.dp)) {
                val stroke = 20.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                if (data.isEmpty()) {
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                }
                var start = -90f
                val gapDeg = if (data.size > 1) 2.5f else 0f
                data.forEachIndexed { i, d ->
                    val sweep = 360f * d.value / total
                    drawArc(colors[i], start + gapDeg / 2, max(0.5f, sweep - gapDeg), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
                drawCircle(surface, radius = (size.minDimension - stroke * 2) / 2 - 1)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerTop, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyLabel(centerValue, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            data.forEachIndexed { i, d ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp))
                        .then(if (onSelect != null && d.key != OTHER) Modifier.clickable { onSelect(d) } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i]))
                    Spacer(Modifier.width(8.dp))
                    Text(d.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    MoneyLabel(d.valueText, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * IN-02 bars from zero: thin columns with 4 dp rounded data ends anchored to the baseline, one accent, the selected
 * bar emphasised; a tap shows the value and calls [onSelect] for drill-down. Mirrors in RTL (LO-07).
 */
@Composable
fun Bars(
    data: List<Datum>,
    modifier: Modifier = Modifier,
    secondary: List<Long>? = null,
    highlight: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    height: Int = 160,
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val second = app.penny.ui.theme.Penny.gold
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var picked by remember(data) { mutableStateOf(highlight ?: data.lastIndex) }
    val maxV = max(1L, max(data.maxOfOrNull { it.value } ?: 0, secondary?.maxOrNull() ?: 0))
    val summary = data.joinToString { "${it.label} ${it.valueText}" }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        data.getOrNull(picked)?.let { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                MoneyLabel(d.valueText, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier.fillMaxWidth().height(height.dp).semantics { contentDescription = summary }
                .pointerInput(data) {
                    detectTapGestures { o ->
                        if (data.isEmpty()) return@detectTapGestures
                        val slot = size.width / data.size.toFloat()
                        var i = (o.x / slot).toInt().coerceIn(0, data.lastIndex)
                        if (rtl) i = data.lastIndex - i
                        picked = i
                        onSelect?.invoke(i)
                    }
                },
        ) {
            if (data.isEmpty()) return@Canvas
            val slot = size.width / data.size
            val barW = (slot * if (secondary != null) 0.32f else 0.56f).coerceAtMost(28.dp.toPx())
            for (k in 1..3) {
                val y = size.height * (1 - k / 4f)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            data.forEachIndexed { idx, d ->
                val i = if (rtl) data.lastIndex - idx else idx
                val cx = slot * i + slot / 2
                val h = size.height * d.value.coerceAtLeast(0) / maxV
                val x = if (secondary != null) cx - barW - 1.dp.toPx() else cx - barW / 2
                val r = CornerRadius(4.dp.toPx())
                if (h > 0) {
                    val path = Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(x, size.height - h, x + barW, size.height, r, r, CornerRadius.Zero, CornerRadius.Zero))
                    }
                    drawPath(path, if (idx == picked) accent else muted)
                }
                secondary?.getOrNull(idx)?.let { s ->
                    val h2 = size.height * s.coerceAtLeast(0) / maxV
                    if (h2 > 0) {
                        val x2 = cx + 1.dp.toPx()
                        drawPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(x2, size.height - h2, x2 + barW, size.height, r, r, CornerRadius.Zero, CornerRadius.Zero)) }, second.copy(alpha = if (idx == picked) 1f else 0.45f))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            data.forEachIndexed { i, d ->
                Text(
                    if (data.size <= 8 || i % ((data.size + 5) / 6) == 0) d.label else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** IN-02/IN-05/IN-06 line: 2 dp stroke, soft area, crosshair with value on drag; one series. */
@Composable
fun LineChart(points: List<Pair<String, Long>>, format: (Long) -> String, modifier: Modifier = Modifier, height: Int = 160, zeroLine: Boolean = true) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val ink = MaterialTheme.colorScheme.onSurface
    val near = LocalPenny.current.status.near
    val ring = app.penny.ui.theme.Penny.card
    var picked by remember(points) { mutableStateOf<Int?>(null) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    if (points.isEmpty()) return
    val minV = minOf(0L, points.minOf { it.second })
    val maxV = max(points.maxOf { it.second }, minV + 1)
    val show = picked ?: points.lastIndex
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(points[show].first, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            MoneyLabel(format(points[show].second), style = MaterialTheme.typography.titleMedium, color = if (points[show].second < 0) near else Color.Unspecified)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier.fillMaxWidth().height(height.dp).semantics { contentDescription = "${points.first().first} ${format(points.first().second)} – ${points.last().first} ${format(points.last().second)}" }
                .pointerInput(points) {
                    fun pick(x: Float) {
                        val f = (x / size.width).coerceIn(0f, 1f)
                        picked = ((if (rtl) 1 - f else f) * points.lastIndex).toInt().coerceIn(0, points.lastIndex)
                    }
                    // Horizontal only: a vertical swipe over the chart scrolls the page (it used to get stuck on charts).
                    detectHorizontalDragGestures(onDragStart = { pick(it.x) }, onDragEnd = { }) { c, _ -> pick(c.position.x) }
                }
                .pointerInput(points) { detectTapGestures { o -> val f = (o.x / size.width).coerceIn(0f, 1f); picked = ((if (rtl) 1 - f else f) * points.lastIndex).toInt() } },
        ) {
            fun xOf(i: Int): Float { val x = if (points.size == 1) size.width / 2 else size.width * i / points.lastIndex; return if (rtl) size.width - x else x }
            fun yOf(v: Long) = size.height - size.height * (v - minV).toFloat() / (maxV - minV).toFloat()
            for (k in 1..3) drawLine(grid, Offset(0f, size.height * k / 4f), Offset(size.width, size.height * k / 4f), 1f)
            if (zeroLine && minV < 0) drawLine(ink.copy(alpha = 0.5f), Offset(0f, yOf(0)), Offset(size.width, yOf(0)), 1.5f)
            val line = Path()
            points.forEachIndexed { i, p -> if (i == 0) line.moveTo(xOf(i), yOf(p.second)) else line.lineTo(xOf(i), yOf(p.second)) }
            val area = Path().apply { addPath(line); lineTo(xOf(points.lastIndex), size.height); lineTo(xOf(0), size.height); close() }
            drawPath(area, accent.copy(alpha = 0.10f))
            drawPath(line, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            val px = xOf(show)
            drawLine(ink.copy(alpha = 0.35f), Offset(px, 0f), Offset(px, size.height), 1.dp.toPx())
            drawCircle(ring, 6.dp.toPx(), Offset(px, yOf(points[show].second)))
            drawCircle(accent, 4.dp.toPx(), Offset(px, yOf(points[show].second)))
        }
    }
}

/** IN-02 calendar heatmap: sequential single hue, light to dark; weeks as rows; tap a day to drill down. */
@Composable
fun Heatmap(days: List<Pair<java.time.LocalDate, Long>>, format: (Long) -> String, modifier: Modifier = Modifier, firstDay: java.time.DayOfWeek = java.time.DayOfWeek.MONDAY, cellLabel: ((Long) -> String)? = null, onSelect: ((java.time.LocalDate) -> Unit)? = null) {
    if (days.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    val track = LocalPenny.current.status.track
    val maxV = max(1L, days.maxOf { it.second })
    val lead = ((days.first().first.dayOfWeek.value - firstDay.value) + 7) % 7
    val cells: List<Pair<java.time.LocalDate, Long>?> = List(lead) { null } + days
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Weekday initials, so a month that starts mid-week reads correctly.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.clearAndSetSemantics { }) {
            (0 until 7).forEach { i ->
                Text(firstDay.plus(i.toLong()).getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { c ->
                    val share = (c?.second ?: 0).toFloat() / maxV
                    val bg = if (c == null) Color.Transparent else if (c.second <= 0) track else accent.copy(alpha = 0.18f + 0.82f * share)
                    Surface(
                        color = bg, shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f).heightIn(min = if (cellLabel != null) 44.dp else 30.dp).semantics { if (c != null) contentDescription = "${c.first}: ${format(c.second)}" }
                            .then(if (c != null && onSelect != null) Modifier.clickable { onSelect(c.first) } else Modifier),
                    ) {
                        if (c != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            val ink = if (share > 0.55f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            Text(c.first.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall, color = ink)
                            if (cellLabel != null && c.second > 0) Text(cellLabel(c.second), style = MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)), color = ink, maxLines = 1)
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * IN-02 Sankey, simplified for phones: income sources on one bar, where it went on the other, proportional segments
 * with 2 dp gaps, labelled rows below. Readable at 200% text where curved ribbons are not.
 */
@Composable
fun FlowBars(sources: List<Datum>, sinks: List<Datum>, modifier: Modifier = Modifier) {
    val totalIn = max(1L, sources.sumOf { it.value })
    val totalOut = max(1L, sinks.sumOf { it.value })
    val scale = max(totalIn, totalOut)
    @Composable
    fun bar(items: List<Datum>, total: Long, offset: Int) {
        Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(6.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { i, d ->
                val w = d.value.toFloat() / scale
                if (w > 0) Box(Modifier.weight(w).height(18.dp).background(seriesColor(d.key, i + offset)))
            }
            val rest = (scale - total).toFloat() / scale
            if (rest > 0.001f) Spacer(Modifier.weight(rest))
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        bar(sources, totalIn, 0)
        bar(sinks, totalOut, sources.size)
        (sources + sinks).forEachIndexed { i, d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(seriesColor(d.key, i)))
                Spacer(Modifier.width(8.dp))
                Text(d.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                MoneyLabel(d.valueText, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
