# KMP Network Threading Model

Scope: Slock Kuikly/KMP shared UI, shared data sources, and host network
modules on Android and OHOS.

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
  `SlockKuiklyThreadAdapter.execute(...)`, then OkHttp executes synchronously on
  that worker.
- OHOS host networking runs through an async ArkTS `http.request(...)` path.
- Shared/KMP response decode/map can hop to `Dispatchers.Default` after the host
  module returns `bodyText` / `errorBodyText`.

The remote API server is a separate process somewhere else, but client-side
HTTP body parsing does not currently happen in a separate app process.

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

1. `SlockRequestScheduler.run(...)`
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
request inside `SlockKuiklyThreadAdapter.execute("network-request-...")`.

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
2. Add endpoint-specific host/native parser support only for proven hot payloads.
3. Generate host parsers from shared contracts to avoid hand-written platform
   duplication.
4. Build a background repository pipeline for heavy endpoints such as
   `/tasks/server`, producing a normalized snapshot before the UI route needs
   to render it.

The current approach is the least duplicative shared-code design. It is not the
same thing as parsing in a separate network process; it is parsing on a shared
coroutine background dispatcher after the host network module has returned the
raw response body.
