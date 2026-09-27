package build.raft.app.markdown

import build.raft.app.model.MemberKind

/**
 * The peer part of a `dm:@peer[:short]` reference, with an optional explicit
 * peer kind — mirrors Web `@botiverse/raft-shared` `dmPeerRef.ts`.
 *
 * A human and an agent in one server may share a name (production: `skyzh`,
 * `tygg`). `dm:@skyzh` then names two different DMs, so a ref may pin the
 * peer's kind: `dm:@skyzh~agent` / `dm:@skyzh~human`. `~` never occurs in a
 * name, so an unknown suffix is refused rather than read as a bare name.
 */
internal data class RaftDirectMessagePeerRef(
    val peerName: String,
    val peerKind: MemberKind?
)

internal const val RaftDirectMessagePeerKindSeparator = '~'

/** Parses `name`, `name~agent` or `name~human`; any other `~suffix` or an empty name → null. */
internal fun parseRaftDirectMessagePeerRef(raw: String): RaftDirectMessagePeerRef? {
    val separator = raw.indexOf(RaftDirectMessagePeerKindSeparator)
    if (separator == -1) {
        return raw.takeIf { it.isNotEmpty() }?.let { RaftDirectMessagePeerRef(it, null) }
    }
    val peerName = raw.substring(0, separator).takeIf { it.isNotEmpty() } ?: return null
    val kind =
        when (raw.substring(separator + 1)) {
            "agent" -> MemberKind.Agent
            "human" -> MemberKind.Human
            else -> return null
        }
    return RaftDirectMessagePeerRef(peerName, kind)
}

/** Inverse of [parseRaftDirectMessagePeerRef]: `skyzh` or `skyzh~agent`. */
internal fun formatRaftDirectMessagePeerRef(peerName: String, peerKind: MemberKind?): String =
    when (peerKind) {
        MemberKind.Agent -> "$peerName${RaftDirectMessagePeerKindSeparator}agent"
        MemberKind.Human -> "$peerName${RaftDirectMessagePeerKindSeparator}human"
        else -> peerName
    }
