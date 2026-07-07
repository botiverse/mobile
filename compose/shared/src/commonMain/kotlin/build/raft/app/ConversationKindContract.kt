package build.raft.app

import build.raft.app.model.ConversationKind

/**
 * #417 (t3 of the conversation-open pipeline convergence): canonical
 * conversation-kind contract.
 *
 * Rules:
 * 1. Inside shared domain code the ONLY representation of a conversation's
 *    kind is [ConversationKind]. Kind-describing strings
 *    ([KuiklyThreadSurfaceKind] values, wire channel types such as
 *    "public"/"private"/"direct_message", ...) must not travel between layers.
 * 2. Strings may exist at exactly two boundaries, converted exactly once each:
 *    - parse boundary (server wire payloads → domain): [parseConversationKindOrNull]
 *    - Kuikly bridge boundary (domain → route/page payload strings):
 *      [toKuiklySurfaceKind] / [toWireChannelKindOrNull]
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
 *
 * The legacy converters (`threadSurfaceKind`, both `threadParentSurfaceKindOrNull`
 * overloads, `threadChannelKindWireValue`, ...) stay untouched until the t4-t6
 * cutovers migrate their call sites onto these functions;
 * ConversationKindContractTest pins parity with them until they are deleted.
 */
internal fun parseConversationKindOrNull(raw: String?): ConversationKind? =
    when (raw?.trim()?.lowercase()) {
        KuiklyThreadSurfaceKind.DirectMessage,
        "direct_message",
        "direct-message",
        "directmessage",
        "direct" -> ConversationKind.DirectMessage
        KuiklyThreadSurfaceKind.ReplyThread -> ConversationKind.Thread
        "joint" -> ConversationKind.Joint
        KuiklyThreadSurfaceKind.Channel,
        "public",
        "private",
        "group" -> ConversationKind.Channel
        "system" -> ConversationKind.System
        "inbox" -> ConversationKind.Inbox
        else -> null
    }

internal fun ConversationKind.toKuiklySurfaceKind(): String =
    when (this) {
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        ConversationKind.Thread -> KuiklyThreadSurfaceKind.ReplyThread
        ConversationKind.Channel,
        ConversationKind.Joint,
        ConversationKind.Inbox,
        ConversationKind.System -> KuiklyThreadSurfaceKind.Channel
    }

internal fun ConversationKind.toWireChannelKindOrNull(): String? =
    when (this) {
        ConversationKind.Channel -> KuiklyThreadSurfaceKind.Channel
        ConversationKind.Joint -> "joint"
        ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
        ConversationKind.Thread,
        ConversationKind.Inbox,
        ConversationKind.System -> null
    }
