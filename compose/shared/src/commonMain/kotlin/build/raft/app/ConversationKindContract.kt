package build.raft.app

import build.raft.app.model.ConversationKind

/**
 * #417 (t3 of the conversation-open pipeline convergence): canonical
 * conversation-kind contract.
 *
 * Rules:
 * 1. Inside shared domain code the ONLY representation of a conversation's
 *    kind is [ConversationKind]. Kind-describing strings
 *    ([RaftThreadSurfaceKind] values, wire channel types such as
 *    "public"/"private"/"direct_message", ...) must not travel between layers.
 * 2. Strings may exist at exactly two boundaries, converted exactly once each:
 *    - parse boundary (server wire payloads → domain): [parseConversationKindOrNull]
 *    - Kuikly bridge boundary (domain → route/page payload strings):
 *      [toRaftSurfaceKind] / [toWireChannelKindOrNull]
 * 3. No silent defaults: [parseConversationKindOrNull] returns null for
 *    unknown input and callers decide explicitly (wait / drop / log) — never
 *    assume Channel. #415's DM-thread misroute grew from a `?: Channel`
 *    default inside a mid-chain conversion.
 * 4. Channel visibility ("public"/"private") is an orthogonal attribute, not
 *    a kind: both parse to [ConversationKind.Channel] here, and the
 *    visibility itself stays a separate field where a caller needs it.
 * 5. The Kuikly surface projection is lossy (Joint/Inbox/System all render on
 *    the channel surface). Round-tripping a kind through a string is only
 *    guaranteed via [toWireChannelKindOrNull] for the kinds it covers.
 * 6. Confinement (task #444 lesson): [toRaftSurfaceKind] may be CALLED only
 *    where a surface string is being assembled for the bridge — route/launch
 *    payload builders and directory-projection `surfaceKind` fields. It must
 *    never serialize a kind into a field that domain logic later parses back
 *    (the #444 bug: home rows carried the lossy surface as their kind and the
 *    Joint section's `kind == "joint"` filter went permanently false). When a
 *    domain check compares against [RaftThreadSurfaceKind.Channel], be
 *    explicit about intent: that value also matches Joint/Inbox/System per
 *    rule 5 — if the check means "is a plain channel, not joint", it must use
 *    [ConversationKind] (or the lossless wire kind), not the surface.
 *
 * The legacy converters (`threadSurfaceKind`, both `threadParentSurfaceKindOrNull`
 * overloads, `threadChannelKindWireValue`, ...) stay untouched until the t4-t6
 * cutovers migrate their call sites onto these functions;
 * ConversationKindContractTest pins parity with them until they are deleted.
 */
internal fun parseConversationKindOrNull(raw: String?): ConversationKind? =
    when (raw?.trim()?.lowercase()) {
        RaftThreadSurfaceKind.DirectMessage,
        "direct_message",
        "direct-message",
        "directmessage",
        "direct" -> ConversationKind.DirectMessage
        RaftThreadSurfaceKind.ReplyThread -> ConversationKind.Thread
        "joint" -> ConversationKind.Joint
        RaftThreadSurfaceKind.Channel,
        "public",
        "private",
        "group" -> ConversationKind.Channel
        "system" -> ConversationKind.System
        "inbox" -> ConversationKind.Inbox
        else -> null
    }

internal fun ConversationKind.toRaftSurfaceKind(): String =
    when (this) {
        ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
        ConversationKind.Thread -> RaftThreadSurfaceKind.ReplyThread
        ConversationKind.Channel,
        ConversationKind.Joint,
        ConversationKind.Inbox,
        ConversationKind.System -> RaftThreadSurfaceKind.Channel
    }

internal fun ConversationKind.toWireChannelKindOrNull(): String? =
    when (this) {
        ConversationKind.Channel -> RaftThreadSurfaceKind.Channel
        ConversationKind.Joint -> "joint"
        ConversationKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
        ConversationKind.Thread,
        ConversationKind.Inbox,
        ConversationKind.System -> null
    }
