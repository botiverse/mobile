package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.SearchSenderFilterItem
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.thread.key
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
