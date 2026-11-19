package com.zomato.livetelemetry.cpu

import java.io.File

/**
 * Process CPU % sampled from `/proc/self/stat` (utime + stime) against wall-clock time,
 * normalised by the number of cores. Not thread-safe: call from a single sampling coroutine.
 */
internal class AdvancedCpuMonitor {

    private var lastCpuTicks = 0L
    private var lastSampleTimeMs = 0L
    private val totalCores = Runtime.getRuntime().availableProcessors()

    fun sampleCpuDetailed(): Double {
        val currentTimeMs = System.currentTimeMillis()
        val currentCpuTicks = readProcessCpuTicks()

        if (currentCpuTicks == -1L) return 0.0

        if (lastSampleTimeMs == 0L) {
            lastCpuTicks = currentCpuTicks
            lastSampleTimeMs = currentTimeMs
            return 0.0
        }

        val deltaCpuTicks = currentCpuTicks - lastCpuTicks
        val deltaTimeMs = currentTimeMs - lastSampleTimeMs
        lastCpuTicks = currentCpuTicks
        lastSampleTimeMs = currentTimeMs

        if (deltaTimeMs <= 0) return 0.0
        return cpuPercent(deltaCpuTicks, deltaTimeMs, totalCores)
    }

    private fun readProcessCpuTicks(): Long =
        try {
            parseCpuTicks(File("/proc/self/stat").readText())
        } catch (_: Exception) {
            -1L
        }

    companion object {
        /** Android kernels run at USER_HZ = 100 ticks per second. */
        private const val USER_HZ = 100.0

        /**
         * Returns utime + stime (fields 14 and 15 of `/proc/<pid>/stat`), or -1 if [statLine]
         * cannot be parsed. The process name (field 2) may itself contain spaces and
         * parentheses, so parsing starts after the *last* `)`.
         */
        internal fun parseCpuTicks(statLine: String): Long {
            val lastParenthesis = statLine.lastIndexOf(')')
            if (lastParenthesis == -1 || lastParenthesis + 2 >= statLine.length) return -1L
            // tokens[0] is field 3 (state), so field N is tokens[N - 3].
            val tokens = statLine.substring(lastParenthesis + 2).trim().split(Regex("\\s+"))
            if (tokens.size <= 12) return -1L
            val utime = tokens[11].toLongOrNull() ?: return -1L
            val stime = tokens[12].toLongOrNull() ?: return -1L
            return utime + stime
        }

        /** CPU % of the whole device capacity, clamped to 0..100. */
        internal fun cpuPercent(deltaCpuTicks: Long, deltaTimeMs: Long, cores: Int): Double {
            if (deltaTimeMs <= 0 || cores <= 0) return 0.0
            val capacityTicks = (deltaTimeMs / 1000.0) * USER_HZ * cores
            return ((deltaCpuTicks / capacityTicks) * 100.0).coerceIn(0.0, 100.0)
        }
    }
}
