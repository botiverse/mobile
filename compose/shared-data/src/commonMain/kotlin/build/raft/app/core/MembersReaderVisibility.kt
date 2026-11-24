package build.raft.app

import build.raft.app.model.MemberProfile

/** Web MembersPage `showGuests`: only a server owner or admin sees the guests (unknown role: not). */
@RaftCoreInternalApi fun membersShowGuests(serverRole: String?): Boolean = serverRole == "owner" || serverRole == "admin"

/**
 * Whether the server directory lists [this] member to a reader ([showGuests] = [membersShowGuests]
 * of the reader's role): a guest only to owners and admins (web MembersPage; the server's
 * `listServerMemberDirectory`, cindyz 10-23 #wg-rbac:#15). The Members page, global search and the
 * `@` list's directory part share this one rule; a guest still shows on its own channel's roster.
 */
@RaftCoreInternalApi fun MemberProfile.listedForMembersReader(showGuests: Boolean): Boolean = showGuests || serverRole != "guest"

/** [listedForMembersReader] for a reader whose server role is [viewerServerRole]. */
@RaftCoreInternalApi fun List<MemberProfile>.listedForMembersReaderRole(viewerServerRole: String?): List<MemberProfile> {
    val showGuests = membersShowGuests(viewerServerRole)
    return filter { it.listedForMembersReader(showGuests) }
}
