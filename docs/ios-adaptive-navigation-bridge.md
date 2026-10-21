# iOS adaptive navigation primitives (capability remains OFF)

This API does not enable A4. Shared still must implement/verify editor capture and persistence,
destination restoration, live authority/window checks, native-result handling and rollback.
The existing `nativeGuardedCommitAvailable=false` remains unchanged. Source initiation must also
remain disabled until the shared integration and end-to-end checks pass.

## Prepare the editor with the existing native reader

Use `RaftTextFieldNativeEditingStateReader.readCurrentValue` (BasicWidget) on the source
composer's bound editor. It resolves the actual `AutoHeightTextAreaView`, calls
`getTextInputState`, and rejects the response if the native binding has changed. This returns
text, selection and composition, including changes not yet delivered by ordinary Compose
callbacks. Keep the existing editor-session/request fences used by Send; do not add a second
UIKit text walk or read only the last shared draft value. `Unavailable` is an abort, not empty text.

Shared owns applying that value to the correct account/server/conversation draft, awaiting
persistence, and preventing/revalidating edits made during migration. `dispatch(Flush)` alone
is not a completion barrier. This PR reuses the native reader, it does not wire A4 to it.

## `HRBridgeModule.adaptiveNavigation`

Asynchronous JSON under the normal `KR_PARAM_KEY` envelope; prepare/cancel
return via `KR_CALLBACK_KEY`. UIKit access and commit execute on the main thread. No registration
change: the existing ObjC `HRBridgeModule` runtime class exports the selector.

- Prepare: `{action:"prepare", requestId, expectedEditor:{refTag,text}}`. Invoke from the standalone `compose_thread` root.
  Only an active, attached source that is the navigation top, directly above an AppShell,
  without transition/modal (including container ancestors), in the active key window at least
  840 points wide can prepare. `refTag` is the actual AutoHeightTextAreaView nativeRef resolved
  by the source reader for this capture, not its Compose wrapper. `text` must be the captured
  value whose persistence shared has confirmed. Native resolves this ref only in the caller's
  render root, requires an attached editable KRTextAreaView, and compares its live text with
  the expected text. Missing/malformed editor data, replacement/unavailable editors, or active
  marked composition fail closed (`rejected/editor_drift`). No first-responder or tree-search fallback.
  Active composition must finish before retry: popping can otherwise commit an intermediate IME value.
- Prepared reply: `{status:"prepared", requestId, token, context, expiresInSeconds}`. `token`
  is an opaque one-request nonce. `context` has source/target controller tokens, sourceRevision
  and geometry. These are instance identities, not canonical conversation/server identities.
  Internally the nonce additionally binds a weak reference to the exact native editor and an
  immutable text/UTF-16 selection snapshot. Text is not returned in navigation receipts or logged.
  The source revision invalidates on layout change, transition start, disappearance or resign-active.
- Commit: `{action:"commit", requestId, token}` only after shared's target restoration ACK
  and a fresh authority/window/editor check. It compares the live source context with the
  prepared one, requires the same root/window and direct preceding shell, then pops without
  animation and reads back the stack in the same main-thread turn. Immediately before the
  mutation it resolves the saved ref again and compares the exact editor instance, text and
  selection; composition must still be absent. Any mismatch yields `rejected/editor_drift`
  to the prepared target without calling pop. Commit does not accept an updated expected text
  that could silently replace the prepared state. An editor rejection permanently retires that
  nonce; use a fresh prepare after preserving/reconciling source and destination state. No modal dismissal or
  key-window/top-controller fallback is allowed. Preparation expires after 30 monotonic seconds.
- Cancel: `{action:"cancel", requestId, token}`. No navigation effect. A cancelled/rejected
  transaction cannot commit; use a new requestId for an explicit retry. Cancel before replacing
  an outstanding preparation. Repeating prepare for the same request does not extend expiry.

The authoritative commit result is a pager event on the **prepared target AppShell**:
`{event:"slockAdaptiveNativeCommitResult", data:{requestId,status,reason,
sourceControllerToken,targetControllerToken}}`. The envelope here describes the event name/data;
the actual `sendWithEvent` payload is the data object. Success is `status:"committed"` and is
emitted only after UIKit reports removal of the exact source and the exact target is now top.
Commit never invokes a source callback, even on failure: its renderer may already be destroyed
before stack readback fails. All commit results use the target event when a prepared target survives.
An unprepared/unsupported commit has no target; shared must time out while preserving state.

Shared must match requestId + native controller identities + its current authority, accept success
idempotently, then consume the matching anchor. ACK/READY/prepare are never commit success.
Failures, cancellation and missing results retain recovery records; rollback the staged destination
without losing the source draft. Duplicate commit cannot pop another controller. A later success
replay is still tied to the original request and cannot authorize a different migration.

## Scope and checks

Only direct Thread→preceding AppShell navigation stacks are supported. Android has no
implementation from this change. iOS does not advertise full A4 capability. The native editor guard
is a state snapshot, not a monotonic edit revision. It does not detect
an A→B→A history whose final native state is identical, and does not replace shared logical
editor-session, account/server, draft instance, shell identity or canonical message checks.
Shared must preserve those identities across capture/persistence/READY. The native guard closes
new native input between shared's last read and synchronous pop; it does not implement restoration.

`compose/iosApp/scripts/test_adaptive_navigation_session.sh` compiles and executes the production
Foundation transaction object on macOS (not a reimplementation). It checks stale tokens, context
changes, timeout, cancellation, native failure, duplicate and reentrant calls. Hosted iOS compile
checks the UIKit/Kuikly wiring. Neither constitutes device migration/draft/viewport acceptance;
those remain required before enabling the capability.

### Editor guard verification

The production Foundation transaction test also checks post-read text/selection drift, equal-text
editor replacement, unavailable/composing snapshots, editor destruction, repeated prepare and
no revival after rejection. `RaftAdaptiveEditorSnapshotTests` exercises real UITextView text,
UTF-16 selection and marked composition; the iOS CI job runs this focused simulator suite.
Neither these tests nor host compilation is A4 end-to-end or physical-device acceptance.

Renderer API verified against immutable Kuikly `2.24.0-raft.7`, source
`10dbe9168d5ca743978c371f36118400bb461ac8`: `viewWithRefTag:` and `KRTextAreaView : UITextView`.
No fork modification or renderer version change is involved.

UIKit snapshot checks run in the standalone `AdaptiveEditorTests` simulator target. It links only system frameworks and the production snapshot header, with no app host or shared framework dependency; the host app compilation and Foundation transaction suite remain separate CI steps. This avoids loading a second copy of the static shared runtime through the existing `iosAppTests` carrier.

### Covered destination preparation

A Thread controller covering AppShell detaches its view from UIWindow. Kuikly still
sends adaptive detail pageData and a nonzero frame, but UIKit may defer layout.
The adaptive child host therefore coalesces an explicit layout on the main queue
after frame, page props, or parent arrival. Before running it rechecks its live
parent, dimensions, page name, shell ID and positive detail generation. Removed
or rebound ordinary hosts do not create a child. It uses normal layout/attachment
and existing visibility guards; it never manufactures appearance or activation.

QA revision 10 (5f8bca2b2, run 36494504839) positively captured this deferral: frame
arrived before AckTimeout, while the first child creation occurred only after
manual Back. Revision 11 (5dda43b57, run 36497233364) with the above preparation
change produced READY/ACK at window=0 and active/appearance=false, then dispatched
commit and automatically showed the same empty channel in Home with its original
unsent draft. This is an isolated iPad simulator result with QA-only shared gates.
The newer shared cancellation patch was not in that comparison carrier.

Remaining acceptance includes authoritative commit receipt settlement, nonempty
scroll restoration at final geometry, edit/replacement during handoff, and
new-carrier timeout/cancel/retry/late-READY behavior. Initial detached-host bounds
can precede the final wide layout, so empty READY is not nonempty viewport proof.
Product capability remains disabled. No physical-device acceptance is claimed.
