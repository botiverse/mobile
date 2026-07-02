# Mobile Native Push Notification Analysis

Date: 2026-06-25

## Trigger

Android Thread realtime investigation showed that relying on local socket
`message:new` delivery for system notifications is not reliable enough: the app
can miss `message:new` events while server state and reply counts continue to
advance. React/web notifications use a different chain and should be the source
of truth for native mobile push eligibility.

## React / Server Chain

Server source: `third_party/slock-source/packages/server/src/services/messageService.ts`

1. All human and agent sends go through `broadcastAndDeliver()`.
2. The message row is persisted with `createMessage()` or agent-send replay.
3. Mention facts are resolved and inserted before delivery:
   `writeMentionFacts()` writes durable `message_mentions` rows.
4. Socket realtime is emitted separately via `emitPersistedMessageToFrontend()`.
   This is the `message:new` path and is not the notification source of truth.
5. Push targets are derived from durable server state via `buildPushTargets()` /
   `buildPushTargetsFromContext()`.
6. `sendPushNotifications()` sends browser Web Push payloads through
   `pushService.ts`.

Web client source:

- `packages/server/src/routes/push.ts`
  - `GET /push/vapid-key`
  - `POST /push/subscribe`
  - `DELETE /push/subscribe`
  - `POST /push/test`
  - `POST /push/prompt-events`
- `packages/server/src/services/pushService.ts`
  - stores browser `PushSubscription` rows in `push_subscriptions`
  - sends VAPID Web Push payloads
- `packages/web/public/sw.js`
  - receives the browser `push` event
  - suppresses notification if the same target page is visible
  - opens `/s/:server/...` URLs from notification clicks

The React/web chain is therefore durable enough because push target resolution
happens after database write and mention facts, not because the browser received
the socket event.

## Push Eligibility Semantics

The current server tests in `messageService.test.ts` encode the intended shapes:

- DM messages push to the other human DM participants, excluding the sender.
- Channel/private/joint messages push to joined humans, excluding the sender.
- Channel mentions use a mention-specific body. Public-channel structured
  mentions can also notify visible non-joined humans.
- Thread replies push only to followed human users with active follows, excluding
  the sender.
- Server mute removes recipients through `getServerPushMutedUserIds()`.
- Push payload shape is:
  - `title`
  - `body`
  - `tag`
  - `url`
  - optional `alwaysShow`

The manual currently describes coverage as "DMs, direct mentions, and followed
thread replies", while `buildPushTargetsFromContext()` also notifies normal
joined channel/private/joint messages. That mismatch should be resolved before
native UI copy promises a narrower or broader contract.

## Current Android Chain

Android source:

- `AndroidAppScopeRealtimeOwner.observeEvents`
- `maybePostBackgroundUnreadNotification(MessageNew)`
- `buildSystemUnreadNotificationRequest()`
- `AndroidUnreadNotificationPoster.postUnreadNotification()`

Current behavior:

1. Native notification is local-only.
2. It is triggered only by `MessageNew` from the socket.
3. It skips when the app is foreground, notifications are disabled, or any
   realtime consumers are attached.
4. It does not consume server `PushPayload`.
5. It has no native push subscription or device-token registration.

This means Android notification delivery has the same loss mode as realtime
UI: if `message:new` is not delivered to the device, Android cannot know to post
the notification.

## Why The Browser Web Push Endpoint Cannot Be Directly Reused

The existing `/push/subscribe` endpoint accepts browser Web Push
`PushSubscription` fields:

- `endpoint`
- `keys.p256dh`
- `keys.auth`

A native Android app does not normally have a browser service worker
`PushSubscription`. Android native push needs a platform transport such as FCM,
or a Slock-owned polling/sync fallback. Reusing VAPID directly would require
embedding a WebView/PWA service-worker environment, which would make native
notification behavior depend on browser internals and is not a good app-layer
contract.

## Recommended Native V1

Implement a native push transport that reuses the same server-side target
resolution:

1. Server:
   - Add a native/mobile push subscription table keyed by user and device.
   - Add endpoints such as:
     - `POST /api/push/native/subscribe`
     - `DELETE /api/push/native/subscribe`
     - `POST /api/push/native/test`
   - Store FCM token or another native transport token.
   - In `broadcastAndDeliver()`, after `buildPushTargets()` produces the same
     userId → `PushPayload` map, call a native sender with the same payload.
   - Keep browser Web Push and native push as two transports behind the same
     target-resolution result.
2. Android:
   - Add native push dependency and service (`FirebaseMessagingService` if FCM
     is chosen).
   - Register/delete token with the new native endpoints after login/logout and
     token refresh.
   - Parse server `PushPayload` into an Android notification request.
   - Map server `url` or explicit route fields to existing
     `SlockKuiklyActivity.buildUnreadNotificationThreadPageData()`.
   - Keep `AndroidUnreadNotificationPoster` as the final local posting layer.
3. Settings:
   - Keep local Android notification permission/preference.
   - Keep server mute wired to `/servers/:id/notification-settings`.
   - Replace browser-specific VAPID subscribe/test calls for native app with the
     native endpoints.

## Interim Fallback If Native Push Backend Is Not Ready

If backend/native push transport is not available yet, Android can reduce missed
notifications but cannot fully match server push:

- On background realtime gap detection or `thread:updated` with newer unread
  state, call `messageSync` / Inbox unread APIs and post notifications from
  fetched durable messages.
- Periodically refresh Inbox/unread while the foreground keepalive is running.
- This still depends on the Android process being alive and is not a replacement
  for server push when the OS kills the app.

## Integration Decision

Do not present Android's current socket-derived local notification path as the
React/server push chain. The correct native integration boundary is:

`messageService.buildPushTargets*()` result → native push transport → Android
local notification poster.

Until the server exposes a native push subscription/delivery transport, Android
can only implement a best-effort local fallback.

## Platform Server Contracts

Follow-up platform-specific server contracts:

- `docs/mobile-native-push-server-requirements-2026-07-02.md`
- `docs/mobile-killed-app-push-android-server-contract-2026-07-01.md`
- `docs/mobile-killed-app-push-ios-server-contract-2026-07-01.md`
- `docs/mobile-killed-app-push-ohos-server-contract-2026-07-01.md`
