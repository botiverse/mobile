# Kuikly HRBridgeModule Contract

Date: 2026-05-20

> **OHOS note:** HarmonyOS (OHOS) was frozen at 1.14.x and its host was removed
> from `main`; OHOS rows below are historical and describe `release/1.14.x`.

`HRBridgeModule` is Slock's host capability bridge for shared Kuikly pages. Shared code may call the same module name on Android and OHOS; each host implements the platform-specific storage, network, routing, and diagnostics work.

This document fixes the method surface that shared code is allowed to depend on while the app moves toward one `compose_slock_app_shell` container.

## Result Conventions

- Async methods must always call their callback exactly once.
- Transport failures must be returned as structured payloads, not thrown through Kuikly render.
- Network errors use `code = 0`, empty `bodyText`, and a non-empty `errorBodyText` / `message`.
- Storage errors use `status = "error"`, `code`, and `message`.
- Successful storage mutations use `status = "ok"`.

## Required Shared Methods

### `authRequest`

Purpose: shared network transport for auth, Home, Thread, Settings, and send flows.

Request:

| Field | Type | Notes |
| --- | --- | --- |
| `url` | string | Absolute API URL. |
| `method` | string | `GET` / `POST` today; Android accepts other `HttpURLConnection` methods, OHOS currently maps to GET/POST. |
| `bodyText` | string? | JSON request body. Empty or missing means no body. |
| `authorization` | string? | Full authorization header value, usually `Bearer ...`. |
| `serverId` | string? | Sent as `X-Server-Id` when non-empty. |

Response:

| Field | Type | Notes |
| --- | --- | --- |
| `code` | number | HTTP status, or `0` for transport failure. |
| `bodyText` | string | Filled for 2xx responses. |
| `errorBodyText` | string | Filled for non-2xx or transport failures. |
| `message` | string? | Optional diagnostic for transport failures. |

Parity:

| Host | Status |
| --- | --- |
| Android | Implemented by `SlockKuiklyBridgeModule.authRequest` using `HttpURLConnection`. |
| OHOS | Implemented by `KRBridgeModule.authRequest` using ArkTS `http`. |

### `relationalStore`

Purpose: shared local relational store bridge for KMP state/cache on hosts that do not run the same JVM storage layer.

Request:

| Field | Type | Notes |
| --- | --- | --- |
| `action` | string | `query` or mutation action. Non-`query` is treated as execute. |
| `databaseName` | string? | Defaults to `slock_shared.db`. |
| `version` | number? | Store version, currently used by OHOS. |
| `sql` | string | SQL statement. |
| `bindArgs` | array | Positional bind values. Boolean values are stored as `1` / `0`. |

Query response:

| Field | Type | Notes |
| --- | --- | --- |
| `status` | string | `ok` or `error`. |
| `columns` | array<string> | Present on successful query. |
| `rows` | array<array> | Present on successful query. |
| `code` | number? | Present on error. |
| `message` | string? | Present on error. |

Mutation response: `{"status":"ok"}` on success.

Parity:

| Host | Status |
| --- | --- |
| Android | Implemented with `SQLiteDatabase`; mirrors `auth_session` writes into `EncryptedAuthSessionStore` while auth gate migration is in progress. |
| OHOS | Implemented with ArkTS `relationalStore.RdbStore`. |

### `log`

Purpose: shared diagnostics.

Request: `{"content": string}`.

Parity: Android logs to Logcat and Slock diagnostics; OHOS logs to console. Shared code must not rely on a callback.

### `currentTimestamp`

Purpose: shared timing and lightweight diagnostics.

Response: string milliseconds since epoch.

Parity: implemented on Android and OHOS.

## Transitional / Optional Methods

These methods exist because Slock still has migration-era Android islands or demo-derived bridge API. Shared app-shell code should avoid adding new dependencies on them unless the method is promoted to required above.

| Method | Current use | Android | OHOS | Direction |
| --- | --- | --- | --- | --- |
| `settingsEvent` | Standalone shared page compatibility event | Implemented where still used by direct page harnesses | Not implemented | Retire after remaining direct page visual/debug harnesses move into shared shell contracts. |
| `closePage` | Close current Kuikly page | Implemented | Stub | Prefer shared router/back handling; implement host close only if page-level close remains necessary. |
| `openPage` | Demo-style page open | Not implemented in Slock Android bridge | Stub | Use Kuikly `RouterModule` or shared `NavHost` for app routes. |
| `toast` | User-visible host toast | Implemented | Stub | Optional until product decides shared toast behavior. |
| `copyToPasteboard` | Clipboard capability | Not implemented | Stub | Promote when shared copy actions must work on OHOS. |
| `localServeTime` / `dateFormatter` | Demo/time helpers | Not implemented or unused in Android Slock bridge | Partial/stub | Avoid for Slock business time; use shared `SlockTime`. |
| `showAlert`, `reportDT`, `reportRealTime`, `readAssetFile` | Demo or platform helper | Not part of Android Slock bridge | Partial | Keep out of new shared Slock flows unless documented separately. |

## Production Host Registration

OHOS production registration now exports only Slock-owned capabilities:

- `HRBridgeModule`
- `SlockLucideIconView`
- `SlockOhosHdsTabBarView`

The demo `KRMyModule` and `KRMyView` are removed from OHOS production registration and source. Future smoke/demo components should live in a debug-only host or a sample app, not in the production Slock host delegate.

## Retired Transitional Methods

- `threadEvent` was removed after full shared Thread stopped using the legacy Android island event path.
- `filesEvent` was removed with the standalone `compose_thread_files` page. Product Thread renders `ThreadFilesContent` inside `compose_thread` and routes file actions through the shared Thread route/data-source.
- `composerEvent` was removed after product Thread composer actions moved to typed `ThreadPage` handlers. The remaining standalone composer visual fixture route is registered only from `compose/shared/src/visualFixtures/kotlin` when `slockVisualFixtures=true`; it logs local visual events instead of calling host compatibility events.
