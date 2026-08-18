# Raft launch-contract scan patterns

Run from the Mobile repository root. These commands discover candidates; inspect every hit before calling it a violation.

## Production route and pageData ingress

```bash
rg -n "parse[A-Za-z0-9_]*(Payload|PageData)|pageData|fallbackPageData|LaunchPayload|OpenRequest|AuthPendingOpenRequest" \
  compose/shared/src/commonMain/kotlin/build/raft/app -S
```

Keep API response parsers out of scope unless the same DTO is also accepted from route/pageData/host launch.

## Content-bearing launch fields

```bash
rg -n "val (title|subtitle|preview|sender|messages|tasks|members|profile|files|attachments|suggestions|composer)[A-Za-z0-9_]*:" \
  compose/shared/src/commonMain/kotlin/build/raft/app -S
```

Fields are suspicious only inside route/open/auth-resume/pageData DTOs. They are normal in canonical store state and explicit fixtures.

## Current typed launch files

```bash
rg -n "Wire.*LaunchPayload|ThreadLaunchConversationIdentity|ThreadLaunchProvenance|SettingsStandalone|ExternalShareLaunch|ShellStoreLaunch" \
  compose/shared/src/commonMain/kotlin/build/raft/app -S
```

Review paired server/account identity, typed conversation kind, parent identity and fail-closed behavior.

## Candidate snapshots and fallback

```bash
rg -n "fallback|cached|snapshot|launchContext|originFallback|recovery|resume|writeThrough|Realtime|realtimeJoin" \
  compose/shared/src/commonMain/kotlin/build/raft/app -S
```

For each hit identify producer, identity/generation fence, merge direction and retirement path. Matched candidates may fill missing state; they do not overwrite canonical store state.

## Derived scopes

```bash
rg -n "sourceThreadId|streamChannelId|parentChannelId|parentMessageId|serverId|accountPrincipalId|ViewModel\\(key|scope =|sendChannelId|realtimeJoin" \
  compose/shared/src/commonMain/kotlin/build/raft/app -S
```

Check that ViewModel, cache, auxiliary store, realtime and send scopes all derive from one resolved identity rather than raw route hints.

## Structure-based guards

Prefer:

- `Wire...LaunchPayload.serializer().descriptor` assertions;
- parser tests for minimal identity/intent and hostile full-content input;
- focused static checks on a named production parser or host launch adapter;
- mutation tests proving wrong identity/kind/generation is rejected.

Avoid broad repository word-grep as a required CI gate: model and fixture files legitimately contain product fields.
