package com.zomato.livetelemetry.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process

internal class MemoryUtils(private val context: Context? = null) {

    private fun getProcessMemoryInfo(): Debug.MemoryInfo {
        val activityManager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val infos = activityManager?.getProcessMemoryInfo(intArrayOf(Process.myPid()))
        return infos?.firstOrNull() ?: Debug.MemoryInfo()
    }

    /**
     * All memory statistics, using a hybrid approach:
     * 1. Java / native heap from `Runtime` and `Debug` static calls: live allocated values.
     * 2. Code and stack from a single `Debug.MemoryInfo.getMemoryStat()` lookup.
     * 3. Graphics estimated as total PSS minus native PSS, code and stack. This approximates
     *    what the Android Studio profiler shows; GPU driver stats are not accessible to apps.
     *
     * Note: since Android 10 `ActivityManager.getProcessMemoryInfo()` is rate-limited by the
     * platform, so the code / stack / graphics values may update less often than once a second.
     */
    fun getAllMemoryStatsV2(): MemoryInfoV2 {
        val nativeHeapAllocated = Debug.getNativeHeapAllocatedSize() / MB

        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / MB
        val totalMemory = runtime.totalMemory() / MB
        val freeMemory = runtime.freeMemory() / MB

        val memInfo = getProcessMemoryInfo()
        val code = memInfo.getMemoryStat(CODE_MEMORY_KEY)?.toLongOrNull()?.div(1024) ?: 0L
        val stack = memInfo.getMemoryStat(STACK_MEMORY_KEY)?.toLongOrNull()?.div(1024) ?: 0L
        val totalPss = (memInfo.totalPss / 1024).toLong()
        val nativePss = (memInfo.nativePss / 1024).toLong()
        val graphics = maxOf(0L, totalPss - nativePss - code - stack)

        return MemoryInfoV2(
            maxMemoryInMB = maxMemory,
            totalMemoryInMB = totalMemory,
            freeMemoryInMB = freeMemory,
            nativeAllocatedHeapInMB = nativeHeapAllocated,
            javaAllocatedHeapInMB = totalMemory - freeMemory,
            codeAllocatedSizeInMB = code,
            stackMemoryStackSizeInMB = stack,
            graphMemoryStackSizeInMB = graphics,
        )
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val CODE_MEMORY_KEY = "summary.code"
        const val STACK_MEMORY_KEY = "summary.stack"
    }
}
