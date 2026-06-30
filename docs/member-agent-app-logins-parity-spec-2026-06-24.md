# Member Agent Apps / Raft Agent Login Parity Spec

Date: 2026-06-24

Owner scope: Android / KMP member detail integration. This spec fixes the Members agent detail `Apps` tab semantics so Android matches React: `Apps` means third-party / built-in apps this agent has logged into through Raft Agent Login, not Codex/KMP skills.

## Problem

React agent detail uses an `integrations` tab labelled `Apps`:

- `third_party/slock-source/packages/web/src/components/agent/AgentDetailPanel.tsx:103-125` defines the tab id as `integrations` and label as `Apps`.
- `AgentDetailPanel.tsx:227-279` loads `GET /api/integrations/agents/:agentId` and describes the section as "Apps this agent has logged into with Raft Agent Login".
- `AgentDetailPanel.tsx:281-323` renders only active login grants, with app name, grant created time, description, manifest URL, scopes, revoke action for server managers, and service link.
- `AgentDetailPanel.tsx:2238-2242` keeps skills in the Profile surface via `<AgentSkills ... embedded />`; skills are not the Apps tab.

Android / KMP currently conflates these two domains:

- `compose/shared/src/commonMain/kotlin/build/raft/app/data/DataSnapshots.kt:168-175` defines `AgentDetailSnapshot` with only `skills` for this part of the detail model.
- `MemberRepositoryDataSource.kt:280-301` fetches `GET agents/:id/skills` as part of agent detail.
- `MembersPage.kt:3553-3568` correctly renders a `SKILLS` section from `detail.skills`.
- `MembersPage.kt:4040-4050` incorrectly renders the `APPS` tab from `detail.skills.groups`, showing skill group names as app rows.

The result is a user-visible parity bug: Android says "Apps" but shows skill paths / skill groups, while React shows Raft Agent Login grants.

## Canonical Product Meaning

`Members > Agent Detail > Apps` must mean:

1. Apps with an active or pending Raft Agent Login/OAuth relationship for this agent.
2. Source of truth: `GET /api/integrations/agents/:agentId`.
3. Rows are `IntegrationOverviewItem`-shaped records from server `oauthService.getAgentIntegrationsOverview`.
4. Skills remain a separate `SKILLS` section in Profile and continue to use `GET /api/agents/:id/skills`.

`Apps` must not use:

- Codex/Codex skill roots.
- `AgentSkillsSnapshot`.
- `GET /api/agents/:id/skills`.
- Mock labels like "available" derived from skill count.

## Server Contract

React and server evidence:

- User-auth route: `GET /api/integrations/agents/:agentId`.
- Server implementation: `third_party/slock-source/packages/server/src/routes/integrations.ts:440-455`.
- Permission failure copy: `Only server owners and admins can view agent integrations`.
- Returned item shape is `IntegrationOverviewItem` from `oauthService.ts:35-55`:
  - `id`
  - `type`: `pending | active`
  - `serverId`
  - `agentId`
  - `agentName`
  - `agentDisplayName`
  - `clientId`
  - `clientKey`
  - `clientName`
  - `clientDescription`
  - `clientHomepageUrl`
  - `clientReturnUrl`
  - `clientAgentManifestUrl`
  - `scopes`
  - `remember`
  - `createdAt`
  - `resolvedAt`
  - `resolvedByUserId`
  - `revokedAt`

Android already has a typed transport stub:

- `IntegrationsActionsPushApi.kt:31-38` includes `agentIntegrations(agentId)`.
- `IntegrationsActionsPushApi.kt:70-71` calls `integrations/agents/${agentId.value}`.
- `IntegrationsActionsPushApi.kt:157-163` currently reduces the response to `AgentIntegrationsPage(itemCount, firstClientName)`.
- `IntegrationsActionsPushApi.kt:282-286` currently has an incomplete DTO with only `clientId` and `clientName`.

That existing stub is useful as proof of endpoint routing, but it is not sufficient for the Members Apps UI.

## Target Android Data Model

Add a real app-login snapshot model in shared data, separate from skills:

```kotlin
data class AgentAppLoginSnapshot(
    val agentId: MemberId,
    val items: List<AgentAppLoginItem> = emptyList()
)

data class AgentAppLoginItem(
    val id: String,
    val type: AgentAppLoginType,
    val clientId: String,
    val clientKey: String?,
    val clientName: String,
    val clientDescription: String?,
    val clientHomepageUrl: String?,
    val clientAgentManifestUrl: String?,
    val scopes: List<String>,
    val remember: Boolean,
    val createdAt: SlockInstant?,
    val resolvedAt: SlockInstant?,
    val resolvedByUserId: String?,
    val revokedAt: SlockInstant?
)

enum class AgentAppLoginType { Pending, Active }
```

Integration choices:

1. Prefer adding this to `AgentDetailSnapshot` as `appLogins: AgentAppLoginSnapshot = AgentAppLoginSnapshot(agentId)`, because the tab belongs to agent detail.
2. Load it independently from skills so an apps 403/500 does not collapse the Profile, DMs, Reminders, Workspace, Skills, or Activity surfaces.
3. Keep `AgentSkillsSnapshot` unchanged and only used by the Profile `SKILLS` section.

## Target Repository / Store Behavior

Implement a real repository boundary instead of rendering endpoint probes:

1. Expand `IntegrationOverviewItemDto` in `IntegrationsActionsPushApi.kt` or move it to a reusable data parser with all fields listed above.
2. Change `SlockIntegrationsActionsPushApiClient.agentIntegrations(...)` to return `DataResult<AgentAppLoginSnapshot>` or create `MemberRepositoryDataSource.getAgentAppLogins(...)` backed by the same endpoint.
3. Store state should distinguish:
   - `Loading`
   - `Content(items)`
   - `Empty`
   - `NoPermission`
   - `Error(code, retryable)`
4. Do not add the app-login request into the all-or-nothing `getAgentDetail(...)` combine unless the combine is also relaxed. Apps is a private auxiliary tab and must not block base profile detail.
5. Refresh apps when:
   - agent selection changes,
   - user taps Retry,
   - revoke succeeds,
   - app foreground/detail refresh key fires.

## Target UI

Replace `MembersAgentAppsTab(detail: AgentDetailSnapshot?)` with state-backed rendering:

1. Header title: `CONNECTED APPS`.
2. Subtitle/copy: `Apps this agent has logged into with Raft Agent Login`.
3. Empty state: `No connected apps yet.`
4. Active rows:
   - app/client name,
   - status badge `Active`,
   - created time using existing Slock time formatting,
   - optional description,
   - optional `agent manifest: <url>`,
   - scope chips,
   - `Revoke` button only when current user can manage server / route allows it,
   - `View service` link/action only when `clientHomepageUrl` is present.
5. Pending rows:
   - keep hidden in MVP if matching React exactly, because React filters `items.filter((item) => item.type === "active")`.
   - If product wants pending visible on Android, first update React or write a separate parity note. Do not diverge silently.
6. Remove any use of `detail.skills.groups` from the Apps tab.
7. Keep `MembersAgentSkillsSection(detail)` in Profile unchanged.

## Tab Order

React order is defined by `AGENT_PANEL_TABS` in `AgentDetailPanel.tsx:118-126`:

`Profile -> Permissions -> Agent DMs -> Reminders -> Workspace -> Apps -> Activity`

This is only the default order. React also lets users drag the tab bar and persists the custom order:

- `AgentDetailPanel.tsx:1813-1818` reads `serverStore.sidebarOrder.agentPanelTabOrder` and writes reorder updates through `updateSidebarOrder({ agentPanelTabOrder: nextOrder })`.
- `ReorderablePanelTabs.tsx:98-145` implements the drag/drop tab bar and calls `onReorderTabs(...)`.
- `serverStore.ts:55-56` includes `agentPanelTabOrder` in sidebar order preferences.
- `serverStore.ts:309-310` loads `agentPanelTabOrder` from server data.
- `serverStore.ts:319-340` persists updates with `PATCH /servers/:serverId/sidebar-order`.

Android currently defines the same default enum order in `MembersPage.kt:155-166`, and `membersVisibleAgentTabs(...)` uses `MembersAgentDetailTab.entries` as-is. That is correct only before a user reorders tabs on React. Parity requires Android to consume the server-backed `agentPanelTabOrder` preference and reconcile it with currently visible tabs the same way React does:

1. Start from `membersVisibleAgentTabs(canViewPrivateSurfaces, deleted)`.
2. Read `agentPanelTabOrder` from the shared server/sidebar-order preference model.
3. Apply stored ids first, skipping unknown/hidden/duplicate ids.
4. Append any visible tabs missing from stored order in default enum order.
5. If Android later supports drag reorder, persist the same `agentPanelTabOrder` field via the sidebar-order API.

Until Android consumes `agentPanelTabOrder`, a user who reordered tabs on React will see a different Android order. If QA still sees an order mismatch after this is implemented, investigate the specific route/fixture that launched member detail:

1. Check whether private tab filtering removed a tab for the current user/deleted agent.
2. Check whether a stale preview/pageData route supplies an initial tab or alternate tab list.
3. Check whether horizontal tab scroll position makes the visible first tab appear different without changing order.
4. Check whether the stored server order includes old/unknown tab ids that must be reconciled, not rendered raw.

## Permission And Error Semantics

React calls the endpoint from the Apps tab and shows the server error banner on failure. Android should match behavior with native components:

1. If the user cannot view private agent surfaces, do not show or load Apps. Existing private notice copy may still mention Apps as hidden.
2. If Apps tab is visible but endpoint returns 403, show a tab-local warning/notice using the server code/copy; do not replace the whole member detail with `Private agent surfaces are hidden`.
3. If endpoint returns 404, show `Agent not found` or existing not-found detail state.
4. If endpoint returns network/5xx, show retry action inside Apps tab.
5. Revoke action uses `POST /api/integrations/grants/:grantId/revoke`; after success, reload apps.

## Mock / Preview State

Update sample/mock providers so preview and local Android runs show true app-login data:

1. Add an agent detail mock with at least two active app logins:
   - `Raft Actions` with scopes like `messages:read`, `tasks:write`.
   - `Calendar` or similar with description and homepage URL.
2. Keep a separate mock skill group under `SKILLS` so screenshots prove the two domains are not conflated.
3. Add empty and no-permission variants for Apps tab visual QA.

## Tests

Required unit tests:

1. Parser maps a full `IntegrationOverviewItem` payload to `AgentAppLoginItem`, including `scopes`, `createdAt`, `clientHomepageUrl`, and `clientAgentManifestUrl`.
2. Contract test asserts path `GET /api/integrations/agents/agent-1`.
3. Members Apps tab does not read `AgentSkillsSnapshot` when rendering app rows.
4. Revoke action calls `POST /api/integrations/grants/:grantId/revoke` and reloads the apps state.
5. Apps endpoint `NoPermission` does not clear already loaded agent profile detail.

Recommended screenshot/fixture checks:

1. Profile tab shows `SKILLS` with skill rows.
2. Apps tab shows `CONNECTED APPS` with Raft Agent Login copy and app rows.
3. Apps empty state says `No connected apps yet.`
4. Apps error state has a retry action and no skills-derived rows.

## Acceptance Criteria

This task is done when:

1. Android/KMP `Apps` tab no longer uses `detail.skills.groups`.
2. Android/KMP `Apps` tab fetches and renders `GET /api/integrations/agents/:agentId`.
3. Skills remain visible only in Profile `SKILLS`.
4. React and Android visible copy agree on the Raft Agent Login concept.
5. Android default tab order remains `Profile -> Permissions -> Agent DMs -> Reminders -> Workspace -> Apps -> Activity`, and server-backed React reorder preference `agentPanelTabOrder` is honored when present.
6. No app-login error collapses unrelated agent detail tabs.
7. `./gradlew :shared:allTests` or the narrower changed-module unit test set passes from `compose/`.
8. Android visual fixture or real-device smoke includes both Profile Skills and Apps Connected Apps surfaces.

## Non-goals

This spec does not require:

- Changing Settings `Connected Apps`.
- Changing server OAuth/agent login routes.
- Implementing new app login initiation from the member detail Apps tab.
- Moving skills/scopes/runtime config parity work; those remain separate Members agent-detail gaps.
