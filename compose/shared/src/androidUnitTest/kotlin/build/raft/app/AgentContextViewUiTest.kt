package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import build.raft.app.data.AgentContextView
import build.raft.app.data.AgentContextViewAnswer
import build.raft.app.data.AgentContextViewCompaction
import build.raft.app.data.AgentContextViewFailureReason
import build.raft.app.data.AgentContextViewGrant
import build.raft.app.data.AgentContextViewGrantUnusableReason
import build.raft.app.data.AgentContextViewMenuEntryState
import build.raft.app.data.AgentContextViewMessage
import build.raft.app.data.AgentContextViewMessageRole
import build.raft.app.data.AgentContextViewNamedRef
import build.raft.app.data.AgentContextViewOutcome
import build.raft.app.data.AgentContextViewPart
import build.raft.app.data.AgentContextViewRecord
import build.raft.app.data.AgentContextViewRedactionKind
import build.raft.app.data.AgentContextViewSource
import build.raft.app.data.AgentContextViewSourceKind
import build.raft.app.data.AgentContextViewStatus
import build.raft.app.data.AgentContextViewStatusEntry
import build.raft.app.data.AgentContextViewStatusReason
import build.raft.app.data.AgentContextViewStatusState
import build.raft.app.data.AgentContextViewSystemBlock
import build.raft.app.data.AgentContextViewTokens
import build.raft.app.data.AgentContextViewTooLargeCause
import build.raft.app.data.AgentContextViewTool
import build.raft.app.data.AgentContextViewTrigger
import build.raft.app.data.AgentContextViewVerification
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose UI tests for `#wg-mobile-sync:#65` (Agent View Context panel,
 * message action menu integration, and Settings › Administration context access grants;
 * `botiverse/slock` #12980, #13548, #13550, #14072, #14560).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1600dp")
class AgentContextViewUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val fixedTimeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 26),
            // 2026-11-26T12:00:00Z
            nowEpochMillis = 1_795_694_400_000L,
        )

    private val sampleRequest =
        AgentContextViewRequest(
            agentId = "agent-1",
            messageId = "msg-1",
            agentName = "Lagoon",
            agentAvatarWireValue = null,
            messageBody = "Shipped the View Context panel with web parity.",
            createdAtLabel = "11:45",
            channelName = "wg-mobile-sync",
            serverId = "server-1",
            viewerUserId = "user-me",
        )

    private val availableStatus =
        AgentContextViewStatusEntry(
            status =
                AgentContextViewStatus(
                    runtime = "claude-code",
                    computer = AgentContextViewNamedRef("m-1", "Mac Studio"),
                    computerOwner = AgentContextViewNamedRef("u-owner", "Artin"),
                    retainedSince = "2026-11-01T00:00:00Z",
                    retentionDays = 30,
                    state = AgentContextViewStatusState.Available,
                ),
            atMillis = 1_000L,
        )

    private val sampleContext =
        AgentContextView(
            runtime = "claude-code",
            model = "claude-opus-4-6",
            sessionId = "sess-1",
            requestAt = "2026-11-26T11:44:00Z",
            messageId = "msg-1",
            tokens = AgentContextViewTokens(input = 1000L, cacheRead = 500L, total = 1500L),
            system = listOf(AgentContextViewSystemBlock("You are Lagoon, Mobile DRI.")),
            tools =
                listOf(
                    AgentContextViewTool(
                        name = "Bash",
                        description = "Run a shell command",
                        inputSchemaJson = """{"type":"object"}""",
                    )
                ),
            messages =
                listOf(
                    AgentContextViewMessage(
                        role = AgentContextViewMessageRole.User,
                        parts = listOf(AgentContextViewPart.Text("[target=#wg-mobile-sync msg=11112222] Build View Context")),
                    ),
                    AgentContextViewMessage(
                        role = AgentContextViewMessageRole.Assistant,
                        parts =
                            listOf(
                                AgentContextViewPart.Text("Investigating the web contract."),
                                AgentContextViewPart.ToolUse(
                                    toolName = "Bash",
                                    toolUseId = "call-send",
                                    inputJson = """{"command":"raft message send --target #wg-mobile-sync"}""",
                                    flatArguments = listOf("command" to "raft message send --target #wg-mobile-sync"),
                                    summary = "raft message send",
                                ),
                            ),
                    ),
                ),
            compaction = AgentContextViewCompaction.Happened(summaryKind = "plaintext", summaryText = "Earlier turn"),
            trigger = AgentContextViewTrigger(messageIndex = 1, partIndex = 1, toolUseId = "call-send"),
            redactions = mapOf(AgentContextViewRedactionKind.GithubToken to 1),
            source = AgentContextViewSource(kind = AgentContextViewSourceKind.Native, format = "anthropic", version = "1.25.15"),
            verification = AgentContextViewVerification.Verified,
        )

    @Test
    fun panelRendersLoadingSkeletonAndMessageExcerpt() = runRobolectricComposeUiTest {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                AgentContextViewPanelSurface(
                    request = sampleRequest,
                    statusEntry = availableStatus,
                    answer = AgentContextViewAnswer.Loading,
                    viewedBy = null,
                    retrying = false,
                    isSidePanel = false,
                    onClose = {},
                    onRetry = {},
                    onCopyRaw = {},
                    onCopyText = {},
                    onCopyCallId = {},
                    timeContext = fixedTimeContext,
                )
            }
        }
        waitForIdle()
        onNodeWithTag(AGENT_CONTEXT_PANEL_ROOT_TAG).assertIsDisplayed()
        onNodeWithTag(AGENT_CONTEXT_PANEL_EXCERPT_TAG).assertIsDisplayed()
        onNodeWithTag(AGENT_CONTEXT_PANEL_LOADING_TAG).assertIsDisplayed()
        onNodeWithText("Shipped the View Context panel with web parity.").assertIsDisplayed()
    }

    @Test
    fun panelRendersAvailableContextSectionsVerifiedBadgeCopyRawAndViewedBy() = runRobolectricComposeUiTest {
        var copiedRaw: String? = null
        val views =
            listOf(
                AgentContextViewRecord(
                    viewer = AgentContextViewNamedRef("user-me", "Artin"),
                    at = "2026-11-26T11:46:00Z",
                    outcome = AgentContextViewOutcome.Ok,
                    outcomeRaw = "ok",
                ),
                AgentContextViewRecord(
                    viewer = AgentContextViewNamedRef("agent-audit", "AuditBot"),
                    at = "2026-11-26T11:45:00Z",
                    outcome = AgentContextViewOutcome.Ok,
                    outcomeRaw = "ok",
                    viewerIsAgent = true,
                ),
                AgentContextViewRecord(
                    viewer = null,
                    at = "2026-11-26T11:40:00Z",
                    outcome = AgentContextViewOutcome.Offline,
                    outcomeRaw = "offline",
                ),
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                AgentContextViewPanelSurface(
                    request = sampleRequest,
                    statusEntry = availableStatus,
                    answer = AgentContextViewAnswer.Ok(sampleContext, rawJson = """{"runtime":"claude-code"}"""),
                    viewedBy = views,
                    retrying = false,
                    isSidePanel = false,
                    onClose = {},
                    onRetry = {},
                    onCopyRaw = { copiedRaw = it },
                    onCopyText = {},
                    onCopyCallId = {},
                    timeContext = fixedTimeContext,
                )
            }
        }
        waitForIdle()
        onNodeWithText("claude-opus-4-6").assertIsDisplayed()
        onNodeWithText("Compacted").assertIsDisplayed()
        onNodeWithTag(AGENT_CONTEXT_PANEL_VERIFIED_TAG).assertIsDisplayed()
        onNodeWithTag(AGENT_CONTEXT_PANEL_COPY_RAW_TAG).performClick()
        assertEquals("""{"runtime":"claude-code"}""", copiedRaw)

        // Toggle Verified badge shows explanation notice.
        onNodeWithTag(AGENT_CONTEXT_PANEL_VERIFIED_TAG).performClick()
        waitForIdle()
        onNodeWithText("Rebuilt from the agent's own session and checked against what the model received.").assertIsDisplayed()

        // Expand System prompt & Tools sections.
        onNodeWithText("System prompt").performClick()
        waitForIdle()
        onNodeWithText("You are Lagoon, Mobile DRI.").assertIsDisplayed()

        onNodeWithText("Tools").performClick()
        waitForIdle()
        onNodeWithText("Bash").assertIsDisplayed()

        // Search inside Messages.
        onNode(hasSetTextAction(), useUnmergedTree = true).performTextInput("Investigating")
        waitForIdle()
        onNodeWithText("1 of 1").assertIsDisplayed()

        // Viewed by section shows self, agent, and former member with failure outcome.
        onNodeWithTag(AGENT_CONTEXT_VIEWED_BY_TAG).performScrollTo().assertIsDisplayed()
        onNodeWithText("Artin · you").assertIsDisplayed()
        onNodeWithText("AuditBot").assertIsDisplayed()
        onNodeWithText("Former member").assertIsDisplayed()
        onNodeWithText("Offline").assertIsDisplayed()
    }

    @Test
    fun panelRendersOfflineRetryOldDaemonEmptyStatesAndStatusFailedTraceId() = runRobolectricComposeUiTest {
        var retryClicks = 0
        var currentStatus by mutableStateOf(
            availableStatus.copy(
                status =
                    availableStatus.status!!.copy(
                        state = AgentContextViewStatusState.Unavailable(AgentContextViewStatusReason.Offline),
                    )
            )
        )
        var currentAnswer by mutableStateOf<AgentContextViewAnswer?>(null)
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                AgentContextViewPanelSurface(
                    request = sampleRequest,
                    statusEntry = currentStatus,
                    answer = currentAnswer,
                    viewedBy = null,
                    retrying = false,
                    isSidePanel = false,
                    onClose = {},
                    onRetry = { retryClicks += 1 },
                    onCopyRaw = {},
                    onCopyText = {},
                    onCopyCallId = {},
                    timeContext = fixedTimeContext,
                )
            }
        }
        waitForIdle()
        onNodeWithText("Mac Studio is offline, so this context can't be read right now. Try again once it's back.").assertIsDisplayed()
        onNodeWithTag(AGENT_CONTEXT_PANEL_RETRY_TAG).performClick()
        assertEquals(1, retryClicks)

        // old_daemon -> banner without Retry button.
        currentStatus =
            availableStatus.copy(
                status =
                    availableStatus.status!!.copy(
                        state = AgentContextViewStatusState.Unavailable(AgentContextViewStatusReason.OldDaemon),
                    )
            )
        waitForIdle()
        onNodeWithText("The Raft Computer on Mac Studio is too old to show contexts. Update it, then try again.").assertIsDisplayed()
        onAllNodesWithTag(AGENT_CONTEXT_PANEL_RETRY_TAG).assertCountEquals(0)

        // unsupported_runtime -> EmptyState.
        currentStatus =
            availableStatus.copy(
                status =
                    availableStatus.status!!.copy(
                        state = AgentContextViewStatusState.Unavailable(AgentContextViewStatusReason.UnsupportedRuntime),
                    )
            )
        waitForIdle()
        onNodeWithText("Not available for this runtime").assertIsDisplayed()

        // no_session -> EmptyState.
        currentStatus =
            availableStatus.copy(
                status =
                    availableStatus.status!!.copy(
                        state = AgentContextViewStatusState.Unavailable(AgentContextViewStatusReason.NoSession),
                    )
            )
        waitForIdle()
        onNodeWithText("No session record yet").assertIsDisplayed()

        // no_computer -> EmptyState.
        currentStatus =
            availableStatus.copy(
                status =
                    availableStatus.status!!.copy(
                        state = AgentContextViewStatusState.Unavailable(AgentContextViewStatusReason.NoComputer),
                    )
            )
        waitForIdle()
        onNodeWithText("Not on a computer").assertIsDisplayed()

        // status check failed -> Retry + TraceIdNote.
        currentStatus =
            AgentContextViewStatusEntry(
                failed = true,
                traceId = "0123456789abcdef0123456789abcdef",
            )
        waitForIdle()
        onNodeWithText("Couldn't check whether this context can be shown. Try again.").assertIsDisplayed()
        onNodeWithTag(RaftTraceIdNoteTestTag).assertIsDisplayed()
        onNodeWithTag(AGENT_CONTEXT_PANEL_RETRY_TAG).performClick()
        assertEquals(2, retryClicks)

        // too_large (request cause).
        currentStatus = availableStatus
        currentAnswer =
            AgentContextViewAnswer.Failed(
                reason = AgentContextViewFailureReason.TooLarge,
                tooLargeCause = AgentContextViewTooLargeCause.Request,
            )
        waitForIdle()
        onNodeWithText("This context is too large to show: the request itself is too large.").assertIsDisplayed()
    }

    @Test
    fun compactMessageMenuShowsOpenRetryOrHidesViewContext() = runRobolectricComposeUiTest {
        var entryState by mutableStateOf<AgentContextViewMenuEntryState>(AgentContextViewMenuEntryState.ShowOpen)
        var openClicks = 0
        var retryClicks = 0
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                ThreadNewMessageMenuChrome(
                    availability = ThreadNewMessageMenuActionAvailability(),
                    onReact = {},
                    onCopy = {},
                    onReply = {},
                    onForward = {},
                    onSave = {},
                    onTranslate = {},
                    onSelect = {},
                    onMore = {},
                    viewContextState = entryState,
                    onViewContext = { openClicks += 1 },
                    onRetryViewContext = { retryClicks += 1 },
                )
            }
        }
        waitForIdle()
        onNodeWithTag(THREAD_MESSAGE_MENU_VIEW_CONTEXT_TAG).assertIsDisplayed()
        onNodeWithText("View context").performClick()
        assertEquals(1, openClicks)

        // Check failed -> Retry row (#14072).
        entryState = AgentContextViewMenuEntryState.ShowRetry(retrying = false)
        waitForIdle()
        onNodeWithTag(THREAD_MESSAGE_MENU_VIEW_CONTEXT_RETRY_TAG).assertIsDisplayed()
        onNodeWithText("Retry View context").assertIsDisplayed()
        onNodeWithText("Couldn't check").assertIsDisplayed()
        onNodeWithTag(THREAD_MESSAGE_MENU_VIEW_CONTEXT_RETRY_TAG).performClick()
        assertEquals(1, retryClicks)

        // Hidden (404 / human / unsupported_runtime).
        entryState = AgentContextViewMenuEntryState.Hidden
        waitForIdle()
        onAllNodesWithTag(THREAD_MESSAGE_MENU_VIEW_CONTEXT_TAG).assertCountEquals(0)
        onAllNodesWithTag(THREAD_MESSAGE_MENU_VIEW_CONTEXT_RETRY_TAG).assertCountEquals(0)
    }

    @Test
    fun contextAccessGrantsSectionShowsStoppedWorkingBadgeAndPrePickDisclosureDialog() = runRobolectricComposeUiTest {
        val grants =
            listOf(
                AgentContextViewGrant(
                    id = "g-1",
                    agent = AgentContextViewNamedRef("a-1", "Lagoon"),
                    granter = AgentContextViewNamedRef("u-1", "Artin"),
                    grantedAt = "2026-11-20T08:00:00Z",
                    usable = true,
                ),
                AgentContextViewGrant(
                    id = "g-2",
                    agent = AgentContextViewNamedRef("a-2", "Scout"),
                    granter = null,
                    grantedAt = "2026-11-21T09:00:00Z",
                    usable = false,
                    unusableReason = AgentContextViewGrantUnusableReason.GranterGone,
                ),
            )
        var grantDialogOpen by mutableStateOf(false)
        var revokeRequested: AgentContextViewGrant? = null

        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                Column {
                    AgentContextViewGrantsSectionContent(
                        serverId = "s-1",
                        state = AgentContextViewGrantsUiState.Ready(grants),
                        infoNote = null,
                        onDismissInfoNote = {},
                        onRetryLoad = {},
                        onOpenGrantDialog = { grantDialogOpen = true },
                        onRequestRevoke = { revokeRequested = it },
                        timeContext = fixedTimeContext,
                    )
                    if (grantDialogOpen) {
                        GrantContextViewDialogSurface(
                            eligibleCandidates =
                                filterEligibleContextViewGrantCandidates(
                                    candidates =
                                        listOf(
                                            AgentContextViewGrantCandidate(id = "a-eligible", name = "Triage"),
                                            AgentContextViewGrantCandidate(id = "a-pa", name = "MyPA", isPersonalAssistant = true),
                                            AgentContextViewGrantCandidate(id = "a-bot", name = "SlackBot", managedByAppInstallId = "inst-1"),
                                            AgentContextViewGrantCandidate(id = "a-1", name = "Lagoon"),
                                        ),
                                    grantedAgentIds = setOf("a-1", "a-2"),
                                ),
                            granting = false,
                            errorMessage = null,
                            errorTraceId = null,
                            onDismiss = { grantDialogOpen = false },
                            onConfirm = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag(SETTINGS_CONTEXT_GRANTS_SECTION_TAG).assertIsDisplayed()
        onNodeWithText("Lagoon").assertIsDisplayed()
        onNodeWithText("Scout").assertIsDisplayed()
        onNodeWithText("Stopped working · granter left the server").assertIsDisplayed()

        // Open Grant access dialog -> pre-pick warning shown with "this agent", confirm disabled until pick.
        onNodeWithTag(SETTINGS_CONTEXT_GRANTS_OPEN_GRANT_TAG).performClick()
        waitForIdle()
        onNodeWithTag(SETTINGS_CONTEXT_GRANTS_DISCLOSURE_TAG).assertIsDisplayed()
        onNodeWithText(
            "The access is server-wide: this agent can search and read other agents' context on this whole server, " +
                "limited to messages this agent can see. Whoever controls this agent can read what it reads: its creator, " +
                "who may be an ordinary member, and anyone on its Computer."
        ).assertIsDisplayed()
        onNodeWithTag(SETTINGS_CONTEXT_GRANTS_CONFIRM_GRANT_TAG).assertIsNotEnabled()

        // Revoke callback fires on row Revoke button.
        grantDialogOpen = false
        waitForIdle()
        onAllNodesWithText("Revoke")[0].performClick()
        assertTrue(revokeRequested?.id == "g-1")
    }
}
