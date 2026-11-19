package com.zomato.livetelemetry.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zomato.livetelemetry.LiveTelemetryCollector
import com.zomato.livetelemetry.TrackTelemetryWindow
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Demo screen: show the overlay, then use the buttons and watch the graphs react.
 */
class MainActivity : ComponentActivity() {

    // Held so the allocations are not immediately collectable.
    private val memoryHog = mutableListOf<ByteArray>()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var overlayShown by remember { mutableStateOf(LiveTelemetryCollector.isLiveModeActive()) }
                var heavyDraw by remember { mutableStateOf(false) }
                var sheetOpen by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Live Telemetry Sample", style = MaterialTheme.typography.headlineSmall)

                    Button(
                        onClick = {
                            overlayShown = !overlayShown
                            if (overlayShown) {
                                LiveTelemetryCollector.showTelemetryOverlay(this@MainActivity)
                            } else {
                                LiveTelemetryCollector.hideTelemetryOverlay(this@MainActivity)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (overlayShown) "Hide overlay" else "Show overlay")
                    }

                    Spinner(heavyDraw)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Expensive draw (~20 ms per frame)", Modifier.weight(1f))
                        Switch(checked = heavyDraw, onCheckedChange = { heavyDraw = it })
                    }

                    Text("Simulate load", style = MaterialTheme.typography.titleMedium)
                    DemoButton("Burn CPU (3 s, all cores)", ::burnCpu)
                    DemoButton("Allocate 50 MB", ::allocateMemory)
                    DemoButton("Release memory (then tap ⟲ GC)") { memoryHog.clear() }
                    DemoButton("Block main thread 800 ms (frozen frame)") { Thread.sleep(800) }

                    Text("Windows", style = MaterialTheme.typography.titleMedium)
                    DemoButton("Open bottom sheet") { sheetOpen = true }
                    DemoButton("Open second screen") {
                        startActivity(Intent(this@MainActivity, SecondActivity::class.java))
                    }
                }

                if (sheetOpen) {
                    ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
                        // The sheet renders in its own window: this hands it to Live Telemetry so
                        // its frames are measured and the overlay stays on top of it.
                        TrackTelemetryWindow("DemoSheet")
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Bottom sheet", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(16.dp))
                            Spinner(heavyDraw)
                            Spacer(Modifier.height(48.dp))
                        }
                    }
                }
            }
        }
    }

    private fun burnCpu() {
        repeat(Runtime.getRuntime().availableProcessors()) {
            thread {
                val end = System.currentTimeMillis() + 3_000
                var x = 0.0
                while (System.currentTimeMillis() < end) x += sqrt(x + 1)
            }
        }
    }

    private fun allocateMemory() {
        repeat(50) { memoryHog.add(ByteArray(1024 * 1024)) }
    }
}

@Composable
private fun DemoButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

/** A continuously animating spinner, so there are always frames to measure. */
@Composable
private fun Spinner(heavyDraw: Boolean) {
    val angle by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        if (heavyDraw) Thread.sleep(20) // deliberately slow frame: watch the Draw segment grow
        val radius = size.height / 2.5f
        repeat(12) { i ->
            val a = Math.toRadians((angle + i * 30).toDouble())
            drawCircle(
                color = Color(0xFFE23744).copy(alpha = (i + 1) / 12f),
                radius = 6.dp.toPx(),
                center = Offset(
                    center.x + radius * cos(a).toFloat(),
                    center.y + radius * sin(a).toFloat(),
                ),
            )
        }
    }
}
