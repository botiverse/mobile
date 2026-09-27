# iOS adaptive navigation primitives (capability remains OFF)

This API does not enable A4. Shared still must implement/verify editor capture and persistence,
destination restoration, live authority/window checks, native-result handling and rollback.
The existing `nativeGuardedCommitAvailable=false` and absence of source initiation are unchanged.

## Prepare the editor with the existing native reader

Use `SlockTextFieldNativeEditingStateReader.readCurrentValue` (BasicWidget) on the source
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

- Prepare: `{action:"prepare", requestId}`. Invoke from the standalone `compose_thread` root.
  Only an active, attached source that is the navigation top, directly above an AppShell,
  without transition/modal, in a window at least 840 points wide can prepare.
- Prepared reply: `{status:"prepared", requestId, token, context, expiresInSeconds}`. `token`
  is an opaque one-request nonce. `context` has source/target controller tokens, sourceRevision
  and geometry. These are instance identities, not canonical conversation/server identities.
  The source revision invalidates on layout change, transition start, disappearance or resign-active.
- Commit: `{action:"commit", requestId, token}` only after shared's target restoration ACK
  and a fresh authority/window/editor check. It compares the live source context with the
  prepared one, requires the same root/window and direct preceding shell, then pops without
  animation and reads back the stack in the same main-thread turn. No modal dismissal or
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

Only direct Thread→preceding AppShell navigation stacks are supported. Android/OHOS have no
implementation from this change. iOS does not advertise full A4 capability. Native guard does not
replace shared account/server, editor revision, shell identity or canonical message checks.

`compose/iosApp/scripts/test_adaptive_navigation_session.sh` compiles and executes the production
Foundation transaction object on macOS (not a reimplementation). It checks stale tokens, context
changes, timeout, cancellation, native failure, duplicate and reentrant calls. Hosted iOS compile
checks the UIKit/Kuikly wiring. Neither constitutes device migration/draft/viewport acceptance;
those remain required before enabling the capability.
