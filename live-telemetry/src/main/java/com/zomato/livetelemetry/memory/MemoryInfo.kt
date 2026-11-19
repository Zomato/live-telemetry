package com.zomato.livetelemetry.memory

internal data class MemoryInfoV2(
    val totalMemoryInMB: Long,
    val freeMemoryInMB: Long,
    val maxMemoryInMB: Long,
    val nativeAllocatedHeapInMB: Long,
    val javaAllocatedHeapInMB: Long? = null,
    val codeAllocatedSizeInMB: Long? = null,
    val stackMemoryStackSizeInMB: Long? = null,
    val graphMemoryStackSizeInMB: Long? = null,
)
