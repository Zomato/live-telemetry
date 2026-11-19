package com.zomato.livetelemetry

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// Enums & models
// ---------------------------------------------------------------------------

internal enum class TelemetryMetricType(val label: String) {
    FPS("FPS"),
    UI_FPS("UI FPS"),
    FRAME_BREAKDOWN("Frame Bars"),
    MEMORY("Memory"),
    CPU("CPU"),
    NETWORK("Network"),
    GC("GC")
}

/**
 * A single stage of a frame's lifecycle, matching the segments shown by "Profile GPU
 * rendering" (Input Handling → Animation → Measure/Layout → Draw → Sync&Upload → Issue
 * Commands → Swap Buffers). Colors here are our own — a visual approximation, not a
 * guaranteed match to the on-device tool's palette.
 */
internal enum class FrameStage(val label: String, val color: Color) {
    INPUT_HANDLING("Input", Color(0xFF64B5F6)) {
        override fun nanosOf(sample: FrameStageSample) = sample.inputHandlingNanos
    },
    ANIMATION("Animation", Color(0xFFBA68C8)) {
        override fun nanosOf(sample: FrameStageSample) = sample.animationNanos
    },
    MEASURE_LAYOUT("Measure/Layout", Color(0xFF4DB6AC)) {
        override fun nanosOf(sample: FrameStageSample) = sample.measureLayoutNanos
    },
    DRAW("Draw", Color(0xFF81C784)) {
        override fun nanosOf(sample: FrameStageSample) = sample.drawNanos
    },
    SYNC_UPLOAD("Sync & Upload", Color(0xFFFFD54F)) {
        override fun nanosOf(sample: FrameStageSample) = sample.syncNanos
    },
    COMMAND_ISSUE("Issue Commands", Color(0xFFFF8A65)) {
        override fun nanosOf(sample: FrameStageSample) = sample.commandIssueNanos
    },
    SWAP_BUFFERS("Swap Buffers", Color(0xFFE57373)) {
        override fun nanosOf(sample: FrameStageSample) = sample.swapBuffersNanos
    };

    abstract fun nanosOf(sample: FrameStageSample): Long
}

/** One heap series shown in [TelemetryMemoryGraph]; toggleable via [MemorySeriesSelector]. */
internal enum class MemorySeries(val label: String, val color: Color) {
    JAVA("Java", Color(0xFF4CAF50)) {
        override fun valueOf(data: LiveTelemetryData) = data.javaHeapMB.toFloat()
    },
    NATIVE("Native", Color(0xFF4FC3F7)) {
        override fun valueOf(data: LiveTelemetryData) = data.nativeHeapMB.toFloat()
    },
    GRAPHICS("Graphics", Color(0xFFFFB300)) {
        override fun valueOf(data: LiveTelemetryData) = data.graphicsMB.toFloat()
    };

    abstract fun valueOf(data: LiveTelemetryData): Float
}

private data class GraphSeries(val label: String, val color: Color, val values: List<Float>)

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/** Strip package prefix — "com.zomato.food.HomeActivity" → "HomeActivity" */
private fun String.toSimpleName() = substringAfterLast('.')

private fun formatCurrentValue(value: Float, type: TelemetryMetricType): String = when (type) {
    TelemetryMetricType.FPS     -> "${value.toInt()} fps"
    TelemetryMetricType.UI_FPS          -> "${value.toInt()} fps"
    TelemetryMetricType.FRAME_BREAKDOWN -> ""   // shown as stacked bars + legend, not a single number
    TelemetryMetricType.MEMORY  -> "${value.toInt()} MB"
    TelemetryMetricType.CPU     -> "${"%.1f".format(value)}%"
    TelemetryMetricType.NETWORK -> "${"%.1f".format(value)} KBps"
    TelemetryMetricType.GC      -> "${value.toInt()} ms"
}

private fun metricLineColor(value: Float, type: TelemetryMetricType): Color = when (type) {
    TelemetryMetricType.FPS, TelemetryMetricType.UI_FPS -> when {
        value >= 50f -> Color(0xFF4CAF50)
        value >= 30f -> Color(0xFFFFB300)
        else         -> Color(0xFFE23744)
    }
    TelemetryMetricType.CPU -> when {
        value < 30f -> Color(0xFF4CAF50)
        value < 70f -> Color(0xFFFFB300)
        else        -> Color(0xFFE23744)
    }
    else -> Color(0xFF4FC3F7)
}

private fun primaryValue(data: LiveTelemetryData, type: TelemetryMetricType): Float = when (type) {
    TelemetryMetricType.FPS     -> data.fps
    TelemetryMetricType.UI_FPS          -> data.uiFps
    TelemetryMetricType.FRAME_BREAKDOWN -> 0f   // unused — this tab renders its own graph + header
    TelemetryMetricType.MEMORY  -> (data.javaHeapMB + data.nativeHeapMB + data.graphicsMB).toFloat()
    TelemetryMetricType.CPU     -> data.cpuPercent
    TelemetryMetricType.NETWORK -> (data.downloadKBps + data.uploadKBps).toFloat()
    TelemetryMetricType.GC      -> data.gcTimeMs.toFloat()
}

// ---------------------------------------------------------------------------
// DrawScope extensions
// ---------------------------------------------------------------------------

/** Fixed Y-axis labels — drawn in a non-scrolling overlay canvas (52dp wide). */
private fun DrawScope.drawYAxisLabels(minValue: Float, maxValue: Float, bottomPad: Float, topPad: Float) {
    val range = (maxValue - minValue).coerceAtLeast(1f)
    val plotH = size.height - bottomPad - topPad
    val txtSz = 22f
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            color       = android.graphics.Color.argb(200, 210, 210, 210)
            textSize    = txtSz
            isAntiAlias = true
        }
        for (i in 0..4) {
            val frac = i.toFloat() / 4
            val y    = topPad + plotH * (1f - frac)
            val v    = minValue + range * frac
            val lbl  = when { v >= 1000f -> "${"%.1f".format(v / 1000f)}k"; v >= 10f -> "${v.toInt()}"; else -> "${"%.1f".format(v)}" }
            canvas.nativeCanvas.drawText(lbl, 2f, y + txtSz / 2.5f, paint)
        }
    }
}

/** Horizontal grid lines + X-axis time labels — drawn in the scrollable canvas. */
private fun DrawScope.drawScrollableGrid(
    timestamps: List<Long>,
    minValue: Float,
    maxValue: Float,
    bottomPad: Float,
    topPad: Float
) {
    val plotH = size.height - bottomPad - topPad
    val plotW = size.width
    val txtSz = 22f

    for (i in 0..4) {
        val y = topPad + plotH * (1f - i / 4f)
        drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, y), Offset(plotW, y), 1f)
    }

    if (timestamps.size < 2) return
    val tStep = maxOf(1, (timestamps.size - 1) / 4)
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            color     = android.graphics.Color.argb(160, 180, 180, 180)
            textSize  = txtSz
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        timestamps.indices.filter { it % tStep == 0 || it == timestamps.lastIndex }.forEach { i ->
            val x = if (timestamps.size > 1) i.toFloat() / (timestamps.size - 1) * plotW else 0f
            canvas.nativeCanvas.drawText(timeFmt.format(Date(timestamps[i])), x, size.height - 4f, paint)
        }
    }
}

private fun DrawScope.drawSeriesLine(
    values: List<Float>,
    minValue: Float,
    range: Float,
    color: Color,
    bottomPad: Float,
    topPad: Float,
    highlightLast: Boolean = false
) {
    if (values.size < 2) return
    val plotW = size.width
    val plotH = size.height - bottomPad - topPad
    val stepX = plotW / (values.size - 1)

    val pts = values.mapIndexed { i, v ->
        Offset(i * stepX, topPad + plotH - ((v - minValue) / range * plotH))
    }

    drawPath(Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } },
        color, style = Stroke(2.dp.toPx()))

    val dotR = 3.dp.toPx()
    pts.forEach { p -> drawCircle(color, dotR, p); drawCircle(Color.Black, dotR * 0.45f, p) }

    if (highlightLast) {
        val last = pts.last()
        drawLine(color.copy(alpha = 0.4f), Offset(last.x, topPad), Offset(last.x, topPad + plotH), 1.dp.toPx())
        drawCircle(color.copy(alpha = 0.25f), 7.dp.toPx(), last)
        drawCircle(color, 4.dp.toPx(), last)
        drawCircle(Color.White, 2.dp.toPx(), last)
    }
}

/**
 * A thin vertical tick at every sample where [LiveTelemetryData.gcCount] rose since the
 * previous sample — i.e. a GC actually ran in that interval, not just that GCs have happened
 * at some point (gcCount is cumulative since process start).
 */
private fun DrawScope.drawGcOccurredMarkers(data: List<LiveTelemetryData>, bottomPad: Float, topPad: Float) {
    if (data.size < 2) return
    val plotW = size.width
    val plotH = size.height - bottomPad - topPad
    val stepX = plotW / (data.size - 1)
    val color = Color(0xFFFF7043)

    for (i in 1 until data.size) {
        if (data[i].gcCount <= data[i - 1].gcCount) continue
        val x = i * stepX
        val blocking = data[i].blockingGcCount > data[i - 1].blockingGcCount
        drawLine(color.copy(alpha = if (blocking) 0.8f else 0.4f), Offset(x, topPad), Offset(x, topPad + plotH), 1.dp.toPx())
        drawCircle(color, 2.5.dp.toPx(), Offset(x, topPad + 3.dp.toPx()))
    }
}

// ---------------------------------------------------------------------------
// Graph composables  (horizontally scrollable, fixed Y-axis overlay)
// ---------------------------------------------------------------------------

private const val POINT_WIDTH_DP = 8
private const val Y_AXIS_WIDTH_DP = 36
private const val FRAME_BREAKDOWN_MAX_MS = 30f

/**
 * Inner scroll + jump-to-latest logic shared by both graph types.
 *
 * [autoScroll] is the user's *intent*, owned by the caller so it persists across tab switches:
 *   true  → graph pins to latest datapoint and follows new data (default)
 *   false → user scrolled into history; a "→ Latest" button is shown to re-enable
 *
 * Crucially, [autoScroll] is only flipped by *user drags*. Programmatic scrolls also set
 * isScrollInProgress — including the auto-scroll effect's own scrollTo(0) on a fresh
 * scrollState right after a tab switch, before the canvas is laid out — so watching
 * isScrollInProgress alone turned auto-follow off on every tab switch.
 */
@Composable
private fun GraphContainer(
    totalWidthDp: Int,
    autoScroll: MutableState<Boolean>,
    modifier: Modifier = Modifier,
    content: @Composable (scrollState: ScrollState) -> Unit
) {
    val scrollState = rememberScrollState()
    val scope       = rememberCoroutineScope()
    val atEndThresholdPx = with(LocalDensity.current) { (POINT_WIDTH_DP * 4).dp.roundToPx() }

    // After a user drag (and the fling that may follow it) settles, decide intent from the
    // final resting position.
    LaunchedEffect(scrollState) {
        scrollState.interactionSource.interactions
            .filter { it is DragInteraction.Stop || it is DragInteraction.Cancel }
            .collectLatest {
                snapshotFlow { scrollState.isScrollInProgress }.first { inProgress -> !inProgress }
                autoScroll.value = (scrollState.maxValue - scrollState.value) <= atEndThresholdPx
            }
    }
    // When canvas width grows (new data laid out) OR tab is switched, pin to end if auto-scrolling.
    LaunchedEffect(scrollState.maxValue, autoScroll.value) {
        if (autoScroll.value) scrollState.scrollTo(scrollState.maxValue)
    }

    Box(modifier = modifier) {
        content(scrollState)

        // "Jump to latest" button — visible only while viewing history
        if (!autoScroll.value) {
            Text(
                text = "→ Latest",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp, top = 4.dp)
                    .background(Color(0xFF444444).copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                    .clickable {
                        autoScroll.value = true   // re-enable; persists to other tabs
                        scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
internal fun TelemetryGraph(
    data: List<LiveTelemetryData>,
    metricType: TelemetryMetricType,
    autoScroll: MutableState<Boolean>,
    modifier: Modifier = Modifier
) {
    val totalWidthDp = (data.size * POINT_WIDTH_DP).coerceAtLeast(300)
    val values   = if (data.size >= 2) data.map { primaryValue(it, metricType) } else emptyList()
    val maxValue = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val minValue = values.minOrNull() ?: 0f
    val range    = (maxValue - minValue).coerceAtLeast(1f)
    val B = 28f; val T = 4f

    GraphContainer(
        totalWidthDp = totalWidthDp,
        autoScroll   = autoScroll,
        modifier     = modifier.background(Color(0xFF1A1A1A))
    ) { scrollState ->
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState)
                .width(totalWidthDp.dp)
        ) {
            if (values.size < 2) return@Canvas
            drawScrollableGrid(data.map { it.timestamp }, minValue, maxValue, B, T)
            drawSeriesLine(values, minValue, range, metricLineColor(values.last(), metricType), B, T, highlightLast = true)
        }
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(Y_AXIS_WIDTH_DP.dp)
                .background(Color(0xFF1A1A1A).copy(alpha = 0.9f))
        ) {
            drawYAxisLabels(minValue, maxValue, B, T)
        }
    }
}

@Composable
internal fun TelemetryMemoryGraph(
    data: List<LiveTelemetryData>,
    visibleSeries: Set<MemorySeries>,
    autoScroll: MutableState<Boolean>,
    modifier: Modifier = Modifier
) {
    val totalWidthDp = (data.size * POINT_WIDTH_DP).coerceAtLeast(300)
    val series = MemorySeries.entries
        .filter { it in visibleSeries }
        .map { ms -> GraphSeries(ms.label, ms.color, data.map { ms.valueOf(it) }) }
    val maxValue = series.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val B = 28f; val T = 4f

    Column(modifier = modifier) {
        GraphContainer(
            totalWidthDp = totalWidthDp,
            autoScroll   = autoScroll,
            modifier     = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1A1A1A))
        ) { scrollState ->
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(scrollState)
                    .width(totalWidthDp.dp)
            ) {
                if (data.size < 2) return@Canvas
                drawScrollableGrid(data.map { it.timestamp }, 0f, maxValue, B, T)
                series.forEachIndexed { idx, s ->
                    drawSeriesLine(s.values, 0f, maxValue, s.color, B, T, highlightLast = idx == 0)
                }
                drawGcOccurredMarkers(data, B, T)
            }
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(Y_AXIS_WIDTH_DP.dp)
                    .background(Color(0xFF1A1A1A).copy(alpha = 0.9f))
            ) {
                drawYAxisLabels(0f, maxValue, B, T)
            }
        }
        Row(
            modifier = Modifier.padding(start = (Y_AXIS_WIDTH_DP + 4).dp, top = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).background(s.color, CircleShape))
                    Text(s.label, color = Color.LightGray, fontSize = 9.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Frame breakdown graph — one stacked bar per frame, segments = FrameStage
// ---------------------------------------------------------------------------

/**
 * One vertical stacked bar per captured frame, segment height = that stage's duration.
 * Only stages in [visibleStages] contribute to a bar's height — deselecting a stage in
 * [FrameStageSelector] shrinks bars rather than hiding them, so the remaining stages stay
 * comparable frame-to-frame. The horizontal line marks the current display's one-refresh
 * deadline (e.g. ~16.6ms @60Hz), same role as the green line in "Profile GPU rendering".
 */
@Composable
internal fun FrameBreakdownGraph(
    data: List<FrameStageSample>,
    visibleStages: Set<FrameStage>,
    deadlineNanos: Long,
    autoScroll: MutableState<Boolean>,
    modifier: Modifier = Modifier
) {
    val totalWidthDp = (data.size * POINT_WIDTH_DP).coerceAtLeast(300)
    val stages = FrameStage.entries.filter { it in visibleStages }
    val deadlineMs = deadlineNanos / 1_000_000f
    // Fixed range, not data-driven — a max that rescales with every new frame makes bars
    // impossible to compare visually. 30ms comfortably fits ~2 dropped 60Hz frames; a bar
    // that would exceed it is clipped (see the budget loop below) rather than shrinking the
    // whole graph to fit it.
    val maxMs = FRAME_BREAKDOWN_MAX_MS
    val B = 28f; val T = 4f

    GraphContainer(
        totalWidthDp = totalWidthDp,
        autoScroll   = autoScroll,
        modifier     = modifier.background(Color(0xFF1A1A1A))
    ) { scrollState ->
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState)
                .width(totalWidthDp.dp)
        ) {
            if (data.isEmpty()) return@Canvas
            drawScrollableGrid(data.map { it.timestampMillis }, 0f, maxMs, B, T)

            val plotH = size.height - B - T
            val barW  = size.width / data.size

            data.forEachIndexed { i, sample ->
                var yCursor = size.height - B
                var remainingH = plotH   // clips bars whose stacked total exceeds maxMs
                val x = i * barW
                stages.forEach { stage ->
                    if (remainingH <= 0f) return@forEach
                    val stageMs = stage.nanosOf(sample) / 1_000_000f
                    if (stageMs <= 0f) return@forEach
                    val h = ((stageMs / maxMs) * plotH).coerceAtMost(remainingH)
                    drawRect(
                        color   = stage.color,
                        topLeft = Offset(x + barW * 0.1f, yCursor - h),
                        size    = Size(barW * 0.8f, h)
                    )
                    yCursor -= h
                    remainingH -= h
                }
            }

            // One-refresh-interval deadline — same role as the green line in the on-device profiler.
            val deadlineY = T + plotH * (1f - (deadlineMs / maxMs))
            drawLine(Color(0xFF4CAF50).copy(alpha = 0.7f), Offset(0f, deadlineY), Offset(size.width, deadlineY), 1.5.dp.toPx())
        }
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(Y_AXIS_WIDTH_DP.dp)
                .background(Color(0xFF1A1A1A).copy(alpha = 0.9f))
        ) {
            drawYAxisLabels(0f, maxMs, B, T)
        }
    }
}

/** Legend, doubling as a static reference for what each color in [FrameBreakdownGraph] means. */
@Composable
private fun FrameStageLegend(visibleStages: Set<FrameStage>, modifier: Modifier = Modifier) {
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(FrameStage.entries.filter { it in visibleStages }) { stage ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp).background(stage.color, CircleShape))
                Text(stage.label, color = Color.LightGray, fontSize = 9.sp)
            }
        }
    }
}

/**
 * Multi-select dropdown for which [FrameStage] segments show in [FrameBreakdownGraph], plus
 * an "All segments" row that toggles every stage on/off in one tap.
 */
@Composable
internal fun FrameStageSelector(
    selected: Set<FrameStage>,
    onSelectionChanged: (Set<FrameStage>) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val allSelected = selected.size == FrameStage.entries.size

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("Segments: ", color = Color(0xFF9E9E9E), fontSize = 10.sp)
            Text(
                text = if (allSelected) "All" else "${selected.size}/${FrameStage.entries.size}",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
            Text("▾", color = Color(0xFF9E9E9E), fontSize = 10.sp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF2C2C2C))
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (allSelected) "Clear all" else "All segments",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                onClick = { onSelectionChanged(if (allSelected) emptySet() else FrameStage.entries.toSet()) }
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
            FrameStage.entries.forEach { stage ->
                val isChecked = stage in selected
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).background(stage.color, CircleShape))
                            Text(
                                text = stage.label,
                                color = if (isChecked) Color.White else Color(0xFF888888),
                                fontSize = 11.sp,
                                fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (isChecked) Text("✓", color = Color(0xFF4CAF50), fontSize = 11.sp)
                        }
                    },
                    onClick = { onSelectionChanged(if (isChecked) selected - stage else selected + stage) }
                )
            }
        }
    }
}

private val FRAME_AVERAGE_WINDOW_OPTIONS = listOf(15, 30, 60)

/** Single-select of how many frames get averaged into one [FrameBreakdownGraph] bar. */
@Composable
internal fun FrameAverageWindowSelector(
    selected: Int,
    onSelectionChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Avg: ", color = Color(0xFF9E9E9E), fontSize = 10.sp)
        FRAME_AVERAGE_WINDOW_OPTIONS.forEach { window ->
            val isSelected = window == selected
            Text(
                text = "${window}f",
                color = if (isSelected) Color.White else Color(0xFF888888),
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 10.sp,
                modifier = Modifier
                    .background(
                        if (isSelected) Color(0xFFE23744) else Color.White.copy(alpha = 0.05f),
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { onSelectionChanged(window) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Multi-select dropdown for which [MemorySeries] lines show in [TelemetryMemoryGraph], plus
 * an "All series" row that toggles all three on/off in one tap. Same shape as [FrameStageSelector].
 */
@Composable
internal fun MemorySeriesSelector(
    selected: Set<MemorySeries>,
    onSelectionChanged: (Set<MemorySeries>) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val allSelected = selected.size == MemorySeries.entries.size

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("Series: ", color = Color(0xFF9E9E9E), fontSize = 10.sp)
            Text(
                text = if (allSelected) "All" else "${selected.size}/${MemorySeries.entries.size}",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
            Text("▾", color = Color(0xFF9E9E9E), fontSize = 10.sp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF2C2C2C))
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (allSelected) "Clear all" else "All series",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                onClick = { onSelectionChanged(if (allSelected) emptySet() else MemorySeries.entries.toSet()) }
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
            MemorySeries.entries.forEach { ms ->
                val isChecked = ms in selected
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).background(ms.color, CircleShape))
                            Text(
                                text = ms.label,
                                color = if (isChecked) Color.White else Color(0xFF888888),
                                fontSize = 11.sp,
                                fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (isChecked) Text("✓", color = Color(0xFF4CAF50), fontSize = 11.sp)
                        }
                    },
                    onClick = { onSelectionChanged(if (isChecked) selected - ms else selected + ms) }
                )
            }
        }
    }
}

/** Small "⟲ GC" button — requests a manual GC via [LiveTelemetryCollector.triggerManualGc]. */
@Composable
internal fun TriggerGcButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = "⟲ GC",
        color = Color(0xFFFF7043),
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

// ---------------------------------------------------------------------------
// Secondary info row
// ---------------------------------------------------------------------------

@Composable
private fun SecondaryInfoRow(data: LiveTelemetryData?, type: TelemetryMetricType) {
    if (data == null) return
    val items: List<Pair<String, Color>> = when (type) {
        TelemetryMetricType.FPS     -> listOf("Slow: ${data.slowFrames}" to Color(0xFFFFB300), "Frozen: ${data.frozenFrames}" to Color(0xFFE23744), "Threads: ${data.threadCount}" to Color(0xFF9E9E9E))
        TelemetryMetricType.UI_FPS          -> listOf("Slow: ${data.slowFrames}" to Color(0xFFFFB300), "Frozen: ${data.frozenFrames}" to Color(0xFFE23744), "Threads: ${data.threadCount}" to Color(0xFF9E9E9E))
        TelemetryMetricType.FRAME_BREAKDOWN -> emptyList()   // per-frame legend is shown next to the graph instead
        TelemetryMetricType.MEMORY  -> listOf("Avail: ${data.availMemMB} MB" to Color(0xFF4FC3F7), "Low mem: ${data.isLowMemory}" to if (data.isLowMemory) Color(0xFFE23744) else Color(0xFF4CAF50))
        TelemetryMetricType.CPU     -> listOf("Threads: ${data.threadCount}" to Color(0xFF9E9E9E), "Slow frames: ${data.slowFrames}" to Color(0xFFFFB300))
        TelemetryMetricType.NETWORK -> listOf("↓ ${"%.1f".format(data.downloadKBps)} KBps" to Color(0xFF4CAF50), "↑ ${"%.1f".format(data.uploadKBps)} KBps" to Color(0xFFFFB300))
        TelemetryMetricType.GC      -> listOf(
            "Count: ${data.gcCount}" to Color(0xFF9E9E9E),
            "Total: ${data.gcTimeMs} ms" to Color(0xFF4FC3F7),
            "Blocking: ${data.blockingGcCount}" to Color(0xFFFFB300),
            "Blocked: ${data.blockingGcTimeMs} ms" to if (data.blockingGcTimeMs > 0) Color(0xFFE23744) else Color(0xFF4CAF50)
        )
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (label, color) -> Text(label, color = color, fontSize = 10.sp) }
    }
}

// ---------------------------------------------------------------------------
// Activity selector dropdown
// ---------------------------------------------------------------------------

// "" sentinel = "All" (unfiltered across all activities)
private const val SELECTOR_ALL = ""

@Composable
private fun ActivitySelectorRow(
    activityNames: List<String>,
    selectedActivity: String,
    currentActivityName: String,
    onActivitySelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isAll       = selectedActivity == SELECTOR_ALL
    val displayName = if (isAll) "All" else selectedActivity.toSimpleName()
    val isCurrent   = !isAll && selectedActivity == currentActivityName

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Screen: ", color = Color(0xFF9E9E9E), fontSize = 10.sp)
            Text(
                text = displayName,
                color = when {
                    isAll     -> Color(0xFF4FC3F7)
                    isCurrent -> Color(0xFF4CAF50)
                    else      -> Color.White
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isCurrent) Text(" ●", color = Color(0xFF4CAF50), fontSize = 8.sp)
            Spacer(Modifier.width(4.dp))
            Text("▾", color = Color(0xFF9E9E9E), fontSize = 10.sp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF2C2C2C))
        ) {
            // "All" entry
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).background(Color(0xFF4FC3F7), CircleShape))
                        Text(
                            text = "All activities",
                            color = if (isAll) Color.White else Color(0xFFCCCCCC),
                            fontSize = 11.sp,
                            fontWeight = if (isAll) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                },
                onClick = { onActivitySelected(SELECTOR_ALL); expanded = false }
            )
            activityNames.forEach { name ->
                val isCur = name == currentActivityName
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isCur) Box(Modifier.size(6.dp).background(Color(0xFF4CAF50), CircleShape))
                            else       Box(Modifier.size(6.dp).background(Color(0xFF555555), CircleShape))
                            Text(
                                text = name.toSimpleName(),
                                color = if (name == selectedActivity) Color.White else Color(0xFFCCCCCC),
                                fontSize = 11.sp,
                                fontWeight = if (name == selectedActivity) FontWeight.Bold else FontWeight.Normal
                            )
                            if (isCur) Text("(current)", color = Color(0xFF4CAF50), fontSize = 9.sp)
                        }
                    },
                    onClick = { onActivitySelected(name); expanded = false }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Collapsed pill
// ---------------------------------------------------------------------------

@Composable
internal fun CollapsedPill(
    telemetryData: List<LiveTelemetryData>,
    onExpand: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    val last = telemetryData.lastOrNull()
    val fps  = last?.uiFps ?: 0f   // UI FPS supersedes the hidden FPS tab here too
    val cpu  = last?.cpuPercent ?: 0f
    val mem  = last?.let { (it.javaHeapMB + it.nativeHeapMB + it.graphicsMB).toInt() } ?: 0

    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { onExpand() }) }
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); onDrag(drag.x, drag.y) } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("⠿", color = Color.Gray, fontSize = 13.sp)
        Text("${fps.toInt()} fps", color = metricLineColor(fps, TelemetryMetricType.UI_FPS), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("·", color = Color.DarkGray, fontSize = 14.sp)
        Text("${"%.0f".format(cpu)}% cpu", color = metricLineColor(cpu, TelemetryMetricType.CPU), fontSize = 11.sp)
        Text("·", color = Color.DarkGray, fontSize = 14.sp)
        Text("${mem}MB", color = Color(0xFF4FC3F7), fontSize = 11.sp)
        Text("⤢", color = Color.LightGray, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------------------
// Main overlay content
// ---------------------------------------------------------------------------

@Composable
internal fun TelemetryOverlayContent(
    allTelemetryData: List<LiveTelemetryData>,
    frameStageData: List<FrameStageSample>,
    frameDeadlineNanos: Long,
    selectedFrameStages: Set<FrameStage>,
    onFrameStagesSelected: (Set<FrameStage>) -> Unit,
    frameAverageWindow: Int,
    onFrameAverageWindowSelected: (Int) -> Unit,
    selectedMemorySeries: Set<MemorySeries>,
    onMemorySeriesSelected: (Set<MemorySeries>) -> Unit,
    onTriggerGc: () -> Unit,
    currentActivityName: String,
    selectedActivity: String,
    onActivitySelected: (String) -> Unit,
    selectedMetric: TelemetryMetricType,
    onMetricSelected: (TelemetryMetricType) -> Unit,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit
) {
    if (isCollapsed) {
        CollapsedPill(telemetryData = allTelemetryData, onExpand = onToggleCollapse, onDrag = onDrag)
        return
    }

    val activityNames = remember(allTelemetryData.size) {
        allTelemetryData.map { it.activityName }.distinct().filter { it.isNotEmpty() }
    }
    val filteredData = if (selectedActivity == SELECTOR_ALL) allTelemetryData.toList()
                       else allTelemetryData.filter { it.activityName == selectedActivity }
    val filteredFrameStageData = if (selectedActivity == SELECTOR_ALL) frameStageData.toList()
                                 else frameStageData.filter { it.activityName == selectedActivity }

    val last         = filteredData.lastOrNull()
    val currentValue = last?.let { primaryValue(it, selectedMetric) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {

            // ── Header ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selectedMetric.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (currentValue != null && selectedMetric != TelemetryMetricType.FRAME_BREAKDOWN) {
                        Text(
                            text = formatCurrentValue(currentValue, selectedMetric),
                            color = metricLineColor(currentValue, selectedMetric),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Export session report (CSV via email/share sheet)
                    Text("⇪", color = Color(0xFF4FC3F7), fontSize = 16.sp, modifier = Modifier.clickable { onExport() })
                    Text("−", color = Color.LightGray, fontSize = 18.sp, modifier = Modifier.clickable { onToggleCollapse() })
                    Text("✕", color = Color.Red,       fontSize = 14.sp, modifier = Modifier.clickable { onClose() })
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── Activity selector ──────────────────────────────────────────
            ActivitySelectorRow(
                activityNames       = activityNames,
                selectedActivity    = selectedActivity,
                currentActivityName = currentActivityName,
                onActivitySelected  = onActivitySelected
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ── Graph — fixed height so chips are never pushed off ─────────
            // autoScroll persists across tab switches — owned here, not inside graph composable
            val autoScroll = remember { mutableStateOf(true) }
            val graphHeight = if (selectedMetric == TelemetryMetricType.MEMORY) 160.dp else 180.dp
            when (selectedMetric) {
                TelemetryMetricType.MEMORY -> TelemetryMemoryGraph(
                    data          = filteredData,
                    visibleSeries = selectedMemorySeries,
                    autoScroll    = autoScroll,
                    modifier      = Modifier.height(graphHeight).fillMaxWidth()
                )
                TelemetryMetricType.FRAME_BREAKDOWN -> FrameBreakdownGraph(
                    data          = filteredFrameStageData,
                    visibleStages = selectedFrameStages,
                    deadlineNanos = frameDeadlineNanos,
                    autoScroll    = autoScroll,
                    modifier      = Modifier.height(graphHeight).fillMaxWidth()
                )
                else -> TelemetryGraph(
                    data       = filteredData,
                    metricType = selectedMetric,
                    autoScroll = autoScroll,
                    modifier   = Modifier.height(graphHeight).fillMaxWidth()
                )
            }

            // ── Secondary stats ─────────────────────────────────────────────
            when (selectedMetric) {
                TelemetryMetricType.FRAME_BREAKDOWN -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FrameStageLegend(visibleStages = selectedFrameStages, modifier = Modifier.weight(1f))
                    FrameAverageWindowSelector(selected = frameAverageWindow, onSelectionChanged = onFrameAverageWindowSelected)
                    Spacer(modifier = Modifier.width(6.dp))
                    FrameStageSelector(selected = selectedFrameStages, onSelectionChanged = onFrameStagesSelected)
                }
                TelemetryMetricType.MEMORY -> Column(modifier = Modifier.fillMaxWidth()) {
                    SecondaryInfoRow(data = last, type = selectedMetric)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MemorySeriesSelector(selected = selectedMemorySeries, onSelectionChanged = onMemorySeriesSelected)
                        TriggerGcButton(onClick = onTriggerGc)
                    }
                }
                else -> SecondaryInfoRow(data = last, type = selectedMetric)
            }

            // ── Metric chips — always visible ──────────────────────────────
            // FPS is hidden here — UI_FPS supersedes it, but the enum value (and the raw
            // `fps` field it reads) stays around for the CSV export and the collapsed pill.
            val visibleMetricTypes = remember { TelemetryMetricType.entries.filter { it != TelemetryMetricType.FPS } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                items(visibleMetricTypes) { type ->
                    FilterChip(
                        selected = selectedMetric == type,
                        onClick  = { onMetricSelected(type) },
                        label    = { Text(type.label, fontSize = 10.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE23744),
                            selectedLabelColor     = Color.White,
                            labelColor             = Color.LightGray
                        )
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Manager
// ---------------------------------------------------------------------------

internal class TelemetryOverlayManager private constructor(context: Context) : Application.ActivityLifecycleCallbacks {

    private val appContext = context.applicationContext

    // ── Persistent state (survives activity transitions) ───────────────────
    private val persistentData     = mutableStateListOf<LiveTelemetryData>()
    private val frameStageData     = mutableStateListOf<FrameStageSample>()   // per-frame breakdown, "Frame Bars" tab
    private val selectedFrameStages = mutableStateOf(FrameStage.entries.toSet())   // default: all segments ("All")
    private val frameAverageWindow = mutableStateOf(LiveTelemetryCollector.DEFAULT_FRAME_STAGE_AVERAGE_WINDOW)
    private val selectedMemorySeries = mutableStateOf(MemorySeries.entries.toSet())   // default: all series ("All")
    private val isCollapsed        = mutableStateOf(false)
    private val selectedMetric     = mutableStateOf(TelemetryMetricType.UI_FPS)   // FPS tab is hidden — this is the default now
    private val selectedActivity   = mutableStateOf(SELECTOR_ALL)               // SELECTOR_ALL = show all activities
    private val currentActivityRef = mutableStateOf("")                         // tracks foreground activity

    private val managerScope = CoroutineScope(Dispatchers.Main)
    private var dataJob: Job? = null
    private var frameStageJob: Job? = null

    private var pillX = -1
    private var pillY = 300

    // ComposeView context + ViewTree owners for the panel, even when hosted on a sheet window.
    private var currentActivity: WeakReference<Activity>? = null

    private val overlayViews = ConcurrentHashMap<String, WeakReference<View>>()

    // Sheet / dialog windows hosting the panel, outermost first. A stack because a dialog can
    // open over a sheet: the panel rides the top one and falls back to the one below on dismiss.
    // Main thread only.
    private val hostedWindows = ArrayList<Window>()

    companion object {
        private const val MAX_DATA_POINTS = 2000
        // Per-frame samples, not per-second — 300 covers several seconds even at 120Hz, enough
        // for a debug bar chart without letting the rolling buffer grow unbounded.
        private const val MAX_FRAME_STAGE_SAMPLES = 1000
        private const val TAG = "telemetry_overlay_view"
        // overlayViews key prefix for sheet-hosted panels (+ window identity). Can't collide
        // with an Activity class name.
        private const val WINDOW_OVERLAY_KEY_PREFIX = "__compose_window_overlay__"

        @Volatile private var instance: TelemetryOverlayManager? = null
        fun getInstance(context: Context): TelemetryOverlayManager =
            instance ?: synchronized(this) {
                instance ?: TelemetryOverlayManager(context).also { instance = it }
            }

        fun instanceOrNull(): TelemetryOverlayManager? = instance
    }

    var isEnabled: Boolean = false
        set(value) {
            // Re-enabling must not register the lifecycle callbacks (and collectors) twice.
            if (field == value) return
            field = value
            if (value) {
                (appContext.applicationContext as? Application)?.registerActivityLifecycleCallbacks(this)
                LiveTelemetryCollector.startLiveTelemetry()
                startDataCollection()
                startFrameStageCollection()
            } else {
                (appContext.applicationContext as? Application)?.unregisterActivityLifecycleCallbacks(this)
                LiveTelemetryCollector.stopLiveTelemetry()
                dataJob?.cancel(); dataJob = null
                frameStageJob?.cancel(); frameStageJob = null
                persistentData.clear()
                frameStageData.clear()
                selectedFrameStages.value = FrameStage.entries.toSet()
                frameAverageWindow.value = LiveTelemetryCollector.DEFAULT_FRAME_STAGE_AVERAGE_WINDOW
                LiveTelemetryCollector.frameStageAverageWindow = LiveTelemetryCollector.DEFAULT_FRAME_STAGE_AVERAGE_WINDOW
                selectedMemorySeries.value = MemorySeries.entries.toSet()
                isCollapsed.value = false
                selectedMetric.value = TelemetryMetricType.UI_FPS
                selectedActivity.value = ""
                currentActivityRef.value = ""
                overlayViews.forEach { (_, ref) ->
                    ref.get()?.let { v ->
                        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
                        catch (_: Exception) { }
                    }
                }
                overlayViews.clear()
                hostedWindows.clear()
            }
        }

    private fun startDataCollection() {
        if (dataJob?.isActive == true) return
        dataJob = managerScope.launch {
            LiveTelemetryCollector.telemetryFlow.collect { data ->
                // Rolling 1000-point buffer across entire session
                if (persistentData.size >= MAX_DATA_POINTS) persistentData.removeAt(0)
                persistentData.add(data)
            }
        }
    }

    private fun startFrameStageCollection() {
        if (frameStageJob?.isActive == true) return
        frameStageJob = managerScope.launch {
            LiveTelemetryCollector.frameStageFlow.collect { sample ->
                if (frameStageData.size >= MAX_FRAME_STAGE_SAMPLES) frameStageData.removeAt(0)
                frameStageData.add(sample)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
        val name = activity.javaClass.name
        LiveTelemetryCollector.currentActivityName = name
        currentActivityRef.value = name
        // If user pinned to a specific activity (not "All"), follow the new foreground activity.
        // If user explicitly chose "All", keep it.
        if (selectedActivity.value != SELECTOR_ALL) {
            selectedActivity.value = name
        }
        if (isEnabled) {
            // A sheet survives pause/resume — if one is open, the panel goes back on it.
            val sheet = topHostedWindowOf(activity)
            if (sheet != null) hostOnWindow(activity, sheet) else show(activity)
            LiveTelemetryCollector.attachFrameMetrics(activity.window)
            LiveTelemetryCollector.attachUiFpsTracking(name, activity.window)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        LiveTelemetryCollector.detachFrameMetrics(activity.window)
        LiveTelemetryCollector.detachUiFpsTracking(activity.javaClass.name)
        hide(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        overlayViews.remove(activity.javaClass.name)
        LiveTelemetryCollector.removeUiFpsTracking(activity.javaClass.name)
    }

    private fun makeParams(collapsed: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (collapsed) {
                gravity    = Gravity.TOP or Gravity.START
                x          = pillX
                y          = pillY
            } else {
                gravity = Gravity.BOTTOM
            }
        }

    private fun applyParams(activity: Activity, view: View, collapsed: Boolean, token: IBinder) {
        val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = makeParams(collapsed)
        // Keep the original token so a sheet-hosted panel stays above that sheet.
        params.token = token
        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val activityKey = activity.javaClass.name
        if (overlayViews[activityKey]?.get() != null) return

        // windowToken is null until ViewRootImpl.setView() completes after onResume — post to next frame
        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val token = activity.window.decorView.windowToken ?: return@post
            addOverlay(activity, token, activityKey, activity.windowManager)
        }
    }

    /**
     * Hosts the panel on [window] — a Compose `ModalBottomSheet` / `Dialog` window.
     */
    fun attachOverlayToWindow(window: Window) {
        if (!isEnabled) return
        val activity = currentActivity?.get() ?: return
        val decor = window.decorView
        // As in show(): no token until the window's ViewRootImpl is set up.
        decor.post {
            if (!isEnabled) return@post
            val token = decor.windowToken ?: return@post
            hostedWindows.lastOrNull()?.let { removeOverlay(windowOverlayKey(it)) }
            // Also drop the Activity's panel — a resume may have re-added it under a sheet.
            hide(activity)
            hostedWindows.remove(window)
            hostedWindows.add(window)
            addOverlay(activity, token, windowOverlayKey(window), window.windowManager)
        }
    }

    /**
     * Counterpart to [attachOverlayToWindow]. Hands the panel to the Activity's next open sheet,
     * else the Activity itself.
     */
    fun detachOverlayFromWindow(window: Window) {
        hostedWindows.remove(window)
        removeOverlay(windowOverlayKey(window))
        if (!isEnabled) return
        val activity = currentActivity?.get() ?: return
        // Sheets of back-stack Activities aren't visible — only the current Activity's count.
        val next = topHostedWindowOf(activity)
        if (next == null) show(activity) else hostOnWindow(activity, next)
    }

    /** The innermost hosted sheet/dialog window [activity] owns, if it has one open. */
    private fun topHostedWindowOf(activity: Activity): Window? =
        hostedWindows.lastOrNull { it.owningActivity() === activity }

    /** (Re-)adds the panel on an already hosted [window], once its token is available. */
    private fun hostOnWindow(activity: Activity, window: Window) {
        val decor = window.decorView
        decor.post {
            if (!isEnabled || topHostedWindowOf(activity) !== window) return@post
            val token = decor.windowToken ?: return@post
            addOverlay(activity, token, windowOverlayKey(window), window.windowManager)
        }
    }

    private fun windowOverlayKey(window: Window): String =
        "$WINDOW_OVERLAY_KEY_PREFIX${System.identityHashCode(window)}"

    /** Removes the panel registered under [key], if it is still around. */
    private fun removeOverlay(key: String) {
        val view = overlayViews.remove(key)?.get() ?: return
        try {
            (view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
        }
    }

    /** Adds the panel as a sub-window of [token] via [wm] (the Activity's or the sheet's). */
    private fun addOverlay(activity: Activity, token: IBinder, key: String, wm: WindowManager) {
        if (overlayViews[key]?.get() != null) return

        if (pillX < 0) {
            val dm = activity.resources.displayMetrics
            pillX = (dm.widthPixels - (120 * dm.density).toInt()).coerceAtLeast(0)
        }

        val params = makeParams(isCollapsed.value).also { it.token = token }

        val composeView = ComposeView(activity)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        composeView.setViewTreeLifecycleOwner(activity as? LifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(activity as? SavedStateRegistryOwner)
        composeView.setViewTreeViewModelStoreOwner(activity as? ViewModelStoreOwner)
        composeView.setContent {
            TelemetryOverlayContent(
                allTelemetryData    = persistentData,
                frameStageData        = frameStageData,
                frameDeadlineNanos    = LiveTelemetryCollector.currentFrameDeadlineNanos(),
                selectedFrameStages   = selectedFrameStages.value,
                onFrameStagesSelected = { selectedFrameStages.value = it },
                frameAverageWindow    = frameAverageWindow.value,
                onFrameAverageWindowSelected = {
                    frameAverageWindow.value = it
                    LiveTelemetryCollector.frameStageAverageWindow = it
                },
                selectedMemorySeries   = selectedMemorySeries.value,
                onMemorySeriesSelected = { selectedMemorySeries.value = it },
                onTriggerGc            = { LiveTelemetryCollector.triggerManualGc() },
                currentActivityName = currentActivityRef.value,
                selectedActivity    = selectedActivity.value,
                onActivitySelected  = { selectedActivity.value = it },
                selectedMetric      = selectedMetric.value,
                onMetricSelected    = { selectedMetric.value = it },
                isCollapsed         = isCollapsed.value,
                onToggleCollapse    = {
                    val nowCollapsed = !isCollapsed.value
                    isCollapsed.value = nowCollapsed
                    applyParams(activity, composeView, nowCollapsed, token)
                },
                onDrag = { dx, dy ->
                    if (isCollapsed.value) {
                        pillX += dx.toInt(); pillY += dy.toInt()
                        applyParams(activity, composeView, true, token)
                    }
                },
                onExport = { exportReport() },
                onClose = {
                    this@TelemetryOverlayManager.isEnabled = false
                }
            )
        }

        try {
            wm.addView(composeView, params)
            overlayViews[key] = WeakReference(composeView)
        } catch (e: Exception) {
        }
    }

    /**
     * Snapshot the current session buffer, build CSV + summary off the main
     * thread, then open the email/share chooser.
     */
    private fun exportReport() {
        val snapshot = persistentData.toList()
        if (snapshot.isEmpty()) return
        managerScope.launch(Dispatchers.IO) {
            TelemetryReportGenerator.exportAndShare(appContext, snapshot)
        }
    }

    private fun hide(activity: Activity) {
        removeOverlay(activity.javaClass.name)
    }

    private val Activity.contentFrame: ViewGroup?
        get() = window?.decorView as? ViewGroup
}
