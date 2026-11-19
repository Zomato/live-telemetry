package com.zomato.livetelemetry

import androidx.core.content.FileProvider

/**
 * Distinct FileProvider subclass so the manifest merger never collides with the
 * host app's (or another library's) androidx FileProvider declaration — merger
 * keys provider nodes by class name.
 */
public class LiveTelemetryFileProvider : FileProvider()
