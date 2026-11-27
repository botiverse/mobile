package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTestFakes.FakeMemberRepository
import build.raft.app.base.BridgeModule
import build.raft.app.cmp.RaftCmpToastWindow
import build.raft.app.cmp.RaftCmpToasts
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.Module
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ChannelMemberStillInChannel
import build.raft.app.data.ChannelMemberStillInChannelServer
import build.raft.app.data.DataResult
import build.raft.app.data.MemberSnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberServerRef
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadChannelMemberCommands
import build.raft.app.thread.ThreadRemoveChannelMemberResult
import build.raft.app.thread.threadMemberRemovalConfirmation
import build.raft.app.thread.toRaftThreadMemberItem
import build.raft.designsystem.components.RaftToastActionTimeoutMillis
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.components.RaftToastTimeoutMillis
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelMemberStillInChannelToastTest {
    private val transport = HostBridgeTransport { _, _, _ -> }
    private val capabilities = BridgeWireHostCapabilities(transport)
    private val navigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) = Unit
            override fun closePage() = Unit
        }
    private val windows = mutableListOf<RaftCmpToastWindow>()

    private fun bridge(): BridgeModule {
        val pager =
            object : Pager() {
                override fun createExternalModules(): Map<String, Module> = mapOf(BridgeModule.MODULE_NAME to BridgeModule())
            }
        pager.pagerId = "still-in-channel-toast-test"
        pager.pageData = PageData(JSONObject())
        pager.host = RaftCmpHost(capabilities, navigator, bridgeTransport = transport)
        return pager.acquireModule(BridgeModule.MODULE_NAME)
    }

    private fun foregroundWindow(): RaftCmpToastWindow =
        RaftCmpToasts.attach(RaftToastHostState()).also { window ->
            window.setLifecycle(visible = true, resumed = true)
            window.addOrigin(capabilities)
            windows += window
        }

    @AfterTest
    fun detachWindows() {
        windows.forEach { it.detach() }
        windows.clear()
    }

    @Test
    fun formatStillInChannelUsesLocalizedTemplateAndLocaleAwareListFormatting() {
        val single =
            ChannelMemberStillInChannel(
                through = listOf(ChannelMemberStillInChannelServer("s1", "Server A")),
                text = "fallback text",
            )
        assertEquals(
            "Alice is still in the channel through Server A. Only your server’s side of their membership was removed.",
            formatStillInChannelToastMessage(single, "Alice", EnglishStrings),
        )

        val pair =
            ChannelMemberStillInChannel(
                through =
                    listOf(
                        ChannelMemberStillInChannelServer("s1", "Server A"),
                        ChannelMemberStillInChannelServer("s2", "Server B"),
                    ),
                text = "fallback text",
            )
        assertEquals(
            "Alice is still in the channel through Server A and Server B. Only your server’s side of their membership was removed.",
            formatStillInChannelToastMessage(pair, "Alice", EnglishStrings),
        )
        assertEquals(
            "Alice 还通过 Server A和Server B 留在频道里，只移除了你这个服务器那一侧的成员身份。",
            formatStillInChannelToastMessage(pair, "Alice", SimplifiedChineseStrings),
        )

        val triple =
            ChannelMemberStillInChannel(
                through =
                    listOf(
                        ChannelMemberStillInChannelServer("s1", "Server A"),
                        ChannelMemberStillInChannelServer("s-blank", "   "),
                        ChannelMemberStillInChannelServer("s2", "Server B"),
                        ChannelMemberStillInChannelServer("s3", "Server C"),
                    ),
                text = "fallback text",
            )
        assertEquals(
            "Alice is still in the channel through Server A, Server B, and Server C. Only your server’s side of their membership was removed.",
            formatStillInChannelToastMessage(triple, "Alice", EnglishStrings),
        )
        assertEquals(
            "Alice 还通过 Server A、Server B和Server C 留在频道里，只移除了你这个服务器那一侧的成员身份。",
            formatStillInChannelToastMessage(triple, "Alice", SimplifiedChineseStrings),
        )
        assertEquals(
            "Alice は Server A、Server B、Server C 経由で引き続きチャンネルに参加しています。削除されたのは、あなたのサーバー側からの参加だけです。",
            formatStillInChannelToastMessage(triple, "Alice", JapaneseStrings),
        )
    }

    @Test
    fun formatStillInChannelFallsBackToServerTextAndReturnsNullWhenUnusable() {
        val textOnly =
            ChannelMemberStillInChannel(
                through = emptyList(),
                text = "  Server-supplied explanation.  ",
            )
        assertEquals(
            "Server-supplied explanation.",
            formatStillInChannelToastMessage(textOnly, "Alice", EnglishStrings),
        )

        val blankServerNamesWithText =
            ChannelMemberStillInChannel(
                through = listOf(ChannelMemberStillInChannelServer("s1", "   ")),
                text = "Server-supplied explanation.",
            )
        assertEquals(
            "Server-supplied explanation.",
            formatStillInChannelToastMessage(blankServerNamesWithText, "Alice", EnglishStrings),
        )

        val withServersAndText =
            ChannelMemberStillInChannel(
                through = listOf(ChannelMemberStillInChannelServer("s1", "Server A")),
                text = "Server-supplied explanation.",
            )
        assertEquals(
            "Server-supplied explanation.",
            formatStillInChannelToastMessage(withServersAndText, "   ", EnglishStrings),
        )
        assertEquals(
            "Server-supplied explanation.",
            formatStillInChannelToastMessage(withServersAndText, null, EnglishStrings),
        )

        assertNull(formatStillInChannelToastMessage(null, "Alice", EnglishStrings))
        assertNull(
            formatStillInChannelToastMessage(
                ChannelMemberStillInChannel(
                    through = listOf(ChannelMemberStillInChannelServer("s1", "Server A")),
                    text = "   ",
                ),
                personName = null,
                strings = EnglishStrings,
            )
        )
        assertNull(
            formatStillInChannelToastMessage(
                ChannelMemberStillInChannel(through = emptyList(), text = null),
                personName = "Alice",
                strings = EnglishStrings,
            )
        )
    }

    @Test
    fun personNameAvoidsRawIdAndConfirmationCarriesItCleanly() {
        val named =
            RaftThreadMemberItem(
                id = "user-1",
                displayName = "Alice",
                handle = "alice",
                kind = "human",
                canRemove = true,
            )
        assertEquals("Alice", named.stillInChannelPersonName())

        val handleOnly =
            RaftThreadMemberItem(
                id = "user-2",
                displayName = "   ",
                handle = "@bob",
                kind = "human",
                canRemove = true,
            )
        assertEquals("bob", handleOnly.stillInChannelPersonName())

        val idOnly =
            RaftThreadMemberItem(
                id = "user-3",
                displayName = "",
                handle = "   ",
                kind = "human",
                canRemove = true,
            )
        assertNull(idOnly.stillInChannelPersonName())

        val confirmation =
            checkNotNull(
                threadMemberRemovalConfirmation(
                    member = idOnly,
                    serverId = ServerId("server-1"),
                    channelId = ChannelId("channel-1"),
                    channelName = "joint",
                )
            )
        assertEquals("user-3", confirmation.memberLabel)
        assertNull(confirmation.personName)
    }

    @Test
    fun postChannelMemberRemovedToastUsesEightSecondInfoToastForStillInChannelAndPlainToastOtherwise() {
        val window = foregroundWindow()
        val bridge = bridge()
        val stillInChannel =
            ChannelMemberStillInChannel(
                through = listOf(ChannelMemberStillInChannelServer("s1", "Server A")),
                text = "fallback",
            )

        bridge.postChannelMemberRemovedToast(
            stillInChannel = stillInChannel,
            personName = "Alice",
            stillInChannelStrings = EnglishStrings,
            memberRemovedLabel = "Member removed",
        )
        val infoToast = window.state.visible.single()
        assertEquals(
            "Alice is still in the channel through Server A. Only your server’s side of their membership was removed.",
            infoToast.title,
        )
        assertEquals(RaftToastIntent.Info, infoToast.intent)
        assertEquals(RaftToastActionTimeoutMillis, infoToast.durationMillis)

        window.state.dismissAll()
        bridge.postChannelMemberRemovedToast(
            stillInChannel = null,
            personName = "Alice",
            stillInChannelStrings = EnglishStrings,
            memberRemovedLabel = "Member removed",
        )
        val plainToast = window.state.visible.single()
        assertEquals("Member removed", plainToast.title)
        assertNull(plainToast.intent)
        assertEquals(RaftToastTimeoutMillis, plainToast.durationMillis)

        window.state.dismissAll()
        bridge.postChannelMemberRemovedToast(
            stillInChannel = ChannelMemberStillInChannel(through = emptyList(), text = null),
            personName = "Alice",
            stillInChannelStrings = EnglishStrings,
            memberRemovedLabel = "Member removed",
        )
        assertTrue(window.state.visible.isEmpty())
    }

    @Test
    fun threadChannelMemberCommandsKeepsMemberInRosterAndSurfacesStillInChannel() = runTest {
        val serverA = MemberServerRef(id = "server-a", name = "Server A", slug = "server-a")
        val viewerServer = MemberServerRef(id = "server-1", name = "Slock", slug = "slock")
        val aliceBefore =
            MemberProfile(
                id = MemberId("u-alice"),
                displayName = "Alice",
                handle = "alice",
                kind = MemberKind.Human,
                serverId = ServerId("server-1"),
                inViewerServer = true,
                canRemoveFromChannel = true,
                servers = listOf(viewerServer, serverA),
            )
        val aliceAfter =
            MemberProfile(
                id = MemberId("u-alice"),
                displayName = "Alice",
                handle = "alice",
                kind = MemberKind.Human,
                serverId = ServerId("server-a"),
                serverName = "Server A",
                inViewerServer = false,
                canRemoveFromChannel = false,
                servers = listOf(serverA),
            )
        val bob =
            MemberProfile(
                id = MemberId("u-bob"),
                displayName = "Bob",
                handle = "bob",
                kind = MemberKind.Human,
                serverId = ServerId("server-1"),
            )
        val stillInChannel =
            ChannelMemberStillInChannel(
                through = listOf(ChannelMemberStillInChannelServer("server-a", "Server A")),
                text = "Alice is still in the channel through Server A. Only your server’s side of their membership was removed.",
            )
        val memberRepository =
            FakeMemberRepository(
                serverMembers = listOf(aliceBefore, bob),
                channelMembers = listOf(aliceBefore, bob),
            ).apply {
                removeStillInChannelProvider = { stillInChannel }
                onRemoveStillInChannel = { listOf(aliceAfter, bob) }
            }
        val serverMembersStore = testServerMembersStore(memberRepository, scope = backgroundScope)
        serverMembersStore.seed(
            MemberSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                members = listOf(aliceBefore, bob),
            )
        )
        serverMembersStore.loadChannelMembers(ServerId("server-1"), ChannelId("channel-1"))

        val commands =
            ThreadChannelMemberCommands(
                memberRepository = memberRepository,
                serverMembersStore = serverMembersStore,
                channelRepository = null,
                readSession = { sampleSession() },
            )
        val initialPayload =
            RaftThreadScreenPayload(
                sourceThreadId = "channel-1",
                title = "#joint",
                subtitle = "",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "joint",
                membersCount = 2,
                members = listOf(aliceBefore.toRaftThreadMemberItem(ServerId("server-1")), bob.toRaftThreadMemberItem(ServerId("server-1"))),
            )

        val result =
            assertIs<DataResult.Content<ThreadRemoveChannelMemberResult>>(
                commands.removeChannelMember(initialPayload, "u-alice", "human")
            ).data

        assertEquals(stillInChannel, result.stillInChannel)
        assertEquals(2, result.payload.membersCount)
        assertEquals(listOf("u-alice", "u-bob"), result.payload.members.map { it.id })
        assertEquals(listOf("u-alice", "u-bob"), serverMembersStore.currentChannelMembers(ServerId("server-1"), ChannelId("channel-1")).map { it.id.value })

        val repoOnlyCommands =
            ThreadChannelMemberCommands(
                memberRepository = memberRepository,
                serverMembersStore = null,
                channelRepository = null,
                readSession = { sampleSession() },
            )
        val repoOnlyResult =
            assertIs<DataResult.Content<ThreadRemoveChannelMemberResult>>(
                repoOnlyCommands.removeChannelMember(initialPayload, "u-alice", "human")
            ).data
        assertEquals(stillInChannel, repoOnlyResult.stillInChannel)
        assertEquals(listOf("u-alice", "u-bob"), repoOnlyResult.payload.members.map { it.id })
    }

    private companion object {
        val EnglishStrings =
            StillInChannelToastStrings(
                throughTemplate = "\u0001 is still in the channel through \u0002. Only your server’s side of their membership was removed.",
                listTwoTemplate = "\u0001 and \u0002",
                listMoreTemplate = "\u0001, \u0002",
                listEndTemplate = "\u0001, and \u0002",
            )
        val SimplifiedChineseStrings =
            StillInChannelToastStrings(
                throughTemplate = "\u0001 还通过 \u0002 留在频道里，只移除了你这个服务器那一侧的成员身份。",
                listTwoTemplate = "\u0001和\u0002",
                listMoreTemplate = "\u0001、\u0002",
                listEndTemplate = "\u0001和\u0002",
            )
        val JapaneseStrings =
            StillInChannelToastStrings(
                throughTemplate = "\u0001 は \u0002 経由で引き続きチャンネルに参加しています。削除されたのは、あなたのサーバー側からの参加だけです。",
                listTwoTemplate = "\u0001、\u0002",
                listMoreTemplate = "\u0001、\u0002",
                listEndTemplate = "\u0001、\u0002",
            )
    }
}
