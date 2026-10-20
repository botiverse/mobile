---
name: slock-launch-contract
description: Audit, design, or fix Raft Mobile shared/KMP route, launch, Kuikly pageData, auth-resume, fallback-cache, realtime and auxiliary-store boundaries so Home, Thread, Settings, Composer, Inbox/Search/Saved and fixtures load product state through typed MVI stores/repositories instead of route payloads.
---

# Raft Mobile Launch Contract

The skill name remains `slock-launch-contract` for compatibility; current source namespace is `build.raft.app`.

## One rule

Production routes carry identity and intent. The destination resolves canonical product state through its typed store/repository. Route, host-launch and Kuikly `pageData` must not become a second product-state database.

## Allowed payload

- Identity: server/account principal, conversation/thread/channel, parent channel/message, member/object ids and typed conversation kind.
- Intent: navigation target, selected tab, focus request/nonce, draft seed, explicit action request.
- Presentation request that is not product data: compact layout toggle, local time/locale formatting hints.

Treat title/subtitle/preview/sender display fields, messages, tasks, files, profiles, members, agents, attachments, suggestions and composer UI state as suspicious. A historical field is not automatically allowed because it already exists in `Wire...LaunchPayload`.

## Identity pairing

Identity fields form a contract, not an unordered bag:

- server id must stay paired with the authenticated account/principal;
- reply thread requires typed parent conversation kind plus parent channel/message identity;
- DM member identity must not be inferred from display name, handle, URL or stale suggestion;
- stream/source ids must be resolved from the canonical conversation identity before cache, realtime, send or subscription scope is derived.

Missing or mismatched identity fails closed or reloads; it must not silently fall back to Channel/DM guesses.

## Candidate snapshots

Durable cache, in-memory cache, realtime payload, sparse API response, auth-resume request and legacy fallback/context are candidate inputs, not canonical state.

1. Bind the candidate to server/account and route identity.
2. Reject it if the identity or generation does not match.
3. If matched, use it only to fill fields the canonical store has not supplied.
4. Never let candidate display data overwrite canonical store state.
5. Do not add a new full-content fallback DTO to make a visual or launch test convenient.

Visual/smoke/test state belongs in a fixture provider or direct typed construction with an explicit non-production gate.

## Mounted native cache boundary

For avatar/image bytes mounted through native storage, identity matching alone is insufficient:

- shared owns environment/account/server owner scope, typed subject, canonical URL/revision, quota/expiry/retirement policy and commit identity;
- native owns opaque atomic bytes, integrity checks, bounded LRU and physical delete, but never downloader/network authority;
- publish a readable descriptor only after stage → reserve → bind → finalize succeeds;
- finalize failure or a later shared commit failure must rollback/retire the new native bytes;
- compare-and-swap includes the expected commit identity, including the explicit nil/absent case;
- logout, server/account retirement, corruption and terminal cleanup physically delete indexed bytes and stale staging/orphans.

An ownerless `sha256(url).png` directory, unbounded URL cache or native downloader remains invalid even when the URL happens to match the current route.

## Current Raft hotspots

Start at the current source rather than old line numbers:

- `compose/shared/src/commonMain/kotlin/build/raft/app/ThreadRoutePayload.kt`
- `ThreadLaunchConversationIdentity.kt` and `ThreadLaunchProvenance.kt`
- `SettingsStandalonePageData.kt`
- `ExternalShareLaunch.kt`
- `RaftShellStoreLaunch.kt`
- `data/RaftDatabaseLaunchRecovery.kt`
- Home/Settings/Thread open-request builders and auth-resume conversions

`WireThreadLaunchPayload` currently contains both typed route identity and historical origin fallback/display fields. Audit each field by authority and consumer; do not classify the whole DTO as safe merely because its name starts with `Wire`.

## Execution order

1. Trace production ingress: host URL/notification/share/auth-resume → parser → typed open request → store/repository.
2. Separate production parsing from smoke/visual fixture construction.
3. Resolve one canonical identity before creating cache keys, ViewModel keys, realtime joins, send scopes or auxiliary stores.
4. Remove or constrain content-bearing fallback fields.
5. Add descriptor/parser/behavior guards at the narrow boundary.

Use [references/scan-patterns.md](references/scan-patterns.md) for discovery. Scan results are triage, never a broad grep hard gate.

## Tests

- Serializer descriptor excludes forbidden content fields from launch DTOs.
- Parser accepts minimal identity/intent and ignores or rejects full-content payloads.
- Wrong server/account/parent/generation candidate is discarded.
- Reply-thread and DM kind cannot silently downgrade to Channel.
- Fixture-only payload cannot enter a production parser.
- Store/realtime/send/cache scopes are derived from the same resolved identity.

## Report shape

Report separately:

- canonical production path;
- remaining content-bearing or ambiguous route fields;
- visual/test/legacy compatibility path;
- identity/store boundary reused;
- guard and focused validation added;
- runtime/platform boundaries not exercised.
