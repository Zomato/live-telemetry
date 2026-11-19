package com.zomato.livetelemetry.network

/** Bytes received / sent over [timeTaken] milliseconds. */
internal data class Usage(
    val downloads: Long = 0L,
    val uploads: Long = 0L,
    val timeTaken: Long = 0L,
)
