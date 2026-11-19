package com.zomato.livetelemetry.sample

import android.app.Application
import com.zomato.livetelemetry.LiveTelemetryCollector

class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // One-time init: wires up the memory / CPU / network samplers. Nothing is collected
        // until the overlay is shown.
        LiveTelemetryCollector.initialize(this)
    }
}
