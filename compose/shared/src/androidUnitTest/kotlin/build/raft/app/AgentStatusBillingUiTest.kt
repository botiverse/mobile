package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import build.raft.app.model.AgentSpawnFailure
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
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
 * The agent page on a real composition (AGENTS.md "Compose UI tests"), against the web behaviour
 * tests (botiverse/slock #13460 agentRuntimeErrorBanner.behavior.test.tsx: the status text and
 * Change model; #13435 agentBillingBanner.behavior.test.tsx: the billing banner, its one action,
 * or who can fix it).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class AgentStatusBillingUiTest {
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

    private val agent = MemberProfile(id = MemberId("agent-1"), displayName = "Ruby", handle = "ruby", kind = MemberKind.Agent)

    private val offline =
        SharedServerMemberStatus(status = AgentStatus.Offline, presence = AgentStatus.Offline.presence, runtime = "codex", external = false)
            .toRaftAgentStatusIndicatorState()

    private fun ComposeUiTest.showHero(
        spawnFailure: AgentSpawnFailure,
        onChangeModel: (() -> Unit)?,
        showActivityDetail: Boolean = true,
    ) {
        val summary =
            AgentDetailSummary(
                id = "agent-1",
                name = "ruby",
                runtime = "codex",
                activity = "offline",
                activityDetail = "Codex said: model not supported",
                activityDetailKind = "runtime_unavailable",
                spawnFailure = spawnFailure,
            )
        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    MembersAgentProfileHeader(
                        member = agent,
                        summary = summary,
                        canEdit = false,
                        agentStatusObservedState = offline,
                        agentStatusIndicatorState = offline,
                        onChangeAvatar = {},
                        showActivityDetail = showActivityDetail,
                        spawnFailure = membersAgentStatusSpawnFailure(offline, summary.activityDetailKind, summary.activityDetail, spawnFailure),
                        onChangeModel = onChangeModel,
                        timeContext = RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = 1_782_367_200_000L),
                    )
                }
            }
        }
    }

    @Test
    fun aModelTheRuntimeCannotUseOffersChangeModel() =
        runRobolectricComposeUiTest {
            var opens = 0
            showHero(AgentSpawnFailure("model_unsupported", model = "o3-pro"), onChangeModel = { opens++ })
            onNodeWithText(
                "This runtime's account or plan doesn't include \"o3-pro\". Change the model, or sign in with an account that includes it",
            ).assertExists()
            // The daemon's English detail is replaced, not shown beside it.
            onAllNodesWithText("Codex said: model not supported", substring = true).assertCountEquals(0)
            onNodeWithTag(AgentSpawnFailureStatusTestTags.ChangeModel).performClick()
            assertEquals(1, opens)
        }

    @Test
    fun withoutTheRightThereIsNoChangeModel() =
        runRobolectricComposeUiTest {
            showHero(AgentSpawnFailure("session_needs_reset", autoRestartPaused = true), onChangeModel = null)
            onNodeWithText("The context is too large", substring = true).assertExists()
            onAllNodesWithTag(AgentSpawnFailureStatusTestTags.ChangeModel).assertCountEquals(0)
        }

    @Test
    fun aRepeatedFailureSaysWhenRaftTriesNextAndHasNoChangeModel() =
        runRobolectricComposeUiTest {
            // 2026-06-25 06:00 UTC; the next try 30 min later, the same day: the clock only.
            showHero(
                AgentSpawnFailure("runtime_repeated_failure", autoRestartPaused = true, pauseRetryAtMs = 1_782_367_200_000L + 30 * 60_000L),
                onChangeModel = {},
            )
            onNodeWithText("Raft tries again by itself at 06:30", substring = true).assertExists()
            onAllNodesWithTag(AgentSpawnFailureStatusTestTags.ChangeModel).assertCountEquals(0)
        }

    @Test
    fun aViewerWithoutDetailSeesOnlyTheStatus() =
        runRobolectricComposeUiTest {
            showHero(AgentSpawnFailure("model_unsupported", model = "o3-pro"), onChangeModel = {}, showActivityDetail = false)
            onAllNodesWithTag(AgentSpawnFailureStatusTestTags.Text).assertCountEquals(0)
            onAllNodesWithTag(AgentSpawnFailureStatusTestTags.ChangeModel).assertCountEquals(0)
        }

    private fun ComposeUiTest.showBillingBanner(canViewPrivate: Boolean, onOpenModelSettings: (() -> Unit)?, onCopyInfo: () -> Unit = {}) {
        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    MembersAgentRuntimeErrorBanner(
                        errorText = agentRuntimeErrorDisplayText(AgentRuntimeErrorDisplay.Known(AgentRuntimeErrorKind.BillingExhausted)),
                        canViewPrivate = canViewPrivate,
                        showViewLogs = false,
                        onViewLogs = {},
                        onCopyInfo = onCopyInfo,
                        billingAgentName = "Ruby",
                        onOpenModelSettings = onOpenModelSettings,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    @Test
    fun theBillingBannerNamesTheAgentAndOpensModelSettings() =
        runRobolectricComposeUiTest {
            var opens = 0
            showBillingBanner(canViewPrivate = true, onOpenModelSettings = { opens++ })
            onNodeWithTag(MembersAgentBillingBannerTestTags.Banner).assertExists()
            onNodeWithText("Model service balance too low").assertExists()
            onNodeWithText(
                "Ruby can't reply right now. Check the balance and billing for the model service this agent uses, then start it again.",
            ).assertExists()
            onAllNodesWithText("Ask a server admin to fix this.").assertCountEquals(0)
            onAllNodesWithText("View Logs", ignoreCase = true).assertCountEquals(0)
            onNodeWithText("View model settings").performClick()
            assertEquals(1, opens)
        }

    @Test
    fun withoutTheRightTheBillingBannerSaysWhoCanFixIt() =
        runRobolectricComposeUiTest {
            showBillingBanner(canViewPrivate = false, onOpenModelSettings = null)
            // Catalog copy for every viewer, never the generic "agent error" fallback.
            onNodeWithText("Model service balance too low").assertExists()
            onNodeWithText("Ask a server admin to fix this.").assertExists()
            onAllNodesWithTag(MembersAgentBillingBannerTestTags.ModelSettings).assertCountEquals(0)
            onAllNodesWithText("Copy Info", ignoreCase = true).assertCountEquals(0)
        }
}
