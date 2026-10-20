package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.SearchSenderFilterItem
import build.raft.app.data.SearchFrequentEntityRank
import build.raft.app.data.SearchPersonalizationEntityKey
import build.raft.app.data.SearchPersonalizationEntityKind
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsHomeSearchEntityRankingTest {
    @Test
    fun realHomeSearchConsumerSharesPinyinInitialsAndFuzzyRanking() {
        assertEquals(listOf("conversation:channel-pinyin"), keysFor("duihua"))
        assertEquals(listOf("conversation:channel-pinyin"), keysFor("dh"))
        assertEquals(listOf("sender:server-1:Human:human-pinyin"), keysFor("zhaoziqi"))
        assertEquals(listOf("sender:server-1:Human:human-pinyin"), keysFor("zzq"))
        // task #238: `dm-agent` carries presenceMemberId "agent-pinyin" on server-1, i.e. it is the
        // *same* entity as the sender row. These two assertions previously pinned the duplicate
        // that was reported (one DM row + one member row for one principal). The member row is now
        // the single surviving row and carries the DM's launch identity, so ranking is asserted
        // over one key rather than two.
        assertEquals(listOf("sender:server-1:Agent:agent-pinyin"), keysFor("xiaoming"))
        assertEquals(listOf("sender:server-1:Agent:agent-pinyin"), keysFor("xmzs"))

        val fuzzy = resultsFor("gnrl")
        assertEquals("conversation:channel-prefix", fuzzy.first().key)
        assertTrue(fuzzy.any { it.key == "conversation:channel-substring" })
    }

    @Test
    fun realHomeSearchConsumerRanksAcrossEntityTypesAndNormalizesPrefixes() {
        val unified = resultsFor("gen")
        assertEquals("sender:server-1:Human:human-exact", unified.first().key)
        assertEquals(SearchEntityType.Human, unified.first().type)

        assertEquals(
            listOf("conversation:channel-pinyin"),
            keysFor("#duihua")
        )
        assertEquals(
            listOf("sender:server-1:Human:human-pinyin"),
            keysFor("@zhaoziqi")
        )
        assertEquals(
            listOf("sender:server-1:Agent:agent-pinyin"),
            keysFor("@xmzs")
        )
    }

    @Test
    fun realHomeSearchConsumerPreservesConversationAndPinProjection() {
        val archived = resultsFor("archive").single()
        assertEquals(SearchEntityType.Channel, archived.type)
        assertTrue(archived.archived)
        assertNull(searchEntityPinTarget(archived))

        val human = resultsFor("zoe").first { it.type == SearchEntityType.Human }
        assertEquals("dm-human", human.threadId)
        assertEquals("human-dm", human.memberId)
        assertEquals("server-1", human.memberIdentity?.sourceServerId?.value)
        assertEquals("dm-human", human.conversationIdentity?.threadId?.value)
        assertEquals("human", searchEntityPinTarget(human)?.kind)
    }

    @Test
    fun queryAndFrequentChannelRowsDistinguishPublicPrivateAndJointLeadingIcons() {
        val joint = resultsFor("joint-bridge").single()
        val public = resultsFor("general").first { it.key == "conversation:channel-prefix" }
        val private = resultsFor("private-room").single()

        assertEquals(RaftLucideIconName.GitBranch, joint.homeSearchEntityLeadingIconName())
        assertEquals(RaftLucideIconName.Hash, public.homeSearchEntityLeadingIconName())
        assertEquals(RaftLucideIconName.Lock, private.homeSearchEntityLeadingIconName())

        val frequent =
            homeSearchFrequentEntityResults(
                homeState = entityHomeState,
                senderFilters = entitySenders,
                ranks =
                    mapOf(
                        SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, "channel-joint") to
                            SearchFrequentEntityRank(score = 2.0, lastOpenedAtMillis = 2L),
                        SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, "channel-private") to
                            SearchFrequentEntityRank(score = 1.0, lastOpenedAtMillis = 1L),
                    ),
                channelLabel = "Channel",
                dmLabel = "DM",
                agentLabel = "Agent",
                personLabel = "Person",
                membersLabel = "Members",
            )

        assertEquals(
            listOf(RaftLucideIconName.GitBranch, RaftLucideIconName.Lock),
            frequent.map { it.homeSearchEntityLeadingIconName() },
        )
    }

    @Test
    fun blankQueryReturnsBeforeHomeContentIsAvailable() {
        val results =
            homeSearchEntityResults(
                query = "  ",
                homeState = SharedHomeState(),
                senderFilters = entitySenders,
                channelLabel = "Channel",
                dmLabel = "DM",
                agentLabel = "Agent",
                personLabel = "Person",
                membersLabel = "Members"
            )

        assertEquals(emptyList(), results)
    }

    @Test
    fun computersUseWebFieldPriorityAndStayOutOfPrefixSearches() {
        val directory =
            HomeSearchComputerDirectory(
                serverId = "server-1",
                machines =
                    listOf(
                        computer(
                            id = "machine-1",
                            name = "Orion Workstation",
                            hostname = "build-host",
                            description = "Render Farm",
                            os = "Darwin",
                        ),
                        computer(id = "agent-runtime", name = "Orion Agent", isComputer = false),
                    ),
                hasSnapshot = true,
            )

        listOf("Orion Workstation", "build-host", "Render Farm", "Darwin").forEach { query ->
            val result = resultsFor(query, directory).single { it.type == SearchEntityType.Computer }
            assertEquals("computer:server-1:machine-1", result.key)
            assertEquals("build-host", result.subtitle)
        }
        assertTrue(resultsFor("#Orion", directory).none { it.type == SearchEntityType.Computer })
        assertTrue(resultsFor("@Orion", directory).none { it.type == SearchEntityType.Computer })
        assertTrue(resultsFor("Orion Agent", directory).none { it.type == SearchEntityType.Computer })
    }

    @Test
    fun computersAreServerFencedDeduplicatedAndNavigateByAtomicIdentity() {
        val machine = computer(id = "machine-1", name = "Build Mac", hostname = "build.local")
        val matching =
            resultsFor(
                query = "Build Mac",
                computerDirectory =
                    HomeSearchComputerDirectory(
                        serverId = "server-1",
                        machines = listOf(machine, machine),
                        hasSnapshot = true,
                    ),
            ).filter { it.type == SearchEntityType.Computer }
        assertEquals(1, matching.size)
        assertEquals(
            HomeSearchEntityDestination.Computer(SettingsComputerRouteIdentity("server-1", "machine-1")),
            matching.single().destinationOrNull(),
        )
        assertNull(searchEntityPinTarget(matching.single()))

        val leaked =
            resultsFor(
                query = "Build Mac",
                computerDirectory =
                    HomeSearchComputerDirectory(
                        serverId = "server-2",
                        machines = listOf(machine),
                        hasSnapshot = true,
                    ),
            )
        assertTrue(leaked.none { it.type == SearchEntityType.Computer })
    }

    @Test
    fun frequentProjectionUsesOnlyAuthoritativeEligibleEntitiesAndDeterministicRanking() {
        val content = entityHomeState.content as SharedHomeContentState.Content
        val stateWithInaccessibleEntities =
            entityHomeState.copy(
                content =
                    content.copy(
                        snapshot =
                            content.snapshot.copy(
                                channels =
                                    content.snapshot.channels +
                                        HomeChannelRow(
                                            threadId = ThreadId("channel-unjoined"),
                                            channelId = ChannelId("channel-unjoined"),
                                            name = "unjoined",
                                            joined = false,
                                        ) +
                                        HomeChannelRow(
                                            threadId = ThreadId("channel-denied"),
                                            channelId = ChannelId("channel-denied"),
                                            name = "denied",
                                            permissions = ConversationPermissions(canRead = false),
                                        ),
                                directMessages =
                                    content.snapshot.directMessages +
                                        HomeDirectMessageRow(
                                            threadId = ThreadId("dm-hidden"),
                                            channelId = ChannelId("dm-hidden"),
                                            name = "Hidden person",
                                        ),
                                sidebarOrder = SettingsServerSidebarOrderSnapshot(hiddenDmIds = listOf("dm-hidden")),
                            )
                    )
            )
        val ranks =
            mapOf(
                SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, "channel-archived") to
                    SearchFrequentEntityRank(score = 99.0, lastOpenedAtMillis = 99L),
                SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Human, "human-dm") to
                    SearchFrequentEntityRank(score = 2.0, lastOpenedAtMillis = 20L),
                SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, "channel-prefix") to
                    SearchFrequentEntityRank(score = 2.0, lastOpenedAtMillis = 20L),
                SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, "deleted-channel") to
                    SearchFrequentEntityRank(score = 100.0, lastOpenedAtMillis = 100L),
                SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, "channel-unjoined") to
                    SearchFrequentEntityRank(score = 100.0, lastOpenedAtMillis = 100L),
                SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, "channel-denied") to
                    SearchFrequentEntityRank(score = 100.0, lastOpenedAtMillis = 100L),
                SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.DirectMessage, "dm-hidden") to
                    SearchFrequentEntityRank(score = 100.0, lastOpenedAtMillis = 100L),
            )

        val results =
            homeSearchFrequentEntityResults(
                homeState = stateWithInaccessibleEntities,
                senderFilters = entitySenders,
                ranks = ranks,
                channelLabel = "Channel",
                dmLabel = "DM",
                agentLabel = "Agent",
                personLabel = "Person",
                membersLabel = "Members",
            )

        assertEquals(
            listOf("conversation:channel-prefix", "sender:server-1:Human:human-dm"),
            results.map { it.key },
        )
        assertTrue(results.none { it.archived })
    }

    @Test
    fun searchHomeVisualTypographyStressChangesRealTextMetricsWithoutChangingDefault() {
        val base = RaftType.Body

        assertEquals(base, base.withHomeSearchVisualTextScale(1f))

        val large = base.withHomeSearchVisualTextScale(1.3f)
        assertEquals(base.fontSize * 1.3f, large.fontSize)
        assertEquals(base.lineHeight * 1.3f, large.lineHeight)
        assertEquals(base.letterSpacing * 1.3f, large.letterSpacing)
    }

    private fun keysFor(query: String): List<String> = resultsFor(query).map { it.key }

    private fun resultsFor(
        query: String,
        computerDirectory: HomeSearchComputerDirectory = HomeSearchComputerDirectory(),
    ): List<SearchEntityResult> =
        homeSearchEntityResults(
            query = query,
            homeState = entityHomeState,
            senderFilters = entitySenders,
            channelLabel = "Channel",
            dmLabel = "DM",
            agentLabel = "Agent",
            personLabel = "Person",
            membersLabel = "Members",
            computerLabel = "Computer",
            computerDirectory = computerDirectory,
        )

    private fun computer(
        id: String,
        name: String,
        hostname: String? = null,
        description: String? = null,
        os: String? = null,
        isComputer: Boolean = true,
    ) =
        ComputerSummary(
            id = id,
            name = name,
            status = ComputerStatus.Unknown,
            statusVersion = 0,
            apiKeyPrefix = null,
            runtimes = emptyList(),
            daemonVersion = null,
            computerVersion = null,
            isComputer = isComputer,
            hostname = hostname,
            os = os,
            description = description,
            lastHeartbeat = null,
            createdAt = null,
        )

    private companion object {
        val entitySenders =
            listOf(
                SearchSenderFilterItem(
                    id = "human-pinyin",
                    label = "赵子奇",
                    handle = "zhaozq",
                    kind = MemberKind.Human,
                    sourceServerId = ServerId("server-1")
                ),
                SearchSenderFilterItem(
                    id = "human-exact",
                    label = "gen",
                    handle = "general-person",
                    kind = MemberKind.Human,
                    sourceServerId = ServerId("server-1")
                ),
                SearchSenderFilterItem(
                    id = "human-dm",
                    label = "Zoe Person",
                    handle = "zoe",
                    kind = MemberKind.Human,
                    sourceServerId = ServerId("server-1")
                ),
                SearchSenderFilterItem(
                    id = "agent-pinyin",
                    label = "小明助手",
                    handle = "assistant-xm",
                    kind = MemberKind.Agent,
                    sourceServerId = ServerId("server-1")
                )
            )

        val entityHomeState =
            SharedHomeState(
                content =
                    SharedHomeContentState.Content(
                        snapshot =
                            HomeSnapshot(
                                server = ServerSummary(ServerId("server-1"), "Raft"),
                                currentMember =
                                    MemberProfile(
                                        id = MemberId("user-1"),
                                        displayName = "Current User",
                                        handle = "current",
                                        kind = MemberKind.Human
                                    ),
                                channels =
                                    listOf(
                                        HomeChannelRow(
                                            threadId = ThreadId("channel-pinyin"),
                                            channelId = ChannelId("channel-pinyin"),
                                            name = "对话流专修"
                                        ),
                                        HomeChannelRow(
                                            threadId = ThreadId("channel-substring"),
                                            channelId = ChannelId("channel-substring"),
                                            name = "eng-general"
                                        ),
                                        HomeChannelRow(
                                            threadId = ThreadId("channel-prefix"),
                                            channelId = ChannelId("channel-prefix"),
                                            name = "general"
                                        ),
                                        HomeChannelRow(
                                            threadId = ThreadId("channel-joint"),
                                            channelId = ChannelId("channel-joint"),
                                            kind = ConversationKind.Joint,
                                            name = "joint-bridge"
                                        ),
                                        HomeChannelRow(
                                            threadId = ThreadId("channel-private"),
                                            channelId = ChannelId("channel-private"),
                                            channelType = "private",
                                            name = "private-room"
                                        ),
                                        HomeChannelRow(
                                            threadId = ThreadId("channel-archived"),
                                            channelId = ChannelId("channel-archived"),
                                            name = "archive-room",
                                            archived = true
                                        )
                                    ),
                                directMessages =
                                    listOf(
                                        HomeDirectMessageRow(
                                            threadId = ThreadId("dm-human"),
                                            channelId = ChannelId("dm-human"),
                                            kind = ConversationKind.DirectMessage,
                                            name = "Zoe Person",
                                            presenceMemberId = MemberId("human-dm"),
                                            directMessagePeerType = "human"
                                        ),
                                        HomeDirectMessageRow(
                                            threadId = ThreadId("dm-agent"),
                                            channelId = ChannelId("dm-agent"),
                                            kind = ConversationKind.DirectMessage,
                                            name = "小明助手",
                                            presenceMemberId = MemberId("agent-pinyin"),
                                            directMessagePeerType = "agent"
                                        )
                                    ),
                                unreadTotal = 0
                            ),
                        session = authSessionFixture()
                    )
            )
    }
}
