# Docs 盘点与剩余工作清单 — 2026-07-04

- 范围：`docs/` 全部 92 份文档，逐份判定状态，并从文档 + git log（06-26 以来 870 commits）+ 活跃 task board 交叉核对出剩余工作。
- 基准：main `97d86040`（2026-07-04）。
- 方式：状态结论均对照代码符号 / git log 验证过；个别未完全确证的项标 `(?)`。
- Owner：@CC-希乐（task #106，#设置项专修）。下次刷新建议随 feature-map 节奏（约每 3 天或大批量合入后）。

状态口径：**OPEN** 有明确未完成工作 / **CURRENT** 现行有效（契约、规范、runbook）/ **DONE** 所述工作已落地 / **STALE** 事实已漂移 / **SUPERSEDED** 被新文档或代码取代。

---

## 一、剩余工作总清单（按优先级合并去重）

### P0 — 正确性 / 产品缺口

1. ~~Notifications 链路（#102/#103）title/body 降级~~ **复核后撤销（2026-07-04）**：`SystemNotifications.kt` 已按 sender+preview 构造标题/正文（DM=sender、频道=#channel + "sender: preview"），task103 系列已合。真正未做的是下一条的服务端推送。
2. **Killed-app 原生推送（服务端侧）→ #native task #199**：三平台契约（FCM/APNs/OHOS，`mobile-killed-app-push-*-2026-07-01.md`）+ 汇总需求（`mobile-native-push-server-requirements-2026-07-02.md`）全部处于"等 server review"状态，未开工；客户端只落了 FCM SDK bootstrap。三层边界（target resolution / provider-neutral payload / provider delivery）是先决架构。
3. **Status resolution 单源审计（原 #73）→ #native task #198**：已核实 `resolveMemberDisplayState` 不存在、7 个文件各自推导 status 颜色；但"颜色/脉冲双源 bug 仍在"出自老审计、未复现验证——任务按审计式推进：先复现确认，不成立即关。
4. **Store 收敛 Phase 2/3（#386/#387/#388）+ Thread realtime 单入口 → #native task #196**：已核实 ThreadPage 路由层仍持有 raw realtime listener（`realtimeEvents.addListener` ~L2945），漏事件根因 #418 未闭环；`SharedRealtimeDomainEventOwner` 需扩 multi-sink。与 #native 在审的 #191/#192 同一主线。
5. **前台回收敛（`mobile-foreground-reconciliation-spec-2026-06-29.md`）→ #native task #197**：已核实相关符号完全不存在——后台返回后 standalone Thread 不刷新、状态陈旧、房间不强制 re-join。用户可感知的正确性问题。
6. **IM 主线集群（07-02/07-03 五份文档，全部是纯提案零代码）→ #native task #200（Phase 1 状态机）/ #201（Phase 2-4）**：`SharedMessageLifecycleStore`（基石）→ media/notification/presence 投影 → composer baseline 契约 → 跨端 fixture matrix。

### P1 — 体验 / 功能补齐

7. ~~Settings 路由化拆分 #345/#346/#347~~ **复核后撤销（2026-07-04）**：`SettingsStandaloneStore` / `StandaloneSettingsEnvironment` 已在 commonMain 落地。仅余老审计（`kmp-settings-*-audit`，自标 STALE）的 #38/#39 残留项待复核。
8. ~~Activity 动作矩阵~~ **复核后撤销（2026-07-04）**：MarkRead / MarkUnread / MarkDone / LoadMore 均已在 `SharedInboxPanel.kt` 接线（含 load-more retry 行）。竞态/角标刷新如仍可复现另行开任务。
9. ~~Thread/Home 首屏并行加载~~ **复核后撤销（2026-07-04）**：`withSenderProfiles` 已从代码中移除，spec 关注的阻塞点已被重构解决。
10. **File preview 文本/文档走服务端契约 → #native task #203**：mp4 已上（Android+OHOS），typed `AttachmentPreviewData` union + `/attachments/:id/preview` 迁移（worktree B/C/F）未做。
11. **Composer `BasicTextField` 迁移 → #native task #202**：~~整份 spec 未动，OHOS 选区/触发正确性依赖它。~~ 07-05 关闭：前提过时（fork 7ea6b09 起三端已有完整选区同步），spec 已删除。
12. **Reaction sprite atlas（#391/#397）→ #native task #204**：~~素材/目录已入库但设备端渲染被 revert 两次，需要稳定的 source-rect 渲染原语再落。~~ 07-05 完结：PR #28 asset-URL 修复后设备端渲染落地（artin 真机确认），PR #32 删除全部手绘 glyph，spec 已标 Implemented。
13. **Raft UI 组件对齐（活跃大集群）**：按视觉差异优先级 #283 surface/avatar 行 → #284 progress bar → #279 form/input → #278 menu/popover/select（部分已落）→ #277 segmented；对应 visual-testing board 上 #275-#321 一批在办；`slock-visual-testing validate` 缺 cli.mjs 待恢复。
14. **Members 细节**：外部 agent 显示态、agent-detail activity 实时 #93、create-agent runtime 模型加载 #92、profile 底部按钮 #91、`screens.members.human.profile` fixture+安卓采集。
15. **其他单点**：Search 多选 FROM #285（要服务端配合）；TaskBoard 多列视图；Saved 行右侧图标+菜单复核；Home cached-first visible 加载（缓存目前只在 Background 优先级读）。

### P2 / 平台与基建

16. **Fork 产物远程化（`fork-artifact-github-packages-publishing-spec.md`）→ #native task #205**：OHOS render 仍 `file:./libs/*.har`、SQLDelight 栈仍本地自动发布；CI 侧 mavenLocal 缓存（07-04 已由 CC-Wow2 落地）解决了速度，但可复现的远程发布 + `publish-fork-artifacts.yml` 仍未做。
17. **OHOS → #native task #206**：07-05 完结关闭。已落：HDS tabbar 官方材质（#53 HdsTabs systemMaterialEffect）、widget/forms、前台保活开关 #113。两个残留并入 task #207：composer 换行菜单 Phase 2（OHOS render fork 未接，prop 目前 OHOS no-op）、SQLDelight smoke DB 双驱动（现仅 androidUnitTest JVM 驱动有测试，native 驱动 smoke 未建）。
18. **性能/工具**：per-surface KuiklyProfiler 审计未跑（spec 已定）；~~perf marker 缺口（modal/cache/icon-batch）~~ 07-07 按 artin 决定不补反删（日志已过宽，#229 按不做关闭，tooling 文档已标 Retired）；#137 OHOS 设备侧证据、#139 代码审计；release lane 缺 `--sign`/zipalign、R8 关闭无 mapping。
19. **未建产品面（owner 决策）→ #native task #208**：Owner onboarding、Release Notes 入口、Announcements modal、Reminders 入口；Auth 补页（邮箱验证/重置密码/邀请接受/社交回调，MVP-optional）；WebView billing 契约（等 server 签认）。
20. **杂项 → #native task #207**：Quiver diagnostics follow-up（OHOS user-context、feedback/upload 拆分、Android 稳定 uniqueId）；Kuikly host 窗口/adapter 对齐；`ConversationSummary` Phase 3/4 收尾；markdown iOS/OHOS inline-code 对齐 + Mermaid/outline/copy-code；android realtime SSOT 清理尾巴。

### 活跃 task board 上、文档未覆盖的单点（2026-07-04 快照）

#539 thread at 排序、#541 界面交互（对话流）；#342 图标清晰度、#356 channel 拖拽排序、#370 shared Socket.IO pilot、#388 粗体字重（首页）；#43 thread draft 缓存+铅笔标识、#44 mention 候选 loading（输入框）；#48 预加载渲染为空（markdown）；#176 iOS native tabbar、#193 圆角来源、#194 头像缓存（native）；大屏 #12 响应式 spec。

---

## 二、92 份文档状态表

### OPEN — 有明确未完成工作（21）

| 文档 | 摘要/剩余 |
| --- | --- |
| composer-basictextfield-migration-spec | ~~整体未实施（OHOS 正确性依赖）~~ 07-05 已删除：前提过时——composer 实际走 BasicTextField(TextFieldValue)，fork 7ea6b09 起三端（含 OHOS C++）已有完整双向选区同步（上游 #1342），task #202 关闭 |
| fork-artifact-github-packages-publishing-spec | OHOS HAR/SQLDelight 远程发布 + workflow 未落 |
| im-composer-baseline-contract-2026-07-03 | send-operation 生命周期/typed 状态未建 |
| im-core-cross-platform-fixture-matrix-2026-07-03 | case id 与各层测试未建 |
| im-mainline-kmp-execution-plan-2026-07-03 | Phase 0-4 全未执行（索引文档） |
| im-media-notification-presence-contract-2026-07-03 | 三个投影类型均未建 |
| im-message-lifecycle-state-machine-2026-07-03 | `SharedMessageLifecycleStore` 未建（IM 集群基石） |
| im-shortcomings-kmp-analysis-2026-07-02 | 7 项优先级由上述文档承载 |
| kmp-settings-action-wiring-audit | #38/#39 slices 待复核后收尾 |
| kuikly-host-alignment-audit | ~~窗口处理/host error surface~~ 07-07 随 task #230 落地（沉浸式迁移+错误兜底页）；余 adapter 决策项 |
| kuikly-profiler-surface-audit-spec | per-surface 审计报告未产出 |
| kuikly-route-ui-layer-audit-matrix | #137 设备证据、#139 代码审计 |
| mobile-file-preview-mp4-text-spec-2026-06-24（+06-26-update） | 文本/文档服务端契约（worktree B/C/F） |
| mobile-foreground-reconciliation-spec-2026-06-29 | 全部未实现 |
| mobile-killed-app-push-{android,ios,ohos}-server-contract-2026-07-01 | 服务端三平台契约等 review/开工 |
| mobile-native-push-server-requirements-2026-07-02 | 三层推送边界（Phase 1 起步点） |
| mobile-webview-server-contract-2026-07-02 | 等 server/API owner 签认 |
| ohos-hds-tabbar-material-plan | ~~依赖 SDK 6.1/API 23~~ 07-05 已落（#53 HdsTabs systemMaterialEffect，文档已带状态注） |
| ohos-sqldelight-fork | shared smoke DB 未建（native 驱动侧；JVM 侧已有 SqlDelightComposeLocalDataStoreTest。随 #206 关闭并入 task #207 残留） |
| thread-realtime-ingress-next-step-2026-06-27 | Thread raw listener 未移除（multi-sink） |
| realtime-session-owner-boundary-spec | RealtimeBridgeManager 抽取、房间引用计数 |

### OPEN — 对齐审计类（gap 清单部分闭合，剩余项见第一节）（9）

react-kmp-{activity, auth, home, members, native, saved, search, settings, tasks, thread, visual-testing}-alignment-2026-06-26 中除 realtime 外均属此类；realtime-alignment 已自我更新为现行快照（CURRENT）。thread-home-api-parallel-loading-spec-2026-06-25 待复核 (?)。

### CURRENT — 现行有效的契约/规范/runbook（约 24）

android-emulator-setup、android-hands-release-practices-2026-07-02、android-realtime-code-path-2026-06-27（含 SSOT 清理尾巴）、android-release-lane-pi-perfprofiler（含 sign/R8 backlog）、commit-signoff-policy、common-sqldelight-schema-review、kmp-capability-parity-release-checklist、kmp-network-threading-model、kuikly-bridge-module-contract、kuikly-overlay-coordinate-contract、kuikly-route-realtime-performance-checklist、kuikly-performance-attribution-tooling（marker 缺口已按 #229 标 Retired）、mobile-device-automation、mobile-native-push-notification-analysis-2026-06-25、new-agent-landing、ohos-kmp-commonmain-compatibility、ohos-screenshot-capture、raft-ui-kmp-component-library-audit-2026-07-03、raft-ui-kmp-product-gap-plan-2026-07-03、raft-ui-kmp-visual-similarity-priority-2026-07-03、react-kmp-realtime-alignment-2026-06-26、realtime-event-contract-2026-06-23、shared-mvi-foundation、slock-app-state-first-principles、visual-testing-android-provider。

### DONE — 所述工作已落地（约 20）

agent-detail-permissions-tab-spec-2026-06-25、android-ui-shared-migration-audit（尾巴：icons bridge 待删）、composer-system-newline-menu-spec（OHOS Phase 2 除外）、conversation-summary-minimal-migration-spec-2026-07-03（Phase 3-5 尾巴）、diagnostics-deep-link-intent-spec-2026-06-26、ios-kmp-p0-host-bridge-spec-2026-06-27、killed-app-launcher-badge-spec-2026-06-24（Android v1；服务端另列）、kmp-shared-button-component-audit、kmp-thread-react-fetch-alignment-plan-2026-06-21、kuikly-pager-boundary-thread-spec、markdown-inline-token-registry-spec、markdown-richtext-inline-code-plan（iOS/OHOS 视觉对齐 deferred）、member-agent-app-logins-parity-spec-2026-06-24、mobile-message-seq-gap-sync-spec-2026-06-25（验证尾巴）、realtime-read-state-sync-spec-2026-06-23（客户端侧）、spec-android-app-merge-into-kmp、thread-action-card-confirm-execution-spec、thread-channel-kind-data-flow-2026-06-25（reducer 清理尾巴）、thread-channel-tasks-reuse-spec、thread-system-message-rendering-spec、visual-testing-build-gating-spec-2026-06-26。

conversation-summary-boundary-spec-2026-06-27 为部分完成（Phase 3/4 未做）。

### STALE / SUPERSEDED — 已过时或被取代（8）

| 文档 | 处置 |
| --- | --- |
| removed crash-provider capability audit | 07-08 #native task #228 删除；SDK 已移除，QuiverCrash + app diagnostics 在岗 |
| react-kmp-feature-map-2026-06-20 | 被 06-26 版取代（已加横幅） |
| react-kmp-feature-map-2026-06-26 | 仍是权威 gap 图，但 P0/P1 多项已落——已加 07-04 状态附录；建议尽快重跑 fresh scan |
| kmp-thread-capability-parity-audit | 文内自标 superseded（06-27） |
| kmp-home-capability-parity-audit | 自标历史快照；cached-first 项转入第一节 P1 |
| kmp-settings-capability-parity-audit | 自标历史快照；#38/#39 转入第一节 P1 |
| thread-first-open-performance-attribution | 调查快照，工件已失效；结论是否落地未验证 |
| realtime-single-source-runtime-spec-2026-06-23 | 自标历史；以 realtime-alignment + 代码为准 |

---

## 二点五、复核修正记录（2026-07-04 第二轮）

按 @artin 要求对第一节结论逐条二次核实，修正 4 条过时判断（已在上文用删除线标注）：通知 title/body 降级（已修）、Settings 路由拆分（已落地）、Activity 动作矩阵（已接线）、Thread/Home 阻塞加载（已重构解决）。核实后仍成立的项已在 #native 建任务跟踪（#196-#208，assignee @CC-希乐）；已有活跃 task 的项（Raft UI #275-#321、members #91-#93 等）未重复建任务。

## 三、本次维护动作

1. 新增本文档（92 份全量状态 + 剩余工作合并清单）。
2. `react-kmp-feature-map-2026-06-26.md` 顶部加"2026-07-04 状态附录"：P0 中 translation（#105, 7dead569）/ avatar #417 / realtime 漏事件 #406/#407/#414 已落地标记；仍开放项与本清单第一节对齐。
3. `react-kmp-feature-map-2026-06-20.md` 顶部加 superseded 横幅。
4. 未逐份改写其余 STALE 文档（它们多数已自标历史快照）；处置以本表为准。
