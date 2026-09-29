# Test-only A4 integration revision 17 — DO NOT MERGE

Shared PR2730: 70dc7ba1004c36d709944b517cf8bbb9dd2bc07e (rebased main includes layout 2727/2728).
Native PR2731: 4d1628e4779a9fd593dce0d84c98b822084b0b1c.

This QA branch alone sets the source initiation default and nativeGuardedCommitAvailable true.
Product branches retain disabled gates. Launch AppShell workspace/Rail flags on in the existing isolated iPad.
No messages or attachments are sent. The original carrier 6d6d884e4 proved the real native-push path was excluded by its inherited shell scope.
This revision uses the explicit embedded-child marker instead, retaining shell ownership.
Re-run real Home Compact channel → Wide, then draft editing/replacement/cancel/retry/commit-order cases.
Debug simulator results do not establish TestFlight or physical-device acceptance.

Revision 3 additionally accepts an unchanged Change receipt only for the same draft identity and exact native text. Revision 2 reached initiation but stopped at DraftCaptureFailed; no native prepare/commit was covered. Re-run that real entry first.

Revision 4 includes the test-class brace correction; product logic is identical to revision 3. Rebuilt against the final shared head.

Revision 5 carries numeric native editor refTag across the shared bridge. Revision 4 reached prepare but native rejected the JSON string refTag as editor_drift. Re-run that same real entry before attempting commit timing cases.

Revision 6 includes real JSON serialization tests and portable omission of absent refTag. Revision 5 did not build: the Kuikly JSONObject API has no NULL constant. Native strict validation is unchanged.

Revision 7 adds persistent shell OPEN/READY/ACK and destination effect/payload/layout/READY stage diagnostics. Revision 6 progressed through native prepare, then timed out awaiting ACK; manual Back subsequently exposed the embedded detail and is not a successful migration. No native guard changes. Missing-stage absence is not proof of non-execution without a logging and lifecycle control.

Revision 8 moves destination READY above the empty-state return and permits successful empty timelines, retaining loading/error rejection and identity/generation checks. Both effects now key on payload status. Revision 7 accepted OPEN but skipped every detail effect for an empty channel and timed out; re-run the same empty channel before the remaining commit matrix. Native lifecycle and commit guards are unchanged.

Revision 9 moves empty READY above the outer ThreadChatTabContent status dispatch and uses actual parser terminal states empty/content. Revision 8 still bypassed the entire list in empty_state and accepted an unused success status. Shared posted state now connects outer empty readiness to inner nonempty restore readiness; state declarations/import are included. Repeat real compact Home → empty channel → wide before any broader acceptance.

Revision 10 adds Debug-only native stage diagnostics for adaptive detail child hosts (pageData, frame, layout, attach, load and visibility state). Revision 9 times out while covered and posts READY/ACK only after manual Back. No lifecycle, mounting or commit behavior is changed; probes distinguish missing layout/attachment from post-attachment lifecycle suspension. Shared source remains d1421b4a7.

Revision 11 is a QA-only native preparation experiment: a valid adaptive detail host explicitly lays itself out after frame/props/parent arrival, even while its navigation controller is detached. Work is coalesced on the main queue and rechecks live parent, dimensions and adaptive envelope; it does not call appearance methods or change active. Revision 10 positively captured nonzero frame with child=0 until manual Back triggered first layout. Shared remains d1421b4a7 to isolate this change; newer cancellation cleanup is not yet integrated. Nonempty scroll/final geometry still require separate verification.

Revision 12 integrates reviewed shared cancellation (8bda31aab) and binds native
preparation to product PR2731 head 4d1628e47. Revision 11 reached hidden READY/ACK,
CommitDispatched and automatic Home return with original draft, without manual
Back. It did not independently observe native receipt settlement.

QA-only diagnostics now log parsed commit receipt status/matching and successful
pending removal/anchor consumption. Optional launch pageData
`slockQaDelayFirstAdaptiveReadyMs=30000` delays processing exactly the first valid
READY in the existing shell coroutine scope; the captured event is delivered to
the same production processing function afterward. Default 0 leaves delivery
synchronous. Disposing the shell subscription cancels the delayed job. This is
fault injection for the real 10-second source timeout/cancel and later retry:
wait for first AckTimeout/open_cancelled, narrow then widen to create the new
request, then observe old generation READY hitting ready_no_pending. No fake
receipt or ACK is created; subsequent READY delivery is normal. Reset the app
with flag 0 for ordinary-path validation. Neither this QA hook nor diagnostic
probes are proposed in the product PR. Physical coverage remains NOT RUN.

Revision 13 fixes the revision-12 counterexample: after cancellation a same-identity
retry must obtain a fresh generation, and target pager commit-result events enter
the shared settlement bus. QA diagnostics/delay injection are unchanged. Repeat
the normal path with delay 0 and require a matched committed receipt, pending
removal and anchor consumption. Then use delay 25000: first timeout, narrow/wide
retry, and old READY arrival while the new attempt is active or settled. Require
new generation > old, fresh destination READY, and no stale ACK/commit. Revision
12 reused generation 1 and ACKed the retry from the old READY at 08:19:15+08.
This source revision is for QA construction, not an independent-review verdict.

Revision 14 changes QA fault injection only: slockQaDelaySecondAdaptiveReadyMs
(default0, max9000) independently delays the second valid READY. First=22000 and
second=8000, with the existing narrow/wide retry timing, should deliver old READY
while the new generation remains pending. Delivery logs record pending request,
generation and acknowledged state. Both delay jobs are canceled on disposal and
run in the existing shell coroutine scope. Product shared/native heads unchanged.
Revision13 proved settlement and old READY rejection after the new request settled;
this additional interleaving must be independently captured, not inferred.

Revision 15 integrates nonempty restoration arbitration and persisted readback fields.
Revision 14 proved old READY isolation while the new generation remained pending,
but its 52-row QA fixture lost middle position after successful native commit.
Source item18/-72px became final item2/-21px; initial bottom positioning also ran
after READY, though the exact causal chain remains unproven. This revision ends
the initial bottom loop after nonlatest restoration, disables automatic bottom
following for that restored view, and writes staged target/offset and immediate
scroll readback to device diagnostics. Keep both QA READY delays at zero for
this ordinary nonempty test. Compare staged, immediate and final 777x834 viewport;
do not treat ordered hops or anchorConsumed alone as acceptance. atLatest and
restore-skipped paths require separate coverage. Product defaults remain OFF;
this is an unreviewed QA integration carrier, not a product merge verdict.

Revision 16 carries the invalid-anchor READY hold (e3aaf6fae). The normal nonlatest
path in r15 still loses its restored index after following_latest inset replay;
that separate owner repair is not included or claimed here.

QA-only `slockQaInvalidAdaptiveAnchor=true` (default false) replaces only the
accepted request's staged anchor with a request-specific missing message id,
fallbackIndex=-1 and atLatest=false before publishing the next session. The
request fence must match the captured record. Source capture, native prepare,
destination restore decision, timeout, cancel and receipt code remain unmodified.
No fixture messages are deleted and no READY/ACK/result is synthesized. A diagnostic
records the injection and another records pending count and anchor presence after
actual cancellation. On the authorized nonempty fixture, preserve an unsent QA
draft and require restore_skipped, no READY or CommitDispatched, actual timeout
cleanup and visible source/draft retention. This proves a deliberately invalid
record only, not a natural deletion race or unknown bounds fallback. Keep both
READY delay flags zero. Product branches/gates remain unchanged.

Revision 17 integrates the normal-anchor intent fix: restoration dispatches an
ExactOffset message Anchor, awaits controller settlement and verifies Anchored
identity plus first-visible index before READY. Invalid fallback identities hold
READY. The controller declarations now precede the restore effect. Native source
and QA hooks are unchanged. r16 remains independently bound to its running build.

For normal nonempty validation leave invalid-anchor and both READY-delay flags
off; use a nonzero source offset and compare staged offset, immediate readback,
post-inset relayout and final wide geometry. Current source checks index but not
pixel offset in its success predicate; this carrier is for runtime evidence, not
full contract approval. atLatest and failed restoration remain separate cases.
