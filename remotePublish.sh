#!/bin/bash
# Publishes the library to Maven Central.
#
# Needs these Gradle properties (in ~/.gradle/gradle.properties, or as ORG_GRADLE_PROJECT_<name>
# environment variables on CI):
#   mavenCentralUsername, mavenCentralPassword            Central Portal user token
#   signingInMemoryKey, signingInMemoryKeyId, signingInMemoryKeyPassword   GPG key (ASCII armored)
#
#   ./remotePublish.sh            upload only; release the deployment by hand at central.sonatype.com
#   ./remotePublish.sh --release  upload and release automatically (what CI does)
set -euo pipefail

auto_release=false
if [[ "${1:-}" == "--release" ]]; then auto_release=true; fi

./gradlew :live-telemetry:publishToMavenCentral \
  -PmavenCentralAutomaticRelease="$auto_release" \
  --no-configuration-cache
