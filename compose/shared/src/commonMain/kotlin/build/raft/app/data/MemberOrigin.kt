package build.raft.app.data

import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberServerRef

/**
 * Member labels (#wg-rbac:#46): where someone is from, as seen from the viewed server. One rule
 * for every place that asks (channel member rows, Remove, the @ list, profile opens), a port of
 * web `utils/memberOrigin.ts` `memberOrigin` (botiverse/slock ec13decd3).
 *
 * External only when the server said so: `inViewerServer == false` (computed joint rosters,
 * whose `serverId` is only the person's first source), else a `serverId` that is not the viewed
 * server's (older servers and every non-joint roster, as before). A member with neither is the
 * viewed server's own.
 */
internal data class MemberOrigin(
    val external: Boolean,
    /** The servers to name, the viewed server left out; may be empty (no name was sent). */
    val servers: List<MemberServerRef>,
) {
    companion object {
        /** Someone of the viewed server: no mark anywhere. */
        val Local = MemberOrigin(external = false, servers = emptyList())
    }
}

internal fun memberOrigin(member: MemberProfile?, viewerServerId: String?): MemberOrigin {
    val viewer = viewerServerId?.trim()?.takeIf { it.isNotBlank() } ?: return MemberOrigin.Local
    if (member == null) return MemberOrigin.Local
    val home = member.homeServerRef()
    val external = member.inViewerServer?.not() ?: (home != null && home.id != viewer)
    if (!external) return MemberOrigin.Local
    val servers = mutableListOf<MemberServerRef>()
    for (server in member.servers ?: listOfNotNull(home)) {
        if (server.id == viewer || servers.any { it.id == server.id }) continue
        if (server.label().isNotEmpty()) servers += server
    }
    return MemberOrigin(external = true, servers = servers)
}

/**
 * The server names a joint roster row shows (web `utils/channelLocalMembership.ts`
 * `memberServerBadgeLabels`): empty when every server is the viewed one, otherwise every server
 * the member takes part through, so a person of the viewed server who is also in another keeps
 * that tag. `servers` is the server's answer, an empty list included; without it, the single
 * `serverId`/`serverName`/`serverSlug`.
 */
internal fun memberServerBadgeLabels(member: MemberProfile, viewerServerId: String?): List<String> {
    val viewer = viewerServerId?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    val servers = member.servers ?: listOfNotNull(member.homeServerRef())
    if (servers.all { it.id == viewer }) return emptyList()
    return servers.map { it.label() }.filter { it.isNotEmpty() }
}

/**
 * One line for a list of servers where there is room for one (web `MentionSuggestionList`
 * `message.profilePreview.serverAndMore`, `{server} +{count}` in every locale): the first, and
 * how many more. Null for none.
 */
internal fun memberServerNamesLabel(names: List<String>): String? =
    when (names.size) {
        0 -> null
        1 -> names.single()
        else -> "${names.first()} +${names.size - 1}"
    }

/**
 * The server names a member row or @ suggestion writes after the name, one line: someone from
 * another server, the servers they are from; anyone else, [memberServerBadgeLabels] (a person of
 * the viewed server who is also in another keeps that tag). Null for none.
 */
internal fun memberRowServerLabel(member: MemberProfile, viewerServerId: String?): String? {
    val origin = memberOrigin(member, viewerServerId)
    return memberServerNamesLabel(
        if (origin.external) origin.servers.map { it.label() } else memberServerBadgeLabels(member, viewerServerId),
    )
}

/**
 * Whether the viewer may remove [member] from the channel (web `ChannelMembers.tsx`
 * `canRemoveFromChannel ?? (canRemoveChannelMembers && isLocalMember)`): the server's per-row
 * answer when it sent one (joint authority: a host admin may remove another server's people),
 * otherwise only the viewed server's own members. The channel-wide capability is applied by the
 * caller.
 */
internal fun memberRemovableFromChannel(member: MemberProfile, viewerServerId: String?): Boolean =
    member.canRemoveFromChannel ?: !memberOrigin(member, viewerServerId).external

private fun MemberProfile.homeServerRef(): MemberServerRef? =
    serverId?.value?.trim()?.takeIf { it.isNotBlank() }?.let { id ->
        MemberServerRef(id = id, name = serverName.orEmpty(), slug = serverSlug.orEmpty())
    }

private fun MemberServerRef.label(): String = name.trim().ifBlank { slug.trim() }
