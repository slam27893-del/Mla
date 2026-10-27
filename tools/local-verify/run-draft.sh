#!/usr/bin/env bash
# Compiles the draft package (pure Kotlin, no Android) plus DraftChecks.kt and
# runs it on a plain JVM. Same idea as run.sh, for the Brawl Stars draft logic.
#
#   JAVA_HOME=/path/to/jdk17
#   KOTLINC=/path/to/kotlinc
set -euo pipefail
cd "$(dirname "$0")/../.."
D=app/src/main/java/com/myai/assistant/draft
OUT=$(mktemp -d)
KOTLINC_BIN="${KOTLINC:-$(command -v kotlinc)}"
"$KOTLINC_BIN" -nowarn -jvm-target 17 \
  tools/local-verify/DraftChecks.kt \
  "$D"/*.kt \
  -d "$OUT"
STDLIB="$(cd "$(dirname "$KOTLINC_BIN")/../lib" && pwd)/kotlin-stdlib.jar"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT:$STDLIB" DraftChecksKt
