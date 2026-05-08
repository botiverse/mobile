# Android Settings Billing And Server Sections Contract

Task: #388
Referenced by: #383

## Scope

This document is the canonical repo artifact for the Android Settings Billing
and confirmed Server sections contract. It records the current mobile contract
surface so future API wiring does not need to be reverse engineered from chat.

Browser settings are explicitly excluded from Android. Web has a Browser entry,
but the current Android Settings implementation keeps the mobile Settings
surface only.

## Source Of Truth

- Web parity reference: `/Users/artin/Downloads/Data/Slock.html`, saved from
  `https://app.slock.ai/s/slock-android/settings/server`.
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

No dedicated Server Settings REST endpoint is confirmed in `SlockApi` yet. The
current Android server snapshot is derived from the persisted auth session and
selected server metadata:

- `AuthSession.currentServerId`
- matching `AuthServer` from `AuthSession.servers`
- `AuthServer.name`
- `AuthServer.plan`

Mapped model: `SettingsServerSnapshot`.

### Profile Mapping

`SettingsServerProfileSnapshot`:

- `name`: selected `AuthServer.name`; missing server falls back to `Slock`
- `slug`: derived locally from `name.toSettingsSlug()`
- `canSave`: currently `false`

There is no confirmed Android endpoint for saving server profile changes in
this phase. The Save Profile action remains disabled until that endpoint and
permission contract are documented.

### Section Mapping

Current Android `RealSettingsRepository.getServerSettings()` emits only the
server sections that are confirmed for this mobile slice:

| key | title | state | semantics |
| --- | --- | --- | --- |
| `billing` | `Plan & Billing` | `Available` | Opens the readable Billing surface; detail includes the selected server plan display name. |
| `danger` | `Danger Zone` | `NoPermission` | Delete Server is visible but disabled; endpoint and owner permission contract are not confirmed. |

Section availability values:

- `Available`: section can be rendered as an active/readable Settings section.
- `Empty`: endpoint is confirmed but returned no user-visible rows.
- `Unavailable`: contract is not wired on Android yet; hidden from the current
  Server page by the UI filter.
- `NoPermission`: action exists in the IA but cannot be performed by this
  Android build/user.

### Deferred Section Contracts

These Web-confirmed server sections are not wired as Android API contracts in
this slice:

- `Admins`
- `Pending Invites`
- `Join Links`
- `Onboarding Agent`

Do not invent Android endpoints for these sections. When a section is wired,
create or update a contract doc with endpoint, aliases, null/missing/0
semantics, permissions, mapper landing point, and tests.

## REST Versus Realtime

Settings Billing and Server sections currently have no realtime delta contract.
The first Android load is derived from the persisted auth/session server list.
If future REST endpoints or realtime settings events are added, they must merge
into the same `SettingsBillingSnapshot` and `SettingsServerSnapshot` boundaries
instead of letting UI read raw payloads.

## Regression Coverage

- `app/src/test/java/ai/slock/android/core/data/network/RealSettingsRepositoryTest.kt`
  - `billingMapsReadablePlanStateAndRequiresOwnerPermission`
  - `computersServerAndMobileReturnUnavailableSnapshotsWithoutGuessingEndpoints`

These tests lock the current owner/admin Billing permission, readable plan
mapping, `billing_owner_required`, and the mobile Server section keys
`billing` / `danger`.

## Follow-up Gaps

- Dedicated Billing REST endpoint, if one exists, is not confirmed.
- Server profile save endpoint is not confirmed.
- Delete Server endpoint and owner permission contract are not confirmed.
- Admins, Pending Invites, Join Links, and Onboarding Agent endpoints are not
  confirmed for Android.
- Any future contract update must also update `artifacts/api-contract-index.md`.
