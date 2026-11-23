package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.PlatformFile
import build.raft.app.data.SettingsFeedbackType
import build.raft.designsystem.components.RaftButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftScreenshotFeedbackOverlayUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val pageContext =
        RaftScreenshotFeedbackPageContext(
            pageName = "compose_thread",
            serverId = "srv-1",
            channelId = "ch-1",
        )

    private val screenshot =
        PlatformFile(
            id = "shot-ui-1",
            fileName = "screenshot.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            previewUri = null,
            fileRef = "file:///tmp/screenshot-feedback/shot-ui-1.jpg",
        )

    @Test
    fun floatingPromptPillDoesNotBlockUnderlyingPageAndDismissesCleanly() = runRobolectricComposeUiTest {
        var underlyingClicks = 0
        var promptDismissed = false
        var promptTapped = false
        var state by mutableStateOf<RaftScreenshotFeedbackState>(
            RaftScreenshotFeedbackState.Prompting(
                sessionId = 1L,
                screenshot = screenshot,
                pageContext = pageContext,
            )
        )

        setContent {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize()) {
                    RaftButton(
                        text = "Underlying action",
                        onClick = { underlyingClicks += 1 },
                        modifier = Modifier.align(Alignment.TopCenter).testTag("underlying_page_button"),
                    )
                    RaftScreenshotFeedbackOverlayContent(
                        state = state,
                        onPromptTap = { promptTapped = true },
                        onPromptDismiss = {
                            promptDismissed = true
                            state = RaftScreenshotFeedbackState.Idle
                        },
                        onNoteChange = {},
                        onFeedbackTypeChange = {},
                        onDetachScreenshot = {},
                        onSheetDismiss = {},
                        onSubmit = {},
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag(RaftScreenshotFeedbackPromptPillTag).assertExists()
        onNodeWithTag("underlying_page_button").performClick()
        waitForIdle()
        assertEquals(1, underlyingClicks)

        onNodeWithTag(RaftScreenshotFeedbackPromptDismissTag).performClick()
        waitForIdle()
        assertTrue(promptDismissed)
        onNodeWithTag(RaftScreenshotFeedbackPromptPillTag).assertDoesNotExist()
        assertEquals(false, promptTapped)
    }

    @Test
    fun tappingPromptOpensQuickFeedbackSheetSupportsDetachTypeToggleAndSubmit() = runRobolectricComposeUiTest {
        var submittedCount = 0
        var detached = false
        var state by mutableStateOf<RaftScreenshotFeedbackState>(
            RaftScreenshotFeedbackState.Prompting(
                sessionId = 1L,
                screenshot = screenshot,
                pageContext = pageContext,
            )
        )

        setContent {
            RaftDesignSystemTheme {
                RaftScreenshotFeedbackOverlayContent(
                    state = state,
                    onPromptTap = {
                        state =
                            RaftScreenshotFeedbackState.Composing(
                                sessionId = 1L,
                                screenshot = screenshot,
                                screenshotRemoved = false,
                                pageContext = pageContext,
                                note = "",
                                feedbackType = SettingsFeedbackType.Problem,
                                diagnosticsState = RaftScreenshotFeedbackDiagnosticsState.Ready,
                                diagnosticsRef = null,
                                submitting = false,
                                errorCode = null,
                                errorRetryable = false,
                                errorTraceId = null,
                                pendingRetryAttempt = null,
                            )
                    },
                    onPromptDismiss = { state = RaftScreenshotFeedbackState.Idle },
                    onNoteChange = { text ->
                        val current = state as? RaftScreenshotFeedbackState.Composing ?: return@RaftScreenshotFeedbackOverlayContent
                        state = current.copy(note = text)
                    },
                    onFeedbackTypeChange = { type ->
                        val current = state as? RaftScreenshotFeedbackState.Composing ?: return@RaftScreenshotFeedbackOverlayContent
                        state = current.copy(feedbackType = type)
                    },
                    onDetachScreenshot = {
                        detached = true
                        val current = state as? RaftScreenshotFeedbackState.Composing ?: return@RaftScreenshotFeedbackOverlayContent
                        state = current.copy(screenshot = null, screenshotRemoved = true)
                    },
                    onSheetDismiss = { state = RaftScreenshotFeedbackState.Idle },
                    onSubmit = { submittedCount += 1 },
                )
            }
        }
        waitForIdle()

        // Step 1 -> Step 2
        onNodeWithTag(RaftScreenshotFeedbackPromptActionTag).performClick()
        waitForIdle()

        onNodeWithTag(RaftScreenshotFeedbackSheetTag).assertExists()
        onNodeWithTag(RaftScreenshotFeedbackThumbnailTag).assertExists()
        onNodeWithTag(RaftScreenshotFeedbackContextTag).assertExists()
        onNodeWithTag(RaftScreenshotFeedbackDiagnosticsTag).assertExists()
        onNodeWithTag(RaftScreenshotFeedbackSubmitButtonTag).assertIsNotEnabled()

        // Detach screenshot thumbnail
        onNodeWithTag(RaftScreenshotFeedbackRemoveScreenshotTag).performClick()
        waitForIdle()
        assertTrue(detached)
        onNodeWithTag(RaftScreenshotFeedbackThumbnailTag).assertDoesNotExist()

        // Type note -> Submit enables and fires
        onNodeWithTag(RaftScreenshotFeedbackNoteInputTag).performTextInput("Composer flickers on scroll")
        waitForIdle()
        onNodeWithTag(RaftScreenshotFeedbackSubmitButtonTag).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(1, submittedCount)
    }

    @Test
    fun errorStateShowsWarningAndTraceIdWhilePreservingNote() = runRobolectricComposeUiTest {
        val state =
            RaftScreenshotFeedbackState.Composing(
                sessionId = 2L,
                screenshot = null,
                screenshotRemoved = false,
                pageContext = pageContext,
                note = "Keep my typed note",
                feedbackType = SettingsFeedbackType.Problem,
                diagnosticsState = RaftScreenshotFeedbackDiagnosticsState.Unavailable,
                diagnosticsRef = null,
                submitting = false,
                errorCode = "settings_feedback_network_failed",
                errorRetryable = true,
                errorTraceId = "4bf92f3577b34da6a3ce929d0e0e4736",
                pendingRetryAttempt = null,
            )

        setContent {
            RaftDesignSystemTheme {
                RaftScreenshotFeedbackOverlayContent(
                    state = state,
                    onPromptTap = {},
                    onPromptDismiss = {},
                    onNoteChange = {},
                    onFeedbackTypeChange = {},
                    onDetachScreenshot = {},
                    onSheetDismiss = {},
                    onSubmit = {},
                )
            }
        }
        waitForIdle()

        onNodeWithTag(RaftScreenshotFeedbackErrorTag).assertExists()
        onNodeWithText("Keep my typed note").assertExists()
        onNodeWithText("4bf92f35", substring = true).assertExists()
    }
}
