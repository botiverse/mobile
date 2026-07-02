# Mobile WebView Server Contract

Date: 2026-07-02

Status: Draft for server review only. Do not start implementation from this
document until the server/API owners comment on the approach.

## Background

Mobile Settings is trialing Plan & Billing by hosting the existing React billing
surface in a Kuikly-backed native WebView. The mobile client-side trial is in:

- `9cde796a feat(webview): add controllable kuikly webview surface`
- `859688d7 feat(settings): show billing in webview`

The trial currently loads the React route:

```text
/s/{serverSlug}/settings/billing
```

and writes the mobile `StoredAuthSession` tokens into WebView `localStorage`
before React boots:

```text
slock_access_token
slock_refresh_token
slock_last_server_slug
```

This is good enough to validate rendering and navigation shape, but it should
not be the long-term server/client contract. This document extracts the server
requirements for a production-safe mobile WebView session bootstrap.

## Goals

1. Let mobile host selected existing React surfaces, starting with Plan &
   Billing, without reimplementing complex billing UX in Kuikly/KMP.
2. Avoid writing long-lived mobile refresh tokens into WebView `localStorage`.
3. Keep React billing behavior server-authoritative and reuse the existing web
   routes/API behavior.
4. Give server code a clear, auditable way to create, consume, expire, and
   revoke mobile WebView sessions.
5. Make Stripe checkout/portal return behavior explicit before mobile starts
   relying on it.

## Non-Goals

- Do not move billing business logic into mobile.
- Do not create a separate mobile-only billing API for plan display unless the
  server decides WebView is not acceptable.
- Do not use React Native JSI as the WebView bridge. WebView JavaScript runs in
  the browser/WebView engine, not in the app's native/KMP runtime.
- Do not start native push or notification work from this document. Native push
  server contracts are covered separately by:
  - `docs/mobile-killed-app-push-android-server-contract-2026-07-01.md`
  - `docs/mobile-killed-app-push-ios-server-contract-2026-07-01.md`
  - `docs/mobile-killed-app-push-ohos-server-contract-2026-07-01.md`

## Current Web Baseline

React billing is implemented in:

```text
third_party/slock-source/packages/web/src/components/settings/SettingsPanel.tsx
```

The billing tab uses existing web auth state and server APIs, including:

- `GET /billing/subscription`
- `POST /billing/checkout`
- `POST /billing/portal`
- `POST /billing/seat-pack-quantity`
- `POST /billing/cancel`

The web app currently stores tokens in browser storage and injects auth headers
from:

```text
third_party/slock-source/packages/web/src/store/authStore.ts
third_party/slock-source/packages/web/src/api/client.ts
```

Mobile should not permanently depend on writing raw mobile refresh tokens into
that browser storage.

## Proposed Server Contract

Add a short-lived mobile WebView bootstrap flow.

### 1. Create a WebView Session Code

Mobile calls a server endpoint using normal mobile API auth:

```http
POST /api/mobile-webview/sessions
Authorization: Bearer {mobileAccessToken}
X-Server-Id: {serverId}
Content-Type: application/json
```

Request:

```json
{
  "surface": "settings.billing",
  "serverId": "server-id",
  "returnPath": "/s/server-slug/settings/billing",
  "platform": "android|ios|ohos",
  "appVersion": "1.2.3",
  "deviceId": "stable-install-id"
}
```

Response:

```json
{
  "bootstrapUrl": "https://raft.build/mobile-webview/bootstrap?code=opaque-code",
  "expiresAt": "2026-07-02T06:35:00.000Z"
}
```

Server semantics:

- `code` is opaque, one-time-use, and short-lived. Suggested TTL: 60-180 seconds.
- Code is bound to:
  - authenticated user id
  - target server id
  - requested surface
  - return path
  - optional device id / platform for audit
- Code must not be reusable after successful bootstrap.
- Code must be invalidated on logout/session revoke where practical.
- Server logs must redact the raw code.

### 2. Bootstrap Web Auth

WebView loads:

```http
GET /mobile-webview/bootstrap?code=opaque-code
```

Server validates the code and then establishes a WebView-scoped web session.

Preferred response:

```http
302 Found
Set-Cookie: slock_webview_session=...; HttpOnly; Secure; SameSite=Lax; Path=/
Location: /s/{serverSlug}/settings/billing
```

Alternative, if existing React auth cannot be moved to cookie-backed auth in
one step:

```html
<!doctype html>
<script>
  localStorage.setItem("slock_access_token", "{shortWebAccessToken}");
  localStorage.setItem("slock_refresh_token", "{shortWebRefreshToken}");
  localStorage.setItem("slock_last_server_slug", "{serverSlug}");
  location.replace("/s/{serverSlug}/settings/billing");
</script>
```

If the token alternative is used, the returned tokens should be WebView-scoped,
shorter-lived than the mobile refresh token, and revocable independently.

### 3. React App Session Detection

Server/web owners should choose one of these implementation paths:

#### Path A: Cookie-backed WebView session

- Add a WebView bootstrap cookie.
- Teach web API auth middleware to accept this cookie for WebView sessions.
- Keep React `api/client.ts` compatible with cookie auth for this surface.
- Best security shape because refresh material stays HttpOnly.

#### Path B: Short WebView tokens in existing localStorage contract

- Bootstrap endpoint mints short-lived WebView tokens.
- Existing React auth store continues to use `slock_access_token` and
  `slock_refresh_token`.
- Faster to adopt but still exposes tokens to WebView JavaScript.
- Must clearly separate these tokens from the mobile app's long-lived refresh
  token.

Mobile preference: Path A for production, Path B only as an interim bridge if
cookie-backed web auth is too large for V1.

## Supported Surfaces

V1 surface allowlist:

```text
settings.billing
```

The server should reject unknown `surface` values rather than accepting arbitrary
redirect paths. Future surfaces can be added explicitly.

Suggested server-side mapping:

```ts
const MOBILE_WEBVIEW_SURFACES = {
  "settings.billing": {
    requiredCapability: "canReadBillingSummary",
    route: (serverSlug: string) => `/s/${serverSlug}/settings/billing`,
  },
};
```

## Authorization Rules

The session creation endpoint must verify:

- the bearer token is a valid signed-in user
- the requested `serverId` belongs to the user
- the requested surface is allowed
- the user has the same capability the React page expects

For billing:

- owners/admins may view billing summary if web currently allows it
- billing mutation remains owner-only, matching existing web behavior
- members must not obtain a bootstrap session that bypasses the existing
  Settings billing authorization

The bootstrap session should carry the selected server identity so web API calls
continue to set or infer the correct `X-Server-Id`.

## Stripe Checkout / Portal Behavior

Server/web owners need to decide the mobile WebView policy for Stripe flows.

Open questions for review:

1. Should `/billing/checkout` and `/billing/portal` URLs open inside the WebView
   or be handed to system browser / Custom Tabs / Safari View Controller?
2. What `successUrl`, `cancelUrl`, and `returnUrl` should be used for mobile?
3. After returning from Stripe, should the user land back in:
   - the same WebView billing route, or
   - a native deep link that reopens Settings billing?
4. Are there app store policy constraints for plan purchase/management on each
   mobile platform?

Server contract recommendation:

- Return URLs should be generated from a server-approved mobile return target,
  not blindly echo arbitrary client URLs.
- If external browser is preferred, server should support mobile deep link return
  URLs such as:

```text
raft://settings/billing?serverId={serverId}
```

or a universal/app link equivalent.

## Security Requirements

- Do not put the mobile app refresh token in WebView storage in production.
- Bootstrap code must be one-time-use and short-lived.
- Reject open redirects. Only allow mapped return routes for approved surfaces.
- Redact bootstrap code and tokens in logs/metrics.
- Bind bootstrap code to user/server/surface.
- Prefer HttpOnly Secure cookies for WebView auth.
- If localStorage tokens are used temporarily, mint WebView-scoped tokens with
  short expiry and independent revocation.
- Preserve CSRF posture for cookie-backed endpoints. If cookie auth is added,
  use SameSite plus CSRF token or same-origin-only mutation protections.
- WebView should not gain broader capability than the native mobile session.

## Observability

Server should emit safe events/metrics for:

- session code created
- session code consumed
- expired code rejected
- reused code rejected
- authorization rejected
- bootstrap redirect completed
- billing action started from mobile WebView
- Stripe return completed from mobile WebView

Log fields should include user id, server id, surface, platform, app version,
and reason code. Do not log raw tokens or raw bootstrap codes.

## Concrete Implementation Path For Review

Phase 0: Review and choose auth shape

- Server/web owners review this document.
- Decide Path A cookie-backed session vs Path B short localStorage tokens.
- Decide Stripe in-WebView vs external-browser policy.

Phase 1: Server bootstrap skeleton

- Add `mobile_webview_sessions` persistence or Redis-equivalent storage.
- Add `POST /api/mobile-webview/sessions`.
- Add `GET /mobile-webview/bootstrap`.
- Implement one-time code validation and allowlisted surface mapping.
- Add tests for expiry, reuse, wrong user/server, and open redirect rejection.

Phase 2: Web auth integration

- If Path A: add cookie-backed API auth for WebView sessions and route it through
  existing permission checks.
- If Path B: mint WebView-scoped short tokens and bootstrap existing localStorage
  keys.
- Add web tests that `/s/{slug}/settings/billing` can load after bootstrap.

Phase 3: Billing/Stripe return policy

- Update billing checkout/portal creation to understand mobile WebView return
  context.
- Add tests for approved return URLs and rejected arbitrary return URLs.
- Define whether mobile host should intercept external URLs or remain in WebView.

Phase 4: Mobile replacement of trial injection

- Mobile stops writing `slock_refresh_token` directly into WebView localStorage.
- Mobile opens only the server-provided `bootstrapUrl`.
- Mobile keeps native WebView controls for navigation, back, external links, and
  load failure states.

Phase 5: Smoke / rollout

- Test Android, iOS, and OHOS against staging.
- Verify owner/admin/member permissions.
- Verify Stripe checkout/portal return.
- Verify logout/account switch invalidates or isolates WebView state.
- Roll out behind a mobile feature flag if available.

## Acceptance Criteria

The server contract is ready for mobile production use when:

1. Mobile can request a one-time bootstrap URL for `settings.billing`.
2. The bootstrap URL opens React billing without exposing the mobile refresh
   token to WebView JavaScript.
3. The server rejects expired, reused, wrong-user, wrong-server, and unknown
   surface codes.
4. Billing permissions match existing React/web behavior.
5. Stripe checkout/portal behavior and return route are explicitly supported.
6. Raw tokens and bootstrap codes are redacted from logs.
7. Android/iOS/OHOS can all use the same bootstrap API even if their WebView
   implementations differ.

## Questions For Server Review

1. Is cookie-backed WebView auth feasible with the current web API client, or do
   we need a short-token bridge first?
2. Where should one-time session codes live: database table, Redis, or existing
   session storage?
3. What is the right TTL for bootstrap codes?
4. Should WebView sessions be revocable independently from mobile sessions?
5. Should Stripe URLs remain inside the WebView or be externalized?
6. Does billing mutation from mobile WebView require additional product/legal
   review?
