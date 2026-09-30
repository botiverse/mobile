# iOS external permalink intake

The SwiftUI window owns `SlockIosPermalinkSceneHost`. Its only system entry is
`onContinueUserActivity(NSUserActivityTypeBrowsingWeb)`; OAuth `onOpenURL` and
notification navigation remain independent. The same user-activity object reuses
its activation ID; native retries keep that ID. A new activity gets a new ID even
when its URL is identical.

The scene and root revision form an explicit `slockShellInstanceId`. A root is
bound before Kuikly construction, and only matching `shellScopeId` events may
release pending identities. Replacing a root disables delivery until its own
receiver becomes ready. Unauthenticated activations stay native until the first
authenticated Ready; bound activations cannot change auth generation. Accepted
acknowledges transport ownership, not successful navigation.

Wire contract (Raft task49, JingKe interface v3.4):

- Open: `slockExternalPermalinkOpen`; identity-only fields and `stringify` JSON.
- Ready/NotReady: `shellScopeId` and optional `authGeneration` string. Explicit
  login cancellation uses NotReady `reason=login_cancelled` to clear only unbound pending;
  ordinary receiver suspension does not cancel authentication.
- Accepted: `activationId`, `shellScopeId`, `authGeneration`, `status` equal to
  `accepted_first_time`, `accepted_duplicate`, or `rejected`.
- Result: separate `slockExternalPermalinkResult`; `opened` or `rejected` plus reason.
- All envelope values are strings; absent message/parent anchors are omitted.

Only HTTPS on `app.raft.build`, `app.slock.ai`, and `staging.slock.ai`, default
port or 443, is parsed. Configured developer origins do not expand this list.
The parser rejects credentials, ambiguous query anchors, mismatched parent
channels, and invalid path shapes. It never manufactures a message anchor from
the parent. Unknown URLs are not re-opened, preventing an external-link loop.

The native queue is bounded to eight pending activations and five minutes.
It retries on readiness, foreground, and a timer while pending; overflow and
expiry are recorded explicitly without logging the URL or message content.

Login cancellation is emitted only after the shared OAuth coordinator admits a
terminal cancellation for the original OAuth generation. Native browser cancellation,
Apple authorization cancellation and warm `access_denied` callbacks use a scope
captured from the requesting render controller; linking, ordinary failures, superseded
callbacks and missing scopes cannot cancel another window's queue. The scope is
also supplied to native authentication roots. Cold callbacks without a captured
in-process scope do not broadcast a guessed cancellation.

## Verification and remaining integration

`scripts/test_ios_permalink_intake.sh` compiles the production Foundation queue
and scene NotificationCenter adapter into a small macOS XCTest runner. It checks
parsing, first auth binding, generation retirement, scene/root isolation,
duplicate acknowledgements, repeated intentional opens, capacity/expiry, and
synchronous acknowledgement reentrancy. The intake cases also belong to the
existing iOS XCTest target. Product compilation is a separate Hosted CI check.

The iOS platform dispatcher forwards native Open notifications into the shared
event bus, preserves the scope/authentication envelope, and ignores shared-origin
echoes. Registration is process-wide and idempotent; each receiving Shell must
validate the requested scope and generation before enqueueing.

Shared must install its Open listener, publish per-scope Ready/NotReady, retain
canonical ownership after Accepted, and emit Accepted/Result using the contract.
Shared destination policy and Android implementation are owned separately.
Until that implementation is integrated, an intake build is not a navigable
Universal Link feature. No associated-domain entitlement, AASA, signing or web
publication is changed here. A simulator/unit-test pass does not establish
system HTTPS delivery on a physical signed build.

Explicit window scopes also publish their own foreground visibility and lifecycle
events; the pre-existing process host still serves legacy receivers and sockets.
An old root is marked hidden when replaced, so its visibility cannot leak.
