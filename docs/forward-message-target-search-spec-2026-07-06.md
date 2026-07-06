# Forward Message Composer Spec

Date: 2026-07-06

Status: aligned to React staging `origin/staging` as of 2026-07-06.

## React Staging Source

The current web reference is:

- `packages/web/src/components/message/ForwardComposerDialog.tsx`
- `packages/web/src/components/message/ForwardedBundleCard.tsx`
- `packages/web/src/components/message/forwardSelectionUtils.ts`
- `packages/web/src/components/message/ChatPanel.tsx`
- `packages/web/src/components/message/ThreadPanel.tsx`
- `packages/web/src/components/message/SelectModeToolbar.tsx`

Important staging behavior:

- Forwarding is gated by `GET /messages/forward/enabled` on the channel surface.
- Select mode exposes one primary `Forward` action, plus copy-link and lower-frequency image/markdown actions.
- `ForwardComposerDialog` uses existing loaded destinations: joined non-archived channels and existing DMs.
- It does not search never-DMed humans/agents and does not show/join unjoined public channels.
- The send body is:

```json
{
  "destinationChannelId": "channel-or-dm-id",
  "sourceMessageIds": ["message-id"],
  "note": "optional note"
}
```

- The endpoint is `POST /messages/forward`.
- Nested forwarded bundles are skipped. If every selected message is already a forwarded bundle, web shows: `Forwarded messages can't be forwarded. Select the original message instead.`
- Received forwarded messages use `actionMetadata.kind === "forwarded-bundle"` with `forwardedItems`.

## Scope

Forward-message target selection has two local states:

1. Default state: show existing joined channels and existing DMs.
2. Search state: filter those same existing destinations locally by target label/name/id.

The picker must not receive a full target directory or forwarded content through route/pageData. Route/pageData may carry only identity and intent, such as selected source message ids and source channel/thread identity. Target data must come from repositories/stores.

## Target Types

The staging-aligned picker recognizes these sendable target classes:

- `recent_channel`: a joined, non-archived channel from the user's existing channels.
- `recent_dm`: an existing DM channel from `/channels/dm`.

The parser still tolerates older/experimental search result shapes for compatibility, but the staging-aligned mobile picker must not rely on an unshipped target-search endpoint.

## Default State

Recommended default sources:

- Existing DMs: first `N` items from `/channels/dm`.
- Joined channels: first `N` non-archived channels from `/channels` where `joined=true`.

The list may be mixed under a single "Destinations" section. Large servers must not turn the picker into a full member/channel directory before the user searches.

## Search State

When the query is non-empty, filter the same existing destination list locally. Match against:

- channel/DM display label,
- target id,
- resolved channel id.

Do not match helper subtitles such as "Joined channel", because React staging searches target labels/names, not generated UI copy.

## Execute Rules

### Joined Channel

If the selected target is a joined, non-archived channel, forward directly to that channel.

### Existing DM

If the selected target is an existing DM, forward directly to that DM channel.

### Unsupported Targets

Never-DMed humans/agents and unjoined public channels are intentionally out of scope for this staging-aligned phase. They may be added later behind a separate product/API decision, but the mobile UI should not silently create/join destinations for this task.

## UI Behavior

- Select mode toolbar shows a single `Forward` primary action.
- Thread mode offers `Select All`, selecting the parent and all non-system replies, matching React `ThreadPanel`.
- Forward opens a composer dialog.
- The dialog contains:
  - search field,
  - existing destination list,
  - selected-target checkmark,
  - optional note field,
  - Cancel and Send actions.
- Selecting a target does not immediately send. The user must press Send.
- If forwarding succeeds, clear selection and close the composer.
- Copy-link and image/markdown actions stay separate from forwarding.

## Forwarded Bundle Read Path

The read path must support the staging metadata shape:

```json
{
  "kind": "forwarded-bundle",
  "version": 1,
  "forwardedItems": [
    {
      "index": 0,
      "sourceMessageId": "message-id",
      "sourceAuthorSnapshot": {
        "type": "user",
        "id": "user-id",
        "name": "Ada",
        "uniqueName": "ada"
      },
      "sourceCreatedAt": "2026-07-06T12:58:00.000Z",
      "sourceTargetSnapshot": {
        "id": "channel-id",
        "type": "channel",
        "label": "#general",
        "labelVisibility": "public"
      },
      "contentSnapshot": "message body",
      "attachmentSnapshots": [
        { "filename": "note.txt", "mimeType": "text/plain" }
      ],
      "provenanceState": "available"
    }
  ]
}
```

The mobile parser may also keep accepting earlier `forwardBundle` draft payloads so existing fixtures and transitional server responses continue to render.

## Mobile/KMP Architecture Notes

- Keep route/pageData identity-only.
- Load destinations through `ChannelRepository.getChannels` and `ChannelRepository.getDirectMessages`.
- Do not pass target directories or message content in launch payloads.
- Send only source message ids, destination channel id, and note to the forward endpoint.
- Render received forwarded messages from loaded message state, not from route payload state.

## Acceptance Cases

1. Recently used DMs and joined channels appear in the default composer state.
2. Searching filters existing DMs/joined channels without calling an unshipped target-search endpoint.
3. Unjoined and archived channels are not shown as forward targets.
4. Selecting a target only marks it selected; Send performs the forward.
5. Send posts `destinationChannelId`, `sourceMessageIds`, and trimmed `note`.
6. Thread Select All selects parent plus non-system replies.
7. `actionMetadata.kind=forwarded-bundle` with `forwardedItems` renders as a forwarded bundle.
8. Older `forwardBundle` payloads still render during transition.
