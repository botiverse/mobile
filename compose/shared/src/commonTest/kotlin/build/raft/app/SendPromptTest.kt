package build.raft.app

import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.parseSharedSettingsAgentsAsMembers
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ThreadId
import build.raft.app.thread.RaftMessageLinkIngress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class SendPromptTest {
    private val prod = "https://app.raft.build"
    private val staging = "https://staging.raft.build"
    private val noHosts = emptySet<String>()
    private val servers =
        listOf(
            StoredAuthServer(id = "srv-hq", name = "HQ", slug = "hq", officialOnboardingAgentId = "cindy"),
            StoredAuthServer(id = "srv-noslug", name = "NoSlug", slug = null),
        )

    private fun agent(
        id: String,
        serverRole: String? = "member",
        deletedAt: String? = null,
        raftbenchService: Boolean = false,
        assistantOwnerUserId: String? = null,
        membershipStatus: String? = null,
        displayName: String = id,
    ): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = displayName,
            handle = id,
            kind = MemberKind.Agent,
            serverRole = serverRole,
            agentDeletedAt = deletedAt,
            membershipStatus = membershipStatus,
            raftbenchService = raftbenchService,
            assistantOwnerUserId = assistantOwnerUserId,
        )

    private fun dmRow(
        channelId: String,
        peerId: String,
        peerType: String = "agent",
        lastMessageAt: String? = null,
    ): HomeDirectMessageRow =
        HomeDirectMessageRow(
            threadId = ThreadId(channelId),
            channelId = ChannelId(channelId),
            kind = ConversationKind.DirectMessage,
            name = peerId,
            directMessagePeerType = peerType,
            presenceMemberId = MemberId(peerId),
            lastMessageAt = lastMessageAt,
        )

    @Test
    fun parsesRelativeAndOwnOriginSendPromptLinks() {
        assertEquals(
            RaftSendPromptLink("Hello world"),
            parseRaftSendPromptLink("?dialog=send-prompt&prompt=Hello+world", configuredWebUrl = prod, appHosts = noHosts),
        )
        assertEquals(
            RaftSendPromptLink("Line 1\nLine 2 你好"),
            parseRaftSendPromptLink(
                "/?prompt=Line%201%0ALine%202%20%E4%BD%A0%E5%A5%BD&dialog=send-prompt&other=1#top",
                configuredWebUrl = prod,
                appHosts = noHosts,
            ),
        )
        assertEquals(
            RaftSendPromptLink("Check status"),
            parseRaftSendPromptLink(
                "$prod/s/hq?dialog=send-prompt&prompt=Check%20status",
                configuredWebUrl = prod,
                appHosts = noHosts,
                allowRelative = false,
            ),
        )
        assertEquals(
            RaftSendPromptLink("From open host"),
            parseRaftSendPromptLink(
                "https://open.raft.build/?dialog=send-prompt&prompt=From+open+host",
                configuredWebUrl = prod,
                appHosts = noHosts,
                allowRelative = false,
            ),
        )
        assertEquals(
            RaftSendPromptLink("Staging prompt"),
            parseRaftSendPromptLink(
                "$staging/s/hq/channel/abc?dialog=send-prompt&prompt=Staging+prompt",
                configuredWebUrl = staging,
                appHosts = noHosts,
                allowRelative = false,
            ),
        )
        assertEquals(
            RaftSendPromptLink("Custom host"),
            parseRaftSendPromptLink(
                "https://raft.example/?dialog=send-prompt&prompt=Custom+host",
                configuredWebUrl = prod,
                appHosts = setOf("raft.example"),
                allowRelative = false,
            ),
        )
    }

    @Test
    fun clampsPromptToMaxLengthAndIgnoresEmptyOrBlankPrompts() {
        val overlong = "a".repeat(SEND_PROMPT_MAX_LENGTH + 250)
        val parsed = parseRaftSendPromptLink("?dialog=send-prompt&prompt=$overlong", configuredWebUrl = prod, appHosts = noHosts)
        assertEquals(SEND_PROMPT_MAX_LENGTH, assertNotNull(parsed).prompt.length)

        // 4000 leading spaces + "x": clamped prefix is blank, matching web `.slice(0, MAX_PROMPT_LENGTH)` + `.trim()`.
        val padded = "%20".repeat(SEND_PROMPT_MAX_LENGTH) + "x"
        assertNull(parseRaftSendPromptLink("?dialog=send-prompt&prompt=$padded", configuredWebUrl = prod, appHosts = noHosts))

        for (blankUrl in listOf(
            "?dialog=send-prompt",
            "?dialog=send-prompt&prompt=",
            "?dialog=send-prompt&prompt=%20%09%0A",
            "$prod/?dialog=send-prompt&prompt=+++",
        )) {
            assertNull(parseRaftSendPromptLink(blankUrl, configuredWebUrl = prod, appHosts = noHosts), blankUrl)
            assertTrue(isRaftSendPromptIntentUrl(blankUrl, configuredWebUrl = prod, appHosts = noHosts), blankUrl)
        }
    }

    @Test
    fun rejectsOffOriginMalformedAndNonSendPromptLinks() {
        for (url in listOf(
            "https://evil.example/?dialog=send-prompt&prompt=hi",
            "https://app.raft.build.evil.example/?dialog=send-prompt&prompt=hi",
            "http://app.raft.build/?dialog=send-prompt&prompt=hi",
            "//app.raft.build/?dialog=send-prompt&prompt=hi",
            "$staging/?dialog=send-prompt&prompt=hi",
            "?dialog=other&prompt=hi",
            "?dialog=send-prompt&prompt=%ZZ",
            "javascript:alert(1)",
            "?dialog=send-prompt&prompt=hello world",
        )) {
            assertNull(parseRaftSendPromptLink(url, configuredWebUrl = prod, appHosts = noHosts), url)
            assertFalse(isRaftSendPromptIntentUrl(url, configuredWebUrl = prod, appHosts = noHosts), url)
        }
        assertNull(parseRaftSendPromptLink("?dialog=send-prompt&prompt=hi", configuredWebUrl = prod, appHosts = noHosts, allowRelative = false))
    }

    @Test
    fun announcementLinkActionRoutesSendPromptAndIgnoresBlankPrompt() {
        val relative =
            assertIs<AnnouncementLinkAction.SendPrompt>(
                announcementLinkAction("?dialog=send-prompt&prompt=Summarize+today", prod, "srv-hq", servers, noHosts)
            )
        assertEquals("Summarize today", relative.link.prompt)

        val noSlugServer =
            assertIs<AnnouncementLinkAction.SendPrompt>(
                announcementLinkAction("?dialog=send-prompt&prompt=Hello", prod, "srv-noslug", servers, noHosts)
            )
        assertEquals("Hello", noSlugServer.link.prompt)

        val ownOrigin =
            assertIs<AnnouncementLinkAction.SendPrompt>(
                announcementLinkAction("$prod/s/hq?dialog=send-prompt&prompt=Go", prod, "srv-hq", servers, noHosts)
            )
        assertEquals("Go", ownOrigin.link.prompt)

        // Empty or blank prompt on a send-prompt link is ignored rather than opening a browser.
        assertNull(announcementLinkAction("?dialog=send-prompt&prompt=%20", prod, "srv-hq", servers, noHosts))
        assertNull(announcementLinkAction("$prod/?dialog=send-prompt", prod, "srv-hq", servers, noHosts))
        // No current server -> ignored.
        assertNull(announcementLinkAction("?dialog=send-prompt&prompt=Hello", prod, null, servers, noHosts))
        // External origin is never hijacked into send-prompt.
        assertIs<AnnouncementLinkAction.OpenUrl>(
            announcementLinkAction("https://external.example/?dialog=send-prompt&prompt=Hi", prod, "srv-hq", servers, noHosts)
        )
    }

    @Test
    fun ownLinkRouteAndIngressClaimSendPromptLinks() {
        val url = "$prod/s/hq?dialog=send-prompt&prompt=Audit+logs"
        val route = assertIs<RaftOwnLinkRoute.SendPrompt>(raftOwnLinkRoute(url, configuredWebUrl = prod, appHosts = noHosts))
        assertEquals("Audit logs", route.link.prompt)

        val testInbox = RaftSendPromptLinkInbox(nowEpochMillis = { 1000L })
        val opener =
            raftOwnLinkShellOpener(
                webUrl = { prod },
                appHosts = { noHosts },
                sendPromptInbox = testInbox,
            )
        assertTrue(opener(url))
        val pending = assertNotNull(testInbox.pending.value)
        assertEquals("Audit logs", pending.link.prompt)
        assertEquals(pending, testInbox.take(pending.id))
        assertNull(testInbox.take(pending.id))

        RaftSendPromptLinkInbox.Process.clear()
        try {
            assertTrue(RaftMessageLinkIngress.accept(url, source = "android_intent", configuredWebUrl = prod))
            val takenWeb = assertNotNull(RaftSendPromptLinkInbox.Process.pending.value)
            assertEquals("Audit logs", takenWeb.link.prompt)

            assertTrue(
                RaftMessageLinkIngress.accept(
                    "raft:///s/hq?dialog=send-prompt&prompt=From+desktop+scheme",
                    source = "desktop_scheme",
                    configuredWebUrl = prod,
                )
            )
            val takenScheme = assertNotNull(RaftSendPromptLinkInbox.Process.pending.value)
            assertEquals("From desktop scheme", takenScheme.link.prompt)
        } finally {
            RaftSendPromptLinkInbox.Process.clear()
        }
    }

    @Test
    fun recipientFilteringMatchesWebVerbatim() {
        val human =
            MemberProfile(
                id = MemberId("u-human"),
                displayName = "Human",
                handle = "human",
                kind = MemberKind.Human,
            )
        val list =
            listOf(
                human,
                agent("a", serverRole = null, deletedAt = "2026-11-22T00:00:00Z"),
                agent("b", serverRole = null, raftbenchService = true),
                agent("c", serverRole = null, assistantOwnerUserId = "u2"),
                agent("d", serverRole = null, assistantOwnerUserId = "u1"),
                agent("e"),
                agent("f", membershipStatus = "deleted"),
            )
        assertEquals(listOf("d", "e"), sendPromptRecipients(list, currentUserId = "u1").map { it.id.value })
        assertEquals(listOf("e"), sendPromptRecipients(list, currentUserId = null).map { it.id.value })
    }

    @Test
    fun defaultSendPromptAgentPriorityMatchesWebVerbatim() {
        val all = listOf(agent("a"), agent("b", serverRole = "admin"), agent("c"), agent("cindy"))

        // 1. Official onboarding agent first
        val official = defaultSendPromptAgent(all, officialOnboardingAgentId = "cindy", recentAgentIds = listOf("b", "a"))
        assertEquals("cindy", official.agent?.id?.value)
        assertEquals(SendPromptDefaultAgentKind.OfficialOnboarding, official.kind)

        // 2. Most recently DM'd admin agent
        val recentAdmin = defaultSendPromptAgent(all, officialOnboardingAgentId = null, recentAgentIds = listOf("a", "b"))
        assertEquals("b", recentAdmin.agent?.id?.value)
        assertEquals(SendPromptDefaultAgentKind.RecentAdmin, recentAdmin.kind)

        // 3. Most recently DM'd agent when official is absent/gone and no admin was DM'd
        val recentMember = defaultSendPromptAgent(all, officialOnboardingAgentId = "gone", recentAgentIds = listOf("c", "a"))
        assertEquals("c", recentMember.agent?.id?.value)
        assertEquals(SendPromptDefaultAgentKind.RecentMember, recentMember.kind)

        // 4. First recipient, else none
        val first = defaultSendPromptAgent(all, officialOnboardingAgentId = null, recentAgentIds = emptyList())
        assertEquals("a", first.agent?.id?.value)
        assertEquals(SendPromptDefaultAgentKind.FirstAgent, first.kind)

        val none = defaultSendPromptAgent(emptyList(), officialOnboardingAgentId = null, recentAgentIds = emptyList())
        assertNull(none.agent)
        assertEquals(SendPromptDefaultAgentKind.None, none.kind)
    }

    @Test
    fun orderSendPromptAgentsListsSuggestedFirstThenRecentThenListedOrder() {
        val all = listOf(agent("a"), agent("b", serverRole = "admin"), agent("c"), agent("cindy"))
        assertEquals(
            listOf("cindy", "c", "b", "a"),
            orderSendPromptAgents(all, suggestedId = "cindy", recentAgentIds = listOf("c", "b")).map { it.id.value },
        )
        assertEquals(
            listOf("b", "c", "a", "cindy"),
            orderSendPromptAgents(all, suggestedId = "b", recentAgentIds = listOf("b", "c")).map { it.id.value },
        )
        assertEquals(
            listOf("a", "b", "c", "cindy"),
            orderSendPromptAgents(all, suggestedId = null, recentAgentIds = emptyList()).map { it.id.value },
        )
    }

    @Test
    fun previewGroupingMarksDayDividersAndGroupsSameSenderMessages() {
        val day1a = 1_795_000_000_000L
        val day1b = day1a + 60_000L
        val day2 = day1a + 24 * 60 * 60_000L
        val messages =
            listOf(
                SendPromptPreviewMessage(
                    id = "m1",
                    senderName = "Cindy",
                    content = "First",
                    senderId = "agent-cindy",
                    senderKind = MemberKind.Agent,
                    createdAtEpochMillis = day1a,
                ),
                SendPromptPreviewMessage(
                    id = "m2",
                    senderName = "Cindy",
                    content = "Second same day",
                    senderId = "agent-cindy",
                    senderKind = MemberKind.Agent,
                    createdAtEpochMillis = day1b,
                ),
                SendPromptPreviewMessage(
                    id = "m3",
                    senderName = "System",
                    content = "Joined",
                    senderId = "sys",
                    senderKind = MemberKind.System,
                    createdAtEpochMillis = day1b + 1000L,
                    isSystem = true,
                ),
                SendPromptPreviewMessage(
                    id = "m4",
                    senderName = "Cindy",
                    content = "After system",
                    senderId = "agent-cindy",
                    senderKind = MemberKind.Agent,
                    createdAtEpochMillis = day1b + 2000L,
                ),
                SendPromptPreviewMessage(
                    id = "m5",
                    senderName = "Cindy",
                    content = "Next day",
                    senderId = "agent-cindy",
                    senderKind = MemberKind.Agent,
                    createdAtEpochMillis = day2,
                ),
            )
        val rows = computeSendPromptPreviewGrouping(messages)
        assertEquals(listOf(true, false, false, false, true), rows.map { it.showDayDivider })
        assertEquals(listOf(true, false, false, true, true), rows.map { it.showHeader })
    }

    @Test
    fun recentAgentIdsAndDmMapFollowLastMessageAtDescending() {
        val dms =
            listOf(
                dmRow("dm-1", "a", lastMessageAt = "2026-11-20T10:00:00Z"),
                dmRow("dm-2", "u-human", peerType = "user", lastMessageAt = "2026-11-23T10:00:00Z"),
                dmRow("dm-3", "b", lastMessageAt = "2026-11-22T15:00:00Z"),
                dmRow("dm-4", "c", lastMessageAt = null),
            )
        assertEquals(listOf("b", "a"), recentSendPromptAgentIds(dms))
        assertEquals(
            mapOf("a" to ChannelId("dm-1"), "b" to ChannelId("dm-3"), "c" to ChannelId("dm-4")),
            sendPromptDmChannelByAgent(dms),
        )
    }

    @Test
    fun wireParsingPreservesOfficialOnboardingAgentIdAndRaftbenchService() {
        val json = Json { ignoreUnknownKeys = true }
        val server =
            json.decodeFromString(
                StoredAuthServer.serializer(),
                """{"id":"srv-1","name":"HQ","slug":"hq","onboardingAgentId":"old-1","officialOnboardingAgentId":"cindy-1"}""",
            )
        assertEquals("cindy-1", server.officialOnboardingAgentId)
        assertEquals("cindy-1", server.toServerSummary().officialOnboardingAgentId)

        val parsedAgents =
            assertNotNull(
                parseSharedSettingsAgentsAsMembers(
                    """[
                        {"id":"bench-1","name":"bench","raftbenchService":true},
                        {"id":"normal-1","name":"cindy","serverRole":"admin","raftbenchService":false}
                    ]"""
                )
            )
        assertEquals(true, parsedAgents.first { it.id.value == "bench-1" }.raftbenchService)
        assertEquals(false, parsedAgents.first { it.id.value == "normal-1" }.raftbenchService)
        assertEquals(listOf("normal-1"), sendPromptRecipients(parsedAgents, currentUserId = "u1").map { it.id.value })
    }

    @Test
    fun waitForAgentsGateWaitsOnlyUntilFirstListOrError() {
        assertTrue(shouldWaitForSendPromptAgents(SharedServerMembersEntry(serverId = "s1", status = SharedServerMembersLoadStatus.Idle)))
        assertTrue(shouldWaitForSendPromptAgents(SharedServerMembersEntry(serverId = "s1", status = SharedServerMembersLoadStatus.InFlight)))
        assertTrue(
            shouldWaitForSendPromptAgents(
                SharedServerMembersEntry(serverId = "s1", status = SharedServerMembersLoadStatus.Loaded, seededFromDurable = true)
            )
        )
        assertFalse(
            shouldWaitForSendPromptAgents(
                SharedServerMembersEntry(
                    serverId = "s1",
                    members = listOf(agent("a")),
                    status = SharedServerMembersLoadStatus.InFlight,
                )
            )
        )
        assertFalse(
            shouldWaitForSendPromptAgents(
                SharedServerMembersEntry(serverId = "s1", status = SharedServerMembersLoadStatus.Loaded, seededFromDurable = false)
            )
        )
        assertFalse(
            shouldWaitForSendPromptAgents(
                SharedServerMembersEntry(serverId = "s1", status = SharedServerMembersLoadStatus.Error)
            )
        )
    }
}
