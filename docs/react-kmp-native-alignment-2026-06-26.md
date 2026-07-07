# React vs KMP Alignment: Native Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: Native (Android + OHOS)

包含: appBadge / notification / widget / file preview / launcher icon / safe area / Kuikly / MMKV / Quiver diagnostics / OHOS platform fixes

## React source of truth

N/A — React is web, no native. PUX scans KMP/Android + OHOS native layer.

## KMP source

- `compose/androidApp/src/main/java/build/raft/app/shared/AndroidAppScopeRealtimeOwner.kt`
- `compose/androidApp/src/main/java/build/raft/app/shared/AndroidUnreadNotificationPoster.kt`
- `compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt`
- `compose/ohosApp/entry/src/main/ets/kuikly/`
- `compose/ohosApp/entry/src/main/ets/kuikly/KuiklyViewDelegate.ets`

## Feature points inventory (06-23 → 06-26)

(52 features, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 12)

主要类别:
1. **MMKV** (3 features): `feat(native)` add Kuikly MMKV preference backend, `androidRegisterMmkvModuleAndExtractNativeLibs`, `nativeRequireMmkvPreferenceBackend`
2. **Notifications** (5 features): foreground realtime, post from background, surface foreground, retry after auth restore, retain rooms paused
3. **Widgets** (4 features): `feat(ohos)` add activity and agent widgets, `feat(android)` add raft launcher widgets, widget button margins, widget server per native, widget align live agent cards
4. **Launcher icon** (6 features): `androidUseImageLauncherForeground`, `androidRegenerateLauncherIcons`, `androidShrinkLauncherIconMark`, `androidShrinkAdaptiveLauncherForeground`, `androidRestoreWhiteLauncherIconFill`, `androidInsetAdaptiveLauncherForeground`
5. **File preview** (8 features): generic download controls, show download progress immediately, show progress and speed, stream attachment download controls, manage downloaded attachment cache, route unsupported uniformly, polish file icons, generate common file icons
6. **Diagnostics / Quiver** (5 features): `feat(ohos)` export app diagnostics archive, `feat(android)` attach diagnostics, `feat(android)` export feedback, `feat(native)` expand app diagnostics, `androidUseTdlogAsDiagnosticsSink`
7. **Kuikly baseline** (5 features): `androidUse16kbAlignedNativeDeps`, `androidCenterKuiklyTextGlyphMetrics`, `androidAlignKuiklyTextBaseline`, `androidAlignCjkTextBaseline`, `feat(gradle)` add toolchain configuration
8. **Safe area / IME** (4 features): `androidPreserveGestureNavBottomInset`, `androidAvoidDoubleBottomSafeArea`, `androidRequestNotificationPermissionBeforeTestPush`, `androidGateVerboseTraceLogging`
9. **Background behavior** (4 features): `androidBackgroundAppShellOnRootBack`, `androidOpenUnreadNotificationsInThread`, `nativeAvoidPullRefreshBridgeCrash`, `nativeLimitRealtimeFanoutToForeground`
10. **OHOS platform fixes** (8 features): `ohosUnblockSharedAndHapBuilds`, `ohosSupportApiEnvironmentSwitching`, `ohosStreamAttachmentDownloadProgress`, `ohosForwardRealtimeEventsThroughSharedMapper`, `ohosDensifyDesktopWidgets`, `ohosCacheOpenedAttachments`, `ohosAvoidRuntimeConfigRegexInitCrash`, `ohosAddStartWindowFieldsToConfigAbility`

## 现状

### ✅ 已对齐 (06-23 → 06-26)
- MMKV preference backend (per #96 done)
- Foreground / background realtime notifications (per #103 #105 done)
- Auth restore retry, rooms retained (per native done)
- Widget implementation (per #110 #283 done)
- Launcher icon polish (per #95 #110 done)
- File preview download progress + speed (per #377 done)
- App diagnostics integration (per #111 done)
- Kuikly text baseline fixes (CJK + glyph metrics)
- Safe area / IME preservation
- OHOS platform fixes (8 items)
- Keepalive preference (per #112 done)

### ⚠️ 接近对齐 (P1)
- Notification title/body 退化成数字 (#102/#103 in_progress)
- File preview text/markdown/code parity (#421-#425 Pi-Worker1 todo)

### ❌ 仍未对齐 (06-26 仍 P0/P1)
1. **Notification title/body 退化成 "x unread"** (#102/#103 in_progress)
   - Background realtime 通知发出后, 收到 system tray 显示 "x unread" 而不是 "xxx: xxx"
   - 修复: `postUnreadNotification` call 的 request title/body 不是数字
2. **File preview text/markdown/code** (#421-#425 Pi-Worker1 in_progress)
   - React side: 已经支持 text/markdown/code preview
   - KMP side: `composeFilePreview.kt` 当前 only 图像
   - 修复: 加 `kind=video` panel + truncated copy for text/markdown
3. **File preview list visual testing** (per @artin 02:08)
4. **HarmonyOS widget 信息量 + brutal style 适配** (per #382 todo)
   - 当前 widget 信息少, 字太大
   - 鸿蒙 widget 不要 brutal 风格, 用标准圆角
5. **Foreground keep-alive notification switch** (per #105 done 但 #113 todo 分离)
6. **Native realtime bridge cleanup** (per #321 Codex-KMP-Developer todo)
   - 统一通过 NotifyModule 分发
7. **Android-Launcher icon font 太大** (per #95 done, #280 todo partial)

### ❌ 不在 React parity 范围 (KMP 业务扩展 / 工程改造)
- Quiver crash + app diagnostics (#111 done)
- Kuikly fork artifact publish (#113 done)
- File preview MP4/text (#421-#425)
- Reaction sprite atlas (#397)
- Launcher widget #110 #283 done

## P0/P1/P2 缺口分类

### P0 (本轮必修)
- Notification title/body (#102/#103)
- File preview text/markdown/code (#421-#425 Pi-Worker1)
- Native realtime bridge cleanup (#321)
- File preview list visual testing (per @artin 02:08)

### P1 (体验 / 视觉)
- HarmonyOS widget 信息量 (#382)
- Foreground keep-alive switch (#113)
- Android launcher icon font (#280)

### P2 (业务扩展)
- Quiver crash + app diagnostics (#111 done)
- Kuikly fork publish (#113 done)
- Reaction sprite atlas (#397)

## Owner 拍 (下一步)

- (i) 等 #102/#103 notification title/body 修复 (API-Integration-Developer), 我跑 verify
- (ii) 等 #421-#425 file preview text/markdown/code (Pi-Worker1), 我跑 verify
- (iii) 等 #321 native realtime bridge cleanup (Codex-KMP-Developer), 我跑 verify
- (iv) 等 Files list visual testing case (owner 拍), 我跑 verify
- (v) owner 显式派

我倾向 (i)+(ii)+(iii) 并行: API + Pi-Worker1 + Codex-KMP-Developer 修完, 我跑 v6.x verify.

PUX 不下 fix decision, 不写 code. 不动 source (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26