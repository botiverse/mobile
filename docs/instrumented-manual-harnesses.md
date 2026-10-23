# Instrumented Manual Harnesses

Status of every Android instrumented test class that is **not** run by CI.
Origin: Raft #Perf task #17 audit + task #19 triage (2026-07-21). Policy (see
AGENTS.md "Unit Test Case Rule"): a compiled-but-never-run instrumented class
must either be promoted to a workflow, listed here as a manual harness, or
deleted — "compiles in CI, runs nowhere" is not an allowed steady state.

Run any harness against the connected-test APK pair that test.yml builds
(`build.raft.app.connectedtest` + `build.raft.app.connectedtest.test`, visual
fixtures enabled):

```bash
cd compose
./gradlew -PslockVisualFixtures=true :androidApp:assembleConnectedTest :androidApp:assembleConnectedTestAndroidTest
adb install -r androidApp/build/outputs/apk/connectedTest/*.apk
adb install -r androidApp/build/outputs/apk/androidTest/connectedTest/*.apk
adb shell am instrument -w -r -e class <FQCN> build.raft.app.connectedtest.test/androidx.test.runner.AndroidJUnitRunner
```

## Manual harnesses (keep; run when touching the named area)

| Class (build.raft.app.shared.* unless noted) | Rerun when touching | Notes |
|---|---|---|
| RaftModalPointerBarrierConnectedTest | Modal layer / pointer barrier | Pixel probe; modal layer regressed 3× (#682→#910) — primary repro harness for that lane |
| RaftTaskStatusMenuConnectedTest | Anchor-lease machinery, status menus | Pixel probes for message-owned anchors + LazyRow remount lease invalidation (#834/#844) |
| RaftTaskListStatusMenuConnectedTest | Tasks panel recycled rows | Needs `-PslockVisualFixtures=true` (fixture `status-menu-recycle`); color-band geometry heuristics |
| RaftFloatingOverlayTouchGuardConnectedTest | Floating overlay click-through | `sendPointerSync` is focus-sensitive; modal successor tests cover the same layer |
| RaftAttachmentCommentInputFocusTest | File-preview comment input focus (#573) | Synthetic pointer/key injection |
| RaftMarkdownRerenderConnectedTest | Markdown inline chips in lazy rows | Needs visual fixtures; chips regressed repeatedly — repro harness for that lane |
| RaftRealThreadMarkdownConnectedTest | Mention-chip styling on real data | Opt-in only: `-e slockRealThreadMarkdown true` + real-thread page data and a logged-in session; auto-skips otherwise |
| BaselineProfileGeneratorTest | Startup / cold-start path, `src/main/baseline-prof.txt` | task #25. Regenerates the baseline-profile seed by driving the thread first-open fixture (`-PslockVisualFixtures=true`). Standard Macrobenchmark flow; on OEM Android 16 its GrantPermissionRule may fail — equivalent seed can be captured via `am start --start-profiler --sampling 1000` (sampling reaches the Kuikly render context-queue thread; full method tracing does not). Profile only packs into non-debuggable variants (`assets/dexopt/`). |
| visual/KmpHomeScrollBoundsConnectedTest | kuikly-ui pin bumps (scroll bounds) | 23 injected swipes; rerun on fork scroll changes rather than gating CI |
| RaftForegroundBannerPointerBarrierConnectedTest | Foreground (in-app) banner, overlay pointer barrier | Pixel probe after an injected tap at a fixed dp point; same family as the modal pointer-barrier harness (task #18 triage) |
| RaftInlineReplyTimestampConnectedTest | Inline reply rows, message timestamp toggle | Needs visual fixtures (`inline_replies`); accessibility-tree lookups plus injected taps and pixel counts with fixed settle sleeps (task #18 triage) |

## Promoted to CI by task #19 (for reference)

RaftChildPageVisibilityConnectedTest, RaftPagerScrollProbeConnectedTest,
core.data.diagnostics.DiagnosticsCallerPipelineConnectedTest,
shared androidInstrumentedTest data.DurablePartitionAndroidDriverMigrationTest
(every PR), RaftThreadNewerAutoPaginationConnectedTest (main/nightly only) —
all in test.yml's android-device job.

Task #18 (2026-10-23) promoted AndroidAdaptivePaneHostConnectedTest,
core.data.auth.QaSessionBootstrapInboxConnectedTest and shared
androidInstrumentedTest RaftComposedCharacterBoundaryRuntimeTest to the same
job (`scripts/ci/run-first-character-emulator-suite.sh`, every run of the
device suite).

## On HOLD for a production fix (do not promote, do not "fix" the test)

**AndroidShellTaskReuseConnectedTest** — its first-ever CI run (task #19)
failed `secondServerLegacyDocumentLaunchCannotCreateAnotherAppShell`:
expected `android-default-app-shell`, got `server-shell:server-b`. Root
cause is not the test's device waits: PR #1152 (multi-shell host settings
gate, 2026-07-17) removed the activity-side
`normalizeAndroidAppShellPageData` call from `resolvedPageData` that the
test (written 2026-07-12 for #661/#866/#918) asserts, leaving that function
with no production caller and `registerAndroidShellWindow` resolving
targets with `multiShellHostEnabled = true` regardless of the preference.
Task-reuse assertions still hold (manifest singleTask); the pageData
demotion no longer happens for raw intents.

Adjudicated in Raft task #743 (launch-contract review, 2026-07-21): this is
a **production ingress gap left by #1152**, not intended semantics. The
test's expectation stands — do not rewrite it to accept
`server-shell:server-b`, and do not delete `normalizeAndroidAppShellPageData`.
The fix (a host-aware ingress canonicalizer consumed by splash/registry/
page-spec/store alike) belongs to the launch-contract lane; once it lands,
re-promote this class to the android-device gate. Until then it stays
manual and its other three tests remain valid.

## Deleted by task #19

SlockComposerNewlineActionModeTest — its assertion (an EditText exists and
`performLongClick()` returns true) could not detect the regression it was
written for.

## Deleted by task #18

RaftMermaidVisualCaptureConnectedTest — it saved screenshots to
`/sdcard/Download/mermaid-visual` and asserted nothing (it also still waited
for the retired Kuikly canvas). Mermaid rendering is covered by the visual
screenshot lanes.
