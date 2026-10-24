# MyAI — Architecture

This document explains the runtime design and the reasoning behind the parts
that are easy to get wrong: command execution, verification, confirmation,
and the overlay service lifecycle.

---

## 1. Layers

```
┌────────────────────────────────────────────────────────────┐
│ UI            MainActivity · OverlayPanel · ChatAdapter     │
├────────────────────────────────────────────────────────────┤
│ Controller    ChatController (single turn, single flight)   │
├────────────────────────────────────────────────────────────┤
│ Reasoning     AgentEngine ── Planner (Rule | LLM | Fallback)│
├────────────────────────────────────────────────────────────┤
│ Action        ToolRegistry ── 20 × Tool                     │
├────────────────────────────────────────────────────────────┤
│ Platform      Intents · AccessibilityService · MediaSession │
└────────────────────────────────────────────────────────────┘
```

Dependencies point downward only. `AgentEngine` knows nothing about Android
widgets; `Tool` knows nothing about the chat UI. `AppContainer`
(`MyAiApplication.kt:32`) is the single place where concrete classes are wired,
so swapping a planner or provider never touches the UI.

## 2. A turn, step by step

1. **Gate** — `ChatController.submit()` (`agent/ChatController.kt:27`) uses an
   `AtomicBoolean` compare-and-set so the chat screen and the floating panel
   can never run two turns at once. A second submission while busy returns
   `false`, and the UI ignores it.
2. **Persist** — the user message is appended to `ConversationStore` before any
   work starts, so a crash does not lose it. If history is disabled, the store
   keeps everything in memory only.
3. **Plan** — `PlannerFactory` picks the planner: LLM when configured and
   reachable, otherwise the rule-based planner. `FallbackPlanner` catches LLM
   failure and retries with rules, so a bad network never blocks a simple
   command.
4. **Validate** — `PlanParser` rejects unknown tool ids and missing required
   arguments *before* execution. A hallucinated `tool: "delete_all"` aborts with
   a readable Arabic message instead of running nothing and claiming success.
5. **Execute** — for each step, `AgentEngine` checks tool existence, argument
   contract, accessibility availability, then risk:
   - `RiskLevel.SAFE` runs immediately.
   - `RiskLevel.SENSITIVE` emits `ConfirmationRequired` and waits for the user.
     Refusal marks the step `SKIPPED` and aborts the remaining chain.
6. **Verify** — the tool returns `ToolOutcome(success, verification, message)`.
   Verification is a four-value enum, not a boolean, because "intent sent" and
   "intent worked" are different facts.
7. **Report** — every step update flows to the UI, which colors the row and
   prints the verification label in Arabic. The final reply only says a thing
   happened when verification allows it.

## 3. Verification model

| Value | Emitted when | Example |
|---|---|---|
| `VERIFIED` | The tool observed the effect itself | `flashlight` reads `CameraManager` torch state after toggling |
| `DISPATCHED` | The platform accepted the Intent; outcome unknown | `open_settings` opened the Wi-Fi panel |
| `UNVERIFIED` | Sent through a channel with no feedback | `share_text`, `copy_to_clipboard` |
| `FAILED` | Definite failure | `open_app` — package not installed |

`AgentEngine` never upgrades a step to `VERIFIED`. Only the tool can, because
only the tool knows what evidence it has.

## 3b. Privacy: screen content is local-only

Text read through Accessibility never reaches a remote provider. This is enforced
in code, not by documentation:

```
screen_summary ──► ScreenSummaryFormatter ──► ScreenContentGuard.registerScreenRead()
                                  │                        │
                                  ▼                        ▼
                        PlanStep.localOnly = true   fragments registry
                                  │                        │
        transcript (local, shown) │                        │
                                  ▼                        ▼
                    RemoteHistoryFilter          PrivacyFilteringProvider
                    (message dropped from        (scrubs prompt + every turn,
                     outbound history)            refuses if anything remains)
                                  │                        │
                                  └────────► AiProvider ───┘
```

Three independent mechanisms:

1. **Structural** — `AgentEngine` marks every step of a tool whose
   `outputPrivacy == SCREEN_CONTENT` as `localOnly` *before* execution, and
   `ChatMessage.containsLocalOnlyContent` makes the whole turn ineligible for
   remote history. A future tool only has to declare its output type.
2. **Content** — `ScreenContentGuard` stores distinctive fragments of every screen
   read (lines ≥ 12 chars, or ≥ 6 chars containing a digit). Any outbound string
   containing one is dropped (history) or redacted (prompt). Short generic labels
   are intentionally not registered, so "إرسال" on a button does not poison
   ordinary prompts.
3. **Transport** — `ProviderFactory` wraps every provider in
   `PrivacyFilteringProvider`, which scrubs and then re-checks the final request.
   A new caller cannot bypass the rule by holding a raw provider.

Stored locally and never uploaded: `conversation.json` (excluded from both cloud
backup and device transfer), the encrypted API key, and the screen fragments.

A request typed by the user that already contains a registered fragment is
refused with an explicit Arabic message rather than forwarded
(`LlmPlanner.SCREEN_CONTENT_BLOCKED`).

## 3c. Consent: sensitive actions are never implicit

`RiskLevel.SENSITIVE` is the single source of truth, and `AgentEngine` checks it
for every step:

```kotlin
if (tool.risk == RiskLevel.SENSITIVE) {
    emit(AgentEvent.ConfirmationRequired(plan, index, tool.title))
    if (!confirmer.confirm(request)) { /* SKIPPED, abort */ }
}
```

There is no constructor flag and no user setting that skips this branch — the
old `confirmSensitive` preference was removed because a safety rule must not
have an off switch. The dialog shows the tool, the step title and the
exact arguments (`SetTextTool` prints the text it will type, `DialTool` the
number, `CopyToClipboardTool` the clipboard replacement).

`ToolConfirmationPolicyTest` asserts the full classification of `defaultTools()`,
so adding a tool without deciding its risk fails the build.

## 4. Accessibility as an optional capability

`MyAiAccessibilityService` implements `ScreenController`
(`agent/ScreenController.kt:20`). `AccessibilityBridge` exposes the live
instance to the tool layer as a nullable reference.

```kotlin
interface ScreenController {
    fun snapshot(): ScreenSnapshot?   // visible, tappable nodes
    suspend fun clickByText(text: String): Boolean
    suspend fun setText(text: String, target: String?): Boolean
    suspend fun scroll(direction: ScrollDirection): Boolean
    fun global(action: GlobalAction): Boolean
}
```

Two consequences:

- Tools that need it declare `requiresAccessibility = true`; `AgentEngine`
  fails the step with an actionable Arabic message if the service is off,
  instead of silently doing nothing.
- The service can be revoked at any time by the system. Every call re-checks
  the bridge, so a revoked service degrades to `FAILED`, never to a crash.

Sensitive-by-nature actions (`click_text`, `set_text`, `global_action`) are
`SENSITIVE`, so they always pass through the confirmation gate.

## 5. Secrets

`SecureStore` (`core/SecureStore.kt:26`):

- AES-256-GCM key generated in the Android Keystore (`myai.secrets` alias),
  hardware-backed when available.
- Ciphertext stored in private SharedPreferences; the file is excluded from
  backup via `data_extraction_rules.xml`.
- `put()` uses `commit()` deliberately: the settings screen must not tell the
  user "saved" if the write failed.
- If the Keystore key is invalidated (lock screen change, factory reset), the
  ciphertext is dropped and the user is asked to re-enter the key — we never
  fall back to plaintext.

## 6. Overlay lifecycle

`FloatingAssistantService` is a `LifecycleService` with
`foregroundServiceType="specialUse"`:

1. `onStartCommand` verifies `Settings.canDrawOverlays()`; without it the
   service stops itself and shows a toast. No hidden window.
2. `startForeground` is attempted first; if the platform refuses (revoked
   notification permission, background start ban), the service stops instead of
   leaving a bubble with no notification — a bubble with no visible anchor is
   both confusing and against Play policy.
3. Bubble position is stored in `myai.floating` prefs, clamped to the current
   screen bounds on restore (rotation changes the display size).
4. The panel is a separate `TYPE_APPLICATION_OVERLAY` window hosting
   `overlay_panel.xml`; it uses the same `ChatController`, so the conversation
   continues seamlessly between the app and the bubble.
5. Stopping: notification action `com.myai.assistant.action.STOP_FLOATING`, the
   in-app toggle, or system unbind — all paths remove both windows and the
   notification.

## 6b. CI permissions and release separation

`.github/workflows/android.yml` uses the default `permissions: contents: read`
and grants write scopes per job:

| Job | Token | Runs when |
|---|---|---|
| `build` | `contents: read` | every push, PR, manual run |
| `release` | `contents: write` | push to `main` only, and only if `build` succeeded |
| `diagnostics` | `contents: read`, `issues: write` | push events, even when the build fails |

The build job cannot write to the repository at all; a feature branch produces an
APK artifact and a diagnostics comment, never a release. Checkout steps use
`persist-credentials: false`, and lint is a gate (AGP fails on lint errors, plus a
step that greps the text report for `Error:` lines).

## 7. Why a rule-based planner exists

Local inference on-device is not shipped: bundling an LLM would add hundreds of
megabytes and a thermal/battery cost that contradicts the project's constraints.
`LocalInferenceEngine` (`ai/AiProvider.kt:43`) therefore exists as an extension
point, and `UnavailableLocalEngine` throws an explicit `AiException` rather than
pretending to answer.

The rule-based planner covers the common device commands with regexes tuned for
Arabic (including the ta-marbuta/ha variants and optional hamza), and it
preserves the original text for free-text payloads (search queries, SMS bodies,
clipboard) by extracting from the raw input instead of the normalized one. That
distinction is the reason `طقس جدة` is not silently rewritten to `طقس جده`.

## 8. Testing strategy

Unit tests cover the pure layers, which is where the logic bugs live:

| File | What it pins down |
|---|---|
| `util/ArabicTextTest.kt` | normalization, hamza/ta-marbuta folding |
| `agent/PlanParserTest.kt` | JSON contract, unknown tool rejection, bad args |
| `agent/RuleBasedPlannerTest.kt` | intent coverage, raw-text preservation, honesty on unknown input |
| `ai/OpenAiCompatibleProviderTest.kt` | request shape, error mapping, missing key |
| `agent/AgentEngineTest.kt` | ordering, abort-on-failure, confirmation refusal, verification |
| `agent/ToolConfirmationPolicyTest.kt` | every SENSITIVE tool asks first; safe tools never do |
| `agent/LlmPlannerPrivacyTest.kt` | no leak through history, request, or system prompt |
| `privacy/ScreenContentGuardTest.kt` | fragment registration, redaction, bounded memory |
| `privacy/RemoteHistoryFilterTest.kt` | local-only turns never enter remote history |
| `ai/PrivacyFilteringProviderTest.kt` | transport-level scrub as a second layer |
| `tools/ScreenSummaryFormatterTest.kt` | what counts as screen content, and clipping |

Android-dependent code is kept thin and free of logic so these tests carry real
weight: a tool is mostly a mapping from arguments to an Intent, and the
interesting decisions (what is safe, what aborts, what counts as verified) are
in testable Kotlin.
