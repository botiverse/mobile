package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ThreadId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agent Lounge v0. The contract is botiverse/slock (slock-fb working tree, 2026-11-07):
 * packages/server/src/routes/agents.ts `POST /:id/lounge`, its api test
 * routes/agentLounge.api.test.ts, and the web rules in
 * packages/web/src/components/agent/agentLounge.ts.
 */
class AgentLoungeTest {
    @AfterTest
    fun forgetLounges() = RaftAgentLounges.clear()

    @Test
    fun theFlagIsRegisteredUnderTheServersKey() {
        assertEquals("agent_lounge_v0", AGENT_LOUNGE_FEATURE_FLAG_KEY)
        assertTrue(AGENT_LOUNGE_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse(AGENT_LOUNGE_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS, "default off")
    }

    @Test
    fun theGateIsTheFlagAndNotAGuest() {
        for ((flag, role, expected) in listOf(
            Triple(true, "member", true),
            Triple(true, "owner", true),
            Triple(true, null, true),
            Triple(true, "guest", false),
            Triple(true, "Guest", false),
            Triple(false, "member", false),
        )) {
            assertEquals(expected, agentLoungeEnabled(flag, role), "flag=$flag role=$role")
        }
    }

    @Test
    fun onlyALiveSharedLocalAgentOffersALounge() {
        assertTrue(agentOffersLounge(deleted = false, personalAssistant = false, remoteProfile = false))
        assertFalse(agentOffersLounge(deleted = true, personalAssistant = false, remoteProfile = false))
        assertFalse(agentOffersLounge(deleted = false, personalAssistant = true, remoteProfile = false))
        assertFalse(agentOffersLounge(deleted = false, personalAssistant = false, remoteProfile = true))
    }

    @Test
    fun aLoungeIsNamedAfterItsAgentNeverTheRawChannelName() {
        assertEquals("Bugen", loungeAgentDisplayName("Bugen", "bugen-lounge"))
        assertEquals("bugen", loungeAgentDisplayName(null, "bugen-lounge"))
        assertEquals("bugen", loungeAgentDisplayName("  ", "#bugen-lounge"))
        // The web strips only a trailing "-lounge" (`/-lounge$/`).
        assertEquals("bugen-lounge-2", loungeAgentDisplayName(null, "bugen-lounge-2"))
        assertEquals("Bugen", RaftAgentLounge(ChannelId("l"), "agent-bugen", agentName = "Bugen", channelName = "bugen-lounge").displayAgentName)
    }

    @Test
    fun theSidebarRowShowsOnlyOnceJoinedOrMentioned() {
        val lounge = ChannelId("lounge-bugen")
        assertNull(agentLoungeSidebarEntry(null, null), "an agent with no Lounge yet")
        assertEquals(
            AgentLoungeSidebarEntry(lounge, unreadCount = 3, mentioned = false),
            agentLoungeSidebarEntry(AgentLoungeSummary(lounge, joined = true, unreadCount = 3), null),
        )
        assertEquals(
            AgentLoungeSidebarEntry(lounge, unreadCount = 1, mentioned = true),
            agentLoungeSidebarEntry(AgentLoungeSummary(lounge, joined = false, unreadCount = 1, mentionCount = 1), null),
        )
        assertNull(agentLoungeSidebarEntry(AgentLoungeSummary(lounge, joined = false, unreadCount = 4), null))
        // Joining after the DM list loaded: the Lounge the client holds says so.
        assertEquals(
            AgentLoungeSidebarEntry(lounge, unreadCount = 0, mentioned = false),
            agentLoungeSidebarEntry(
                AgentLoungeSummary(lounge, joined = false),
                RaftAgentLounge(lounge, "agent-bugen", joined = true),
            ),
        )
    }

    @Test
    fun openingParsesTheLoungeRowAndTheAnswersOwnJoinedAndCanPost() {
        // routes/agentLounge.api.test.ts: 201 { channel: {...row, loungeAgentId, joined}, joined, canPost }.
        val created =
            agentLoungeOpenResponse(
                HomeNetworkResponse(
                    201,
                    """{"channel":{"id":"lounge-bugen","name":"bugen-lounge","type":"channel","description":null,""" +
                        """"archivedAt":null,"loungeAgentId":"agent-bugen","joined":false},"joined":false,"canPost":true}""",
                ),
                agentId = "agent-bugen",
            )
        assertIs<DataResult.Content<AgentLoungeOpenResult>>(created)
        assertEquals("lounge-bugen", created.data.channel.channelId.value)
        assertEquals("agent-bugen", created.data.channel.loungeAgentId)
        assertFalse(created.data.joined)
        assertTrue(created.data.canPost)

        // A server that left `loungeAgentId` off the row: still the Lounge of the agent asked for.
        val bare =
            parseSharedAgentLoungeOpenResult(
                """{"channel":{"id":"lounge-bugen","name":"bugen-lounge","type":"channel"},"joined":true,"canPost":false}""",
                agentId = "agent-bugen",
            )
        assertEquals("agent-bugen", bare?.channel?.loungeAgentId)
        assertEquals(true, bare?.joined)
        assertEquals(false, bare?.canPost)
        assertNull(parseSharedAgentLoungeOpenResult("""{"lounge":{}}""", "agent-bugen"), "no guessed envelope")
    }

    @Test
    fun openingFailuresKeepTheirReasonAndTraceId() {
        val traceHeaders = mapOf("X-Trace-Id" to listOf("0123456789abcdef0123456789abcdef"))
        val notFound = agentLoungeOpenResponse(HomeNetworkResponse(404, null, """{"error":"Not found"}""", responseHeaders = traceHeaders), "a")
        assertIs<DataResult.Error>(notFound)
        assertEquals(AGENT_LOUNGE_NOT_FOUND_ERROR_CODE, notFound.error.code)
        assertEquals("0123456789abcdef0123456789abcdef", notFound.agentLoungeOpenFailureTraceId())

        val guest =
            agentLoungeOpenResponse(
                HomeNetworkResponse(403, null, """{"error":"Guests cannot open an agent's lounge","code":"agent_lounge_guest"}"""),
                "a",
            )
        assertIs<DataResult.NoPermission>(guest)
        assertEquals(AGENT_LOUNGE_GUEST_ERROR_CODE, guest.blocker.code)

        val broken = agentLoungeOpenResponse(HomeNetworkResponse(200, """{"ok":true}"""), "a")
        assertIs<DataResult.Error>(broken)
    }

    @Test
    fun aLoungeIsKnownByIdFromItsDmRowAndFromItsOwnRow() {
        RaftAgentLounges.rememberDirectMessages(
            "server-1",
            listOf(
                HomeDirectMessageRow(
                    threadId = ThreadId("dm-bugen"),
                    channelId = ChannelId("dm-bugen"),
                    name = "Bugen",
                    presenceMemberId = MemberId("agent-bugen"),
                    lounge = AgentLoungeSummary(ChannelId("lounge-bugen"), joined = false),
                )
            ),
        )
        val fromDm = RaftAgentLounges.lounge("server-1", "lounge-bugen")
        assertEquals("agent-bugen", fromDm?.agentId)
        assertEquals("Bugen", fromDm?.displayAgentName)
        assertNull(RaftAgentLounges.lounge("server-2", "lounge-bugen"), "per server")
        assertEquals(fromDm, RaftAgentLounges.loungeOfAgent("server-1", "agent-bugen"))

        RaftAgentLounges.rememberRow(
            "server-1",
            HomeChannelRow(
                threadId = ThreadId("lounge-bugen"),
                channelId = ChannelId("lounge-bugen"),
                name = "bugen-lounge",
                joined = false,
                loungeAgentId = "agent-bugen",
            ),
            canPost = false,
        )
        val opened = RaftAgentLounges.lounge("server-1", "lounge-bugen")
        assertEquals("Bugen", opened?.displayAgentName, "the agent's name survives a row without it")
        assertEquals(false, opened?.postable)

        RaftAgentLounges.rememberRow(
            "server-1",
            HomeChannelRow(threadId = ThreadId("general"), channelId = ChannelId("general"), name = "general"),
        )
        assertNull(RaftAgentLounges.lounge("server-1", "general"), "an ordinary channel is not a Lounge")
    }
}
