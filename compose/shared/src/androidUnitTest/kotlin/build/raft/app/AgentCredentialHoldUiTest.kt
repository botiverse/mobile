package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agent page's status for an unusable sign-in, plan or balance on a real composition (AGENTS.md
 * "Compose UI tests"), against web agentRuntimeErrorBanner.behavior.test.tsx "4b" (botiverse/slock
 * #13276, staging 853c6998b): the reason instead of Online / "Error: …", Retry for whoever may
 * restart, a plain Error for a viewer without the agent's details.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class AgentCredentialHoldUiTest {
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
        const val HeldDetail = "Codex CLI is not logged in on this computer. (Reported by another agent on the same sign-in.)"
        const val OwnAuthDetail = "Codex CLI is not logged in on this computer. Sign in, then retry starting this agent."
        const val PlanDetail =
            "Codex CLI reported that the provider plan for this agent does not include access to the configured model (gpt-x)."
    }

    private val agent = MemberProfile(id = MemberId("agent-1"), displayName = "Ruby", handle = "ruby", kind = MemberKind.Agent)

    private fun live(status: AgentStatus, detail: String, detailKind: String): RaftAgentStatusIndicatorState =
        SharedServerMemberStatus(
            status = status,
            presence = status.presence,
            statusLabel = detail,
            detailKind = detailKind,
            runtime = "codex",
            external = false
        ).toRaftAgentStatusIndicatorState()

    private fun ComposeUiTest.showHero(
        state: RaftAgentStatusIndicatorState,
        summary: AgentDetailSummary,
        onRetry: (() -> Unit)?,
        showActivityDetail: Boolean = true,
    ) {
        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    MembersAgentProfileHeader(
                        member = agent,
                        summary = summary,
                        canEdit = false,
                        agentStatusObservedState = state,
                        agentStatusIndicatorState = state,
                        onChangeAvatar = {},
                        showActivityDetail = showActivityDetail,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }

    private fun summary(
        activity: String,
        detail: String,
        detailKind: String,
        hold: AgentCredentialHoldReason? = null,
        errorMessage: String? = null,
        errorClass: String? = null,
        requiredAction: AgentCredentialHoldReason? = null,
    ) = AgentDetailSummary(
        id = "agent-1",
        name = "ruby",
        runtime = "codex",
        model = "gpt-x",
        activity = activity,
        activityDetail = detail,
        activityDetailKind = detailKind,
        credentialHold = hold,
        lastRuntimeErrorMessage = errorMessage,
        lastRuntimeErrorClass = errorClass,
        lastRuntimeErrorRequiredAction = requiredAction,
    )

    @Test
    fun aHeldAgentShowsTheReasonInsteadOfOnlineWithRetry() =
        runRobolectricComposeUiTest {
            var retries = 0
            showHero(
                live(AgentStatus.Online, HeldDetail, "idle"),
                summary("online", HeldDetail, "idle", hold = AgentCredentialHoldReason.Billing),
                onRetry = { retries++ },
            )
            onNodeWithText("Provider balance exhausted").assertExists()
            onAllNodesWithText("Online").assertCountEquals(0)
            onNodeWithTag(AgentStatusRetryTestTags.Retry).performClick()
            assertEquals(1, retries)
        }

    @Test
    fun anOwnSignInOrPlanErrorNamesTheRuntimeOrTheModel() =
        runRobolectricComposeUiTest {
            showHero(
                live(AgentStatus.Error, OwnAuthDetail, "runtime_error"),
                summary(
                    "error",
                    OwnAuthDetail,
                    "runtime_error",
                    errorMessage = OwnAuthDetail,
                    errorClass = "AuthError",
                    requiredAction = AgentCredentialHoldReason.UserReauth,
                ),
                onRetry = {},
            )
            onNodeWithText("Sign-in needed for Codex CLI").assertExists()
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(1)
        }

    @Test
    fun aPlanWithoutTheModelNamesTheModel() =
        runRobolectricComposeUiTest {
            showHero(
                live(AgentStatus.Error, PlanDetail, "runtime_error"),
                summary(
                    "error",
                    PlanDetail,
                    "runtime_error",
                    errorMessage = PlanDetail,
                    errorClass = "ModelConfigError",
                    requiredAction = AgentCredentialHoldReason.UserModelAccess,
                ),
                onRetry = {},
            )
            onNodeWithText("Plan doesn't include gpt-x").assertExists()
        }

    @Test
    fun anOlderDaemonsHeldNoticeStaysOnlineWithoutRetry() =
        runRobolectricComposeUiTest {
            showHero(live(AgentStatus.Online, HeldDetail, "idle"), summary("online", HeldDetail, "idle"), onRetry = {})
            onAllNodesWithText("Provider balance exhausted").assertCountEquals(0)
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(0)
        }

    @Test
    fun withoutTheRightToRestartThereIsNoRetry() =
        runRobolectricComposeUiTest {
            showHero(
                live(AgentStatus.Online, HeldDetail, "idle"),
                summary("online", HeldDetail, "idle", hold = AgentCredentialHoldReason.UserReauth),
                onRetry = null,
            )
            onNodeWithText("Sign-in needed for Codex CLI").assertExists()
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(0)
        }

    @Test
    fun aViewerWithoutTheDetailsSeesAPlainErrorBesideRetry() =
        runRobolectricComposeUiTest {
            showHero(
                live(AgentStatus.Online, HeldDetail, "idle"),
                summary("online", HeldDetail, "idle", hold = AgentCredentialHoldReason.UserReauth),
                onRetry = {},
                showActivityDetail = false,
            )
            onNodeWithText("Error").assertExists()
            onAllNodesWithText("Sign-in needed", substring = true).assertCountEquals(0)
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(1)
        }
}
