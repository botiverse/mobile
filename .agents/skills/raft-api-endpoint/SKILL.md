---
name: raft-api-endpoint
description: Use when Raft Mobile needs to call a new or changed server HTTP endpoint, add/rename a DTO field, or write a test fixture for an API payload — verifying the contract against botiverse/slock and wiring it through shared DTOs, RaftNetworkClient with an explicit priority, and the MVI store.
---

# Adding / changing an API call

Iron rules from AGENTS.md "Slock server/source contract", "Shared DTO API
Contract Rule", "Network Priority Workflow", "DTO mocks must be verified".

## 1. Read the contract (never infer it from mobile code)

```bash
export SLOCK_SOURCE_REPO=/path/to/botiverse/slock      # branch staging
git -C "$SLOCK_SOURCE_REPO" fetch && git -C "$SLOCK_SOURCE_REPO" rev-parse HEAD   # record this SHA
grep -rn "<route path>" "$SLOCK_SOURCE_REPO/packages/server/src/routes"
```
Read the route handler, its service, and `packages/shared/` types. Write down
the exact path, method, request body, response envelope, camelCase field names,
optionality and error statuses. Never vendor the server source into this
repository.

## 2. Model it in shared

- File: `compose/shared/src/commonMain/kotlin/build/raft/app/data/<Area>Api.kt`
  (pattern: `MessageTranslationsApi.kt`). Put the server contract and the slock
  file paths in the KDoc.
- `@Serializable` wire DTOs with exact field names; parse with
  `Json { ignoreUnknownKeys = true }`. No snake_case aliases, no
  `firstObject(...)` envelope sniffing, no `string("a","b")` guessing, no
  `mapOf` / ad hoc `JsonObject` request bodies.
- Map wire DTOs to domain types; repositories return `DataResult<T>` /
  `DomainError`, never raw HTTP responses, tokens or headers.
- Call through `RaftNetworkClient.request(priority = ..., path = ..., ...)`.
  Priority is mandatory: `Foreground` user-blocking open/mutation, `Visible`
  on-screen refresh/pagination/retry, `Background` warmup/reconcile/polling/
  preload.
- No Android endpoint facades or app-local JSON parsers in
  `compose/androidApp`; platforms adapt only auth/refresh/headers/raw HTTP.

## 3. Wire it through MVI

UI → Store → UseCase → Repository → DataSource. The Store owns single-flight,
stale tokens, no-op equality gates and error mapping; success patches only the
affected slice, failure keeps previous state and attaches an error. Web links
built from responses use the Developer environment `webUrl`
(`BridgeSettingsPlatformCapabilities.loadDeveloper()`), not a hardcoded host.

## 4. Test with the real shape

- Parser test in `compose/shared/src/commonTest/kotlin/build/raft/app/data/`
  using the canonical upstream payload (copied from server source/tests, with
  the SHA in a comment). Assert parsed fields, not whole JSON strings.
- If no upstream fixture exists, name the test and DTO "pending contract
  verification" and resolve before In Review.
- Keep negative tests that reject guessed wrappers/aliases.
- Coroutine tests: `runTest` + virtual time (AGENTS.md "Unit Test Case Rule").

## 5. Validate

```bash
scripts/check_slock_source_path_contract.sh
```
then hosted CI (`raft-ci-validation`). `commonMain` must also pass the iOS
compile, which ordinary PRs skip: JVM-only APIs (`Map.putIfAbsent`,
`Map.merge`) break Kotlin/Native. Run `test.yml` with `scope=full` when in
doubt and say in the PR whether iOS ran.
