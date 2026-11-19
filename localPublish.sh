#!/bin/bash
# Publishes the library to ~/.m2 (mavenLocal). No credentials or signing key needed.
set -euo pipefail
./gradlew :live-telemetry:publishToMavenLocal
