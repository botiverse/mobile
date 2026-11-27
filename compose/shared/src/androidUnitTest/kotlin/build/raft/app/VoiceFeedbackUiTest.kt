@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import build.raft.app.agents.AgentVoiceCallCard
import build.raft.app.agents.AgentVoiceCallDraggableOverlay
import build.raft.app.agents.AgentVoiceCallFeedbackBadTestTag
import build.raft.app.agents.AgentVoiceCallFeedbackDetailsTestTag
import build.raft.app.agents.AgentVoiceCallFeedbackErrorTestTag
import build.raft.app.agents.AgentVoiceCallFeedbackGoodTestTag
import build.raft.app.agents.AgentVoiceCallFeedbackNoteTestTag
import build.raft.app.agents.AgentVoiceCallFeedbackSavedTestTag
import build.raft.app.agents.AgentVoiceCallFeedbackSubmitTestTag
import build.raft.app.agents.AgentVoiceCallFeedbackTestTag
import build.raft.app.agents.AgentVoiceCallFloatingPositionHolder
import build.raft.app.agents.agentVoiceCallFeedbackTagTestTag
import build.raft.app.agents.canShowAgentVoiceCallFeedback
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentVoiceCallEndReason
import build.raft.app.data.AgentVoiceCallFeedbackRequest
import build.raft.app.data.AgentVoiceCallPhase
import build.raft.app.data.AgentVoiceCallState
import build.raft.app.data.AgentVoiceCallee
import build.raft.app.data.AppLocale
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.RaftTelemetryPrivacy
import build.raft.app.data.RaftVoiceDictationAudioRecorder
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.VOICE_DICTATION_V1_FEATURE_FLAG_KEY
import build.raft.app.data.VOICE_FEEDBACK_SAMPLE_V0_FEATURE_FLAG_KEY
import build.raft.app.data.VoiceDictationAudioStartResult
import build.raft.app.data.VoiceDictationFeedbackRequest
import build.raft.app.data.VoiceDictationFeedbackTarget
import build.raft.app.data.VoiceDictationOutcomeReport
import build.raft.app.data.VoiceDictationV2Controller
import build.raft.app.data.VoiceDictationV2Permit
import build.raft.app.data.VoiceDictationV2PermitApi
import build.raft.app.data.VoiceDictationV2PermitOutcome
import build.raft.app.data.VoiceDictationV2Phase
import build.raft.app.data.VoiceDictationV2Quota
import build.raft.app.data.VoiceDictationV2ServerFrame
import build.raft.app.data.VoiceDictationV2StreamConnection
import build.raft.app.data.VoiceDictationV2StreamConnector
import build.raft.app.data.VoiceDictationV2StreamEvent
import build.raft.app.data.VoiceFeedbackApi
import build.raft.app.data.VoiceFeedbackRating
import build.raft.app.data.VoiceFeedbackSample
import build.raft.app.data.VoiceFeedbackSubmitFailure
import build.raft.app.data.VoiceFeedbackSubmitOutcome
import build.raft.app.data.VoiceFeedbackTag
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ServerId
import build.raft.app.resources.StringDesc
import build.raft.app.shell.RaftHorizontalSafeArea
import build.raft.app.thread.LocalThreadVoiceDictationHost
import build.raft.app.thread.ThreadVoiceDictationButtonTestTag
import build.raft.app.thread.ThreadVoiceDictationFeedbackConsentTestTag
import build.raft.app.thread.ThreadVoiceDictationFeedbackDismissTestTag
import build.raft.app.thread.ThreadVoiceDictationFeedbackErrorTestTag
import build.raft.app.thread.ThreadVoiceDictationFeedbackNoteTestTag
import build.raft.app.thread.ThreadVoiceDictationFeedbackOpenTestTag
import build.raft.app.thread.ThreadVoiceDictationFeedbackSavedTestTag
import build.raft.app.thread.ThreadVoiceDictationFeedbackStripTestTag
import build.raft.app.thread.ThreadVoiceDictationFeedbackSubmitTestTag
import build.raft.app.thread.ThreadVoiceDictationHost
import build.raft.app.thread.canShowVoiceDictationFeedbackStrip
import build.raft.app.thread.rememberComposerVoiceDictationHost
import build.raft.app.thread.threadVoiceDictationFeedbackTagTestTag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class VoiceFeedbackUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        RaftTelemetryPrivacy.publish(RaftPrivacyMode.Unknown)
    }

    @AfterTest
    fun tearDown() {
        RaftTelemetryPrivacy.publish(RaftPrivacyMode.Unknown)
        StringDesc.localeType = previousLocale
    }

    @Test
    fun postDictationStripHiddenWhenFlagOffOrPrivacyUnknownOrOnAndVisibleWhenFlagOnAndPrivacyOff() =
        runRobolectricComposeUiTest {
            val sampleTarget =
                VoiceDictationFeedbackTarget(
                    serverId = "srv-1",
                    sessionId = "jti_gate",
                    raw = "raw",
                    refined = "Refined.",
                )
            assertFalse(
                canShowVoiceDictationFeedbackStrip(
                    recentFeedbackTarget = sampleTarget,
                    sampleFeedbackEnabled = false,
                    privacyMode = RaftPrivacyMode.Off,
                    hasError = false,
                    isDictationActive = false,
                    showLowQuota = false,
                ),
            )
            assertFalse(
                canShowVoiceDictationFeedbackStrip(
                    recentFeedbackTarget = sampleTarget,
                    sampleFeedbackEnabled = true,
                    privacyMode = RaftPrivacyMode.Unknown,
                    hasError = false,
                    isDictationActive = false,
                    showLowQuota = false,
                ),
            )
            assertFalse(
                canShowVoiceDictationFeedbackStrip(
                    recentFeedbackTarget = sampleTarget,
                    sampleFeedbackEnabled = true,
                    privacyMode = RaftPrivacyMode.On,
                    hasError = false,
                    isDictationActive = false,
                    showLowQuota = false,
                ),
            )
            assertTrue(
                canShowVoiceDictationFeedbackStrip(
                    recentFeedbackTarget = sampleTarget,
                    sampleFeedbackEnabled = true,
                    privacyMode = RaftPrivacyMode.Off,
                    hasError = false,
                    isDictationActive = false,
                    showLowQuota = false,
                ),
            )

            val serverId = "srv-voice-fb-gate"
            val offFlags = SharedServerFeatureFlags(api = FakeFeatureFlagsApi(dictationEnabled = true, sampleEnabled = false))
            val onFlags = SharedServerFeatureFlags(api = FakeFeatureFlagsApi(dictationEnabled = true, sampleEnabled = true))
            var activeFlags by mutableStateOf(offFlags)
            val connection = FakeStreamConnection()
            val recorder = FakeRecorder()
            val permitApi = FakePermitApi { samplePermit(sessionId = "jti_gate_live") }
            val streamConnector = FakeStreamConnector { connection }
            val recorderProvider: () -> RaftVoiceDictationAudioRecorder? = { recorder }
            val feedbackApi = FakeVoiceFeedbackApi()
            val session = sampleSession(serverId = serverId)
            var controllerRef: VoiceDictationV2Controller? = null

            setContent {
                RaftDesignSystemTheme {
                    val host =
                        rememberComposerVoiceDictationHost(
                            serverFeatureFlags = activeFlags,
                            permitApi = permitApi,
                            active = true,
                            currentSession = session,
                            serverId = serverId,
                            prefetchIfUnresolved = true,
                            streamConnector = streamConnector,
                            audioRecorderProvider = recorderProvider,
                            feedbackApi = feedbackApi,
                        ).also { controllerRef = it.controller }
                    CompositionLocalProvider(LocalThreadVoiceDictationHost provides host) {
                        ThreadComposerContent(
                            surface = ThreadComposerSurface(channelId = "chan-fb-gate"),
                            state = ThreadComposerUiState(instanceId = "voice-fb-gate", enabled = true),
                            onEvent = { _, _, _, _ -> },
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = 0.dp,
                            attachmentPreviewEnabled = false,
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            // Complete a dictation session while flag is OFF and privacy is Off -> strip stays hidden.
            runOnIdle { RaftTelemetryPrivacy.publish(RaftPrivacyMode.Off) }
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            connection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            connection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "hello raft", stableLen = 10, refined = true))
            connection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()

            assertNotNull(assertNotNull(controllerRef).state.value.recentFeedbackTarget)
            onNodeWithTag(ThreadVoiceDictationFeedbackStripTestTag, useUnmergedTree = true).assertDoesNotExist()

            // Turn sample flag ON while privacy is Unknown or On -> strip still stays hidden (fail-closed).
            runOnIdle {
                RaftTelemetryPrivacy.publish(RaftPrivacyMode.Unknown)
                activeFlags = onFlags
            }
            waitUntil(timeoutMillis = 5_000L) { onFlags.snapshotFor(ServerId(serverId)).resolved }
            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationFeedbackStripTestTag, useUnmergedTree = true).assertDoesNotExist()

            runOnIdle { RaftTelemetryPrivacy.publish(RaftPrivacyMode.On) }
            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationFeedbackStripTestTag, useUnmergedTree = true).assertDoesNotExist()

            // Privacy resolves Off + flag ON -> strip appears and can be dismissed cleanly.
            runOnIdle { RaftTelemetryPrivacy.publish(RaftPrivacyMode.Off) }
            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationFeedbackStripTestTag, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("How was voice dictation?").assertIsDisplayed()
            onNodeWithText("Report issue").assertIsDisplayed()

            onNodeWithTag(ThreadVoiceDictationFeedbackDismissTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationFeedbackStripTestTag, useUnmergedTree = true).assertDoesNotExist()
            assertNull(assertNotNull(controllerRef).state.value.recentFeedbackTarget)
        }

    @Test
    fun postDictationStripDefaultsConsentUncheckedSubmitsTagsNoteAndIncludesSampleOnlyOnOptIn() =
        runRobolectricComposeUiTest {
            RaftTelemetryPrivacy.publish(RaftPrivacyMode.Off)
            val firstConnection = FakeStreamConnection()
            val secondConnection = FakeStreamConnection()
            var permitIndex = 0
            var connectIndex = 0
            val submitted = mutableListOf<Pair<String, VoiceDictationFeedbackRequest>>()

            setContent {
                val scope = rememberCoroutineScope()
                val controller =
                    remember {
                        VoiceDictationV2Controller(
                            permitApi =
                                FakePermitApi {
                                    val sessionId = if (permitIndex == 0) "jti_internal_1" else "jti_internal_2"
                                    permitIndex += 1
                                    samplePermit(sessionId = sessionId)
                                },
                            streamConnector =
                                FakeStreamConnector {
                                    val next = if (connectIndex == 0) firstConnection else secondConnection
                                    connectIndex += 1
                                    next
                                },
                            scope = scope,
                            audioRecorderProvider = { FakeRecorder() },
                        )
                    }
                val host =
                    remember(controller) {
                        ThreadVoiceDictationHost(
                            flagEnabled = true,
                            viewerIsGuest = false,
                            recorderAvailable = true,
                            serverId = "srv-1",
                            channelId = "550e8400-e29b-41d4-a716-446655440000",
                            threadId = null,
                            languageHint = "en",
                            controller = controller,
                            sampleFeedbackEnabled = true,
                            onSubmitFeedback = { serverId, request ->
                                submitted += serverId to request
                                VoiceFeedbackSubmitOutcome.Submitted
                            },
                        )
                    }
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalThreadVoiceDictationHost provides host) {
                        ThreadComposerContent(
                            surface = ThreadComposerSurface(channelId = "chan-fb-submit"),
                            state = ThreadComposerUiState(instanceId = "voice-fb-submit", enabled = true),
                            onEvent = { _, _, _, _ -> },
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = 0.dp,
                            attachmentPreviewEnabled = false,
                        )
                    }
                }
            }

            // Session 1: consent defaults unchecked -> sample MUST be omitted.
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "open rafted", stableLen = 11, refined = false))
            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Open rafted.", stableLen = 12, refined = true))
            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()

            onNodeWithTag(ThreadVoiceDictationFeedbackOpenTestTag, useUnmergedTree = true).assertIsDisplayed().performClick()
            waitForIdle()

            onNodeWithTag(ThreadVoiceDictationFeedbackConsentTestTag, useUnmergedTree = true).assertIsOff()
            onNodeWithTag(threadVoiceDictationFeedbackTagTestTag(VoiceFeedbackTag.WrongWord), useUnmergedTree = true)
                .assertIsOff()
                .performClick()
            onNodeWithTag(threadVoiceDictationFeedbackTagTestTag(VoiceFeedbackTag.WrongWord), useUnmergedTree = true)
                .assertIsOn()
            onNodeWithTag(ThreadVoiceDictationFeedbackNoteTestTag, useUnmergedTree = true)
                .performTextInput("  heard rafted  ")
            onNodeWithTag(ThreadVoiceDictationFeedbackSubmitTestTag, useUnmergedTree = true).performClick()
            waitForIdle()

            onNodeWithTag(ThreadVoiceDictationFeedbackSavedTestTag, useUnmergedTree = true).assertIsDisplayed()
            assertEquals(1, submitted.size)
            assertEquals(
                "srv-1" to
                    VoiceDictationFeedbackRequest(
                        sessionId = "jti_internal_1",
                        rating = VoiceFeedbackRating.Bad,
                        tags = listOf(VoiceFeedbackTag.WrongWord),
                        note = "heard rafted",
                        sample = null,
                    ),
                submitted[0],
            )

            // Session 2: consent resets unchecked per session; checking it includes sample with consent = true.
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            secondConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            secondConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "second raw", stableLen = 10, refined = false))
            secondConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Second refined.", stableLen = 15, refined = true))
            secondConnection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()

            onNodeWithTag(ThreadVoiceDictationFeedbackOpenTestTag, useUnmergedTree = true).assertIsDisplayed().performClick()
            waitForIdle()

            val consent = onNodeWithTag(ThreadVoiceDictationFeedbackConsentTestTag, useUnmergedTree = true)
            consent.assertIsOff()
            consent.performClick()
            consent.assertIsOn()
            onNodeWithTag(ThreadVoiceDictationFeedbackSubmitTestTag, useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(2, submitted.size)
            assertEquals(
                "srv-1" to
                    VoiceDictationFeedbackRequest(
                        sessionId = "jti_internal_2",
                        rating = VoiceFeedbackRating.Bad,
                        tags = emptyList(),
                        note = null,
                        sample =
                            VoiceFeedbackSample(
                                consent = true,
                                raw = "second raw",
                                refined = "Second refined.",
                                sent = null,
                            ),
                    ),
                submitted[1],
            )
        }

    @Test
    fun postDictationStripMapsTypedRefusalsToLocalizedErrorsAndAllowsRetry() =
        runRobolectricComposeUiTest {
            RaftTelemetryPrivacy.publish(RaftPrivacyMode.Off)
            val connection = FakeStreamConnection()
            val outcomes =
                ArrayDeque<VoiceFeedbackSubmitOutcome>(
                    listOf(
                        VoiceFeedbackSubmitOutcome.Refused(VoiceFeedbackSubmitFailure.Unavailable()),
                        VoiceFeedbackSubmitOutcome.Refused(VoiceFeedbackSubmitFailure.RateLimited(retryAfterSeconds = 12)),
                        VoiceFeedbackSubmitOutcome.Refused(VoiceFeedbackSubmitFailure.NotFound(code = "voice_session_not_found")),
                        VoiceFeedbackSubmitOutcome.Refused(VoiceFeedbackSubmitFailure.InvalidRequest()),
                        VoiceFeedbackSubmitOutcome.Submitted,
                    ),
                )

            setContent {
                val scope = rememberCoroutineScope()
                val controller =
                    remember {
                        VoiceDictationV2Controller(
                            permitApi = FakePermitApi { samplePermit(sessionId = "jti_refusal") },
                            streamConnector = FakeStreamConnector { connection },
                            scope = scope,
                            audioRecorderProvider = { FakeRecorder() },
                        )
                    }
                val host =
                    remember(controller) {
                        ThreadVoiceDictationHost(
                            flagEnabled = true,
                            viewerIsGuest = false,
                            recorderAvailable = true,
                            serverId = "srv-1",
                            channelId = "550e8400-e29b-41d4-a716-446655440000",
                            threadId = null,
                            languageHint = "en",
                            controller = controller,
                            sampleFeedbackEnabled = true,
                            onSubmitFeedback = { _, _ -> outcomes.removeFirst() },
                        )
                    }
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalThreadVoiceDictationHost provides host) {
                        ThreadComposerContent(
                            surface = ThreadComposerSurface(channelId = "chan-fb-err"),
                            state = ThreadComposerUiState(instanceId = "voice-fb-err", enabled = true),
                            onEvent = { _, _, _, _ -> },
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = 0.dp,
                            attachmentPreviewEnabled = false,
                        )
                    }
                }
            }

            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            connection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            connection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "retry me", stableLen = 8, refined = true))
            connection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()

            onNodeWithTag(ThreadVoiceDictationFeedbackOpenTestTag, useUnmergedTree = true).performClick()
            waitForIdle()

            // 503 Unavailable
            onNodeWithTag(ThreadVoiceDictationFeedbackSubmitTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationFeedbackErrorTestTag, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Voice feedback isn't available right now.").assertIsDisplayed()

            // 429 RateLimited
            onNodeWithTag(ThreadVoiceDictationFeedbackSubmitTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Too many feedback submissions. Wait 12s, then try again.").assertIsDisplayed()

            // 404 NotFound
            onNodeWithTag(ThreadVoiceDictationFeedbackSubmitTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("This voice session is no longer available for feedback.").assertIsDisplayed()

            // 400 InvalidRequest
            onNodeWithTag(ThreadVoiceDictationFeedbackSubmitTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Couldn't send feedback. Try again.").assertIsDisplayed()

            // Retry succeeds
            onNodeWithTag(ThreadVoiceDictationFeedbackSubmitTestTag, useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationFeedbackSavedTestTag, useUnmergedTree = true).assertIsDisplayed()
        }

    @Test
    fun composerSendReportsSubmittedTextAndEmitsImplicitEditMetrics() =
        runRobolectricComposeUiTest {
            RaftTelemetryPrivacy.publish(RaftPrivacyMode.Off)
            val firstConnection = FakeStreamConnection()
            val secondConnection = FakeStreamConnection()
            val thirdConnection = FakeStreamConnection()
            var permitIndex = 0
            var connectIndex = 0
            var admission by mutableStateOf(ThreadComposerSendAdmission.Rejected)
            val reports = mutableListOf<VoiceDictationOutcomeReport>()
            val sentTexts = mutableListOf<String>()
            var controllerRef: VoiceDictationV2Controller? = null

            setContent {
                val scope = rememberCoroutineScope()
                var commandNonce by remember { mutableStateOf(0) }
                var clearCommand by remember { mutableStateOf<ThreadComposerEditorCommand?>(null) }
                val controller =
                    remember {
                        VoiceDictationV2Controller(
                            permitApi =
                                FakePermitApi {
                                    val sessionId =
                                        when (permitIndex) {
                                            0 -> "jti_send_1"
                                            1 -> "jti_send_2"
                                            else -> "jti_send_3"
                                        }
                                    permitIndex += 1
                                    samplePermit(sessionId = sessionId)
                                },
                            streamConnector =
                                FakeStreamConnector {
                                    val next =
                                        when (connectIndex) {
                                            0 -> firstConnection
                                            1 -> secondConnection
                                            else -> thirdConnection
                                        }
                                    connectIndex += 1
                                    next
                                },
                            scope = scope,
                            audioRecorderProvider = { FakeRecorder() },
                            onOutcomeReport = { reports += it },
                        ).also { controllerRef = it }
                    }
                val host =
                    remember(controller) {
                        ThreadVoiceDictationHost(
                            flagEnabled = true,
                            viewerIsGuest = false,
                            recorderAvailable = true,
                            serverId = "srv-1",
                            channelId = "550e8400-e29b-41d4-a716-446655440000",
                            threadId = null,
                            languageHint = "en",
                            controller = controller,
                        )
                    }
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalThreadVoiceDictationHost provides host) {
                        ThreadComposerContent(
                            surface = ThreadComposerSurface(channelId = "chan-send-report"),
                            state =
                                ThreadComposerUiState(
                                    instanceId = "voice-send-report",
                                    enabled = true,
                                    editorCommand = clearCommand,
                                ),
                            onEvent = { _, _, _, _ -> },
                            onSendRequest = { request ->
                                if (admission != ThreadComposerSendAdmission.Rejected) {
                                    sentTexts += request.value.text
                                    commandNonce += 1
                                    clearCommand =
                                        ThreadComposerEditorCommand(
                                            id = commandNonce,
                                            kind = ThreadComposerEditorCommandKind.Clear,
                                            expectedText = request.value.text,
                                        )
                                }
                                admission
                            },
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = 0.dp,
                            attachmentPreviewEnabled = false,
                        )
                    }
                }
            }

            // Dictate and finish cleanly.
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Ship it", stableLen = 7, refined = true))
            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()

            // Rejected send must NOT report submitted yet.
            onNodeWithContentDescription("Send").assertIsEnabled().performClick()
            waitForIdle()
            assertTrue(reports.isEmpty())

            // Edit slightly and allow send -> reports edited implicit metrics & populates target.sent;
            // subsequent Clear command must NOT emit a spurious abandoned report.
            onNode(hasSetTextAction()).performTextInput(" now")
            runOnIdle { admission = ThreadComposerSendAdmission.Started }
            waitForIdle()
            onNodeWithContentDescription("Send").assertIsEnabled().performClick()
            waitForIdle()

            assertEquals(listOf("Ship it now"), sentTexts)
            assertEquals(1, reports.size)
            assertEquals("jti_send_1", reports[0].sessionId)
            assertEquals(false, reports[0].clientMetrics.abandoned)
            assertEquals(false, reports[0].clientMetrics.sentAsIs)
            assertEquals(false, reports[0].clientMetrics.sentBeforeRefineDone)
            assertEquals("Ship it now", assertNotNull(controllerRef).state.value.recentFeedbackTarget?.sent)

            // Second dictation sent while still actively listening -> reports sentBeforeRefineDone = true.
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            secondConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            secondConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Quick send", stableLen = 10, refined = false))
            waitForIdle()
            assertEquals(VoiceDictationV2Phase.Listening, assertNotNull(controllerRef).state.value.phase)

            onNodeWithContentDescription("Send").assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(listOf("Ship it now", "Quick send"), sentTexts)
            assertEquals(2, reports.size)
            assertEquals("jti_send_2", reports[1].sessionId)
            assertEquals(true, reports[1].clientMetrics.sentBeforeRefineDone)
            assertEquals(true, reports[1].clientMetrics.sentAsIs)

            // Third dictation under RAFT_PRIVACY_MODE = On -> zero outcome report emitted on send.
            runOnIdle { RaftTelemetryPrivacy.publish(RaftPrivacyMode.On) }
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            thirdConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            thirdConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Private msg", stableLen = 11, refined = true))
            thirdConnection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()

            onNodeWithContentDescription("Send").assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(listOf("Ship it now", "Quick send", "Private msg"), sentTexts)
            assertEquals(2, reports.size, "implicit edit metrics must be suppressed under privacy mode")
        }

    @Test
    fun agentVoiceCallCardFeedbackRespectsFailClosedPrivacyModeAndEndedPhase() =
        runRobolectricComposeUiTest {
            val callId = "123e4567-e89b-12d3-a456-426614174000"
            assertFalse(
                canShowAgentVoiceCallFeedback(
                    phase = AgentVoiceCallPhase.Live,
                    callId = callId,
                    privacyMode = RaftPrivacyMode.Off,
                    hasSubmitHandler = true,
                ),
            )
            assertFalse(
                canShowAgentVoiceCallFeedback(
                    phase = AgentVoiceCallPhase.Ended,
                    callId = null,
                    privacyMode = RaftPrivacyMode.Off,
                    hasSubmitHandler = true,
                ),
            )
            assertFalse(
                canShowAgentVoiceCallFeedback(
                    phase = AgentVoiceCallPhase.Ended,
                    callId = callId,
                    privacyMode = RaftPrivacyMode.Unknown,
                    hasSubmitHandler = true,
                ),
            )
            assertFalse(
                canShowAgentVoiceCallFeedback(
                    phase = AgentVoiceCallPhase.Ended,
                    callId = callId,
                    privacyMode = RaftPrivacyMode.On,
                    hasSubmitHandler = true,
                ),
            )
            assertTrue(
                canShowAgentVoiceCallFeedback(
                    phase = AgentVoiceCallPhase.Ended,
                    callId = callId,
                    privacyMode = RaftPrivacyMode.Off,
                    hasSubmitHandler = true,
                ),
            )

            val endedCall =
                AgentVoiceCallState(
                    agent = AgentVoiceCallee(id = "agent-lagoon", name = "Lagoon"),
                    serverId = "srv-1",
                    callId = callId,
                    phase = AgentVoiceCallPhase.Ended,
                    connectedAtEpochMs = 1_000_000L,
                    durationMs = 45_000L,
                    endReason = AgentVoiceCallEndReason.Left,
                )

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        AgentVoiceCallCard(
                            call = endedCall,
                            nowEpochMs = 1_045_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = {},
                            onClose = {},
                            onRetry = {},
                            onSubmitFeedback = { VoiceFeedbackSubmitOutcome.Submitted },
                        )
                    }
                }
            }

            // Unknown -> hidden
            onNodeWithTag(AgentVoiceCallFeedbackTestTag).assertDoesNotExist()

            // On -> hidden
            runOnIdle { RaftTelemetryPrivacy.publish(RaftPrivacyMode.On) }
            waitForIdle()
            onNodeWithTag(AgentVoiceCallFeedbackTestTag).assertDoesNotExist()

            // Off -> visible
            runOnIdle { RaftTelemetryPrivacy.publish(RaftPrivacyMode.Off) }
            waitForIdle()
            onNodeWithTag(AgentVoiceCallFeedbackTestTag).assertIsDisplayed()
            onNodeWithText("How was the call?").assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallFeedbackGoodTestTag).assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallFeedbackBadTestTag).assertIsDisplayed()
        }

    @Test
    fun agentVoiceCallCardOneTapGoodBadOverwriteRefineTagsNoteAndRetry() =
        runRobolectricComposeUiTest {
            RaftTelemetryPrivacy.publish(RaftPrivacyMode.Off)
            val callId = "123e4567-e89b-12d3-a456-426614174000"
            val submitted = mutableListOf<AgentVoiceCallFeedbackRequest>()
            var nextOutcome: VoiceFeedbackSubmitOutcome = VoiceFeedbackSubmitOutcome.Submitted
            val positionHolder = AgentVoiceCallFloatingPositionHolder()
            val endedCall =
                AgentVoiceCallState(
                    agent = AgentVoiceCallee(id = "agent-lagoon", name = "Lagoon"),
                    serverId = "srv-1",
                    callId = callId,
                    phase = AgentVoiceCallPhase.Ended,
                    connectedAtEpochMs = 1_000_000L,
                    durationMs = 90_000L,
                    endReason = AgentVoiceCallEndReason.Left,
                )

            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        AgentVoiceCallDraggableOverlay(
                            call = endedCall,
                            nowEpochMs = 1_090_000L,
                            onToggleMute = {},
                            onToggleSpeaker = {},
                            onHangUp = {},
                            onClose = {},
                            onRetry = {},
                            bottomClearance = 34.dp,
                            topSafeArea = 44.dp,
                            horizontalSafeArea = RaftHorizontalSafeArea.Zero,
                            positionHolder = positionHolder,
                            onSubmitFeedback = { request ->
                                submitted += request
                                nextOutcome
                            },
                        )
                    }
                }
            }

            // 1-tap Good saves immediately without expanding details.
            onNodeWithContentDescription("Good call").assertIsOff().performClick()
            waitForIdle()
            onNodeWithContentDescription("Good call").assertIsOn()
            onNodeWithTag(AgentVoiceCallFeedbackSavedTestTag).assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallFeedbackDetailsTestTag).assertDoesNotExist()
            assertEquals(listOf(AgentVoiceCallFeedbackRequest(rating = VoiceFeedbackRating.Good)), submitted)

            // 1-tap Bad immediately overwrites with Bad AND expands optional tags + note controls.
            onNodeWithContentDescription("Call had issues").assertIsOff().performClick()
            waitForIdle()
            onNodeWithContentDescription("Call had issues").assertIsOn()
            onNodeWithTag(AgentVoiceCallFeedbackDetailsTestTag).assertIsDisplayed()
            assertEquals(2, submitted.size)
            assertEquals(AgentVoiceCallFeedbackRequest(rating = VoiceFeedbackRating.Bad), submitted[1])

            // Select tags + enter note + Submit -> idempotent overwrite with refined payload.
            onNodeWithTag(agentVoiceCallFeedbackTagTestTag(VoiceFeedbackTag.WrongAnswer)).performClick()
            onNodeWithTag(agentVoiceCallFeedbackTagTestTag(VoiceFeedbackTag.TooSlow)).performClick()
            onNodeWithTag(AgentVoiceCallFeedbackNoteTestTag, useUnmergedTree = true)
                .performTextInput("  Delayed response  ")
            onNodeWithTag(AgentVoiceCallFeedbackSubmitTestTag).performClick()
            waitForIdle()

            assertEquals(3, submitted.size)
            assertEquals(
                AgentVoiceCallFeedbackRequest(
                    rating = VoiceFeedbackRating.Bad,
                    tags = listOf(VoiceFeedbackTag.WrongAnswer, VoiceFeedbackTag.TooSlow),
                    note = "Delayed response",
                ),
                submitted[2],
            )
            onNodeWithTag(AgentVoiceCallFeedbackSavedTestTag).assertIsDisplayed()

            // Refusal surfaces typed error and allows retry.
            nextOutcome = VoiceFeedbackSubmitOutcome.Refused(VoiceFeedbackSubmitFailure.RateLimited(retryAfterSeconds = 15))
            onNodeWithContentDescription("Good call").performClick()
            waitForIdle()
            onNodeWithTag(AgentVoiceCallFeedbackErrorTestTag).assertIsDisplayed()
            onNodeWithText("Too many feedback submissions. Wait 15s, then try again.").assertIsDisplayed()

            nextOutcome = VoiceFeedbackSubmitOutcome.Submitted
            onNodeWithContentDescription("Good call").performClick()
            waitForIdle()
            onNodeWithTag(AgentVoiceCallFeedbackSavedTestTag).assertIsDisplayed()
            onNodeWithTag(AgentVoiceCallFeedbackErrorTestTag).assertDoesNotExist()
        }

    private fun sampleSession(serverId: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.com"),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = serverId,
            servers = listOf(StoredAuthServer(id = serverId, name = "Test Server", role = "member")),
        )

    private fun samplePermit(sessionId: String): VoiceDictationV2Permit =
        VoiceDictationV2Permit(
            permit = "hv2.kid.body.sig",
            url = "wss://live.holt.botiverse.build/v1/stream",
            expiresAtIso = "2026-11-26T20:01:00Z",
            expiresAtEpochMs = 1_795_723_260_000L,
            maxDurationSeconds = 600,
            quota =
                VoiceDictationV2Quota(
                    usedSeconds = 60,
                    limitSeconds = 7_200,
                    remainingSeconds = 7_140,
                    resetsAtIso = "2026-11-27T00:00:00Z",
                    resetsAtEpochMs = 1_795_737_600_000L,
                ),
            sessionId = sessionId,
        )

    private class FakeFeatureFlagsApi(
        private val dictationEnabled: Boolean,
        private val sampleEnabled: Boolean,
    ) : RaftFeatureFlagsApi {
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"evaluations":[""" +
                        """{"key":"$VOICE_DICTATION_V1_FEATURE_FLAG_KEY","enabled":$dictationEnabled,"reason":"rule"},""" +
                        """{"key":"$VOICE_FEEDBACK_SAMPLE_V0_FEATURE_FLAG_KEY","enabled":$sampleEnabled,"reason":"rule"}""" +
                        """]}""",
            )
    }

    private class FakeVoiceFeedbackApi : VoiceFeedbackApi {
        val dictationSubmissions = mutableListOf<Pair<String, VoiceDictationFeedbackRequest>>()

        override suspend fun submitDictationFeedback(
            serverId: String,
            request: VoiceDictationFeedbackRequest,
        ): VoiceFeedbackSubmitOutcome {
            dictationSubmissions += serverId to request
            return VoiceFeedbackSubmitOutcome.Submitted
        }

        override suspend fun submitAgentVoiceCallFeedback(
            serverId: String,
            agentId: String,
            callId: String,
            request: AgentVoiceCallFeedbackRequest,
        ): VoiceFeedbackSubmitOutcome = VoiceFeedbackSubmitOutcome.Submitted
    }

    private class FakePermitApi(
        private val handler: suspend () -> VoiceDictationV2Permit,
    ) : VoiceDictationV2PermitApi {
        override suspend fun requestPermit(
            serverId: String,
            language: String?,
            channelId: String?,
        ): VoiceDictationV2PermitOutcome = VoiceDictationV2PermitOutcome.Issued(handler())
    }

    private class FakeStreamConnector(
        private val factory: () -> FakeStreamConnection,
    ) : VoiceDictationV2StreamConnector {
        override suspend fun connect(url: String, permit: String): VoiceDictationV2StreamConnection = factory()
    }

    private class FakeStreamConnection : VoiceDictationV2StreamConnection {
        private val channel = Channel<VoiceDictationV2StreamEvent>(Channel.UNLIMITED)
        override val events: Flow<VoiceDictationV2StreamEvent> = channel.receiveAsFlow()

        override suspend fun sendStart(sampleRateHz: Int): Boolean = true

        override suspend fun sendAudio(pcm16LeMonoBytes: ByteArray): Boolean = true

        override suspend fun sendStop(): Boolean = true

        override suspend fun close(code: Int, reason: String) {
            channel.close()
        }

        fun emitFrame(frame: VoiceDictationV2ServerFrame) {
            channel.trySend(VoiceDictationV2StreamEvent.Frame(frame))
        }
    }

    private class FakeRecorder : RaftVoiceDictationAudioRecorder {
        override suspend fun start(
            onPcmChunk: (ByteArray) -> Unit,
            onAudioLevel: (Float) -> Unit,
        ): VoiceDictationAudioStartResult = VoiceDictationAudioStartResult.Started

        override suspend fun stop() = Unit
    }
}
