package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AppLocale
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.stringDescLocaleType
import build.raft.app.members.EmptyRaftMemberAvatarResolver
import build.raft.app.members.LocalMembersRuntimeAccountUsageAccess
import build.raft.app.members.MembersAgentProfileHeader
import build.raft.app.members.MembersRuntimeAccountUsageAccess
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.resources.StringDesc
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadHeaderDisplayModel
import build.raft.app.thread.ThreadMemberRemovalGateState
import build.raft.app.thread.ThreadMembersPageContent
import build.raft.app.thread.ThreadPageHeader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Runtime sign-in-needed status across sidebar DM row, channel member list, DM header (narrow vs
 * wide), and agent profile badge, plus in-place Sign in and zero-read privacy (botiverse/slock #15282).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class AgentRuntimeSignInSurfacesUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private companion object {
        val OWNER = SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1")
        const val EXPECTED_DETAIL =
            "Milo can't start until Kimi Code is signed in on Studio Mac. Sign in there, or switch Milo to another runtime."
    }

    private fun signInIndicator(
        spawnFailureReason: String? = "runtime_login_required",
        lastRuntimeErrorSpawnFailureReason: String? = null,
        status: AgentStatus = AgentStatus.Offline,
    ): RaftAgentStatusIndicatorState =
        SharedServerMemberStatus(
            status = status,
            presence = if (status == AgentStatus.Offline) Presence.Offline else Presence.Active,
            runtime = "kimi-sdk",
            external = false,
            spawnFailureReason = spawnFailureReason,
            lastRuntimeErrorSpawnFailureReason = lastRuntimeErrorSpawnFailureReason,
            paused = false,
            machineId = "m-1",
            machineName = "Studio Mac",
        ).toRaftAgentStatusIndicatorState()

    private fun testAccess(
        api: FakeRuntimeSignInApi,
        canViewMachine: Boolean = true,
        withAuthHost: Boolean = true,
    ): MembersRuntimeAccountUsageAccess =
        MembersRuntimeAccountUsageAccess(
            store =
                SharedRuntimeAccountUsageStore(
                    client = RaftComputersApiClient(api),
                    currentIdentity = { OWNER },
                    clock = { 0L },
                ),
            owner = OWNER,
            serverId = "server-1",
            auth = if (withAuthHost) ComputerRuntimeAuthHost(realtime = null, openUrl = {}) else null,
            canViewMachine = { _, _ -> canViewMachine },
        )

    @Test
    fun channelMemberRowShowsFullStatusDetailAndOpensSignInDialogInPlace() =
        runRobolectricComposeUiTest {
            val api = FakeRuntimeSignInApi(kimiStatus = "logged_out", canAct = true)
            val state = signInIndicator()
            val member = RaftThreadMemberItem(id = "agent-1", displayName = "Milo", handle = "milo", kind = "agent")
            setContent {
                val access = remember { testAccess(api) }
                val pager =
                    remember {
                        object : Pager() {}.apply {
                            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                        }
                    }
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(pager),
                    LocalMembersRuntimeAccountUsageAccess provides access,
                ) {
                    ThreadMembersPageContent(
                        members = listOf(member),
                        filteredMembers = listOf(member),
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
                        agentStatusResolver = { flowOf(state) },
                        memberAvatarResolver = EmptyRaftMemberAvatarResolver,
                        onBack = {},
                        onRefresh = {},
                        onOpenMember = {},
                        onRemoveMember = {},
                        onSubmit = {},
                    )
                }
            }
            onNodeWithTag(AgentRuntimeSignInTestTags.MemberRowStatus, useUnmergedTree = true)
                .assertExists()
                .assertTextEquals("Sign-in needed for Kimi Code")
            onNodeWithTag(AgentRuntimeSignInTestTags.MemberRowDetail, useUnmergedTree = true)
                .assertExists()
                .assertTextEquals(EXPECTED_DETAIL)
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(AgentRuntimeSignInTestTags.MemberRowAction, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(AgentRuntimeSignInTestTags.MemberRowAction, useUnmergedTree = true)
                .assertContentDescriptionEquals("Sign in to Kimi Code")
                .performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(COMPUTER_RUNTIME_SIGN_IN_DIALOG_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Sign in to Kimi Code", useUnmergedTree = true).assertExists()
        }

    @Test
    fun liveLoggedInClearsSignInNeededOnDmHeaderAndProfileBadge() =
        runRobolectricComposeUiTest {
            val api = FakeRuntimeSignInApi(kimiStatus = "logged_in", canAct = true)
            val state = signInIndicator(spawnFailureReason = null, lastRuntimeErrorSpawnFailureReason = "runtime_login_required")
            val member = MemberProfile(id = MemberId("agent-1"), displayName = "Milo", handle = "milo", kind = MemberKind.Agent)
            val identity = MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent)
            val summary =
                AgentDetailSummary(
                    id = "agent-1",
                    name = "milo",
                    displayName = "Milo",
                    runtime = "kimi-sdk",
                    activity = "offline",
                    machineId = "m-1",
                    machineName = "Studio Mac",
                    lastRuntimeErrorSpawnFailureReason = "runtime_login_required",
                )
            setContent {
                val access = remember { testAccess(api) }
                CompositionLocalProvider(LocalMembersRuntimeAccountUsageAccess provides access) {
                    Column(Modifier.fillMaxSize()) {
                        ThreadPageHeader(
                            payload = RaftThreadScreenPayload(instanceId = "dm-milo", surfaceKind = RaftThreadSurfaceKind.DirectMessage, status = "content"),
                            headerDisplay =
                                ThreadHeaderDisplayModel(
                                    title = "Milo",
                                    subtitle = null,
                                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                                    channelKind = "dm",
                                    dmPeerAvatarLabel = "MI",
                                    dmPeerIdentity = identity,
                                ),
                            agentStatusResolver = { flowOf(state) },
                            applyTopSafeArea = false,
                            onBack = {},
                            onOpenInChannel = {},
                            onOpenMemberProfile = {},
                            onOpenSearch = {},
                            onShowMembers = {},
                            onShowSettings = {},
                        )
                        RaftDesignSystemTheme {
                            MembersAgentProfileHeader(
                                member = member,
                                summary = summary,
                                canEdit = false,
                                agentStatusObservedState = state,
                                agentStatusIndicatorState = state,
                                onChangeAvatar = {},
                                showActivityDetail = false,
                            )
                        }
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                api.authReads > 0 &&
                    onAllNodesWithTag(AgentRuntimeSignInTestTags.DmHeaderStatus, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() &&
                    onAllNodesWithText("Sign-in needed for Kimi Code", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            onAllNodesWithTag(AgentRuntimeSignInTestTags.DmHeaderAction, useUnmergedTree = true).assertCountEquals(0)
            onAllNodesWithTag(AgentRuntimeSignInTestTags.ProfileAction, useUnmergedTree = true).assertCountEquals(0)
            onAllNodesWithText("Offline", useUnmergedTree = true).onFirst().assertExists()
        }

    @Test
    fun unknownViewerWithoutCanViewMachineFailsClosedWithZeroReads() =
        runRobolectricComposeUiTest {
            val api = FakeRuntimeSignInApi(kimiStatus = "logged_out", canAct = true)
            val identity = MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent)
            val state = signInIndicator()
            setContent {
                val store = remember { SharedRuntimeAccountUsageStore(RaftComputersApiClient(api), currentIdentity = { OWNER }) }
                val access =
                    remember(store) {
                        MembersRuntimeAccountUsageAccess(
                            store = store,
                            owner = OWNER,
                            serverId = "srv-1",
                            viewer = null,
                            auth = ComputerRuntimeAuthHost(realtime = null, openUrl = {}),
                            canViewMachine = null,
                        )
                    }
                CompositionLocalProvider(LocalMembersRuntimeAccountUsageAccess provides access) {
                    ThreadPageHeader(
                        payload = RaftThreadScreenPayload(instanceId = "dm-milo", surfaceKind = RaftThreadSurfaceKind.DirectMessage, status = "content"),
                        headerDisplay =
                            ThreadHeaderDisplayModel(
                                title = "Milo",
                                subtitle = null,
                                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                                channelKind = "dm",
                                dmPeerAvatarLabel = "MI",
                                dmPeerIdentity = identity,
                            ),
                        agentStatusResolver = { flowOf(state) },
                        applyTopSafeArea = false,
                        onBack = {},
                        onOpenInChannel = {},
                        onOpenMemberProfile = {},
                        onOpenSearch = {},
                        onShowMembers = {},
                        onShowSettings = {},
                    )
                }
            }
            waitForIdle()
            onNodeWithTag(AgentRuntimeSignInTestTags.DmHeaderStatus, useUnmergedTree = true)
                .assertExists()
                .assertTextEquals("Sign-in needed")
            onAllNodesWithTag(AgentRuntimeSignInTestTags.DmHeaderAction, useUnmergedTree = true).assertCountEquals(0)
            assertEquals(0, api.authReads)
        }

    private class FakeRuntimeSignInApi(
        private val kimiStatus: String,
        private val canAct: Boolean,
    ) : RaftComputersApi {
        var authReads = 0

        override suspend fun runtimeAuth(serverId: ServerId, machineId: String): HomeNetworkResponse {
            authReads += 1
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"status":{"collectedAt":"2026-11-21T00:00:00.000Z","runtimes":{"kimi":{"kind":"$kimiStatus"}}},"availability":"ok","runtimes":["kimi"],"canAct":$canAct}""",
            )
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
            error("unused")
    }
}
