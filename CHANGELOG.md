# Changelog

All notable changes to this project are documented here. The project follows
[semantic versioning](https://semver.org/).

## 1.0.0

First open-source release, extracted from Zomato's Android app, where the overlay has been in
internal use.

- Overlay tabs: UI FPS (JankStats, refresh-rate-aware), Frame Bars (per-stage frame breakdown),
  Memory (with manual GC), CPU, Network, GC.
- Collapsible, draggable pill; per-activity screen selector; CSV export through the share sheet.
- `TrackTelemetryWindow` / `LiveTelemetryCollector.attachWindow` to measure and overlay Compose
  dialogs and bottom sheets.
- `showTelemetryOverlay(activity)` shows the overlay on that activity immediately.
- Public API trimmed to `LiveTelemetryCollector`, `TrackTelemetryWindow`, `LiveTelemetryData` and
  `FrameStageSample` (explicit-API mode).
- Published to Maven Central as `com.eternal.kits:live-telemetry`.
