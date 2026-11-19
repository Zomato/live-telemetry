package com.zomato.livetelemetry.network

import android.net.TrafficStats
import android.os.Process

/** Per-UID network throughput from [TrafficStats], as deltas since the previous call. */
internal class NetworkUsageManager {

    private var lastTxBytes = 0L
    private var lastRxBytes = 0L
    private var lastTime = 0L

    fun initialize(appUid: Int = Process.myUid()) {
        lastTxBytes = TrafficStats.getUidTxBytes(appUid)
        lastRxBytes = TrafficStats.getUidRxBytes(appUid)
        lastTime = System.currentTimeMillis()
    }

    fun getUsageNow(appUid: Int = Process.myUid()): Usage {
        val currentRxBytes = TrafficStats.getUidRxBytes(appUid)
        val currentTxBytes = TrafficStats.getUidTxBytes(appUid)
        val currentTime = System.currentTimeMillis()

        val usage = Usage(
            downloads = currentRxBytes - lastRxBytes,
            uploads = currentTxBytes - lastTxBytes,
            timeTaken = currentTime - lastTime,
        )

        lastRxBytes = currentRxBytes
        lastTxBytes = currentTxBytes
        lastTime = currentTime
        return usage
    }
}
