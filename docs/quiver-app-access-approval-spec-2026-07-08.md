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
existing `agent:action_request:write`:

```ts
"app:access": {
  phase: "agent_inbound",          // or a fitting phase
  defaultAllowed: false,           // ⇒ first request needs consent (the card)
  publicDiscovery: true,
  requiresResource: true,          // bound to a specific app (the resource)
  label: "Access a Quiver app's release/feedback data",
}
```

Allow Quiver's OAuth client to request it (client `allowedScopes`). Nothing
else on the platform side — the existing consent/approval card renders it.

### 2. Quiver — request + honor

- **Manifest action** `request-app-access` →
  `POST /api/apps/{app_id}/access-grants/self`, invoked with
  `--scope app:access` and the app as the OAuth **resource**, plus a `role`
  param (default `viewer`) and `--target <admin conversation>`.
- On a session whose token carries `app:access` for `resource = {app_id}`,
  Quiver grants the requesting account that role on the app
  (`INSERT INTO app_members …`, idempotent), authorizing **on the token scope**
  (the admin's card commit is the gate) — not on "caller is admin". Records
  approval provenance for the audit log.
- **403 descriptor**: extend the existing `insufficient_app_role` body with a
  `request_access` hint (the exact `raft integration invoke … request-app-access`
  command), so an agent learns the next step from the error.

### End-to-end loop

1. Agent invokes `get-feedback` → `403 insufficient_app_role` (+ `request_access`
   hint).
2. Agent invokes `request-app-access` (scope `app:access`, resource = app,
   `--target <admin>`).
3. No covering grant → Raft posts the **approve-agent-login card** ("grant
   `app:access` for `<app>` to `<agent>`") to the admin.
4. Admin commits the card in the UI.
5. The token now carries `app:access` for that app → the grant runs → the agent
   is `viewer`.
6. Agent retries `get-feedback` → `200`. (Subsequent requests auto-approve via
   the remembered grant.)

## Open review points

1. Scope name / `phase` / `label` wording (`app:access` vs a Quiver-namespaced
   name) — platform naming preference.
2. Should a granted `app:access` map to `viewer` only, or honor a requested
   role (viewer/publisher/admin) with per-role approval text on the card?
3. Audit/expiry: is the app-member grant permanent, or should it expire /
   require renewal?

## Rollout

- **Phase A (Slock):** add the `app:access` scope to the catalog + Quiver client
  allowlist.
- **Phase B (Quiver):** `request-app-access` action + `access-grants/self`
  endpoint honoring the token scope; `request_access` hint on the 403 descriptor;
  audit provenance.
- **Verify:** an agent with no role → `request-app-access` → admin commits the
  card → grant → `get-feedback` returns `200`.
