# Slock → Raft 改名分析

> 分析时间：2026-06-13
> 分析人：Pi-Test
> 范围：客户端代码库（不含 `third_party/slock-source/` 上游 server）

## 概述

品牌改名：**Slock → Raft**，域名从 `slock.ai` → `raft.build`。

### 总体规模

| 类别 | 数量 | 难度 | 说明 |
|------|------|------|------|
| 包名 `ai.slock` → `build.raft` | 2652 处 / 326+ 文件 | 🔴 高 | package/import 声明 + 目录结构 |
| 域名 `slock.ai` → `raft.build` | 121 处 / ~40 文件 | 🟡 中 | API 端点 + UI 文案 + 测试数据 |
| UI 可见文案 "Slock" → "Raft" | ~80 处 / 20+ 文件 | 🟢 低 | strings.xml + 硬编码字符串 |
| 文件名含 "Slock" | 130+ 文件 | 🔴 高 | Kotlin/Java/XML/ETS/SVG |
| 代码标识符 (类名/变量/常量) | ~500+ 处 | 🟡 中 | `SlockXxx` → `RaftXxx` |
| 资源文件 (color/theme/layout) | ~20 文件 | 🟢 低 | XML 资源 |
| 构建/配置文件 | ~15 文件 | 🟡 中 | Gradle/Manifest/backup rules |
| OHOS 鸿蒙端 | ~30 文件 | 🟡 中 | ETS 组件/配置 |

**不动的部分**：
- `third_party/slock-source/` — 上游 server 子仓，不应改
- `.git/` 历史记录
- `artifacts/` 已打好的包

### 向后兼容策略

**服务端已有 301 重定向**：`slock.ai` → `raft.build`，所有 API 请求会自动跟随 301。
- ✅ API 端点可以直接改成 `api.raft.build`，旧版本 app 的请求会被 301 到新地址
- ✅ 浏览器访问 `app.slock.ai` 会自动跳转到 `app.raft.build`

**需要客户端兼容的地方**：
1. **Deep Link（AndroidManifest.xml）**：旧域名 `app.slock.ai` 的链接可能被用户保存/分享过。**建议保留两个 host 的 intent-filter 至少一个大版本周期**
2. **ThreadPage.kt Web Link 匹配**：行 2088 的 host 判断需要同时认两个域名 `setOf("app.slock.ai", "app.raft.build").contains(host)`
3. **Email 域名**：用户真实邮箱如果是 `@slock.ai`，需要确认邮件发送是否仍走旧域名。测试/mock 数据可以直接改成 `@raft.build`

**不需要兼容的**：
- 包名、文件名、类名：纯内部，改完重新编译即可
- SharedPreferences：内部存储，做数据迁移
- XML 资源名、strings.xml 文案：编译时解析，运行时无影响

---

## 1. 域名替换 `slock.ai` → `raft.build`（P0 功能性）

### 1.1 API 端点（最关键，改错就连不上）

| 文件 | 改动 |
|------|------|
| `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SlockApiEndpoints.kt` | `https://api.slock.ai/api/` → `https://api.raft.build/api/`，`https://api.slock.ai` → `https://api.raft.build` |

### 1.2 Web Link / Deep Link（需要向后兼容）

| 文件 | 行号 | 改动 |
|------|------|------|
| `compose/shared/src/commonMain/kotlin/ai/slock/compose/ThreadPage.kt` | 2088 | `"app.slock.ai"` → `setOf("app.slock.ai", "app.raft.build").contains(host)` 同时认两个域名 |
| `compose/androidApp/src/main/AndroidManifest.xml` | 46 | 新增 `app.raft.build` intent-filter，**保留** `app.slock.ai` 旧域名兼容（至少一个大版本周期） |

### 1.3 strings.xml 中的域名

| 文件 | 改动 |
|------|------|
| `compose/androidApp/src/main/res/values/strings.xml` | `you@slock.ai` → `you@raft.build` |
| `compose/androidApp/src/main/res/values-zh-rCN/strings.xml` | `you@slock.ai` → `you@raft.build` |

### 1.4 测试/Mock 中的邮箱域名

所有 `@slock.ai` 测试邮箱 → `@raft.build`：

| 文件 | 涉及处数 |
|------|----------|
| `AuthDataSourceTest.kt` | ~15 处 |
| `AuthDiagnosticsExportTest.kt` | ~3 处 |
| `AuthSessionStoreTest.kt` | ~5 处 |
| `SharedThreadStoreTest.kt` | ~3 处 |
| `ThreadPayloadTest.kt` | ~10 处 |
| `HomeParserTest.kt` | ~5 处 |
| `SlockMarkdownTest.kt` | ~2 处 |
| `AvatarPriorityTest.kt` | ~2 处 |
| `MemberRepositoryDataSourceTest.kt` | ~3 处 |
| `SettingsRepositoryDataSourceTest.kt` | ~2 处 |
| `ChannelRepositoryDataSourceTest.kt` | ~2 处 |
| `ConversationRepositoryDataSourceTest.kt` | ~2 处 |
| `InboxSavedSearchRepositoryDataSourceTest.kt` | ~2 处 |
| `TaskRepositoryDataSourceTest.kt` | ~2 处 |
| `SharedHomeStoreTest.kt` | ~2 处 |
| `SharedSettingsStoreTest.kt` | ~2 处 |
| `SharedTasksStoreTest.kt` | ~2 处 |
| `SharedAuthGateStoreTest.kt` | ~2 处 |
| `AuthPages.kt` (preview data) | ~3 处 |
| `AuthComponents.kt` (preview data) | ~1 处 |
| `SettingsAccountPage.kt` (preview data) | ~2 处 |
| `MockSeedData.kt` | ~5 处 |
| `SettingsAccountScreen.kt` | ~3 处 |
| `sharedCases.json` (visual testing) | ~5 处 |

**可批量 sed**：`sed -i '' 's/@slock\.ai/@raft.build/g'`

### 1.5 其他文档

| 文件 | 改动 |
|------|------|
| `docs/kmp-settings-capability-parity-audit.md` | slock.ai → raft.build |

---

## 2. UI 文案 "Slock" → "Raft"（P0 用户可见）

### 2.1 strings.xml（EN）

`compose/androidApp/src/main/res/values/strings.xml` 中 **35+ 条**需要改：

| string name | 旧值 | 新值 |
|-------------|------|------|
| `app_name` | Slock | Raft |
| `auth_reset_description` | ...Slock account... | ...Raft account... |
| `auth_create_description` | ...Slock workspace... | ...Raft workspace... |
| `auth_email_placeholder` | you@slock.ai | you@raft.build |
| `settings_billing_trial_detail` | ...Slock with all core... | ...Raft with all core... |
| `settings_about_logs_clip_label` | Slock diagnostics | Raft diagnostics |
| `settings_about_share_subject` | Slock diagnostics | Raft diagnostics |
| `settings_mobile_push_disabled_detail` | ...Slock is in the background... | ...Raft is in the background... |
| `settings_notification_test_title` | Slock notification test | Raft notification test |
| `notification_unread_general_channel_description` | ...Slock message... | ...Raft message... |
| `inbox_notification_channel_description` | ...from Slock. | ...from Raft. |
| `home_server_fallback` | Slock | Raft |
| `home_default_title` | Slock Android | Raft Android |
| `home_create_server_detail` | ...Slock will switch... | ...Raft will switch... |
| `home_create_channel_detail` | ...Slock will open... | ...Raft will open... |
| ... | (还有 ~20 条) | ... |

### 2.2 strings.xml（ZH）

`compose/androidApp/src/main/res/values-zh-rCN/strings.xml` 同步改，所有 "Slock" → "Raft"。

### 2.3 硬编码 UI 字符串

| 文件 | 改动示例 |
|------|----------|
| `SettingsHomePage.kt` | `"Open Slock"` → `"Open Raft"`，`"Open app.slock.ai in the system browser."` → `"Open app.raft.build in the system browser."` |
| `SettingsAccountScreen.kt` | preview data 中的 `@slock.ai` |

---

## 3. 包名 `ai.slock` → `build.raft`（P1 最大改动）

### 3.1 包名替换

**规则**：Java/Kotlin 域名倒写
- `slock.ai` → `ai.slock`
- `raft.build` → `build.raft`

**替换映射**：
- `ai.slock.compose` → `build.raft.compose`
- `ai.slock.compose.data` → `build.raft.compose.data`
- `ai.slock.compose.model` → `build.raft.compose.model`
- `ai.slock.compose.network` → `build.raft.compose.network`
- `ai.slock.compose.markdown` → `build.raft.compose.markdown`
- `ai.slock.compose.components` → `build.raft.compose.components`
- `ai.slock.android` → `build.raft.android`
- `ai.slock.android.core` → `build.raft.android.core`
- `ai.slock.android.feature` → `build.raft.android.feature`
- `ai.slock.android.ui` → `build.raft.android.ui`
- `ai.slock.android.shared` → `build.raft.android.shared`
- `ai.slock.shared` → `build.raft.shared`

### 3.2 目录结构

```
# 旧
compose/shared/src/commonMain/kotlin/ai/slock/compose/
compose/shared/src/androidMain/kotlin/ai/slock/compose/
compose/shared/src/commonMain/sqldelight/ai/slock/shared/data/local/
compose/androidApp/src/main/java/ai/slock/android/

# 新
compose/shared/src/commonMain/kotlin/build/raft/compose/
compose/shared/src/androidMain/kotlin/build/raft/compose/
compose/shared/src/commonMain/sqldelight/build/raft/shared/data/local/
compose/androidApp/src/main/java/build/raft/android/
```

### 3.3 Gradle 配置

| 文件 | 改动 |
|------|------|
| `compose/androidApp/build.gradle.kts` | `namespace = "ai.slock.android"` → `"build.raft.android"`，`applicationId = "ai.slock.android"` → `"build.raft.android"` |
| `compose/shared/build.gradle.kts` | `namespace = "ai.slock.compose.shared"` → `"build.raft.compose.shared"` |

### 3.4 SQLDelight

| 文件 | 改动 |
|------|------|
| `compose/shared/src/commonMain/sqldelight/ai/slock/shared/data/local/SlockDatabase.sq` | package 声明 + 文件路径 `ai/slock/` → `build/raft/`，文件名 → `RaftDatabase.sq` |

### 3.5 AndroidManifest

`ai.slock.android` 相关的所有引用（application name、activity name、authorities 等）。

### 3.6 统计

- 2652 处 `ai.slock` 引用需要替换
- 326+ 文件需要修改
- 建议用 IDE Refactor → Rename Package 或脚本批量处理

---

## 4. 文件名重命名（P1）

### 4.1 compose/shared/ KMP 共享层（30+ 文件）

| 旧文件名 | 新文件名 |
|----------|----------|
| `SlockApiEndpoints.kt` | `RaftApiEndpoints.kt` |
| `SlockAppShell.kt` | `RaftAppShell.kt` |
| `SlockBackHandler.kt` | `RaftBackHandler.kt` |
| `SlockDesignTokens.kt` | `RaftDesignTokens.kt` |
| `SlockFilterPopover.kt` | `RaftFilterPopover.kt` |
| `SlockFloatingOverlay.kt` | `RaftFloatingOverlay.kt` |
| `SlockHomeComponents.kt` | `RaftHomeComponents.kt` |
| `SlockLucideIcon.kt` | `RaftLucideIcon.kt` |
| `SlockLucideIconCatalog.kt` | `RaftLucideIconCatalog.kt` |
| `SlockLucideIconGalleryPage.kt` | `RaftLucideIconGalleryPage.kt` |
| `SlockMenu.kt` | `RaftMenu.kt` |
| `SlockModalComponents.kt` | `RaftModalComponents.kt` |
| `SlockSafeArea.kt` | `RaftSafeArea.kt` |
| `SlockSectionHeader.kt` | `RaftSectionHeader.kt` |
| `SlockStatusIndicator.kt` | `RaftStatusIndicator.kt` |
| `SlockTabBar.kt` | `RaftTabBar.kt` |
| `SlockBrutalComponents.kt` | `RaftBrutalComponents.kt` |
| `SlockRepositories.kt` | `RaftRepositories.kt` |
| `SlockSharedStoreProvider.kt` | `RaftSharedStoreProvider.kt` |
| `SlockSqlDatabase.kt` | `RaftSqlDatabase.kt` |
| `SlockNetworkAuthMiddleware.kt` | `RaftNetworkAuthMiddleware.kt` |
| `SlockNetworkModule.kt` | `RaftNetworkModule.kt` |
| `SlockApiClient.android.kt` | `RaftApiClient.android.kt` |
| `KtorSlockNetworkClient.android.kt` | `KtorRaftNetworkClient.android.kt` |
| `KuiklySlockNetworkClient.kt` | `KuiklyRaftNetworkClient.kt` |
| `SlockMarkdown.kt` | `RaftMarkdown.kt` |
| `SlockModels.kt` | `RaftModels.kt` |
| `SlockTime.kt` | `RaftTime.kt` |
| `SlockWorkspaceModels.kt` | `RaftWorkspaceModels.kt` |
| `SlockDatabase.sq` | `RaftDatabase.sq` |

### 4.2 compose/androidApp/ Android 原生（40+ 文件）

| 旧文件名 | 新文件名 |
|----------|----------|
| `SlockApplication.kt` | `RaftApplication.kt` |
| `SlockKuiklyActivity.kt` | `RaftKuiklyActivity.kt` |
| `SlockKuiklyHostContract.kt` | `RaftKuiklyHostContract.kt` |
| `SlockKuiklyNetworkModule.kt` | `RaftKuiklyNetworkModule.kt` |
| `SlockKuiklyPerformanceDiagnostics.kt` | `RaftKuiklyPerformanceDiagnostics.kt` |
| `SlockKuiklyTextFieldViews.kt` | `RaftKuiklyTextFieldViews.kt` |
| `SlockKuiklyThreading.kt` | `RaftKuiklyThreading.kt` |
| `SlockLucideIconRenderView.kt` | `RaftLucideIconRenderView.kt` |
| `RealSlockNetworkClientFactory.kt` | `RealRaftNetworkClientFactory.kt` |
| `SlockAndroidBuglyDiagnostics.kt` | `RaftAndroidBuglyDiagnostics.kt` |
| `SlockCrashReporter.kt` | `RaftCrashReporter.kt` |
| `SlockDiagnosticsLogger.kt` | `RaftDiagnosticsLogger.kt` |
| `SlockPerformanceDiagnostics.kt` | `RaftPerformanceDiagnostics.kt` |
| `SlockModels.kt` (android) | `RaftModels.kt` |
| `SlockStateSamples.kt` | `RaftStateSamples.kt` |
| `SlockAdaptiveAppScaffold.kt` | `RaftAdaptiveAppScaffold.kt` |
| `SlockAgentStatusIndicator.kt` | `RaftAgentStatusIndicator.kt` |
| `SlockAvatar.kt` | `RaftAvatar.kt` |
| `SlockBadge.kt` | `RaftBadge.kt` |
| `SlockBottomTabBar.kt` | `RaftBottomTabBar.kt` |
| `SlockButton.kt` | `RaftButton.kt` |
| `SlockFeedbackComponents.kt` | `RaftFeedbackComponents.kt` |
| `SlockIconSquareButton.kt` | `RaftIconSquareButton.kt` |
| `SlockKeySurface.kt` | `RaftKeySurface.kt` |
| `SlockMemberAvatar.kt` | `RaftMemberAvatar.kt` |
| `SlockMemberPicker.kt` | `RaftMemberPicker.kt` |
| `SlockMenuOverlay.kt` | `RaftMenuOverlay.kt` |
| `SlockModal.kt` | `RaftModal.kt` |
| `SlockPageHeader.kt` | `RaftPageHeader.kt` |
| `SlockPullRefreshContainer.kt` | `RaftPullRefreshContainer.kt` |
| `SlockSectionHeader.kt` (android) | `RaftSectionHeader.kt` |
| `SlockSegmentOption.kt` | `RaftSegmentOption.kt` |
| `SlockSelectionControls.kt` | `RaftSelectionControls.kt` |
| `SlockTextField.kt` | `RaftTextField.kt` |
| `SlockWorkspaceFileTree.kt` | `RaftWorkspaceFileTree.kt` |
| `SlockShadow.kt` | `RaftShadow.kt` |
| `SlockTokens.kt` | `RaftTokens.kt` |

### 4.3 compose/ohosApp/ 鸿蒙端（15+ 文件）

| 旧文件名 | 新文件名 |
|----------|----------|
| `SlockOhosBuglyDiagnostics.ets` | `RaftOhosBuglyDiagnostics.ets` |
| `SlockOhosCrashReporter.ets` | `RaftOhosCrashReporter.ets` |
| `SlockLucideIconView.ets` | `RaftLucideIconView.ets` |
| `SlockLucidePathIconView.ets` | `RaftLucidePathIconView.ets` |
| `SlockOhosHdsTabBarView.ets` | `RaftOhosHdsTabBarView.ets` |
| `SlockLucidePathIcons.ets` | `RaftLucidePathIcons.ets` |
| `SlockNetworkModule.ets` | `RaftNetworkModule.ets` |
| `SlockOhosApiConfig.ets` | `RaftOhosApiConfig.ets` |
| `SlockOhosClipboard.ets` | `RaftOhosClipboard.ets` |
| `SlockOhosDiagnostics.ets` | `RaftOhosDiagnostics.ets` |
| `SlockOhosMotionBridge.ets` | `RaftOhosMotionBridge.ets` |
| `SlockOhosRealtimeBridge.ets` | `RaftOhosRealtimeBridge.ets` |
| `SlockNativeIconGallery.ets` | `RaftNativeIconGallery.ets` |
| `slock-icon.svg` | `raft-icon.svg` |
| `slock-lockup.svg` | `raft-lockup.svg` |

### 4.4 资源文件

| 旧文件名 | 新文件名 |
|----------|----------|
| `slock_splash_branding.xml` | `raft_splash_branding.xml` |
| `slock_splash_logo.xml` | `raft_splash_logo.xml` |
| `slock_splash_placeholder.xml` | `raft_splash_placeholder.xml` |
| `activity_slock_kuikly.xml` | `activity_raft_kuikly.xml` |
| `slock_file_paths.xml` | `raft_file_paths.xml` |

### 4.5 测试文件

| 旧文件名 | 新文件名 |
|----------|----------|
| `SlockDiagnosticsLoggerTest.kt` | `RaftDiagnosticsLoggerTest.kt` |
| `LiveSlockDbFlowTest.kt` | `LiveRaftDbFlowTest.kt` |
| `SlockAdaptiveAppScaffoldTest.kt` | `RaftAdaptiveAppScaffoldTest.kt` |
| `SlockNetworkAuthMiddlewareTest.kt` | `RaftNetworkAuthMiddlewareTest.kt` |
| `SlockMarkdownTest.kt` | `RaftMarkdownTest.kt` |
| `SlockTimeTest.kt` | `RaftTimeTest.kt` |
| `SlockApiClientContractTest.kt` | `RaftApiClientContractTest.kt` |
| `SlockRequestSchedulerTest.kt` | `RaftRequestSchedulerTest.kt` |
| `SlockAppShellTest.kt` | `RaftAppShellTest.kt` |
| `SlockHomeComponentsTest.kt` | `RaftHomeComponentsTest.kt` |
| `SlockSafeAreaTest.kt` | `RaftSafeAreaTest.kt` |
| `SlockThreadWebLinkTargetTest.kt` | `RaftThreadWebLinkTargetTest.kt` |

### 4.6 脚本/工具

| 旧文件名 | 新文件名 |
|----------|----------|
| `scripts/run_live_slock_db_flow.sh` | `scripts/run_live_raft_db_flow.sh` |
| `scripts/slock-visual-testing.mjs` | `scripts/raft-visual-testing.mjs` |
| `tools/visual-testing/src/slock-visual-image-diff.swift` | `tools/visual-testing/src/raft-visual-image-diff.swift` |
| `docs/slock-app-state-first-principles.md` | `docs/raft-app-state-first-principles.md` |

---

## 5. 代码标识符（P1）

所有 `Slock` 前缀的类名、变量名、常量名：

### 5.1 类名

- `SlockApplication` → `RaftApplication`
- `SlockApiClient` → `RaftApiClient`
- `SlockNetworkAuthMiddleware` → `RaftNetworkAuthMiddleware`
- `SlockDatabase` → `RaftDatabase`
- `SlockRepositories` → `RaftRepositories`
- `SlockSharedStoreProvider` → `RaftSharedStoreProvider`
- `SlockSqlDatabase` → `RaftSqlDatabase`
- `SlockMarkdown` → `RaftMarkdown`
- `SlockModels` → `RaftModels`
- `SlockTime` → `RaftTime`
- `SlockWorkspaceModels` → `RaftWorkspaceModels`
- `SlockNetworkModule` → `RaftNetworkModule`
- `SlockAppShell` → `RaftAppShell`
- `SlockDesignTokens` → `RaftDesignTokens`
- `SlockLucideIcon` → `RaftLucideIcon`
- `SlockLucideIconCatalog` → `RaftLucideIconCatalog`
- `SlockKuiklyActivity` → `RaftKuiklyActivity`
- ... (约 100+ 个)

### 5.2 常量名

| 旧 | 新 |
|----|----|
| `SLOCK_API_BASE_URL` | `RAFT_API_BASE_URL` |
| `SLOCK_SOCKET_BASE_URL` | `RAFT_SOCKET_BASE_URL` |
| `PREFERENCES_NAME = "slock_auth_session"` | `"auth_session"` |
| `KEY_ALIAS = "slock_auth_session_key"` | `"auth_session_key"` |

### 5.3 XML 资源名

| 旧 | 新 |
|----|----|
| `slock_brand_yellow` | `raft_brand_yellow` |
| `slock_accent_pink` | `raft_accent_pink` |
| `slock_surface_cream` | `raft_surface_cream` |
| `Theme.Slock` | `Theme.Raft` |
| `Theme.Slock.Starting` | `Theme.Raft.Starting` |
| `slock_kuikly_container` | `raft_kuikly_container` |
| `slock_feedback` | `raft_feedback` |
| `slock_attachments` | `raft_attachments` |

---

## 6. SharedPreferences 迁移

旧文件名 `slock_auth_session.xml` → 新文件名 `auth_session.xml`。

**风险**：改名后老用户的本地登录状态（token、session）会丢失。

**建议方案**：
1. 保留读取旧 `slock_auth_session.xml` 的逻辑作为 fallback
2. 首次启动时检测旧文件存在 → 迁移到新文件名 → 删除旧文件
3. 或者更简单的方案：保持文件名不变（内部已经没有 "slock" 用户可见内容），只改代码中的常量名

---

## 7. 执行顺序建议

### Phase 1 — 域名 & API（P0 功能性，1 个 PR）
1. `SlockApiEndpoints.kt` — API base URL
2. `ThreadPage.kt` — web link host
3. `AndroidManifest.xml` — deep link host
4. `strings.xml` EN/ZH — `slock.ai` → `raft.build`

### Phase 2 — UI 文案（P0 用户可见，1 个 PR）
5. `strings.xml` 所有 "Slock" → "Raft"
6. 代码中硬编码 "Slock" UI 字符串
7. mock/preview 数据

### Phase 3 — 包名 + 文件名 + 标识符 + 资源（P1 工程改动，1 个大 PR）
8. 包名 `ai.slock` → `build.raft`（批量替换 + 目录移动）
9. 文件名重命名（130+ 文件）
10. 类名/变量名/常量名更新
11. XML 资源名更新
12. Gradle/Manifest/SQLDelight 配置更新
13. SharedPreferences 常量更新
14. OHOS 鸿蒙端同步

### Phase 4 — 验证
15. `./gradlew :app:assembleDebug`
16. `./gradlew :app:testDebugUnitTest`
17. 全局搜索 `grep -ri 'slock' --include='*.kt' --include='*.xml' --include='*.json' --include='*.toml' --include='*.gradle*' --include='*.properties' --include='*.md'` 确认无遗漏（排除 third_party/、build/、.gradle/、.git/、artifacts/）

### Phase 5 — 上游同步（如果需要）
18. 如果 `third_party/slock-source` 上游也改名了，更新 submodule remote URL
19. 如果没改，保持不动

---

## ⚠️ 关键风险提醒

1. **包名改动 326+ 文件**必须一个大 PR 一次性完成，不能部分改
2. **SQLDelight** `.sq` 文件改路径后所有生成代码类名变，DAO 引用要同步
3. **SharedPreferences** `slock_auth_session.xml` 改名后老用户登录状态可能丢失，建议做迁移
4. **OHOS 鸿蒙端**有独立的工程结构，需要单独处理
5. **applicationId** 从 `ai.slock.android` 改成 `build.raft.android` 后，Google Play / AppGallery 上的应用会被视为新应用（除非做 applicationId migration）
6. **deep link** `app.slock.ai` 改成 `app.raft.build` 后需要确保新域名已配置
