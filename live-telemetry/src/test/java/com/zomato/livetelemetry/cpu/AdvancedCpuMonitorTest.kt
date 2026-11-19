package com.zomato.livetelemetry.cpu

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedCpuMonitorTest {

    // Real /proc/self/stat line shape: pid (comm) state ppid … utime(14) stime(15) …
    private fun statLine(comm: String, utime: Long, stime: Long): String {
        val afterComm = listOf(
            "S", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
            utime.toString(), stime.toString(), "0", "0", "20", "0", "40",
        )
        return "12345 ($comm) " + afterComm.joinToString(" ") + "\n"
    }

    @Test
    fun parsesUtimePlusStime() {
        assertEquals(350L, AdvancedCpuMonitor.parseCpuTicks(statLine("com.example.app", 300, 50)))
    }

    @Test
    fun processNameWithSpacesAndParenthesesDoesNotShiftFields() {
        assertEquals(42L, AdvancedCpuMonitor.parseCpuTicks(statLine("we(ird) name)", 40, 2)))
    }

    @Test
    fun malformedLinesReturnMinusOne() {
        assertEquals(-1L, AdvancedCpuMonitor.parseCpuTicks(""))
        assertEquals(-1L, AdvancedCpuMonitor.parseCpuTicks("12345 (app"))
        assertEquals(-1L, AdvancedCpuMonitor.parseCpuTicks("12345 (app) S 1 2"))
        assertEquals(-1L, AdvancedCpuMonitor.parseCpuTicks(statLine("app", 1, 1).replace(" 1 1 0 0", " x 1 0 0")))
    }

    @Test
    fun cpuPercentIsShareOfAllCores() {
        // 1 s on 4 cores = 400 ticks of capacity; 100 ticks used = 25 %.
        assertEquals(25.0, AdvancedCpuMonitor.cpuPercent(100, 1_000, 4), 0.001)
        // One core fully busy for 2 s on a 2-core device = 50 %.
        assertEquals(50.0, AdvancedCpuMonitor.cpuPercent(200, 2_000, 2), 0.001)
    }

    @Test
    fun cpuPercentIsClampedAndGuarded() {
        assertEquals(100.0, AdvancedCpuMonitor.cpuPercent(10_000, 1_000, 1), 0.001)
        assertEquals(0.0, AdvancedCpuMonitor.cpuPercent(-5, 1_000, 1), 0.001)
        assertEquals(0.0, AdvancedCpuMonitor.cpuPercent(10, 0, 4), 0.001)
        assertEquals(0.0, AdvancedCpuMonitor.cpuPercent(10, 1_000, 0), 0.001)
    }
}
