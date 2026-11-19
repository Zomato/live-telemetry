package com.zomato.livetelemetry

import android.os.Build
import android.view.Window
import androidx.metrics.performance.JankStats

/**
 * Perceived-FPS aggregator using a bucket-average approach over androidx JankStats
 * (non-jank frames are assumed to take a fixed duration),
 * but with that fixed duration derived from the window's actual display refresh rate instead
 * of a hardcoded 16.6ms (60Hz) assumption — so it stays correct on 90/120Hz+ devices.
 */
internal class UiFpsAggregator(
    window: Window,
    private val frameBucketSize: Int = DEFAULT_FRAME_BUCKET_SIZE,
    private val onReport: (perceivedFps: Float) -> Unit
) {
    companion object {
        const val DEFAULT_FRAME_BUCKET_SIZE = 30
        private const val MILLI_TO_NANO = 1_000_000
        private const val SEC_TO_MILLIS = 1_000
        private const val DEFAULT_REFRESH_RATE = 60f
    }

    // Non-jank frames are assumed to take exactly one refresh interval — computed once per
    // attach from this window's display, not hardcoded to 60Hz.
    private val nonJankFrameDurationNanos: Long = computeFrameDurationNanos(window)

    private var bucketFrameCount = 0
    private var bucketTotalDurationNanos = 0L

    private val listener = JankStats.OnFrameListener { frameData ->
        bucketFrameCount++
        bucketTotalDurationNanos += if (frameData.isJank) frameData.frameDurationUiNanos else nonJankFrameDurationNanos

        if (bucketFrameCount == frameBucketSize) {
            val avgFrameDurationMs = bucketTotalDurationNanos.toFloat() / (frameBucketSize * MILLI_TO_NANO)
            onReport(SEC_TO_MILLIS / avgFrameDurationMs)
            bucketFrameCount = 0
            bucketTotalDurationNanos = 0L
        }
    }

    val jankStats: JankStats = JankStats.createAndTrack(window, listener).apply {
        jankHeuristicMultiplier = 1f
    }

    @Suppress("DEPRECATION")
    private fun computeFrameDurationNanos(window: Window): Long {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.context.display
        } else {
            window.windowManager?.defaultDisplay
        }
        val refreshRate = display?.refreshRate?.takeIf { it > 0f } ?: DEFAULT_REFRESH_RATE
        return (1_000_000_000.0 / refreshRate).toLong()
    }
}
