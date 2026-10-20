package build.raft.app.data

import build.raft.app.model.ServerId

/**
 * Local forward-target search over one loaded directory authority (DMs, joined channels,
 * member DM candidates). The searchable values are lower-cased once when the index is
 * built, so a keystroke is a plain substring scan with no network, re-mapping, or
 * per-item normalisation.
 */
internal class ForwardMessageTargetSearchIndex private constructor(
    val serverId: ServerId,
    val authorityRevision: String,
    private val targets: List<ForwardMessageTarget>,
    private val haystacks: List<List<String>>
) {
    val size: Int
        get() = targets.size

    /** Same ranking/sectioning as the previous per-search rebuild: matches in authority order, paged by section. */
    fun search(query: String, limit: Int): List<ForwardMessageTarget> {
        val needle = query.trim().toForwardSearchNormalized()
        if (needle.isEmpty()) return targets.take(limit.coerceAtLeast(1))
        val matches = ArrayList<ForwardMessageTarget>()
        for (index in targets.indices) {
            if (haystacks[index].any { value -> value.contains(needle) }) matches += targets[index]
        }
        return matches.toForwardDirectoryPage(pageSize = limit).items
    }

    companion object {
        fun build(
            serverId: ServerId,
            targets: List<ForwardMessageTarget>,
            authorityRevision: String = targets.forwardDirectoryAuthorityRevision()
        ): ForwardMessageTargetSearchIndex =
            ForwardMessageTargetSearchIndex(
                serverId = serverId,
                authorityRevision = authorityRevision,
                targets = targets,
                haystacks =
                    targets.map { target ->
                        target.forwardSearchValues()
                            .map { it.toForwardSearchNormalized() }
                            .filter { it.isNotEmpty() }
                    }
            )
    }
}

/** web/staging target search matches labels, names, and ids — never generated helper copy. */
internal fun ForwardMessageTarget.forwardSearchValues(): List<String> {
    val identityValues = listOf(title, id, memberId?.value.orEmpty(), channelId?.value.orEmpty())
    return when (kind) {
        ForwardMessageTargetKind.HumanDirectMessageCandidate,
        ForwardMessageTargetKind.AgentDirectMessageCandidate -> identityValues + subtitle
        else -> identityValues
    }
}

internal fun String.toForwardSearchNormalized(): String = lowercase()
