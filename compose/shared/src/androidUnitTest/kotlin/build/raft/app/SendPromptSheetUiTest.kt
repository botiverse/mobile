package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftComposerSendKey
import build.raft.app.data.RaftComposerSendKeyRuntime
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class SendPromptSheetUiTest {
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

    private fun agent(
        id: String,
        name: String,
        role: String? = "member",
        description: String? = null,
    ): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = id,
            kind = MemberKind.Agent,
            description = description,
            serverRole = role,
        )

    @Composable
    private fun DialogTestShell(content: @Composable () -> Unit) {
        val modalHostState = rememberRaftUnifiedDialogHostState()
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme {
                RaftUnifiedDialogHostProvider(modalHostState) {
                    Box(Modifier.fillMaxSize()) {
                        content()
                        RaftUnifiedDialogHost(state = modalHostState, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    @Test
    fun emptyStateShowsNoAgentsMessageWithoutCancelButton() =
        runRobolectricComposeUiTest {
            setContent {
                DialogTestShell {
                    SendPromptDialog(
                        prompt = "Check my tasks",
                        serverId = "srv-1",
                        recipients = emptyList(),
                        officialOnboardingAgentId = null,
                        recentAgentIds = emptyList(),
                        dmChannelByAgent = emptyMap(),
                        loadPreview = { SendPromptDmPreviewResult.Loaded(emptyList()) },
                        onSend = { _, _ -> true },
                        onClose = {},
                    )
                }
            }

            waitForIdle()
            onNode(hasTestTag(SendPromptNoAgentsTestTag)).assertIsDisplayed()
            onNode(hasText("There are no agents on this server you can message yet.")).assertIsDisplayed()
            onNode(hasText("Cancel")).assertDoesNotExist()
        }

    @Test
    fun dmRedesignShowsHeadNoticeComposerWithoutCancelOrAttachAndSendsEditedPrompt() =
        runRobolectricComposeUiTest {
            val cindy = agent("agent-cindy", "Cindy", role = "admin", description = "Onboarding assistant")
            val alex = agent("agent-alex", "Alex", role = "member")
            val sent = mutableListOf<Pair<String, String>>()
            val closedEvents = mutableListOf<Triple<String, Boolean, Boolean>>()
            var closeCalls = 0

            setContent {
                DialogTestShell {
                    SendPromptDialog(
                        prompt = "Summarize today",
                        serverId = "srv-1",
                        recipients = listOf(alex, cindy),
                        officialOnboardingAgentId = "agent-cindy",
                        recentAgentIds = listOf("agent-alex"),
                        dmChannelByAgent = mapOf("agent-cindy" to ChannelId("dm-cindy")),
                        loadPreview = { channelId ->
                            if (channelId == ChannelId("dm-cindy")) {
                                SendPromptDmPreviewResult.Loaded(
                                    listOf(
                                        SendPromptPreviewMessage(
                                            id = "m1",
                                            senderId = "agent-cindy",
                                            senderKind = MemberKind.Agent,
                                            senderName = "Cindy",
                                            content = "Welcome to Raft!",
                                        ),
                                        SendPromptPreviewMessage(
                                            id = "m2",
                                            senderId = "u1",
                                            senderKind = MemberKind.Human,
                                            senderName = "You",
                                            content = "Thanks!",
                                        ),
                                    )
                                )
                            } else {
                                SendPromptDmPreviewResult.Loaded(emptyList())
                            }
                        },
                        onSend = { target, body ->
                            sent += target.id.value to body
                            true
                        },
                        onClose = { closeCalls++ },
                        onClosed = { outcome, edited, changedAgent ->
                            closedEvents += Triple(outcome, edited, changedAgent)
                        },
                        requestKey = "req-1",
                    )
                }
            }

            waitForIdle()
            onNode(hasTestTag(SendPromptAgentTriggerTestTag) and hasText("Cindy", substring = true)).assertIsDisplayed()
            onNode(hasTestTag(SendPromptAgentDescriptionTestTag) and hasText("Onboarding assistant"), useUnmergedTree = true).assertIsDisplayed()
            onNode(hasTestTag(SendPromptRecentNoticeTestTag) and hasText("Last 2 messages")).assertIsDisplayed()
            onNode(hasText("Welcome to Raft!")).assertIsDisplayed()
            onNode(hasText("Cancel")).assertDoesNotExist()
            onNode(hasContentDescription("Attach file")).assertDoesNotExist()
            onNode(hasContentDescription("Attach media")).assertDoesNotExist()
            onNode(hasText("As task")).assertDoesNotExist()

            val sendButton = hasContentDescription("Send") and hasAnyAncestor(hasTestTag(SendPromptComposerTestTag))
            onAllNodes(hasContentDescription("Send")).assertCountEquals(1)
            onNode(sendButton).assertIsEnabled()

            // Blank prompt disables Send
            onNode(hasSetTextAction()).performTextClearance()
            waitForIdle()
            onNode(sendButton).assertIsNotEnabled()

            // Edited prompt re-enables Send and submits trimmed text
            onNode(hasSetTextAction()).performTextReplacement("  Summarize this week  ")
            waitForIdle()
            onNode(sendButton).assertIsEnabled().performClick()
            waitForIdle()

            assertEquals(listOf("agent-cindy" to "Summarize this week"), sent)
            assertEquals(listOf(Triple("sent", true, false)), closedEvents)
            assertEquals(1, closeCalls)
        }

    @Test
    fun failedSendKeepsDraftShowsErrorBannerAndAllowsRetry() =
        runRobolectricComposeUiTest {
            val cindy = agent("agent-cindy", "Cindy", role = "admin")
            var attempts = 0
            var closeCalls = 0
            val closedEvents = mutableListOf<Triple<String, Boolean, Boolean>>()

            setContent {
                DialogTestShell {
                    SendPromptDialog(
                        prompt = "Help me set up",
                        serverId = "srv-1",
                        recipients = listOf(cindy),
                        officialOnboardingAgentId = "agent-cindy",
                        recentAgentIds = emptyList(),
                        dmChannelByAgent = emptyMap(),
                        loadPreview = { SendPromptDmPreviewResult.Loaded(emptyList()) },
                        onSend = { _, _ ->
                            attempts++
                            attempts > 1
                        },
                        onClose = { closeCalls++ },
                        onClosed = { outcome, edited, changedAgent ->
                            closedEvents += Triple(outcome, edited, changedAgent)
                        },
                        requestKey = "req-retry",
                    )
                }
            }

            waitForIdle()
            onNode(hasTestTag(SendPromptFirstMessageTestTag)).assertIsDisplayed()
            onNode(hasText("This will be your first message to this agent.")).assertIsDisplayed()

            onNode(hasContentDescription("Send")).performClick()
            waitForIdle()

            assertEquals(1, attempts)
            assertEquals(0, closeCalls)
            assertEquals(emptyList(), closedEvents)
            onNode(hasTestTag(SendPromptErrorBannerTestTag)).assertIsDisplayed()
            onNode(hasText("Couldn't send. Try again.")).assertIsDisplayed()
            onNode(hasSetTextAction() and hasText("Help me set up")).assertIsDisplayed()

            // Retry succeeds
            onNode(hasContentDescription("Send")).performClick()
            waitForIdle()
            assertTrue(attempts == 2)
            assertEquals(1, closeCalls)
        }

    @Test
    fun failedLookBackShowsLoadErrorInsteadOfFirstMessageCopy() =
        runRobolectricComposeUiTest {
            val cindy = agent("agent-cindy", "Cindy", role = "admin")
            setContent {
                DialogTestShell {
                    SendPromptDialog(
                        prompt = "Check status",
                        serverId = "srv-1",
                        recipients = listOf(cindy),
                        officialOnboardingAgentId = "agent-cindy",
                        recentAgentIds = emptyList(),
                        dmChannelByAgent = mapOf("agent-cindy" to ChannelId("dm-cindy")),
                        loadPreview = { SendPromptDmPreviewResult.Failed },
                        onSend = { _, _ -> true },
                        onClose = {},
                        requestKey = "req-failed-preview",
                    )
                }
            }

            waitForIdle()
            onNode(hasTestTag(SendPromptPreviewFailedTestTag)).assertIsDisplayed()
            onNode(hasText("Couldn't load your recent messages with this agent.")).assertIsDisplayed()
            onNode(hasText("This will be your first message to this agent.")).assertDoesNotExist()
        }

    @Test
    fun closeButtonIsIgnoredWhileSendIsInFlight() =
        runRobolectricComposeUiTest {
            val cindy = agent("agent-cindy", "Cindy", role = "admin")
            val sendGate = CompletableDeferred<Boolean>()
            var closeCalls = 0
            val closedEvents = mutableListOf<Triple<String, Boolean, Boolean>>()

            setContent {
                DialogTestShell {
                    SendPromptDialog(
                        prompt = "Run diagnostics",
                        serverId = "srv-1",
                        recipients = listOf(cindy),
                        officialOnboardingAgentId = "agent-cindy",
                        recentAgentIds = emptyList(),
                        dmChannelByAgent = emptyMap(),
                        loadPreview = { SendPromptDmPreviewResult.Loaded(emptyList()) },
                        onSend = { _, _ -> sendGate.await() },
                        onClose = { closeCalls++ },
                        onClosed = { outcome, edited, changedAgent ->
                            closedEvents += Triple(outcome, edited, changedAgent)
                        },
                        requestKey = "req-inflight",
                    )
                }
            }

            waitForIdle()
            onNode(hasContentDescription("Send")).performClick()
            waitForIdle()

            onNode(hasContentDescription("Close")).assertIsNotEnabled()
            onNode(hasTestTag(SendPromptCloseButtonTestTag)).performClick()
            waitForIdle()
            assertEquals(0, closeCalls)
            assertEquals(emptyList(), closedEvents)

            sendGate.complete(true)
            waitForIdle()

            assertEquals(1, closeCalls)
            assertEquals(listOf(Triple("sent", false, false)), closedEvents)
        }

    @Test
    fun switchingAgentUpdatesSelectionPreviewAndReopeningResetsEditedText() =
        runRobolectricComposeUiTest {
            val cindy = agent("agent-cindy", "Cindy", role = "admin")
            val alex = agent("agent-alex", "Alex", role = "member")
            val sent = mutableListOf<Pair<String, String>>()
            val closedEvents = mutableListOf<Triple<String, Boolean, Boolean>>()
            var requestKey by mutableStateOf("req-open-1")

            setContent {
                DialogTestShell {
                    SendPromptDialog(
                        prompt = "Check builds",
                        serverId = "srv-1",
                        recipients = listOf(cindy, alex),
                        officialOnboardingAgentId = "agent-cindy",
                        recentAgentIds = emptyList(),
                        dmChannelByAgent = mapOf("agent-cindy" to ChannelId("dm-cindy")),
                        loadPreview = { channelId ->
                            if (channelId == ChannelId("dm-cindy")) {
                                SendPromptDmPreviewResult.Loaded(
                                    listOf(
                                        SendPromptPreviewMessage(
                                            id = "m1",
                                            senderId = "agent-cindy",
                                            senderKind = MemberKind.Agent,
                                            senderName = "Cindy",
                                            content = "Line 1\nLine 2\nLine 3\nLine 4",
                                        )
                                    )
                                )
                            } else {
                                SendPromptDmPreviewResult.Loaded(emptyList())
                            }
                        },
                        onSend = { target, body ->
                            sent += target.id.value to body
                            true
                        },
                        onClose = {},
                        onClosed = { outcome, edited, changedAgent ->
                            closedEvents += Triple(outcome, edited, changedAgent)
                        },
                        requestKey = requestKey,
                    )
                }
            }

            waitForIdle()
            onNode(hasTestTag(SendPromptRecentNoticeTestTag) and hasText("Last message")).assertIsDisplayed()
            onNode(hasTestTag(SendPromptShowMoreTestTag) and hasText("Show more")).assertIsDisplayed().performClick()
            waitForIdle()
            onNode(hasTestTag(SendPromptShowMoreTestTag) and hasText("Show less")).assertIsDisplayed()

            // Edit text, then simulate reopen with new requestKey: edit is not kept
            onNode(hasSetTextAction()).performTextReplacement("Edited prompt")
            waitForIdle()
            requestKey = "req-open-2"
            waitForIdle()
            onNode(hasSetTextAction() and hasText("Check builds")).assertIsDisplayed()

            // Switch agent to Alex and send
            onNode(hasTestTag(SendPromptAgentTriggerTestTag)).performClick()
            waitForIdle()
            onNode(hasTestTag("send-prompt-agent-agent-alex")).assertIsDisplayed().performClick()
            waitForIdle()

            onNode(hasTestTag(SendPromptAgentTriggerTestTag) and hasText("Alex", substring = true)).assertIsDisplayed()
            onNode(hasText("This will be your first message to this agent.")).assertIsDisplayed()

            onNode(hasContentDescription("Send")).performClick()
            waitForIdle()

            assertEquals(listOf("agent-alex" to "Check builds"), sent)
            assertEquals(listOf(Triple("sent", false, true)), closedEvents)
        }

    @Test
    fun suggestedAgentLeavingFallsBackToNextAgentAndKeepsComposer() =
        runRobolectricComposeUiTest {
            val cindy = agent("agent-cindy", "Cindy", role = "admin")
            val atlas = agent("agent-atlas", "Atlas", role = "member")
            var roster by mutableStateOf(listOf(cindy, atlas))

            setContent {
                DialogTestShell {
                    SendPromptDialog(
                        prompt = "Audit logs",
                        serverId = "srv-1",
                        recipients = roster,
                        officialOnboardingAgentId = "agent-cindy",
                        recentAgentIds = emptyList(),
                        dmChannelByAgent = emptyMap(),
                        loadPreview = { SendPromptDmPreviewResult.Loaded(emptyList()) },
                        onSend = { _, _ -> true },
                        onClose = {},
                        requestKey = "req-fallback",
                    )
                }
            }

            waitForIdle()
            onNode(hasTestTag(SendPromptAgentTriggerTestTag) and hasText("Cindy", substring = true)).assertIsDisplayed()

            roster = listOf(atlas)
            waitForIdle()
            onNode(hasTestTag(SendPromptAgentTriggerTestTag) and hasText("Atlas", substring = true)).assertIsDisplayed()
            onNode(hasSetTextAction() and hasText("Audit logs")).assertIsDisplayed()
        }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi-keysexposed-qwerty")
    fun modEnterAccountHidesSendKeyHintInsideSendPromptDialogComposer() =
        runRobolectricComposeUiTest {
            val previousSendKey = RaftComposerSendKeyRuntime.sendKey
            try {
                RaftComposerSendKeyRuntime.setSendKey(RaftComposerSendKey.ModEnter)
                val cindy = agent("agent-cindy", "Cindy", role = "admin")

                setContent {
                    DialogTestShell {
                        SendPromptDialog(
                            prompt = "Summarize today's changes",
                            serverId = "srv-1",
                            recipients = listOf(cindy),
                            officialOnboardingAgentId = "agent-cindy",
                            recentAgentIds = emptyList(),
                            dmChannelByAgent = emptyMap(),
                            loadPreview = { SendPromptDmPreviewResult.Loaded(emptyList()) },
                            onSend = { _, _ -> true },
                            onClose = {},
                            requestKey = "req-mod-enter",
                        )
                    }
                }

                waitForIdle()
                onNode(hasSetTextAction() and hasText("Summarize today's changes")).assertIsDisplayed()
                onNode(hasTestTag(ThreadComposerSendKeyHintTestTag), useUnmergedTree = true).assertDoesNotExist()
                onNode(hasContentDescription("Send") and hasAnyAncestor(hasTestTag(SendPromptComposerTestTag))).assertIsEnabled()
            } finally {
                RaftComposerSendKeyRuntime.setSendKey(previousSendKey)
            }
        }
}
