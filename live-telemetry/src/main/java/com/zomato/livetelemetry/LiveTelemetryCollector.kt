package com.zomato.livetelemetry

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.FrameMetrics
import android.view.Window
import com.zomato.livetelemetry.cpu.AdvancedCpuMonitor
import com.zomato.livetelemetry.memory.MemoryUtils
import com.zomato.livetelemetry.network.NetworkUsageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One per-second sample, emitted on [LiveTelemetryCollector.telemetryFlow] while telemetry is active.
 */
public data class LiveTelemetryData(
    val timestamp: Long,
    val activityName: String,   // fully-qualified class name of the foreground activity
    // Rendering
    val fps: Float,
    val uiFps: Float,           // perceived FPS via JankStats bucket-average (refresh-rate-corrected)
    val jankRate: Float,
    val slowFrames: Int,        // frames 16–700ms in collection interval
    val frozenFrames: Int,      // frames >700ms in collection interval
    // Memory
    val javaHeapMB: Long,
    val nativeHeapMB: Long,
    val graphicsMB: Long,
    val availMemMB: Long,       // system-wide available RAM
    val isLowMemory: Boolean,   // system low-memory pressure
    // GC
    val gcCount: Long,
    val gcTimeMs: Long,
    val blockingGcCount: Long,   // GCs that paused app threads
    val blockingGcTimeMs: Long,  // total time app threads were paused by GC
    // CPU
    val cpuPercent: Float,      // app process CPU %
    val threadCount: Int,       // live thread count for process
    // Network
    val downloadKBps: Double,
    val uploadKBps: Double,
    // System
    val thermalHeadroom: Float, // 0–1 (1 = no throttle risk), -1 = API unavailable
    val batteryCurrentMa: Int   // µA→mA, negative = discharging (device-dependent sign)
) {
    override fun toString(): String =
        "LiveTelemetryData(ts=$timestamp activity=$activityName fps=$fps uiFps=$uiFps jank=$jankRate slow=$slowFrames frozen=$frozenFrames " +
        "java=${javaHeapMB}MB native=${nativeHeapMB}MB gfx=${graphicsMB}MB avail=${availMemMB}MB lowMem=$isLowMemory " +
        "gc=$gcCount/${gcTimeMs}ms blockingGc=$blockingGcCount/${blockingGcTimeMs}ms cpu=${"%.1f".format(cpuPercent)}% threads=$threadCount " +
        "dl=${"%.1f".format(downloadKBps)} ul=${"%.1f".format(uploadKBps)} KBps " +
        "thermal=${"%.2f".format(thermalHeadroom)} batt=${batteryCurrentMa}mA)"
}

/**
 * Per-frame breakdown of where a single frame's time went, mirroring the stages shown by
 * "Profile GPU rendering" (Input → Animation → Measure/Layout → Draw → Sync&Upload → Issue
 * Commands → Swap Buffers). One of these is emitted per frame via [LiveTelemetryCollector.frameStageFlow].
 */
public data class FrameStageSample(
    val timestampMillis: Long,
    val activityName: String,
    val inputHandlingNanos: Long,
    val animationNanos: Long,
    val measureLayoutNanos: Long,
    val drawNanos: Long,
    val syncNanos: Long,
    val commandIssueNanos: Long,
    val swapBuffersNanos: Long,
    val totalNanos: Long
)

/**
 * Entry point of Live Telemetry.
 *
 * ```
 * LiveTelemetryCollector.initialize(application)      // once, e.g. in Application.onCreate
 * LiveTelemetryCollector.showTelemetryOverlay(activity) // from a debug menu, gesture, …
 * LiveTelemetryCollector.hideTelemetryOverlay(context)
 * ```
 */
public object LiveTelemetryCollector {

    private val _telemetryFlow = MutableSharedFlow<LiveTelemetryData>(replay = 1)
    /** Per-second samples while telemetry is active. Replays the latest sample. */
    public val telemetryFlow: SharedFlow<LiveTelemetryData> = _telemetryFlow.asSharedFlow()

    // Per-frame stage breakdown — high frequency (one per frame), so no replay and a small
    // drop-oldest buffer: a debug overlay collecting slowly should never apply backpressure
    // to the frame-metrics thread.
    private val _frameStageFlow = MutableSharedFlow<FrameStageSample>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Per-frame stage breakdown (averaged over a few frames) of the window on screen. */
    public val frameStageFlow: SharedFlow<FrameStageSample> = _frameStageFlow.asSharedFlow()

    private val cpuMonitor: AdvancedCpuMonitor by lazy { AdvancedCpuMonitor() }

    private var memoryUtils: MemoryUtils? = null
    private val networkUsageManager = NetworkUsageManager()
    private var appContext: Context? = null

    // Current foreground activity — set by TelemetryOverlayManager.onActivityResumed
    @Volatile internal var currentActivityName: String = ""

    // FPS — written on the frame-metrics thread, read on the collection thread.
    @Volatile private var lastFps = 0f
    @Volatile private var lastJankRate = 0f

    // UI FPS (JankStats bucket-average, refresh-rate-corrected) — written from JankStats'
    // own callback thread, read on the collection thread.
    @Volatile private var lastUiFps = 0f

    // One UiFpsAggregator per activity, keyed by activity class name. Recreating the
    // aggregator on every resume would reset its frame bucket each time, so it would rarely
    // accumulate enough frames to ever report — it is kept alive across pause/resume and only
    // torn down on destroy.
    private val uiFpsAggregatorMap: MutableMap<String, UiFpsAggregator> = HashMap()

    /**
     * Windows attached for frame metrics, bottom to top; the last one is on screen.
     */
    private class TrackedWindow(val key: String, val window: Window, val isSheet: Boolean)

    private val trackedWindows = ArrayList<TrackedWindow>()

    // Written on main, read on the frame-metrics thread.
    @Volatile private var measuredWindow: Window? = null

    // Number of frames averaged into one FrameStageSample before it's emitted — user-selectable
    // (15/30/60) from the "Frame Bars" tab. Touched from the UI thread (the selector) and read
    // from the frame-metrics thread; a stale read for one frame is harmless, so @Volatile is
    // enough — no need to serialize with the bucket fields below (those are only ever touched
    // on frameMetricsThread).
    @Volatile internal var frameStageAverageWindow: Int = DEFAULT_FRAME_STAGE_AVERAGE_WINDOW

    // Frame-stage averaging bucket — touched only on the frame-metrics thread.
    private var stageBucketCount = 0
    private var stageBucketActivityName = ""
    private var stageBucketInputNanos = 0L
    private var stageBucketAnimationNanos = 0L
    private var stageBucketMeasureLayoutNanos = 0L
    private var stageBucketDrawNanos = 0L
    private var stageBucketSyncNanos = 0L
    private var stageBucketCommandIssueNanos = 0L
    private var stageBucketSwapBuffersNanos = 0L
    private var stageBucketTotalNanos = 0L

    private val ONE_SECOND_NANOS = TimeUnit.SECONDS.toNanos(1)
    private const val FROZEN_FRAME_THRESHOLD_NANOS = 700_000_000L
    private const val DEFAULT_REFRESH_RATE = 60f
    // 60Hz frame deadline; overridden per-window from the display refresh rate on attach.
    private const val DEFAULT_FRAME_DEADLINE_NANOS = 16_666_667L
    internal const val DEFAULT_FRAME_STAGE_AVERAGE_WINDOW = 30

    // FrameMetrics counters — incremented from main-thread listener, drained on collection thread.
    // pendingSlow/Frozen are reported as raw per-collection-interval counts by collectData.
    private val pendingSlowFrames = AtomicInteger(0)
    private val pendingFrozenFrames = AtomicInteger(0)

    // FPS window state — touched only on the frame-metrics thread (listener + posted resets).
    private var windowFrameCount = 0
    private var windowJankyFrames = 0
    private var windowTotalDurationNanos = 0L  // sum of per-frame durations, each clamped to >= frameDeadlineNanos
    private var windowStartNanos = System.nanoTime()

    // Frame deadline for slow-frame classification, derived from the window's refresh rate.
    @Volatile private var frameDeadlineNanos = DEFAULT_FRAME_DEADLINE_NANOS

    // Dedicated background thread for per-frame metrics processing — keeps the UI thread free.
    private val frameMetricsThread by lazy {
        HandlerThread("telemetry-frame-metrics").apply { start() }
    }
    private val frameMetricsHandler by lazy { Handler(frameMetricsThread.looper) }

    /**
     * Lazy so the lambda body (which references API 24 types) is never loaded on API < 24.
     * Access is always guarded by Build.VERSION.SDK_INT checks.
     *
     * Runs on [frameMetricsThread], not the UI thread, so per-frame bookkeeping never
     * competes with rendering. Computes average-duration FPS once per second.
     */
    private val frameMetricsListenerApi24 by lazy {
        Window.OnFrameMetricsAvailableListener { window, frameMetrics, _ ->
            // Shared listener, single-window counters: ignore every window but the measured one.
            if (window !== measuredWindow) return@OnFrameMetricsAvailableListener

            val totalNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (totalNs <= 0L) return@OnFrameMetricsAvailableListener

            // Average every [frameStageAverageWindow] frames into one FrameStageSample before
            // emitting — one raw sample per frame (60-120/sec) is too dense to read as a bar
            // chart and constantly rescales the graph; a short rolling average smooths that
            // out while still tracking real regressions within a second or two.
            stageBucketCount++
            stageBucketActivityName = currentActivityName
            stageBucketInputNanos += frameMetrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
            stageBucketAnimationNanos += frameMetrics.getMetric(FrameMetrics.ANIMATION_DURATION)
            stageBucketMeasureLayoutNanos += frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
            stageBucketDrawNanos += frameMetrics.getMetric(FrameMetrics.DRAW_DURATION)
            stageBucketSyncNanos += frameMetrics.getMetric(FrameMetrics.SYNC_DURATION)
            stageBucketCommandIssueNanos += frameMetrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
            stageBucketSwapBuffersNanos += frameMetrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
            stageBucketTotalNanos += totalNs

            val averageWindow = frameStageAverageWindow.coerceAtLeast(1)
            if (stageBucketCount >= averageWindow) {
                _frameStageFlow.tryEmit(
                    FrameStageSample(
                        timestampMillis    = System.currentTimeMillis(),
                        activityName       = stageBucketActivityName,
                        inputHandlingNanos = stageBucketInputNanos / stageBucketCount,
                        animationNanos     = stageBucketAnimationNanos / stageBucketCount,
                        measureLayoutNanos = stageBucketMeasureLayoutNanos / stageBucketCount,
                        drawNanos          = stageBucketDrawNanos / stageBucketCount,
                        syncNanos          = stageBucketSyncNanos / stageBucketCount,
                        commandIssueNanos  = stageBucketCommandIssueNanos / stageBucketCount,
                        swapBuffersNanos   = stageBucketSwapBuffersNanos / stageBucketCount,
                        totalNanos         = stageBucketTotalNanos / stageBucketCount
                    )
                )
                stageBucketCount = 0
                stageBucketInputNanos = 0L
                stageBucketAnimationNanos = 0L
                stageBucketMeasureLayoutNanos = 0L
                stageBucketDrawNanos = 0L
                stageBucketSyncNanos = 0L
                stageBucketCommandIssueNanos = 0L
                stageBucketSwapBuffersNanos = 0L
                stageBucketTotalNanos = 0L
            }

            // A frame faster than the display deadline still only displays for one refresh
            // interval, so clamp to the deadline. Slower frames count their real duration.
            windowTotalDurationNanos += if (totalNs < frameDeadlineNanos) frameDeadlineNanos else totalNs
            windowFrameCount++
            when {
                totalNs > FROZEN_FRAME_THRESHOLD_NANOS -> {
                    pendingFrozenFrames.incrementAndGet()
                    windowJankyFrames++
                }
                totalNs > DEFAULT_FRAME_DEADLINE_NANOS -> {
                    pendingSlowFrames.incrementAndGet()
                    windowJankyFrames++
                }
            }

            val now = System.nanoTime()
            val elapsedNanos = now - windowStartNanos
            if (elapsedNanos >= ONE_SECOND_NANOS && windowFrameCount > 0) {
                val avgFrameDurationNanos = windowTotalDurationNanos.toDouble() / windowFrameCount
                val fps = (1_000_000_000.0 / avgFrameDurationNanos).toFloat()

                val jankRate = (windowJankyFrames.toFloat() / windowFrameCount) * 100f

                updateFps(fps, jankRate)

                // Reset for the next 1-second window.
                windowFrameCount = 0
                windowJankyFrames = 0
                windowTotalDurationNanos = 0L
                windowStartNanos = now
            }
        }
    }

    /** Current one-refresh-interval deadline (ns), refreshed on each [attachFrameMetrics] call. */
    internal fun currentFrameDeadlineNanos(): Long = frameDeadlineNanos

    /**
     * Call from TelemetryOverlayManager.onActivityResumed so we capture per-frame timings
     * for the currently visible window. Zero overhead unless telemetry is enabled.
     */
    internal fun attachFrameMetrics(window: Window, key: String = currentActivityName) {
        trackWindow(window, key, isSheet = false)
    }

    private fun trackWindow(window: Window, key: String, isSheet: Boolean) {
        // Re-attaching an already tracked window (e.g. showTelemetryOverlay() followed by the
        // real onResume) must not register the frame-metrics listener a second time.
        val alreadyListening = trackedWindows.removeAll { it.window === window }
        // Sheets go on top. An Activity goes *under* any sheet it still owns — the sheet survives
        // pause/resume, so it is still the window on screen.
        val owner = if (isSheet) null else window.owningActivity()
        val index = owner?.let { activity ->
            trackedWindows.indexOfFirst { it.isSheet && it.window.owningActivity() === activity }
        }?.takeIf { it >= 0 } ?: trackedWindows.size
        trackedWindows.add(index, TrackedWindow(key, window, isSheet))
        // Listener is API 24+; the stack is not — JankStats gating uses it on every API level.
        if (!alreadyListening && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                window.addOnFrameMetricsAvailableListener(frameMetricsListenerApi24, frameMetricsHandler)
            } catch (_: Exception) { /* window may not be attached yet */ }
        }
        promoteTopWindow()
    }

    /**
     * Points every single-window counter at the top tracked window.
     * Resets that state when the window changes, so two windows' numbers never mix.
     */
    private fun promoteTopWindow() {
        val top = trackedWindows.lastOrNull()

        // Only the top window feeds lastUiFps; the rest pause but keep their frame buckets.
        for (tracked in trackedWindows) {
            uiFpsAggregatorMap[tracked.key]?.jankStats?.isTrackingEnabled = tracked === top
        }

        if (top?.window === measuredWindow) return
        measuredWindow = top?.window
        val deadline = top?.window?.let { computeFrameDeadlineNanos(it) } ?: DEFAULT_FRAME_DEADLINE_NANOS
        // Reset window state on the frame-metrics thread so it is never raced by a live callback.
        frameMetricsHandler.post {
            frameDeadlineNanos = deadline
            windowStartNanos = System.nanoTime()
            windowFrameCount = 0
            windowJankyFrames = 0
            windowTotalDurationNanos = 0L
            stageBucketCount = 0
            stageBucketInputNanos = 0L
            stageBucketAnimationNanos = 0L
            stageBucketMeasureLayoutNanos = 0L
            stageBucketDrawNanos = 0L
            stageBucketSyncNanos = 0L
            stageBucketCommandIssueNanos = 0L
            stageBucketSwapBuffersNanos = 0L
            stageBucketTotalNanos = 0L
        }
    }

    /**
     * Frame deadline (ns) from the window display's current refresh rate, e.g. ~8.33ms at
     * 120Hz, ~16.66ms at 60Hz. Falls back to 60Hz if the display cannot be resolved.
     */
    @Suppress("DEPRECATION")
    private fun computeFrameDeadlineNanos(window: Window): Long {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.context.display
        } else {
            window.windowManager?.defaultDisplay
        }
        val refreshRate = display?.refreshRate?.takeIf { it > 0f } ?: DEFAULT_REFRESH_RATE
        return (1_000_000_000.0 / refreshRate).toLong()
    }

    internal fun detachFrameMetrics(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                window.removeOnFrameMetricsAvailableListener(frameMetricsListenerApi24)
            } catch (_: Exception) { }
        }
        trackedWindows.removeAll { it.window === window }
        // Measurement falls back to the window underneath (Activity or outer sheet).
        promoteTopWindow()
    }

    /**
     * Call from TelemetryOverlayManager.onActivityResumed, alongside [attachFrameMetrics],
     * to track the "UI FPS" metric for [activityName]'s window. Reuses that activity's
     * existing aggregator (and its in-progress frame bucket) if one is already tracked,
     * only creating a new one the first time this activity is seen.
     */
    internal fun attachUiFpsTracking(activityName: String, window: Window) {
        val existing = uiFpsAggregatorMap[activityName]
        // Respect [promoteTopWindow]: an Activity resuming under its own sheet stays paused.
        val top = trackedWindows.lastOrNull()
        val enabled = top == null || top.key == activityName ||
            trackedWindows.none { it.key == activityName }
        if (existing != null) {
            existing.jankStats.isTrackingEnabled = enabled
        } else {
            val aggregator = UiFpsAggregator(window) { fps -> lastUiFps = fps }
            uiFpsAggregatorMap[activityName] = aggregator
            aggregator.jankStats.isTrackingEnabled = enabled
        }
    }

    /** Call from onActivityPaused — pauses tracking but keeps the aggregator (and its bucket) alive. */
    internal fun detachUiFpsTracking(activityName: String) {
        uiFpsAggregatorMap[activityName]?.jankStats?.isTrackingEnabled = false
    }

    /** Call from onActivityDestroyed — the window is gone, so the aggregator must be torn down. */
    internal fun removeUiFpsTracking(activityName: String) {
        uiFpsAggregatorMap.remove(activityName)?.jankStats?.isTrackingEnabled = false
    }

    /**
     * Tracks a non-Activity window — a `Dialog` or Compose `ModalBottomSheet` / `Dialog` — while
     * it is on screen: its frames feed the metrics and the overlay is shown above it.
     * No-op unless telemetry is active. In Compose, prefer [TrackTelemetryWindow].
     *
     * @param key names the call site (e.g. "CheckoutSheet"); used to tell windows apart.
     */
    @JvmStatic
    public fun attachWindow(key: String, window: Window) {
        if (!isLiveModeActive) return
        val windowKey = windowKey(key, window)
        trackWindow(window, windowKey, isSheet = true)
        attachUiFpsTracking(windowKey, window)
        TelemetryOverlayManager.instanceOrNull()?.attachOverlayToWindow(window)
    }

    /** Counterpart to [attachWindow]; call when the window is dismissed. */
    @JvmStatic
    public fun detachWindow(key: String, window: Window) {
        detachFrameMetrics(window)
        removeUiFpsTracking(windowKey(key, window))
        TelemetryOverlayManager.instanceOrNull()?.detachOverlayFromWindow(window)
    }

    private fun windowKey(key: String, window: Window): String =
        "$key#${System.identityHashCode(window)}"

    private var isLiveModeActive = false
    private var collectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    /** One-time setup; call from `Application.onCreate` (or lazily before [showTelemetryOverlay]). */
    @JvmStatic
    public fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        memoryUtils = MemoryUtils(context.applicationContext)
        networkUsageManager.initialize()
    }

    internal fun startLiveTelemetry(intervalInSec: Long = 1) {
        if (isLiveModeActive) return
        isLiveModeActive = true
        collectionJob = scope.launch {
            while (isLiveModeActive) {
                val data = collectData(intervalInSec)
                _telemetryFlow.emit(data)
                delay(intervalInSec * 1000)
            }
        }
    }

    internal fun stopLiveTelemetry() {
        isLiveModeActive = false
        collectionJob?.cancel()
        collectionJob = null
        releaseWindows()
    }

    /**
     * Drops every window and aggregator.
     */
    private fun releaseWindows() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            for (tracked in trackedWindows) {
                try {
                    tracked.window.removeOnFrameMetricsAvailableListener(frameMetricsListenerApi24)
                } catch (_: Exception) { }
            }
        }
        trackedWindows.clear()
        uiFpsAggregatorMap.values.forEach { it.jankStats.isTrackingEnabled = false }
        uiFpsAggregatorMap.clear()
        promoteTopWindow()
    }

    /** Whether telemetry is being collected (the overlay is enabled). */
    @JvmStatic
    public fun isLiveModeActive(): Boolean = isLiveModeActive

    private fun collectData(intervalInSec: Long): LiveTelemetryData {
        val timestamp    = System.currentTimeMillis()
        val activityName = currentActivityName
        val ctx          = appContext

        // ── Heap memory ──────────────────────────────────────────────────────
        val memStats =
            memoryUtils?.getAllMemoryStatsV2()

        // ── System memory pressure ────────────────────────────────────────────
        var availMemMB = 0L
        var isLowMemory = false
        if (ctx != null) {
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                availMemMB = mi.availMem / (1024L * 1024L)
                isLowMemory = mi.lowMemory
            } catch (_: Exception) {
            }
        }

        // ── GC ───────────────────────────────────────────────────────────────
        var gcCount = 0L
        var gcTimeMs = 0L
        var blockingGcCount = 0L
        var blockingGcTimeMs = 0L
        try {
            val stats = Debug.getRuntimeStats()
            gcCount = stats["art.gc.gc-count"]?.toLongOrNull() ?: 0L
            gcTimeMs = stats["art.gc.gc-time"]?.toLongOrNull() ?: 0L

            // The metrics closest to thread-pausing GC:
            blockingGcCount = stats["art.gc.blocking-gc-count"]?.toLongOrNull() ?: 0L
            blockingGcTimeMs = stats["art.gc.blocking-gc-time"]?.toLongOrNull() ?: 0L

        } catch (_: Exception) {
        }

        // ── Network ───────────────────────────────────────────────────────────
        val networkUsage = networkUsageManager.getUsageNow()
        val downloadKBps = (networkUsage.downloads / 1024.0) / intervalInSec
        val uploadKBps   = (networkUsage.uploads   / 1024.0) / intervalInSec

        // ── CPU usage (process %) ─────────────────────────────────────────────
        val cpuPercent = cpuMonitor.sampleCpuDetailed()

        // ── Thread count ──────────────────────────────────────────────────────
        val threadCount = readThreadCount()

        // ── Thermal headroom (API 29+) ────────────────────────────────────────
        val thermalHeadroom: Float = if (ctx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).getThermalHeadroom(1)

            } catch (_: Exception) {
                -1f
            }
        } else -1f

        // ── Battery current ───────────────────────────────────────────────────
        // Unit is µA on most OEMs; divide by 1000 for mA. Sign: negative = discharging (varies by OEM).
        val batteryCurrentMa: Int = if (ctx != null) {
            try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000
            } catch (_: Exception) {
                0
            }
        } else 0

        // ── FrameMetrics counters (reset each interval) ───────────────────────
        val slowFrames   = pendingSlowFrames.getAndSet(0)
        val frozenFrames = pendingFrozenFrames.getAndSet(0)

        val data = LiveTelemetryData(
            timestamp        = timestamp,
            activityName     = activityName,
            fps              = lastFps,
            uiFps           = lastUiFps,
            jankRate        = lastJankRate,
            slowFrames      = slowFrames,
            frozenFrames    = frozenFrames,
            javaHeapMB      = memStats?.javaAllocatedHeapInMB   ?: 0L,
            nativeHeapMB    = memStats?.nativeAllocatedHeapInMB ?: 0L,
            graphicsMB      = memStats?.graphMemoryStackSizeInMB ?: 0L,
            availMemMB      = availMemMB,
            isLowMemory     = isLowMemory,
            gcCount         = gcCount,
            gcTimeMs        = gcTimeMs,
            blockingGcCount = blockingGcCount,
            blockingGcTimeMs = blockingGcTimeMs,
            cpuPercent      = cpuPercent.toFloat(),
            threadCount     = threadCount,
            downloadKBps    = downloadKBps,
            uploadKBps      = uploadKBps,
            thermalHeadroom = thermalHeadroom,
            batteryCurrentMa = batteryCurrentMa
        )

        return data
    }
    /**
     * Reads Threads: field from /proc/pid/status. ~0.1ms.
     */
    private fun readThreadCount(): Int {
        return try {
            val pid = android.os.Process.myPid()
            File("/proc/$pid/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Threads:") }
                    ?.split("\\s+".toRegex())
                    ?.getOrNull(1)
                    ?.toIntOrNull() ?: 0
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Overrides the raw FPS / jank-rate numbers with values from the host app's own frame
     * pipeline. Optional: they are measured automatically from `FrameMetrics` while the overlay
     * is shown, and a later measurement overwrites a pushed value.
     */
    @JvmStatic
    public fun updateFps(fps: Float, jankRate: Float) {
        lastFps = fps
        lastJankRate = jankRate
    }

    /**
     * Requests a GC for the "Trigger GC" button on the Memory tab. `System.gc()` is only a
     * hint — ART is free to ignore it — but in practice it reliably runs one on current ART,
     * which is all a manual "does releasing X free memory" debugging check needs.
     */
    internal fun triggerManualGc() {
        System.gc()
    }

    /**
     * Starts collecting and shows the overlay on every resumed activity. When [context] is (or
     * wraps) an Activity, the overlay appears on it right away instead of on the next resume.
     */
    @JvmStatic
    public fun showTelemetryOverlay(context: Context) {
        initialize(context)
        val manager = TelemetryOverlayManager.getInstance(context)
        manager.isEnabled = true
        context.findActivity()
            ?.takeUnless { it.isFinishing || it.isDestroyed }
            ?.let { manager.onActivityResumed(it) }
    }

    /** Stops collecting, removes the overlay everywhere and clears the in-memory session. */
    @JvmStatic
    public fun hideTelemetryOverlay(context: Context) {
        TelemetryOverlayManager.getInstance(context).isEnabled = false
    }
}

/** Owning Activity: itself for an Activity window, the host for a dialog's. */
internal fun Window.owningActivity(): Activity? = context.findActivity()

internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
