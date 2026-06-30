# Agent Detail Permissions Tab — React Parity Spec

Date: 2026-06-25
Owner: @artin
Implementer: @Android-Developer-3 (task #89)
Visual: PUX (per #visual-testing coordination 2026-06-24 17:48)
Spec scope: task #89 (Permissions tab on agent detail page) — replace placeholder with real permissions form mirroring React `AgentScopesPanel.tsx`.

## 1. React source of truth

`third_party/slock-source/packages/web/src/components/agent/AgentScopesPanel.tsx` (177 lines).

Wire shape:
- `GET /api/agents/:id/scopes` → `AgentScopeSet { agentId, granted: AgentGrantableScope[], revision, updatedAt, mode: "default" | "custom" }`
- `PUT /api/agents/:id/scopes` body `{ scopes: AgentGrantableScope[] }` OR `{ mode: "default" }` → returns updated `AgentScopeSet`

Scopes model (`packages/shared/src/agentScopes.ts`):
- `AGENT_GRANTABLE_SCOPES` = 14 strings: `inbox:receive`, `server:read`, `channel:read`, `channel:join`, `channel:leave`, `thread:unfollow`, `message:read`, `message:send`, `attachment:upload`, `attachment:view`, `task:read`, `task:write`, `knowledge:read`, `action:prepare`
- `AGENT_SCOPE_GROUPS` = 8 groups across 2 sections (`"notifications"` / `"cli"`):
  - **Notifications / inbox section** (1 group): `inbox` (1 scope: inbox:receive)
  - **Raft CLI permissions section** (7 groups): `server`, `channel`, `thread`, `message`, `attachment`, `task`, `knowledge`, `action` (note: `action` group is in CLI section in React)
- Each row: `{ scope, verbLabel, description }`

UI structure (panel-level, not per-row):
1. **Mode banner** at top — shows "Using default permissions" vs "Customized permissions" with explanation + "Use default" button (only in custom mode)
2. **Two section headers** — "Notifications / inbox" + "Raft CLI permissions", each with subtitle
3. **Groups** — brutal-bordered box (2px black border, no shadow) per group, with group label/description in header
4. **Rows** — checkbox + verb label + scope code (mono, 10px, 40% black) + description. Rows separated by `border-b border-black/10` (subtle horizontal divider), last row no border
5. **Action bar at bottom** — "Save permissions" (pink, disabled when not dirty or saving) + "Discard" (white, same disabled) + revision label (mono, 11px, 40% black, right-aligned)

Save semantics (per file header comment):
- Optimistic local state (draft set of scopes)
- Single PUT on Save (NOT per-toggle auto-save)
- Server's revision counter is whole-set anyway
- "dirty" check: `draft.size !== serverSet.granted.length` OR any granted scope not in draft

Permissions tab visibility gate (`AgentDetailPanel.tsx:1963`): `if (tab === "permissions" && agent.deletedAt) return false;` — deleted agents don't show this tab. Per-rest gating is already handled by `useServerPermissions().capabilities` in React (only render when viewer has `manageAgents`); KMP-side equivalent is `canManageAgent`.

## 2. KMP current state

`MembersPage.kt:2261-2264`:
```kotlin
MembersAgentDetailTab.Permissions ->
    MembersAgentPlaceholderTab(
        title = "PERMISSIONS",
        detail = "No permission scopes available.",
        modifier = Modifier.padding(horizontal = MembersPageInset)
    )
```

`MembersAgentPlaceholderTab` is a generic placeholder. No scopes data, no API call, no form.

## 3. KMP data layer requirements

- New data class `AgentScopeSet` in `DataSnapshots.kt`:
  ```kotlin
  data class AgentScopeSet(
      val agentId: MemberId,
      val granted: List<String>,  // scope strings
      val revision: Long,
      val updatedAt: String?,
      val mode: String  // "default" | "custom"
  )
  ```
- `AgentWireDto` extension: add `scopes: AgentScopeSet?` to agent detail API response. Backend currently may not return this — check `agentService` schema. **Open question for AID/AAPI lane**: confirm backend returns `GET /api/agents/:id/scopes` and the create/update paths. If not, KMP cannot render real data and stays placeholder.
- `SlockAgentsApiClient` extensions: `getAgentScopes(agentId)`, `updateAgentScopes(agentId, scopes: List<String>)`, `resetAgentScopes(agentId)` (default).
- `SharedMembersStore` / `SharedAgentDetailStore` (per #56 lane): add `loadAgentScopes(agentId)` action + cache invalidation on update.

## 4. KMP UI implementation

Replace `MembersAgentPlaceholderTab` placeholder with new `MembersAgentPermissionsTab`:

State (local):
```kotlin
var serverSet by remember { mutableStateOf<AgentScopeSet?>(null) }
var draft by remember { mutableStateOf<Set<String>?>(null) }
var loading by remember { mutableStateOf(true) }
var saving by remember { mutableStateOf(false) }
var error by remember { mutableStateOf<String?>(null) }
```

Lifecycle:
- `LaunchedEffect(agentId)` calls `loadAgentScopes(agentId)` → set `serverSet` + `draft` to a `Set(serverSet.granted)`
- "Save permissions" → `updateAgentScopes(agentId, draft.toList())` → on success update `serverSet` + reset `draft`; on failure keep `draft` and show error banner
- "Discard" → `draft = serverSet.granted.toSet()`
- "Use default" → `resetAgentScopes(agentId)` → same as Save with `mode: "default"`

UI render order (top to bottom):
1. **Error banner** (if `error != null` and `serverSet == null`): `SlockBanner(intent="warning", text=error)` + "Retry" brutal button
2. **Mode banner**: brutal-bordered box (2px black border, no shadow), `SlockColor.SurfaceWhite` background. Left: "Using default permissions" or "Customized permissions" bold + subtitle; right: "Use default" brutal button (only in custom mode)
3. **Notifications / inbox section** (header + groups): each group = brutal-bordered box (2px black border, no shadow), inner rows = checkbox + verb + scope code + description with bottom border
4. **Raft CLI permissions section** (header + groups): same structure
5. **Action bar**: "Save permissions" (coral pink, `SlockBrutalActionButton` with `backgroundColor = SlockColor.AccentPink` — matches React `bg-brutal-pink`), "Discard" (white), revision label right-aligned

`SlockBrutalActionButton` already supports disabled state + `backgroundColor` parameter — reuse.

`SlockCheckbox` (`SlockCheckbox.kt` from #57) — reuse. Currently `SlockCheckbox(checked, onCheckedChange, enabled, size)`. Default 16dp — React checkbox is md-sized; size 16 matches.

## 5. Component reuse vs new

- **Reuse**: `SlockBrutalActionButton` (#60), `SlockCheckbox` (#57), `SlockBanner` (verify exists in `SlockBrutalComponents.kt`), `SlockType.Caption` (mono 10/11px for scope code + revision)
- **New**: nothing — the panel is a layout of existing primitives + state machine
- **No new shared component** — the layout is specific to the Permissions tab

## 6. Out of scope

- Backend `GET /api/agents/:id/scopes` endpoint — must be confirmed by AID/AAPI. If missing, KMP render stays placeholder + spec re-opens when backend ready
- KMP data layer integration with `SharedAgentDetailStore` (#56 lane, AD4 work) — defer to AD4 to wire scopes state
- Permissions tab content for non-agent detail (e.g. server-level permissions) — different surface
- React has `ResetAgentDialog` confirmation dialog — KMP has `MembersLifecycleConfirmation` analog (already used for Stop/Start/Reset/Delete). Reuse for any "Use default" confirmation if needed (currently no confirmation in React either — direct PUT)

## 7. File touches

- `compose/shared/src/commonMain/kotlin/build/raft/app/data/DataSnapshots.kt` — add `AgentScopeSet` data class
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/AgentsApi.kt` — add `AgentScopeSet` wire DTO + extension + 3 API methods on `SlockAgentsApiClient`
- `compose/shared/src/commonMain/kotlin/build/raft/app/MembersPage.kt` — replace `MembersAgentPlaceholderTab` call with new `MembersAgentPermissionsTab` Composable + helper functions for `AgentScopeGroup` / `AgentScopeRow` data classes (mirroring `AGENT_SCOPE_GROUPS` shape)
- `compose/shared/src/commonTest/kotlin/build/raft/app/SharedCreateAgentDialogTest.kt` (or new test file) — add tests for `loadScopes / saveScopes / resetScopes / dirty detection`

## 8. Acceptance

- Permissions tab in agent detail page renders 8 groups (1 notifications + 7 CLI) with 14 checkboxes total
- Toggling a checkbox changes `draft`; "Save permissions" button becomes enabled when `draft != serverSet.granted` set
- Save PUTs whole set, server returns updated `AgentScopeSet`, KMP updates `serverSet` + resets `draft`
- "Discard" resets `draft` to current `serverSet.granted`; Save button disabled again
- "Use default" button (custom mode only) calls `resetAgentScopes(agentId)`; after success `mode: "default"`
- Error banner + Retry on initial load failure
- No regressions in `#90` status indicator / `#62` pulse / `#57` SlockCheckbox
- `:shared:compileDebugKotlinAndroid :androidApp:assembleDebug :shared:test` all green
- Visual capture: PUX adds `screens.members.agent-detail.permissions` case to `sharedCases.json` after spec lands; AD-3 wires Android capture fixture in same worktree

## 9. Open questions for owner / AID

1. **Backend endpoint** `GET /api/agents/:id/scopes` + `PUT /api/agents/:id/scopes` — does server return this? KMP render can't show real data without it. AID/AAPI confirm.
2. **Visibility gate** — React only shows Permissions tab when viewer has `manageAgents` server capability. KMP `canManageAgent` callback already gates via host, but spec wants confirm: should Permissions tab also hide for non-managers? Per current `MembersAgentDetailTab` (`MembersPage.kt:191`) logic: `tab == MembersAgentDetailTab.Permissions && deleted -> false` is the only KMP gate. Host `canManageAgent` gate is upstream of the tab list. Confirm with owner.
3. **SharedAgentDetailStore integration** — scopes state should live in `SharedAgentDetailStore` (AD4 #56 work) for cross-tab consistency. Confirm with AD4 before implementing local state in `MembersAgentPermissionsTab`.

Spec unblock depends on #1 (backend) — if no backend, defer implementation until AID/AAPI land it. If backend ready, can implement KMP UI in parallel.
