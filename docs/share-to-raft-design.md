# Share to Raft: Android and iOS product and launch contract

Status: shared foundation is on `main`; final shared launch/consume wiring is in
follow-up. Android/iOS system intake and physical runtime remain pending.

## Product flow

The interaction follows the two-step forwarding pattern described by Product:

1. The system share surface opens **Select recipients**.
2. The user searches and selects one or more people, DMs, or channels.
3. **Next** opens a confirmation composer. It contains:
   - removable chips for every selected recipient;
   - a read-only list/preview of the shared text, link, images, or files;
   - an optional editable message/comment;
   - one final **Send** action.
4. Sending shows a per-recipient result. Successful recipients stay terminal;
   retry only targets that failed.

The system share action never sends immediately after a recipient row is tapped.
The user gets one final content-and-recipient confirmation.

The first implementation should use a bounded multi-select limit (recommended:
9 recipients) and the existing composer attachment-count/size/MIME policies.
The limit must be a shared product constant, not per-platform values.

## Reused product owners

- `ForwardMessageTargetRepository` remains the authority for recent targets,
  search, server access, channel/DM identity, join requirements, and DM creation.
- The target picker reuses the existing Forward row/search/empty/error visuals,
  extended with selection state, recipient chips, and a Next action.
- Existing composer attachment preview and upload models own the content list.
- Existing Thread route builders own destination identity after a target resolves.

The existing `messages/forward` endpoint is **not** used for system-shared
content. It only accepts source message IDs. External text/files are a new
composer draft and use the normal message upload/send path.

## Launch and persistence contract

Native hosts create a private, one-shot `ExternalShareEnvelope`:

```text
token
createdAt
sourcePlatform
text?
subject?
items[] = { localRef, displayName, mimeType, sizeBytes }
```

Only the opaque `token` and the intent `open_share_to_raft` may cross a route,
Want, URL, or Kuikly `pageData` boundary. Target lists, auth/session data,
message bodies, attachment bytes, and raw external provider URIs never enter
route payloads.

The platform intake must:

- copy provider-backed files into the app/extension private container before
  the temporary read grant ends;
- apply the existing attachment count, per-file size, total-size, filename,
  and MIME validation while copying with bounded streaming;
- write the envelope atomically, with file protection available on the platform;
- clear raw Intent/Want/extension input after admission;
- expire unconsumed envelopes and copied files after a short TTL;
- consume/delete the envelope only after the shared store has loaded it;
- retain a failed confirmation draft until explicit cancel, successful send, or
  TTL expiry.

The handoff from the native one-shot envelope to the shared draft is a durable
two-phase transfer: save the account-scoped draft with
`envelopeConsumed=false`, call the native repository's idempotent
`consume(token, consumptionId)`, then save `envelopeConsumed=true`. The stable
consumption id lets a restart retry the marker write without physically
consuming the native envelope twice. On restart, an incomplete transfer resumes
the same consume transaction before any product state is shown. A durable draft
always wins over a redelivered route token, so the same native payload cannot
create a second share flow.

Persisted recipient rows are candidate snapshots keyed by server + target kind
+ target id. Their display hints may restore the confirmation UI, but the sender
must re-resolve the canonical destination and current auth/server generation
before upload or send.

If authentication is required, the token remains local while the normal auth
flow completes. Auth material is never copied into the share envelope.

## Shared state machine

`ExternalShareStore` owns all product state:

```text
LoadingEnvelope
SelectingTargets(query, targets, selectedTargets, loading, error)
Confirming(selectedTargets, content, comment)
Sending(attemptId, perTargetStatus)
Completed(successTargets, failedTargets)
Error(retryable, code)
```

Required intents:

```text
Load(token, canonicalServerId)
Search(query)
ToggleTarget(identity)
Continue
RemoveTarget(identity)
ChangeComment(text)
Send
RetryFailed
Cancel
```

Every target execution is keyed by `attemptId + destination identity`.
Generation/cancellation fences prevent a previous query, destination resolve,
upload, or send callback from overwriting a newer selection/attempt. A retry
contains only failed targets; it must never repeat a successful send.

Before the first network call, the store durably records a stable per-envelope,
per-destination `deliveryId`. Process restoration never auto-sends: `Sent`
receipts remain terminal and any in-flight/non-terminal delivery becomes an
explicit failed target. Manual retry reuses the same `deliveryId`, which the
normal composer sender must bind to its idempotent client message identity.
This closes the response-loss window where the server accepted a send but the
client died before persisting the terminal receipt.

The AppShell parser recognizes only `intent=open_share_to_raft` and a bounded,
path-free opaque `externalShareToken`. It rejects raw content/auth fields and
derives account/server from the canonical shell session. The production shared
assembly uses an account-scoped durable draft repository, the platform-installed
native envelope repository, `ForwardMessageTargetRepository`, and the normal
authority-bound attachment/message sender. One mounted launch coordinator emits
one `Load`; a new coordinator after process restoration emits one new `Load` so
the durable draft can restore without auto-sending.

There is no cross-target atomicity in the current API. The UI therefore reports
partial success explicitly rather than claiming all-or-nothing delivery.
Attachment IDs are destination-scoped, so files are uploaded through each
resolved destination's normal composer path before its message is sent.

## Destination resolution

- Existing channels/DMs resolve without side effects.
- A person without a DM may create the DM during Send, with the confirmation
  page making that outcome visible.
- An unjoined public channel requires an explicit join confirmation. Selection
  alone must not join a channel.
- Server/account/auth generation is revalidated immediately before resolve and
  send. A stale target snapshot is discarded and reloaded.

## Platform entry points

### Android

- Add `ACTION_SEND` and `ACTION_SEND_MULTIPLE` MIME filters to the exported
  trampoline (`MainActivity`), not the internal Kuikly activities.
- Read `EXTRA_TEXT`, `EXTRA_SUBJECT`, `EXTRA_STREAM`, and `ClipData`; honor
  `FLAG_GRANT_READ_URI_PERMISSION` only during bounded intake copying.
- Persist the one-shot envelope under app-private cache/MMKV metadata, replace
  the retained Activity Intent with a clean intent, then open the app shell with
  only the share token/intent.
- Cold and warm `singleTop` launches must converge on the same store intent.

### iOS

- Add a real Share Extension target; do not use responder-chain or private-API
  tricks to force-open the containing app.
- Use App Group `group.build.raft.app.share` for an atomic envelope and copied
  files. Apply complete-until-first-authentication file protection where
  supported.
- The extension must present the same two-step recipient/confirmation flow. It
  may host the shared Kuikly page only if the signed extension stays within
  Apple's extension memory/startup limits; otherwise use a thin SwiftUI shell
  over the same shared `ExternalShareStore`/repository contract.
- Auth/session access requires an explicitly reviewed shared Keychain/App Group
  binding. Do not copy access or refresh tokens into UserDefaults or envelope
  files.
- Extension completion is terminal only after the send store reports its real
  result or the user cancels. If login is required, stage the envelope and show
  a safe “Continue in Raft” handoff supported by public extension APIs; never
  silently report Send success.

## Failure and recovery contract

- Intake rejection identifies which item is unsupported/too large and keeps
  the original source app active where the platform permits.
- Target-load failure shows shared retry and preserves the envelope.
- Destination resolve/join/DM-create failure is target-scoped.
- Upload/send cancellation returns the target to retryable state.
- Auth loss routes through the existing auth-loss funnel and preserves the
  local envelope until TTL.
- Process death restores the last durable selection/confirmation state without
  replaying any send. Terminal per-target receipts prevent duplicate delivery.

## Validation matrix

Shared deterministic tests:

- envelope parser rejects expired, invalid-token, oversized, count-over-limit,
  raw-provider-URI, and mismatched-file metadata;
- stale target/search generation cannot overwrite current state;
- multi-select add/remove/limit and confirmation transitions;
- destination identity revalidation and explicit join requirement;
- partial success retries failed targets only;
- process restore never replays terminal sends.

Platform tests:

- text, link, single image, multiple images, generic file, and mixed supported
  payload where the OS permits it;
- cold launch, warm launch, logged-out resume, cancel, process death, and TTL;
- private URI/grant expiry after intake;
- Android 12/14 and physical iPhone Share Extension;
- narrow/landscape/Dynamic Type, recipient overflow, attachment list scrolling,
  keyboard/focus, and accessibility labels;
- recipient 1 succeeds / recipient 2 fails / retry sends only recipient 2.

No platform may be called supported from compile/simulator evidence alone. The
final release report must list missing physical-device coverage explicitly.
