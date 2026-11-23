package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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

    private fun agent(id: String, name: String, role: String? = "member"): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = id,
            kind = MemberKind.Agent,
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
    fun emptyStateShowsNoAgentsMessage() =
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
                        loadPreview = { emptyList() },
                        onSend = { _, _ -> true },
                        onClose = {},
                    )
                }
            }

            waitForIdle()
            onNode(hasTestTag(SendPromptNoAgentsTestTag)).assertIsDisplayed()
            onNode(hasText("There are no agents on this server you can message yet.")).assertIsDisplayed()
        }

    @Test
    fun suggestsOfficialAgentShowsPreviewEditsAndSends() =
        runRobolectricComposeUiTest {
            val cindy = agent("agent-cindy", "Cindy", role = "admin")
            val alex = agent("agent-alex", "Alex", role = "member")
            val sent = mutableListOf<Pair<String, String>>()
            val closedEvents = mutableListOf<Triple<String, Boolean, Boolean>>()

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
                                listOf(
                                    SendPromptPreviewMessage(
                                        id = "m1",
                                        senderName = "Cindy",
                                        content = "Welcome to Raft!",
                                    )
                                )
                            } else {
                                emptyList()
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
                    )
                }
            }

            waitForIdle()
            onNode(hasTestTag(SendPromptAgentTriggerTestTag) and hasText("Cindy", substring = true)).assertIsDisplayed()
            onNode(hasText("Cindy: Welcome to Raft!")).assertIsDisplayed()
            onNode(hasText("Send")).assertIsEnabled()

            // Blank prompt disables Send
            onNode(hasSetTextAction()).performTextClearance()
            waitForIdle()
            onNode(hasText("Send")).assertIsNotEnabled()

            // Edited prompt re-enables Send and submits trimmed text
            onNode(hasSetTextAction()).performTextReplacement("  Summarize this week  ")
            waitForIdle()
            onNode(hasText("Send")).assertIsEnabled().performClick()
            waitForIdle()

            assertEquals(listOf("agent-cindy" to "Summarize this week"), sent)
            assertEquals(listOf(Triple("sent", true, false)), closedEvents)
        }

    @Test
    fun failedSendShowsInlineErrorBannerAndAllowsRetry() =
        runRobolectricComposeUiTest {
            val cindy = agent("agent-cindy", "Cindy", role = "admin")
            var attempts = 0

            setContent {
                DialogTestShell {
                    SendPromptDialog(
                        prompt = "Help me set up",
                        serverId = "srv-1",
                        recipients = listOf(cindy),
                        officialOnboardingAgentId = "agent-cindy",
                        recentAgentIds = emptyList(),
                        dmChannelByAgent = emptyMap(),
                        loadPreview = { emptyList() },
                        onSend = { _, _ ->
                            attempts++
                            attempts > 1
                        },
                        onClose = {},
                    )
                }
            }

            waitForIdle()
            onNode(hasText("This will be your first message to this agent.")).assertIsDisplayed()

            onNode(hasText("Send")).performClick()
            waitForIdle()

            assertEquals(1, attempts)
            onNode(hasTestTag(SendPromptErrorBannerTestTag)).assertIsDisplayed()
            onNode(hasText("Couldn't send. Try again.")).assertIsDisplayed()

            // Retry succeeds
            onNode(hasText("Send")).performClick()
            waitForIdle()
            assertTrue(attempts == 2)
        }

    @Test
    fun switchingAgentUpdatesSelectionAndPreview() =
        runRobolectricComposeUiTest {
            val cindy = agent("agent-cindy", "Cindy", role = "admin")
            val alex = agent("agent-alex", "Alex", role = "member")
            val sent = mutableListOf<Pair<String, String>>()
            val closedEvents = mutableListOf<Triple<String, Boolean, Boolean>>()

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
                                listOf(SendPromptPreviewMessage("m1", "Cindy", "Existing message"))
                            } else {
                                emptyList()
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
                    )
                }
            }

            waitForIdle()
            onNode(hasText("Cindy: Existing message")).assertIsDisplayed()

            // Open picker and choose Alex (shows role label & switches preview to first-message hint)
            onNode(hasTestTag(SendPromptAgentTriggerTestTag)).performClick()
            waitForIdle()
            onNode(hasTestTag("send-prompt-agent-agent-alex")).assertIsDisplayed().performClick()
            waitForIdle()

            onNode(hasTestTag(SendPromptAgentTriggerTestTag) and hasText("Alex", substring = true)).assertIsDisplayed()
            onNode(hasText("This will be your first message to this agent.")).assertIsDisplayed()

            onNode(hasText("Send")).performClick()
            waitForIdle()

            assertEquals(listOf("agent-alex" to "Check builds"), sent)
            assertEquals(listOf(Triple("sent", false, true)), closedEvents)
        }
}
