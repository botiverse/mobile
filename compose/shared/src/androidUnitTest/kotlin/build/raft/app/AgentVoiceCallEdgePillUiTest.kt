@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.agents.AgentVoiceCallCollapseButtonTestTag
import build.raft.app.agents.AgentVoiceCallDraggableOverlay
import build.raft.app.agents.AgentVoiceCallEdgePillTestTag
import build.raft.app.agents.AgentVoiceCallFeedbackGoodTestTag
import build.raft.app.agents.AgentVoiceCallFeedbackTestTag
import build.raft.app.agents.AgentVoiceCallFloatingPositionHolder
import build.raft.app.agents.AgentVoiceCallFloatingStateHolder
import build.raft.app.agents.AgentVoiceCallPillClockTestTag
import build.raft.app.agents.AgentVoiceCallPillMutedTestTag
import build.raft.app.agents.AgentVoiceCallPillSpeakingRingTestTag
import build.raft.app.agents.AgentVoiceCallPillStatusDotTestTag
import build.raft.app.agents.AgentVoiceCallPresentationMode
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.RaftHostImeInsets
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentVoiceCallEndReason
import build.raft.app.data.AgentVoiceCallPhase
import build.raft.app.data.AgentVoiceCallProblem
import build.raft.app.data.AgentVoiceCallState
import build.raft.app.data.AgentVoiceCallee
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.RaftTelemetryPrivacy
import build.raft.app.data.VoiceFeedbackSubmitOutcome
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.shell.RaftHorizontalSafeArea
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import kotlin.math.abs
import kotlin.math.roundToInt
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
class AgentVoiceCallEdgePillUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        RaftTelemetryPrivacy.publish(RaftPrivacyMode.Unknown)
    }

    @AfterTest
    fun tearDown() {
        RaftTelemetryPrivacy.publish(RaftPrivacyMode.Unknown)
        StringDesc.localeType = previousLocale
    }

    private val testAgent = AgentVoiceCallee(id = "agent-lagoon", name = "Lagoon")

    @Test
    fun collapseButtonSwitchesToEdgePillWithLiveStatusAndSingleTapExpandsBackToCard() =
        runRobolectricComposeUiTest {
            val positionHolder = AgentVoiceCallFloatingPositionHolder()
            val floatingStateHolder = AgentVoiceCallFloatingStateHolder()
            var call by mutableStateOf(
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    callId = "call-1",
                    phase = AgentVoiceCallPhase.Live,
                    connectedAtEpochMs = 1_000_000L,
                    muted = true,
                    agentSpeaking = true,
                ),
            )

            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        AgentVoiceCallDraggableOverlay(
                            call = call,
                            nowEpochMs = 1_065_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = {},
                            onClose = {},
                            onRetry = {},
                            bottomClearance = 34.dp,
                            topSafeArea = 44.dp,
                            horizontalSafeArea = RaftHorizontalSafeArea.Zero,
                            positionHolder = positionHolder,
                            floatingStateHolder = floatingStateHolder,
                        )
                    }
                }
            }

            onNodeWithTag("agent-voice-call-window").assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertDoesNotExist()
            onNodeWithContentDescription("Collapse to floating pill").assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallCollapseButtonTestTag).performClick()
            waitForIdle()

            // Card collapses cleanly into the edge pill showing timer, speaking ring, status dot, and muted icon.
            assertEquals<AgentVoiceCallPresentationMode>(
                AgentVoiceCallPresentationMode.EdgePill,
                floatingStateHolder.state.value.mode,
            )
            onNodeWithTag("agent-voice-call-window").assertDoesNotExist()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertIsDisplayed()
            onNodeWithContentDescription("Expand call with Lagoon (1:05)").assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallPillClockTestTag, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("1:05", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallPillStatusDotTestTag, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallPillMutedTestTag, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallPillSpeakingRingTestTag, useUnmergedTree = true).assertIsDisplayed()

            // Unmuting hides the muted indicator while staying collapsed.
            runOnIdle { call = call.copy(muted = false, agentSpeaking = false) }
            waitForIdle()
            onNodeWithTag(AgentVoiceCallPillMutedTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(AgentVoiceCallPillSpeakingRingTestTag, useUnmergedTree = true).assertDoesNotExist()

            // Single tap on the edge pill expands smoothly back to the full interactive call card.
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).performClick()
            waitForIdle()
            assertEquals<AgentVoiceCallPresentationMode>(
                AgentVoiceCallPresentationMode.Card,
                floatingStateHolder.state.value.mode,
            )
            onNodeWithTag("agent-voice-call-window").assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertDoesNotExist()
        }

    @Test
    fun touchPassThroughKeepsUnderlyingControlsOutsidePillAndCardClickable() =
        runRobolectricComposeUiTest {
            val positionHolder = AgentVoiceCallFloatingPositionHolder()
            val floatingStateHolder = AgentVoiceCallFloatingStateHolder()
            var bottomButtonClicks = 0
            var topButtonClicks = 0
            val call =
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    callId = "call-1",
                    phase = AgentVoiceCallPhase.Live,
                    connectedAtEpochMs = 1_000_000L,
                )

            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        RaftButton(
                            text = "Top underlying",
                            onClick = { topButtonClicks += 1 },
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                            modifier =
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = 16.dp, top = 64.dp)
                                    .testTag("underlying-top-button"),
                        )
                        RaftButton(
                            text = "Bottom underlying",
                            onClick = { bottomButtonClicks += 1 },
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 16.dp, bottom = 64.dp)
                                    .testTag("underlying-bottom-button"),
                        )
                        AgentVoiceCallDraggableOverlay(
                            call = call,
                            nowEpochMs = 1_012_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = {},
                            onClose = {},
                            onRetry = {},
                            bottomClearance = 34.dp,
                            topSafeArea = 44.dp,
                            horizontalSafeArea = RaftHorizontalSafeArea.Zero,
                            positionHolder = positionHolder,
                            floatingStateHolder = floatingStateHolder,
                        )
                    }
                }
            }

            // While expanded at bottom-center, underlying button at top-start stays clickable.
            onNodeWithTag("underlying-top-button").performClick()
            assertEquals<Int>(1, topButtonClicks)

            // Collapse into upper-right edge pill -> underlying button at bottom-start stays clickable.
            onNodeWithTag(AgentVoiceCallCollapseButtonTestTag).performClick()
            waitForIdle()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertIsDisplayed()
            onNodeWithTag("underlying-bottom-button").performClick()
            assertEquals<Int>(1, bottomButtonClicks)
        }

    @Test
    fun edgePillDragsBilaterallyAcrossEdgesClampsImeAndPreservesCardFloatingPosition() =
        runRobolectricComposeUiTest {
            val positionHolder = AgentVoiceCallFloatingPositionHolder()
            val floatingStateHolder = AgentVoiceCallFloatingStateHolder()
            val hostIme = RaftHostImeInsets()
            val call =
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    callId = "call-1",
                    phase = AgentVoiceCallPhase.Live,
                    connectedAtEpochMs = 1_000_000L,
                )

            setContent {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalRaftHostImeInsets provides hostIme) {
                        Box(Modifier.fillMaxSize().testTag("voice-call-pill-host")) {
                            AgentVoiceCallDraggableOverlay(
                                call = call,
                                nowEpochMs = 1_020_000L,
                                onToggleMute = {},
                                onToggleSpeaker = {},
                                onHangUp = {},
                                onClose = {},
                                onRetry = {},
                                bottomClearance = 34.dp,
                                topSafeArea = 44.dp,
                                horizontalSafeArea = RaftHorizontalSafeArea.Zero,
                                positionHolder = positionHolder,
                                floatingStateHolder = floatingStateHolder,
                            )
                        }
                    }
                }
            }

            val hostBounds = onNodeWithTag("voice-call-pill-host").getBoundsInRoot()
            val stepPx = 10f * density.density
            val grabOffsetY = 20f * density.density

            // Drag the expanded card upward first and record its persisted position.
            onNodeWithTag("agent-voice-call-window").performTouchInput {
                down(Offset(centerX, top + grabOffsetY))
                repeat(24) { moveBy(Offset(0f, -stepPx)) }
                up()
            }
            waitForIdle()
            val draggedCardBounds = onNodeWithTag("agent-voice-call-window").getBoundsInRoot()

            // Collapse -> pill docks flush to physical Right edge.
            onNodeWithTag(AgentVoiceCallCollapseButtonTestTag).performClick()
            waitForIdle()
            val initialPill = onNodeWithTag(AgentVoiceCallEdgePillTestTag).getBoundsInRoot()
            assertTrue(
                abs(initialPill.right.value - hostBounds.right.value) <= 2f,
                "Expected pill initially docked flush to right edge (${hostBounds.right}), got right=${initialPill.right}",
            )

            // Drag horizontally past viewport midpoint -> snaps magnetically to physical Left edge.
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).performTouchInput {
                down(center)
                repeat(30) { moveBy(Offset(-stepPx, 0f)) }
                up()
            }
            waitForIdle()
            assertEquals<RaftFloatingPillEdge>(RaftFloatingPillEdge.Left, floatingStateHolder.state.value.pillEdge)
            val dockedLeft = onNodeWithTag(AgentVoiceCallEdgePillTestTag).getBoundsInRoot()
            assertTrue(
                abs(dockedLeft.left.value - hostBounds.left.value) <= 2f,
                "Expected pill docked flush to left edge (${hostBounds.left}), got left=${dockedLeft.left}",
            )

            // Short rightward pull without crossing midpoint snaps back to Left edge.
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).performTouchInput {
                down(center)
                repeat(6) { moveBy(Offset(stepPx, 0f)) }
                up()
            }
            waitForIdle()
            assertEquals<RaftFloatingPillEdge>(RaftFloatingPillEdge.Left, floatingStateHolder.state.value.pillEdge)

            // Drag past midpoint back to Right -> snaps flush to physical Right edge.
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).performTouchInput {
                down(center)
                repeat(30) { moveBy(Offset(stepPx, 0f)) }
                up()
            }
            waitForIdle()
            assertEquals<RaftFloatingPillEdge>(RaftFloatingPillEdge.Right, floatingStateHolder.state.value.pillEdge)
            val dockedRight = onNodeWithTag(AgentVoiceCallEdgePillTestTag).getBoundsInRoot()
            assertTrue(
                abs(dockedRight.right.value - hostBounds.right.value) <= 2f,
                "Expected pill docked flush back to right edge (${hostBounds.right}), got right=${dockedRight.right}",
            )

            // Drag pill to bottom, then open live IME -> placement-only layout lifts pill above keyboard.
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).performTouchInput {
                down(center)
                repeat(60) { moveBy(Offset(0f, stepPx)) }
                up()
            }
            waitForIdle()
            val atBottomNoIme = onNodeWithTag(AgentVoiceCallEdgePillTestTag).getBoundsInRoot()

            runOnIdle { hostIme.imeBottomPx = (300f * density.density).roundToInt() }
            waitForIdle()
            val liftedAboveIme = onNodeWithTag(AgentVoiceCallEdgePillTestTag).getBoundsInRoot()
            assertTrue(
                liftedAboveIme.bottom < atBottomNoIme.bottom - 200.dp,
                "Expected live IME inset to lift voice call pill: noIme=${atBottomNoIme.bottom}, ime=${liftedAboveIme.bottom}",
            )

            // Close IME and tap pill -> expands back to card at its preserved dragged floating position.
            runOnIdle { hostIme.imeBottomPx = 0 }
            waitForIdle()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).performClick()
            waitForIdle()

            val expandedCardBounds = onNodeWithTag("agent-voice-call-window").getBoundsInRoot()
            assertTrue(
                abs(expandedCardBounds.top.value - draggedCardBounds.top.value) <= 2f,
                "Expected expanded card to preserve prior dragged top=${draggedCardBounds.top}, got ${expandedCardBounds.top}",
            )
        }

    @Test
    fun endedTransitionAutoExpandsCollapsedPillSoPostCallFeedbackRatingIsVisible() =
        runRobolectricComposeUiTest {
            RaftTelemetryPrivacy.publish(RaftPrivacyMode.Off)
            val positionHolder = AgentVoiceCallFloatingPositionHolder()
            val floatingStateHolder = AgentVoiceCallFloatingStateHolder()
            var call by mutableStateOf(
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    callId = "123e4567-e89b-12d3-a456-426614174000",
                    phase = AgentVoiceCallPhase.Live,
                    connectedAtEpochMs = 1_000_000L,
                ),
            )

            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        AgentVoiceCallDraggableOverlay(
                            call = call,
                            nowEpochMs = 1_045_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = {},
                            onClose = {},
                            onRetry = {},
                            bottomClearance = 34.dp,
                            topSafeArea = 44.dp,
                            horizontalSafeArea = RaftHorizontalSafeArea.Zero,
                            positionHolder = positionHolder,
                            floatingStateHolder = floatingStateHolder,
                            onSubmitFeedback = { VoiceFeedbackSubmitOutcome.Submitted },
                        )
                    }
                }
            }

            // Collapse active call to edge pill.
            onNodeWithTag(AgentVoiceCallCollapseButtonTestTag).performClick()
            waitForIdle()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertIsDisplayed()
            onNodeWithTag("agent-voice-call-window").assertDoesNotExist()

            // Transition call to Ended -> auto-expands immediately to card with post-call 1-tap feedback visible and collapse button hidden.
            runOnIdle {
                call =
                    call.copy(
                        phase = AgentVoiceCallPhase.Ended,
                        durationMs = 45_000L,
                        endReason = AgentVoiceCallEndReason.Left,
                    )
            }
            waitForIdle()

            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertDoesNotExist()
            onNodeWithTag("agent-voice-call-window").assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallCollapseButtonTestTag).assertDoesNotExist()
            onNodeWithTag(AgentVoiceCallFeedbackTestTag).assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallFeedbackGoodTestTag).assertIsDisplayed()
        }

    @Test
    fun failedTransitionFocusNonceAndNewCallAutoExpandCollapsedPill() =
        runRobolectricComposeUiTest {
            val positionHolder = AgentVoiceCallFloatingPositionHolder()
            val floatingStateHolder = AgentVoiceCallFloatingStateHolder()
            var call by mutableStateOf(
                AgentVoiceCallState(
                    agent = testAgent,
                    serverId = "srv-1",
                    callId = "call-1",
                    phase = AgentVoiceCallPhase.Connecting,
                ),
            )

            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        AgentVoiceCallDraggableOverlay(
                            call = call,
                            nowEpochMs = 1_010_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = {},
                            onClose = {},
                            onRetry = {},
                            bottomClearance = 34.dp,
                            topSafeArea = 44.dp,
                            horizontalSafeArea = RaftHorizontalSafeArea.Zero,
                            positionHolder = positionHolder,
                            floatingStateHolder = floatingStateHolder,
                        )
                    }
                }
            }

            // Collapse while connecting -> bump focusNonce -> auto-expands to card.
            onNodeWithTag(AgentVoiceCallCollapseButtonTestTag).performClick()
            waitForIdle()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertIsDisplayed()

            runOnIdle { call = call.copy(focusNonce = 1L) }
            waitForIdle()
            onNodeWithTag("agent-voice-call-window").assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertDoesNotExist()

            // Collapse again -> fail -> auto-expands to card without collapse button.
            onNodeWithTag(AgentVoiceCallCollapseButtonTestTag).performClick()
            waitForIdle()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertIsDisplayed()

            runOnIdle {
                call =
                    call.copy(
                        phase = AgentVoiceCallPhase.Failed,
                        problem = AgentVoiceCallProblem.AgentOffline,
                    )
            }
            waitForIdle()
            onNodeWithTag("agent-voice-call-window").assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertDoesNotExist()
            onNodeWithTag(AgentVoiceCallCollapseButtonTestTag).assertDoesNotExist()
            onNodeWithTag("agent-voice-call-retry").assertIsDisplayed()

            // New call starts -> collapse -> another callId replaces it -> auto-expands to card.
            runOnIdle {
                call =
                    AgentVoiceCallState(
                        agent = testAgent,
                        serverId = "srv-1",
                        callId = "call-2",
                        phase = AgentVoiceCallPhase.Live,
                        connectedAtEpochMs = 1_000_000L,
                    )
            }
            waitForIdle()
            onNodeWithTag(AgentVoiceCallCollapseButtonTestTag).performClick()
            waitForIdle()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertIsDisplayed()

            runOnIdle {
                call = call.copy(callId = "call-3")
            }
            waitForIdle()
            onNodeWithTag("agent-voice-call-window").assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallEdgePillTestTag).assertDoesNotExist()
        }
}
