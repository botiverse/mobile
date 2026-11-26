package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.agents.AgentVoiceCallCard
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentVoiceCallEndReason
import build.raft.app.data.AgentVoiceCallPhase
import build.raft.app.data.AgentVoiceCallProblem
import build.raft.app.data.AgentVoiceCallState
import build.raft.app.data.AgentVoiceCallee
import build.raft.app.data.AgentVoiceCaption
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class AgentVoiceCallCardUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setup() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    private val testAgent = AgentVoiceCallee(id = "agent-lagoon", name = "Lagoon")

    @Test
    fun connectingStateShowsConnectingStatusAndHangUpOnly() =
        runRobolectricComposeUiTest {
            var hangUps = 0
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        AgentVoiceCallCard(
                            call =
                                AgentVoiceCallState(
                                    agent = testAgent,
                                    serverId = "srv-1",
                                    phase = AgentVoiceCallPhase.Connecting,
                                ),
                            nowEpochMs = 10_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = { hangUps += 1 },
                            onClose = {},
                            onRetry = {},
                        )
                    }
                }
            }

            onNodeWithTag("agent-voice-call-window").assertIsDisplayed()
            onNodeWithText("Lagoon").assertIsDisplayed()
            onNodeWithText("Connecting…").assertIsDisplayed()
            onNodeWithTag("agent-voice-call-mute").assertDoesNotExist()
            onNodeWithTag("agent-voice-call-sound").assertDoesNotExist()
            onNodeWithContentDescription("Hang up").assertIsDisplayed().performClick()
            assertEquals(1, hangUps)
        }

    @Test
    fun liveStateRendersSpeakingRingClockCaptionsAndControlCallbacks() =
        runRobolectricComposeUiTest {
            var mutes = 0
            var speakers = 0
            var hangUps = 0
            val call =
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    callId = "123e4567-e89b-12d3-a456-426614174000",
                    phase = AgentVoiceCallPhase.Live,
                    connectedAtEpochMs = 1_000_000L,
                    agentSpeaking = true,
                    captions =
                        listOf(
                            AgentVoiceCaption(id = "c1", mine = true, text = "Can you check CI?", final = true),
                            AgentVoiceCaption(id = "c2", mine = false, text = "All checks are green.", final = false),
                        ),
                )
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        AgentVoiceCallCard(
                            call = call,
                            nowEpochMs = 1_065_000L,
                            onToggleMute = { mutes += 1 },
                            onToggleSpeaker = { speakers += 1 },
                            onHangUp = { hangUps += 1 },
                            onClose = {},
                            onRetry = {},
                        )
                    }
                }
            }

            onNodeWithTag("agent-voice-call-speaking-ring").assertIsDisplayed()
            onNodeWithText("1:05").assertIsDisplayed()
            onNodeWithTag("agent-voice-call-captions").assertIsDisplayed()
            onNodeWithText("You: Can you check CI?").assertIsDisplayed()
            onNodeWithText("Lagoon: All checks are green.").assertIsDisplayed()

            onNodeWithContentDescription("Mute").performClick()
            onNodeWithContentDescription("Sound off").performClick()
            onNodeWithContentDescription("Hang up").performClick()
            assertEquals(1, mutes)
            assertEquals(1, speakers)
            assertEquals(1, hangUps)
        }

    @Test
    fun liveMicWarningBannerShowsAndDisablesMuteControl() =
        runRobolectricComposeUiTest {
            val call =
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    phase = AgentVoiceCallPhase.Live,
                    connectedAtEpochMs = 1_000_000L,
                    micBlockedReason = "busy",
                )
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        AgentVoiceCallCard(
                            call = call,
                            nowEpochMs = 1_010_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = {},
                            onClose = {},
                            onRetry = {},
                        )
                    }
                }
            }

            onNodeWithTag("agent-voice-call-mic").assertIsDisplayed()
            onNodeWithText("Another app is using your microphone, so Lagoon can't hear you. Close it to keep talking.").assertIsDisplayed()
            onNodeWithContentDescription("Microphone unavailable").assertIsNotEnabled()
        }

    @Test
    fun endedStateWithLimitShowsDurationLimitNoticeDmRecordAndClose() =
        runRobolectricComposeUiTest {
            var closes = 0
            val call =
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    phase = AgentVoiceCallPhase.Ended,
                    connectedAtEpochMs = 1_000_000L,
                    durationMs = 15 * 60 * 1_000L,
                    endReason = AgentVoiceCallEndReason.Closed,
                    limitReached = true,
                )
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        AgentVoiceCallCard(
                            call = call,
                            nowEpochMs = 1_900_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = {},
                            onClose = { closes += 1 },
                            onRetry = {},
                        )
                    }
                }
            }

            onNodeWithText("Call ended · 15:00").assertIsDisplayed()
            onNodeWithText("Calls end after 15 minutes.").assertIsDisplayed()
            onNodeWithText("The call's record is in your DM with Lagoon.").assertIsDisplayed()
            onNodeWithTag("agent-voice-call-retry").assertDoesNotExist()
            onNodeWithTag("agent-voice-call-close").assertIsDisplayed().performClick()
            assertEquals(1, closes)
        }

    @Test
    fun failedStateShowsTypedProblemAndAllowsRetryAndClose() =
        runRobolectricComposeUiTest {
            var retries = 0
            var closes = 0
            val call =
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    phase = AgentVoiceCallPhase.Failed,
                    problem = AgentVoiceCallProblem.AgentOffline,
                )
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        AgentVoiceCallCard(
                            call = call,
                            nowEpochMs = 10_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = {},
                            onClose = { closes += 1 },
                            onRetry = { retries += 1 },
                        )
                    }
                }
            }

            onNodeWithText("Call didn't go through").assertIsDisplayed()
            onNodeWithText("Lagoon isn't reachable right now. Send it a message instead.").assertIsDisplayed()
            onNodeWithTag("agent-voice-call-retry").assertIsDisplayed().performClick()
            onNodeWithTag("agent-voice-call-close").assertIsDisplayed().performClick()
            assertEquals(1, retries)
            assertEquals(1, closes)
        }
}
