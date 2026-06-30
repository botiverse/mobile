# Android Killed-App Push Server Plan (FCM)

Date: 2026-07-01
Status: draft for server/mobile alignment
Related docs:

- `docs/mobile-native-push-notification-analysis-2026-06-25.md`
- `docs/killed-app-launcher-badge-spec-2026-06-24.md`

Source discussions:

- `#native:7450907d` / `db99c81a` and `f5f06005`: killed app/offline push is a platform push entry, not a persistent KMP background process.
- `#native:179028d6` / `cf3545db` and `a464904a`: existing server push is browser Web Push; native needs provider fanout and native token storage.
- `#native:2acb4be0` / `6ea7b394`: owner accepted in-app alive first, killed-app path as a separate spec, with FCM likely first.

Official references:

- Firebase Android receive behavior: <https://firebase.google.com/docs/cloud-messaging/android/receive-messages>
- Firebase HTTP v1 send API: <https://firebase.google.com/docs/cloud-messaging/send-message>
- Android notification runtime permission: <https://developer.android.com/develop/ui/views/notifications/notification-permission>
- Android notification badges: <https://developer.android.com/develop/ui/views/notifications/badges>

## Executive Summary

Android killed-app notification delivery should be implemented as native FCM
device push, not as a long-running background process.

Server-side work is not just adding one endpoint. The current server has a
browser Web Push/VAPID transport:

- `third_party/slock-source/packages/server/src/services/pushService.ts`
- `third_party/slock-source/packages/server/src/routes/push.ts`

That transport stores browser `PushSubscription` fields (`endpoint`,
`keys.p256dh`, `keys.auth`) and sends with `web-push`. Native Android cannot
reuse that subscription model. The server should keep the existing durable
recipient resolution (`buildPushTargets*()` / `sendPushNotifications()`) and add
a provider layer:

```text
message write + mention facts
  -> buildPushTargets*()
  -> PushFanoutService
       -> WebPushProvider        existing VAPID/browser
       -> FcmPushProvider        new Android v1
       -> ApnsPushProvider       future iOS
       -> OhosPushProvider       future HarmonyOS/OHOS
```

## Server Data Model

Add a native push device table. Naming can be `native_push_devices` or
`push_devices`; the important part is keeping it separate from browser
`push_subscriptions`.

Recommended columns:

| Column | Purpose |
|---|---|
| `id` | server UUID |
| `user_id` | authenticated user that owns the device |
| `server_id` | Slock server scope for notification settings and privacy |
| `platform` | `android` |
| `provider` | `fcm` |
| `token_hash` | stable hash for uniqueness and safe logging |
| `token_ciphertext` or `token` | actual FCM registration token; store as secret where possible |
| `device_id` | app-visible device id, if stable |
| `installation_id` | app install id; survives token refresh better than token |
| `app_version` / `app_build` | rollout and debugging |
| `locale` / `timezone` | optional copy/localization routing |
| `notifications_enabled` | app-level opt-in as last reported by native |
| `badge_enabled` | if user separates badge from alert later |
| `last_seen_at` | refreshed on subscribe/token refresh/app resume |
| `revoked_at` | set on logout/revoke instead of hard delete for audit |
| `created_at` / `updated_at` | standard timestamps |

Indexes:

- Unique active device: `(user_id, server_id, platform, provider, installation_id)` where `revoked_at IS NULL`.
- Token cleanup: `(provider, token_hash)`.
- Fanout lookup: `(server_id, user_id, platform, provider, revoked_at, notifications_enabled)`.

Token values must be redacted from logs. Log only `token_hash` / short suffix.

## Server APIs

Add native endpoints alongside, not inside, the existing browser
`/push/subscribe` contract.

### `POST /push/native/subscribe`

Auth: `requireAuth`, `requireVerified`, server membership check.

Request:

```json
{
  "platform": "android",
  "provider": "fcm",
  "token": "fcm-registration-token",
  "deviceId": "optional-native-device-id",
  "installationId": "stable-app-install-id",
  "appVersion": "1.2.3",
  "appBuild": "123",
  "notificationsEnabled": true,
  "badgeEnabled": true
}
```

Behavior:

- Validate `platform=android` and `provider=fcm`.
- Verify `serverId` from header/body is a server where the user is a member.
- Upsert the active row by `(user, server, platform, provider, installationId)`.
- If the same installation reports a new token, revoke or supersede the old row.
- Return `{ ok: true, deviceId: <row id> }`.

### `DELETE /push/native/subscribe`

Use on logout, account switch, notification disable, and token invalidation.

Request can identify by `deviceId`, or by `(platform, provider, token)` if the
client has not stored row id. Prefer row id when available.

Behavior:

- Set `revoked_at`.
- Do not delete immediately; provider failures and audit need history.

### `PATCH /push/native/subscribe/:id`

Use for settings changes:

```json
{
  "notificationsEnabled": false,
  "badgeEnabled": true
}
```

### `POST /push/native/test`

Sends a FCM test payload to the current device/user. This is required because
killed-app push cannot be validated with normal in-app sockets.

## Provider Interface

Refactor `pushService.ts` so `sendPushNotifications(targets)` no longer means
"send browser web push only".

Recommended interface:

```ts
export interface PushProvider<TDevice = unknown> {
  readonly id: "web" | "fcm" | "apns" | "ohos";
  isEnabled(): boolean;
  loadDevices(userIds: string[], serverId: string): Promise<TDevice[]>;
  send(device: TDevice, payload: NativePushPayload): Promise<PushSendResult>;
  revokeInvalidDevice?(device: TDevice, reason: string): Promise<void>;
}
```

Keep web VAPID as `WebPushProvider`. Add `FcmPushProvider` using Firebase HTTP
v1. Do not put FCM-specific fields into message business logic.

## Android Payload Contract

Prefer a FCM **data message** for v1 because Android can route it through the
native `FirebaseMessagingService` and reuse local notification code.

Payload fields:

```json
{
  "type": "slock.message",
  "provider": "fcm",
  "serverId": "server uuid",
  "channelId": "channel uuid",
  "threadId": "thread/channel uuid",
  "parentMessageId": "optional parent id",
  "messageId": "message uuid",
  "category": "dm|mention|channel|thread_reply|task_mention",
  "notificationKey": "server:thread:message",
  "collapseKey": "server:thread",
  "title": "Raft",
  "body": "Message preview",
  "routeUrl": "/s/<server>/channels/<channel>/...",
  "badgeCount": "12",
  "serverSeq": "8099915",
  "createdAt": "2026-07-01T00:00:00.000Z"
}
```

Notes:

- Values are strings because FCM data values are stringly typed.
- `badgeCount` is server authoritative for the user/server scope.
- `serverSeq` or `createdAt` lets the client discard older push payloads.
- `notificationKey` and `collapseKey` should be deterministic. Use them for
  dedupe and provider collapse where supported.

## Recipient and Privacy Rules

Reuse existing target resolution:

- Persist message first.
- Persist mention facts first.
- Build targets from durable DB state, not from websocket delivery.
- Filter sender/self.
- Filter muted server/channel/thread.
- Filter membership and private/joint visibility.
- Respect followed thread rules.
- Respect notification settings.

Server should generate `title` and `body`; clients only display and route. If
privacy mode is enabled later, server sends generic copy such as "New message"
and still includes route identifiers.

## Badge and Unread Count

Android launcher dots are notification-derived on modern Android. Server should
send `badgeCount`, but native display has two layers:

1. The app posts a local notification with `setNumber(badgeCount)` and
   `setShowBadge(true)`.
2. On app resume, shared `SharedHomeStore.appBadgeCount` reconciles and clears
   stale local/headless notifications if needed.

Server must also send count-decrease or zeroing payloads when the user reads
messages on another device, otherwise killed-app badge can remain stale.

Recommended server worker behavior:

- New unread event: push message payload with latest badge count.
- Read/mark-read event: push badge-only payload with latest badge count.
- Logout/revoke: no push; client clears locally during revoke.

## Delivery Failure Handling

FCM provider should detect permanent token failures and revoke devices:

- `UNREGISTERED`
- `INVALID_ARGUMENT` for token format/token invalidity
- sender/project mismatch errors

Transient errors should retry with exponential backoff and bounded attempts.
Do not block message write latency on provider retries; enqueue fanout to a
worker or background job after durable message commit.

## Rollout Plan

1. Add data model and native subscribe/revoke/test APIs.
2. Refactor current `pushService.ts` into provider fanout while preserving web
   push behavior.
3. Add `FcmPushProvider` behind an environment flag:
   - `FCM_PROJECT_ID`
   - `FCM_SERVICE_ACCOUNT_JSON` or service-account credentials path
   - `NATIVE_PUSH_FCM_ENABLED=true`
4. Fanout message targets to both Web Push and FCM.
5. Add badge-only fanout for read/unread count changes.
6. Add server integration tests:
   - native token upsert/revoke
   - web push still sends unchanged payload
   - FCM provider receives same target-resolution result
   - muted/self/non-member filters apply to native
   - stale/invalid token revokes row
7. Client Android implements token registration and message handler.

## Acceptance Criteria

- Existing browser Web Push tests remain green.
- Native subscribe/revoke/test APIs are authenticated, verified, membership
  checked, and do not accept browser PushSubscription payloads.
- `sendPushNotifications()` can fan out to Web Push and FCM without duplicating
  target resolution.
- Android FCM payload contains route fields and authoritative `badgeCount`.
- Invalid FCM tokens are revoked.
- Server logs never print raw FCM tokens.
- Force-stop is documented as a platform hard limit: FCM cannot be promised
  after user force-stops the package until the user opens the app again.

## Open Questions

- Should Android v1 use data-only FCM for all messages, or notification+data for
  lower-latency tray display when the app is backgrounded? The prior native
  direction favors data message so local notification code remains the single
  display layer.
- Should `server_id` be required in `native_push_devices`, or should device
  registration be user-global with fanout filtering by memberships? V1 can use
  server-scoped rows to match current selected-server badge semantics.
- What is the exact unread-count scope for badge: selected server, all joined
  servers, or all visible servers in the mobile session? Prior #81 selected
  server contract says selected-server for in-app alive; killed-app server
  payload should match that unless product changes it.
