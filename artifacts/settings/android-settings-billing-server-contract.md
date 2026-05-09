# Android Settings Billing And Server Sections Contract

Task: #388, #1 in `#设置项专修`
Referenced by: #383

## Scope

This document is the canonical repo artifact for the Android Settings Billing
and confirmed Server sections contract. It records the current mobile contract
surface so future API wiring does not need to be reverse engineered from chat.

Browser settings are explicitly excluded from Android. Web has a Browser entry,
but the current Android Settings implementation keeps the mobile Settings
surface only.

## Source Of Truth

- Web parity reference:
  - `/Users/artin/Downloads/Data/Slock.html`, saved from
    `https://app.slock.ai/s/slock-android/settings/server`
  - `/Users/artin/Downloads/Data/Server.html`
  - `/Users/artin/Downloads/Data/Server_files/index-BaD09UKb.js`
- Android repository boundary:
  - `SettingsRepository.getBilling()`
  - `SettingsRepository.getServerSettings()`
- Current implementation landing point:
  - `RealSettingsRepository.getBilling()`
  - `RealSettingsRepository.getServerSettings()`
  - `SettingsBillingScreen`
  - `SettingsServerManagementScreen`

Compose/UI must consume mapped Settings snapshots only. It must not read raw
responses, bearer tokens, auth headers, or selected-server headers directly.

## Billing Contract

### Source

No dedicated Billing REST endpoint is confirmed in `SlockApi` yet. The current
Android billing snapshot is derived from the persisted auth session and selected
server metadata:

- `AuthSession.currentServerId`
- matching `AuthServer` from `AuthSession.servers`
- `AuthServer.name`
- `AuthServer.plan`
- `AuthServer.role`

### Permission And Empty Semantics

- Missing session maps to `DataResult.NoPermission` with `auth_required`.
- Missing selected/current server maps to `DataResult.NoPermission` with
  `server_required` and required capability `selected_server`.
- `role == "owner"` or `role == "admin"` can view Billing.
- Any other role maps to `DataResult.NoPermission` with
  `billing_owner_required` and required capability `manage_billing`.
- There is no server 404 contract for Billing yet because no Billing endpoint is
  called in this phase.

### Field Mapping

Mapped model: `SettingsBillingSnapshot`.

`SettingsBillingCurrentPlanSnapshot`:

- `planName`: derived from `AuthServer.plan.toPlanDisplayName()`.
- `usageRows`:
  - `Workspace`: `AuthServer.name`
  - `Role`: capitalized `AuthServer.role`; missing role falls back to `Member`
  - `Plan source`: fixed label `Selected server`
- `trialActive`: currently `false`.

Plan display name semantics:

- missing / null / empty plan -> `Unknown`
- `hobby` or `starter` -> `Hobby`
- `team` -> `Team`
- `business` or `enterprise` -> `Business`
- any other string -> title-cased original value

Plan cards currently exposed to the UI:

- `Starter`, price `$0`, features `Core chat`, `Inbox and thread access`
- `Team`, price `$12 / user`, features `Advanced permissions`,
  `Billing controls`, `current=true` when current plan is `Team`
- `Enterprise`, price `Contact sales`, features `SAML`, `Audit log`,
  `Priority support`, `comingSoon=true`

### UI State Mapping

`SettingsBillingScreen` consumes only:

- `SettingsCurrentPlanState`
- `SettingsPlanCardState`

Current UI states:

- loading: shows `settings_billing_loading`
- error: shows `settings_billing_error`
- no permission: shows `settings_billing_no_permission`
- content: shows current plan, usage rows, and plan cards

## Server Sections Contract

### Confirmed Web Sections

The saved Web Settings server page contains these body sections:

- `Profile`
- `Admins`
- `Pending Invites`
- `Join Links`
- `Onboarding Agent`
- `Plan & Billing`
- `Danger Zone`

The Web sidebar also contains `Browser`, but Android excludes Browser by
product decision. Do not add a Browser Settings route or Android Browser
contract from this Web page.

### Current Android Source

No single dedicated Server Settings REST endpoint is confirmed. Android now
builds the Server snapshot from selected-server session metadata plus the
individual Web-confirmed endpoints below:

- `AuthSession.currentServerId`
- matching `AuthServer` from `AuthSession.servers`
- `AuthServer.name`
- `AuthServer.plan`
- `PATCH /servers/{serverId}` for profile save, exposed in `SlockApi` but UI
  save remains disabled until editable state wiring is added
- `GET /servers/{serverId}/members`
- `GET /servers/{serverId}/usage`
- `GET /billing/subscription`
- `POST /servers/{serverId}/invites`, exposed in `SlockApi` but not surfaced
  as a send action yet
- `GET /servers/{serverId}/onboarding-settings`
- `PATCH /servers/{serverId}/onboarding-settings`, exposed in `SlockApi` but
  not surfaced as a toggle action yet
- `DELETE /servers/{serverId}`, exposed in `SlockApi` but Android keeps the
  destructive button disabled pending confirmation UI

Mapped model: `SettingsServerSnapshot`.

### Profile Mapping

`SettingsServerProfileSnapshot`:

- `name`: selected `AuthServer.name`
- `slug`: selected `AuthServer.slug`; missing slug falls back to
  `name.toSettingsSlug()`
- `canSave`: `true` for selected server `owner` or `admin`

Profile save endpoint is confirmed as `PATCH /servers/{serverId}`, but the
current Android UI still renders read-only text fields. Follow-up editable state
must keep mutation inside `SettingsRepository` and must not expose raw responses
to Compose.

### Section Mapping

Current Android `RealSettingsRepository.getServerSettings()` emits the Web
sections below:

| key | title | state | semantics |
| --- | --- | --- | --- |
| `admins` | `Admins` | `Available` / `Empty` / `NoPermission` / hidden on error | Loaded from `GET /servers/{serverId}/members`, filtered to `role == admin`. Owners are not duplicated as admins. |
| `billing` | `Plan & Billing` | `Available` / `Empty` / `NoPermission` / hidden on total error | Combines `GET /billing/subscription` plan and `GET /servers/{serverId}/usage` rows. Falls back to selected server plan when subscription fails. |
| `pending_invites` | `Pending Invites` | `Empty` / `NoPermission` | Web confirms invite creation by `POST /servers/{serverId}/invites`, but no pending invite list endpoint is present in the saved bundle. Android displays the empty state and does not send invites yet. |
| `join_links` | `Join Links` | `Empty` | Web accepts join-link tokens through auth invite flow, but no Settings join-link management endpoint is present in the saved bundle. Android displays disabled/empty state and does not guess an endpoint. |
| `onboarding_agent` | `Onboarding Agent` | `Available` / `NoPermission` / hidden on error | Loads `GET /servers/{serverId}/onboarding-settings`; owner-only settings map `setupModalReminderOptOut` / `onboardingReminderOptOut` to a readable status. |
| `danger` | `Danger Zone` | `NoPermission` | `DELETE /servers/{serverId}` is confirmed, but Android keeps the button disabled until explicit confirmation UI is added. |

Section availability values:

- `Available`: section can be rendered as an active/readable Settings section.
- `Empty`: endpoint is confirmed but returned no user-visible rows.
- `Unavailable`: contract is not wired on Android yet; hidden from the current
  Server page by the UI filter.
- `NoPermission`: action exists in the IA but cannot be performed by this
  Android build/user.

### Field Alias Semantics

Admins:

- List wrappers: `members`, `users`, `items`, `nodes`, `records`, `rows`,
  `list`, `results`, `data`
- Row id aliases: `userId`, `id`, `memberId`
- Name aliases: `displayName`, `name`, `username`, `email`
- Detail aliases: `email`, `handle`, `username`
- Only `role == admin` rows render in the Admins section.

Usage:

- Row wrappers: `usageRows`, `usage`, `rows`, `items`, `data`
- Explicit row aliases: `label` / `name` / `key` / `metric` and `value` /
  `displayValue` / `current` / `count`
- Object counters: `computers`, `machineCount`, `machines`, `computerCount`,
  `agents`, `agentCount`, `activeAgents`, `channels`, `channelCount`,
  `members`, `memberCount`, `history`, `historyDays`, `historyLimit`
- Limits support `*Limit`, `max*`, and `*Max`; missing limits render just the
  value, and explicit `Unlimited` string is preserved.

Billing subscription:

- Source objects: `subscription`, `billing`, `plan`, or root
- Plan aliases: `planName`, `plan`, `name`, `tier`, `currentPlan`
- Values pass through `toPlanDisplayName()`.

Onboarding:

- Source objects: `settings`, `onboarding`, or root
- Boolean aliases: `setupModalReminderOptOut`, `onboardingReminderOptOut`,
  `reminderOptOut`
- Missing boolean defaults to `false`, meaning reminder active.

## REST Versus Realtime

The Web bundle listens for `server:plan-updated` and reloads server data.
Android Settings Server currently refreshes from REST when the Settings Server
screen loads; no Settings-specific realtime delta is wired yet. Future realtime
events must merge into `SettingsServerSnapshot` rather than letting UI read raw
payloads.

## Regression Coverage

- `app/src/test/java/ai/slock/android/core/data/network/RealSettingsRepositoryTest.kt`
  - `billingMapsReadablePlanStateAndRequiresOwnerPermission`
  - `serverSettingsMapsWebConfirmedSectionsWithoutGuessingJoinLinks`
  - `computersAndMobileKeepExistingSettingsContracts`

These tests lock the current owner/admin Billing permission, readable plan
mapping, `billing_owner_required`, Server section keys, admin filtering,
usage/billing merge, onboarding mapping, and join-link disabled semantics.

## Follow-up Gaps

- Pending invite listing endpoint is not confirmed.
- Join-link management endpoints are not confirmed.
- Profile save, invite create, onboarding update, and delete server are exposed
  on `SlockApi`, but Android does not yet surface those mutations as enabled
  UI actions.
- Any future contract update must also update `artifacts/api-contract-index.md`.
