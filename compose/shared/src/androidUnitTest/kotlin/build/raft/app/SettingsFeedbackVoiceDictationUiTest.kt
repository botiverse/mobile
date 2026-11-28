@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.FeedbackAuthorType
import build.raft.app.data.FeedbackComment
import build.raft.app.data.FeedbackTicket
import build.raft.app.data.FeedbackTicketDetail
import build.raft.app.data.FeedbackTicketDetailState
import build.raft.app.data.FeedbackTicketKind
import build.raft.app.data.FeedbackTicketStatus
import build.raft.app.data.FeedbackTicketsListPhase
import build.raft.app.data.FeedbackTicketsListState
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.RaftVoiceDictationAudioRecorder
import build.raft.app.data.SettingsFeedbackTicketsState
import build.raft.app.data.SettingsFeedbackType
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.VOICE_DICTATION_V1_FEATURE_FLAG_KEY
import build.raft.app.data.VoiceDictationAudioStartResult
import build.raft.app.data.VoiceDictationSessionMode
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
import build.raft.app.data.stringDescLocaleType
import build.raft.app.format.RaftFormat
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.resources.StringDesc
import build.raft.app.settings.SettingsFeedbackTicketsActions
import build.raft.app.settings.SettingsFeedbackTicketsScreen
import build.raft.app.settings.SettingsHandsFeedbackComposer
import build.raft.app.settings.SettingsHandsFeedbackForm
import build.raft.app.settings.settingsFeedbackImageMaxBytes
import build.raft.app.settings.settingsHandsFeedbackCopy
import build.raft.app.thread.LocalThreadVoiceDictationHost
import build.raft.app.thread.ThreadVoiceDictationButtonTestTag
import build.raft.app.thread.ThreadVoiceDictationErrorTestTag
import build.raft.app.thread.VoiceDictationProvisionalRangeSemanticsKey
import build.raft.app.thread.rememberComposerVoiceDictationHost
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
class SettingsFeedbackVoiceDictationUiTest {
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
    fun micButtonHiddenWhenFlagOffGuestOrMissingRecorderAndShownReactivelyWhenFlagOn() =
        runRobolectricComposeUiTest {
            val serverId = "srv-feedback-1"
            val offFlags = SharedServerFeatureFlags(api = FakeFeatureFlagsApi(voiceDictationEnabled = false))
            val onFlags = SharedServerFeatureFlags(api = FakeFeatureFlagsApi(voiceDictationEnabled = true))
            val memberSession = sampleSession(serverId = serverId, role = "member")
            val guestSession = sampleSession(serverId = serverId, role = "guest")
            val permitApi = FakePermitApi { _, _ -> VoiceDictationV2PermitOutcome.Issued(samplePermit()) }
            val streamConnector = FakeStreamConnector(FakeStreamConnection())
            val recorder = FakeRecorder()

            var activeFlags by mutableStateOf(offFlags)
            var activeSession by mutableStateOf(memberSession)
            var recorderInstalled by mutableStateOf(true)
            val recorderProvider: () -> RaftVoiceDictationAudioRecorder? = {
                if (recorderInstalled) recorder else null
            }

            setContent {
                FeedbackVoiceHostShell {
                    val host =
                        rememberComposerVoiceDictationHost(
                            serverFeatureFlags = activeFlags,
                            permitApi = permitApi,
                            active = true,
                            currentSession = activeSession,
                            serverId = serverId,
                            prefetchIfUnresolved = true,
                            streamConnector = streamConnector,
                            audioRecorderProvider = recorderProvider,
                        )
                    CompositionLocalProvider(LocalThreadVoiceDictationHost provides host) {
                        NewFeedbackFormSurface(instanceId = "feedback-voice-gate")
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000L) { offFlags.snapshotFor(ServerId(serverId)).resolved }
            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true).assertDoesNotExist()

            // Switch to a server where `voice_dictation_v1` evaluates ON -> reactive prefetch shows the mic.
            runOnIdle { activeFlags = onFlags }
            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            onNodeWithContentDescription("Voice dictation").assertIsDisplayed().assertIsEnabled()

            // Guest members must never see the mic button even when the flag is ON.
            runOnIdle { activeSession = guestSession }
            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true).assertDoesNotExist()

            // Restore non-guest member, then simulate a platform without a registered audio recorder.
            runOnIdle {
                activeSession = memberSession
                recorderInstalled = false
            }
            waitForIdle()
            onNodeWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun dictationCommitsIntoEmptyFeedbackAndOmitsChannelIdOnPermit() =
        runRobolectricComposeUiTest {
            val serverId = "srv-feedback-dictate"
            val flags = SharedServerFeatureFlags(api = FakeFeatureFlagsApi(voiceDictationEnabled = true))
            val recorder = FakeRecorder()
            val connection = FakeStreamConnection()
            val permitRequests = mutableListOf<Pair<String, String?>>()
            val permitApi =
                FakePermitApi { reqServerId, reqChannelId ->
                    permitRequests += reqServerId to reqChannelId
                    VoiceDictationV2PermitOutcome.Issued(samplePermit())
                }
            val streamConnector = FakeStreamConnector(connection)
            val recorderProvider: () -> RaftVoiceDictationAudioRecorder? = { recorder }
            val session = sampleSession(serverId = serverId)
            val changedTexts = mutableListOf<String>()
            var editorOwnerRef: ThreadComposerEditorOwner? = null

            setContent {
                FeedbackVoiceHostShell {
                    val host =
                        rememberComposerVoiceDictationHost(
                            serverFeatureFlags = flags,
                            permitApi = permitApi,
                            active = true,
                            currentSession = session,
                            serverId = serverId,
                            prefetchIfUnresolved = true,
                            streamConnector = streamConnector,
                            audioRecorderProvider = recorderProvider,
                        )
                    CompositionLocalProvider(LocalThreadVoiceDictationHost provides host) {
                        NewFeedbackFormSurface(
                            instanceId = "feedback-voice-empty",
                            seedText = "",
                            onEditorReady = { editorOwnerRef = it },
                            onChange = { changedTexts += it },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()

            // Feedback forms have no chat channel ID; permit request must pass serverId and omit channelId.
            assertEquals(listOf<Pair<String, String?>>(serverId to null), permitRequests)
            assertEquals(VoiceDictationSessionMode.Tap, connection.lastStartMode)
            connection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            connection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "New feedback mic works", stableLen = 22, refined = false))
            waitForIdle()

            assertEquals("New feedback mic works", assertNotNull(editorOwnerRef).text)
            assertEquals("New feedback mic works", changedTexts.lastOrNull())

            onNodeWithContentDescription("Stop dictation").performClick()
            waitForIdle()
            connection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "New feedback mic works.", stableLen = 23, refined = true))
            connection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()

            assertEquals("New feedback mic works.", assertNotNull(editorOwnerRef).text)
            assertEquals("New feedback mic works.", changedTexts.lastOrNull())
            assertTrue(recorder.stopCount >= 1)
        }

    @Test
    fun dictationAppendsToExistingAsciiAndCjkFeedbackDraftsWithoutCorruptingSpacing() =
        runRobolectricComposeUiTest {
            val serverId = "srv-feedback-append"
            val flags = SharedServerFeatureFlags(api = FakeFeatureFlagsApi(voiceDictationEnabled = true))
            val recorder = FakeRecorder()
            val asciiConnection = FakeStreamConnection()
            val cjkConnection = FakeStreamConnection()
            var connectionIndex = 0
            val streamConnector =
                object : VoiceDictationV2StreamConnector {
                    override suspend fun connect(url: String, permit: String): VoiceDictationV2StreamConnection {
                        val next = if (connectionIndex == 0) asciiConnection else cjkConnection
                        connectionIndex += 1
                        return next
                    }
                }
            val permitApi = FakePermitApi { _, _ -> VoiceDictationV2PermitOutcome.Issued(samplePermit()) }
            val recorderProvider: () -> RaftVoiceDictationAudioRecorder? = { recorder }
            val session = sampleSession(serverId = serverId)
            var useCjk by mutableStateOf(false)
            val changedTexts = mutableListOf<String>()
            var editorOwnerRef: ThreadComposerEditorOwner? = null

            setContent {
                FeedbackVoiceHostShell {
                    val host =
                        rememberComposerVoiceDictationHost(
                            serverFeatureFlags = flags,
                            permitApi = permitApi,
                            active = true,
                            currentSession = session,
                            serverId = serverId,
                            prefetchIfUnresolved = true,
                            streamConnector = streamConnector,
                            audioRecorderProvider = recorderProvider,
                        )
                    CompositionLocalProvider(LocalThreadVoiceDictationHost provides host) {
                        NewFeedbackFormSurface(
                            instanceId = if (useCjk) "feedback-voice-cjk" else "feedback-voice-ascii",
                            seedText = if (useCjk) "新建反馈" else "Repro:",
                            onEditorReady = { editorOwnerRef = it },
                            onChange = { changedTexts += it },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            asciiConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            asciiConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "open settings", stableLen = 13, refined = true))
            asciiConnection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()

            assertEquals("Repro: open settings", assertNotNull(editorOwnerRef).text)
            assertEquals("Repro: open settings", changedTexts.lastOrNull())

            // CJK boundary appends without inserting an extraneous ASCII space.
            runOnIdle { useCjk = true }
            waitForIdle()
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            cjkConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            cjkConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "支持语音输入", stableLen = 6, refined = true))
            cjkConnection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()

            assertEquals("新建反馈支持语音输入", assertNotNull(editorOwnerRef).text)
            assertEquals("新建反馈支持语音输入", changedTexts.lastOrNull())
        }

    @Test
    fun submittingFeedbackStopsActiveDictationAndWaitsForPostStopFinalBeforeSending() =
        runRobolectricComposeUiTest {
            val serverId = "srv-feedback-submit"
            val flags = SharedServerFeatureFlags(api = FakeFeatureFlagsApi(voiceDictationEnabled = true))
            val recorder = FakeRecorder()
            val firstConnection = FakeStreamConnection()
            val secondConnection = FakeStreamConnection()
            var connectionIndex = 0
            val streamConnector =
                object : VoiceDictationV2StreamConnector {
                    override suspend fun connect(url: String, permit: String): VoiceDictationV2StreamConnection {
                        val next = if (connectionIndex == 0) firstConnection else secondConnection
                        connectionIndex += 1
                        return next
                    }
                }
            val permitApi = FakePermitApi { _, _ -> VoiceDictationV2PermitOutcome.Issued(samplePermit()) }
            val recorderProvider: () -> RaftVoiceDictationAudioRecorder? = { recorder }
            val session = sampleSession(serverId = serverId)
            val sentMessages = mutableListOf<String>()
            var controllerRef: VoiceDictationV2Controller? = null
            var editorOwnerRef: ThreadComposerEditorOwner? = null

            setContent {
                FeedbackVoiceHostShell {
                    val host =
                        rememberComposerVoiceDictationHost(
                            serverFeatureFlags = flags,
                            permitApi = permitApi,
                            active = true,
                            currentSession = session,
                            serverId = serverId,
                            prefetchIfUnresolved = true,
                            streamConnector = streamConnector,
                            audioRecorderProvider = recorderProvider,
                        ).also { controllerRef = it.controller }
                    CompositionLocalProvider(LocalThreadVoiceDictationHost provides host) {
                        NewFeedbackFormSurface(
                            instanceId = "feedback-voice-submit",
                            onEditorReady = { editorOwnerRef = it },
                            onSend = { submitted ->
                                sentMessages += submitted
                                editorOwnerRef?.applyProgrammaticText(
                                    sourceSessionId = editorOwnerRef!!.editorSessionId,
                                    nextText = "",
                                    cursor = 0,
                                    publishAsSynced = true,
                                )
                            },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            // Case 1: Tapping Send while actively listening stops/releases the mic (0 ms post-roll),
            // waits <= 1 s for the final post-stop snapshot, and submits the complete text.
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            val controller = assertNotNull(controllerRef)
            assertEquals(VoiceDictationV2Phase.Listening, controller.state.value.phase)
            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Missing mic in feedback", stableLen = 23, refined = false))
            waitForIdle()

            onNodeWithContentDescription("Send").assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
            assertTrue(controller.state.value.hasBackgroundDrain)
            assertTrue(controller.state.value.awaitingSubmitFinal)
            assertTrue(recorder.stopCount >= 1)
            assertTrue(firstConnection.stopSent)
            assertTrue(sentMessages.isEmpty(), "Send while active should wait briefly for post-stop final")
            onNodeWithContentDescription("Send").assertIsNotEnabled()

            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Missing mic in feedback.", stableLen = 24, refined = false))
            waitForIdle()

            assertEquals(listOf("Missing mic in feedback."), sentMessages)
            assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
            assertFalse(controller.state.value.hasBackgroundDrain)
            assertFalse(controller.state.value.awaitingSubmitFinal)
            assertTrue(firstConnection.closed)

            // Late frame after submit must never pollute the cleared composer.
            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "STALE LATE TEXT", stableLen = 15, refined = true))
            waitForIdle()
            assertEquals("", assertNotNull(editorOwnerRef).text)

            // Case 2: Stop followed by Send while background refine is in flight sends visible text
            // immediately (WYSIWYG), clears the retained post-stop composing underline, and ignores late refine.
            val hasComposingRange = SemanticsMatcher.keyIsDefined(VoiceDictationProvisionalRangeSemanticsKey)
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            assertEquals(VoiceDictationSessionMode.Tap, secondConnection.lastStartMode)
            secondConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            secondConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Add voice button", stableLen = 0, refined = false))
            waitForIdle()
            onNode(hasComposingRange, useUnmergedTree = true)
                .assertExists()
                .assert(SemanticsMatcher.expectValue(VoiceDictationProvisionalRangeSemanticsKey, TextRange(0, 16)))

            onNodeWithContentDescription("Stop dictation").performClick()
            waitForIdle()
            assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
            assertTrue(controller.state.value.hasBackgroundDrain)
            onNode(hasComposingRange, useUnmergedTree = true)
                .assertExists()
                .assert(SemanticsMatcher.expectValue(VoiceDictationProvisionalRangeSemanticsKey, TextRange(0, 16)))

            onNodeWithContentDescription("Send").assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(listOf("Missing mic in feedback.", "Add voice button"), sentMessages)
            assertFalse(controller.state.value.awaitingSubmitFinal)
            assertFalse(controller.state.value.hasBackgroundDrain)
            assertNull(controller.state.value.provisionalRange)
            onNode(hasComposingRange, useUnmergedTree = true).assertDoesNotExist()
            assertTrue(secondConnection.closed)

            // Late refined frame after WYSIWYG submit must never repopulate the cleared feedback composer.
            secondConnection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Add voice button.", stableLen = 17, refined = true))
            waitForIdle()
            assertEquals("", assertNotNull(editorOwnerRef).text)
        }

    @Test
    fun leavingOrDisposingFeedbackStopsActiveDictationAndReleasesMic() =
        runRobolectricComposeUiTest {
            val serverId = "srv-feedback-dispose"
            val flags = SharedServerFeatureFlags(api = FakeFeatureFlagsApi(voiceDictationEnabled = true))
            val recorder = FakeRecorder()
            val firstConnection = FakeStreamConnection()
            val secondConnection = FakeStreamConnection()
            var connectionIndex = 0
            val streamConnector =
                object : VoiceDictationV2StreamConnector {
                    override suspend fun connect(url: String, permit: String): VoiceDictationV2StreamConnection {
                        val next = if (connectionIndex == 0) firstConnection else secondConnection
                        connectionIndex += 1
                        return next
                    }
                }
            val permitApi = FakePermitApi { _, _ -> VoiceDictationV2PermitOutcome.Issued(samplePermit()) }
            val recorderProvider: () -> RaftVoiceDictationAudioRecorder? = { recorder }
            val session = sampleSession(serverId = serverId)
            var active by mutableStateOf(true)
            var mounted by mutableStateOf(true)
            var controllerRef: VoiceDictationV2Controller? = null

            setContent {
                FeedbackVoiceHostShell {
                    if (mounted) {
                        val host =
                            rememberComposerVoiceDictationHost(
                                serverFeatureFlags = flags,
                                permitApi = permitApi,
                                active = active,
                                currentSession = session,
                                serverId = serverId,
                                prefetchIfUnresolved = true,
                                streamConnector = streamConnector,
                                audioRecorderProvider = recorderProvider,
                            ).also { controllerRef = it.controller }
                        CompositionLocalProvider(LocalThreadVoiceDictationHost provides host) {
                            NewFeedbackFormSurface(instanceId = "feedback-voice-lifecycle")
                        }
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            // Deactivating the page (e.g. backgrounding or opening attachment picker) stops dictation.
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            val controller = assertNotNull(controllerRef)
            assertEquals(VoiceDictationV2Phase.Listening, controller.state.value.phase)
            firstConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            waitForIdle()

            runOnIdle { active = false }
            waitForIdle()
            assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
            assertTrue(recorder.stopCount >= 1)
            assertTrue(firstConnection.closed)

            // Re-activate, start again, then unmount/dispose the form -> DisposableEffect releases mic & WS.
            val stopsBeforeDispose = recorder.stopCount
            runOnIdle { active = true }
            waitForIdle()
            onNodeWithContentDescription("Voice dictation").performClick()
            waitForIdle()
            assertEquals(VoiceDictationV2Phase.Listening, controller.state.value.phase)
            secondConnection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            waitForIdle()

            runOnIdle { mounted = false }
            waitForIdle()
            assertEquals(VoiceDictationV2Phase.Idle, controller.state.value.phase)
            assertTrue(recorder.stopCount > stopsBeforeDispose)
            assertTrue(secondConnection.closed)
        }

    @Test
    fun micPermissionDeniedRendersDismissibleNoticeAboveFeedbackComposer() =
        runRobolectricComposeUiTest {
            val serverId = "srv-feedback-perm"
            val flags = SharedServerFeatureFlags(api = FakeFeatureFlagsApi(voiceDictationEnabled = true))
            val deniedRecorder = FakeRecorder(startResult = VoiceDictationAudioStartResult.PermissionDenied)
            val permitApi = FakePermitApi { _, _ -> VoiceDictationV2PermitOutcome.Issued(samplePermit()) }
            val streamConnector = FakeStreamConnector(FakeStreamConnection())
            val recorderProvider: () -> RaftVoiceDictationAudioRecorder? = { deniedRecorder }
            val session = sampleSession(serverId = serverId)

            setContent {
                FeedbackVoiceHostShell {
                    val host =
                        rememberComposerVoiceDictationHost(
                            serverFeatureFlags = flags,
                            permitApi = permitApi,
                            active = true,
                            currentSession = session,
                            serverId = serverId,
                            prefetchIfUnresolved = true,
                            streamConnector = streamConnector,
                            audioRecorderProvider = recorderProvider,
                        )
                    CompositionLocalProvider(LocalThreadVoiceDictationHost provides host) {
                        NewFeedbackFormSurface(instanceId = "feedback-voice-perm")
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
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
    fun ticketReplyComposerSupportsVoiceDictationWhenFlagEnabled() =
        runRobolectricComposeUiTest {
            val serverId = "srv-feedback-reply"
            val flags = SharedServerFeatureFlags(api = FakeFeatureFlagsApi(voiceDictationEnabled = true))
            val recorder = FakeRecorder()
            val connection = FakeStreamConnection()
            val permitApi = FakePermitApi { _, _ -> VoiceDictationV2PermitOutcome.Issued(samplePermit()) }
            val streamConnector = FakeStreamConnector(connection)
            val recorderProvider: () -> RaftVoiceDictationAudioRecorder? = { recorder }
            val session = sampleSession(serverId = serverId)
            val now = 1_762_700_000_000L
            val ticket =
                FeedbackTicket(
                    id = "11111111-2222-4333-8444-555555555555",
                    kind = FeedbackTicketKind.Feedback,
                    status = FeedbackTicketStatus.Open,
                    closureReason = null,
                    message = "Add voice button to feedback",
                    createdAt = now - 3_600_000L,
                    updatedAt = now - 600_000L,
                    unread = false,
                    unreadCount = 0,
                    attachmentCount = 0,
                    commentCount = 1,
                    latestComment = null,
                )
            val detail =
                FeedbackTicketDetail(
                    ticket = ticket,
                    comments = listOf(FeedbackComment("c-1", FeedbackAuthorType.Staff, "Thanks!", now - 1_000L)),
                    attachments = emptyList(),
                    nextCommentCursor = null,
                    unreadTotal = 0,
                )
            var replyDraft by mutableStateOf("")
            val sentReplies = mutableListOf<String>()

            setContent {
                FeedbackVoiceHostShell {
                    val host =
                        rememberComposerVoiceDictationHost(
                            serverFeatureFlags = flags,
                            permitApi = permitApi,
                            active = true,
                            currentSession = session,
                            serverId = serverId,
                            prefetchIfUnresolved = true,
                            streamConnector = streamConnector,
                            audioRecorderProvider = recorderProvider,
                        )
                    CompositionLocalProvider(LocalThreadVoiceDictationHost provides host) {
                        SettingsFeedbackTicketsScreen(
                            state =
                                SettingsFeedbackTicketsState(
                                    list = FeedbackTicketsListState(phase = FeedbackTicketsListPhase.Ready, tickets = listOf(ticket)),
                                    detail =
                                        FeedbackTicketDetailState(
                                            ticketId = ticket.id,
                                            summary = ticket,
                                            detail = detail,
                                            loading = false,
                                            replyDraft = replyDraft,
                                        ),
                                ),
                            timeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = now / 86_400_000L, nowEpochMillis = now),
                            actions =
                                SettingsFeedbackTicketsActions(
                                    onReplyChange = { replyDraft = it },
                                    onSendReply = { sentReplies += replyDraft },
                                ),
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 5_000L) {
                onAllNodesWithTag(ThreadVoiceDictationButtonTestTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            onNodeWithContentDescription("Voice dictation").assertIsDisplayed().performClick()
            waitForIdle()
            connection.emitFrame(VoiceDictationV2ServerFrame.Ready)
            connection.emitFrame(VoiceDictationV2ServerFrame.Text(text = "Works great now", stableLen = 15, refined = true))
            waitForIdle()
            assertEquals("Works great now", replyDraft)

            onNodeWithContentDescription("Send").assertIsEnabled().performClick()
            waitForIdle()
            assertTrue(recorder.stopCount >= 1)
            assertTrue(connection.stopSent)
            connection.emitFrame(VoiceDictationV2ServerFrame.Done)
            waitForIdle()
            assertEquals(listOf("Works great now"), sentReplies)
            assertTrue(connection.closed)
        }

    @Composable
    private fun FeedbackVoiceHostShell(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { content() }
        }
    }

    @Composable
    private fun NewFeedbackFormSurface(
        instanceId: String,
        seedText: String = "",
        onEditorReady: (ThreadComposerEditorOwner) -> Unit = {},
        onChange: (String) -> Unit = {},
        onSend: (String) -> Unit = {},
    ) {
        val copy = settingsHandsFeedbackCopy(RaftFormat.byteLimit(settingsFeedbackImageMaxBytes()))
        val editorOwner =
            remember(instanceId) {
                ThreadComposerEditorOwner(seedText = seedText, seedAsTask = false)
            }.also(onEditorReady)
        SettingsHandsFeedbackForm(
            copy = copy,
            type = SettingsFeedbackType.Idea,
            onTypeChange = {},
            enabled = true,
            notice = null,
            onRetry = {},
            onCopyResult = {},
            bottomContentPadding = 16.dp,
        ) {
            SettingsHandsFeedbackComposer(
                instanceId = instanceId,
                editorOwner = editorOwner,
                placeholder = copy.placeholder,
                enabled = true,
                submitting = false,
                attachmentActionsEnabled = true,
                attachments = emptyList(),
                composerSendKeyEnabled = false,
                onEvent = { action, value, _ ->
                    when (action) {
                        "change" -> onChange(value.orEmpty())
                        "send" -> onSend((value ?: editorOwner.text).trim())
                    }
                },
            )
        }
    }

    private fun sampleSession(
        serverId: String,
        role: String = "member",
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.com"),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = serverId,
            servers = listOf(StoredAuthServer(id = serverId, name = "Test Server", role = role)),
        )

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

    private class FakeFeatureFlagsApi(
        private val voiceDictationEnabled: Boolean,
    ) : RaftFeatureFlagsApi {
        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"evaluations":[{"key":"$VOICE_DICTATION_V1_FEATURE_FLAG_KEY","enabled":$voiceDictationEnabled,"reason":"rule"}]}""",
            )
    }

    private class FakePermitApi(
        private val handler: suspend (serverId: String, channelId: String?) -> VoiceDictationV2PermitOutcome,
    ) : VoiceDictationV2PermitApi {
        override suspend fun requestPermit(
            serverId: String,
            language: String?,
            channelId: String?,
        ): VoiceDictationV2PermitOutcome = handler(serverId, channelId)
    }

    private class FakeStreamConnector(
        private val connection: FakeStreamConnection,
    ) : VoiceDictationV2StreamConnector {
        override suspend fun connect(url: String, permit: String): VoiceDictationV2StreamConnection = connection
    }

    private class FakeStreamConnection : VoiceDictationV2StreamConnection {
        private val channel = Channel<VoiceDictationV2StreamEvent>(Channel.UNLIMITED)
        var startSent = false
        var lastStartMode: VoiceDictationSessionMode? = null
        var stopSent = false
        var closed = false

        override val events: Flow<VoiceDictationV2StreamEvent> = channel.receiveAsFlow()

        override suspend fun sendStart(
            sampleRateHz: Int,
            mode: VoiceDictationSessionMode,
        ): Boolean {
            startSent = true
            lastStartMode = mode
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
    ) : RaftVoiceDictationAudioRecorder {
        var stopCount = 0

        override suspend fun start(
            onPcmChunk: (ByteArray) -> Unit,
            onAudioLevel: (Float) -> Unit,
        ): VoiceDictationAudioStartResult = startResult

        override suspend fun stop() {
            stopCount += 1
        }
    }
}
