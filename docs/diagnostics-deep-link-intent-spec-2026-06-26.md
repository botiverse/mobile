# Diagnostics Deep-Link Intent Spec (2026-06-26)

Author: @Pi-Worker1 (task #4 follow-up)
Trigger: owner @artin in #Perf 2026-06-26 msg `a552c47f` — "总结一个需求，intent 直接 deep-link，我来帮忙发给别人实现".
Status: spec only. No code committed. Owner will dispatch implementation to another lane.

> **Update (2026-09-29):** the recomposition profiler was removed per artin,
> together with `scripts/slock-recomposition-profiler.ts`. The deep link now
> only opens Diagnostics (plus optional `auto_close_ms`); the `enable_profiler`,
> `reset_scene`, and `export_report` extras described below are no longer read.

## 1. Problem

Today the only way to open the Slock Diagnostics screen and toggle the
recomposition profiler is to tap through 4 layers in the running app:

```
Home → bottom-nav SETTINGS → Settings row "Developer" → Developer row "Diagnostics"
  → Diagnostics page → Recomposition profiler toggle
```

This path is exercised by:

- `scripts/slock-recomposition-profiler.ts` (the in-app profiler automation);
- ad-hoc captures by hand / by `slock_android_adb.py` + manual taps.

It is fragile because:

- UI text and bounds shift with copy / i18n / screen size changes — the row
  finder has to be revalidated per release.
- `tsx scripts/slock-recomposition-profiler.ts` currently throws
  `spawnSync adb ENOBUFS` on this emulator (Node.js + macOS pipe-buffer
  bug with large stdout from `adb exec-out screencap -p`); the workaround is
  to switch to async `spawn()`, but a stable deep-link avoids the wrapper
  altogether.
- Manual captures from `interaction_capture.py` cannot do 4-layer navigation
  cheaply; a one-shot intent shortcut would let `#4 send-path recomposition
  profiler` re-run in seconds.

## 2. Goal

Add a single Android intent (and the matching manifest entry + receiver
handler in shared KMP) that, when fired via `adb shell am start -a ...`,
performs the equivalent of the full 4-layer tap path:

- Opens the Diagnostics page.
- Optionally enables the recomposition profiler.
- Optionally resets the in-memory profiler counters.
- Optionally triggers a report export.

This lets perf capture scripts (and ad-hoc debug) skip UI navigation entirely.

## 3. Non-goals

- Do not change the user-facing Diagnostics page UI.
- Do not expose this intent on the Google Play release build. Gate it on
  `BuildConfig.DEBUG` (or a dedicated `adb-debug-only` manifest entry).
- Do not add new persistent state beyond what the existing profiler already
  persists (`recompositionProfilerPreferenceRepository`).
- Do not refactor the profiler implementation itself; this is a transport
  change only.

## 4. Proposed contract

### Intent shape

Action: `build.raft.app.diagnostics.OPEN`

Extras (all optional; defaults shown):

| extra key | type | default | meaning |
| --- | --- | --- | --- |
| `enable_profiler` | `boolean` | `false` | If true, call `recompositionProfilerController.start()` after the page opens. Persist via `recompositionProfilerPreferenceRepository.setEnabled(true)` so the user-facing toggle reflects the change. |
| `reset_scene` | `boolean` | `false` | If true, after `enable_profiler`, call the existing "Reset scene window" handler. |
| `export_report` | `boolean` | `false` | If true, after `reset_scene`, call the existing "Export report" handler (`getReport(saveToFile=true)`). |
| `auto_close_ms` | `long` | `0` | If > 0, finish the Diagnostics Activity after `auto_close_ms` ms. Useful when chained from `am start --ez ... --el auto_close_ms 15000` for captures. |

### Manifest entry

Add an `<activity-alias>` (not a new full Activity) so the existing
`SlockKuiklyActivity` / `MainActivity` routing stays unchanged:

```xml
<activity-alias
    android:name=".core.diagnostics.DiagnosticsDeepLinkActivity"
    android:targetActivity=".shared.SlockKuiklyActivity"
    android:exported="true"
    android:enabled="@bool/diagnostics_deeplink_enabled">

    <intent-filter>
        <action android:name="build.raft.app.diagnostics.OPEN" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>

</activity-alias>
```

`diagnostics_deeplink_enabled` should be `true` for `debug` build flavor,
`false` for `release` (gated via `res/values/booleans.xml` + `res/values-release/booleans.xml`).

### Receiver handler (shared KMP)

Add `build.raft.app.diagnostics.DiagnosticsDeepLink.kt` in `compose/shared/src/commonMain/kotlin/`:

```kotlin
@Composable
fun DiagnosticsDeepLinkEffect(
    intentExtras: DiagnosticsDeepLinkExtras,
    onOpenDiagnostics: () -> Unit,
    onEnableProfiler: () -> Unit,
    onResetProfiler: () -> Unit,
    onExportProfiler: () -> Unit,
    onAutoClose: (ms: Long) -> Unit,
)
```

The host layer (`SlockKuiklyActivity` or a dedicated small Kotlin
`DiagnosticsDeepLinkActivity`) reads the extras from the launching intent,
forwards them through the shared bridge, and the composable reacts with
`LaunchedEffect(intentExtras) { ... }`. The four callback slots are wired to:

- `onOpenDiagnostics`: navigate to `SettingsHomePage → Developer → Diagnostics`
  via the existing `RouterModule.openPage(...)` call. **Keep this no-op when
  already on the Diagnostics page** to avoid double-navigation loops.
- `onEnableProfiler`: `sharedSettingsStore.setRecompositionProfilerEnabled(true)`
  → `recompositionProfilerController.start()`.
- `onResetProfiler`: existing "Reset scene window" handler.
- `onExportProfiler`: existing "Export report" handler.
- `onAutoClose`: `requireActivity().finish()` after delay.

### Flag ordering (when multiple extras are set)

The handler must execute the flags in this order, on the same coroutine scope,
and only proceed to the next step after the previous one returns success:

1. `onOpenDiagnostics`
2. `onEnableProfiler` (if `enable_profiler`)
3. `onResetProfiler` (if `reset_scene`)
4. `onExportProfiler` (if `export_report`)
5. `onAutoClose(auto_close_ms)` (if `auto_close_ms > 0`)

If `enable_profiler` is false but `reset_scene` or `export_report` is true,
the handler must still navigate first (step 1) but skip steps 2 onward that
depend on the profiler being enabled, and log a warning via
`SlockOhosDiagnostics` equivalent channel.

## 5. End-to-end usage examples

### a) Open Diagnostics, no toggle
```
adb shell am start \
    -a build.raft.app.diagnostics.OPEN \
    -n build.raft.app/.core.diagnostics.DiagnosticsDeepLinkActivity
```

### b) Open Diagnostics + enable profiler + reset scene + export + auto-close in 15s
```
adb shell am start \
    -a build.raft.app.diagnostics.OPEN \
    -n build.raft.app/.core.diagnostics.DiagnosticsDeepLinkActivity \
    --ez enable_profiler true \
    --ez reset_scene true \
    --ez export_report true \
    --el auto_close_ms 15000
```

### c) Capture scenario (what `task #4` will look like after this ships)

```bash
# 1. Reset + enable + (after capture) export
adb shell am start -a build.raft.app.diagnostics.OPEN \
    -n build.raft.app/.core.diagnostics.DiagnosticsDeepLinkActivity \
    --ez enable_profiler true --ez reset_scene true
sleep 1
# 2. Drive the interaction (existing `interaction_capture.py` handles this)
python3 scripts/perf/interaction_capture.py \
    --device emulator-5554 --package build.raft.app \
    --label send-v3 --out artifacts/perf-send-v3 \
    --stage open-channel:574:1621 \
    --stage type:530:2175:audit-v2 \
    --stage tap:993:2258 --atrace-window 12
# 3. Trigger export via the same intent (no UI navigation needed)
adb shell am start -a build.raft.app.diagnostics.OPEN \
    -n build.raft.app/.core.diagnostics.DiagnosticsDeepLinkActivity \
    --ez export_report true
# 4. Pull profiler_report.json + profiler_frames.jsonl (SKILL.md §"pull-profiler")
python3 scripts/slock_android_adb.py --device emulator-5554 \
    pull-profiler --out artifacts/perf-send-v3 --package build.raft.app
```

## 6. Validation

Per-owner task #3 tooling convention + SKILL.md §"Reporting":

- `./gradlew :shared:compileDebugKotlinAndroid` BUILD SUCCESSFUL.
- `./gradlew :shared:compileDebugUnitTestKotlinAndroid` BUILD SUCCESSFUL.
- New unit tests in `compose/shared/src/commonTest/...` covering:
  - `enable_profiler=true` → profiler on, persisted to preference.
  - `enable_profiler=false, reset_scene=true` → reset is skipped, warning
    logged.
  - `export_report=true` after `enable_profiler=false` → skipped + warning.
  - `auto_close_ms=15000` → finish() called after 15s ± 1s tolerance.
- Manual smoke on `emulator-5554`:
  - example (a) above opens Diagnostics page.
  - example (b) opens Diagnostics, enables profiler (toggle visible "On"),
    shows reset/export reflect the intent.
  - release build (`./gradlew :androidApp:assembleRelease`) does NOT
    expose `DiagnosticsDeepLinkActivity` in `aapt dump xmltree ... AndroidManifest.xml`.

## 7. Lane / ownership recommendation

Owner (@artin) is dispatching — suggested assignees:

- **@Android-Developer-N** (Android host, manifest, Activity alias) — primary.
- **@Codex-Kuikly-KMP专家** (shared KMP composable effect, profiler controller
  wiring) — secondary if the effect side touches shared state.

`@Pi-Worker1` (me) will re-run the #4 capture once this lands, using the
intents in section 5c.

## 8. Out of scope (defer)

- iOS / HarmonyOS deep-link equivalents (they have different IPC; left for
  later if at all).
- A general "open any page by deep-link" framework; this is one intent for
  Diagnostics only.
- Removing the existing UI entry path (Settings → Developer → Diagnostics).
  Keep it as the user-facing path for manual on-device debugging.