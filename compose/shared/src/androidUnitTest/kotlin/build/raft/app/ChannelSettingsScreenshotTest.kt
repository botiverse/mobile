package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.ChannelSlackBridgeOption
import build.raft.app.data.ChannelSlackBridgeState
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.RaftLongMessageCollapseConversationPreference
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlinx.coroutines.flow.flowOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The channel settings page (web ChannelOverflowMenu + EditChannelDialog panel), light and dark,
 * as a manager of a public, guest-visible channel who may stop its agents: identity, the members
 * strip (summary, agent activity lights, the add tile), the inline Info form with its Slack
 * channel pick, Guest access,
 * Preferences and the Actions group. Fixed fixture data, no network faces. Reports land in
 * `build/reports/home-screenshots/` (uploaded by CI as `shared-home-screenshots`); verify / record
 * as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h2000dp-xxhdpi")
class ChannelSettingsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun channelSettingsLight() = capture("channel.settings", RaftAppAppearance.Light)

    @Test
    fun channelSettingsDark() = capture("channel.settings.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { ChannelSettingsVisualContent() }
        }
}

private val triage =
    HomeChannelRow(
        threadId = ThreadId("triage"),
        channelId = ChannelId("triage"),
        channelType = "channel",
        name = "triage",
        subtitle = "Intake for bugs AND small feature requests. One top-level message per item, details in the thread.",
        memberCount = 22,
        activityNotificationPreference = ChannelActivityNotificationPreference(activityMuteSupported = true),
        guestVisible = true,
    )

private val memberNames =
    listOf(
        "Artin", "Jiacheng", "Lin Zhou", "Mia", "Volta", "Lagoon", "Friday", "Echo", "Kirby", "Sindre", "Ada",
        "Grace", "Linus", "Ken", "Rob", "Bjarne", "Guido", "Yukihiro", "Margaret", "Barbara", "Edsger", "Donald",
    )

private val triageMembers =
    SharedServerChannelMembersSlice(
        channelId = "triage",
        members =
            memberNames.mapIndexed { index, name ->
                MemberProfile(
                    id = MemberId("member-$index"),
                    displayName = name,
                    handle = name.lowercase().replace(" ", ""),
                    kind = if (index % 3 == 2) MemberKind.Agent else MemberKind.Human,
                )
            },
        canAddMembers = true,
        canAddMembersKnown = true,
        status = SharedServerMembersLoadStatus.Loaded,
    )

/** Agents' live activity lights (web AgentActivityDot): working, online, offline in turn. */
private val visualAgentStatusResolver: RaftAgentStatusResolver = { memberId ->
    val index = memberId?.removePrefix("member-")?.toIntOrNull() ?: 0
    val (status, color) =
        when ((index / 3) % 3) {
            0 -> AgentStatus.Working to RaftColor.BrandYellow
            1 -> AgentStatus.Online to RaftColor.AccentLime
            else -> AgentStatus.Offline to RaftColor.StatusNeutralGray
        }
    flowOf(RaftAgentStatusIndicatorState(status = status, color = color, pulsing = false, label = status.name, external = false))
}

@Composable
internal fun ChannelSettingsVisualContent() {
    ChannelSettingsScaffold(refreshing = false, onBack = {}) {
        ChannelSettingsContent(
            model =
                ChannelSettingsUiModel(
                    channel = triage,
                    memberSlice = triageMembers,
                    pinned = true,
                    muted = false,
                    permissions = channelSettingsPermissions(triage, canManage = true),
                    writeActionsEnabled = true,
                    canStopAllAgents = true,
                    showGuestAccess = true,
                    slackBridge =
                        ChannelSlackBridgeState(
                            options =
                                listOf(
                                    ChannelSlackBridgeOption("C1", "triage", membershipRequired = false, pairedElsewhere = false),
                                    ChannelSlackBridgeOption("C2", "general", membershipRequired = false, pairedElsewhere = true),
                                ),
                            pairedSlackChannelId = "C1",
                        ),
                ),
            longMessageCollapseSetting =
                RaftLongMessageCollapseConversationSetting(
                    preference = RaftLongMessageCollapseConversationPreference(globalEnabled = false, channelOverride = null),
                    available = true,
                    onSetEnabled = {},
                ),
            agentStatusResolver = visualAgentStatusResolver,
            callbacks = ChannelSettingsCallbacks(),
        )
    }
}
