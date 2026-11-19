package com.zomato.livetelemetry

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Tracks the window of the Compose `Dialog` / `ModalBottomSheet` this is called from.
 *
 * Dialogs and modal sheets render in their own window, so frame metrics bound to the Activity
 * window miss every frame drawn inside them, and the overlay would sit behind them. Call this
 * once inside the dialog's content:
 *
 * ```
 * ModalBottomSheet(onDismissRequest = { … }) {
 *     TrackTelemetryWindow("FiltersSheet")
 *     SheetContent()
 * }
 * ```
 *
 * No-op outside a dialog window. While telemetry is off it costs one map lookup per composition.
 *
 * @param key names the call site; shown nowhere, only used to tell windows apart.
 */
@Composable
public fun TrackTelemetryWindow(key: String) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(key, window) {
        LiveTelemetryCollector.attachWindow(key, window)
        onDispose { LiveTelemetryCollector.detachWindow(key, window) }
    }
}
