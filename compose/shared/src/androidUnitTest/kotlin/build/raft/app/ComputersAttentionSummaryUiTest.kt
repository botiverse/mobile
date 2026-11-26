package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.data.ANTHROPIC_VERIFICATION_PORTAL_URL
import build.raft.app.data.AppLocale
import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.ComputerReadinessAction
import build.raft.app.data.ComputerReadinessActor
import build.raft.app.data.ComputerReadinessAttention
import build.raft.app.data.ComputerReadinessAttentionItem
import build.raft.app.data.ComputerReadinessBehaviour
import build.raft.app.data.ComputerReadinessEntry
import build.raft.app.data.ComputerReadinessFact
import build.raft.app.data.ComputerReadinessResource
import build.raft.app.data.ComputerReadinessScope
import build.raft.app.data.ComputerReadinessSnapshot
import build.raft.app.data.ComputerReadinessState
import build.raft.app.data.ComputersRackView
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
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
 * Robolectric Compose UI tests for the Computers page needs-attention top summary, header counts,
 * orange/yellow status dots, guest owner-name privacy, and runtime readiness notice
 * (`#wg-mobile-sync:#337`, botiverse/slock #14554, #14587, #14602).
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ComputersAttentionSummaryUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    // 2026-11-10T12:00:00Z
    private val now = 1_794_312_000_000L

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

    private fun computer(
        id: String,
        name: String = id,
        online: Boolean = true,
        attention: ComputerReadinessAttention? = null,
        readiness: ComputerReadinessSnapshot? = null,
    ) =
        SettingsComputerSnapshot(
            id = id,
            name = name,
            daemonVersion = "",
            computerVersion = "1.0.53",
            isComputer = true,
            online = online,
            latestComputerVersion = "1.0.53",
            runtimes = listOf("claude"),
            serverNeedsAttention = attention?.let { it.count > 0 },
            attention = attention,
            readiness = readiness,
        )

    private fun ComposeUiTest.show(
        computers: List<SettingsComputerSnapshot>,
        onToolbarIntent: (RaftComputersRackToolbarIntent) -> Unit = {},
        onOpenComputer: (String) -> Unit = {},
    ) {
        val model = raftComputersListModel(ScreenLoadState.Content(SettingsComputersSnapshot(computers = computers)), nowMillis = now)
        val time = RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = now)
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(time)) {
                    RaftComputersTheme {
                        RaftSettingsComputersContent(
                            model = model,
                            onRetry = {},
                            onAddComputer = {},
                            onOpenComputer = onOpenComputer,
                            rackView = ComputersRackView.Cards,
                            toolbar = RaftComputersRackToolbarState(serverId = "server-1"),
                            onToolbarIntent = onToolbarIntent,
                            serverId = "server-1",
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun summaryHiddenOnOlderServerOrWhenNothingNeedsAttention() = runRobolectricComposeUiTest {
        show(listOf(computer("legacy-offline", online = false)))
        onNodeWithTag(COMPUTERS_ATTENTION_SUMMARY_TAG).assertDoesNotExist()
        onNodeWithTag(COMPUTERS_RACK_COUNTS_TAG).assertIsDisplayed()
        onNodeWithText("need attention", substring = true).assertDoesNotExist()
    }

    @Test
    fun summaryHiddenWhenAttentionCountAndWaitingAreZero() = runRobolectricComposeUiTest {
        show(listOf(computer("ok", attention = ComputerReadinessAttention(count = 0, items = emptyList(), waitingAgents = 0))))
        onNodeWithTag(COMPUTERS_ATTENTION_SUMMARY_TAG).assertDoesNotExist()
    }

    @Test
    fun singleItemForOwnerShowsReasonAgentsActionWaitingLineAndHandlesClicks() = runRobolectricComposeUiTest {
        val verifyForOwner =
            ComputerReadinessAttention(
                count = 1,
                items =
                    listOf(
                        ComputerReadinessAttentionItem(
                            code = "verification_required",
                            resource = ComputerReadinessResource.Login("shared:claude"),
                            actor = ComputerReadinessActor.ComputerOwner,
                            actorName = "Olive",
                            action = ComputerReadinessAction.VerifyAccount,
                            agentIds = listOf("a1", "a2"),
                            agentCount = 2,
                        ),
                    ),
                waitingAgents = 0,
            )
        val waiting =
            ComputerReadinessAttention(
                count = 0,
                items = emptyList(),
                waitingAgents = 3,
                waitingUntilMs = now + 5L * MillisPerMinute,
            )
        val intents = mutableListOf<RaftComputersRackToolbarIntent>()
        val opened = mutableListOf<String>()

        show(
            computers = listOf(computer("oak-linux", attention = verifyForOwner), computer("elm", attention = waiting)),
            onToolbarIntent = { intents += it },
            onOpenComputer = { opened += it },
        )

        onNodeWithTag(COMPUTERS_ATTENTION_SUMMARY_TAG).assertIsDisplayed()
        onNodeWithTag(COMPUTERS_ATTENTION_FILTER_TAG)
            .assertIsDisplayed()
            .assertTextEquals("Claude Code: Account needs verification on oak-linux")
        onNodeWithText("2 agents").assertIsDisplayed()
        onNodeWithTag(COMPUTERS_ATTENTION_ACTION_TAG)
            .assertIsDisplayed()
            .assertTextEquals("Verify account")
        onNodeWithTag(COMPUTERS_WAITING_LINE_TAG).assertIsDisplayed()
        onNodeWithText("3 agents waiting").assertIsDisplayed()
        onNodeWithText("back in 5m").assertIsDisplayed()
        onNodeWithTag(COMPUTERS_RACK_COUNTS_TAG).assertTextContains("1\u00A0need\u00A0attention", substring = true)
        onNode(
            hasContentDescription("Claude Code: Account needs verification · Olive can verify the account"),
            useUnmergedTree = true,
        ).assertExists()

        onNodeWithTag(COMPUTERS_ATTENTION_ACTION_TAG).performClick()
        assertEquals(listOf("oak-linux"), opened)

        onNodeWithTag(COMPUTERS_ATTENTION_FILTER_TAG).performClick()
        assertTrue(
            intents.contains(RaftComputersRackToolbarIntent.SetStatus(RaftComputersRackStatusFilter.Attention)),
            "clicking the needs-attention summary applies Status: Needs attention",
        )
    }

    @Test
    fun singleItemForMemberShowsWhoActsNeverWhyAndNoActionButton() = runRobolectricComposeUiTest {
        val memberAttention =
            ComputerReadinessAttention(
                count = 1,
                items =
                    listOf(
                        ComputerReadinessAttentionItem(
                            code = null,
                            resource = null,
                            actor = ComputerReadinessActor.ComputerOwner,
                            actorName = "Olive",
                            action = ComputerReadinessAction.Fix,
                            agentCount = 2,
                        ),
                    ),
                waitingAgents = 0,
            )
        show(listOf(computer("oak-linux", attention = memberAttention)))

        onNodeWithTag(COMPUTERS_ATTENTION_FILTER_TAG).assertTextEquals("Needs attention on oak-linux")
        onNodeWithText("2 agents can't run").assertIsDisplayed()
        onNodeWithText("Olive can fix it").assertIsDisplayed()
        onNodeWithTag(COMPUTERS_ATTENTION_ACTION_TAG).assertDoesNotExist()
        onNodeWithTag(COMPUTERS_WAITING_LINE_TAG).assertDoesNotExist()
        onNode(hasContentDescription("Needs attention · Olive can fix it"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun singleItemForGuestUsesRoleCopyWithoutOwnerName() = runRobolectricComposeUiTest {
        val guestAttention =
            ComputerReadinessAttention(
                count = 1,
                items =
                    listOf(
                        ComputerReadinessAttentionItem(
                            code = null,
                            resource = null,
                            actor = ComputerReadinessActor.ComputerOwner,
                            actorName = null,
                            action = ComputerReadinessAction.Fix,
                            agentCount = 1,
                        ),
                    ),
                waitingAgents = 0,
            )
        show(listOf(computer("oak-linux", attention = guestAttention)))

        onNodeWithTag(COMPUTERS_ATTENTION_FILTER_TAG).assertTextEquals("Needs attention on oak-linux")
        onNodeWithText("1 agent can't run").assertIsDisplayed()
        onNodeWithText("The computer's owner can fix it").assertIsDisplayed()
        onNodeWithTag(COMPUTERS_ATTENTION_ACTION_TAG).assertDoesNotExist()
        onNode(hasContentDescription("Needs attention · The computer's owner can fix it"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun multipleComputersSummaryAppliesFilterAndRestartingWindowShowsRestartingTone() = runRobolectricComposeUiTest {
        val flagged =
            ComputerReadinessAttention(
                count = 1,
                items =
                    listOf(
                        ComputerReadinessAttentionItem(
                            actor = ComputerReadinessActor.ComputerOwner,
                            actorName = "Olive",
                            action = ComputerReadinessAction.Fix,
                            agentCount = 1,
                        ),
                    ),
                waitingAgents = 0,
            )
        val restartingReadiness =
            ComputerReadinessSnapshot(
                own =
                    ComputerReadinessEntry(
                        state = ComputerReadinessState.Waiting,
                        primary =
                            ComputerReadinessFact(
                                resource = ComputerReadinessResource.Computer,
                                code = "computer_restarting",
                                behaviour = ComputerReadinessBehaviour.Waiting,
                                sinceMs = now - 60_000L,
                                untilMs = now + 240_000L,
                            ),
                        scope = ComputerReadinessScope.Computer,
                    ),
            )
        val intents = mutableListOf<RaftComputersRackToolbarIntent>()

        show(
            computers =
                listOf(
                    computer("a", attention = flagged),
                    computer("b", attention = flagged.copy(waitingAgents = 4, waitingUntilMs = now + 6L * MillisPerDay)),
                    computer("c", online = false, attention = ComputerReadinessAttention(count = 0), readiness = restartingReadiness),
                ),
            onToolbarIntent = { intents += it },
        )

        onNodeWithTag(COMPUTERS_ATTENTION_FILTER_TAG).assertTextEquals("2 computers need attention")
        onNodeWithText("4 agents waiting").assertIsDisplayed()
        onNodeWithText("back in 6d").assertIsDisplayed()
        onNodeWithTag(COMPUTERS_RACK_COUNTS_TAG).assertTextContains("2\u00A0need\u00A0attention", substring = true)
        onNode(hasContentDescription("Restarting…"), useUnmergedTree = true).assertExists()

        onNodeWithTag(COMPUTERS_ATTENTION_FILTER_TAG).performClick()
        assertEquals(
            listOf(RaftComputersRackToolbarIntent.SetStatus(RaftComputersRackStatusFilter.Attention)),
            intents,
        )
    }

    @Test
    fun runtimeReadinessNoticeRendersHeadingBodyAndFixedVerificationPortalLink() = runRobolectricComposeUiTest {
        val openedUrls = mutableListOf<String>()
        val readiness =
            ComputerReadinessEntry(
                state = ComputerReadinessState.Blocked,
                primary =
                    ComputerReadinessFact(
                        resource = ComputerReadinessResource.Login("shared:claude"),
                        code = "verification_required",
                        behaviour = ComputerReadinessBehaviour.Blocked,
                        sinceMs = now - 60_000L,
                    ),
                scope = ComputerReadinessScope.Computer,
                actor = ComputerReadinessActor.ComputerOwner,
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftRuntimeReadinessNotice(
                        readiness = readiness,
                        runtimeId = "claude",
                        runtimeCatalog = BundledRuntimeCatalog,
                        nowMillis = now,
                        inset = 16.dp,
                        onOpenUrl = { openedUrls += it },
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag(RUNTIME_READINESS_NOTICE_TAG).assertIsDisplayed()
        onNodeWithText("This Computer's problem: Account needs verification").assertIsDisplayed()
        onNodeWithText("The computer's owner can fix it.", substring = true).assertIsDisplayed()
        onNodeWithTag(RUNTIME_READINESS_LINK_TAG)
            .assertIsDisplayed()
            .assertTextContains("Open the Anthropic verification portal", substring = true)
            .performClick()
        assertEquals(listOf(ANTHROPIC_VERIFICATION_PORTAL_URL), openedUrls)
    }
}
