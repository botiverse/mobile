# OHOS Killed-App Push Server Contract

Date: 2026-07-01

## Background

This document records the HarmonyOS / OHOS side of the killed-app offline push
contract requested in `#raft-mobile-reconcile:ad844ed9`.

Prior research concluded that killed-app delivery cannot rely on Kuikly shared
runtime, Socket.IO, or a normal always-running background process. OHOS needs a
native push provider path, equivalent in role to Android FCM and iOS APNs, with
app-resume reconciliation through canonical Slock APIs.

The existing cross-platform analysis is
`docs/mobile-native-push-notification-analysis-2026-06-25.md`.

## Current Server Baseline

Current server push is browser Web Push:

- target resolution: `third_party/slock-source/packages/server/src/services/messageService.ts`
- sender: `third_party/slock-source/packages/server/src/services/pushService.ts`
- web routes: `third_party/slock-source/packages/server/src/routes/push.ts`
- browser table: `push_subscriptions`

OHOS must reuse target resolution but needs a separate native provider and
token/device storage. Do not reuse browser `PushSubscription` storage.

## Provider Strategy

Treat OHOS as a provider behind the same native push abstraction:

```text
PushProvider
  WebPushProvider
  FcmPushProvider
  ApnsPushProvider
  OhosPushProvider
```

The exact provider implementation depends on the deployment target:

- Huawei/HarmonyOS Push Kit for devices in Huawei Mobile Services ecosystems.
- OpenHarmony/OHOS notification push capability if the distribution target uses
  an OpenHarmony-compatible push service.
- A future vendor-specific provider if app distribution requires it.

The important server boundary is provider abstraction, not a hardcoded FCM or
APNs branch in message business logic.

## Native Device Registration

Add OHOS rows to the same native-device storage family.

Suggested table fields:

```text
native_push_devices
  id
  user_id
  platform              -- "ohos"
  provider              -- "huawei-push" | "ohos-push" | vendor name
  device_id             -- stable install/device id
  token_hash
  encrypted_token       -- OHOS/provider push token
  bundle_name           -- current OHOS bundle name, expected build.raft.mobile
  app_version
  os_version
  device_model
  locale
  timezone
  enabled
  last_seen_at
  revoked_at
  created_at
  updated_at
```

If the provider token is app-id scoped, store provider app id / project id in
server configuration, not in client-controlled payload fields.

## API Contract

Use the shared native push API family:

```http
POST /api/push/native/devices
DELETE /api/push/native/devices/:deviceId
PATCH /api/push/native/devices/:deviceId
POST /api/push/native/test
```

`POST /api/push/native/devices` request:

```json
{
  "platform": "ohos",
  "provider": "huawei-push",
  "deviceId": "stable-install-id",
  "token": "ohos-provider-token",
  "bundleName": "build.raft.mobile",
  "appVersion": "1.2.3",
  "osVersion": "5.0",
  "deviceModel": "OHOS Device",
  "serverIds": ["65598229-cd0a-4e92-96f3-c33e7e2e4cc1"],
  "enabled": true
}
```

Semantics:

- idempotent register/refresh
- revoke on logout, account switch, notification disabled, or token invalid
- do not log raw provider tokens
- platform/provider are explicit so the same route can support multiple OHOS
  distribution channels

## OHOS Payload Contract

The provider payload should carry the same Slock route identity as Android/iOS.

Required logical fields:

```json
{
  "type": "message",
  "serverId": "server-id",
  "channelId": "channel-id",
  "threadId": "thread-or-channel-stream-id",
  "parentMessageId": "optional-parent-message-id",
  "messageId": "message-id",
  "category": "dm|mention|channel|thread_reply|task_mention",
  "notificationKey": "server:channel-or-thread:category",
  "title": "Raft",
  "body": "Message preview",
  "url": "/s/server-slug/c/channel-or-thread",
  "badgeCount": "12",
  "serverSeq": "12345"
}
```

Provider-specific wire shape may wrap these fields as `data`, `intent`, or
notification extras. The server-side contract is that all route ids remain
present and the native OHOS host can reconstruct the same app launch intent
without relying on displayed text.

## Badge / Red Dot Semantics

OHOS badge behavior is provider and launcher dependent. Server should still
send an authoritative unread count because:

- some OHOS/Huawei launchers can display a badge/count through provider
  notification metadata
- notification center grouping still needs the same count
- app resume needs the same count to reconcile local UI

As with Android and iOS, product must choose global app unread vs selected-server
unread. Global app unread is the safer default for OS-level app icon badges.

## Provider Requirements

`OhosPushProvider` must handle:

- provider credentials outside client-controlled config
- token invalid / unregistered responses -> revoke token
- provider throttling / transient failure -> retry with backoff
- provider-specific payload size limits
- per-provider environment separation if needed
- metrics by provider and platform

Message send must not block on OHOS provider success. Push is a delivery side
effect after durable message write.

## Client Boundary

OHOS host responsibilities:

- request / reflect system notification permission
- obtain provider push token through native ability/module
- register/revoke token through native push API
- receive provider push and post system notification
- route notification click by ids into the Kuikly app shell
- refresh unread/thread state on resume

Shared/KMP responsibilities:

- provide route identity parsing and post-resume reconciliation
- never assume Kuikly shared runtime runs while the app is killed

Server responsibilities:

- calculate recipients once through `buildPushTargets*()`
- send payload through `OhosPushProvider`
- own token lifecycle and provider feedback

## Acceptance Criteria

Server V1 is ready for OHOS killed-app push when:

1. OHOS tokens are stored in native-device storage, not browser
   `push_subscriptions`.
2. Provider is selected through `platform + provider`, not hardcoded in message
   business logic.
3. The same `buildPushTargets*()` output fans out to OHOS provider.
4. A test route can send a notification payload to an OHOS device/emulator or
   provider sandbox.
5. Notification click can launch the app by `serverId/channelId/threadId/messageId`.
6. Invalid tokens are revoked and raw tokens are redacted in logs.
7. App resume refreshes canonical unread state and corrects stale badge/local
   notification state.
