package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.agents.AgentVoiceCallCard
import build.raft.app.agents.AgentVoiceCallDraggableOverlay
import build.raft.app.agents.AgentVoiceCallFloatingPositionHolder
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
import build.raft.app.shell.RaftHorizontalSafeArea
import kotlin.math.abs
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

    @Test
    fun draggableOverlayRepositionsOnVerticalDragAndClampsInsideSafeArea() =
        runRobolectricComposeUiTest {
            val positionHolder = AgentVoiceCallFloatingPositionHolder()
            val call =
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    callId = "123e4567-e89b-12d3-a456-426614174000",
                    phase = AgentVoiceCallPhase.Live,
                    connectedAtEpochMs = 1_000_000L,
                )
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().testTag("voice-overlay-host")) {
                        AgentVoiceCallDraggableOverlay(
                            call = call,
                            nowEpochMs = 1_015_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = {},
                            onClose = {},
                            onRetry = {},
                            bottomClearance = 34.dp,
                            topSafeArea = 44.dp,
                            horizontalSafeArea = RaftHorizontalSafeArea.Zero,
                            positionHolder = positionHolder,
                        )
                    }
                }
            }

            onNodeWithTag("agent-voice-call-drag-handle").assertIsDisplayed()
            val hostBounds = onNodeWithTag("voice-overlay-host").getBoundsInRoot()
            val initialBounds = onNodeWithTag("agent-voice-call-window").getBoundsInRoot()
            val expectedDefaultBottom = hostBounds.bottom.value - 34f - 16f
            assertTrue(
                abs(initialBounds.bottom.value - expectedDefaultBottom) <= 2f,
                "Expected default card bottom near ${expectedDefaultBottom}dp, got ${initialBounds.bottom}",
            )

            val stepPx = 10f * density.density
            val grabOffsetY = 20f * density.density
            onNodeWithTag("agent-voice-call-window").performTouchInput {
                down(Offset(centerX, top + grabOffsetY))
                repeat(30) { moveBy(Offset(0f, -stepPx)) }
                up()
            }
            waitForIdle()

            val draggedBounds = onNodeWithTag("agent-voice-call-window").getBoundsInRoot()
            val expectedTopLimit = hostBounds.top.value + 44f + 12f
            assertTrue(
                draggedBounds.top < initialBounds.top - 250.dp,
                "Expected card to move up after vertical drag: before=${initialBounds.top}, after=${draggedBounds.top}",
            )
            assertTrue(
                draggedBounds.top.value >= expectedTopLimit - 1f,
                "Expected dragged card to stay below top safe area + gap (${expectedTopLimit}dp): top=${draggedBounds.top}",
            )

            onNodeWithTag("agent-voice-call-window").performTouchInput {
                down(Offset(centerX, top + grabOffsetY))
                repeat(80) { moveBy(Offset(0f, -stepPx)) }
                up()
            }
            waitForIdle()

            val clampedTopBounds = onNodeWithTag("agent-voice-call-window").getBoundsInRoot()
            assertTrue(
                abs(clampedTopBounds.top.value - expectedTopLimit) <= 2f,
                "Expected card top clamped to ${expectedTopLimit}dp (44dp status bar + 12dp gap), got ${clampedTopBounds.top}",
            )
        }

    @Test
    fun draggableOverlayPreservesPositionAcrossRecompositionAndKeepsControlsClickable() =
        runRobolectricComposeUiTest {
            val positionHolder = AgentVoiceCallFloatingPositionHolder()
            var mutes = 0
            var speakers = 0
            var hangUps = 0
            var callState by mutableStateOf(
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    callId = "123e4567-e89b-12d3-a456-426614174000",
                    phase = AgentVoiceCallPhase.Live,
                    connectedAtEpochMs = 1_000_000L,
                ),
            )
            var nowMs by mutableStateOf(1_010_000L)

            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        AgentVoiceCallDraggableOverlay(
                            call = callState,
                            nowEpochMs = nowMs,
                            onToggleMute = { mutes += 1 },
                            onToggleSpeaker = { speakers += 1 },
                            onHangUp = { hangUps += 1 },
                            onClose = {},
                            onRetry = {},
                            bottomClearance = 34.dp,
                            topSafeArea = 44.dp,
                            horizontalSafeArea = RaftHorizontalSafeArea.Zero,
                            positionHolder = positionHolder,
                        )
                    }
                }
            }

            val stepPx = 10f * density.density
            val grabOffsetY = 20f * density.density
            onNodeWithTag("agent-voice-call-window").performTouchInput {
                down(Offset(centerX, top + grabOffsetY))
                repeat(24) { moveBy(Offset(0f, -stepPx)) }
                up()
            }
            waitForIdle()

            val draggedBounds = onNodeWithTag("agent-voice-call-window").getBoundsInRoot()

            runOnIdle {
                callState =
                    callState.copy(
                        agentSpeaking = true,
                        captions =
                            listOf(
                                AgentVoiceCaption(id = "c1", mine = true, text = "Moved out of the way", final = true),
                                AgentVoiceCaption(id = "c2", mine = false, text = "Looks much cleaner now.", final = false),
                            ),
                    )
                nowMs = 1_065_000L
            }
            waitForIdle()

            onNodeWithText("Lagoon: Looks much cleaner now.").assertIsDisplayed()
            val afterRecomposeBounds = onNodeWithTag("agent-voice-call-window").getBoundsInRoot()
            assertTrue(
                abs(afterRecomposeBounds.top.value - draggedBounds.top.value) <= 2f,
                "Expected card to keep dragged top=${draggedBounds.top} across live updates, got ${afterRecomposeBounds.top}",
            )

            onNodeWithContentDescription("Mute").performClick()
            onNodeWithContentDescription("Sound off").performClick()
            onNodeWithContentDescription("Hang up").performClick()
            assertEquals(1, mutes)
            assertEquals(1, speakers)
            assertEquals(1, hangUps)
        }

    @Test
    @Config(sdk = [34], qualifiers = "w1024dp-h768dp")
    fun draggableOverlaySupportsTwoDimensionalDragOnWideViewport() =
        runRobolectricComposeUiTest {
            val positionHolder = AgentVoiceCallFloatingPositionHolder()
            val call =
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    callId = "123e4567-e89b-12d3-a456-426614174000",
                    phase = AgentVoiceCallPhase.Live,
                    connectedAtEpochMs = 1_000_000L,
                )
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().testTag("voice-overlay-host")) {
                        AgentVoiceCallDraggableOverlay(
                            call = call,
                            nowEpochMs = 1_020_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = {},
                            onClose = {},
                            onRetry = {},
                            bottomClearance = 16.dp,
                            topSafeArea = 24.dp,
                            horizontalSafeArea = RaftHorizontalSafeArea.Zero,
                            positionHolder = positionHolder,
                        )
                    }
                }
            }

            val hostBounds = onNodeWithTag("voice-overlay-host").getBoundsInRoot()
            val initialBounds = onNodeWithTag("agent-voice-call-window").getBoundsInRoot()
            val expectedDefaultRight = hostBounds.right.value - 12f
            assertTrue(
                abs(initialBounds.right.value - expectedDefaultRight) <= 2f,
                "Expected wide default placement near bottom-end (${expectedDefaultRight}dp): right=${initialBounds.right}",
            )

            val stepPx = 10f * density.density
            val grabOffsetY = 20f * density.density
            onNodeWithTag("agent-voice-call-window").performTouchInput {
                down(Offset(centerX, top + grabOffsetY))
                repeat(30) { moveBy(Offset(-stepPx, -stepPx)) }
                up()
            }
            waitForIdle()

            val draggedBounds = onNodeWithTag("agent-voice-call-window").getBoundsInRoot()
            assertTrue(
                draggedBounds.left < initialBounds.left - 250.dp,
                "Expected horizontal movement on wide screen: before=${initialBounds.left}, after=${draggedBounds.left}",
            )
            assertTrue(
                draggedBounds.top < initialBounds.top - 250.dp,
                "Expected vertical movement on wide screen: before=${initialBounds.top}, after=${draggedBounds.top}",
            )

            onNodeWithTag("agent-voice-call-window").performTouchInput {
                down(Offset(centerX, top + grabOffsetY))
                repeat(80) { moveBy(Offset(-stepPx, -stepPx)) }
                up()
            }
            waitForIdle()

            val clampedBounds = onNodeWithTag("agent-voice-call-window").getBoundsInRoot()
            val expectedLeftLimit = hostBounds.left.value + 12f
            val expectedTopLimit = hostBounds.top.value + 24f + 12f
            assertTrue(
                abs(clampedBounds.left.value - expectedLeftLimit) <= 2f,
                "Expected wide card left clamped to ${expectedLeftLimit}dp horizontal inset, got ${clampedBounds.left}",
            )
            assertTrue(
                abs(clampedBounds.top.value - expectedTopLimit) <= 2f,
                "Expected wide card top clamped to ${expectedTopLimit}dp (24dp safe area + 12dp gap), got ${clampedBounds.top}",
            )
        }
}
