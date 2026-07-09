# Spec: self-serve Quiver app-access requests via native Raft approval

Status: draft (for review) · Owner: @CC-Quiver-Owner (Quiver) · Spans: Slock
platform (one scope) + Quiver

## Problem

An agent-login identity (e.g. an agent triaging a Quiver feedback ticket)
usually has no role on the specific Quiver app, so `get-feedback` and the other
app-scoped actions return `403 insufficient_app_role`. Today an org admin must
add the account by hand in the app's **Access** tab.

We want the agent to **request access** and an admin to **approve it in the
native Raft UI** — the same interaction as approving a *Login with Raft*
request — with **no custom Quiver approval page**.

## Goals / non-goals

- Reuse Raft's existing approval-card UX; do not build an approval flow in
  Quiver.
- The whole loop happens inside Raft: request → card → approve → access granted.
- Non-goal: a brand-new approval-card type or bespoke mechanism.
- Non-goal: granting anything broader than the requested app role (default
  `viewer`).

## How Raft approval actually works (read from `botiverse/slock`)

Neither approval cards nor scopes are arbitrary — both are fixed enums in the
platform, and they are **the same flow**:

- Scopes are a fixed catalog: `RAFT_OAUTH_SCOPE_CATALOG`
  (`packages/shared/src/oauthScopes.ts`), each entry
  `{ phase, defaultAllowed, publicDiscovery, requiresResource, label }`.
- Requesting a scope with no covering prior grant
  (`oauthService.ts`, no `coveringGrant`) creates a pending
  `oauthAccessRequest` and posts an **`integration:approve_agent_login`** action
  card — which already lists *"grant these scopes to this agent"*.
- The admin commits the card via the server-authoritative action path
  (`actionCardsService.ts`); the grant + access token are issued carrying the
  scopes (and resource); the token's scopes are readable via
  `accessTokenHasScope`.

So the existing agent-login approval card **is** the approval UI. We do not need
a new card type — only a new scope.

## Design (minimal)

### 1. Slock platform — add one scope

Add a resource-scoped scope to `RAFT_OAUTH_SCOPE_CATALOG`, modeled on the
existing `agent:action_request:write`. **Namespaced** (`quiver:app:access`, not
a flat `app:access`) so grants are subject-attributable — the `resource`
(the app id) is what lets the Registry attribute / revoke / audit per app; a
flat name would collapse every app's grant into one indistinguishable
capability (SHA-V0-006C: a grant is subject-relative *supplied-observed*, not an
*owned* global capability).

```ts
"quiver:app:access": {
  phase: "agent_inbound",          // or a fitting phase
  defaultAllowed: false,           // ⇒ first request needs consent (the card)
  publicDiscovery: true,
  requiresResource: true,          // bound to a specific app (resource = appId)
  label: "Access a Quiver app's release/feedback data",
}
```

The `label` is the single source of the card's consent copy, so it must read as
the **truth of the grant** — "read a Quiver app's release/feedback data" — and
**not** inherit the approve-login card's "login" framing. Allow Quiver's OAuth
client to request the scope (client `allowedScopes`). Nothing else on the
platform side — the existing consent/approval card renders it.

### 2. Quiver — request + honor

- **Manifest action** `request-app-access` →
  `POST /api/apps/{app_id}/access-grants/self`, invoked with
  `--scope quiver:app:access`, the app as the OAuth **resource**, and
  `--target <admin conversation>`.
- **The scope *is* the capability** (scope-per-capability, not a `role` field):
  `quiver:app:access` maps to `viewer`. **v0 issues `viewer` only** (least
  privilege). Higher roles open later as **new catalog entries**
  (`quiver:app:publish` → publisher, etc.), each resource-scoped — zero schema
  migration, and each scope carries its own `label` = its own consent copy
  (so there is never a "one card, per-role copy" branch; the label is
  single-source per capability). The token's granted scope determines the role.
- On a session whose token carries `quiver:app:access` for `resource = {app_id}`,
  Quiver grants the requesting account that role on the app
  (`INSERT INTO app_members …`, idempotent), authorizing **on the token scope**
  (the admin's card commit is the gate) — not on "caller is admin".
- The grant record is **revocable** and carries `grantedAt` (+ optional
  `expiresAt`); v0 does not force a TTL, but a grant is never permanent /
  non-revocable (authority ≠ liveness — "was granted" ≠ "still valid"; a
  non-revocable grant would degrade from *supplied-observed* to *owned*,
  SHA-V0-006C anti-pattern).
- **Visibility for revocation** (revocable is only real if the admin can find
  the grant): the grant *is* an `app_member` row, so it already shows in the
  app's **Access** tab with its role and a revoke control (`removeAppMember`) —
  no new surface needed. Record approval **provenance** (granted via the
  approval flow, when, by which card) so the Access tab can distinguish these
  from hand-added members, for the audit log.
- **403 descriptor**: extend the existing `insufficient_app_role` body with a
  `request_access` hint (the exact `raft integration invoke … request-app-access`
  command), so an agent learns the next step from the error.

### End-to-end loop

1. Agent invokes `get-feedback` → `403 insufficient_app_role` (+ `request_access`
   hint).
2. Agent invokes `request-app-access` (scope `quiver:app:access`, resource = app,
   `--target <admin>`).
3. No covering grant → Raft posts the **approve-agent-login card** ("grant
   `quiver:app:access` for `<app>` to `<agent>`") to the admin.
4. Admin commits the card in the UI.
5. The token now carries `app:access` for that app → the grant runs → the agent
   is `viewer`.
6. Agent retries `get-feedback` → `200`. (Subsequent requests auto-approve via
   the remembered grant.)

## Resolved decisions (review — @Kai, @LiBai, @Joy, 2026-07-08)

1. **Scope name:** `quiver:app:access`, namespaced + resource-scoped
   (`resource = appId`) — not a flat `app:access` (must stay per-app
   attributable/revocable; SHA-V0-006C).
2. **Role → scope-per-capability** (Kai, revised): no `role` field. Each
   capability is its own resource-scoped catalog entry — `quiver:app:access` →
   viewer (v0 only); future `quiver:app:publish` etc. = new entries, zero schema
   migration. Each scope's `label` is its own consent copy (single-source per
   capability; no per-role branching in one card).
3. **Lifecycle:** revocable app_member with `grantedAt` (+ optional `expiresAt`);
   no forced TTL in v0, but never permanent/non-revocable (authority ≠ liveness).

Verification once the card-rendering code is available:
- **@Kai (completeness / single-source):** does the existing `approve_agent_login`
  card, rendering a resource-scoped scope, surface **which app** (resource) and
  **which role** clearly? If not, the only card-side change is adding
  resource/role display to that card's **canonical** metadata (no second skin).
- **@Joy (consent truthfulness):** does the card read as a real grant — "grant
  `<agent>` access to `<app>`'s data" — with no leftover "login" framing?
  Driven by the scope `label` above.

Forward-looking (non-v0): higher roles open as new capability scopes
(`quiver:app:publish`, …), each with its own `label`/consent copy — so there is
no per-role branching inside one card. Loop in @Joy to write each new scope's
consent copy when it lands.

## Rollout

- **Phase A (Slock):** add the `quiver:app:access` scope to the catalog + Quiver client
  allowlist.
- **Phase B (Quiver):** `request-app-access` action + `access-grants/self`
  endpoint honoring the token scope; `request_access` hint on the 403 descriptor;
  audit provenance.
- **Verify:** an agent with no role → `request-app-access` → admin commits the
  card → grant → `get-feedback` returns `200`.
