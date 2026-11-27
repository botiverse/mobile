@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.RaftVoiceDictationAudioRecorder
import build.raft.app.data.VoiceDictationAudioStartResult
import build.raft.app.data.VoiceDictationGestureMode
import build.raft.app.data.VoiceDictationV2Controller
import build.raft.app.data.VoiceDictationV2Error
import build.raft.app.data.VoiceDictationV2ErrorCode
import build.raft.app.data.VoiceDictationV2Permit
import build.raft.app.data.VoiceDictationV2PermitApi
import build.raft.app.data.VoiceDictationV2PermitFailure
import build.raft.app.data.VoiceDictationV2PermitOutcome
import build.raft.app.data.VoiceDictationV2Phase
import build.raft.app.data.VoiceDictationV2Quota
import build.raft.app.data.VoiceDictationV2ServerFrame
import build.raft.app.data.VoiceDictationV2State
import build.raft.app.data.VoiceDictationV2StreamConnection
import build.raft.app.data.VoiceDictationV2StreamConnector
import build.raft.app.data.VoiceDictationV2StreamEvent
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBannerStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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
class ThreadVoiceDictationUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun gatingMatrixHidesMicForFlagOffGuestReadOnlyOrMissingRecorder() =
        runRobolectricComposeUiTest {
            assertFalse(canShowComposerVoiceDictation(flagEnabled = false, viewerIsGuest = false, recorderAvailable = true, composerEditable = true))
            assertFalse(canShowComposerVoiceDictation(flagEnabled = true, viewerIsGuest = true, recorderAvailable = true, composerEditable = true))
            assertFalse(canShowComposerVoiceDictation(flagEnabled = true, viewerIsGuest = false, recorderAvailable = false, composerEditable = true))
            assertFalse(canShowComposerVoiceDictation(flagEnabled = true, viewerIsGuest = false, recorderAvailable = true, composerEditable = false))
            assertTrue(canShowComposerVoiceDictation(flagEnabled = true, viewerIsGuest = false, recorderAvailable = true, composerEditable = true))

            setContent {
                val scope = rememberCoroutineScope()
                val controller = remember {
                    VoiceDictationV2Controller(
                        permitApi = FakePermitApi { VoiceDictationV2PermitOutcome.Issued(samplePermit()) },
                        streamConnector = FakeStreamConnector(FakeStreamConnection()),
                        scope = scope,
                        audioRecorderProvider = { FakeRecorder() },
                    )
                }
                val hiddenFlagHost = remember {
                    ThreadVoiceDictationHost(
                        flagEnabled = false,
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
                    CompositionLocalProvider(LocalThreadVoiceDictationHost provides hiddenFlagHost) {
                        ThreadComposerContent(
                            surface = ThreadComposerSurface(channelId = "chan-1"),
                            state = ThreadComposerUiState(instanceId = "voice-gate-off", enabled = true),
                            onEvent = { _, _, _, _ -> },
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = 0.dp,
                            attachmentPreviewEnabled = false,
                        )
                    }
                }
            }

            onNodeWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun guestAndReadOnlyHideMicWhileEligibleMemberShowsMic() =
        runRobolectricComposeUiTest {
            setContent {
                val scope = rememberCoroutineScope()
                val controller = remember {
                    VoiceDictationV2Controller(
                        permitApi = FakePermitApi { VoiceDictationV2PermitOutcome.Issued(samplePermit()) },
                        streamConnector = FakeStreamConnector(FakeStreamConnection()),
                        scope = scope,
                        audioRecorderProvider = { FakeRecorder() },
                    )
                }
                val guestHost = remember {
                    ThreadVoiceDictationHost(
                        flagEnabled = true,
                        viewerIsGuest = true,
                        recorderAvailable = true,
                        serverId = "srv-1",
                        channelId = "550e8400-e29b-41d4-a716-446655440000",
                        threadId = null,
                        languageHint = "en",
                        controller = controller,
                    )
                }
                val eligibleHost = remember {
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
                    Column(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalThreadVoiceDictationHost provides guestHost) {
                            ThreadVoiceDictationToolbarButton(
                                host = guestHost,
                                composerEditable = true,
                                onStart = {},
                            )
                        }
                        CompositionLocalProvider(LocalThreadVoiceDictationHost provides eligibleHost) {
                            ThreadVoiceDictationToolbarButton(
                                host = eligibleHost,
                                composerEditable = false,
                                onStart = {},
                            )
                        }
                        CompositionLocalProvider(LocalThreadVoiceDictationHost provides eligibleHost) {
                            ThreadComposerContent(
                                surface = ThreadComposerSurface(channelId = "chan-2"),
                                state = ThreadComposerUiState(instanceId = "voice-gate-on", enabled = true),
                                onEvent = { _, _, _, _ -> },
                                contentPaddingTop = 0.dp,
                                contentPaddingBottom = 0.dp,
                                attachmentPreviewEnabled = false,
                            )
                        }
                    }
                }
            }

            onNodeWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true).assertExists()
            onNodeWithContentDescription("Voice dictation").assertIsDisplayed().assertIsEnabled()
        }

    @Test
    fun tapMicStartsZeroWaitUpdatesComposerAndNonBlockingStopDrainsInBackground() =
        runRobolectricComposeUiTest {
            val recorderGate = CompletableDeferred<VoiceDictationAudioStartResult>()
            val permitGate = CompletableDeferred<VoiceDictationV2PermitOutcome>()
            val recorder = FakeRecorder(startGate = recorderGate)
            val connection = FakeStreamConnection()
            val changedTexts = mutableListOf<String>()
            var controllerRef: VoiceDictationV2Controller? = null

            setContent {
                val scope = rememberCoroutineScope()
                val controller = remember {
                    VoiceDictationV2Controller(
                        permitApi = FakePermitApi { permitGate.await() },
                        streamConnector = FakeStreamConnector(connection),
                        scope = scope,
                        audioRecorderProvider = { recorder },
                    ).also { controllerRef = it }
                }
                val host = remember(controller) {
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
                            surface = ThreadComposerSurface(channelId = "chan-live"),
                            state =
                                ThreadComposerUiState(
                                    instanceId = "voice-live-flow",
                                    seedText = "Ship",
                                    enabled = true,
                                ),
                            onEvent = { action, value, _, _ ->
                                if (action == "change" && value != null) {
                                    changedTexts += value
                                }
                            },
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = 0.dp,
                            attachmentPreviewEnabled = false,
                        )
                    }
                }
            }

            // Idle -> tap mic -> Connecting while mic starts
            onNodeWithContentDescription("Voice dictation").assertIsDisplayed().performClick()
            waitForIdle()

            val controller = assertNotNull(controllerRef)
            assertEquals(VoiceDictationV2Phase.Connecting, controller.state.value.phase)
            onNodeWithTag(ThreadVoiceDictationHaloTestTag, useUnmergedTree = true).assertExists()
            onNodeWithContentDescription("Stop dictation").assertIsDisplayed()

            // Mic starts -> IMMEDIATELY Listening (zero-wait, before permit/ready!)
            recorderGate.complete(VoiceDictationAudioStartResult.Started)
            waitForIdle()
            assertEquals(VoiceDictationV2Phase.Listening, controller.state.value.phase)
            recorder.emitAudioLevel(0.65f)
            waitForIdle()
            assertEquals(0.65f, controller.state.value.audioLevel)

            // Permit resolves & server sends `ready` + live transcript snapshot
            permitGate.complete(VoiceDictationV2PermitOutcome.Issued(samplePermit()))
            connection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            connection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "it today", stableLen = 2, refined = false))
            waitForIdle()
            assertEquals("Ship it today", changedTexts.lastOrNull())

            // Tap stop -> non-blocking: button IMMEDIATELY returns to idle (no blocking spinner) while background drains
            onNodeWithContentDescription("Stop dictation").performClick()
            waitForIdle()
            assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
            assertTrue(controller.state.value.hasBackgroundDrain)
            assertTrue(connection.stopSent)
            assertTrue(recorder.stopCount >= 1)
            onNodeWithContentDescription("Voice dictation").assertIsDisplayed().assertIsEnabled()

            // Final refined `text` + `done` still apply silently in background
            connection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "it today.", stableLen = 9, refined = true))
            connection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()

            assertEquals("Ship it today.", changedTexts.lastOrNull())
            assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
            assertFalse(controller.state.value.hasBackgroundDrain)
            onNodeWithContentDescription("Voice dictation").assertIsDisplayed()
        }

    @Test
    fun editingComposerAfterStopIgnoresLateBackgroundRefinedFrames() =
        runRobolectricComposeUiTest {
            val recorder = FakeRecorder()
            val connection = FakeStreamConnection()
            val changedTexts = mutableListOf<String>()
            var controllerRef: VoiceDictationV2Controller? = null

            setContent {
                val scope = rememberCoroutineScope()
                val controller = remember {
                    VoiceDictationV2Controller(
                        permitApi = FakePermitApi { VoiceDictationV2PermitOutcome.Issued(samplePermit()) },
                        streamConnector = FakeStreamConnector(connection),
                        scope = scope,
                        audioRecorderProvider = { recorder },
                    ).also { controllerRef = it }
                }
                val host = remember(controller) {
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
                            surface = ThreadComposerSurface(channelId = "chan-edit"),
                            state = ThreadComposerUiState(instanceId = "voice-post-stop-edit", enabled = true),
                            onEvent = { action, value, _, _ ->
                                if (action == "change" && value != null) {
                                    changedTexts += value
                                }
                            },
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = 0.dp,
                            attachmentPreviewEnabled = false,
                        )
                    }
                }
            }

            onNodeWithContentDescription("Voice dictation").performClick()
            connection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            connection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "hello", stableLen = 5, refined = false))
            waitForIdle()
            assertEquals("hello", changedTexts.lastOrNull())

            // Non-blocking stop -> Idle with background drain active
            onNodeWithContentDescription("Stop dictation").performClick()
            waitForIdle()
            assertEquals(VoiceDictationV2Phase.Idle, assertNotNull(controllerRef).state.value.phase)
            assertTrue(assertNotNull(controllerRef).state.value.hasBackgroundDrain)

            // User edits composer immediately while background drain is still open
            onNode(hasSetTextAction()).performTextInput(" world")
            waitForIdle()
            assertEquals("hello world", changedTexts.lastOrNull())

            // Late refined snapshot must be ignored and never overwrite user edit
            connection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "OVERWRITTEN TEXT", stableLen = 16, refined = true))
            connection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()

            assertEquals("hello world", changedTexts.lastOrNull())
            assertFalse(assertNotNull(controllerRef).state.value.hasBackgroundDrain)
        }

    @Test
    fun stopThenSendWaitsBrieflyForPostStopFinalBeforeSending() =
        runRobolectricComposeUiTest {
            val recorder = FakeRecorder()
            val connection = FakeStreamConnection()
            val sentMessages = mutableListOf<String>()
            var controllerRef: VoiceDictationV2Controller? = null

            setContent {
                val scope = rememberCoroutineScope()
                val controller = remember {
                    VoiceDictationV2Controller(
                        permitApi = FakePermitApi { VoiceDictationV2PermitOutcome.Issued(samplePermit()) },
                        streamConnector = FakeStreamConnector(connection),
                        scope = scope,
                        audioRecorderProvider = { recorder },
                    ).also { controllerRef = it }
                }
                val host = remember(controller) {
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
                            surface = ThreadComposerSurface(channelId = "chan-send-wait"),
                            state = ThreadComposerUiState(instanceId = "voice-send-wait", enabled = true),
                            onEvent = { action, value, _, _ ->
                                if (action == "send" && value != null) {
                                    sentMessages += value
                                }
                            },
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = 0.dp,
                            attachmentPreviewEnabled = false,
                        )
                    }
                }
            }

            onNodeWithContentDescription("Voice dictation").performClick()
            connection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            connection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Please ship", stableLen = 0, refined = false))
            waitForIdle()

            // Stop -> non-blocking idle with background drain waiting for final snapshot
            onNodeWithContentDescription("Stop dictation").performClick()
            waitForIdle()
            val controller = assertNotNull(controllerRef)
            assertTrue(controller.state.value.hasBackgroundDrain)

            // Tap Send immediately: Send waits briefly, keeps "Send" label (never "Loading"), and marks busy/disabled
            onNodeWithContentDescription("Send").assertIsEnabled().performClick()
            waitForIdle()
            assertTrue(sentMessages.isEmpty(), "send should wait briefly for pending post-stop final")
            assertTrue(controller.state.value.awaitingSubmitFinal)
            onNodeWithContentDescription("Send").assertIsNotEnabled()
            onNodeWithContentDescription("Loading").assertDoesNotExist()

            // Confirmed post-stop snapshot arrives -> composer updates and Send dispatches latest text immediately
            connection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Please ship it.", stableLen = 15, refined = false))
            waitForIdle()
            assertEquals(listOf("Please ship it."), sentMessages)
            assertFalse(controller.state.value.awaitingSubmitFinal)
            assertFalse(controller.state.value.hasBackgroundDrain)
        }

    @Test
    fun micPermissionDeniedRendersLocalizedNoticeAndCanBeDismissed() =
        runRobolectricComposeUiTest {
            val deniedRecorder = FakeRecorder(startResult = VoiceDictationAudioStartResult.PermissionDenied)

            setContent {
                val scope = rememberCoroutineScope()
                val controller = remember {
                    VoiceDictationV2Controller(
                        permitApi = FakePermitApi { VoiceDictationV2PermitOutcome.Issued(samplePermit()) },
                        streamConnector = FakeStreamConnector(FakeStreamConnection()),
                        scope = scope,
                        audioRecorderProvider = { deniedRecorder },
                    )
                }
                val host = remember(controller) {
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
                            surface = ThreadComposerSurface(channelId = "chan-perm"),
                            state = ThreadComposerUiState(instanceId = "voice-perm-denied", enabled = true),
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

            onNodeWithTag(ThreadVoiceDictationErrorTestTag, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText(
                "Raft can't use your microphone. Allow microphone access in your device settings, then try again.",
            ).assertIsDisplayed()

            onNodeWithContentDescription("Dismiss dictation notice").performClick()
            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationErrorTestTag, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun quotaExceededBusyUnavailableAndSessionLimitRenderKirbyCopyIntentsAndTraceIds() =
        runRobolectricComposeUiTest {
            assertEquals(
                RaftBannerStatus.Info,
                voiceDictationErrorBannerStatus(VoiceDictationV2Error.StreamError(VoiceDictationV2ErrorCode.SessionLimitReached)),
            )
            assertEquals(
                RaftBannerStatus.Warning,
                voiceDictationErrorBannerStatus(VoiceDictationV2Error.StreamError(VoiceDictationV2ErrorCode.Busy)),
            )
            assertEquals(
                RaftBannerStatus.Warning,
                voiceDictationErrorBannerStatus(VoiceDictationV2Error.StreamError(VoiceDictationV2ErrorCode.QuotaExceeded)),
            )
            assertEquals(
                RaftBannerStatus.Warning,
                voiceDictationErrorBannerStatus(VoiceDictationV2Error.PermitRefused(VoiceDictationV2PermitFailure.QuotaExceeded())),
            )

            val utcTodayContext =
                RaftTimeContext(
                    localOffsetMinutes = 0,
                    todayEpochDay = raftEpochDayForDate(2026, 11, 26),
                    timeFormat = RaftTimeFormat24Hour,
                )
            val timeProvider = RaftTimeContextProvider(utcTodayContext)

            setContent {
                val scope = rememberCoroutineScope()
                val quotaController = remember {
                    VoiceDictationV2Controller(
                        permitApi = FakePermitApi {
                            VoiceDictationV2PermitOutcome.Refused(
                                VoiceDictationV2PermitFailure.QuotaExceeded(
                                    usedSeconds = 7_200,
                                    limitSeconds = 7_200,
                                    resetsAtIso = "2026-11-27T00:00:00Z",
                                    traceId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
                                ),
                            )
                        },
                        streamConnector = FakeStreamConnector(FakeStreamConnection()),
                        scope = scope,
                        audioRecorderProvider = { FakeRecorder() },
                    )
                }
                val unavailableController = remember {
                    VoiceDictationV2Controller(
                        permitApi = FakePermitApi {
                            VoiceDictationV2PermitOutcome.Refused(
                                VoiceDictationV2PermitFailure.Unavailable(
                                    traceId = "11223344556677889900aabbccddeeff",
                                ),
                            )
                        },
                        streamConnector = FakeStreamConnector(FakeStreamConnection()),
                        scope = scope,
                        audioRecorderProvider = { FakeRecorder() },
                    )
                }
                val busyConnection = remember { FakeStreamConnection() }
                val busyController = remember {
                    VoiceDictationV2Controller(
                        permitApi = FakePermitApi { VoiceDictationV2PermitOutcome.Issued(samplePermit()) },
                        streamConnector = FakeStreamConnector(busyConnection),
                        scope = scope,
                        audioRecorderProvider = { FakeRecorder() },
                    )
                }
                val sessionLimitConnection = remember { FakeStreamConnection() }
                val sessionLimitController = remember {
                    VoiceDictationV2Controller(
                        permitApi = FakePermitApi { VoiceDictationV2PermitOutcome.Issued(samplePermit()) },
                        streamConnector = FakeStreamConnector(sessionLimitConnection),
                        scope = scope,
                        audioRecorderProvider = { FakeRecorder() },
                    )
                }

                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalRaftTimeContextProvider provides timeProvider) {
                        Column(Modifier.fillMaxWidth()) {
                            ThreadVoiceDictationNoticeSlot(
                                host =
                                    ThreadVoiceDictationHost(
                                        flagEnabled = true,
                                        viewerIsGuest = false,
                                        recorderAvailable = true,
                                        serverId = "srv-1",
                                        channelId = null,
                                        threadId = null,
                                        languageHint = "en",
                                        controller = quotaController,
                                    ),
                                composerEditable = true,
                            )
                            ThreadVoiceDictationNoticeSlot(
                                host =
                                    ThreadVoiceDictationHost(
                                        flagEnabled = true,
                                        viewerIsGuest = false,
                                        recorderAvailable = true,
                                        serverId = "srv-1",
                                        channelId = null,
                                        threadId = null,
                                        languageHint = "en",
                                        controller = unavailableController,
                                    ),
                                composerEditable = true,
                            )
                            ThreadVoiceDictationNoticeSlot(
                                host =
                                    ThreadVoiceDictationHost(
                                        flagEnabled = true,
                                        viewerIsGuest = false,
                                        recorderAvailable = true,
                                        serverId = "srv-1",
                                        channelId = null,
                                        threadId = null,
                                        languageHint = "en",
                                        controller = busyController,
                                    ),
                                composerEditable = true,
                            )
                            ThreadVoiceDictationNoticeSlot(
                                host =
                                    ThreadVoiceDictationHost(
                                        flagEnabled = true,
                                        viewerIsGuest = false,
                                        recorderAvailable = true,
                                        serverId = "srv-1",
                                        channelId = null,
                                        threadId = null,
                                        languageHint = "en",
                                        controller = sessionLimitController,
                                    ),
                                composerEditable = true,
                            )
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    quotaController.start(serverId = "srv-1", onComposerUpdate = {})
                    unavailableController.start(serverId = "srv-1", onComposerUpdate = {})
                    busyController.start(serverId = "srv-1", onComposerUpdate = {})
                    busyConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
                    busyConnection.emitFrame(VoiceDictationV2ServerFrame.Error(code = VoiceDictationV2ErrorCode.Busy))
                    sessionLimitController.start(serverId = "srv-1", onComposerUpdate = {})
                    sessionLimitConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
                    sessionLimitConnection.emitFrame(
                        VoiceDictationV2ServerFrame.Error(code = VoiceDictationV2ErrorCode.SessionLimitReached),
                    )
                }
            }

            waitForIdle()

            onNodeWithText("Today's voice dictation limit is reached. Back tomorrow at 00:00.")
                .assertIsDisplayed()
            onNodeWithText("Voice dictation isn't available right now. Try again in a moment.")
                .assertIsDisplayed()
            onNodeWithText("Voice dictation is busy right now. Try again in a moment.")
                .assertIsDisplayed()
            onNodeWithText("This dictation hit its length limit. Everything you said is kept — tap the mic to continue.")
                .assertIsDisplayed()
            onNodeWithText(raftShortTraceId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6")).assertIsDisplayed()
            onNodeWithText(raftShortTraceId("11223344556677889900aabbccddeeff")).assertIsDisplayed()
        }

    @Test
    fun quotaExceededDisablesMicEvenAfterDismissWhileSessionLimitAndBusyKeepMicEnabled() =
        runRobolectricComposeUiTest {
            val beforeResetMs = 1_795_700_000_000L // 2026-11-26 before 2026-11-27T00:00:00Z
            val utcTodayContext =
                RaftTimeContext(
                    localOffsetMinutes = 0,
                    todayEpochDay = raftEpochDayForDate(2026, 11, 26),
                    timeFormat = RaftTimeFormat24Hour,
                )
            val timeProvider = RaftTimeContextProvider(utcTodayContext)

            setContent {
                val scope = rememberCoroutineScope()
                val controller = remember {
                    VoiceDictationV2Controller(
                        permitApi = FakePermitApi {
                            VoiceDictationV2PermitOutcome.Refused(
                                VoiceDictationV2PermitFailure.QuotaExceeded(
                                    usedSeconds = 7_200,
                                    limitSeconds = 7_200,
                                    resetsAtIso = "2026-11-27T00:00:00Z",
                                ),
                            )
                        },
                        streamConnector = FakeStreamConnector(FakeStreamConnection()),
                        scope = scope,
                        audioRecorderProvider = { FakeRecorder() },
                        nowMillis = { beforeResetMs },
                    )
                }
                val host = remember(controller) {
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
                    CompositionLocalProvider(
                        LocalRaftTimeContextProvider provides timeProvider,
                        LocalThreadVoiceDictationHost provides host,
                    ) {
                        ThreadComposerContent(
                            surface = ThreadComposerSurface(channelId = "chan-quota-lock"),
                            state = ThreadComposerUiState(instanceId = "voice-quota-lock", enabled = true),
                            onEvent = { _, _, _, _ -> },
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = 0.dp,
                            attachmentPreviewEnabled = false,
                        )
                    }
                }
            }

            // Initially enabled -> tap triggers QuotaExceeded -> error banner shown + Mic disabled with reset hint
            onNodeWithTag(ThreadVoiceDictationButtonTestTag).assertIsEnabled().performClick()
            waitForIdle()
            onNodeWithText("Today's voice dictation limit is reached. Back tomorrow at 00:00.").assertIsDisplayed()
            onNodeWithTag(ThreadVoiceDictationButtonTestTag)
                .assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Back tomorrow at 00:00."))

            // Dismissing error banner keeps Mic disabled with reset-time hint!
            onNodeWithContentDescription("Dismiss dictation notice").performClick()
            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationErrorTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(ThreadVoiceDictationButtonTestTag)
                .assertIsNotEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Back tomorrow at 00:00."))
        }

    @Test
    fun lowQuotaBannerFormatsRoundedMinutesUnderMinuteAndSuppressesDegradedFailOpen() =
        runRobolectricComposeUiTest {
            // Pure helper & fail-open guard assertions
            val degradedQuota =
                VoiceDictationV2Quota(
                    usedSeconds = 0,
                    limitSeconds = 7_200,
                    remainingSeconds = 180,
                    resetsAtIso = "2026-11-27T00:00:00Z",
                )
            assertFalse(isVoiceDictationV2QuotaLow(degradedQuota, quotaDegraded = true))
            assertFalse(
                isVoiceDictationV2QuotaLow(degradedQuota, quotaDegraded = false),
                "fail-open signature (used=0, remaining<=180, limit>remaining) must never warn as low quota",
            )
            assertTrue(
                isVoiceDictationV2QuotaLow(
                    VoiceDictationV2Quota(usedSeconds = 6_900, limitSeconds = 7_200, remainingSeconds = 300, resetsAtIso = "2026-11-27T00:00:00Z"),
                    quotaDegraded = false,
                ),
            )
            assertNull(voiceDictationLowQuotaRemainingMinutes(45))
            assertEquals(1, voiceDictationLowQuotaRemainingMinutes(60))
            assertEquals(1, voiceDictationLowQuotaRemainingMinutes(89))
            assertEquals(2, voiceDictationLowQuotaRemainingMinutes(90))
            assertEquals(5, voiceDictationLowQuotaRemainingMinutes(300))

            val utcTodayContext =
                RaftTimeContext(
                    localOffsetMinutes = 0,
                    todayEpochDay = raftEpochDayForDate(2026, 11, 26),
                    timeFormat = RaftTimeFormat24Hour,
                )
            val timeProvider = RaftTimeContextProvider(utcTodayContext)

            setContent {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalRaftTimeContextProvider provides timeProvider) {
                        Column(Modifier.fillMaxWidth()) {
                            ThreadVoiceDictationNotice(
                                error = null,
                                quota = VoiceDictationV2Quota(6_900, 7_200, 300, "2026-11-27T00:00:00Z"),
                                showLowQuota = true,
                                onDismissError = {},
                                onDismissLowQuota = {},
                            )
                            ThreadVoiceDictationNotice(
                                error = null,
                                quota = VoiceDictationV2Quota(7_140, 7_200, 60, "2026-11-27T00:00:00Z"),
                                showLowQuota = true,
                                onDismissError = {},
                                onDismissLowQuota = {},
                            )
                            ThreadVoiceDictationNotice(
                                error = null,
                                quota = VoiceDictationV2Quota(7_155, 7_200, 45, "2026-11-27T00:00:00Z"),
                                showLowQuota = true,
                                onDismissError = {},
                                onDismissLowQuota = {},
                            )
                            ThreadVoiceDictationNotice(
                                error = null,
                                quota = degradedQuota,
                                showLowQuota = isVoiceDictationV2QuotaLow(degradedQuota, quotaDegraded = false),
                                onDismissError = {},
                                onDismissLowQuota = {},
                            )
                        }
                    }
                }
            }

            waitForIdle()
            onNodeWithText("About 5 minutes of voice dictation left today. Back tomorrow at 00:00.").assertIsDisplayed()
            onNodeWithText("About 1 minute of voice dictation left today. Back tomorrow at 00:00.").assertIsDisplayed()
            onNodeWithText("Less than 1 minute of voice dictation left today. Back tomorrow at 00:00.").assertIsDisplayed()
            onNodeWithText("About 3 minutes of voice dictation left today. Back tomorrow at 00:00.").assertDoesNotExist()
        }

    @Test
    fun holdToTalkPillRendersOnlyWhileHoldingAndReducedMotionHaloUsesFixedValues() =
        runRobolectricComposeUiTest {
            assertEquals("0:00", formatVoiceDictationClock(0L))
            assertEquals("0:04", formatVoiceDictationClock(4_900L))
            assertEquals("1:23", formatVoiceDictationClock(83_000L))
            assertEquals(1.25f, voiceDictationHaloScale(0.9f, reducedMotion = true))
            assertEquals(0.35f, voiceDictationHaloAlpha(0.9f, reducedMotion = true))

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        ThreadVoiceDictationButton(
                            state = VoiceDictationV2State(phase = VoiceDictationV2Phase.Listening, audioLevel = 0.4f),
                            enabled = true,
                            onStart = {},
                            onStop = {},
                            gestureModeOverride = VoiceDictationGestureMode.Holding,
                            elapsedMsOverride = 4_200L,
                        )
                    }
                }
            }

            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationHoldPillTestTag, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Release to stop").assertIsDisplayed()
            onNodeWithText("0:04").assertIsDisplayed()
        }

    @Test
    fun lockedModeNeverRendersHoldToTalkPillAndSessionLimitKeepsMicEnabled() =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        ThreadVoiceDictationButton(
                            state = VoiceDictationV2State(phase = VoiceDictationV2Phase.Listening),
                            enabled = true,
                            onStart = {},
                            onStop = {},
                            gestureModeOverride = VoiceDictationGestureMode.Locked,
                        )
                        ThreadVoiceDictationButton(
                            state =
                                VoiceDictationV2State(
                                    phase = VoiceDictationV2Phase.Idle,
                                    error = VoiceDictationV2Error.StreamError(VoiceDictationV2ErrorCode.SessionLimitReached),
                                ),
                            enabled = true,
                            onStart = {},
                            onStop = {},
                        )
                    }
                }
            }

            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationHoldPillTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithContentDescription("Voice dictation").assertIsDisplayed().assertIsEnabled()
        }

    @Test
    fun allEightLocalesResolveVoiceDictationStringsCleanly() {
        val allLocales =
            listOf(
                AppLocale.English,
                AppLocale.SimplifiedChinese,
                AppLocale.TraditionalChinese,
                AppLocale.Japanese,
                AppLocale.Korean,
                AppLocale.Spanish,
                AppLocale.BrazilianPortuguese,
                AppLocale.Russian,
            )
        val plainKeys =
            listOf(
                Res.string.thread_voice_dictation_start,
                Res.string.thread_voice_dictation_stop,
                Res.string.thread_voice_dictation_connecting,
                Res.string.thread_voice_dictation_listening,
                Res.string.thread_voice_dictation_release_to_stop,
                Res.string.thread_voice_dictation_dismiss_notice,
                Res.string.thread_voice_dictation_low_quota_under_minute_no_reset,
                Res.string.thread_voice_dictation_quota_exhausted_tooltip_no_reset,
                Res.string.thread_voice_dictation_error_mic_permission,
                Res.string.thread_voice_dictation_error_mic_none,
                Res.string.thread_voice_dictation_error_quota_exceeded_no_reset,
                Res.string.thread_voice_dictation_error_rate_limited_no_seconds,
                Res.string.thread_voice_dictation_error_busy,
                Res.string.thread_voice_dictation_error_unavailable,
                Res.string.thread_voice_dictation_error_disabled,
                Res.string.thread_voice_dictation_error_session_limit,
                Res.string.thread_voice_dictation_error_connection_lost,
                Res.string.thread_voice_dictation_error_failed,
            )
        val timeFormattedKeys =
            listOf(
                Res.string.thread_voice_dictation_low_quota_under_minute,
                Res.string.thread_voice_dictation_low_quota_under_minute_tomorrow,
                Res.string.thread_voice_dictation_quota_exhausted_tooltip,
                Res.string.thread_voice_dictation_quota_exhausted_tooltip_tomorrow,
                Res.string.thread_voice_dictation_error_quota_exceeded,
                Res.string.thread_voice_dictation_error_quota_exceeded_tomorrow,
            )
        val minuteOnlyKeys =
            listOf(
                Res.string.thread_voice_dictation_low_quota_no_reset_one to 1,
                Res.string.thread_voice_dictation_low_quota_no_reset_other to 5,
            )
        val minuteAndTimeKeys =
            listOf(
                Res.string.thread_voice_dictation_low_quota_one to 1,
                Res.string.thread_voice_dictation_low_quota_other to 5,
                Res.string.thread_voice_dictation_low_quota_tomorrow_one to 1,
                Res.string.thread_voice_dictation_low_quota_tomorrow_other to 5,
            )

        for (locale in allLocales) {
            StringDesc.localeType = locale.stringDescLocaleType
            for (res in plainKeys) {
                val resolved = res.desc().resolve()
                assertTrue(resolved.isNotBlank(), "blank string for ${locale.languageTag}")
                assertFalse('%' in resolved, "unexpected placeholder in ${locale.languageTag}: $resolved")
            }
            for (res in timeFormattedKeys) {
                val resolved = res.format("08:00").resolve()
                assertTrue("08:00" in resolved, "missing time arg in ${locale.languageTag}: $resolved")
                assertFalse('%' in resolved, "unsubstituted placeholder in ${locale.languageTag}: $resolved")
            }
            for ((res, mins) in minuteOnlyKeys) {
                val resolved = res.format(mins).resolve()
                assertTrue(mins.toString() in resolved, "missing minutes arg in ${locale.languageTag}: $resolved")
                assertFalse('%' in resolved, "unsubstituted placeholder in ${locale.languageTag}: $resolved")
            }
            for ((res, mins) in minuteAndTimeKeys) {
                val resolved = res.format(mins, "08:00").resolve()
                assertTrue(mins.toString() in resolved, "missing minutes arg in ${locale.languageTag}: $resolved")
                assertTrue("08:00" in resolved, "missing time arg in ${locale.languageTag}: $resolved")
                assertFalse('%' in resolved, "unsubstituted placeholder in ${locale.languageTag}: $resolved")
            }
            val rateLimited = Res.string.thread_voice_dictation_error_rate_limited.format(30).resolve()
            assertTrue("30" in rateLimited, "missing seconds arg in ${locale.languageTag}: $rateLimited")
            assertFalse('%' in rateLimited, "unsubstituted placeholder in ${locale.languageTag}: $rateLimited")
        }

        // Spot-check exact Kirby English & zh-CN copy parity.
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        assertEquals("Voice dictation", Res.string.thread_voice_dictation_start.desc().resolve())
        assertEquals("Release to stop", Res.string.thread_voice_dictation_release_to_stop.desc().resolve())
        assertEquals(
            "This dictation hit its length limit. Everything you said is kept — tap the mic to continue.",
            Res.string.thread_voice_dictation_error_session_limit.desc().resolve(),
        )
        assertEquals(
            "Voice dictation is busy right now. Try again in a moment.",
            Res.string.thread_voice_dictation_error_busy.desc().resolve(),
        )
        assertEquals(
            "Today's voice dictation limit is reached. Back at 08:00.",
            Res.string.thread_voice_dictation_error_quota_exceeded.format("08:00").resolve(),
        )

        StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
        assertEquals("语音输入", Res.string.thread_voice_dictation_start.desc().resolve())
        assertEquals("停止语音输入", Res.string.thread_voice_dictation_stop.desc().resolve())
        assertEquals("正在语音输入", Res.string.thread_voice_dictation_listening.desc().resolve())
        assertEquals("松手结束", Res.string.thread_voice_dictation_release_to_stop.desc().resolve())
        assertEquals(
            "这次语音输入到上限了，说过的话都在，点麦克风继续。",
            Res.string.thread_voice_dictation_error_session_limit.desc().resolve(),
        )
        assertEquals(
            "语音输入现在有点忙，请稍后再试。",
            Res.string.thread_voice_dictation_error_busy.desc().resolve(),
        )
        assertEquals(
            "今天的语音输入额度已用完，08:00 恢复。",
            Res.string.thread_voice_dictation_error_quota_exceeded.format("08:00").resolve(),
        )
        assertEquals(
            "今天的语音输入还剩约 5 分钟，08:00 恢复。",
            Res.string.thread_voice_dictation_low_quota_other.format(5, "08:00").resolve(),
        )
        assertEquals(
            "今天的语音输入还剩不到 1 分钟，08:00 恢复。",
            Res.string.thread_voice_dictation_low_quota_under_minute.format("08:00").resolve(),
        )
    }

    private fun samplePermit(): VoiceDictationV2Permit =
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
        )

    private class FakePermitApi(
        private val handler: suspend () -> VoiceDictationV2PermitOutcome,
    ) : VoiceDictationV2PermitApi {
        override suspend fun requestPermit(
            serverId: String,
            language: String?,
            channelId: String?,
        ): VoiceDictationV2PermitOutcome = handler()
    }

    private class FakeStreamConnector(
        private val connection: FakeStreamConnection,
    ) : VoiceDictationV2StreamConnector {
        override suspend fun connect(url: String, permit: String): VoiceDictationV2StreamConnection = connection
    }

    private class FakeStreamConnection : VoiceDictationV2StreamConnection {
        private val channel = Channel<VoiceDictationV2StreamEvent>(Channel.UNLIMITED)
        var startSent = false
        var stopSent = false
        var closed = false

        override val events: Flow<VoiceDictationV2StreamEvent> = channel.receiveAsFlow()

        override suspend fun sendStart(sampleRateHz: Int): Boolean {
            startSent = true
            return true
        }

        override suspend fun sendAudio(pcm16LeMonoBytes: ByteArray): Boolean = true

        override suspend fun sendStop(): Boolean {
            stopSent = true
            return true
        }

        override suspend fun close(code: Int, reason: String) {
            closed = true
            channel.close()
        }

        fun emitFrame(frame: VoiceDictationV2ServerFrame) {
            channel.trySend(VoiceDictationV2StreamEvent.Frame(frame))
        }
    }

    private class FakeRecorder(
        private val startResult: VoiceDictationAudioStartResult = VoiceDictationAudioStartResult.Started,
        private val startGate: CompletableDeferred<VoiceDictationAudioStartResult>? = null,
    ) : RaftVoiceDictationAudioRecorder {
        private var audioLevelCallback: ((Float) -> Unit)? = null
        var stopCount = 0

        override suspend fun start(
            onPcmChunk: (ByteArray) -> Unit,
            onAudioLevel: (Float) -> Unit,
        ): VoiceDictationAudioStartResult {
            val resolved = startGate?.await() ?: startResult
            if (resolved is VoiceDictationAudioStartResult.Started) {
                audioLevelCallback = onAudioLevel
            }
            return resolved
        }

        override suspend fun stop() {
            stopCount += 1
            audioLevelCallback = null
        }

        fun emitAudioLevel(level: Float) {
            audioLevelCallback?.invoke(level)
        }
    }
}
