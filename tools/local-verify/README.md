# Local verification harness (optional, not part of the app)

`Harness.kt` compiles and **executes the real production classes** that make up the
screen-content boundary, on a plain JVM:

```
ScreenSummaryTool → ScreenSummaryFormatter → ScreenContentGuard
                  → AgentEngine (vault diversion) → RemoteHistoryFilter
                  → PrivacyFilteringProvider → AiProvider
```

Nothing here is shipped in the APK and Gradle never compiles this folder. It exists
so the privacy claim can be reproduced without an Android SDK, and it is what
caught two incorrect assertions in the JUnit suite during review (a vault holding
the *formatted* readout rather than the raw line, and a guard that matched whole
lines only).

`stubs/` contains minimal stand-ins for the Android / coroutines / serialization
APIs those files import (`android.content.Context`, `Flow`/`flow`/`catch`,
`@Serializable`). They are simplified — notably `withTimeoutOrNull` never times
out — so treat this as a fast check, not a replacement for `./gradlew
testDebugUnitTest`, which is the authoritative suite.

Run:

```bash
JAVA_HOME=/path/to/jdk17 KOTLINC=/path/to/kotlinc ./tools/local-verify/run.sh
```

Expected output ends with `RESULT  passed=22 failed=0`; exit code is non-zero on
any failure. Two checks are *controls*: they assert that a message which really
does contain unregistered screen text **is** forwarded, proving the harness can
detect a leak and is not passing vacuously.
