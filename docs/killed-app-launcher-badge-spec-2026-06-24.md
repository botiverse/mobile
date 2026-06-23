# Killed-App Launcher Badge Spec

> Status: **draft** — awaiting owner (@artin) + @Cindy sign-off before implementation
> Author: @API-Integration-Developer
> Date: 2026-06-24
> Related: #81 (typed `AppBadgeCount` + `bridge.appBadge(count, source, isStale)` contract, **in-app alive**), #86 (badge research, killed-app process discussion)

## 0. Background

#81 wired shared `SharedHomeStore.appBadgeCount` → `bridge.appBadge(count, source, isStale)` contract.
**The contract is consumed only while the app process is alive.** When the user kills the app (swipe-from-recents / force-stop / reboot), the process dies and the badge is no longer maintained.

The user's question: "用户都退出了，怎么更新红点呢" (#86 thread, 2026-06-17). This spec compares the three viable Android options for keeping the launcher icon badge accurate when the app process is dead, and recommends a path.

This spec is **scope-only** — no implementation in this task. Implementation tasks open as follow-ups after owner sign-off.

## 1. Goals & non-goals

### Goals

1. When the user is signed in to a Slock server, the launcher icon badge reflects the current unread count **without requiring the app to be in the foreground or background-alive**.
2. The count must reconcile with server truth on app resume (no stale/duplicated numbers).
3. The count must clear when the user signs out (no orphan badges after sign-out).
4. The implementation must respect the #81 contract: host only consumes `count`, `source` / `isStale` are diagnostics-only.

### Non-goals (explicit)

- **iOS / OHOS killed-app parity.** This spec is Android only. OHOS push and iOS APNs have their own process models and will get separate specs.
- **Replacing the in-app alive path.** #81 in-app alive is the primary source; killed-app is a *fallback to push the count forward when the process is dead*. On app resume, the in-app alive path takes over and reconciles.
- **Per-channel unread breakdown in the badge.** Badge shows a single total. Per-channel detail is the in-app Home screen.
- **Custom launcher icon overlays beyond what Android's notification-dot system provides.** OEM-specific badge number overlays (Xiaomi/华为/Samsung numbers) are out of scope for v1 (see §5).
- **Server push re-architecture.** This spec assumes the server already has the ability to send pushes; it does not redesign push routing. The server-side counterpart must be tracked separately (likely a server task, not an Android task).

## 2. Threat model — what "killed app" really means on Android

There are several distinct "killed" states with very different capabilities. The implementation must handle all of them correctly.

| State | Process | What can update the badge |
|---|---|---|
| **App in foreground** | alive | in-app alive path (#81) — host reads `bridge.appBadge` |
| **App in background, process alive** | alive | in-app alive path continues; socket stays connected if KeepAlive is on |
| **App in background, process killed by system** | dead | must rely on `FirebaseMessagingService` (FMS) `onMessageReceived` |
| **App force-stopped by user** | dead + **user-restricted** | no callbacks run, no FCM data messages, only system tray notifications from notification-type FCM. The user must manually re-open the app. **This state is fundamentally not updatable by the app.** |
| **App killed by reboot** | dead | FCM token may be reset; on first boot, the system re-registers the token with FCM, but the badge stays at 0 until either FCM delivers a push or the user opens the app |

**Key insight:** force-stop is a hard limit set by the user. No background work runs until the user re-opens. The spec must accept this and not promise more than the platform allows.

## 3. Three implementation options

### Option A: FCM data message with badge payload (RECOMMENDED)

**Mechanism:**
1. App registers an FCM token at sign-in (server stores it against the user/device).
2. Server tracks per-user unread count. Whenever the count changes (new message arrives, message read, mention, etc.), the server sends an FCM **data message** (not notification message) with payload `{"type": "badge", "count": <int>, "source": <enum>, "ts": <ms>}` to the device.
3. App's `FirebaseMessagingService.onMessageReceived` runs even when the app process is dead (as long as force-stop is not in effect — see §2). It calls the existing `bridge.appBadge(count, source, isStale)` host consumer to update the launcher icon.
4. On app resume, the in-app alive path (#81) reads the latest server count and reconciles, overriding whatever the FCM path set.

**Badge rendering on Android 8+:**
- The FCM `onMessageReceived` handler posts a `NotificationCompat.Builder` notification with `setNumber(count)` to a "headless" channel.
- Android automatically shows the launcher icon dot with the count, derived from the visible notifications on that channel.
- The user can dismiss the headless notification → badge decrements / clears.
- **Important:** the system tray shows these notifications, which is user-visible. Two mitigations:
  - **Set the channel importance to `IMPORTANCE_MIN`** so the notification is silent and shows no heads-up. The badge dot still appears.
  - **Use a dedicated channel with `setShowBadge(true)`** so the dot is controlled; user can long-press the app icon → app info → notifications to disable.
  - See Android docs: [notification badges](https://developer.android.com/develop/ui/views/notifications/badges).

**Pros:**
- Industry standard; libraries mature; documentation abundant.
- Works without OEM-specific code.
- Same code path for in-app alive + killed-app: both call `bridge.appBadge` host consumer.
- Free piggyback on FCM for any future "real push notification" feature.
- Server already needs a push channel eventually; FCM is the easiest on-ramp.

**Cons:**
- Adds Firebase Cloud Messaging dependency to the Android app (currently absent — confirmed: no `firebase-messaging` in `androidApp/build.gradle.kts`).
- Requires a Google Services Gradle plugin and `google-services.json`.
- Adds a server-side task (FCM token registration, push routing on count change).
- The user-visible system-tray notification (even at `IMPORTANCE_MIN`) is a UX consideration — must coordinate with @Product-UX-Designer for the user notification policy.
- Requires runtime permission: `POST_NOTIFICATIONS` on Android 13+ (already handled — the app has notification permission flow).
- If the user disables notifications for the app in system settings, the badge disappears. This is platform behavior, not a bug.

### Option B: Foreground service + WorkManager periodic refresh (NOT RECOMMENDED for v1)

**Mechanism:**
1. App starts a foreground service on sign-in that maintains a long-lived socket to the server and periodically polls the unread count.
2. WorkManager schedules a periodic worker (minimum 15 min interval by Android policy) to wake the process, fetch unread, update the badge, then return.
3. On Android 8+, foreground services must show a persistent notification — user-visible.

**Pros:**
- No external service (FCM) needed.
- Full control over the badge state without depending on Google's service.
- Works without notification permission (badge from the service, not from a posted notification).

**Cons:**
- **Battery cost.** Persistent foreground service with a long-lived socket drains battery. This is the exact reason Android 8+ restricted background services. Users complain.
- The persistent notification is a UX burden — Slock would show "Slock is running" in the tray at all times, which conflicts with our lightweight chat-app design.
- WorkManager minimum interval is 15 minutes — too coarse for badge updates. New messages can sit unnoticed for 15 min.
- Google Play Store policy scrutinizes foreground-service usage; some categories are restricted.
- OEM battery savers aggressively kill background services on Xiaomi/Huawei/Oppo. This makes the badge unreliable — exactly the problem we're trying to solve.

**Verdict:** Not viable. Foreground services for badge updates are an anti-pattern on modern Android.

### Option C: OEM-specific badge APIs (RECOMMENDED for v2, layered on top of Option A)

**Mechanism:**
- After Option A is shipped, layer OEM-specific badge calls for the launchers that don't render notification dots well (older Samsung, some Xiaomi launchers, Vivo, etc.).
- Libraries like [ShortcutBadger](https://github.com/leolin310148/ShortcutBadger) (and its forks like ShortcutBadgerX) wrap the OEM-specific intents (`com.sec.badge.*`, `com.huawei.android.launcher.*`, etc.).

**Pros:**
- Improves badge fidelity on OEMs that under-render the system notification dot.

**Cons:**
- Adds a third-party dependency that wraps dozens of brittle OEM intents.
- Each new Android OEM version may break the OEM intent.
- Maintenance burden is high.

**Verdict:** v2 only. Not in scope for v1.

## 4. Recommended path

**Adopt Option A (FCM data message + `bridge.appBadge` host consumer) for v1.**

**Sequence:**

1. **Server-side (separate task, owned by backend):**
   - Add FCM device token registration endpoint (`POST /devices/fcm-token` or extend existing device registration).
   - Track per-user-per-server unread count on the server.
   - On unread count change, send FCM data message to all of the user's registered devices for that server.
   - Payload: `{"type": "badge", "count": <int>, "source": "unread_summary", "ts": <ms>}`.

2. **Android side (this team):**
   - Add Firebase Cloud Messaging dependency: `firebase-messaging-ktx` + `play-services-base` + Google Services Gradle plugin.
   - Add `google-services.json` (provided by owner from Firebase console; not in repo).
   - Implement `SlockFcmService : FirebaseMessagingService`:
     - `onNewToken(token)` → upload to server.
     - `onMessageReceived(message)` → extract `type=badge` payload → call `bridge.appBadge(count, source, isStale=false)`.
   - Add a headless notification channel `slock_badge_channel` with `IMPORTANCE_MIN` and `setShowBadge(true)`.
   - In `onMessageReceived`, post a `NotificationCompat.Builder` with `setNumber(count)` to that channel. The system renders the launcher icon dot automatically.
   - On app resume / `MainActivity.onResume`, clear the headless channel's notifications so the tray doesn't accumulate ghosts. The in-app alive path takes over.

3. **Reconcile on resume:**
   - On `onResume`, the in-app alive path (#81) reads `SharedHomeStore.appBadgeCount` (which has the latest from `GET /me/unread`).
   - The host reconciles: clear the headless notification and update the launcher dot to match.

4. **Sign-out cleanup:**
   - On sign-out, call `FirebaseMessaging.getInstance().deleteToken()` to unregister the device from FCM, and clear `slock_badge_channel` notifications.
   - The server-side token cleanup is the backend task.

5. **iOS / OHOS parity:** separate specs.

## 5. OEM-specific badge overlay (v2, deferred)

After Option A ships and we have a baseline, layer ShortcutBadger (or write our own OEM intent wrapper) for the OEM launchers that don't render the system notification dot. **This is a v2 task and explicitly out of scope for v1.**

## 6. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| User force-stops the app | Badge freezes; no updates until user re-opens | Document this as a platform limitation. Educate in app settings: "If badge stops updating, reopen the app." |
| User disables notifications in system settings | Badge disappears | Same — platform behavior, document. |
| Battery / data cost of FCM data messages | Marginal — FCM is designed for low-overhead | Standard FCM quota applies; document expected volume (1 push per unread change event, with server-side debouncing). |
| FCM token rotation | New device token must be uploaded to server | `onNewToken` callback handles this; tested in unit test. |
| Server-side push routing race (count goes up, then down, FCM delivers out of order) | Badge shows stale higher count | Server timestamps each push; client stores last-applied `{count, ts}` and discards older. **This is a small state machine: spec it in the implementation task.** |
| Headless channel notification accumulates in tray | User sees ghost notifications | Clear channel on `onResume` and on sign-out. |
| `google-services.json` is project-specific | Can't commit to repo | Owner provisions; `google-services.json` is in `.gitignore` or in a secrets path. Document the bootstrap. |
| FCM data-only messages and force-stop interaction | Android delivers data messages to `onMessageReceived` even when app is killed *unless* force-stopped | Document force-stop limitation (§2). |

## 7. Acceptance criteria for v1

1. App registers FCM token at sign-in and unregisters at sign-out.
2. With app **force-stopped**, after server sends an FCM data message, the launcher icon shows the badge with the correct number. (Manual test on a real device.)
3. With app **in background (process alive)**, the in-app alive path updates the badge without depending on FCM.
4. With app **in foreground**, the in-app alive path is the source of truth; FCM updates are ignored if older.
5. On app **resume**, the headless channel is cleared and the badge is reconciled to the in-app count.
6. **Type-safety contract preserved:** the new FCM consumer calls `bridge.appBadge(count, source, isStale)` — no new fields added to the #81 contract. The data layer (`SharedHomeStore.appBadgeCount`) is not modified.
7. **No new dependencies outside the standard set:** only `firebase-messaging-ktx` + `play-services-base` + Google Services Gradle plugin.
8. **No server changes required for the in-app alive path** (it's already wired via #81).

## 8. Out of scope (explicit)

- iOS / OHOS killed-app parity (separate specs).
- OEM-specific badge overlays (ShortcutBadger-style) — v2.
- Server-side FCM push routing (backend task, this spec assumes it exists or is built in parallel).
- Per-channel badge breakdown (Home screen handles this).
- Migrating existing system-tray `postUnreadNotification` to use the badge channel — they're complementary, not replacement. The system tray already posts per-message notifications on `setShowBadge(true)` channel; the new badge channel is a separate, headless counter.
- FCM topic-based push (per-server / per-channel topics) — not needed for v1; per-device FCM is sufficient.
- "Badge pulse animation" — Android doesn't have one for launcher dots.

## 9. Estimated effort (post-sign-off)

| Task | Owner | Estimate |
|---|---|---|
| Android `SlockFcmService` + `slock_badge_channel` + Gradle wiring | @API-Integration-Developer | 0.5 day |
| In-app reconcile on resume + sign-out cleanup | @API-Integration-Developer | 0.25 day |
| Unit tests (token registration, payload parsing, onResume reconcile) | @API-Integration-Developer | 0.5 day |
| Manual test on real device (3 OEMs: stock Android, Samsung, Xiaomi) | @QA-Tester | 0.5 day |
| Server-side FCM token registration + push routing | backend (separate task) | TBD |

**Total Android-side:** ~1.25 days.

## 10. Open questions for owner

1. **Server-side readiness:** Is the FCM token endpoint + push routing already planned, or does this spec need to trigger a backend task? If the latter, please tag the backend owner.
2. **User notification policy:** When the headless badge notification is posted, even at `IMPORTANCE_MIN`, the user sees a small icon in the status bar. Is this acceptable? @Product-UX-Designer should confirm with a prototype.
3. **`google-services.json` provisioning:** Who owns the Firebase project? @artin to provision and provide the JSON.
4. **Privacy / data flow:** FCM implies Google receives the device token and (for some message types) message content. We're using **data messages only** (not notification messages), so Google does not see message content — only the badge count. Confirm this is acceptable per the project's privacy posture.
5. **Force-stop limitation:** Do we document this in the app's FAQ / onboarding, or silently accept it?
6. **iOS / OHOS parity timing:** When do we want parallel specs for those platforms? After v1 ships, or in parallel?
7. **OEM badge overlay (v2):** Is ShortcutBadger acceptable, or do we want to write our own OEM intent wrapper to avoid the third-party dependency?
