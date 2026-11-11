package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AppLocale
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.resources.StringDesc
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshot of the agent profile hero for an agent held for an unusable sign-in (web
 * AgentStatusBadge, botiverse/slock #13276): the error light with "Sign-in needed for Codex CLI"
 * instead of Online, and the quiet Retry beside it. Light and dark; verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h200dp-xxhdpi")
class AgentCredentialHoldScreenshotTest {
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

    @Test
    fun heldLight() = capture("members.agent-profile-header.credential-hold", RaftAppAppearance.Light)

    @Test
    fun heldDark() = capture("members.agent-profile-header.credential-hold.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
        ) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    val held =
                        SharedServerMemberStatus(
                            status = AgentStatus.Online,
                            presence = AgentStatus.Online.presence,
                            statusLabel = "held notice",
                            detailKind = "idle",
                            runtime = "codex",
                            external = false,
                        ).toRaftAgentStatusIndicatorState()
                    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(horizontal = 20.dp, vertical = 16.dp)) {
                        MembersAgentProfileHeader(
                            member = MemberProfile(id = MemberId("agent-ruby"), displayName = "Ruby", handle = "Ruby", kind = MemberKind.Agent),
                            summary =
                                AgentDetailSummary(
                                    id = "agent-ruby",
                                    name = "Ruby",
                                    displayName = "Ruby",
                                    status = "active",
                                    runtime = "codex",
                                    activity = "online",
                                    activityDetail = "held notice",
                                    activityDetailKind = "idle",
                                    credentialHold = AgentCredentialHoldReason.UserReauth,
                                ),
                            canEdit = false,
                            agentStatusObservedState = held,
                            agentStatusIndicatorState = held,
                            onChangeAvatar = {},
                            showActivityDetail = true,
                            onRetry = {},
                        )
                    }
                }
            }
        }
}
