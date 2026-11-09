package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentBlockedReasonComputerAccessRevoked
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentMigrationIntent
import build.raft.app.data.AgentMigrationState
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftProGateDecision
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.blockedMemberStatusOrNull
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agent page's status on a real composition (AGENTS.md "Compose UI tests"), against the web
 * behaviour tests: botiverse/slock #13345 agentRuntimeErrorBanner.behavior.test.tsx (Retry on
 * every status that needs a manual restart, `agent-status-retry`), #13575 (a revoked Computer's
 * agent reads "Computer access revoked: move the agent…", and Move places it without the Pro
 * gate) and #13604 (its workspace says so, without Retry).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class AgentStatusRetryUiTest {
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

    private val revokedText =
        "Computer access revoked: move the agent to another computer (re-attaching adds a new one)"

    private fun status(status: AgentStatus, detailKind: String? = null): RaftAgentStatusIndicatorState =
        SharedServerMemberStatus(
            status = status,
            presence = status.presence,
            detailKind = detailKind,
            runtime = "codex",
            external = false
        ).toRaftAgentStatusIndicatorState()

    private fun ComposeUiTest.showHero(
        state: RaftAgentStatusIndicatorState,
        summary: AgentDetailSummary = AgentDetailSummary(id = "agent-1", name = "ruby", runtime = "codex"),
        member: MemberProfile = agent,
        onRetry: (() -> Unit)?,
        retryInProgress: Boolean = false,
    ) {
        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.fillMaxSize()) {
                    MembersAgentProfileHeader(
                        member = member,
                        summary = summary,
                        canEdit = false,
                        agentStatusObservedState = state,
                        agentStatusIndicatorState = state,
                        onChangeAvatar = {},
                        showActivityDetail = true,
                        onRetry = onRetry,
                        retryInProgress = retryInProgress,
                    )
                }
            }
        }
    }

    @Test
    fun anErrorOffersRetryThatRestartsTheAgent() =
        runRobolectricComposeUiTest {
            var retries = 0
            showHero(status(AgentStatus.Error), onRetry = { retries++ })
            onNodeWithText("Retry").assertExists()
            onNodeWithTag(AgentStatusRetryTestTags.Retry).performClick()
            assertEquals(1, retries)
        }

    @Test
    fun whileTheRestartRunsItSaysSoAndIsDisabled() =
        runRobolectricComposeUiTest {
            showHero(status(AgentStatus.Error), onRetry = {}, retryInProgress = true)
            onNodeWithText("Restarting…").assertExists()
            onNodeWithTag(AgentStatusRetryTestTags.Retry).assertIsNotEnabled()
        }

    @Test
    fun pausedWakesOfferRetry() =
        runRobolectricComposeUiTest {
            showHero(status(AgentStatus.Offline, detailKind = "wake_crash_loop_blocked"), onRetry = {})
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(1)
        }

    @Test
    fun aPlainOfflineAgentHasNoRetry() =
        runRobolectricComposeUiTest {
            showHero(status(AgentStatus.Offline), onRetry = {})
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(0)
        }

    @Test
    fun aRateLimitOrAViewerWhoMayNotRestartGetsNoRetry() =
        runRobolectricComposeUiTest {
            showHero(
                status(AgentStatus.Error),
                summary = AgentDetailSummary(id = "agent-1", name = "ruby", runtime = "codex", lastRuntimeErrorClass = "RateLimitError"),
                onRetry = {},
            )
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(0)
        }

    @Test
    fun withoutTheRightToRestartThereIsNoRetry() =
        runRobolectricComposeUiTest {
            showHero(status(AgentStatus.Error), onRetry = null)
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(0)
        }

    @Test
    fun aRevokedComputersAgentSaysToMoveItAndHasNoRetry() =
        runRobolectricComposeUiTest {
            val member = agent.copy(agentBlockedReason = AgentBlockedReasonComputerAccessRevoked)
            val held = requireNotNull(member.blockedMemberStatusOrNull()).toRaftAgentStatusIndicatorState()
            showHero(held, member = member, onRetry = {})
            onNodeWithTag(AgentStatusRetryTestTags.Blocked).assertExists()
            onNodeWithText(revokedText).assertExists()
            onNodeWithText("Error").assertExists()
            onAllNodesWithText("Computer offline", substring = true).assertCountEquals(0)
            onAllNodesWithTag(AgentStatusRetryTestTags.Retry).assertCountEquals(0)
        }

    @Test
    fun everyLiveStatusCaptionReadsTheRevokedSentence() =
        runRobolectricComposeUiTest {
            val member = agent.copy(agentBlockedReason = AgentBlockedReasonComputerAccessRevoked)
            val held = requireNotNull(member.blockedMemberStatusOrNull()).toRaftAgentStatusIndicatorState()
            var caption = ""
            var coarse = ""
            setContent {
                RaftDesignSystemTheme {
                    caption = held.localizedMemberStatusLabel()
                    // Surfaces without the detailed activity keep the held words too.
                    coarse = held.withoutActivityDetail().localizedMemberStatusLabel()
                }
            }
            waitForIdle()
            assertEquals(revokedText, caption)
            assertEquals(revokedText, coarse)
        }

    @Test
    fun moveFromARevokedComputerSkipsTheProGateAndTheCloud() =
        runRobolectricComposeUiTest {
            val intents = mutableListOf<AgentMigrationIntent>()
            var billingOpened = 0
            setContent {
                MigrationFixtureHost {
                    AgentMigrationSection(
                        AgentMigrationState(agentId = agent.id, loaded = true),
                        AgentMigrationSectionInputs(
                            sourceMachineId = "m-revoked",
                            computers = listOf(AgentMigrationComputer("m-target", "KopiC", isComputer = true)),
                            hostedAvailable = true,
                            proGate = RaftProGateDecision.UpgradeRequired,
                            proGateChecking = false,
                            onIntent = { intents += it },
                            onOpenBilling = { billingOpened++ },
                            onCopy = {},
                            formatTimestamp = { null },
                            onAgentPlacementChanged = {},
                            placesWithoutSource = true,
                        ),
                    )
                }
            }
            // No cloud entry ("Move agent"): the plain computer move.
            onAllNodesWithText("Move agent").assertCountEquals(0)
            onNodeWithText("Move to another computer").performClick()
            waitForIdle()
            assertEquals(0, billingOpened, "placing is not a Pro migration")
            assertTrue(AgentMigrationIntent.ClearStartError in intents, "the start dialog opened")
            // The dialog says nothing can be brought over (web `agent-migration-place-note`).
            onNodeWithTag(AgentMigrationSectionTestTags.PlaceNote).assertExists()
            onNodeWithText("This agent's Computer lost its access to the server", substring = true).assertExists()
        }
}
