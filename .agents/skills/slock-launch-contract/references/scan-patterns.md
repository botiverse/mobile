# Scan Patterns

Run from the Slock repo root.

## Route And PageData Parsers

```bash
rg -n "parse[A-Za-z0-9_]*(Payload|PageData)|decodeFromString\\(|pageData|fallbackPageData|LaunchPayload|ScreenPayload|ContentPayload|SmokePayload" compose/shared/src/commonMain/kotlin -S
```

Review only decoders that ingest Kuikly `pageData`, route params, or host-provided payloads. Ordinary API response parsers are not launch-contract issues unless they also feed fallback metadata.

## Full Content Fields In Launch DTOs

```bash
rg -n "val (messages|tasks|members|memberCandidates|filesPayload|composer|plans|computers|profile|agents|workspaces|attachments|suggestions):" compose/shared/src/commonMain/kotlin -S
```

Use this only for discovery. These fields are suspicious in `Wire...LaunchPayload` or production pageData decoders, but normal in canonical store state and explicit fixture/smoke data. Do not use broad field-name grep as a hard gate.

## Candidate Snapshot Inputs

```bash
rg -n "fallback(Header|Parent|Message|Payload|Metadata)|withTitleFallback|cachedPayload|cachePayload|writeThreadSnapshot|withResolvedReplyThreadMetadata" compose/shared/src/commonMain/kotlin -S
```

These hits should trend down over time. Cache, realtime, sparse-response, and legacy fallback/context data are candidate snapshots. They must be matched to the current route identity before entering UI/MVI/cache/subscription/send scope. Unmatched candidates are discarded and reloaded through MVI; matched candidates may only fill missing data and must not override canonical store state.

## Derived Scopes

```bash
rg -n "sourceThreadId|streamChannelId|parentChannelId|parentMessageId|resolvedAuxiliaryThreadId|realtimeJoin|scope = SharedTasksScope|sendChannelId|ViewModel\\(key" compose/shared/src/commonMain/kotlin/ai/slock/compose -S
```

Check whether scopes are based on initial route hints or resolved canonical identity.

## Structure-Based Guards

Prefer these over broad word scans:

- `Wire...LaunchPayload.serializer().descriptor` tests that assert launch DTOs exclude content fields.
- Parser tests that prove production pageData accepts identity/intent and rejects or ignores full content payloads.
- Narrow static checks on known production parser files, for example guarding against `decodeFromString(KuiklyThreadScreenPayload.serializer())` in `parseThreadScreenPayload()`.

## Thread-Specific Hotspots

```bash
sed -n '3140,3195p' compose/shared/src/commonMain/kotlin/ai/slock/compose/ThreadPage.kt
sed -n '1240,1710p' compose/shared/src/commonMain/kotlin/ai/slock/compose/ThreadPage.kt
sed -n '190,255p' compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ComposeLocalWriteThrough.kt
sed -n '510,605p' compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ComposeLocalWriteThrough.kt
```

As of 2026-06-20, the known strict-consistency hole was `parseThreadScreenPayload()` falling back from `WireThreadLaunchPayload` to full `KuiklyThreadScreenPayload`.
