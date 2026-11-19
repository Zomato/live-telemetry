package com.zomato.livetelemetry

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds an exportable report of a live telemetry session (CSV attachment +
 * human-readable summary) and fires a share intent so remote users can email
 * it when they notice a performance lag.
 */
internal object TelemetryReportGenerator {

    private const val REPORT_DIR = "live_telemetry_reports"
    private val fileNameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val readableFmt = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.US)

    /**
     * Generates the CSV + summary off the caller's thread choice (call from a
     * background thread — file I/O) and launches an email/share chooser.
     */
    fun exportAndShare(context: Context, data: List<LiveTelemetryData>) {
        if (data.isEmpty()) return
        try {
            val csvFile = writeCsv(context, data)
            val summary = buildSummary(context, data)
            share(context, csvFile, summary)
        } catch (e: Exception) {
            Log.e("TelemetryReport", "Failed to export telemetry report", e)
        }
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    private fun writeCsv(context: Context, data: List<LiveTelemetryData>): File {
        val dir = File(context.cacheDir, REPORT_DIR).apply { mkdirs() }
        // Keep cache bounded — drop older reports
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }

        val file = File(dir, "telemetry_${fileNameFmt.format(Date())}.csv")
        file.bufferedWriter().use { w ->
            w.appendLine(
                "timestamp,activity,fps,jank_rate,slow_frames,frozen_frames," +
                "java_heap_mb,native_heap_mb,graphics_mb,avail_mem_mb,low_memory," +
                "gc_count,gc_time_ms,blocking_gc_count,blocking_gc_time_ms,cpu_percent,thread_count," +
                "download_kbps,upload_kbps,thermal_headroom,battery_ma"
            )
            data.forEach { d ->
                w.appendLine(
                    "${d.timestamp},${d.activityName.substringAfterLast('.')},${d.fps},${d.jankRate}," +
                    "${d.slowFrames},${d.frozenFrames}," +
                    "${d.javaHeapMB},${d.nativeHeapMB},${d.graphicsMB},${d.availMemMB},${d.isLowMemory}," +
                    "${d.gcCount},${d.gcTimeMs},${d.blockingGcCount},${d.blockingGcTimeMs},${d.cpuPercent},${d.threadCount}," +
                    "${"%.2f".format(d.downloadKBps)},${"%.2f".format(d.uploadKBps)}," +
                    "${d.thermalHeadroom},${d.batteryCurrentMa}"
                )
            }
        }
        return file
    }

    // ── Summary ──────────────────────────────────────────────────────────────

    private fun buildSummary(context: Context, data: List<LiveTelemetryData>): String {
        val first = data.first()
        val last = data.last()
        val durationSec = (last.timestamp - first.timestamp) / 1000

        val fpsValues = data.map { it.fps }.filter { it > 0f }
        val cpuValues = data.map { it.cpuPercent }
        val totalMem = data.map { it.javaHeapMB + it.nativeHeapMB + it.graphicsMB }
        val totalSlow = data.sumOf { it.slowFrames }
        val totalFrozen = data.sumOf { it.frozenFrames }
        val lowMemoryHits = data.count { it.isLowMemory }

        fun avg(values: List<Float>) = if (values.isEmpty()) 0f else values.sum() / values.size

        val perActivity = data.groupBy { it.activityName.substringAfterLast('.') }
            .entries.joinToString("\n") { (name, points) ->
                val actFps = points.map { it.fps }.filter { it > 0f }
                "  • $name: ${points.size}s, avg ${"%.0f".format(avg(actFps))} fps, " +
                    "avg cpu ${"%.1f".format(avg(points.map { it.cpuPercent }))}%, " +
                    "slow ${points.sumOf { it.slowFrames }}, frozen ${points.sumOf { it.frozenFrames }}"
            }

        return """
            |Live Telemetry Report
            |======================
            |App: ${context.packageName}
            |Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})
            |Session: ${readableFmt.format(Date(first.timestamp))} → ${readableFmt.format(Date(last.timestamp))} (${durationSec}s, ${data.size} samples)
            |
            |FPS: avg ${"%.0f".format(avg(fpsValues))}, min ${"%.0f".format(fpsValues.minOrNull() ?: 0f)}, max ${"%.0f".format(fpsValues.maxOrNull() ?: 0f)}
            |CPU: avg ${"%.1f".format(avg(cpuValues))}%, peak ${"%.1f".format(cpuValues.maxOrNull() ?: 0f)}%
            |Memory (java+native+gfx): avg ${totalMem.average().toInt()} MB, peak ${totalMem.maxOrNull() ?: 0} MB
            |Frames: $totalSlow slow, $totalFrozen frozen
            |Blocking GC (session end): ${last.blockingGcCount} pauses, ${last.blockingGcTimeMs} ms total
            |Low-memory pressure hits: $lowMemoryHits
            |
            |Per screen:
            |$perActivity
            |
            |Full per-second data attached as CSV.
        """.trimMargin()
    }

    // ── Share ────────────────────────────────────────────────────────────────

    private fun share(context: Context, csvFile: File, summary: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.livetelemetry.fileprovider",
            csvFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Live Telemetry Report — ${context.packageName}")
            putExtra(Intent.EXTRA_TEXT, summary)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share telemetry report").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
