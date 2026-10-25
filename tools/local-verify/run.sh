#!/usr/bin/env bash
# Compiles the privacy-critical production classes plus Harness.kt and runs them
# on a plain JVM — no Android SDK, no Gradle, no CI.
#
# Requires: a JDK 17+ and the Kotlin command line compiler (kotlinc) 2.x.
#   JAVA_HOME=/path/to/jdk17
#   KOTLINC=/path/to/kotlinc
set -euo pipefail
cd "$(dirname "$0")/../.."
SRC=app/src/main/java/com/myai/assistant
OUT=$(mktemp -d)
KOTLINC_BIN="${KOTLINC:-$(command -v kotlinc)}"
"$KOTLINC_BIN" -nowarn -jvm-target 17 \
  tools/local-verify/stubs/*.kt tools/local-verify/Harness.kt \
  "$SRC"/core/model/Models.kt \
  "$SRC"/privacy/ScreenContentGuard.kt \
  "$SRC"/privacy/ScreenContentVault.kt \
  "$SRC"/privacy/RemoteHistoryFilter.kt \
  "$SRC"/ai/AiProvider.kt \
  "$SRC"/ai/PrivacyFilteringProvider.kt \
  "$SRC"/agent/ScreenController.kt \
  "$SRC"/agent/Tool.kt \
  "$SRC"/agent/ToolRegistry.kt \
  "$SRC"/agent/Planner.kt \
  "$SRC"/agent/AgentEngine.kt \
  "$SRC"/tools/ScreenSummaryFormatter.kt \
  "$SRC"/tools/ScreenTools.kt \
  -d "$OUT"
STDLIB="$(cd "$(dirname "$KOTLINC_BIN")/../lib" && pwd)/kotlin-stdlib.jar"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT:$STDLIB" HarnessKt
