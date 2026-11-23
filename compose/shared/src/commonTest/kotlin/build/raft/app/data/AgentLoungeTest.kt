package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ThreadId
import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
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
            Triple(true, null, false),
            Triple(true, "", false),
            Triple(true, "guest", false),
            Triple(true, "Guest", false),
            Triple(false, "member", false),
        )) {
            assertEquals(expected, agentLoungeEnabled(flag, role), "flag=$flag role=$role (unknown role: off until it loads)")
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
    fun aLoungeIsNamedAfterItsAgentAndNeverFromItsChannelName() {
        // slock #16282: the agent's name, else none (the bare "Lounge"). The channel name is a reserved
        // slug fixed at creation (`_lounge-<agent>-<id4>`, slock #16272) that does not follow a rename.
        assertEquals("Bugen", loungeAgentDisplayName(" Bugen "))
        assertNull(loungeAgentDisplayName("  "))
        assertEquals("Bugen", RaftAgentLounge(ChannelId("l"), "agent-bugen", agentName = "Bugen", channelName = "_lounge-bugen-3f9a").displayAgentName)
        for (channelName in listOf("_lounge-bugen-3f9a", "bugen-lounge")) {
            assertNull(RaftAgentLounge(ChannelId("l"), "agent-bugen", channelName = channelName).displayAgentName, channelName)
        }
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
    fun theSidebarRowFollowsExplicitListedAndFallsBackToJoinedWhenAbsent() {
        val lounge = ChannelId("lounge-bugen")
        // #proj-chat:#44: non-member human who follows a thread (joined=false, listed=true) stays listed.
        assertEquals(
            AgentLoungeSidebarEntry(lounge, unreadCount = 2, mentioned = false),
            agentLoungeSidebarEntry(AgentLoungeSummary(lounge, joined = false, unreadCount = 2, listed = true), null),
        )
        // Explicit listed=false hides the row even if joined is stale, unless mentioned.
        assertNull(
            agentLoungeSidebarEntry(
                AgentLoungeSummary(lounge, joined = false, listed = false),
                RaftAgentLounge(lounge, "agent-bugen", joined = true, listed = false),
            ),
        )
        assertEquals(
            AgentLoungeSidebarEntry(lounge, unreadCount = 1, mentioned = true),
            agentLoungeSidebarEntry(
                AgentLoungeSummary(lounge, joined = false, unreadCount = 1, mentionCount = 1, listed = false),
                null,
            ),
        )
        // First post locally sets listed=true without falsely flipping joined=true on a new server.
        RaftAgentLounges.rememberRow("server-1", loungeRow().copy(joined = false), listed = false)
        assertNull(agentLoungeSidebarEntry(AgentLoungeSummary(lounge, joined = false, listed = false), RaftAgentLounges.lounge("server-1", "lounge-bugen")))
        RaftAgentLounges.markJoined("server-1", "lounge-bugen")
        val afterPost = RaftAgentLounges.lounge("server-1", "lounge-bugen")
        assertEquals(false, afterPost?.joined)
        assertEquals(true, afterPost?.listed)
        assertEquals(
            lounge,
            agentLoungeSidebarEntry(AgentLoungeSummary(lounge, joined = false, listed = false), afterPost)?.channelId,
        )
        // Subsequent server refresh with listed=false (e.g. unfollowed last thread) unlists cleanly.
        RaftAgentLounges.rememberDirectMessages(
            "server-1",
            listOf(
                HomeDirectMessageRow(
                    threadId = ThreadId("dm-bugen"),
                    channelId = ChannelId("dm-bugen"),
                    name = "Bugen",
                    presenceMemberId = MemberId("agent-bugen"),
                    lounge = AgentLoungeSummary(lounge, joined = false, listed = false),
                )
            ),
        )
        assertEquals(false, RaftAgentLounges.lounge("server-1", "lounge-bugen")?.listed)
        assertNull(agentLoungeSidebarEntry(AgentLoungeSummary(lounge, joined = false, listed = false), RaftAgentLounges.lounge("server-1", "lounge-bugen")))
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
                """{"channel":{"id":"lounge-bugen","name":"bugen-lounge","type":"channel"},"joined":true,"canPost":false,"listed":true}""",
                agentId = "agent-bugen",
            )
        assertEquals("agent-bugen", bare?.channel?.loungeAgentId)
        assertEquals(true, bare?.joined)
        assertEquals(false, bare?.canPost)
        assertEquals(true, bare?.listed)
        assertNull(created.data.listed, "older servers omit listed")
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

    private fun loungeRow(archived: Boolean = false) =
        HomeChannelRow(
            threadId = ThreadId("lounge-bugen"),
            channelId = ChannelId("lounge-bugen"),
            name = "bugen-lounge",
            joined = true,
            archived = archived,
            loungeAgentId = "agent-bugen",
        )

    /** Records requests and answers each with [answer]. */
    private class FakeNetwork(private val answer: (path: String) -> HomeNetworkResponse) : RaftNetworkClient {
        val paths = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            paths += "$method $path"
            return answer(path)
        }
    }

    @Test
    fun unarchivingALoungeOpenedWhileArchivedMakesItPostableAgain() = runTest {
        // POST /agents/:id/lounge on an archived Lounge answers canPost=false (computed from archivedAt).
        val opened =
            agentLoungeOpenResponse(
                HomeNetworkResponse(
                    200,
                    """{"channel":{"id":"lounge-bugen","name":"bugen-lounge","type":"channel","archivedAt":"2026-11-02T00:00:00.000Z",""" +
                        """"loungeAgentId":"agent-bugen","joined":true},"joined":true,"canPost":false}""",
                ),
                "agent-bugen",
            )
        assertIs<DataResult.Content<AgentLoungeOpenResult>>(opened)
        RaftAgentLounges.rememberRow("server-1", opened.data.channel, canPost = opened.data.canPost)
        assertEquals(false, RaftAgentLounges.lounge("server-1", "lounge-bugen")?.postable)

        val network =
            FakeNetwork {
                HomeNetworkResponse(200, """{"id":"lounge-bugen","name":"bugen-lounge","type":"channel","archivedAt":null,"loungeAgentId":"agent-bugen"}""")
            }
        val result = AgentLoungeApi(network).unarchive(ServerId("server-1"), ChannelId("lounge-bugen"))
        assertIs<DataResult.Content<HomeChannelRow>>(result)
        assertEquals(listOf("POST channels/lounge-bugen/unarchive"), network.paths)
        val lounge = RaftAgentLounges.lounge("server-1", "lounge-bugen")
        assertEquals(false, lounge?.archived)
        assertNull(lounge?.canPost, "the archived answer's canPost is forgotten")
        assertEquals(true, lounge?.postable)
    }

    @Test
    fun aLoungeRefLooksTheLoungeUpWithAGetAndNeverCreatesOne() = runTest {
        // slock #16297: GET /agents/:id/lounge answers POST's shape when the Lounge exists...
        val found =
            FakeNetwork {
                HomeNetworkResponse(
                    200,
                    """{"channel":{"id":"lounge-bugen","name":"bugen-lounge","type":"channel","archivedAt":null,""" +
                        """"loungeAgentId":"agent-bugen","joined":false},"joined":false,"canPost":true}""",
                )
            }
        val result = AgentLoungeApi(found).lookupLounge(ServerId("server-1"), "agent-bugen")
        assertIs<DataResult.Content<AgentLoungeOpenResult>>(result)
        assertEquals("lounge-bugen", result.data.channel.channelId.value)
        assertEquals(listOf("GET agents/agent-bugen/lounge"), found.paths)

        // ...and 404 agent_lounge_not_found, creating nothing, when the agent has none.
        val none = FakeNetwork { HomeNetworkResponse(404, """{"code":"agent_lounge_not_found","error":"Not found"}""") }
        val missing = AgentLoungeApi(none).lookupLounge(ServerId("server-1"), "agent-quiet")
        assertIs<DataResult.Error>(missing)
        assertEquals(AGENT_LOUNGE_NOT_FOUND_ERROR_CODE, missing.error.code)
        assertEquals(listOf("GET agents/agent-quiet/lounge"), none.paths)
    }

    @Test
    fun loungesNeverOutliveTheAccountOrTheServerTheyWereKnownFor() {
        RaftAgentLounges.bindAccount("user-a")
        RaftAgentLounges.rememberRow("server-1", loungeRow(), canPost = true)
        RaftAgentLounges.rememberRow("server-2", loungeRow().copy(channelId = ChannelId("lounge-2"), threadId = ThreadId("lounge-2")))

        RaftAgentLounges.forgetServer("server-1")
        assertNull(RaftAgentLounges.lounge("server-1", "lounge-bugen"), "removed from the server: its Lounges go")
        assertEquals("lounge-2", RaftAgentLounges.lounge("server-2", "lounge-2")?.channelId?.value)

        RaftAgentLounges.bindAccount("user-a")
        assertEquals("lounge-2", RaftAgentLounges.lounge("server-2", "lounge-2")?.channelId?.value, "same account keeps them")
        RaftAgentLounges.bindAccount("user-b")
        assertNull(RaftAgentLounges.lounge("server-2", "lounge-2"), "another account never sees user-a's Lounges")

        RaftAgentLounges.rememberRow("server-2", loungeRow())
        RaftAgentLounges.clear() // AccountLocalData.onSignOut
        assertTrue(RaftAgentLounges.lounges.value.isEmpty())
    }

    @Test
    fun yourFirstPostJoinsTheLounge() {
        RaftAgentLounges.rememberRow("server-1", loungeRow().copy(joined = false))
        assertNull(agentLoungeSidebarEntry(AgentLoungeSummary(ChannelId("lounge-bugen")), RaftAgentLounges.lounge("server-1", "lounge-bugen")))
        RaftAgentLounges.markJoined("server-1", "lounge-bugen")
        assertEquals(
            ChannelId("lounge-bugen"),
            agentLoungeSidebarEntry(AgentLoungeSummary(ChannelId("lounge-bugen")), RaftAgentLounges.lounge("server-1", "lounge-bugen"))?.channelId,
        )
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
