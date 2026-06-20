---
name: slock-launch-contract
description: Audit, design, or fix Slock shared/KMP route, launch, Kuikly pageData, fallback-cache, realtime, auxiliary-store, Settings, Composer, Home, Thread, Inbox/Search/Saved, and visual fixture boundaries so product state is loaded through MVI stores/repositories instead of being passed as route payload.
---

# Slock Launch Contract

## One Rule

Do not pass product content/state through route, launch, or Kuikly `pageData` payloads. Pass only identity and intent, then let the destination MVI store/repository load canonical state.

## What Can Be Passed

- Identity: server id, channel id, thread stream id, parent channel id, parent message id, object id.
- Intent: selected tab, focused message id, navigation target, draft seed, action request.
- Host context: safe area, time/locale context, local formatting hints.

Do not pass displayed title/subtitle/origin, messages, tasks, files, members, profiles, plans, computers, attachments, suggestions, or composer UI state through production route parsers.

## Candidate Snapshots

In-memory cache, durable local cache, realtime payloads, sparse responses, and legacy fallback/context are candidate snapshots, not automatically canonical state.

- Match candidate snapshots to the current route identity before using them in UI/MVI/cache/subscription/send scope.
- Use identities such as server id, channel id, thread stream id, or parent channel id + parent message id.
- If a candidate does not match, discard it and reload through MVI.
- If it matches, use it only to fill missing data.
- Never let candidate data override canonical store state.
- Do not create new fallback payload interfaces. The end state is no production full content/state payload or fallback display metadata in route/pageData.

## Execution Order

1. Migrate production routes/pages to the rule. Production parsers should accept launch identity/intent only; visual/smoke/test data should move to fixture/provider or direct typed construction.
2. Add candidate-snapshot validation only where legacy/local-cache/realtime/sparse-response inputs still exist.
3. Add structure-based guards last. Use serializer descriptor tests, parser rejection tests, and static checks on known production parser paths. Do not use broad word-grep as a hard gate.

## Scan

Use [scan-patterns.md](references/scan-patterns.md) for discovery commands. Treat those commands as triage only; inspect each hit before deciding it is a violation.

## Report Shape

When reporting an audit or fix, separate:

- canonical production path,
- remaining visual/test/legacy compatibility holes,
- identity/MVI boundary reused,
- guard added to prevent regression.
