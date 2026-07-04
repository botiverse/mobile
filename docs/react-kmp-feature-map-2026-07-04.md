# React vs KMP 功能大图 Fresh Scan (2026-07-04)

基于 06-26 feature map 的 fresh scan，距上次扫描 ~885 commits。本文件只记录与 06-26 版本相比的关键状态变化和剩余缺口。完整 12-slice 细节见 `docs/react-kmp-feature-map-2026-06-26.md`。

日期：2026-07-04
主仓 main：~850+ commits ahead of upstream

## Scan 方法论

1. 逐频道扫描 task board（#对话流专修 #530+, #native #200+, #首页专修 #380+）
2. 对照 06-26 feature map 的 12 slice
3. 结合 CC-希乐 docs-inventory-2026-07-04 的 P0/P1/P2 结论
4. 标注：✅ done / 🔄 in_progress / ⚠️ pending

---

## Slice 1: Auth

- **Login/Register/Forgot**: ✅ 基本齐
- **Google OAuth**: ✅ #173 Android OAuth legal handling done, #174 social login zero-server error done
- **Notification deep link → Login**: ✅ #152, #157 已修复（identity routing + auth gate timing）
- **Package rename auth regression**: ✅ #175 Android Studio run config fix
- **Server switch landing page bug**: ✅ #177 已修复
- **横屏登录**: ✅ #154 density fix + auth scroll done

## Slice 2: Home

- **Pull-to-refresh/spinner**: ✅ #348 foreground refresh debounce fix
- **Home DM row**: ✅ #349 activity reply focus fix
- **Activity page onResume**: ✅ confirmed working
- **ConversationSummary decouple**: ✅ #520 Phase 0-5 completed by @KMP-专家
- **Tabbar native**: 🔄 #176 iOS liquid glass native tabbar (via Kuikly host bridge)

## Slice 3: Thread

- **Markdown regression**: ✅ #38 内联代码/粗体修复(#494); ⚠️ 整体 MD 渲染仍有 fastpath 问题待收尾
- **Reply double-owner routing**: ✅ #495, #349 修复（destination store 单 owner resolve）
- **Thread search**: ✅ #525 线程内搜索功能 done
- **Image gallery swipe**: ✅ #488 multi-image gallery with 1/x indicator
- **Image edit/annotate**: ⚠️ #497 @KMP-专家 spec 分析中（Kuikly 源码有编辑能力）
- **Bookmark icon config**: ⚠️ #498 hide bookmark by default
- **Thread scroll padding**: ✅ #496 bottom scroll spacer 72dp
- **Back-to-bottom white screen**: ✅ #540 fix
- **Thread entry speed regression**: ✅ #533 修复
- **At mention sort**: 🔄 #539 in_review
- **ConversationSummary decouple**: ✅ #520 done

## Slice 4: Composer

- **File+image upload queue bug**: ✅ #39 @Android-Developer-1 修复
- **Image-only send 400**: ⚠️ #41 pending（需要默认附文本）
- **Member modal click → detail**: ✅ #40 @Cindy 修复
- **Image edit/annotate**: ⚠️ #497 pending
- **Composer file preview "no preview"**: ✅ #155 remove for downloadable files

## Slice 5: Members

- **Pull-to-refresh**: ✅ #100 done
- **Member detail click**: ✅ #40 done
- **Human profile visual**: ✅ #95 done
- **Agent detail permissions tab**: ✅ #89 done
- **Tab reorder**: ✅ #99 done

## Slice 6: Settings

- **About "Open Raft" deep link crash**: ✅ #100 fixed
- **Diagnostics zip-only**: ✅ #151 done
- **Diagnostics multi-session export**: ✅ #156 done
- **Settings route 拆分**: ✅ #345-347 done
- **Billing webview**: ✅ #76 done

## Slice 7: Tasks

- **Task board SSOT**: ⚠️ #449 @KMP-专家 audit done, 待实现
- **Task click in thread**: ✅ #474 in_review（cross-channel task resolution spec）

## Slice 8: Native/Platform

- **iOS build**: ✅ #153 编译通过
- **iOS crash**: ✅ #162 修复
- **iOS font**: ✅ #163 applied
- **Package rename**: ✅ #164 build.raft.app
- **OHOS crash**: ✅ #1-4 鸿蒙适配修复
- **393dp landscape**: ✅ #154 density fix

## Slice 9: KMP/Shared

- **Auth refresh Ktor 化**: ✅ #347 完成, 删除 Android AuthTokenRefresher
- **ConversationSummary decouple**: ✅ #520 all phases done
- **MembersMembersPayload launch-contract**: ✅ #492 done
- **FilePreview launch-contract**: ✅ #493 done
- **Thread header fallback cleanup**: ✅ #264-271 done

## Slice 10: Visual Testing

- **Visual fixture gating**: ✅ #258 done
- **Overlay comparison views**: ✅ #254 done
- **Composer/File visual cases**: 🔄 多轮迭代中
- **Settings visual parity**: ✅ #71-99 基本全 done
- **Responsive spec**: ✅ #12 @Product-UX-Designer spec 完成

## Slice 11: Performance

- **Realtime batch apply**: ✅ #88-89 优化
- **Home refresh jank**: ✅ #343 home scroll optimization
- **Thread recomposition audit**: ✅ #297 done
- **Image cache基建**: ✅ #171 done

## Slice 12: Quiver (并行项目)

- **Release flow UX**: ✅ #27-35 全 done, deployed
- **Release type 简化**: ✅ 砍掉 release_type, 只保留 channel 单轴
- **Channel rename**: ✅ main/preview/nightly
- **OpenAPI playground**: ✅ #36 deployed
- **App deploy tokens**: ✅ #55 done
- **CORS config**: ✅ #57 done
- **Logout dropdown**: ✅ #59 done
- **Channels/Settings tabs**: ✅ #60

---

## P0 缺口（与 CC-希乐 inventory 对齐）

1. **通知链路**: #102/#103 native push (FCM/APNs/OHOS) - 未开工 ⚠️
2. **Status 单源 resolveMemberDisplayState**: 未建 ⚠️
3. **Store 收敛 Phase 2/3 + Thread realtime 单入口**: #387/#388 未闭环 ⚠️
4. **前台回收敛**: 06-29 spec 完全未实现 ⚠️
5. **IM 消息生命周期状态机**: 纯提案零代码 ⚠️

## P1 缺口

- Settings route 拆分 ✅ 已完成
- Activity per-item 动作矩阵 ⚠️
- File-preview 文本服务端契约 ⚠️
- Composer BasicTextField 迁移 ⚠️
- Reaction atlas 重落（被 revert 2 次）⚠️
- Members 细节 #91-93 ⚠️
- Search 多选 FROM #285 ⚠️
- Home cached-first 加载 ⚠️

## 未覆盖的新增功能文案

- Shiply SDK 接入（#158-159）: 腾讯远程配置 switch 服务，android 先行
- 云手机自动化测试: #云手机-Alibaba channel 在建
- 反馈系统: #raft-mobile-feedback channel 新建
- 折叠屏/宽屏响应式布局: #大屏适配 task #12 spec done, 待实现
