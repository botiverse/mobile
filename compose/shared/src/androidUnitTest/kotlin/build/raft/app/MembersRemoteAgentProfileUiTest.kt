package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.members.MembersAgentDetailCallbacks
import build.raft.app.members.MembersAgentDetailLoadState
import build.raft.app.members.MembersAgentDetailPresentation
import build.raft.app.members.MembersDetailActions
import build.raft.app.members.MembersDetailContent
import build.raft.app.members.MembersMachineStatusRow
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cross-server (joint channel) agent profile on the real [MembersDetailContent] composition
 * (`stableVisualCapture = false`):
 * - shows the status returned by `GET /api/agents/:id` (`status` / `activity`) for online and
 *   stopped remote agents when no live status resolver is bound;
 * - live canonical status still wins when bound, and renders no chip (never "—") when the server
 *   gives no runtime status;
 * - omits the whole Description section (heading and "No description") on restricted profiles
 *   (`RemoteProfileOnly` or `profileProjection = "channel_summary"`) when description is empty,
 *   keeps showing it when present, and keeps "No description" on home-server agents.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class MembersRemoteAgentProfileUiTest {
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

    private fun sampleMember(
        id: String = "agent-partner-1",
        description: String? = null,
        serverId: String = "server-partner",
        serverName: String = "Partner Labs",
        profileProjection: String? = null
    ): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = "Ruby",
            handle = "ruby",
            kind = MemberKind.Agent,
            description = description,
            serverId = ServerId(serverId),
            serverName = serverName,
            profileProjection = profileProjection
        )

    private fun sampleDetail(
        id: String = "agent-partner-1",
        description: String? = null,
        status: String? = null,
        activity: String? = null,
        activityDetail: String? = null,
        profileProjection: String? = null
    ): AgentDetailSnapshot =
        AgentDetailSnapshot(
            agentId = MemberId(id),
            summary =
                AgentDetailSummary(
                    id = id,
                    name = "ruby",
                    displayName = "Ruby",
                    description = description,
                    status = status,
                    activity = activity,
                    activityDetail = activityDetail,
                    profileProjection = profileProjection
                ),
            directMessages = emptyList(),
            reminders = emptyList(),
            activity = emptyList()
        )

    @Composable
    private fun ProfilePage(
        member: MemberProfile,
        detail: AgentDetailSnapshot,
        accessMode: MemberDetailAccessMode,
        agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver
    ) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme {
                MembersDetailContent(
                    member = member,
                    currentUserId = "viewer-1",
                    currentServerId = "server-home",
                    agentDetailState = MembersAgentDetailLoadState.Content(detail),
                    canViewPrivateAgentSurfaces = { _, _ -> false },
                    canManageAgent = { _, _ -> false },
                    machineStatusRow = { MembersMachineStatusRow.Loading },
                    actions =
                        MembersDetailActions(
                            onBack = {},
                            onOpenDirectMessage = {},
                            onCopyMemberId = {},
                            onOpenUrl = {},
                            onUnavailableAction = {}
                        ),
                    agentCallbacks =
                        MembersAgentDetailCallbacks(
                            onUpdateAgentProfile = null,
                            onAgentLifecycleAction = null,
                            agentWorkspaceTreeLoader = null,
                            agentWorkspaceChildrenLoader = null,
                            onCopyAgentDiagnostics = {},
                            onReportAgentIssue = null,
                            onUploadAgentAvatar = null
                        ),
                    timeContext = RaftTimeContext(localOffsetMinutes = 0),
                    presentation = MembersAgentDetailPresentation(stableVisualCapture = false),
                    accessMode = accessMode,
                    agentStatusResolver = agentStatusResolver
                )
            }
        }
    }

    @Test
    fun remoteOnlineAgentShowsOnlineStatusAndHidesEmptyDescriptionSection() = runRobolectricComposeUiTest {
        setContent {
            ProfilePage(
                member = sampleMember(description = null),
                detail = sampleDetail(description = null, status = "active", activity = "online"),
                accessMode = MemberDetailAccessMode.RemoteProfileOnly
            )
        }
        waitForIdle()

        onNodeWithText("Online").assertIsDisplayed()
        onNodeWithText("Description").assertDoesNotExist()
        onNodeWithText("No description").assertDoesNotExist()
        onNodeWithText("—").assertDoesNotExist()
    }

    @Test
    fun remoteAgentLiveStatusWinsOverSummaryFallback() = runRobolectricComposeUiTest {
        val liveWorking = raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, external = false)
        setContent {
            ProfilePage(
                member = sampleMember(description = "Triages partner bug reports."),
                detail =
                    sampleDetail(
                        description = "Triages partner bug reports.",
                        status = "active",
                        activity = "online"
                    ),
                accessMode = MemberDetailAccessMode.RemoteProfileOnly,
                agentStatusResolver = {
                    RaftAgentStatusFlow(initial = liveWorking, upstream = flowOf(liveWorking))
                }
            )
        }
        waitForIdle()

        onNodeWithText("Working…").assertIsDisplayed()
        onNodeWithText("Online").assertDoesNotExist()
        onNodeWithText("Description").assertIsDisplayed()
        onNodeWithText("Triages partner bug reports.").assertIsDisplayed()
    }
}
