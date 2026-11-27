package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AppLocale
import build.raft.app.data.ComputerReadinessAction
import build.raft.app.data.ComputerReadinessActor
import build.raft.app.data.ComputerReadinessAttention
import build.raft.app.data.ComputerReadinessAttentionItem
import build.raft.app.data.ComputerReadinessResource
import build.raft.app.data.ComputersRackView
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.AgentReadiness
import build.raft.app.model.AgentReadinessFact
import build.raft.app.model.AgentReadinessResource
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.StringDesc
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ThreadHeaderDisplayModel
import build.raft.app.thread.ThreadPageHeader
import build.raft.app.thread.key
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
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
 * Robolectric Compose UI tests matching `botiverse/slock`
 * `packages/web/tests/modelUnavailableReadiness.behavior.test.tsx` (#17124, #17127, #17133):
 * blocked vs advisory `model_unavailable` readiness, stored non-retryable start failures before
 * live activity loads, hiding Retry on `session_needs_reset`, DM header parity, and Computers
 * attention summary `change_model` action.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1200dp")
class AgentAttentionReadinessUiTest {
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

    private val now = 1_794_312_000_000L

    private fun modelUnavailableReadiness(state: String): AgentReadiness =
        AgentReadiness(
            state = state,
            primary =
                AgentReadinessFact(
                    resource = AgentReadinessResource(kind = "model", key = "shared:antigravity", model = "gemini-4-neon"),
                    code = "model_unavailable",
                    behaviour = state,
                    since = 1_000L,
                    model = "gemini-4-neon",
                ),
            scope = "agent",
            actor = "agent_owner",
            actorName = "Agnes",
            action = "change_model",
        )

    private fun agentMember(readiness: AgentReadiness? = null): MemberProfile =
        MemberProfile(
            id = MemberId("agent-muse"),
            displayName = "Muse",
            handle = "muse",
            kind = MemberKind.Agent,
            roleDescription = "antigravity",
            model = "gemini-4-neon",
            agentReadiness = readiness,
        )

    @Test
    fun profileBlockedModelUnavailableShowsChipDetailAndChangeModelForManagersOnly() =
        runRobolectricComposeUiTest {
            val readiness = modelUnavailableReadiness("blocked")
            val state =
                SharedServerMemberStatus(
                    status = AgentStatus.Offline,
                    presence = Presence.Offline,
                    runtime = "antigravity",
                    model = "gemini-4-neon",
                    external = false,
                    readiness = readiness,
                ).toRaftAgentStatusIndicatorState()
            val runtimeName = raftAgentRuntimeDisplayName("antigravity")
            var changeModelClicks = 0

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        MembersAgentProfileHeader(
                            member = agentMember(readiness),
                            summary = AgentDetailSummary(id = "agent-muse", name = "muse", displayName = "Muse", runtime = "antigravity"),
                            canEdit = true,
                            agentStatusObservedState = state,
                            agentStatusIndicatorState = state,
                            onChangeAvatar = {},
                            showActivityDetail = true,
                            onChangeModel = { changeModelClicks += 1 },
                        )
                    }
                }
            }

            onNode(
                hasAnyAncestor(hasTestTag(AgentReadinessTestTags.ProfileChip)) and hasText("Model unavailable"),
                useUnmergedTree = true,
            ).assertExists()
            onNodeWithTag(AgentReadinessTestTags.ProfileDetail, useUnmergedTree = true)
                .assertTextContains("$runtimeName on this Computer no longer offers gemini-4-neon. Pick another model.", substring = true)
            onNodeWithTag(AgentSpawnFailureStatusTestTags.ChangeModel)
                .assertIsDisplayed()
                .performClick()
            assertEquals(1, changeModelClicks)
        }

    @Test
    fun profileTrimmedReadinessForNonManagerHidesChangeModelAndShowsWhoCanAct() =
        runRobolectricComposeUiTest {
            val trimmed =
                AgentReadiness(
                    state = "blocked",
                    primary = null,
                    scope = null,
                    actor = "agent_owner",
                    actorName = "Agnes",
                    action = "change_model",
                )
            val state =
                SharedServerMemberStatus(
                    status = AgentStatus.Offline,
                    presence = Presence.Offline,
                    runtime = "antigravity",
                    external = false,
                    readiness = trimmed,
                ).toRaftAgentStatusIndicatorState()

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        MembersAgentProfileHeader(
                            member = agentMember(trimmed),
                            summary = AgentDetailSummary(id = "agent-muse", name = "muse", displayName = "Muse", runtime = "antigravity"),
                            canEdit = false,
                            agentStatusObservedState = state,
                            agentStatusIndicatorState = state,
                            onChangeAvatar = {},
                            showActivityDetail = true,
                            onChangeModel = null,
                        )
                    }
                }
            }

            onNode(
                hasAnyAncestor(hasTestTag(AgentReadinessTestTags.ProfileChip)) and hasText("Can't reply right now"),
                useUnmergedTree = true,
            ).assertExists()
            onNodeWithTag(AgentReadinessTestTags.ProfileDetail, useUnmergedTree = true)
                .assertTextContains("Agnes can change the model.", substring = true)
            onAllNodesWithTag(AgentSpawnFailureStatusTestTags.ChangeModel, useUnmergedTree = true).assertCountEquals(0)
        }

    @Test
    fun profileAdvisoryModelUnavailableKeepsOnlineChipAndShowsAdvisoryNoteWithChangeModel() =
        runRobolectricComposeUiTest {
            val advisory = modelUnavailableReadiness("advisory")
            val state =
                SharedServerMemberStatus(
                    status = AgentStatus.Online,
                    presence = Presence.Active,
                    runtime = "antigravity",
                    model = "gemini-4-neon",
                    external = false,
                    readiness = advisory,
                ).toRaftAgentStatusIndicatorState()
            val runtimeName = raftAgentRuntimeDisplayName("antigravity")
            var changeModelClicks = 0

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        MembersAgentProfileHeader(
                            member = agentMember(advisory),
                            summary = AgentDetailSummary(id = "agent-muse", name = "muse", displayName = "Muse", runtime = "antigravity", activity = "online"),
                            canEdit = true,
                            agentStatusObservedState = state,
                            agentStatusIndicatorState = state,
                            onChangeAvatar = {},
                            showActivityDetail = true,
                            onChangeModel = { changeModelClicks += 1 },
                        )
                    }
                }
            }

            onNode(
                hasAnyAncestor(hasTestTag(AgentReadinessTestTags.ProfileChip)) and hasText("Online"),
                useUnmergedTree = true,
            ).assertExists()
            onNode(
                hasAnyAncestor(hasTestTag(AgentReadinessTestTags.ProfileAdvisory)) and
                    hasText("$runtimeName on this Computer no longer offers gemini-4-neon. Pick another model.", substring = true),
                useUnmergedTree = true,
            ).assertExists()
            onNodeWithTag(AgentSpawnFailureStatusTestTags.ChangeModel)
                .assertIsDisplayed()
                .performClick()
            assertEquals(1, changeModelClicks)
        }

    @Test
    fun profileStoredModelUnsupportedShowsShortTitleSentenceChangeModelAndRetryBeforeActivityLoads() =
        runRobolectricComposeUiTest {
            val state =
                SharedServerMemberStatus(
                    status = AgentStatus.Offline,
                    presence = Presence.Offline,
                    runtime = "antigravity",
                    model = "gemini-4-neon",
                    external = false,
                    lastRuntimeErrorSpawnFailureReason = "model_unsupported",
                    lastRuntimeErrorMessage = "model gone",
                    lifecycleStatus = "inactive",
                ).toRaftAgentStatusIndicatorState()

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        MembersAgentProfileHeader(
                            member = agentMember(),
                            summary = AgentDetailSummary(id = "agent-muse", name = "muse", displayName = "Muse", runtime = "antigravity", model = "gemini-4-neon"),
                            canEdit = true,
                            agentStatusObservedState = state,
                            agentStatusIndicatorState = state,
                            onChangeAvatar = {},
                            showActivityDetail = true,
                            onChangeModel = {},
                            onRetry = {},
                        )
                    }
                }
            }

            onNode(
                hasAnyAncestor(hasTestTag(AgentReadinessTestTags.ProfileChip)) and hasText("Model unavailable"),
                useUnmergedTree = true,
            ).assertExists()
            onNodeWithTag(AgentSpawnFailureStatusTestTags.Text, useUnmergedTree = true)
                .assertTextContains("gemini-4-neon", substring = true)
            onNodeWithTag(AgentSpawnFailureStatusTestTags.ChangeModel).assertIsDisplayed()
            onNodeWithTag(AgentStatusRetryTestTags.Retry).assertIsDisplayed()
        }

    @Test
    fun profileStoredSessionNeedsResetShowsNeedsRestartChangeModelAndNoRetryBeforeActivityLoads() =
        runRobolectricComposeUiTest {
            val state =
                SharedServerMemberStatus(
                    status = AgentStatus.Offline,
                    presence = Presence.Offline,
                    runtime = "antigravity",
                    model = "gemini-4-neon",
                    external = false,
                    lastRuntimeErrorSpawnFailureReason = "session_needs_reset",
                    lastRuntimeErrorMessage = "context too large",
                    lifecycleStatus = "inactive",
                ).toRaftAgentStatusIndicatorState()

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        MembersAgentProfileHeader(
                            member = agentMember(),
                            summary = AgentDetailSummary(id = "agent-muse", name = "muse", displayName = "Muse", runtime = "antigravity", model = "gemini-4-neon"),
                            canEdit = true,
                            agentStatusObservedState = state,
                            agentStatusIndicatorState = state,
                            onChangeAvatar = {},
                            showActivityDetail = true,
                            onChangeModel = {},
                            onRetry = {},
                        )
                    }
                }
            }

            onNode(
                hasAnyAncestor(hasTestTag(AgentReadinessTestTags.ProfileChip)) and hasText("Needs a restart"),
                useUnmergedTree = true,
            ).assertExists()
            onNodeWithTag(AgentSpawnFailureStatusTestTags.ChangeModel).assertIsDisplayed()
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry, useUnmergedTree = true).assertCountEquals(0)
        }

    @Test
    fun profileStoredEnvVarNotAllowedShowsGenericShortTitleAndRawStartFailureDetail() =
        runRobolectricComposeUiTest {
            val state =
                SharedServerMemberStatus(
                    status = AgentStatus.Offline,
                    presence = Presence.Offline,
                    runtime = "antigravity",
                    external = false,
                    lastRuntimeErrorSpawnFailureReason = "env_var_not_allowed",
                    lastRuntimeErrorMessage = "Env var SECRET_KEY is not allowed",
                    lifecycleStatus = "inactive",
                ).toRaftAgentStatusIndicatorState()

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        MembersAgentProfileHeader(
                            member = agentMember(),
                            summary = AgentDetailSummary(id = "agent-muse", name = "muse", displayName = "Muse", runtime = "antigravity"),
                            canEdit = true,
                            agentStatusObservedState = state,
                            agentStatusIndicatorState = state,
                            onChangeAvatar = {},
                            showActivityDetail = true,
                        )
                    }
                }
            }

            onNode(
                hasAnyAncestor(hasTestTag(AgentReadinessTestTags.ProfileChip)) and hasText("Can't reply right now"),
                useUnmergedTree = true,
            ).assertExists()
            onNodeWithTag(AgentAttentionTestTags.ProfileStartFailureDetail, useUnmergedTree = true)
                .assertTextEquals("Env var SECRET_KEY is not allowed")
        }

    @Test
    fun dmHeaderAndComputersAttentionSummaryRenderModelUnavailableAndChangeModel() =
        runRobolectricComposeUiTest {
            val dmState =
                SharedServerMemberStatus(
                    status = AgentStatus.Offline,
                    presence = Presence.Offline,
                    runtime = "antigravity",
                    model = "gemini-4-neon",
                    external = false,
                    lastRuntimeErrorSpawnFailureReason = "model_unsupported",
                    lastRuntimeErrorMessage = "model gone",
                    lifecycleStatus = "inactive",
                ).toRaftAgentStatusIndicatorState()
            val runtimeName = raftAgentRuntimeDisplayName("antigravity")
            val computer =
                SettingsComputerSnapshot(
                    id = "mac-mini",
                    name = "mac-mini",
                    daemonVersion = "",
                    computerVersion = "1.0.53",
                    isComputer = true,
                    online = true,
                    latestComputerVersion = "1.0.53",
                    runtimes = listOf("antigravity"),
                    serverNeedsAttention = true,
                    attention =
                        ComputerReadinessAttention(
                            count = 1,
                            items =
                                listOf(
                                    ComputerReadinessAttentionItem(
                                        code = "model_unavailable",
                                        resource = ComputerReadinessResource.Model("shared:antigravity", "gemini-4-neon"),
                                        actor = ComputerReadinessActor.AgentOwner,
                                        actorName = "Agnes",
                                        action = ComputerReadinessAction.ChangeModel,
                                        agentIds = listOf("agent-muse"),
                                        agentCount = 1,
                                    ),
                                ),
                            waitingAgents = 0,
                        ),
                )
            val listModel =
                raftComputersListModel(
                    ScreenLoadState.Content(SettingsComputersSnapshot(computers = listOf(computer))),
                    nowMillis = now,
                )
            val time = RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = now)

            setContent {
                Column(Modifier.fillMaxSize()) {
                    ThreadPageHeader(
                        payload = RaftThreadScreenPayload(instanceId = "dm-muse", surfaceKind = RaftThreadSurfaceKind.DirectMessage, status = "content"),
                        headerDisplay =
                            ThreadHeaderDisplayModel(
                                title = "Muse",
                                subtitle = null,
                                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                                channelKind = "dm",
                                dmPeerAvatarLabel = "MU",
                                dmPeerIdentity = MemberDetailIdentity(MemberId("agent-muse"), MemberKind.Agent),
                            ),
                        agentStatusResolver = { flowOf(dmState) },
                        applyTopSafeArea = false,
                        onBack = {},
                        onOpenInChannel = {},
                        onOpenMemberProfile = {},
                        onOpenSearch = {},
                        onShowMembers = {},
                        onShowSettings = {},
                    )
                    RaftTheme(appearance = RaftAppearance.Light) {
                        CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(time)) {
                            RaftComputersTheme {
                                RaftSettingsComputersContent(
                                    model = listModel,
                                    onRetry = {},
                                    onAddComputer = {},
                                    onOpenComputer = {},
                                    rackView = ComputersRackView.Cards,
                                    toolbar = RaftComputersRackToolbarState(serverId = "server-1"),
                                    onToolbarIntent = {},
                                    serverId = "server-1",
                                )
                            }
                        }
                    }
                }
            }

            onNodeWithTag(AgentAttentionTestTags.DmHeaderStatus, useUnmergedTree = true)
                .assertExists()
                .assertTextEquals("Model unavailable")
            onNodeWithTag(COMPUTERS_ATTENTION_FILTER_TAG)
                .assertIsDisplayed()
                .assertTextEquals("$runtimeName: Model unavailable on mac-mini")
            onNodeWithTag(COMPUTERS_ATTENTION_ACTION_TAG)
                .assertIsDisplayed()
                .assertTextEquals("Change model")
        }
}
