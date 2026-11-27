package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAnswer
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.InviteConnectableServer
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.MemberAskServer
import build.raft.app.data.MemberConnectNewServerResponse
import build.raft.app.data.MemberConnectOwnerRef
import build.raft.app.data.MemberConnectResponse
import build.raft.app.data.MemberConnectServerLimit
import build.raft.app.data.OwnConnectableServers
import build.raft.app.data.RaftLongMessageCollapseConversationPreference
import build.raft.app.data.ServerConnectOutcome
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.ThreadId
import build.raft.app.thread.RaftLongMessageCollapseConversationSetting
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.ThreadMemberRemovalGateState
import build.raft.app.thread.ThreadMembersPageContent
import build.raft.app.thread.dispatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Connected servers ⋯ > Connect your servers, private channel Channel settings › Connect your
 * servers row, and Guest Connect your servers + Create a server and connect on a real composition.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class JointMemberConnectSheetUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.show(
        connections: JointChannelConnections = memberTestConnections(),
        account: MemberConnectAccount = memberTestAccount(),
        answer: MemberConnectResponse? = null,
        limits: Map<String, MemberConnectServerLimit> = emptyMap(),
        askServers: List<MemberAskServer> = emptyList(),
        newServerAnswer: MemberConnectNewServerResponse? = null,
        offersUpgrade: Boolean = true,
        onUpgradeServer: (String) -> Unit = {},
    ): Pair<JointChannelConnectionsStore, FakeJointConnectionsDataSource> {
        val dataSource =
            FakeJointConnectionsDataSource(connections = connections).apply {
                memberConnectLimits = limits
                memberConnectAskServers = askServers
                newServerAnswer?.let { connectNewServerResult = DataResult.Content(it) }
            }
        answer?.let { dataSource.memberConnectResult = DataResult.Content(it) }
        val store =
            JointChannelConnectionsStore(
                dataSource,
                CoroutineScope(Dispatchers.Main.immediate),
                connectedByYouSlot = MemberConnectedByYouSlot(MemberConnectedByYouMemory(), "ui"),
            )
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(account))
        store.dispatch(JointChannelConnectionsIntent.Load)
        setContent {
            val state by store.state.collectAsState()
            RaftDesignSystemTheme {
                JointConnectedServersSection(
                    state = state,
                    onConnectYourServers = { store.dispatch(JointChannelConnectionsIntent.OpenMemberConnect) },
                )
                JointMemberConnectSheet(
                    state = state,
                    onDismiss = { store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
                    onSelectionChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(it)) },
                    onSubmit = { store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect) },
                    onUpgradeServer = onUpgradeServer,
                    onOpenCreateServer = { store.dispatch(JointChannelConnectionsIntent.OpenMemberConnectNewServer) },
                    onCloseCreateServer = { store.dispatch(JointChannelConnectionsIntent.CloseMemberConnectNewServer) },
                    onNewServerNameChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectNewServerName(it)) },
                    onNewServerSlugChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectNewServerSlug(it)) },
                    onSubmitNewServer = { store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnectNewServer) },
                    offersUpgrade = offersUpgrade,
                )
            }
        }
        waitForIdle()
        return store to dataSource
    }

    private fun ComposeUiTest.count(tag: String): Int = onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.shown(text: String): Boolean =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.openSheet() {
        onNodeWithTag(JointMemberConnectMenuTag, useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag(JointMemberConnectMenuItemTag, useUnmergedTree = true).performClick()
        waitForIdle()
    }

    @Test
    fun privateChannelDetailsShowsConnectYourServersRowInsteadOfHostOnlySection() = runRobolectricComposeUiTest {
        val dataSource = FakeJointConnectionsDataSource(connections = privateTestConnections())
        val store =
            JointChannelConnectionsStore(
                dataSource,
                CoroutineScope(Dispatchers.Main.immediate),
                connectedByYouSlot = MemberConnectedByYouSlot(MemberConnectedByYouMemory(), "priv-ui"),
            )
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(memberTestAccount()))
        store.dispatch(JointChannelConnectionsIntent.Load)
        val privateRow =
            HomeChannelRow(
                threadId = ThreadId("ch-private"),
                channelId = ChannelId("ch-private"),
                channelType = "private",
                name = "secret-room",
            )
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            val state by store.state.collectAsState()
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    ChannelSettingsContent(
                        model =
                            ChannelSettingsUiModel(
                                channel = privateRow,
                                memberSlice = SharedServerChannelMembersSlice(channelId = "ch-private", status = SharedServerMembersLoadStatus.Loaded),
                                pinned = false,
                                muted = false,
                                permissions = channelSettingsPermissions(privateRow, canManage = false),
                                writeActionsEnabled = true,
                                canStopAllAgents = false,
                                privateMemberConnect = true,
                            ),
                        longMessageCollapseSetting =
                            RaftLongMessageCollapseConversationSetting(
                                preference = RaftLongMessageCollapseConversationPreference(globalEnabled = false, channelOverride = null),
                                available = false,
                                onSetEnabled = {},
                            ),
                        servers = { JointConnectedServersSection(state = state) },
                        callbacks =
                            ChannelSettingsCallbacks(
                                onConnectServers = { store.dispatch(JointChannelConnectionsIntent.OpenMemberConnect) },
                            ),
                    )
                    JointMemberConnectSheet(
                        state = state,
                        onDismiss = { store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
                        onSelectionChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(it)) },
                        onSubmit = { store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect) },
                    )
                }
            }
        }
        waitForIdle()
        // No host-only Connected servers section on a private channel (#16395).
        assertEquals(0, count(JointConnectionsSectionTag))
        assertEquals(0, count(JointConnectionsHostBadgeTag))
        // Channel settings › Connect your servers · Not connected · Connect (#16395/#16454).
        assertEquals(1, count(ChannelSettingsOtherServersTag))
        assertTrue(shown("Connect your servers"))
        assertTrue(shown("Not connected"))
        onNodeWithTag(ChannelSettingsOtherServersConnectTag, useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Alpha Co", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(shown("#secret-room becomes a channel shared with other servers, and the servers you connect see its earlier messages."))
    }

    @Test
    fun guestEntryShowsHelperLineAskServersApproversAndCreateServerKeptBanner() = runRobolectricComposeUiTest {
        val guestAccount =
            MemberConnectAccount(
                currentServerId = "srv-cur",
                currentServerName = "Current Co",
                currentServerSlug = "current-co",
                currentServerRole = "guest",
                servers =
                    listOf(
                        MemberConnectServer("srv-cur", "Current Co", "current-co", null, "guest"),
                        MemberConnectServer("srv-member", "Member Co", "member-co", null, "member"),
                    ),
            )
        val askServers =
            listOf(
                MemberAskServer(
                    id = "srv-member",
                    name = "Member Co",
                    slug = "member-co",
                    approvers = listOf(MemberConnectOwnerRef("u1", "Tom"), MemberConnectOwnerRef("u2", "Lena")),
                )
            )
        val keptAnswer =
            MemberConnectNewServerResponse.Answered(
                serverId = "srv-kept",
                servers = listOf(ServerConnectOutcome.Refused("srv-kept", 409, "free_external_limit", "Host limit", limitSide = "host")),
                traceId = "0123456789abcdef0123456789abcdef",
            )
        val (store, _) = show(connections = privateTestConnections(), account = guestAccount, askServers = askServers, newServerAnswer = keptAnswer)
        store.dispatch(JointChannelConnectionsIntent.OpenGuestMemberConnect)
        waitForIdle()

        assertEquals(1, count(JointMemberConnectGuestEntryTag))
        assertTrue(shown("Ask Tom or Lena to connect it"))
        assertEquals(1, count(JointMemberConnectCreateServerTag))

        onNodeWithTag(JointMemberConnectCreateServerTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, count(JointMemberConnectNewServerFormTag))
        onNodeWithTag(JointMemberConnectNewServerNameTag, useUnmergedTree = true).performTextInput("Kirby Lab")
        waitForIdle()
        onNodeWithText("Create", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(shown("Your new server Kirby Lab was created."))
    }

    @Test
    fun membersPageRendersGuestConnectEntryInPlaceOfAddMember() = runRobolectricComposeUiTest {
        var memberGuestClicks = 0
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    ThreadMembersPageContent(
                        members = listOf(RaftThreadMemberItem("u1", "Kirby", handle = "kirby")),
                        filteredMembers = listOf(RaftThreadMemberItem("u1", "Kirby", handle = "kirby")),
                        filteredExternal = emptyList(),
                        externalMemberCount = 0,
                        candidates = emptyList(),
                        rosterQuery = TextFieldValue(""),
                        onQueryChange = {},
                        agentsExpanded = true,
                        humansExpanded = true,
                        externalExpanded = true,
                        onAgentsExpandedChange = {},
                        onHumansExpandedChange = {},
                        onExternalExpandedChange = {},
                        addDialogVisible = false,
                        onAddVisibleChange = {},
                        addMemberStatus = "idle",
                        addMemberErrorCode = null,
                        removalGate = ThreadMemberRemovalGateState(),
                        membersFailed = false,
                        membersLoading = false,
                        refreshing = false,
                        canAddMembers = false,
                        guestConnectEntry = true,
                        onGuestConnect = { memberGuestClicks += 1 },
                        agentStatusResolver = { kotlinx.coroutines.flow.flowOf(null) },
                        memberAvatarResolver = EmptyRaftMemberAvatarResolver,
                        onBack = {},
                        onRefresh = {},
                        onOpenMember = {},
                        onRemoveMember = {},
                        onSubmit = {},
                    )
                }
            }
        }
        waitForIdle()
        assertEquals(1, count(ChannelMembersGuestConnectButtonTag))
        assertTrue(shown("Connect your servers"))
        assertFalse(shown("Add Member"))
        onNodeWithTag(ChannelMembersGuestConnectButtonTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, memberGuestClicks)
    }

    @Test
    fun channelDetailsRendersGuestConnectTileInMembersStrip() = runRobolectricComposeUiTest {
        var detailsGuestClicks = 0
        val privateRow =
            HomeChannelRow(
                threadId = ThreadId("ch-private"),
                channelId = ChannelId("ch-private"),
                channelType = "private",
                name = "secret-room",
            )
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    ChannelSettingsContent(
                        model =
                            ChannelSettingsUiModel(
                                channel = privateRow,
                                memberSlice = SharedServerChannelMembersSlice(channelId = "ch-private", status = SharedServerMembersLoadStatus.Loaded, canAddMembers = false),
                                pinned = false,
                                muted = false,
                                permissions = channelSettingsPermissions(privateRow, canManage = false),
                                writeActionsEnabled = true,
                                canStopAllAgents = false,
                                guestConnectEntry = true,
                            ),
                        longMessageCollapseSetting =
                            RaftLongMessageCollapseConversationSetting(
                                preference = RaftLongMessageCollapseConversationPreference(globalEnabled = false, channelOverride = null),
                                available = false,
                                onSetEnabled = {},
                            ),
                        callbacks = ChannelSettingsCallbacks(onGuestConnectServers = { detailsGuestClicks += 1 }),
                    )
                }
            }
        }
        waitForIdle()
        assertEquals(1, count(ChannelSettingsMembersGuestConnectTag))
        onNodeWithTag(ChannelSettingsMembersGuestConnectTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, detailsGuestClicks)
    }

    @Test
    fun atLimitRowsShowUpgradeForOwnerAskOwnerForAdminAndHideActionsWhenUpgradeNotOffered() = runRobolectricComposeUiTest {
        val upgraded = mutableListOf<String>()
        val limits =
            mapOf(
                "srv-zed" to MemberConnectServerLimit(serverId = "srv-zed", atLimit = true, canUpgrade = true),
                "srv-alpha" to MemberConnectServerLimit(serverId = "srv-alpha", atLimit = true, canUpgrade = false),
            )
        show(limits = limits, offersUpgrade = true, onUpgradeServer = { upgraded += it })
        openSheet()
        assertTrue(shown("Free · in another external channel"))
        assertEquals(1, count(JointMemberConnectUpgradeTag))
        assertEquals(1, count(JointMemberConnectAskOwnerTag))

        onNodeWithTag(JointMemberConnectUpgradeTag, useUnmergedTree = true).performClick()
        onNodeWithTag(JointMemberConnectAskOwnerTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("srv-zed", "srv-alpha"), upgraded)
    }

    @Test
    fun atLimitRowsOmitInlineUpgradeButtonsWhenPlatformDisallowsUpgradeOffers() = runRobolectricComposeUiTest {
        val limits =
            mapOf(
                "srv-zed" to MemberConnectServerLimit(serverId = "srv-zed", atLimit = true, canUpgrade = true),
                "srv-alpha" to MemberConnectServerLimit(serverId = "srv-alpha", atLimit = true, canUpgrade = false),
            )
        show(limits = limits, offersUpgrade = false)
        openSheet()
        assertTrue(shown("Free · in another external channel"))
        assertEquals(0, count(JointMemberConnectUpgradeTag))
        assertEquals(0, count(JointMemberConnectAskOwnerTag))
    }

    @Test
    fun publicChannelConnectFromInviteShowsBecomesPrivateForAdminAndConnectsWithMakePrivate() = runRobolectricComposeUiTest {
        val publicConn = privateTestConnections().copy(isPrivate = false, channelName = "launch-week")
        val dataSource =
            FakeJointConnectionsDataSource(connections = publicConn).apply {
                ownConnectableServersResult =
                    DataResult.Content(
                        ExternalInviteAnswer.Ok(
                            OwnConnectableServers(
                                listOf(InviteConnectableServer(id = "srv-alpha", name = "Alpha Co", slug = "alpha-co", canConnectDirectly = true))
                            )
                        )
                    )
                connectOwnServersResult =
                    DataResult.Content(ExternalInviteAnswer.Ok(listOf(ServerConnectOutcome.Connected("srv-alpha", "c1"))))
            }
        val store = JointChannelConnectionsStore(dataSource, CoroutineScope(Dispatchers.Main.immediate))
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(memberTestAccount()))
        store.dispatch(JointChannelConnectionsIntent.Load)
        var backClicks = 0
        setContent {
            val state by store.state.collectAsState()
            RaftDesignSystemTheme {
                JointMemberConnectSheet(
                    state = state,
                    onBack = { backClicks += 1 },
                    onDismiss = { store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
                    onSelectionChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(it)) },
                    onSubmit = { store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect) },
                )
            }
        }
        store.dispatch(JointChannelConnectionsIntent.OpenFromExternalInvite(publicChannel = PublicChannelConnect(canMakePrivate = true)))
        waitForIdle()

        assertEquals(1, count(JointMemberConnectBecomesPrivateTag))
        assertEquals(0, count(JointMemberConnectPublicRefusedTag))
        assertTrue(shown("#launch-week will become private once connected."))
        assertEquals(1, onAllNodes(hasContentDescription("Back"), useUnmergedTree = true).fetchSemanticsNodes().size)

        onNodeWithText("Alpha Co", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Connect", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf(listOf("srv-alpha") to true), dataSource.connectOwnServersCalls)
        assertEquals(0, backClicks)
    }

    @Test
    fun publicChannelConnectFromInviteForNonAdminShowsLockedNoticeAndDisablesConnect() = runRobolectricComposeUiTest {
        val publicConn = privateTestConnections().copy(isPrivate = false, channelName = "launch-week")
        val dataSource = FakeJointConnectionsDataSource(connections = publicConn)
        val store = JointChannelConnectionsStore(dataSource, CoroutineScope(Dispatchers.Main.immediate))
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(memberTestAccount()))
        store.dispatch(JointChannelConnectionsIntent.Load)
        setContent {
            val state by store.state.collectAsState()
            RaftDesignSystemTheme {
                JointMemberConnectSheet(
                    state = state,
                    onBack = { store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
                    onDismiss = { store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
                    onSelectionChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(it)) },
                    onSubmit = { store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect) },
                )
            }
        }
        store.dispatch(JointChannelConnectionsIntent.OpenFromExternalInvite(publicChannel = PublicChannelConnect(canMakePrivate = false)))
        waitForIdle()

        assertEquals(1, count(JointMemberConnectPublicRefusedTag))
        assertEquals(0, count(JointMemberConnectBecomesPrivateTag))
        assertTrue(shown("#launch-week is public. Ask an owner or admin to make it private first."))
        assertEquals(0, dataSource.memberConnectLimitsCalls)
        assertEquals(0, dataSource.ownConnectableServersCalls)
        onNodeWithText("Connect", useUnmergedTree = false).assertIsNotEnabled()
        assertEquals(emptyList(), dataSource.memberConnectCalls)
        assertEquals(emptyList(), dataSource.connectOwnServersCalls)
    }

    @Test
    fun privateChannelConnectFromInviteUsesHostAdminDescription() = runRobolectricComposeUiTest {
        val dataSource = FakeJointConnectionsDataSource(connections = privateTestConnections())
        val store = JointChannelConnectionsStore(dataSource, CoroutineScope(Dispatchers.Main.immediate))
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(memberTestAccount()))
        store.dispatch(JointChannelConnectionsIntent.Load)
        setContent {
            val state by store.state.collectAsState()
            RaftDesignSystemTheme {
                JointMemberConnectSheet(
                    state = state,
                    onBack = { store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
                    onDismiss = { store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
                    onSelectionChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(it)) },
                    onSubmit = { store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect) },
                )
            }
        }
        store.dispatch(JointChannelConnectionsIntent.OpenFromExternalInvite(publicChannel = null))
        waitForIdle()

        assertTrue(shown("Servers you own or administer connect now, with no invite. A message in the channel says the server was connected."))
        assertEquals(0, count(JointMemberConnectBecomesPrivateTag))
        assertEquals(0, count(JointMemberConnectPublicRefusedTag))
    }
}
