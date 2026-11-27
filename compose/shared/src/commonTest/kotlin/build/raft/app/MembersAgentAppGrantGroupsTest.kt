package build.raft.app

import build.raft.app.data.AgentAppLoginItem
import build.raft.app.data.AgentAppLoginType
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.canManageAgentAppAccess
import build.raft.app.members.MemberDetailRouteState
import build.raft.app.members.membersAgentAppGrantGroups
import build.raft.app.members.onlyGrantSource
import build.raft.app.members.viewerManagesAgentAppAccess
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One row per App on the agent's Apps tab (web `agentAppGrants.ts` `groupGrantsByApp` /
 * `grantsBySource` and AgentAppGrantsRow's source line, botiverse/slock 31abbdefb #13293), and who
 * may revoke (web AgentDetailPanel `canManageAgentAccess`, slock 5828cdce3e).
 */
class MembersAgentAppGrantGroupsTest {
    @Test
    fun anAppsGrantsAreOneRowInTheServersOrderSinceItsEarliestGrant() {
        val groups = membersAgentAppGrantGroups(listOf(STAMP_NOTIFY, REMINDER, STAMP_SIGN_IN))

        assertEquals(listOf("stamp", "reminder"), groups.map { it.clientId }, "Stamp is listed once, where the server first lists it")
        val stamp = groups.first()
        assertEquals(listOf(STAMP_NOTIFY.id, STAMP_SIGN_IN.id), stamp.grants.map { it.id })
        assertEquals("Stamp", stamp.name)
        assertEquals("Agent login and notifications", stamp.description)
        assertEquals("https://stamp.build", stamp.homepageUrl)
        assertEquals(STAMP_SIGN_IN.createdAt, stamp.since, "the App first got access with its earliest grant")
        assertEquals(listOf(REMINDER.id), groups.last().grants.map { it.id })
    }

    @Test
    fun aPendingRequestOrARevokedGrantIsNoRow() {
        val pending = STAMP_NOTIFY.copy(id = "request-1", type = AgentAppLoginType.Pending)
        val revoked = REMINDER.copy(revokedAt = "2026-11-20T00:00:00.000Z")

        assertEquals(listOf(STAMP_SIGN_IN.id), membersAgentAppGrantGroups(listOf(pending, revoked, STAMP_SIGN_IN)).flatMap { group -> group.grants.map { it.id } })
    }

    @Test
    fun theRowSaysHowAccessWasGrantedOnlyWhenEveryGrantCameTheSameWay() {
        mapOf(
            listOf("agent_login", "app_request") to null,
            listOf("person", "person") to "person",
            listOf("agent_login") to "agent_login",
            listOf<String?>(null) to null,
            listOf(null, "person") to null,
        ).forEach { (sources, expected) ->
            val grants = sources.mapIndexed { index, source -> STAMP_SIGN_IN.copy(id = "g-$index", grantSource = source) }
            assertEquals(expected, membersAgentAppGrantGroups(grants).single().onlyGrantSource(), "sources $sources")
        }
    }

    @Test
    fun ownersAdminsAndTheAgentsHumanCreatorManageItsAppAccess() {
        data class Viewer(val role: String?, val userId: String?, val creatorType: String?, val creatorId: String?)
        mapOf(
            Viewer("owner", "u-1", "user", "u-2") to true,
            Viewer("admin", "u-1", null, null) to true,
            Viewer(" Admin ", "u-1", null, null) to true,
            Viewer("member", "u-1", "user", "u-1") to true,
            Viewer("member", "u-1", "user", "u-2") to false,
            Viewer("member", "u-1", "agent", "u-1") to false,
            Viewer("guest", "u-1", "user", "u-2") to false,
            Viewer("member", null, "user", null) to false,
            Viewer(null, "u-1", "user", "u-2") to false,
        ).forEach { (viewer, expected) ->
            assertEquals(expected, canManageAgentAppAccess(viewer.role, viewer.userId, viewer.creatorType, viewer.creatorId), "$viewer")
        }
    }

    @Test
    fun anotherServersAgentOffersNoRevokeEvenToAnOwner() {
        val detail =
            AgentDetailSnapshot(
                agentId = MemberId("agent-1"),
                summary = AgentDetailSummary(id = "agent-1", name = "agent-1", creatorId = "u-2", creatorType = "user"),
                directMessages = emptyList(),
                reminders = emptyList(),
                activity = emptyList()
            )
        val server = ServerSummary(id = ServerId("server-1"), name = "Raft", role = "owner")

        assertEquals(
            mapOf(MemberDetailAccessMode.LocalFull to true, MemberDetailAccessMode.RemoteProfileOnly to false),
            MemberDetailAccessMode.entries.associateWith { mode ->
                MemberDetailRouteState.Agent(server = server, currentUserId = "u-1", accessMode = mode).viewerManagesAgentAppAccess(detail)
            }
        )
    }
}

private fun grant(id: String, clientId: String, clientName: String, scopes: List<String>, grantSource: String?, createdAt: String) =
    AgentAppLoginItem(
        id = id,
        type = AgentAppLoginType.Active,
        clientId = clientId,
        clientName = clientName,
        clientDescription = if (clientId == "stamp") "Agent login and notifications" else null,
        clientHomepageUrl = if (clientId == "stamp") "https://stamp.build" else null,
        scopes = scopes,
        createdAt = createdAt,
        grantSource = grantSource
    )

// The web test's Apps: Stamp holds the agent's sign-in and a notifications grant; Reminder App one grant.
private val STAMP_SIGN_IN =
    grant("g-stamp-login", "stamp", "Stamp", listOf("identity", "openid", "profile"), "agent_login", "2026-10-01T09:00:00.000Z")
private val STAMP_NOTIFY =
    grant("g-stamp-notify", "stamp", "Stamp", listOf("agent:notification:write"), "app_request", "2026-10-05T01:00:00.000Z")
private val REMINDER =
    grant("g-reminder", "reminder", "Reminder App", listOf("agent:notification:write"), "person", "2026-10-03T12:00:00.000Z")
