# Bugly Capability Audit

Date: 2026-06-24
Owner lane: KMP/shared diagnostics + Android/OHOS host bridge
Source thread: #KMP:752928af / task #215

## Sources

- Android official docs: <https://bugly.tds.qq.com/docs/sdk/android/>
- Harmony official docs: <https://bugly.tds.qq.com/docs/sdk/harmony/>
- Android SDK public API verified from local AAR:
  - `com.tencent.bugly:bugly-pro:4.4.7.8`
  - `Bugly`, `BuglyBuilder`, `BuglyCustomLog`, `BuglyConstants`
- Harmony local dependency:
  - `bugly@0.5.1`

## Product Requirement

Settings diagnostics feedback has two product destinations:

- A normal Raft channel attachment: user/support must receive a local text file.
- Bugly platform diagnostics: crash/observability data should also be searchable in Bugly when supported.

These must be separate host actions. The channel attachment must not depend on asynchronous Bugly upload success.

## Android

### Already wired before this task

- SDK dependency:
  - `compose/androidApp/build.gradle.kts` uses `libs.bugly.pro`.
  - `BUGLY_ANDROID_APP_ID` and `BUGLY_ANDROID_APP_KEY` come from Gradle property or environment.
- Initialization:
  - `SlockAndroidBuglyDiagnostics.init()` configures Bugly Pro server host, app version/build, app channel, device model, debug/release version mode, log level, all-thread crash/ANR stack, crash protect, and foreground app state.
- User context:
  - `SlockApplication.onCreate()` reads `EncryptedAuthSessionStore` and calls `SlockAndroidBuglyDiagnostics.updateUserContext(...)`.
  - `SlockKuiklyActivity` also calls `updateUserContext(...)` after auth/session changes.
  - Existing implementation sets Bugly user id and custom user data for user/server id/slug.
- Existing observability:
  - full launch: `AppLaunchProxy.getAppLaunch().reportAppFullLaunch()`
  - caught exception: `Bugly.postException(...)`
  - test crash: `Bugly.testCrash(...)`
  - scene: `Bugly.enterScene(...)`, `Bugly.exitScene(...)`, `Bugly.setTestLabels(...)`
  - Kuikly perf snapshot: `Bugly.monitorEvent(...)`
  - looper metric/stack monitors: `Bugly.setPerformanceMonitorAble(...)`

### Official capability surface not fully used

- `BuglyBuilder.uniqueId`: recommended stable device unique id.
- `Bugly.updateUniqueId(...)`: update device id after init.
- `Bugly.updateDeviceModel(...)`: update model after init.
- `BuglyCustomLog`: write business custom logs before crash; Bugly uploads them as `BuglyLog.zip`.
- `Bugly.putUserData(...)`: up to 50 custom key/value pairs shown with crash details.
- `Bugly.setAdditionalAttachmentPaths(...)`: up to 10 custom files, compressed total up to 10MB, uploaded after process restart and shown as `custom_log.zip`.
- `Bugly.triggerUserDataReport()`: active user-data report trigger, SDK 4.4.7.8+.
- `Bugly.addCallback(...)`: frame/message lag callbacks, SDK 4.4.7.8+.
- `Bugly.getGlobalCustomDataEditor()` / custom data collectors: performance custom dimensions and fields.
- crash/upload callbacks via `BuglyBuilder.setCrashHandleListener(...)` and `setUploadHandleListener(...)`.

### Added in this implementation pass

- Forward sanitized app diagnostics into `BuglyCustomLog` from `SlockDiagnosticsLogger`.
- Register generated Settings feedback files with `Bugly.setAdditionalAttachmentPaths(...)`.
- Emit `slock_diagnostics_feedback_prepared` monitor event with attachment metadata.
- Update Bugly device model after init using `Bugly.updateDeviceModel(...)`.
- Add `slock_platform=android` custom data alongside user/server context.

### Still worth doing

- Set and persist a stable `BuglyBuilder.uniqueId` / `Bugly.updateUniqueId(...)`.
  - Android currently relies on Bugly-generated unique id.
  - Harmony already persists a `bugly/device_id.txt`.
- Add explicit Settings action for Bugly upload/report status.
  - Android custom attachment upload is crash-associated and happens after process restart; it is not the same as OHOS `TDDiag.uploadLogFiles`.
  - UI copy should say “registered for next Bugly issue attachment” unless we add a direct upload API.
- Add upload callback telemetry if product wants confirmation.
- Add lag callbacks only if we have a concrete use: callbacks run off-main but still require throttling and should not flood shared state.
- Add custom data collectors only after defining stable dimensions.

## Harmony / OHOS

### Already wired before this task

- SDK dependency:
  - `compose/ohosApp/entry/oh-package.json5` uses `bugly@0.5.1`.
- Initialization:
  - `EntryAbility.initBugly()` configures app id/key, persisted device id, platform, app version/build, channel, device model, debug/sdk log mode, crash listener, JS crash protect, privacy authorization, and all perf modules.
- Crash/error:
  - `SlockBuglyCrashListener` records crash callbacks.
  - `SlockOhosBuglyDiagnostics.reportCaughtError(...)` uses `Bugly.postCustomError(...)`.
  - `storeCrashRelatedError(...)` supports `storeFirst=true` with `extraData`.
- TDLog/TDDiag:
  - `SlockOhosBuglyDiagnostics.init()` configures `TDLog` under `filesDir/bugly_tdlog`.
  - `TDDiag.uploadLogFiles(...)` uploads TDLog dir, diagnostics snapshot, and crash dir.
  - `SlockOhosDiagnostics.setPersistentRecorder(...)` forwards app diagnostics to TDLog.
- Scene:
  - `SlockOhosBuglyDiagnostics.updateScene(...)` calls `Bugly.enterScene(...)` / `exitScene(...)`.

### Official capability surface not fully used

- `BuglyBuilder.userId`: initialization-time user id.
- `Bugly.updateUserId(...)`: update user id after init.
- `Bugly.updateDeviceId(...)`: update device id after init.
- `Bugly.updateDeviceModel(...)`: update model after init.
- `Bugly.putUserData(...)`, `removeUserData(...)`, `clearUserData()`: custom data for crash/freeze/error.
- `Bugly.setCustomFilePaths(...)`: custom attachments for Crash and Freeze.
- `Bugly.setCaseLabel(...)`: case labels.
- `Bugly.setLogAdapter(...)`: export Bugly SDK logs into business logger.
- `Bugly.updatePrivacyInfoAuthorized(...)`: post-init privacy authorization.
- `Bugly.setDeleteFaultLogFileAfterUpload(...)`: FaultLog retention policy.

### Current gaps

- OHOS has no visible host auth-session callback equivalent to Android's `EncryptedAuthSessionStore` path, so user id/server context is not currently updated after login/session changes.
- `KRBridgeModule.sendFeedback` currently triggers Bugly/TDDiag upload and does not return a `SettingsDiagnosticsFeedbackDraft`.
- Channel feedback needs a local file draft even when Bugly upload succeeds.
- `setCustomFilePaths(...)` is not used for the generated local diagnostics file.

### Recommended next OHOS implementation

- Add a host-visible auth/session context update path, then call:
  - `Bugly.updateUserId(userId)`
  - `Bugly.putUserData("slock_user_id", ...)`
  - `Bugly.putUserData("slock_server_id", ...)`
  - `Bugly.putUserData("slock_server_slug", ...)`
- Split Settings diagnostics actions:
  - `prepareDiagnosticsFeedback`: generate `cacheDir/slock-feedback/slock-feedback-<ts>.txt` and return `bodyBase64`.
  - `uploadDiagnosticsToBugly`: call current TDDiag upload path and return `queryKey`.
- Register generated diagnostics file with `Bugly.setCustomFilePaths([...])` when path length constraints allow.
- Keep `TDLog.flushLog()` before both channel export and Bugly upload.

## Validation Notes

- Full Android compile is currently blocked before the changed Android sources by unrelated generated SQLDelight/shared compile failures in `SlockSqlDatabase.kt` / `SqlDelightComposeLocalDataStore.kt`.
- Excluding shared compile lets Android compile progress further, but then it fails on unrelated missing shared model classes in `LegacySharedShellHelpers.kt` and workspace file tree types. No compiler error from the modified Bugly files appeared before those unrelated failures.
