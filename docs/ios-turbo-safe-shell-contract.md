# iOS TurboDisplay / TurboSafeShell Architecture Contract

- Status: Accepted architecture boundary; implementation and canary remain on hold
- Scope: iOS-only, default-off startup visual experiment
- Authorization: documentation only
- Not authorized by this document: implementation, feature enablement, Alpha,
  rollout, publication, or Android/OHOS parity work
- Evidence baseline: Raft Mobile `064a4eb71c1d28aa2a7faeb5048ceb2988ba8422`
  with Kuikly fork gitlink `0e101b7a9face423c38b081bd44ae10553afc656`

This document defines the boundary that any future Raft TurboDisplay work must
preserve. It turns the completed architecture and QA review into a durable,
mechanically reviewable contract. It is not evidence that the feature exists or
that a canary is ready.

The governing rule is:

> TurboDisplay is a disposable, passive startup visual artifact. It is never
> cached product state, route state, input authority, an offline fallback, or an
> alternate Store. Shared MVI Store and Repository state remain the only
> product-state authority.

## Decision And Scope

The first admissible design is an iOS-only `TurboSafeShell` that may move the
first non-empty visual frame earlier while the current shared MVI startup path
runs normally behind it.

The first version must be:

- default-off and remotely revocable;
- passive and noninteractive;
- free of arbitrary text, remote resources, product identity, and product
  state;
- structurally validated by a versioned allowlist before attachment;
- partitioned and generation-fenced by Host-owned identity;
- replaced by the current authoritative UI through an atomic presentation,
  input, and Accessibility ownership handoff;
- disposable on every validation, lifecycle, identity, or safety failure.

V1 eligibility is restricted to an ordinary root App Shell cold launch. Deep
links, notification launches, state restoration, logout/login transitions,
visual fixtures, multiwindow admission, and pushed child-page launches are
ineligible until each receives its own identity, revoke, route, and negative
test matrix.

The artifact lane must never delay Store creation, Repository loading, local
state hydration, realtime startup, or the normal loading/error path. When the
artifact lane is disabled, absent, rejected, revoked, or broken, the result must
be the existing startup behavior rather than a different product state.

This contract optimizes only one milestone:

```text
process start
  -> optional passive artifact visible        First Visual Frame
  -> current MVI surface presented             First Authoritative Frame
  -> current MVI surface owns input            Time to Interactive
```

First Visual improvement is useful only if First Authoritative Frame and Time
to Interactive do not regress beyond predeclared budgets. No artifact metric may
relabel decode, file existence, or tree submission as interactive readiness.

## Reviewed Baseline Facts

These are time-bound source facts for the evidence baseline above, not promises
about every future Kuikly revision.

1. Raft does not currently enable TurboDisplay in its iOS host.
   `compose/iosApp/iosApp/KuiklyExpand/KuiklyRenderViewController.m` implements
   neither `turboDisplayKey` nor `configureTurboDisplay`.
2. Android, iOS, OHOS, and shared use the same product shell page name,
   `compose_slock_app_shell`. A page name therefore cannot identify an account,
   API environment, server, route, artifact schema, or renderer generation.
3. Kuikly derives its cache key from `pageName + turboDisplayKey`. Returning the
   shared page name alone is not a valid Raft cache identity.
4. Kuikly persists a node graph with props and method records rather than an
   inert bitmap. It also supports extra cached content.
5. Event props can install temporary callbacks while the cached tree is visible;
   events produced during the current warm launch may then be replayed to the
   real callback during reconciliation. The first Raft version must reject this
   capability rather than adopt interactive cached-tree behavior.
6. Kuikly's default configuration enables automatic cache update and persistent
   real-tree tracking. Raft must not use those defaults to serialize an arbitrary
   late Home, Thread, Inbox, or other product tree.
7. The pinned Kuikly fork includes the lifecycle change that sets
   `firstScreenTurboDisplay` early when `hasNodeWithCacheKey` reports a file.
   That is candidate/file-existence truth, not validation, decode, attachment,
   confirmed display, or successful handoff truth.
8. The audited native TurboDisplay implementation is iOS-specific. No Android
   or OHOS behavior change is part of this contract.
9. The current Kuikly cache file has no Raft safety envelope: no artifact
   manifest, checksum, policy/schema version, structural allowlist, TTL, size
   cap, or embedded identity metadata. Corrupt/truncated length paths can leave
   a poison file that continues to appear as a candidate.
10. Cache writes and auto-refresh work are asynchronous. Deleting current files
    does not by itself prevent an already queued writer from recreating an old
    artifact after logout, environment change, or a kill switch.
11. The generic Raft iOS render controller is used for both the root App Shell
    and pushed child pages. A future implementation cannot safely return a
    TurboDisplay key globally; admission must identify the exact eligible root
    launch before renderer creation.
12. Kuikly's existing snapshot overlay is only a presentation precedent. A
    noninteractive overlay may let touches reach the underlying renderer, so it
    is not an input or Accessibility fence.
13. Existing authenticated per-server feature flags are evaluated after shared
    Store startup and cannot authorize a pre-render artifact attach. Early
    admission requires a native, durable, default-off authority. Later server
    policy may revoke an artifact but cannot retroactively authorize one that
    was attached before evaluation.

## Authority And Terminology

### `TurboSafeShell`

A dedicated passive projection that may contain only the V1 structural
allowlist. It cannot be inferred from an arbitrary current product tree merely
because that tree resembles a skeleton.

It owns no:

- Auth, account, server, route, or membership state;
- messages, channels, members, profiles, unread counts, tasks, files, drafts,
  presence, loading success, or errors;
- callbacks, event queues, methods, focus, timers, animations with product
  completion behavior, navigation, or input state;
- Store, Repository, realtime, or route/pageData payload.

### `TurboArtifactIdentity`

Host-local metadata used only to partition, validate, and purge artifacts. It
must not enter production route/pageData or shared product state.

A defensive V1 identity should be able to bind:

```text
projectionClass
artifactSchema
policyVersion
rendererCompatibility
hostBuildCompatibility
opaqueAccountPartition
apiEnvironmentPartition
serverPartition
locale
appearance
dynamicTypeBucket
viewportClass
```

Which fields are cache-key dimensions and which always force purge is an
implementation decision. Removing a partition dimension requires evidence that
the permitted artifact bytes and policy are safe across that boundary.

Raw account IDs, access tokens, refresh tokens, credentials, message content,
raw auth/session JSON, or sensitive route data must not appear in a key,
manifest, filename, or log. Hashing the complete stored auth record is also
forbidden; the Host must derive an explicit minimal non-secret identity input.

### `StartupAdmissionAuthority`

A native iOS authority available before renderer creation. Its initial state is
default-off. It decides only whether the current root launch may probe for an
artifact; it does not own product state or replace authenticated server policy.

Later shared/server policy may revoke through the normal epoch/kill-switch
path. It cannot make an earlier unauthorized attachment valid after the fact.

### `identityEpoch` and `killSwitchEpoch`

These are Host-authoritative monotonically increasing generations. They
invalidate artifact work created under an earlier identity or safety authority
even when human-readable IDs are reused.

`identityEpoch` advances for identity/trust-boundary invalidation.
`killSwitchEpoch` advances whenever the emergency safety authority changes and
must invalidate already-running work even if product identity is unchanged.

### `launchId`, `consumerLease`, and `writerToken`

- `launchId` distinguishes one startup attempt from every earlier or concurrent
  attempt.
- `consumerLease` grants exactly one controller/scene the right to attach one
  candidate for one `(artifactIdentity, identityEpoch, killSwitchEpoch)`.
- `writerToken` grants one staged writer the right to atomically publish only
  while its identity, epoch, policy, and feature mode remain current.

Every asynchronous decoder, attach, handoff, capture, writer, and publish
completion must carry and revalidate at least its `launchId`, `identityEpoch`,
`killSwitchEpoch`, and operation lease/token immediately before mutating live
presentation or durable storage.

### `AuthoritativeSurfaceReady`

The current real surface is ready only when all of the following are true:

- the current auth/environment/server/route generation is still valid;
- the current shared MVI surface has produced and committed its intended
  Content, Empty, or Error presentation;
- the Host has confirmed that presentation is installed rather than merely
  receiving a tree-build callback;
- current callbacks, delegates, hit-testing, and Accessibility ownership are
  installed for that surface.

File existence, `isTurboDisplay`, successful decode, elapsed time, Store state
creation, generic `contentViewDidLoad`, or a Kuikly tree commit alone is not
sufficient.

The Host records this as a monotonic `authoritativeReadyLatch` for the current
`launchId` and identity generations. Once set, it never becomes false for that
launch. Every pre-attach artifact completion must recheck the latch; if the real
surface won the race, the artifact is superseded without attaching. A later
Store transition cannot reopen artifact eligibility.

### `ArtifactDisplayConfirmed`

An artifact is display-confirmed only after the current presentation owner
proves that it participated in an on-screen frame in the current
foreground-active window/scene while its launch, epochs, and lease are still
current.

Confirmation cannot be inferred from decode, `addSubview`, renderer submission,
an off-window controller, a zero-sized/hidden layer, or an artifact fully
covered by another Host surface. If the controller is destroyed, the real
surface becomes authoritative, or the scene ceases to be eligible before the
frame proof, the artifact is not a displayed hit.

## System Boundary

```text
Canonical product-state plane

Auth / Route / Local / Network / Realtime
                   |
                   v
          Shared MVI Store / Repository
                   |
                   v
             Current real surface

Disposable startup-artifact plane

Approved safe projection -> stage -> validate -> atomic artifact
                                               |
                                               v
                                  passive visual placeholder
                                               |
                                  authoritative handoff removes it

Forbidden direction:

artifact -X-> Route / pageData / Store / Repository / Realtime / actions
```

The MVI lane starts immediately and continues through every artifact state. An
artifact failure never cancels, delays, rewrites, seeds, or restores product
state.

The preferred presentation model is a separate passive layer over the normal
renderer. If a future implementation uses Kuikly cached-tree in-place diff, it
must prove equivalent isolation: no artifact input or Accessibility owner, no
event/method replay, no mixed owner after handoff, and no late artifact callback
that can retake pixels.

## Consumer State Machine

```text
Dormant
  | feature enabled + launch eligible
  v
Probing
  | candidate found
  v
Candidate
  | single-consumer lease acquired
  v
Leased
  | manifest, identity, checksum, compatibility, and policy envelope pass
  v
EnvelopeValidated
  | inert decode succeeds
  v
Decoded
  | structural allowlist passes; epoch/lease/switch still current;
  | authoritativeReadyLatch is false
  v
PolicyValidated
  | attach commit succeeds while input/Accessibility fence is closed
  v
Attached
  | current foreground scene confirms an on-screen artifact frame
  v
PassiveVisible
  | current real surface emits AuthoritativeSurfaceReady
  v
HandoffCommitting
  | one serialized presentation/input/Accessibility transaction
  v
AuthoritativeOwned
  | artifact detached; real surface solely owns presentation and input
  v
Released
```

Alternative terminal paths are:

```text
Dormant / Probing / Candidate
  disabled, ineligible, no candidate, or lease loss -> Bypassed

Leased / EnvelopeValidated / Decoded / PolicyValidated / Attached
  invalid identity/schema/checksum/policy,
  forbidden capability, decode failure, or attach failure
    -> RejectedAndPurged

Candidate / Leased / EnvelopeValidated / Decoded / PolicyValidated
  authoritativeReadyLatch becomes true
    -> SupersededBeforeAttach -> Released

Attached
  authoritativeReadyLatch becomes true, controller is destroyed,
  scene becomes ineligible, or the bounded display-confirm deadline expires
    -> SupersededBeforeConfirm -> detach -> Released

PassiveVisible / HandoffCommitting
  bounded visibility timeout, swap failure, or ownership invariant failure
    -> SafetyFallback
       hide artifact
       purge artifact state
       increment local circuit breaker
       continue the existing normal surface path

Any nonterminal state
  identity change, explicit revoke, or kill switch
    -> Revoking
       advance the applicable identity/safety epoch first
       invalidate leases and tokens
       reject queued attach and handoff
       detach visible artifact
       cancel or fence decoder/writer work
       purge disk, memory, manifest, and temporary state
    -> Revoked
```

`Decoded` and `Attached` do not mean shown. `PassiveVisible` is the only state
that counts as a display-confirmed hit. The artifact never owns input,
Accessibility, focus, product callbacks, timers, Back handling, or navigation.

Every session must emit exactly one terminal outcome and reason. A stale
completion cannot rewrite a terminal outcome as success.

## Publisher State Machine

The publisher is a separate transaction because revocation must defeat a late
capture, validation, or atomic rename.

```text
Idle
  | explicitly capture the approved TurboSafeShell projection
  v
Capturing
  | write attempt-unique temporary payload and manifest
  v
Staged
  | checksum, compatibility, and structural policy pass
  v
PolicyValidated
  | identityEpoch, killSwitchEpoch, writerToken, identity, and switch revalidated
  v
Publishing
  | atomic publish
  v
Published
```

At any state before `Published`, failure or revoke must:

1. invalidate the writer token;
2. cancel work when possible;
3. reject a late completion even if cancellation loses the race;
4. delete temporary payload, manifest, and part files;
5. leave the previously committed artifact untouched unless the failure is an
   identity or security invalidation requiring full purge.

A writer from `(identityEpoch=N, killSwitchEpoch=K)` must be unable to publish
after either generation advances, even if it finishes after a new identity has
already produced a new artifact.

## Hard Contract 1: Atomic Presentation And Input Handoff

The only valid ownership states are:

```text
Before artifact:
  presentationOwner = normal host/loading or none
  inputOwner        = existing normal startup policy

Artifact visible:
  presentationOwner  = artifact
  inputOwner         = none
  accessibilityOwner = none

After handoff:
  presentationOwner  = current real surface
  inputOwner         = current real surface
  accessibilityOwner = current real surface
```

Forbidden states include:

```text
inputOwner = artifact
accessibilityOwner = artifact
inputOwner = real while presentationOwner = artifact
presentationOwner = mixed after handoff commit
```

Requirements:

- The Host input and Accessibility fence must be installed before any cached
  node can attach.
- Setting only the overlay or artifact view to `userInteractionEnabled = NO` is
  insufficient when that allows input to fall through to the real renderer.
- The artifact must expose no hit-testing, focus, Accessibility elements,
  keyboard navigation, page Back handling, callback, or event queue.
- The real tree may build behind the passive layer, but it must not receive a
  touch whose visible target was the artifact.
- Every attach and display-confirm callback must recheck that the current
  `authoritativeReadyLatch` is still false. Once the real surface wins, a slow
  artifact decode/attach cannot cover it or become visible later.
- Setting the ready latch and committing artifact attach/handoff must be
  serialized by the same presentation owner. The attach guard is rechecked
  inside that transaction, not only before dispatching onto the main queue.
- Pointer, keyboard, and Accessibility input before or during handoff is
  dropped, not queued or replayed.
- Handoff runs as one serialized main/presentation-owner transaction and keeps
  the fence closed until `AuthoritativeSurfaceReady` postconditions are true.
- A touch injected immediately before or during handoff produces zero product
  actions. A touch injected after handoff is delivered exactly once to the real
  surface.
- The artifact cannot remain visible after real input ownership is published.
- Current Store Content, Empty, and Error surfaces are all authoritative and
  must replace the artifact.
- A bounded visibility deadline must remove the artifact if authoritative
  readiness never arrives. The artifact cannot become a permanent loading or
  offline page.

If current Kuikly APIs cannot expose a signal or transaction with these
properties, the implementation must add the missing Host/framework boundary.
Elapsed time or a best-effort fade is not an equivalent handoff.

## Hard Contract 2: Epoch, Lease, Revoke, And Purge

The iOS Host must have one artifact coordinator with authority to advance
`identityEpoch` and `killSwitchEpoch`, issue leases/tokens, revoke work, detach
presentation, and purge storage.

Every consumer lease, decoded graph, queued attach, visible artifact, capture,
staged writer, and publish callback must be stamped with the current
`identityEpoch`, `killSwitchEpoch`, and an operation token. Cancellation is an
optimization; token rejection is the correctness mechanism.

The coordinator must advance the applicable epoch before asynchronous cleanup
for:

- logout or account replacement;
- API environment change;
- server trust-boundary change or membership loss;
- incompatible artifact, policy, renderer, or Host build change;
- explicit purge;
- an event/method/content policy violation;
- emergency kill switch, which advances `killSwitchEpoch` even when identity is
  unchanged.

Revocation covers all of the following, not only the committed file:

- the visible artifact layer;
- decoded in-memory nodes;
- pending main-thread attach or handoff;
- committed payload and manifest;
- attempt-unique temporary and part files;
- in-flight decoder;
- in-flight capture and writer;
- deferred atomic publish;
- success telemetry that has not yet passed its terminal guard.

Publishing is compare-and-set-like: atomic publication is authorized only when
writer token, identity epoch, kill-switch epoch, identity, policy, and feature
mode still match. A late decode, attach, handoff, or write callback becomes a
no-op and cannot attach, publish, resurrect purged data, or mark the launch
successful.

Exactly one `consumerLease` may own a given `(artifactIdentity,
identityEpoch, killSwitchEpoch)`. A losing controller follows normal startup
and cannot delete, rewrite, or claim telemetry for the winner's artifact.
The Host must acquire this lease before it returns a non-empty
`turboDisplayKey`/configuration or initializes the Turbo render layer.
`hasNodeWithCacheKey` and Kuikly's internal file lock are candidate/I/O facts,
not scene/controller ownership.

### Disable Versus Purge

- **Disable** prevents artifact use for the current or later launch but may
  retain safe compatible bytes if policy explicitly allows it.
- **Purge** revokes authority and removes committed, decoded, staged, temporary,
  and in-flight state.

Identity and security transitions use Purge. The emergency kill switch must
revoke and purge already-running work, not only affect future launches.

## Hard Contract 3: Versioned Structural Allowlist

Safety must be enforced by a versioned allowlist, not by heuristic counters or
a growing blacklist. Metrics such as `eventPropCount` may aid diagnostics, but
`count == 0` is not a sufficient security boundary when serializers can add
unknown fields or capabilities.

The V1 manifest must contain at least:

```text
policyVersion
artifactSchema
rendererCompatibility
artifactIdentityDigest
payloadLength
payloadSha256
createdAt / expiry policy
```

V1 requirements:

- unknown policy version, manifest field, node type, prop name, enum value,
  method, event, extra-state field, or resource kind is a fatal rejection;
- only explicitly enumerated passive visual primitives and attributes are
  permitted;
- event props, view methods, module methods, and extra cached state are
  forbidden;
- remote URLs and remote images are forbidden;
- arbitrary text strings and dynamic identifiers are forbidden;
- product-model values are forbidden;
- fixed brand resources, if permitted by the implementation review, must be
  compile-time approved local resource IDs/content digests rather than
  arbitrary strings or URIs;
- a policy expansion requires a policy/schema version bump and negative tests
  proving an older parser rejects the new structure;
- validation must complete before an object graph is attached to a live
  renderer.

The safe writer order is:

```text
attempt-unique stage
  -> checksum and compatibility
  -> structural allowlist validation
  -> identityEpoch/killSwitchEpoch/token/identity/switch recheck
  -> atomic publish
```

The safe consumer order is:

```text
manifest/checksum/identity validation
  -> inert decode
  -> structural allowlist validation
  -> identityEpoch/killSwitchEpoch/lease/switch recheck
  -> authoritativeReadyLatch must still be false
  -> attach behind closed input/Accessibility fence
  -> foreground-window artifact frame confirmation
```

If the current Kuikly archive/decoder cannot expose an inert pre-attach
structural validation boundary, that is an implementation blocker to solve. It
is not permission to deserialize an arbitrary product tree and inspect it after
it becomes live.

## Failure Semantics

TurboDisplay may fail only as "no optimization". It cannot fail by changing
product state, retaining old pixels, granting old behavior, or blocking current
MVI startup.

| Failure | Required behavior |
| --- | --- |
| Feature disabled or launch ineligible | Do not read, decode, or attach; use the existing startup path. |
| No candidate | Use the existing startup path; this is not a display failure. |
| Lease contention | Losing controller bypasses the artifact and does not mutate the winner's artifact. |
| Expired artifact | Reject and delete it; continue normal startup. |
| Account, API-environment, or server identity mismatch/change | Never attach; advance `identityEpoch`, revoke, and purge all affected committed/decoded/staged/temp/in-flight state. |
| Artifact schema, renderer, Host, or layout compatibility mismatch | Never attach; purge the incompatible candidate and related staged/temp state. |
| Unknown version, field, node, prop, enum, or resource | Fail closed before attachment, purge, and continue normal startup. |
| Event, method, extra state, remote resource, or dynamic content | Reject, purge, emit a policy reason, and increment safety/circuit accounting. |
| Truncated payload, bad length/checksum, or decode failure | Purge and continue normal startup. |
| Poison file that repeatedly appears as a candidate | Purge all related payload/manifest/temp state and prove the next launch no longer reports the candidate. |
| Attach failure | Hide/release any partial artifact, purge the unusable artifact, and continue normal startup. |
| Real surface becomes authoritative before artifact attach/confirmation | The monotonic ready latch wins; do not attach or count a hit, or detach an unconfirmed attachment immediately. |
| Store returns Content, Empty, or Error | Treat the current real UI as authoritative and perform the normal handoff. |
| Authoritative readiness timeout | Remove the artifact and continue the existing loading/error policy. |
| Handoff invariant or commit failure | Hide and purge the artifact, preserve/restore the normal surface, trip the local circuit breaker, and fail the canary. |
| Identity change | Advance `identityEpoch` first, revoke leases/tokens, detach, and purge disk/memory/temp/in-flight state. |
| Emergency kill switch | Run the same revoke-and-purge path for queued, visible, decoding, and writing work. |
| Memory pressure | Release decoded/visible artifact first and continue normal startup. |
| Late callback after revoke | Become a no-op; do not attach, publish, resurrect, or report success. |

## Telemetry Truth

Lifecycle telemetry must distinguish:

```text
artifact_candidate_found
artifact_lease_acquired
artifact_manifest_validated
artifact_policy_validated
artifact_decode_succeeded
artifact_display_submitted
artifact_display_confirmed
authoritative_surface_ready
artifact_handoff_confirmed
artifact_superseded
artifact_released
artifact_bypassed
artifact_rejected
artifact_revoked
artifact_purged
artifact_terminal_reason
```

Only `artifact_display_confirmed` may count as a displayed hit. Candidate,
validation, decode, attachment, or submission success must not inflate the hit
rate. Confirmation requires the `ArtifactDisplayConfirmed` foreground-frame
proof above.

Required correctness counters are hard gates:

```text
replayed_product_event_count == 0
cross_account_artifact_hit_count == 0
cross_environment_artifact_hit_count == 0
cross_server_artifact_hit_count == 0
old_identity_pixel_exposure_count == 0
artifact_persisted_past_handoff_count == 0
late_revoked_publish_count == 0
artifact_decode_crash_count == 0
```

Old-pixel evidence cannot rely only on business self-reporting. The canary gate
must retain a deterministic frame sequence or pixel-diff oracle across logout,
account/server/environment changes, and excluded launch modes.

Performance and resource metrics must include First Visual, First
Authoritative, TTI, visible duration, handoff duration, blank/flicker/layout
shift, decode time, peak memory, CPU/main-thread time, file size, write
frequency, purge residue, crash, and OOM. Numerical thresholds are rollout
decisions and must be declared before canary enablement.

Logs must not contain credentials, raw account identifiers, message content, or
sensitive route/product data.

## Executable Acceptance Matrix

The following is the minimum future gate. It is a design requirement, not a
claim that tests already exist.

| ID | Scenario or injection | Required result | Minimum evidence layer |
| --- | --- | --- | --- |
| OFF-1 | Default-off with a valid stored artifact | Zero decode/attach; normal MVI action/state trace; no artifact-owned input. | Host integration |
| OFF-2 | Kill switch while idle with stored artifacts | Epoch advances and committed/manifest/temp state is purged. | Coordinator integration |
| BASE-1 | Fresh install or no candidate | No artificial delay; terminal reason `no_candidate`; normal startup. | Two-launch UI harness |
| HIT-1 | Launch 1 publishes safe shell; launch 2 uses same identity | Exact candidate -> lease -> envelope -> decode -> policy -> confirmed -> handoff -> released lifecycle. | iOS two-process test |
| AUTH-1 | Artifact visible; Store produces Content | Atomic handoff, zero intermediate interactive artifact, artifact detached. | iOS UI integration |
| AUTH-2 | Artifact visible; Store produces Empty | Current Empty UI replaces artifact and receives input. | iOS UI integration |
| AUTH-3 | Artifact visible; Store produces Error | Current Error UI replaces artifact; artifact is not an offline fallback. | iOS UI integration |
| POLICY-1 | Truncated payload, bad length/checksum, corrupt archive | Reject before attach, purge, exact reason, normal startup. | Parser/Host integration |
| POLICY-2 | Unknown policy/schema/renderer compatibility | Fail closed and purge; no live graph attachment. | Parser unit |
| POLICY-3 | One unknown manifest field, node, prop, enum, or resource | Fail closed; no ignore-unknown behavior. | Parameterized mutation test |
| POLICY-4 | Event, view method, module method, extra state, remote image, or arbitrary text | Each capability independently makes the gate red and prevents attach. | Mutation tests |
| POLICY-5 | Malformed extra-length/truncated file repeatedly reports candidate | First rejection purges all related state; next launch reports no candidate. | Artifact-store regression |
| ID-1 | Wrong account/environment/server partition | Zero old pixels and no attach; identity epoch advances and all affected artifact state is purged. | Host integration + frame capture |
| COMPAT-1 | Wrong artifact schema/renderer/Host/layout compatibility | No attach; incompatible candidate and related staged/temp state are purged. | Host integration |
| REVOKE-1 | Identity changes during Candidate, EnvelopeValidated, Decoded, PolicyValidated, and PassiveVisible | Identity epoch advances first; lease invalid; visible layer removed; all state purged. | Parameterized coordinator/UI test |
| KILL-1 | Kill switch during decode, queued attach, visible state, and staged writer | No later attach/publish; visible artifact removed; old tokens rejected. | Deterministic scheduler test |
| LATE-1 | Decoder completes after revoke | No-op; cannot attach or mark display confirmed. | Coordinator unit |
| LATE-2 | Writer attempts publish after epoch change | Publish rejected; no old file in new partition; no part-file residue. | Artifact-store integration |
| LATE-3 | A write was queued before purge and completes after cleanup | Token/epoch check rejects publication; deleted artifact is not recreated. | Deterministic writer scheduler |
| CONCUR-1 | Two scenes/controllers request one candidate | Exactly one lease/display owner; loser follows normal startup; no duplicate telemetry. | Multi-controller integration |
| DESTROY-1 | Lease owner is destroyed before attach | Lease invalidated; late callback rejected; another session cannot inherit it. | Host lifecycle integration |
| RACE-1 | Real Content/Empty/Error becomes authoritative while candidate validation or decode is slow | Monotonic ready latch wins; artifact never attaches or covers the real surface; terminal `SupersededBeforeAttach`. | Deterministic Host scheduler |
| RACE-2 | Real surface becomes authoritative after attach but before an artifact frame is confirmed | Artifact detaches without a displayed hit; real presentation/input ownership remains uninterrupted. | Deterministic iOS UI test |
| DISPLAY-1 | Artifact is attached off-window, fully covered, zero-sized/hidden, or controller is destroyed before a frame | No `display_confirmed`/hit; artifact releases or purges by exact reason; normal startup continues. | Foreground-window presentation test |
| INPUT-1 | Pointer immediately before, during, and after handoff | Before/during: zero action; after: exactly one real-tree action; zero replay. | Deterministic iOS UI test |
| INPUT-2 | Accessibility focus, keyboard, page Back, and gesture while visible | Artifact exposes none of those product handlers or focus surfaces. | iOS Accessibility/UI test |
| SWAP-1 | Attach or handoff commit fails | Artifact hidden/purged, circuit incremented, normal surface preserved/restored. | Fault-injection integration |
| TIME-1 | Authoritative readiness never arrives | Deadline removes artifact; normal loading/error behavior continues. | Virtual-clock integration |
| WRITE-1 | Revoke between staging and atomic publish | Temp state removed; old writer cannot publish; prior artifact follows frozen purge policy. | Artifact-store integration |
| UPGRADE-1 | App/renderer/policy upgrade and downgrade | Incompatible artifact never attaches and is purged with an exact compatibility reason. | Upgrade integration |
| ENTRY-1 | Deep link, notification launch, state restoration, logout/login transition while excluded | Artifact lane is ineligible; no flash of old Home or Thread. | Launch-mode UI matrix |
| ENTRY-2 | Pushed child page uses the generic iOS render controller | No Turbo key/config is returned and no root-shell artifact is probed or attached. | Host routing integration |
| ADMIT-1 | Native admission is off while later authenticated server policy would allow | No probe/decode/attach occurs; later policy cannot retroactively turn the launch into a hit. | Host/shared integration |
| ADMIT-2 | Native admission allowed, then later policy or kill switch revokes | Applicable epoch advances; visible/queued/decoded/writing artifact state is revoked and purged. | Host/shared integration |
| LAYOUT-1 | Locale/theme/dynamic type/orientation/window-class mismatch | No incompatible frame; mismatched candidate is purged before attach. | Snapshot/UI matrix |
| MEMORY-1 | Memory pressure during decode and while visible | Artifact memory released first; no crash; normal startup continues. | Host fault injection |
| ROUTE-1 | Production route/pageData serializers | No artifact graph, display metadata, product content, or cache snapshot enters route payload. | Structural serializer/source test |
| TRACE-1 | Same seeded launch with feature off and with valid artifact | Store intents/actions/states and Repository calls are equivalent except artifact telemetry/timing. | Shared/Host integration |
| PERF-1 | Cold-start A/B on frozen build/device strata | Predeclared First Visual gain; Authoritative/TTI and resource budgets do not regress. | Physical-device canary |

Revoke and kill-switch tests must run at every asynchronous boundary rather
than one convenient timing.

## Implementation Slices And Approval Gates

Any future implementation must use new tasks and separate reviewed PRs. At
minimum, ownership must be explicit for these three slices:

1. atomic authoritative presentation, input, and Accessibility handoff;
2. `identityEpoch`, single-consumer lease, kill-switch revoke, and complete
   in-flight decode/writer purge/fencing;
3. versioned structural allowlist parser and atomic validated publisher.

Framework lifecycle/telemetry changes and product enablement should remain
separable from the product implementation. A source exact and deterministic
fixtures for all three hard contracts are required before an internal canary.
An Alpha or broader rollout additionally requires independent review, frozen
build/device evidence, predeclared performance budgets, and a practiced purge/
kill-switch path.

## Open Implementation Decisions

The safety and authority requirements above are fixed. The following choices
must be resolved and reviewed before implementation; this document does not
pretend they already exist:

- separate passive overlay versus an in-place Kuikly cached-tree diff;
- the exact `AuthoritativeSurfaceReady` Host/framework signal;
- the exact V1 node, prop, enum, and packaged-resource allowlist;
- whether fixed brand text or images are admitted at all in V1;
- which identity dimensions are key partitions and which proactively trigger
  purge; any candidate that is actually observed with an account/environment/
  server mismatch is always revoked and purged;
- opaque partition derivation, collision handling, key rotation, and logout
  retention policy;
- process-local versus durable epoch/lease representation and the cross-scene
  locking/storage transaction;
- the single owner for identity purge orchestration and the owner/propagation
  path for the emergency kill switch;
- future expansion beyond the ordinary root App Shell cold launch; every
  additional launch mode remains a separate decision after its negative matrix
  passes;
- visibility deadline, TTL/LRU/disk limits, decode/memory/CPU budgets,
  crash-loop circuit threshold, and First Visual/Authoritative/TTI budgets;
- enablement, expansion, pause, rollback, and post-safety-trip reactivation
  authority.

## Explicit Non-Goals

V1 does not:

- cache or restore Home, Thread, Inbox, messages, channels, members, profiles,
  unread state, tasks, files, drafts, input, focus, selection, or scroll offset;
- build or seed Store/Repository state from an artifact;
- add artifact identity or display metadata to route/pageData;
- use `compose_slock_app_shell` as a complete product cache key;
- permit interactive cached pages or product event replay;
- retain an artifact as an offline, loading, empty, or error fallback;
- change Android/OHOS product semantics;
- authorize contextual or personalized snapshots;
- define performance thresholds, cache TTL/LRU/disk limits, exact V1 allowed
  node/prop resources, or rollout ownership before an implementation review;
- assume TurboDisplay is preferable to a simpler native skeleton. The canary
  must prove incremental value.

If a passive universal shell provides insufficient value, the next decision is
not automatically to cache more product state. The team must compare any wider
snapshot proposal with optimizing Store bootstrap and the first authoritative
render path.

## Source Evidence

Raft baseline paths:

- `compose/iosApp/iosApp/iOSApp.swift`
- `compose/iosApp/iosApp/KuiklyRenderViewPage.swift`
- `compose/iosApp/iosApp/KuiklyExpand/KuiklyRenderViewController.m`
- `compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m`
- `compose/iosApp/iosApp/RaftIosAppScopeMMKV.swift`
- `compose/iosApp/iosApp/RaftIosRealtimeHost.swift`
- `compose/iosApp/iosApp/ContentView.swift`
- `compose/androidApp/src/main/java/build/raft/app/shared/RaftHostContract.kt`
- `compose/ohosApp/entry/src/main/ets/pages/Index.ets`
- `compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/AuthSessionStore.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/SharedApiEnvironmentStore.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/SharedServerFeatureFlags.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/RealtimeAuthorityRegistry.kt`

Kuikly fork paths at the evidence gitlink:

- `third_party/kuikly-ui/core-render-ios/Extension/KuiklyRenderViewControllerBaseDelegator.h`
- `third_party/kuikly-ui/core-render-ios/Handler/KuiklyTurboDisplay/KRTurboDisplayCacheManager.m`
- `third_party/kuikly-ui/core-render-ios/Handler/KuiklyTurboDisplay/KRTurboDisplayConfig.m`
- `third_party/kuikly-ui/core-render-ios/Handler/KuiklyTurboDisplay/KRTurboDisplayNode.m`
- `third_party/kuikly-ui/core-render-ios/Handler/KuiklyTurboDisplay/KRTurboDisplayNodeMethod.m`
- `third_party/kuikly-ui/core-render-ios/Handler/KuiklyTurboDisplay/KRTurboDisplayProp.m`
- `third_party/kuikly-ui/core-render-ios/Handler/KuiklyTurboDisplay/KRTurboDisplayDiffPatch.m`
- `third_party/kuikly-ui/core-render-ios/Handler/KuiklyTurboDisplay/KuiklyTurboDisplayRenderLayerHandler.m`
- `third_party/kuikly-ui/core/src/commonMain/kotlin/com/tencent/kuikly/core/module/TurboDisplayModule.kt`

The lifecycle behavior originated upstream in Tencent KuiklyUI PR #1537 and is
present in the reviewed fork baseline through fork sync commit
`cea98d34d5097864767eca2f8b8c9b20e36dd182`, which absorbed upstream commit
`7afa0f77d86ca91849a8ec66bb3e3eb6707e3ea4`. Its early
`firstScreenTurboDisplay` value remains candidate truth only.

`RaftIosRealtimeHost` and `RealtimeAuthorityRegistry` are existing generation,
lease, and stale-callback rejection precedents. A future Turbo coordinator
should reuse their semantics without coupling artifact state to private realtime
storage or parsing the raw auth record.

## Relationship To Other Contracts

- `docs/slock-app-state-first-principles.md` defines the single canonical
  product-state graph and render-only UI rule.
- `docs/shared-mvi-foundation.md` defines Store, reducer, Repository, and Host
  capability boundaries.
- `docs/process-domain-core-shell-projection.md` separates durable domain facts
  from disposable shell/UI projection.
- `docs/projection-contract.md` governs any future derivation of a safe visual
  projection from canonical facts.
- `docs/rfc-multi-server-shell.md` defines account/server/window isolation.
- `docs/realtime-session-owner-boundary-spec.md` defines process session
  ownership and generation fencing.
- `docs/ios-kmp-p0-host-bridge-spec-2026-06-27.md` records the identity-only
  iOS Host routing boundary.

If these documents appear ambiguous, current canonical identity and shared MVI
authority win. Ambiguity must never be resolved by letting a startup artifact
infer a current account, server, route, permission, or product state.
