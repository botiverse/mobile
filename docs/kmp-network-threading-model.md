# KMP Network Threading Model

Scope: Slock Kuikly/KMP shared UI, shared data sources, and host network
modules on Android and OHOS.

> **OHOS note:** HarmonyOS (OHOS) was frozen at 1.14.x and its host was removed
> from `main`; OHOS sections below are historical and describe `release/1.14.x`.

This document answers the question from task #180: should response parsing
happen "inside the network process" at the same time as the HTTP request?

Short answer: today there is no separate Slock-owned network process in the
mobile client. The host network module fetches the response body on a host
background execution path and returns raw strings to shared/KMP. Shared/KMP
then moves endpoint-owned pure JSON decode/map work to `Dispatchers.Default`
where it is safe, and returns to the caller coroutine before committing UI
state.

## Process And Thread Shape

Current mobile client shape:

- One app process owns the Kuikly/Compose runtime, shared/KMP code, and the
  host native network module.
- The UI/runtime side owns composition, observable/state updates, Kuikly module
  access, and route/store lifecycle.
- Android host networking runs on a background executor via
  `RaftThreadAdapter.execute(...)`, then OkHttp executes synchronously on
  that worker.
- OHOS host networking runs through an async ArkTS `http.request(...)` path.
- Shared/KMP response decode/map can hop to `Dispatchers.Default` after the host
  module returns `bodyText` / `errorBodyText`.

The remote API server is a separate process somewhere else, but client-side
HTTP body parsing does not currently happen in a separate app process.

## Does Same Process Mean UI Blocking?

No. A separate process is not required to keep networking off the UI path. The
important boundary is the execution thread or queue that does the work.

Current expected behavior:

- Kuikly/Compose UI work stays on the runtime thread/queue.
- Android HTTP and response body string read stay on the host background
  executor.
- OHOS HTTP work stays on the host async HTTP path.
- Shared pure JSON decode/map can run on `Dispatchers.Default`.
- Only the final validation, merge, and UI-visible state commit returns to the
  runtime side.

Networking can still cause visible jank if one of these boundaries is broken:

- The host implementation performs HTTP or body reads on the Kuikly/runtime
  thread.
- Shared data source code decodes or maps a large response on the caller/runtime
  coroutine instead of a background dispatcher.
- Background parse is technically off the runtime thread but still consumes
  enough CPU to starve rendering on low-core devices.
- The final store commit is too large, causing reducer work, snapshot diff, or
  recomposition to monopolize the runtime thread.

That is why the tracing is split by phase. `kuikly_network` explains host/network
latency, `*_parse_start/result dispatcher=Default` proves pure parse left the
caller/runtime path, and store/first-render markers show whether the return to
UI state commit is still too heavy.

## Kuikly Reference Check

Checked references:

- `third_party/kuikly-ui/docs/Compose/concepts.md`
- `third_party/kuikly-ui/docs/DevGuide/thread-and-coroutines.md`
- `third_party/kuikly-ui/docs/DevGuide/network.md`
- `third_party/kuikly-ui/core/src/commonMain/kotlin/com/tencent/kuikly/core/module/NetworkModule.kt`
- `third_party/kuikly-ui/core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/module/KRNetworkModule.kt`
- `third_party/kuikly-ui/core-render-ohos/src/main/ets/modules/KRNetworkModule.ets`

The Kuikly recommendation is not "put networking in a separate client process".
The recommendation is:

- Kuikly UI operations, observable access, and Module API access stay on the
  Kuikly thread.
- Simple async control flow can use Kuikly built-in coroutines, which stay on
  the Kuikly thread and are dynamic-capable.
- Expensive work that must support dynamic pages should move behind a Module
  boundary so the platform host can use its own threading or async primitives.
- KMP multi-threading with `kotlinx.coroutines` / `Dispatchers.*` is appropriate
  only when the page does not need dynamic JS-style multi-platform execution.
- After background work completes, UI state changes must return to the Kuikly
  thread/runtime context.

The built-in Kuikly `NetworkModule.MODULE_NAME` follows this same shape:

- Common `NetworkModule.httpRequest(...)` builds request JSON and calls
  `toNative(false, "httpRequest", ...)`.
- Android `KRNetworkModule` executes HTTP work through
  `krThreadAdapter.executeOnSubThread { ... }`, then performs
  `HttpURLConnection` and response stream reads on that worker.
- OHOS `KRNetworkModule` has `syncMode(): false` and uses ArkTS
  `http.createHttp().request(...)` asynchronously.
- The built-in common callback attempts to wrap `data` as a `JSONObject`; if it
  is not JSON, it returns a `{"data": ...}` object.

That means the built-in module keeps network I/O off the Kuikly thread, but it
does not produce Slock endpoint typed models in a separate network process. It
also performs its basic JSON wrapper parse in the common callback path, not in a
typed endpoint parser service.

Slock's custom `HRSlockNetworkModule` is conceptually aligned with Kuikly's
Module recommendation, but it exists because Slock needs API-specific behavior
that the built-in module does not provide as-is:

- raw `bodyText` and `errorBodyText` separation,
- HTTP status code as the primary result contract,
- cancellation by request id,
- OkHttp on Android,
- multipart and file-ref uploads,
- Slock diagnostics around request/cancel/callback,
- auth/server/header policy owned by the shared Slock client.

Replacing `HRSlockNetworkModule` with the built-in `NetworkModule` would not
automatically improve process isolation or typed parsing. It would mainly trade
our current raw API contract and cancellation/upload behavior for Kuikly's
generic `JSONObject` callback contract.

## End-To-End Flow

```text
Kuikly / Compose UI
  rememberCoroutineScope / viewModelScope
          |
          v
Shared store / data source
  Starts refresh/load work on the runtime coroutine
          |
          v
KuiklySlockNetworkClient
  Applies request scheduling and auth middleware
          |
          v
SlockNetworkModule common bridge
  suspendCancellableCoroutine + toNative("request", ...)
          |
          v
Host native network module
  Android: background executor + OkHttp execute()
  OHOS: async ArkTS http.request()
          |
          v
Raw response string callback
  HomeNetworkResponse(bodyText, errorBodyText, code)
          |
          v
Shared parse boundary
  parseResponseBodyWithTrace(...)
  withContext(Dispatchers.Default) for pure decode/map
          |
          v
Back to caller runtime coroutine
  Validate, merge, write StateFlow/observable/snapshot state
          |
          v
Kuikly / Compose recomposition and render
```

## What Runs Where

### UI / Runtime Coroutine

Examples:

- `rememberCoroutineScope()` in Compose UI screens.
- `viewModelScope.launch` in shared stores.
- Store reducers, `StateFlow` updates, observable writes, route activation,
  and UI-visible state commits.
- Kuikly module access and bridge logging.

This side must stay Kuikly-thread safe. Do not call Kuikly modules, touch UI
state, mutate observables, or emit route state from a background parse block.

### Shared Network Client

`KuiklySlockNetworkClient.request(...)` runs in shared/KMP and calls:

1. `RaftRequestScheduler.run(...)`
2. auth middleware
3. `SlockNetworkModule.requestRaw(...)`

It logs network timing and response byte counts after the host module returns.
At this point the result is still raw text plus status code, not endpoint typed
domain objects.

### Common Kuikly Module Bridge

`SlockNetworkModule.request(...)` is a suspend bridge around a native module
call. It:

- assigns a request id,
- registers a callback,
- calls `toNative(false, "request", commandJson, callback, false)`,
- resumes the suspended coroutine when native returns,
- calls native cancel if the coroutine is cancelled.

This bridge crosses from shared/KMP into the host native implementation. It is
not a process boundary by itself.

### Android Host Network Path

`SlockKuiklyNetworkModule` receives the command JSON from Kuikly and runs the
request inside `RaftThreadAdapter.execute("network-request-...")`.

Inside that worker, Android uses OkHttp synchronously:

- build request,
- `CLIENT.newCall(request).execute()`,
- read `response.body?.string().orEmpty()`,
- callback with `code`, `bodyText`, and `errorBodyText`.

This avoids blocking the Kuikly runtime thread during HTTP and body string
read. It still runs inside the Android app process.

### OHOS Host Network Path

`SlockNetworkModule.ets` is async (`syncMode(): false`) and calls ArkTS
`http.request(...)`. It converts the HTTP result to string and callbacks with
the same raw response shape.

This avoids making the Kuikly call synchronous, but it is still the OHOS app
host side, not a separate Slock-owned network process.

### Shared Parse Boundary

After raw response text comes back to shared/KMP, data sources decode and map it
to endpoint-owned result types.

For hot or potentially large responses, the expected shape is:

```kotlin
parseResponseBodyWithTrace(
    source = "...",
    traceName = "...",
    code = response.code,
    bodyText = response.bodyText,
    logger = ...
) {
    // Pure JSON decode/map only.
}
```

The helper logs:

- `*_network_body_received`
- `*_parse_start`
- `*_parse_result`

When offload is enabled, the parser lambda runs under
`withContext(Dispatchers.Default)`. After it returns, execution resumes in the
original caller coroutine so the store can validate and commit state on the
runtime side.

## Why Parsing Is Not In The Host Network Module Today

The host network modules currently know how to:

- execute HTTP,
- apply headers/body/method/url from the command,
- return raw response strings,
- support cancellation.

They do not own the typed endpoint contracts, shared serializers, store merge
policy, route visibility rules, or normalized snapshot models. Those live in
shared/KMP so Android and OHOS share one implementation.

Moving all parse into the host network module would require one of these
architecture changes:

- duplicate endpoint-specific DTO/domain parsing in Android and OHOS host code,
- generate platform host parsers from the shared contracts,
- move a shared parser service behind the host boundary,
- make the network module return endpoint-specific typed payloads instead of
  raw strings.

Those are possible future designs for very large or very hot endpoints, but they
are not the current architecture.

## What Can Be Offloaded

Safe to run on `Dispatchers.Default`:

- `Json.decodeFromString(...)`
- DTO-to-domain mapping with no side effects
- pure filtering, grouping, sorting, counting
- building immutable snapshots or result objects

Must stay on the caller/runtime side:

- Kuikly module access, including `pager.acquireModule(...)` and `toNative(...)`
- bridge logging if the logger touches Kuikly/runtime-only objects
- observable writes and UI state changes
- `StateFlow`/store commits that drive recomposition
- injected mappers that may call platform capability, bridge loggers, or other
  runtime-thread-only code

If a parser lambda might touch runtime-only objects, keep it on the caller side
until the dependency is split into a pure parse phase and a runtime commit/log
phase.

## Dynamic Page Caveat

`kotlinx.coroutines.Dispatchers.Default` is useful for the current KMP mobile
paths, but it is not automatically a safe dynamic-page primitive across every
Kuikly deployment mode.

For dynamic pages, do not assume a real background worker exists just because a
coroutine dispatcher is named `Default`. Heavy dynamic-mode parsing should move
behind an explicit host/module boundary or use a Kuikly-supported async model
that is known to be safe for that page mode.

## How To Read The Traces

Network timing:

```text
kuikly_network endpoint source=... method=... path=...
```

Parse timing:

```text
<source>_network_body_received code=200 responseBytes=...
<source>_parse_start dispatcher=Default
<source>_parse_result result=success durationMs=...
```

Interpretation:

- Long `kuikly_network` duration means the host/network phase is slow.
- Long `*_parse_result` with `dispatcher=caller` means parse may still be
  competing with the UI/runtime path and should be reviewed.
- Long `*_parse_result` with `dispatcher=Default` should not directly block the
  Kuikly runtime thread, but it can still compete for CPU on the device.
- A missing parse trace usually means that endpoint has not been routed through
  the shared parse tracing helper yet, or it has a caller-side injected mapper
  that was intentionally left alone.

## Current Task #180 State

As of the #180 offload work:

- Tasks, Home, Thread, Conversation, Members, Inbox/Saved/Search, ReadReceipt,
  and typed Agents API response parsing have off-UI parse boundaries where the
  parse/map work is pure enough.
- Remaining caller-side parse should be treated case by case, especially when
  the mapper receives bridge loggers, platform modules, or runtime-owned
  dependencies.
- The current model is "host network fetches raw text, shared/KMP parses pure
  endpoint data off the UI/runtime path, shared store commits back on the
  runtime path."

## Future Options

If parsing still shows up in frame or launch traces, the next architecture
choices are:

1. Keep the current raw-body host contract and expand shared/KMP off-UI parse
   coverage.
2. Align `HRSlockNetworkModule` more closely with Kuikly's render thread
   adapter shape where possible, while preserving Slock cancellation, upload,
   raw body, and diagnostics contracts.
3. Add endpoint-specific host/native parser support only for proven hot payloads.
4. Generate host parsers from shared contracts to avoid hand-written platform
   duplication.
5. Build a background repository pipeline for heavy endpoints such as
   `/tasks/server`, producing a normalized snapshot before the UI route needs
   to render it.
6. For dynamic pages, prefer a Module-backed parser/normalizer if the payload is
   large enough that common `Dispatchers.Default` is not a valid execution
   model.

The current approach is the least duplicative shared-code design. It is not the
same thing as parsing in a separate network process; it is parsing on a shared
coroutine background dispatcher after the host network module has returned the
raw response body.

## Recommended Next Optimization

The next optimization should be evidence-driven, not a blind switch to the
built-in `NetworkModule`.

Recommended order:

1. Keep using `HRSlockNetworkModule` for Slock API traffic because it owns the
   Slock-specific raw response, cancellation, upload, auth/header, and
   diagnostics contract.
2. Add or verify host-side diagnostics around the native network worker:
   native task start/end, request id, method/path, bytes read, callback thread,
   and cancellation/drop.
3. Keep endpoint JSON decode/map in shared/KMP when it is pure and can run on
   `Dispatchers.Default`, because this avoids duplicating typed contract logic
   across Android and OHOS.
4. For endpoints that still create visible jank after parse offload, split the
   work further:
   - host/network phase: HTTP and raw body read,
   - shared background phase: pure decode/map/normalize,
   - runtime phase: minimal snapshot commit and recomposition.
5. Only move typed parsing into the host Module for endpoints proven by traces
   to be too large or too dynamic-mode-sensitive for shared background parse.

The architectural target is therefore not "network process parses everything".
It is "every expensive phase has an explicit non-runtime execution boundary,
and the Kuikly runtime receives only the smallest state commit needed to render".
