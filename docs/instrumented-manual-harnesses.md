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
| SlockModalPointerBarrierConnectedTest | Modal layer / pointer barrier | Pixel probe; modal layer regressed 3× (#682→#910) — primary repro harness for that lane |
| SlockTaskStatusMenuConnectedTest | Anchor-lease machinery, status menus | Pixel probes for message-owned anchors + LazyRow remount lease invalidation (#834/#844) |
| SlockTaskListStatusMenuConnectedTest | Tasks panel recycled rows | Needs `-PslockVisualFixtures=true` (fixture `status-menu-recycle`); color-band geometry heuristics |
| SlockFloatingOverlayTouchGuardConnectedTest | Floating overlay click-through | `sendPointerSync` is focus-sensitive; modal successor tests cover the same layer |
| SlockAttachmentCommentInputFocusTest | File-preview comment input focus (#573) | Synthetic pointer/key injection |
| SlockMarkdownRerenderConnectedTest | Markdown inline chips in lazy rows | Needs visual fixtures; chips regressed repeatedly — repro harness for that lane |
| SlockRealThreadMarkdownConnectedTest | Mention-chip styling on real data | Opt-in only: `-e slockRealThreadMarkdown true` + real-thread page data and a logged-in session; auto-skips otherwise |
| KuiklyProfilerRuntimeSmokeTest | Recomposition profiler plumbing | Dev tooling smoke (start/reset/stop/export produce report + frames files) |
| visual/KmpHomeScrollBoundsConnectedTest | kuikly-ui pin bumps (scroll bounds) | 23 injected swipes; rerun on fork scroll changes rather than gating CI |

## Promoted to CI by task #19 (for reference)

SlockChildPageVisibilityConnectedTest, SlockPagerScrollProbeConnectedTest,
core.data.diagnostics.DiagnosticsCallerPipelineConnectedTest,
shared androidInstrumentedTest data.DurablePartitionAndroidDriverMigrationTest
(every PR), SlockThreadNewerAutoPaginationConnectedTest (main/nightly only) —
all in test.yml's android-device job.

## Pending a shell-lane behavior decision (do not promote, do not "fix" the test)

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
demotion no longer happens for raw intents. Whether #1152's semantics are
intended (→ update the test) or a gap (→ restore normalization) is the
shell/launch-contract lane's call — routed via Raft task; until then the
class stays manual and its other three tests remain valid.

## Deleted by task #19

SlockComposerNewlineActionModeTest — its assertion (an EditText exists and
`performLongClick()` returns true) could not detect the regression it was
written for.
