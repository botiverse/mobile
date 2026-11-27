package build.raft.app

import build.raft.app.data.parseSharedAgentProfile
import build.raft.app.data.parseSharedSettingsAgentsAsMembers
import build.raft.app.members.RaftAppBotBadgeLabel
import build.raft.app.members.RaftAppBotIdentity
import build.raft.app.members.appBotIdentityOrNull
import build.raft.app.members.isListedInMembers
import build.raft.app.members.modelLabel
import build.raft.app.members.raftAppBotBadgeLabel
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.thread.ThreadHeaderDisplayModel
import build.raft.app.thread.composerDmAgentPeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * App Bots on mobile, against botiverse/slock staging 6386cfe22dabc40479ecb35ba670df1f8233e9f4:
 *  - `GET /agents` / `GET /agents/:id` (`packages/server/src/routes/agents.ts`): App Bots carry
 *    `managedByAppInstallId`, `managedByApp: { clientId, name, logoUrl, description,
 *    botStatusText }` and `inMemberLists` (#11295, `appBotMemberListing.api.test.ts`); ordinary
 *    agents omit `inMemberLists`.
 *  - web `AppBadge` (#11273, renamed in #11845): "App" / "App · <App>"; no model on a bot's message row.
 *  - web `isRemovedAppBot` (#11303 / #11321): its DM and that DM's threads are read-only.
 */
class RaftAppBotTest {
    private val directory =
        """[
          {"id":"agent-bot","serverId":"srv-1","name":"stamp","displayName":"Stamp","status":"active",
           "runtime":"claude","model":"sonnet","managedByAppInstallId":"install-1","inMemberLists":true,
           "managedByApp":{"clientId":"client-1","name":"Stamp","logoUrl":"https://cdn.example/stamp.png","description":null,"botStatusText":null}},
          {"id":"agent-removed","serverId":"srv-1","name":"listing-bot","displayName":"Release helper","status":"active",
           "runtime":"claude","model":"sonnet","managedByAppInstallId":"install-2","inMemberLists":false,
           "managedByApp":{"clientId":"client-2","name":"Listing Bot App","logoUrl":null,"description":"Lists","botStatusText":"Ready"}},
          {"id":"agent-plain","serverId":"srv-1","name":"alpha","status":"active","runtime":"claude","model":"sonnet","managedByAppInstallId":null}
        ]"""

    private fun members() = parseSharedSettingsAgentsAsMembers(directory)!!.associateBy { it.id.value }

    @Test
    fun agentDirectoryParsesAppAttributionAndMemberListing() {
        val members = members()
        val bot = members.getValue("agent-bot")
        assertEquals("install-1", bot.managedByAppInstallId)
        assertEquals("Stamp", bot.managedByAppName)
        assertEquals(true, bot.inMemberLists)
        val removed = members.getValue("agent-removed")
        assertEquals("Listing Bot App", removed.managedByAppName)
        assertEquals(false, removed.inMemberLists)
        val plain = members.getValue("agent-plain")
        assertNull(plain.managedByAppName)
        assertNull(plain.inMemberLists)
    }

    @Test
    fun agentDetailParsesInMemberListsAndANullAppJoin() {
        val detail =
            parseSharedAgentProfile(
                """{"id":"agent-removed","serverId":"srv-1","name":"listing-bot","status":"active",
                   "managedByAppInstallId":"install-2","managedByApp":null,"inMemberLists":false}""",
            )
        assertNotNull(detail)
        assertEquals(false, detail.inMemberLists)
        assertNull(detail.managedByAppName)
        assertEquals(RaftAppBotIdentity(handle = "listing-bot", appName = null, removed = true), detail.appBotIdentityOrNull())
    }

    @Test
    fun membersPageLeavesOutRemovedBotsOnly() {
        val listed = members().values.filter { it.isListedInMembers() }.map { it.id.value }
        assertEquals(listOf("agent-bot", "agent-plain"), listed)
    }

    @Test
    fun appBotIdentityIsOnlyForManagedAgents() {
        val members = members()
        assertEquals(
            RaftAppBotIdentity(
                handle = "stamp",
                appName = "Stamp",
                removed = false,
                appClientId = "client-1",
                appLogoUrl = "https://cdn.example/stamp.png",
            ),
            members.getValue("agent-bot").appBotIdentityOrNull(),
        )
        assertEquals(
            RaftAppBotIdentity(
                handle = "listing-bot",
                appName = "Listing Bot App",
                removed = true,
                appClientId = "client-2",
                appLogoUrl = null,
            ),
            members.getValue("agent-removed").appBotIdentityOrNull(),
        )
        assertNull(members.getValue("agent-plain").appBotIdentityOrNull())
        assertNull(
            MemberProfile(id = MemberId("user-1"), displayName = "Ann", handle = "ann", kind = MemberKind.Human, managedByAppInstallId = "install-1")
                .appBotIdentityOrNull(),
        )
    }

    @Test
    fun badgeSaysBotWhenTheShownNameIsTheAppsName() {
        assertEquals(RaftAppBotBadgeLabel.Bot, raftAppBotBadgeLabel(appName = "Stamp", botName = "Stamp"))
        assertEquals(RaftAppBotBadgeLabel.Bot, raftAppBotBadgeLabel(appName = null, botName = "Release helper"))
        assertEquals(RaftAppBotBadgeLabel.Bot, raftAppBotBadgeLabel(appName = "  ", botName = "Release helper"))
    }

    @Test
    fun aBotsMessageRowShowsNoModel() {
        val members = members()
        assertNull(members.getValue("agent-bot").modelLabel())
        assertNull(members.getValue("agent-removed").modelLabel())
        assertEquals("Claude Sonnet", members.getValue("agent-plain").modelLabel())
    }

    @Test
    fun composerChecksTheDmPeerAndAThreadsParentDmPeer() {
        val botPeer =
            MemberDetailIdentity(MemberId("agent-removed"), MemberKind.Agent)
        val humanPeer =
            MemberDetailIdentity(MemberId("user-1"), MemberKind.Human)
        fun display(surface: String, dm: MemberDetailIdentity? = null, parentDm: MemberDetailIdentity? = null) =
            ThreadHeaderDisplayModel(
                title = "t",
                subtitle = null,
                surfaceKind = surface,
                channelKind = "dm",
                dmPeerIdentity = dm,
                replyParentDmPeerIdentity = parentDm,
            )
        assertEquals("agent-removed", display(RaftThreadSurfaceKind.DirectMessage, dm = botPeer).composerDmAgentPeerId())
        assertEquals("agent-removed", display(RaftThreadSurfaceKind.ReplyThread, parentDm = botPeer).composerDmAgentPeerId())
        assertNull(display(RaftThreadSurfaceKind.DirectMessage, dm = humanPeer).composerDmAgentPeerId())
        assertNull(display(RaftThreadSurfaceKind.Channel, dm = botPeer).composerDmAgentPeerId())
        assertNull(display(RaftThreadSurfaceKind.ReplyThread).composerDmAgentPeerId())
        assertTrue(display(RaftThreadSurfaceKind.ReplyThread, dm = botPeer).composerDmAgentPeerId() == null)
    }
}
