package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.model.serverPermissionsForRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HomeServerSwitcherInviteTest {
    @Test
    fun inviteMembersIsOwnerAndAdminOnlyLikeWeb() {
        // botiverse/slock packages/shared/src/serverPermissions.ts: inviteMembers.
        val expected = mapOf("owner" to true, "admin" to true, "member" to false, "guest" to false, null to false)
        expected.forEach { (role, canInvite) ->
            assertEquals(canInvite, serverPermissionsForRole(role).canInviteMember, "role=$role")
        }
    }

    @Test
    fun switcherInviteEntryFollowsRoleAndClosesSwitcherBeforeOpeningDialog() {
        val events = mutableListOf<String>()
        fun click(role: String) =
            homeServerSwitcherInviteClick(
                server = StoredAuthServer(id = "server-1", name = "Raft", role = role).toServerSummary(),
                onInviteHuman = { serverId -> events += "invite:$serverId" },
                onDismissSwitcher = { events += "dismiss" }
            )

        assertNull(click("member"))
        assertNull(click("guest"))
        assertNotNull(click("admin")).invoke()
        assertNotNull(click("owner"))

        assertEquals(listOf("dismiss", "invite:server-1"), events)
    }

    @Test
    fun switcherInviteEntryIsHiddenWithoutAHost() {
        val admin = StoredAuthServer(id = "server-1", name = "Raft", role = "admin").toServerSummary()
        assertNull(homeServerSwitcherInviteClick(admin, onInviteHuman = null, onDismissSwitcher = {}))
        assertNull(homeServerSwitcherInviteClick(null, onInviteHuman = {}, onDismissSwitcher = {}))
    }
}
