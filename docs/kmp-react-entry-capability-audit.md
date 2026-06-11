# KMP React Entry Capability Audit

Date: 2026-05-25

This audit maps visible React entry points to the current KMP shared Android/OHOS shell. It is meant to prevent piecemeal discoveries such as "Home plus is not wired" by tracking each entry from UI trigger to route, repository/API, host capability, and current gap.

React sources inspected:

- `packages/web/src/App.tsx`
- `packages/web/src/components/layout/MainLayout.tsx`
- `packages/web/src/components/layout/Sidebar.tsx`
- `packages/web/src/components/auth/LoginPage.tsx`
- `packages/web/src/components/channel/CreateChannelDialog.tsx`
- `packages/web/src/components/message/ChatPanel.tsx`
- `packages/web/src/components/task/TasksPanel.tsx`
- `packages/web/src/components/saved/SavedPanel.tsx`
- `packages/web/src/components/thread/ThreadsInbox.tsx`
- `packages/web/src/components/settings/SettingsPanel.tsx`
- `packages/web/src/components/machine/AddMachineDialog.tsx`
- `packages/web/src/components/machine/MachineDetailPanel.tsx`
- `packages/web/src/components/agent/AgentDetailPanel.tsx`
- `packages/web/src/components/agent/AgentWorkspace.tsx`
- `packages/web/src/components/agent/AgentScopesPanel.tsx`
- `packages/web/src/components/layout/useSystemNotifications.tsx`

KMP sources inspected:

- `compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsHomePage.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/SlockHomeComponents.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/AuthPages.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/SharedInboxPanel.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SharedHomeStore.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SlockRepositories.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ChannelRepositoryDataSource.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/InboxSavedSearchRepositoryDataSource.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsComputersPage.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/MembersPage.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SettingsRepositoryDataSource.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/MemberRepositoryDataSource.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/AgentsApi.kt`

## Status Legend

- OK: entry is wired through shared state and repository/capability.
- PARTIAL: entry exists but only some behavior is wired.
- MISSING: visible React capability has no usable KMP entry or route.
- VERIFY: code exists but needs Android/OHOS device validation.

Gap type:

- Entry: visible trigger or route is absent/inert.
- API/Bridge: UI exists but repository/API/host capability is missing or not connected.
- Experience: behavior is wired but loading, cached state, optimistic update, validation, rollback, or device flow is incomplete.
- Visual: behavior exists but visual parity needs validation or correction.
- None: no current gap found in this audit.

## Executive Summary

The KMP shell now has the main app frame, email login, server gate, Home list, Thread route, Tasks, Members list/detail work in progress, partial Settings, notifications, realtime bridge, and several Thread interaction fixes. The largest remaining gaps are not mostly visual. They are entry-to-capability gaps:

- Auth: Google/GitHub login, create account, forgot/reset password, invite acceptance, email verification, and social callback are still missing or disabled in the KMP app shell.
- Home: channel plus is visible but not connected to create-channel flow; create server is toasted as unavailable inside the shared Home shell; Search and Saved route to placeholders instead of real panels.
- Sidebar/admin actions: React has create channel, invite human, create agent, context menus, pin/reorder, mark read/unread, archive/delete, and agent controls. KMP Home mostly renders list navigation and sort, not these actions.
- Thread: recent foot meta, loading, attachment upload, and fallback avatar work is in review or active, but React parity still needs a full Thread entry sweep: header members count, files/task panels, image upload on OHOS, message row foot meta, fallback avatars, and lazy-list bounds.
- Members: member detail route is active work, but Members list/count parity still has known gaps.
- Settings/Computers/Agents: About/Diagnostics and some notification/computer read-only flows are wired, but Account profile/password/social identities, Billing, Server admin/invites/agreement, Add/Configure Computer, offline/outdated computer notifications, machine runtime/actions, Agent detail tabs/actions, Storage, language/appearance, and danger actions are mostly partial/static.

## Entry Matrix

| Entry group | React behavior | Current KMP state | Status | Gap type | Priority | Suggested owner |
| --- | --- | --- | --- | --- | --- | --- |
| Auth: email login | `LoginPage` email/password, auth restore, server redirect | `AppShellAuthGateContent` calls `SharedAuthDataSource.login` and proceeds to server gate | OK | None | P0 | Android-App-Integrator |
| Auth: Google/GitHub | `LoginPage` uses auth providers and starts provider redirect; `SocialAuthCallbackPage` completes callback | KMP `LoginContent` renders Google/GitHub buttons disabled; no callback route in app shell | MISSING | Entry/API/Bridge | P0 | Android-App-Integrator + API owner |
| Auth: create account | `RegisterPage` supports registration route | App shell register mode shows "Create account will move..." and no submit | MISSING | Entry/API/Bridge | P0 | Android-App-Integrator |
| Auth: forgot/reset password | `ForgotPasswordPage` and `ResetPasswordPage` route from URL token | App shell forgot mode shows "Password reset will move..." and no submit; no reset token route | MISSING | Entry/API/Bridge | P0 | Android-App-Integrator |
| Auth: invite/email verification | `InviteAcceptPage`, `EmailVerificationPage`, pending invite accept | No equivalent app shell route found | MISSING | Entry/API/Bridge | P1 | Android-App-Integrator |
| Server gate | `ServerSelector` selects and creates servers; URL redirect to last server | KMP server gate can select/create server before app shell ready | PARTIAL | Experience | P0 | KMP |
| Home server switch | Sidebar server switch/menu with unread/dock styling | KMP Home server switch works; create server inside Home toasts unavailable | PARTIAL | Entry/API/Bridge | P0 | KMP |
| Home channel plus | `Sidebar` opens `CreateChannelDialog` | `SlockHomeComponents` has plus; shared app shell does not pass create callback, and bridge fallback only emits `settingsEvent("createChannel")` | MISSING | Entry/API/Bridge | P0 | Android-App-Integrator, task #153 |
| Home Search | React route `/search` opens `MessageSearchPage` | KMP selects Search but routes to `SharedHomeDestinationPlaceholder` | MISSING | Entry | P0 | Android-App-Integrator |
| Home Saved | React route `/saved` opens `SavedPanel` with saved repository | KMP selects Saved but routes to placeholder; repository exists but panel not wired here | MISSING | Entry | P0 | Android-App-Integrator |
| Home Activity/Inbox | React has notification trigger and `ThreadsInbox` route | KMP has `SharedHomeActivityScreen`; breadth of React inbox/thread states needs validation | PARTIAL | Experience | P1 | Android-App-Integrator |
| Home notifications | React notification trigger/popover | Shared notifications and dismiss/view bridge are wired | OK | None | P1 | Android-Developer-1 |
| Sidebar sort menu | React Manual/Recent/A-Z menu | KMP shared base menu implemented | OK | None | P1 | Android-Developer-2 |
| Sidebar context menus | React channel/DM/member/agent/server context menus | No full KMP context menu/action parity found | MISSING | Entry/API/Bridge | P1 | Android-Developer-2 |
| Create agent/machine/invite human | React opens `CreateAgentDialog`, `InviteHumanDialog`, machine flows | No KMP Home entry found from sidebar shell | MISSING | Entry/API/Bridge | P1 | KMP + Android-App-Integrator |
| Thread open route | React `ChatPanel` thread/channel/DM route | KMP opens shared Thread route from Home/Tasks and now shows loading before data replacement | OK | None | P0 | Android-Developer-2 |
| Thread message foot meta | React message item shows replies/new/assignee/task/reaction metadata | Recent KMP #215 commit restored bounds; needs device visual validation | VERIFY | Visual/Experience | P0 | Android-Developer-2 |
| Thread lazy/bottom bounds | React list does not allow endless bottom overdrag | KMP #212/#215 work addresses bounds; user still reported related behavior, keep device validation open | VERIFY | Experience | P0 | Android-Developer-2/4 |
| Thread attachment upload | React image/file upload path works | Android path exists; OHOS bridge #198 in review, still needs device picker/upload/preview/send validation | VERIFY | API/Bridge/Experience | P0 | Android-Developer-1 |
| Thread header members count | React shows actual member count | KMP right-top count reported stuck at 0 | MISSING | API/Bridge | P0 | Members owner, task #27 |
| Thread avatar fallback | React avatars fall back to user/agent initials/art | KMP user fallback avatar reported missing in Thread | MISSING | Visual/Entry | P0 | Android-Developer-2 |
| Thread tasks panel | React channel `ChatPanel` reuses `TasksPanel(channelId)` | KMP reuses shared tasks panel in Thread with channel filter hidden | OK | None | P1 | Android-App-Integrator |
| Tasks global panel | React `TasksPanel` with channel filter, board/list | KMP `SharedTasksPanel` aligned with React and API-backed behavior | OK | None | P1 | Android-App-Integrator |
| Members list | React includes humans, agents, detail panels, graph route | KMP Members route exists; agent list/count parity still known gaps | PARTIAL | Entry/API/Bridge | P0 | KMP, tasks #26/#98 |
| Member detail | React `HumanDetailPanel`/`AgentDetailPanel` | KMP members detail route recently added; Android/OHOS entry validation still needed | VERIFY | Experience | P0 | KMP |
| Settings About/Diagnostics | React Settings has About/Diagnostics actions | KMP About/Diagnostics actions wired through shared effects/capabilities | OK | None | P1 | Android-Settings-Integrator |
| Settings Account/Profile | React supports display name, avatar, password, linked identities/social link/unlink | KMP Account uses payload and logout; profile/password/social identity actions are not fully wired | PARTIAL | API/Bridge | P0 | Android-Settings-Integrator, #35-#39 |
| Settings Billing | React Billing panel uses real account/subscription flows | KMP billing payload is mostly static/fallback | PARTIAL | API/Bridge | P1 | Android-Settings-Integrator |
| Settings Server/Workspace | React server settings covers admins, invites, agreement, workspace/admin actions | KMP server settings partially loads members; many mutations/actions are generic or missing | PARTIAL | API/Bridge | P0 | Android-Settings-Integrator |
| Settings Mobile/Notifications | React mobile/push notification settings | KMP notification preference/permission/settings/test notification wired | OK | None | P1 | Android-Settings-Integrator/KMP |
| Settings Storage | React storage management | KMP storage payload is static/fallback | PARTIAL | API/Bridge | P2 | Android-Settings-Integrator |
| Settings Computers list | React Settings/LeftRail Computers show machines with online/offline, hostname, OS, daemon version, last heartbeat, latest daemon version, quotas | KMP settings repository parses `/servers/:id/machines` and renders status/list; `canAddComputer=false`, no add/configure entry, limited last-seen/version behavior | PARTIAL | Entry/API/Bridge | P0 | Android-Settings-Integrator + KMP |
| Add/Configure Computer | React `AddMachineDialog` registers machine, shows one-time API key/run command, polls connection, lets user rename on connected, handles quota and cleanup on cancel | KMP has no shared Add Computer dialog/route; Computers payload explicitly disables add computer | MISSING | Entry/API/Bridge | P0 | KMP + Android-App-Integrator |
| Computer offline/outdated notification | React `useSystemNotifications` surfaces offline machines and outdated daemon warnings, escalates offline machine with active agents to error, action opens machine detail | KMP notification center handles server announcements; no machine-offline/outdated computed notification parity found | MISSING | Experience/API/Bridge | P0 | KMP |
| Computer detail read-only info | React `MachineDetailPanel` shows name, connected/offline, hostname, OS, daemon version/update, detected runtimes, created date | KMP `SettingsComputerDetailSnapshot` includes computer info, workspaces, agents, and placeholder sections; basic read-only detail exists | PARTIAL | Experience/Visual | P1 | Android-Settings-Integrator |
| Computer detail configure/rename/delete/key | React can rename computer, delete computer, rotate API key, copy/generate connect command when offline, permission-gated by `manageMachines` | KMP detail does not expose rename/delete/rotate key/connect command actions; machine logs/heartbeat endpoint is explicitly unconfirmed | MISSING | Entry/API/Bridge | P0 | Android-Settings-Integrator + API owner |
| Computer workspaces | React scans machine workspaces on demand, shows active/stopped/deleted/orphan, size/file count/modified, supports workspace deletion with confirmation | KMP loads `machines/:id/workspaces` and renders workspace rows, but parser only keeps directory/status/lastModified; no scan trigger, size/file count, orphan/deleted detail, or delete action | PARTIAL | API/Bridge/Experience | P1 | Android-Settings-Integrator |
| Computer agents/actions | React shows agents on a machine, creates agent for that machine, multi-selects agents, start/stop/restart/session reset/full reset with offline disabling | KMP detail loads agents assigned to computer and can emit `openAgent`; no create-agent-on-machine or bulk start/stop/reset actions in UI | PARTIAL | Entry/API/Bridge | P0 | KMP + Android-Settings-Integrator |
| Create agent | React `CreateAgentDialog` selects online computer, runtime, model, reasoning, env vars, and validates plan/runtime support | KMP Home/Computer surfaces do not expose create-agent flow; typed data models exist but no shared dialog/action route found | MISSING | Entry/API/Bridge | P0 | KMP + Android-App-Integrator |
| Edit agent profile | React Agent detail edits avatar/name/description and profile fields | KMP member summary displays profile data; no full shared edit profile flow found | MISSING | Entry/API/Bridge | P0 | KMP |
| Agent detail profile | React `AgentDetailPanel` profile tab shows avatar/name/description/runtime/computer/model/status and editable profile fields | KMP Members detail shows member summary and badges; shared `AgentDetailSnapshot` does not drive full React profile editor in current app shell | PARTIAL | Entry/API/Bridge | P0 | KMP |
| Agent runtime configuration | React edits runtime/model/reasoning effort, rescans runtime models, handles runtime migration/restart confirmation, disables unsupported/offline machine states | KMP has typed `AgentsApi` commands and some skills/workspace data, but no shared runtime config editor or migration confirmation UI found | MISSING | Entry/API/Bridge | P0 | KMP + API owner |
| Agent environment variables | React has editable environment variables in agent profile/create flow | No KMP agent environment editor found | MISSING | Entry/API/Bridge | P1 | KMP |
| Agent lifecycle operations | React Agent detail and Machine detail support start, stop, restart, reset session, full reset, delete/disable with pending/success/failure/noPermission/offline states | KMP `AgentsApi` has typed start/stop/reset endpoints, but current shared detail UI does not expose full actions; delete/disable not found in shared agent detail path | PARTIAL | Entry/API/Bridge | P0 | KMP |
| Agent status matrix | React differentiates online, offline, starting, stopping, restarting, error, updating/outdated daemon, no computer assigned, and disables buttons accordingly | KMP member summary has basic presence/agent status; full status/action matrix and realtime error banners are not exposed in shared detail | PARTIAL | Experience/API/Bridge | P0 | KMP |
| Agent computer assignment | React create/detail ties agent to machine, blocks actions when machine offline, and Machine detail groups agents by computer | KMP parses computer labels and shows badges; no assign/unassign/change computer action found | MISSING | Entry/API/Bridge | P1 | KMP |
| Agent permissions/scopes | React `AgentScopesPanel` GET/PUT `/agents/:id/scopes`, default reset, scope groups | No KMP permissions/scopes tab found in shared agent detail UI | MISSING | Entry/API/Bridge | P1 | KMP |
| Agent DMs/reminders/activity | React Agent detail tabs load agent-to-agent DMs, reminders, activity log with empty/loading/error states | KMP `MemberRepositoryDataSource` can fetch agent DMs/reminders/activity into `AgentDetailSnapshot`, but current visible detail route does not expose React-style tabs | PARTIAL | Entry/Experience | P1 | KMP |
| Agent workspace files | React `AgentWorkspace` browses workspace tree, reads files, refreshes, copies path, supports markdown/raw preview | KMP has workspace file parsers and repository methods; no full shared file tree/preview surface found in current app shell | PARTIAL | Entry/API/Bridge | P1 | KMP |
| Agent navigation entry unification | React routes Agent detail from Members, Computer detail, Thread avatar/member clicks, DMs, Settings/Computers, and URLs into one panel | KMP has multiple partial entry points (`openAgent`, `openMember`, Thread member modal); needs one shared Agent detail route contract across Android/OHOS | PARTIAL | Entry/Experience | P0 | KMP + Android-App-Integrator |
| Agent skills | React `AgentSkills` shows global and workspace skills grouped by path | KMP parses agent skills into `AgentSkillsSnapshot`, but visible detail parity needs route validation | VERIFY | Experience/Visual | P2 | KMP |
| Agent diagnostics/report issue | React builds diagnostic bundle, report issue dialog, activity/log surfaces | KMP Settings Diagnostics exists, but agent-specific report issue/log bundle parity not found | MISSING | Entry/API/Bridge | P2 | KMP |
| Release notes/profile/legacy task overlays | React has lazy panels and query-synced right-panel stack | KMP app shell does not expose full overlay stack parity | MISSING | Entry | P2 | Android-App-Integrator |
| Realtime | React socket event handlers update channels/members/server/unread/saved | Android raw `eventName + rawPayload` bridge and OHOS expanded event allowlist are now aligned for the main event set, including `server:plan-updated`; remaining gaps are device proof, OHOS token-refresh handoff, and fine-grained mutation parity. | PARTIAL / VERIFY | API/Bridge/Experience | P0 | KMP |

## Detailed Findings

### Auth and Server Entry

React `App.tsx` imports and routes `LoginPage`, `RegisterPage`, `ForgotPasswordPage`, `ResetPasswordPage`, `EmailVerificationPage`, `InviteAcceptPage`, and `SocialAuthCallbackPage`. It also has URL handling for social callback, reset, invite/join, and verification tokens before entering `MainLayout`.

KMP app shell only executes email/password login through `SharedAuthDataSource.login`. Register and forgot modes render UI but intentionally show placeholder error messages and no submit action. The shared auth components include disabled Google/GitHub buttons, so the visible login page suggests social auth exists while the app shell cannot start or finish the provider flow.

Recommended split:

- P0: wire create account and forgot/reset through `SharedAuthDataSource` with real loading/error/success states.
- P0: add social provider start and callback handling for Google/GitHub, including Android/OHOS host browser return.
- P1: add invite accept and email verification route handling in the shared app shell.

### Home and Sidebar

React `Sidebar.tsx` is not just navigation. It owns create channel, invite human, create agent, notification trigger, server switcher, sort menu, channel/DM/server/agent context menus, and route navigation to activity, threads inbox, saved, tasks, search, and settings.

KMP `SlockHomeComponents.kt` renders many of the visible controls, but the app shell only wires a subset. The plus button is visible and has a callback in the component contract, yet `SharedHomeScreen` does not pass a real `onCreateChannelClick` into `SlockStructuralHomeShell`. The fallback bridge path emits `settingsEvent("createChannel")`, which is not a create-channel flow. The repository layer already has `ChannelRepository.createChannel`, so this is an entry/wiring gap, not a missing API model.

Search and Saved are also visible as Home top actions. In KMP they update selected state and navigate to `SharedHomeDestinationPlaceholder`, so the user sees an entry but not the React feature. Activity is better wired through `SharedHomeActivityScreen`, but should still be compared against `ThreadsInbox` and notification behavior.

Recommended split:

- P0: task #153 canonical for Home channel plus: modal/form, validation, repository call, optimistic insert/select, failure rollback/toast, Android/OHOS parity.
- P0: replace Search and Saved placeholders with real shared panels using existing `InboxSavedSearchRepositoryDataSource`.
- P1: sidebar context menu parity in batches: channel/DM read state and pin/reorder first; archive/delete/admin actions later.
- P1: create agent, invite human, create machine entries.

### Thread and Conversation Flow

React `ChatPanel.tsx` hosts messages, channel-scoped Tasks, attachment/image handling, thread/detail panels, and message metadata/actions. Recent KMP work improved several pieces:

- Thread route loading before slow data replacement.
- Channel-scoped Tasks panel reuse.
- Markdown/reference clicks.
- Sort menu.
- Lazy-list bottom bounds.
- Footer metadata bounds.
- OHOS attachment upload bridge.

Remaining high-risk gaps are still user-visible: member count at the top reported as always 0, user fallback avatar in Thread reported missing, OHOS image upload still needs device picker/upload/preview/send validation, and list bottom behavior needs device validation after the latest commits. These should remain separate from visual-only parity.

Recommended split:

- P0: members count source and payload mapping for Thread header (task #27).
- P0: user fallback avatar in Thread message/member surfaces.
- P0: OHOS attachment upload end-to-end validation with the `ThreadAttachmentUpload` diagnostic stages.
- P1: full React message action/menu parity after foot meta and fallback are stable.

### Tasks

React `TasksPanel.tsx` supports global mode and channel-scoped mode. KMP now reuses the same shared panel for global Tasks and Thread channel Tasks, with `showChannelFilter=false` in Thread. This is one of the cleaner parity points.

Keep future work focused on behavior differences, not duplicate panel implementations.

### Members

React has separate human and agent detail panels plus graph route. KMP now has a Members route and member detail work, but known reports remain:

- Members list should include agents as well as humans.
- Thread top-right member count is stuck at 0.
- Detail entry must work from Members list and Thread avatar/member surfaces on Android and OHOS.

Recommended split:

- P0: task #26 for Members list human/agent parity.
- P0: task #27 for Thread member count.
- P0: task #98 for member detail route validation and Android/OHOS bridge coverage.

### Settings

React `SettingsPanel.tsx` is broad: Account/Profile, linked identities/social accounts, password, Billing, Server/Workspace admin, Mobile/push, Storage, language/translation, appearance/message body settings, diagnostics, admins/invites/agreement, and danger actions.

KMP Settings has a shared shell and several real pieces, especially About/Diagnostics and Mobile notification preferences. The earlier settings action audit should remain the canonical Settings tracker. Biggest remaining gaps are Account profile/password/social identity actions, Billing real flows, Server/admin/invites/agreement mutations, Storage real data/actions, and language/appearance behavior.

Recommended split:

- Continue using Settings tasks #35-#39 from the existing audit.
- Avoid adding product-visible rows unless the row either calls a shared intent/effect/repository/capability or is explicitly disabled with a clear reason.

### Computers, Runtime, and Device Management

React treats Computers as a first-class device management surface, not just a Settings row. `AddMachineDialog.tsx` registers a computer, displays the one-time daemon command/API key, polls until the machine is online, then lets the user rename it. `MachineDetailPanel.tsx` covers online/offline status, daemon version/outdated warning, detected runtimes, rename, delete, rotate API key, connect command for offline machines, agents assigned to the computer, bulk agent start/stop/reset, create-agent-on-this-machine, and workspace scan/delete.

KMP Settings has a real read path for computers:

- `SettingsRepositoryDataSource.getComputers()` reads `/servers/:id/machines`.
- `getComputerDetail()` loads the selected computer, `/machines/:id/workspaces`, and agents assigned to the machine.
- `SettingsComputersPage.kt` renders computer list/detail and can emit `openAgent`.

The gaps are mainly action and state parity:

- Add/Configure Computer is disabled in KMP (`canAddComputer=false`) even though React has the full register/connect/rename flow.
- Offline computer detail cannot generate/copy a connect command or rotate API key.
- Rename/delete computer actions are not wired.
- Workspace scan/delete is not wired; KMP keeps only directory/status/lastModified, while React shows size, file count, orphan/deleted status, and delete confirmation.
- Machine logs/heartbeat history is explicitly described in KMP as not confirmed.
- Machine offline/outdated notifications are computed in React by `useSystemNotifications.tsx`; KMP notification center does not appear to synthesize these local machine warnings.

Recommended split:

- P0: Add/Configure Computer shared flow with Android/OHOS clipboard/browser-safe command display and quota/error handling.
- P0: Computer detail admin actions: rename, delete, rotate key, offline connect command.
- P0: Machine offline/outdated notifications in KMP Home notification center, including error severity when active agents are on an offline machine.
- P1: workspace scan/delete detail parity.
- P1: agents-on-computer bulk actions and create-agent-on-this-machine.

### Agent Detail

React `AgentDetailPanel.tsx` is a large surface. It is not only a member profile:

- Profile tab: avatar/name/description, runtime/computer/model/reasoning details, editable environment variables, edit runtime configuration, and status/error banners.
- Create/edit: create agent selects online computer, runtime, model, reasoning, env vars, and plan/runtime support; edit profile covers avatar/name/description plus runtime config.
- Lifecycle operations: start, stop, restart, reset session, full reset, delete/disable, with pending/success/failure/noPermission/offline machine states.
- Status matrix: online, offline, starting, stopping, restarting, error, updating/outdated daemon, no computer assigned, and button availability tied to those states.
- Permissions tab: `AgentScopesPanel` GET/PUT `/agents/:id/scopes`, including default reset.
- Agent DMs tab: `/agents/:id/agent-dms`.
- Reminders tab.
- Workspace/files tab: `AgentWorkspace` tree, file read/preview, refresh, copy path, empty/error/permission states.
- Activity tab: activity log, current running session/task, realtime updates, runtime error/log link.
- Skills/integrations-related panels and private-surface visibility rules.
- Navigation entries from Members, Computer detail, Thread avatar/member click, DMs, Settings/Computers, and URLs all converge on the same Agent detail panel.

KMP has pieces of the data layer:

- `MemberRepositoryDataSource.getAgentDetail()` aggregates direct messages, reminders, activity, and skills into `AgentDetailSnapshot`.
- Workspace files and skills parsers/repositories exist.
- `AgentsApi` includes typed start/stop/reset commands.
- `MembersPage.kt` renders a shared member summary and grouped agents/humans.

The visible KMP app shell does not yet expose a React-equivalent agent detail panel. Treat this as a capability gap until the shared route proves these tabs/actions are reachable on both Android and OHOS. @artin's hunch is likely correct: Agent detail needs its own entry-to-capability audit and implementation slice instead of being considered done by the generic Members detail summary.

Recommended split:

- P0: Create/edit agent baseline: create agent, avatar/name/description edit, computer/runtime/model/reasoning, env vars.
- P0: Agent lifecycle/status baseline: online/offline/starting/stopping/restarting/error/updating/outdated/no-computer, open DM, start/stop/restart/reset/delete disabled/noPermission/offline handling.
- P0: unify Agent detail navigation entries from Members, Computers, Thread avatar/member click, DM, and Settings.
- P0: runtime configuration editor: runtime/model/reasoning, runtime model rescan, migration/restart confirmation, offline machine disabling.
- P1: Permissions/scopes tab.
- P1: Agent DMs/reminders/activity tabs.
- P1: Workspace tree/file preview.
- P2: skills/integrations/report issue diagnostics after core actions are reachable.

### Realtime, Notifications, and Host Capabilities

React realtime updates many surfaces through socket events. KMP now has Android raw realtime forwarding, an expanded OHOS bridge allowlist, and shared notification handling. These should still be treated as VERIFY until tested against a real server/session on both platforms:

- Home/Thread receive `message:new`, `dm:new`, unread, saved, member, server, and notification updates.
- OHOS `slockRealtimeEvent` reaches the shared reducer with the same raw `eventName + rawPayload` shape as Android.
- Notification permission/settings/channel/local notification host capabilities are validated on device.

## Priority Plan

P0 should focus on entries that users can tap today and find inert or wrong:

1. Auth non-email flows: Google/GitHub, create account, forgot/reset, invite/email verify.
2. Home channel plus create flow, Search, and Saved.
3. Thread top member count, fallback avatars, OHOS attachment validation, and remaining list/foot meta validation.
4. Members list/detail cross-entry parity.
5. Settings Account/Profile, Server/Workspace actions, Computers/Add Computer, offline/outdated computer notifications, and Agent detail actions that appear interactive.

P1 should cover broader parity and admin workflows:

1. Sidebar channel/DM/server context menus.
2. Invite human, create agent, create machine.
3. Activity/ThreadsInbox breadth against React.
4. Computer workspace scan/delete and agents-on-computer bulk actions.
5. Billing and Mobile/notification edge cases.

P2 can wait until core entries are no longer inert:

1. Release notes/profile/legacy task overlays.
2. Members graph.
3. Storage management polish.
4. Visual-only deltas after behavior is wired.

## Task Mapping

Use these existing canonical tasks where possible:

- Home channel plus: `#首页专修` task #153.
- KMP route loading/optimistic first slice: `#native` task #44.
- Members list agent parity: `#members专修` task #26.
- Thread member count: `#members专修` task #27.
- Members detail: `#KMP` task #98.
- Thread foot meta / lazy bounds: `#对话流专修` task #215 and follow-up owner validation.
- Settings real action wiring: `#设置项专修` tasks #35-#39 and task #44 audit.
- Computers/Add Computer/offline machine warnings: no single canonical task found in this audit; create one from the Computers rows instead of burying it under generic Settings.
- Agent detail parity: no single canonical task found beyond Members detail; create a dedicated KMP Agent detail task if task #98 does not already cover tabs/actions.
- Thread header visual testing: `#design` task #68.

Create new tasks only when no canonical task exists, and keep each task scoped to one entry-to-capability chain.
