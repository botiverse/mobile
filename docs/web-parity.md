# Web → mobile feature parity (2026-10-18)

Owner: Lagoon (Mobile DRI) · Status: current snapshot · Procedure: [`docs/kb/web-capability-port-criteria.md`](kb/web-capability-port-criteria.md)

This document lists what the web client can do that the mobile apps (Android / iOS / HarmonyOS) cannot yet, rates each gap, and names the server work it depends on. It supersedes the area-by-area tables in `react-kmp-feature-map-2026-06-26.md` for planning purposes; that file stays as history.

## Base commits

Every file-level claim below was read at these commits. Re-check against a newer tree before acting on a row.

| Repo | Commit |
|---|---|
| `botiverse/mobile` | `7540fd973026a05c799b7e81c37b0f01979f0d11` (later commits up to the PR base touch thread read-state, avatar recovery, CI, docs and the HTML file preview's desktop mode, which moves `SlockFilePreview.kt:7401` to about `:7478`; none changes a row's status) |
| `botiverse/slock` (`staging`) | `eb1c0f2a4ad54f2cb81dd50168d5a8ae6dd669d2` |

Path conventions: mobile paths are relative to `compose/shared/src/commonMain/kotlin/build/raft/app/` unless they start with `compose/`; web paths are relative to `packages/web/src/`; server paths to `packages/server/src/`.

## How to read the status column

- **done**: same user-visible behaviour exists on mobile.
- **partial**: some of it exists; the cell says what is missing.
- **WebView**: the feature works, but only by embedding the web page (`SettingsStandaloneWebViewHost` surfaces). Usable, but it depends on the web page rendering well at phone width and it does not share native state.
- **missing**: no mobile code path found; the evidence column says what was searched.
- **n/a**: not on web either, or not meaningful on a phone.
- **TBC**: to be confirmed; the code was not conclusive.

Effort: **S** ≈ a day or two, **M** ≈ a week, **L** = multi-week or cross-team. Level (P0/P1/P2) follows the port-criteria doc, including the default-path and silent-failure modifiers.

This is a pre-candidate audit snapshot: it ranks gaps but does not yet carry the per-candidate owner / state / evidence columns of the criteria doc §8; those get filled when a gap is claimed. It is also a code-reading audit. Nothing was run on a device, so every "done" means "the code path exists", not "verified on hardware".

---

## 1. Suggested priority

### Do first

| # | Gap | Level | Why | Server | Effort |
|---|---|---|---|---|---|
| 1 | **Android and HarmonyOS push.** Android receives FCM messages but never sends its token to the server; HarmonyOS has no remote push at all. | P0 | Core loop: once the app is backgrounded and the socket drops, Android and HarmonyOS users get no notifications. Default path for most Android users. | **Needed.** `routes/push.ts` `parseProvider` accepts only `"apns"`; an FCM provider (and later Push Kit) plus a sender is required. | M–L (server) + S (Android client) + L (HarmonyOS) |
| 2 | **Invite / join links, password reset and device-login approval open in the browser.** | P1 | Joining a server from an invite is the main growth path; account recovery and CLI pairing are also link-driven. Today these bounce out of the app (Android `MainActivity` `shouldOpenUnsupportedSlockWebLinkExternally`; iOS universal-link handling TBC). | Present at slock staging `eb1c0f2a` (production TBC): `/auth/accept-invite`, `/auth/invite-info`, `/auth/reset-password`, `/auth/device/approve`. | M (invite) + S + S |
| 3 | **Search filters fail silently.** Picking two or more senders sends repeated `senderId`; the server only reads a single string (`routes/messages.ts:757`) and drops the filter without error (with no query and no other filter, the search returns nothing at all). Mobile also ignores the server's `nextCursor` / `incomplete`: a recent-sort text search that stops early tells the client to continue by cursor (web does), but mobile keeps paging by `offset`, so "load more" can duplicate or skip results. | P1 (silent failure) | Users can't tell the results are wrong. Web only allows one sender. | Present at staging `eb1c0f2a` (production TBC) if mobile limits to one sender and honours `nextCursor`; otherwise server needs array support. | S |
| 4 | **Hosted agents.** No way to create, pick a model for, monitor, retry or stop a Raft-hosted agent. | P1 | The quickest way to get a first agent (no computer needed); new mobile-only users can't do it at all. | Present at staging `eb1c0f2a` (production TBC): `/agents/:id/hosted-runtime*`, `/agent-runtime-providers/:kind`. | L |
| 5 | **System notification center and joint-channel invites.** Usage-limit incidents, computer/disk alerts, feedback replies and joint-channel invites are invisible on mobile, and a joint invite cannot be accepted there. | P1 | A blocked agent (usage limit) or a pending invite gives no signal on the phone. | Present at staging `eb1c0f2a` (production TBC): `/servers/:id/system-notifications-v2` (+ `/seen`, `/dismiss`), `/channels/joint-invites*`. | M + M |

### Quick wins (S, no server work)

- **Leave channel button.** `SharedThreadStore.leaveChannel` exists, nothing in the UI calls it.
- **Stop all agents in a channel (SOS).** `POST /channels/:id/stop-all-agents`. This is the emergency control, and the phone is where people see a runaway agent's notifications first.
- **Unarchive channels / list archived channels and agents.**
- **Change a member's channel role.**
- **Task history timeline.** `GET /tasks/:id/history`.
- **Wire the native Add Computer dialog.** `AddComputerDialog.kt` is built; only tests open it.
- **Missing settings tabs as WebView surfaces:** AI Providers, MCP Servers, IM Bridges, Labs (same pattern as Administration / Billing).
- **Regenerate the activity-v1 Kotlin bindings** (see §3.2). Nothing breaks today, but the activity sync cutover will.
- **Long-message collapse** should use the per-channel server setting, not a device switch.
- **Android launcher badge**, the **"unread + mentions"** Activity filter, **who-reacted** via `/reactions/viewer`, **forward-target search**, **unfollowed threads** list.

### Can wait

Agent migration UI (L), joint-channel create/convert/disconnect (M–L), theme/dark mode (M–L), code-block syntax highlighting (M), agent MCP tab and activity overview (M), Guest role (M), native Computers pages (M), public server page (M), RaftBench (L; manager dashboard, low value on a phone).

### Needs server work (for @Tenny)

"Present at staging" elsewhere in this doc means the route exists at slock `eb1c0f2a`; production deployment was not checked. Per the criteria doc §3, confirm production before porting against it.

| Gap | What the server needs |
|---|---|
| Android push | FCM provider in `routes/push.ts` + push outbox sender |
| HarmonyOS push | Push Kit provider + sender |
| Push preview mode (hide message text) | A `pushPreviewMode` field on notification settings. The mobile setting exists (`data/SettingsRepositoryDataSource.kt:1452`) behind `pushPreviewModeSupported`; no server field exists. |
| Multi-sender search (optional) | Accept repeated `senderId`, or mobile drops multi-select to match web |
| Activity sync cutover | A versioning rule for `contracts/activity-v1` so mobile can detect stale bindings |

---

## 2. Area tables

### 2.1 Channels, messages and threads

| Feature | Web | Mobile | Mobile evidence | Server | Effort |
|---|---|---|---|---|---|
| Channel / DM list, unread counts | `components/layout/Sidebar.tsx` | done | `data/ChannelRepositoryDataSource.kt` | `/channels`, `/channels/dm`, `/channels/unread` | – |
| Sidebar sections, sort, move | `layout/SidebarSectionDialog.tsx`, `SidebarSortMenu.tsx` | done | `data/SidebarSections.kt`, `SlockHomeComponents.kt` | `servers/:id/sidebar-order` | – |
| "Joined only" / "hide empty sections" toggles | i18n `layout.sidebar.*` | TBC | none found | client-only | S |
| Pin / mute channel | `channel/ChannelPinMenuItem.tsx` | done | `ChannelSettingsPage.kt` | `PATCH /channels/:id/notification-settings` | – |
| Mark read / unread / all read | `layout/SidebarReadToggleMenuItem.tsx` | done | `data/ReadReceiptRepositoryDataSource.kt` | existing | – |
| Create channel | `channel/CreateChannelDialog.tsx` | done | `SettingsHomePage.kt` `submitCreateChannel` | `POST /channels` | – |
| Join public channel | `message/ChatPanel.tsx` | done | `SharedThreadStore.kt` `joinChannel` | `POST /channels/:id/join` | – |
| **Leave channel** | `channel/EditChannelDialog.tsx` | partial: data layer only, no UI | `SharedThreadStore.kt:4249` | `POST /channels/:id/leave` | S |
| Rename / description / private | `channel/EditChannelDialog.tsx` | done | `ChannelSettingsPage.kt`, `ChannelSettingsStore.kt` | `PATCH /channels/:id` | – |
| Archive / delete | `channel/EditChannelDialog.tsx` | done | `data/ChannelRepositoryDataSource.kt` | existing | – |
| **Unarchive, archived list** | `settings/ArchivedChannelsSection.tsx` | missing | searched `unarchive` | `POST /channels/:id/unarchive` | S |
| Channel members list / add / remove | `channel/ChannelMemberList.tsx`, `AddMembersDialog.tsx` | done | `data/ChannelRepositoryDataSource.kt` | `/channels/:id/members*` | – |
| **Member channel role** | `hooks/useChannelMembers.ts:277` | missing | searched `/role` | `PATCH /channels/:id/members/:type/:id/role` | S |
| **Stop all agents (SOS)** | `message/SOSDialog.tsx` | missing | searched `stop-all` | `POST /channels/:id/stop-all-agents` | S |
| Start DM | `layout/sidebarDmContext.ts` | done | `data/ChannelRepositoryDataSource.kt` | `POST /channels/dm` | – |
| Send, markdown, tables, mermaid | `message/MessageInput.tsx`, `markdown/MarkdownContent.tsx` | done | `markdown/SlockMarkdown.kt`, `SlockMermaidCard.kt` | `POST /messages` | – |
| Code syntax highlighting | `markdown/ShikiHighlightedCode.tsx` | partial: copy button, no highlighting | `markdown/SlockMarkdown.kt` | client | M |
| Mentions, autocomplete, pending mention actions | `message/autocompleteTriggers.ts`, `PendingMentionActionStrip.tsx` | done | `ThreadComposerSuggestionSearch.kt`, `PendingMentionActionBatch.kt` | existing | – |
| Reactions | `message/ReactionGlyph.tsx` | done | `data/ThreadApi.kt:393` | existing | – |
| Who reacted | `store/reactionViewerReadModel.ts` | partial: names resolved on device | `SharedThreadStore.kt` `resolveThreadReactorNames` | `GET /messages/:id/reactions/viewer` unused | S |
| Permalink, quoted preview, copy / quote / select | `message/MessageItem.tsx` | done | `SlockMessagePermalink.kt` | `GET /messages/context/:id` | – |
| Multi-select, share as image | `SelectModeToolbar.tsx` | done | strings `thread_action_select_message` | – | – |
| Forward, forwarded bundle | `ForwardComposerDialog.tsx`, `ForwardedBundleCard.tsx` | done | `SlockForwardTargetPicker.kt`, `ThreadForwardBundleDetailStore.kt` | `POST /messages/forward` | – |
| Forward target search | `forwardComposerModel.tsx:206` | partial: local recent list only | – | `GET /messages/forward/targets/search` unused | S |
| Translation | `message/MessageItem.tsx` | done | `ThreadMessageTranslation.kt` | existing | – |
| Convert to task, done / reopen | `message/MessageItem.tsx` | done | `ThreadPage.kt` | `POST /tasks/convert-message` | – |
| Long-message collapse | `CollapsibleMessageContent.tsx` + per-channel setting | partial: device switch, ignores channel setting | `ThreadLongMessageCollapse.kt`, `ThreadPage.kt:7023` | `/channels/:id/message-display-settings` | S |
| Threads: open, reply, follow, agent followers | `message/ThreadPanel.tsx`, `thread/ThreadAgentFollowers.tsx` | done | `ThreadPage.kt`, `ThreadAgentFollowers.kt` | `/channels/threads/*` | – |
| Thread inbox, done, jump-to-unread | `thread/ThreadsInbox.tsx`, `ThreadUnreadJumpPill.tsx` | done | `SharedInboxPanel.kt` | `/channels/inbox*` | – |
| Unfollowed threads list | `store/inboxStore.ts:878` | partial | `data/SharedActivityStore.kt` (flag only) | `GET /channels/inbox/unfollowed` unused | S |
| Saved messages | `saved/SavedPanel.tsx` | done | `HomeSavedPage.kt` | `/channels/saved*` | – |
| Attachment upload | `message/MessageInput.tsx` | done | `DirectAttachmentUploadCoordinator.kt` | `/attachments/upload-sessions*` | – |
| Image / video / audio / document / HTML preview | `ImageLightbox.tsx`, `MediaPreviewHost.tsx`, `DocumentPreviewHost.tsx` | done | `SlockFilePreview.kt`, `ThreadInline*Preview.kt` | `/attachments/:id/*` | – |
| Diff preview with stats | `message/MessageItem.tsx` | TBC | `SlockFilePreview.kt:7401` only | – | M |
| Attachment comments, channel files | `AttachmentCommentsPanel.tsx`, `ChannelFilesPanel.tsx` | done | `ThreadFilesContent.kt` | existing | – |
| Drafts | `message/optimisticMessageDraft.ts` | done | `SharedThreadComposerDraftStore.kt` | client | – |
| Live agent activity bar | `layout/LiveAgentActivityBar.tsx` | partial: status label in header only | `ThreadPage.kt` `liveActivityLabel` | realtime | S |
| History-limit (plan) banner | `message/HistoryLimitBanner.tsx` | partial: flag read, no banner | `data/ThreadApi.kt:103` | existing | S |
| In-conversation search | `message/threadSearch.ts` | done | `thread_placeholder_search` | `GET /messages/search` | – |
| Action cards | – | done | `data/ThreadActionCards.kt` | existing | – |
| Joint channels: view, badge, billing lock | `layout/Sidebar.tsx` | done (view only) | `SlockHomeComponents.kt`, `ChannelSettingsPage.kt:100` | `/channels` | – |
| **Joint: accept invite** | `layout/usePendingJointInvites.ts` | missing | searched `joint-invite`, `jointInvite` | `/channels/joint-invites`, `/joint-invites/:id/accept` | M |
| Joint: create / invite / resend | `channel/CreateJointChannelDialog.tsx` | missing | same | `/channels/:id/joint-invites`, `/joint-invite/resend` | M |
| Joint: convert / disconnect | `channel/JointConversionSection.tsx` | missing | searched `convert-to-joint` | `/convert-to-joint`, `/disconnect`, `/conversion-jobs/*` | L |
| Edit / delete message, typing indicators, group DMs | – | n/a (not on web) | – | – | – |

### 2.2 Activity, notifications, search and sync

| Feature | Web | Mobile | Mobile evidence | Server | Effort |
|---|---|---|---|---|---|
| Activity / inbox list | `store/inboxStore.ts` | done | `data/InboxSavedSearchRepositoryDataSource.kt`, `SharedInboxPanel.kt` | `GET /channels/inbox` | – |
| Filters all / unread / mentions | `thread/ThreadsInbox.tsx:84-91` | partial: no combined "unread + mentions" | `data/SlockRepositories.kt:639` `InboxFilter` | existing | S |
| Per-item read / unread / done / follow; mark all read | `thread/InboxContextMenu.tsx` | done | `data/SharedActivityStore.kt`, `data/ReadReceiptRepositoryDataSource.kt:58` | existing | – |
| Pinned conversations first in Activity | `ThreadsInbox.tsx:644-663` | TBC | – | `sidebar-order` | S |
| Unread badges (sidebar, server) | `store/serverStore.ts:711` | done | `data/ProcessActivityUnreadStore.kt`, `ServerUnreadSummaryDto.kt` | `/servers/unread-summary` | – |
| Read state across devices | `store/readStateSync.ts` | done | `network/sync/ReadStateSyncDomain.kt`, `ReadStateV1Store.kt` | `read_state:updated` | – |
| Jump to first unread | `ThreadsInbox.tsx:681` | done | `data/ThreadReadAnchorStore.kt` | `messages/context/:id` | – |
| Push: iOS (APNs) | `routes/push.ts` (server) | done | `data/IosPushRegistration.kt`, `compose/iosApp/iosApp/iOSApp.swift` | `/push/registrations` | – |
| **Push: Android (FCM)** | RFC 045 | missing: token never registered | `compose/androidApp/.../push/SlockFirebaseMessagingService.kt` ("Server registration is intentionally left for the native push contract follow-up") | **server needs FCM provider** | M–L |
| **Push: HarmonyOS** | – | missing: local notifications only | `compose/ohosApp/.../KRBridgeModule.ets` (no Push Kit) | **server needs Push Kit provider** | L |
| Server decides what notifies (RFC 045) | `notification:push` | done: mobile trusts the server event | `SlockVisibleConversationRegistry.kt:67-131` | existing | – |
| Notification tap routing, in-app banners | – | done | `AndroidUnreadNotificationPoster.kt`, `SlockForegroundBannerHost.kt` | existing | – |
| Per-channel mute, server push mode | `SettingsPanel.tsx:1545` | done | `SettingsRepositoryDataSource.kt:279` | existing | – |
| Push preview mode | – (mobile-only) | unavailable | `data/SettingsRepositoryDataSource.kt:1452` | **server needs `pushPreviewMode`** | M |
| Launcher badge | `layout/mobileAppBadge.ts` | partial: iOS and HarmonyOS yes, Android no | iOS `HRBridgeModule.m:3698`, OHOS `KRBridgeModule.ets:1571` | `unread-summary` | S |
| **System notification center** | `layout/NotificationCenter.tsx`, `useSystemNotifications.tsx` | missing | searched `system-notifications`, `usageLimit` | `/servers/:id/system-notifications-v2` | M |
| Message search | `search/MessageSearchPage.tsx` | partial: ignores `nextCursor` / `incomplete` (keeps paging by offset) | `data/InboxSavedSearchRepositoryDataSource.kt:75-104` | `GET /messages/search` | S |
| **Search: multiple senders** | single sender only on web | **bug**: filter silently dropped | same file, line 99-103; server `routes/messages.ts:757` | see §1 | S |
| Search: channel, date, @me, humans/agents, sort | `MessageSearchPage.tsx:1105-1147` | done | `SharedHomeSearchSavedStore.kt` | existing | – |
| People / channel search, recent searches | `search/searchEntities.ts`, `searchHome.ts` | done | `data/SearchPersonalizationRepository.kt` | local | – |
| Realtime reconnect (`sync:resume`, gap sync) | server `sync:resume` | done (OHOS emit path TBC) | Android `SocketIoRealtimeClient.kt:454`, iOS `SlockIosRealtimeHost.swift`, `data/realtime/MessageSeqRecoveryPolicy.kt` | existing | – |
| sync-core domains (messages, read state, thread replies) | `packages/sync-core/src/` | partial | `network/sync/SyncCore.kt`, `MessageSyncDomain.kt`, `ThreadRepliesSyncDomain.kt` | – | M |
| Activity sync domain (snapshot / difference) | `store/activityPanel/runtime.ts` (gate off by default) | partial: code present, not wired | `network/sync/activity/ActivitySyncDomain.kt` | `/channels/activity/snapshot`, `/difference` | L |

### 2.3 Tasks, agents, computers and members

| Feature | Web | Mobile | Mobile evidence | Server | Effort |
|---|---|---|---|---|---|
| Task board / list, filters | `task/TasksPanel.tsx` | done (separate status filter TBC) | `SharedTasksPage.kt` | `/tasks/server`, `/tasks/channel/:id` | – |
| Create, claim, assign, status, detail | `task/CreateTaskDialog.tsx`, `TaskProperties.tsx` | done | `data/TasksDataSource.kt`, `SlockTasksDetailOverlay.kt` | existing | – |
| **Task history timeline** | `task/HistoryTimeline.tsx` | missing | searched `/history` | `GET /tasks/:id/history` | S |
| Linked tasks in threads | `message/MessageItem.tsx` | done | `ThreadLinkedTaskProjection.kt` | existing | – |
| Agent list, profile, avatar, permissions | `agent/AgentDetailPanel.tsx` | done | `MembersPage.kt`, `data/AgentsApi.kt` | existing | – |
| Activity log (raw) | `agent/AgentActivityLog.tsx` | done | `data/AgentsApi.kt` | existing | – |
| Activity overview (summaries) | `agent/AgentActivityOverview.tsx` | missing | – | `/agents/:id/activity-summaries` | M |
| Agent DMs, reminders, workspace files, skills, apps tab | `agent/*` | done | `MembersPage.kt` tabs, `data/AgentsApi.kt` | existing | – |
| Agent MCP tab | `agent/AgentMcpTab.tsx` | missing | searched `mcp` | `/mcp/servers*` | M |
| Runtime / model config, usage | `agent/RuntimeConfigFields.tsx`, `machine/RuntimeAccountUsageChip.tsx` | done (no manual usage refresh) | `MembersAgentRuntimeFormV2Dialog.kt`, `MembersAgentRuntimeUsage.kt` | existing | – |
| Start / stop / reset, move to computer | `agent/ResetAgentDialog.tsx` | done | `data/AgentsApi.kt`, `MemberRepositoryDataSource.kt:1007` | existing | – |
| Create agent on a computer; external agent | `agent/CreateAgentDialog.tsx`, `ExternalAgentToken.tsx` | done (external diagnostics unused) | `SharedCreateAgentDialog.kt` | existing | – |
| **Hosted agents** | `CreateAgentDialog.tsx:102`, `agent/HostedModelChoice.tsx`, `HostedRuntimeStatus.tsx` | missing | searched `hosted-runtime`, `HostedRuntime` | `/agents/:id/hosted-runtime*` | L |
| Agent migration | `agentMigration/*` | missing (status parsed, not shown) | `data/MemberAgentParser.kt:172` | `/agents/:id/migrate`, `/migration*` | L |
| Agent archive | `agentArchive/AgentArchiveDialog.tsx` | partial: delete only | `MembersPage.kt` | `/agents/:id/archive*` | M |
| Archived agents list | `settings/ArchivedAgentsSection.tsx` | missing | – | `GET /agents/archived` | S |
| Computers list / detail / upgrade / restart | `machine/MachineDetailPanel.tsx` | WebView | `SettingsComputersPage.kt` | existing | M to go native |
| Add computer | `machine/AddMachineDialog.tsx` | WebView; native dialog built but not wired | `AddComputerDialog.kt` | `POST /servers/:id/machines` | S |
| Invite humans, roles, remove member | `member/InviteHumanDialog.tsx`, `HumanDetailPanel.tsx` | done | `SharedInviteHumanDialog.kt`, `MembersPage.kt` | existing | – |
| Pending invites / join links | `settings/PendingInviteRow.tsx` | WebView | `SettingsAdministrationPage.kt` | existing | – |
| Guest role | `member/InviteHumanDialog.tsx:112-124` (flagged) | partial: public guest toggle only | `data/ServerPublicVisibilityApi.kt` | existing | M |

### 2.4 Sign-in, settings and administration

| Feature | Web | Mobile | Mobile evidence | Server | Effort |
|---|---|---|---|---|---|
| Email / Google / GitHub login (+ native Apple) | `auth/LoginPage.tsx` | done | `AuthPages.kt` | `/auth/login`, `/auth/mobile/oauth/*` | – |
| Register, legal, forgot password, profile setup | `auth/*` | done | `AuthPages.kt` | existing | – |
| Email verification | `auth/EmailVerificationPage.tsx` | partial: gate and resend; iOS link handling TBC | `SettingsHomePage.kt:4551` | existing | S |
| **Reset password link** | `auth/ResetPasswordPage.tsx` | missing: opens browser | searched `reset-password` | `/auth/reset-password` | S |
| **Invite accept, `/join/:token`** | `auth/InviteAcceptPage.tsx` | missing: opens browser | searched `accept-invite`, `invite-info` | `/auth/accept-invite`, `/auth/invite-info` | M |
| **Device / CLI login approval** | `pages/DeviceLoginPage.tsx` | missing: opens browser | searched `auth/device` | `/auth/device/approve` | S–M |
| Login-with-Raft setup, integration invite callback | `pages/HumanLoginSetupPage.tsx`, `IntegrationInvitePage.tsx` | missing | – | existing | M (low) |
| Public server page (logged out) | `pages/PublicServerPage.tsx` | missing | – | existing | M |
| Server select / create / join community | `auth/ServerSelector.tsx` | done | `AuthChooseServerPage.kt` | existing | – |
| Server onboarding wizard | `onboarding/*` | WebView | `ServerSetupAdmissionHost.kt` | existing | – |
| Account: name, avatar, password, link identity, logout | `settings/SettingsPanel.tsx` | done | `SettingsAccountPage.kt` | existing | – |
| Unlink identity | `DELETE /auth/identities/:provider` | missing | – | existing | S |
| Language and region | `LanguageRegionSection` | done | `SettingsNativeDetailPage` | existing | – |
| Appearance: theme, light / dark | `AppearanceThemePicker.tsx` | partial: message font size only | `data/DataSnapshots.kt:1808` | user prefs | M–L |
| Server rename, public visibility, leave / delete | `SettingsPanel.tsx` | done | `SettingsHomePage.kt`, `ServerPublicVisibilityStore.kt` | existing | – |
| Server icon upload | `POST /servers/:id/avatar` | missing | `updateServerProfile` sends name only | existing | S |
| Administration tab | `AdministrationTabContent` | WebView | `SettingsAdministrationPage.kt` | existing | – |
| Billing, usage, hosted spend | `PlanSection`, `HostedRuntimeSpendCard.tsx` | WebView (in-app purchase policy TBC) | `SettingsBillingPage.kt` | existing | – |
| Applications / marketplace | `IntegrationsSection` | WebView | `SettingsMarketplacePage.kt` | existing | – |
| **IM Bridges, AI Providers, MCP Servers, Labs** | `settings/IMBridgesSettingsSection.tsx`, `ProviderConnectionsSettings.tsx`, `McpSettingsSection`, `LabsSection` | missing | searched each name | existing | S each as WebView |
| Feedback, release notes | `feedback/*`, `ReleaseNotesPanel.tsx` | WebView (+ native screenshot report) | `SettingsFeedbackWebViewPage.kt`, `ReleaseNotesPage.kt` | existing | – |
| Announcements | `components/AnnouncementModal.tsx` | done | `AnnouncementModal.kt` | existing | – |
| RaftBench | `raftbench/RaftBenchPage.tsx` | missing | – | existing | L (low) |
| Member graph | `members/graph` | TBC | – | – | – |
| Download / community / dev / embed pages | `pages/*` | n/a | – | – | – |

---

## 3. Notes

### 3.1 Web routes used as the reference

Under `/s/:serverSlug/` (`components/layout/MainLayout.tsx`): `channel`, `dm`, `agent`, `computer`, `human`, `members`, `members/graph`, `computers`, `search`, `raftbench`, `settings/:tab`, `activity`, `tasks`, `saved`, `release-notes`. Settings tabs (`components/settings/settingsNavigation.ts`): account, language-region, appearance, notifications, server, billing, administration, im-bridges, integrations, labs, mcp, providers, about, feedback. Outside a server: auth pages, `/login/device`, `/login/app`, `/login-with-raft/setup`, integration invite, `/servers`, public `/s/:slug`.

### 3.2 Stale activity-v1 bindings

`network/sync/activity/contract/v1/ActivitySync.kt` does not match the generated `packages/sync-core/contracts/activity-v1/generated/bindings/ActivitySync.kt` at the slock base commit. The mobile copy is missing `latestActivitySeq`, the paging fields on `DifferenceIngress` (`nextCursor`, `hasMore`, `complete`, `totalCount`, `totalUnreadCount`; `ActivityWindow` already has them) and the `ExternalProjection` kind, and still uses `expectedRowVersion` where the contract now has `throughActivitySeq`. Nothing calls it in production yet (web also keeps the activity gate off by default), so this is a cutover blocker, not a live bug.

### 3.3 Dead code found

`data/SystemNotifications.kt:179` `buildSystemUnreadNotificationRequest` (builds notifications locally from `message:new`) has no production caller. Mobile already relies on the server's `notification:push`, which is the intended model.

### 3.4 What this audit did not cover

- No device runs. Visual parity and phone-width layout of the WebView surfaces were not checked.
- iOS and HarmonyOS deep-link handling for the link-driven flows in §1 #2 was not fully traced.
- Rows marked TBC need a follow-up read or a device check before they are filed as candidates.
