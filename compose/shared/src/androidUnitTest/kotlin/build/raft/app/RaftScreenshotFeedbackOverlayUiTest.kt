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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.PlatformFile
import build.raft.app.data.SettingsFeedbackType
import build.raft.app.resources.Res
import build.raft.app.resources.artifacts_column_title
import build.raft.app.resources.drafts_row_task_untitled
import build.raft.app.resources.file_preview_title
import build.raft.app.resources.home_nav_search
import build.raft.app.resources.saved_title
import build.raft.app.resources.settings_account_title
import build.raft.app.resources.settings_title
import build.raft.app.resources.tab_activity
import build.raft.app.resources.tab_home
import build.raft.app.resources.tab_members
import build.raft.app.resources.tab_settings
import build.raft.app.resources.tab_tasks
import build.raft.app.resources.thread_reply_title
import build.raft.app.settings.RAFT_APP_SHELL_CHILD_TAB_PAGE
import build.raft.app.settings.RaftScreenshotFeedbackContextTag
import build.raft.app.settings.RaftScreenshotFeedbackDiagnosticsState
import build.raft.app.settings.RaftScreenshotFeedbackDiagnosticsTag
import build.raft.app.settings.RaftScreenshotFeedbackErrorTag
import build.raft.app.settings.RaftScreenshotFeedbackNoteInputTag
import build.raft.app.settings.RaftScreenshotFeedbackOverlayContent
import build.raft.app.settings.RaftScreenshotFeedbackPageContext
import build.raft.app.settings.RaftScreenshotFeedbackPromptActionTag
import build.raft.app.settings.RaftScreenshotFeedbackPromptDismissTag
import build.raft.app.settings.RaftScreenshotFeedbackPromptPillTag
import build.raft.app.settings.RaftScreenshotFeedbackRemoveScreenshotTag
import build.raft.app.settings.RaftScreenshotFeedbackRuntime
import build.raft.app.settings.RaftScreenshotFeedbackSheetTag
import build.raft.app.settings.RaftScreenshotFeedbackState
import build.raft.app.settings.RaftScreenshotFeedbackSubmitButtonTag
import build.raft.app.settings.RaftScreenshotFeedbackThumbnailTag
import build.raft.app.settings.localizedScreenshotFeedbackPageLabelResource
import build.raft.app.settings.resolveScreenshotFeedbackPromptBottomPadding
import build.raft.app.shell.RaftNativeTabBarBand
import build.raft.app.shell.RaftNativeTabBarEdge
import build.raft.app.shell.raftNativeHostActiveBottomTabBarBandHeight
import build.raft.designsystem.components.RaftButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun localizedPageLabelResourceMapsKnownPagesAndTabsAndFallsBackForUnknown() {
        assertEquals(
            Res.string.tab_home,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_slock_app_shell", activeTab = "home")
            ),
        )
        assertEquals(
            Res.string.tab_activity,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_slock_app_shell", activeTab = "activity")
            ),
        )
        assertEquals(
            Res.string.tab_tasks,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_slock_app_shell", activeTab = "tasks")
            ),
        )
        assertEquals(
            Res.string.tab_members,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_slock_app_shell", activeTab = "members")
            ),
        )
        assertEquals(
            Res.string.tab_settings,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_slock_app_shell", activeTab = "settings")
            ),
        )
        assertEquals(
            Res.string.home_nav_search,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_slock_app_shell", activeTab = "search")
            ),
        )
        assertEquals(
            Res.string.thread_reply_title,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_thread")
            ),
        )
        assertEquals(
            Res.string.drafts_row_task_untitled,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_task_detail")
            ),
        )
        assertEquals(
            Res.string.saved_title,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_home_saved")
            ),
        )
        assertEquals(
            Res.string.settings_account_title,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_settings_account")
            ),
        )
        assertEquals(
            Res.string.settings_title,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_settings_future_panel")
            ),
        )
        assertEquals(
            Res.string.artifacts_column_title,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_artifact_viewer")
            ),
        )
        assertEquals(
            Res.string.file_preview_title,
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_file_preview")
            ),
        )
        assertNull(
            localizedScreenshotFeedbackPageLabelResource(
                RaftScreenshotFeedbackPageContext(pageName = "compose_unmapped_custom_screen")
            )
        )
    }

    @Test
    fun promptBottomPaddingFollowsCurrentHostPageAndLiveBottomTabBarBand() {
        // Captured on Settings, still on Settings: base safeBottom + 20dp.
        assertEquals(
            54.dp,
            resolveScreenshotFeedbackPromptBottomPadding(
                hostPageName = "compose_settings_home",
                safeBottom = 34.dp,
                activeShellBottomBarBandHeight = 92.dp,
            ),
        )
        // Pushed server-panel Settings page on phone also uses base padding, not shell tab-bar clearance.
        assertEquals(
            54.dp,
            resolveScreenshotFeedbackPromptBottomPadding(
                hostPageName = "compose_app_shell_server_page",
                safeBottom = 34.dp,
                activeShellBottomBarBandHeight = 92.dp,
            ),
        )
        // Returned to iOS app shell with Liquid Glass floating UITabBar (~92pt band):
        // clears band by 16dp without double-counting safeBottom.
        assertEquals(
            108.dp,
            resolveScreenshotFeedbackPromptBottomPadding(
                hostPageName = "compose_slock_app_shell",
                safeBottom = 34.dp,
                activeShellBottomBarBandHeight = 92.dp,
            ),
        )
        // Defensive match for compose_app_shell_child_tab as well.
        assertEquals(
            108.dp,
            resolveScreenshotFeedbackPromptBottomPadding(
                hostPageName = RAFT_APP_SHELL_CHILD_TAB_PAGE,
                safeBottom = 34.dp,
                activeShellBottomBarBandHeight = 92.dp,
            ),
        )
        // Returned to Android/Compose app shell with bottom bar (80dp band).
        assertEquals(
            96.dp,
            resolveScreenshotFeedbackPromptBottomPadding(
                hostPageName = "compose_slock_app_shell",
                safeBottom = 24.dp,
                activeShellBottomBarBandHeight = 80.dp,
            ),
        )
        // IME open over app shell (safeBottom=300dp > 92dp tab bar): clears keyboard, never dips under it.
        assertEquals(
            320.dp,
            resolveScreenshotFeedbackPromptBottomPadding(
                hostPageName = "compose_slock_app_shell",
                safeBottom = 300.dp,
                activeShellBottomBarBandHeight = 92.dp,
            ),
        )
        // Wide/tablet/desktop app shell with rail (0dp bottom bar): no awkward extra gap.
        assertEquals(
            20.dp,
            resolveScreenshotFeedbackPromptBottomPadding(
                hostPageName = "compose_slock_app_shell",
                safeBottom = 0.dp,
                activeShellBottomBarBandHeight = 0.dp,
            ),
        )
        // Pre-layout fallback when shell has not reported a live band yet.
        assertEquals(
            106.dp,
            resolveScreenshotFeedbackPromptBottomPadding(
                hostPageName = "compose_slock_app_shell",
                safeBottom = 34.dp,
                activeShellBottomBarBandHeight = null,
            ),
        )
        // Native host helper: visible bottom bar vs hidden vs top/side iPad bar vs zero/null measurement.
        assertEquals(
            92.dp,
            raftNativeHostActiveBottomTabBarBandHeight(
                band = RaftNativeTabBarBand(RaftNativeTabBarEdge.Bottom, 92.0),
                visible = true,
                bottomSafeArea = 34.dp,
            ),
        )
        assertEquals(
            0.dp,
            raftNativeHostActiveBottomTabBarBandHeight(
                band = RaftNativeTabBarBand(RaftNativeTabBarEdge.Bottom, 92.0),
                visible = false,
                bottomSafeArea = 34.dp,
            ),
        )
        assertEquals(
            0.dp,
            raftNativeHostActiveBottomTabBarBandHeight(
                band = RaftNativeTabBarBand(RaftNativeTabBarEdge.Top, 64.0),
                visible = true,
                bottomSafeArea = 34.dp,
            ),
        )
        assertEquals(
            83.dp,
            raftNativeHostActiveBottomTabBarBandHeight(
                band = RaftNativeTabBarBand(RaftNativeTabBarEdge.Bottom, 0.0),
                visible = true,
                bottomSafeArea = 34.dp,
            ),
        )
        assertEquals(
            83.dp,
            raftNativeHostActiveBottomTabBarBandHeight(
                band = null,
                visible = true,
                bottomSafeArea = 34.dp,
            ),
        )
    }

    @Test
    fun runtimeBottomBarBandIsHostScopedAndClearsOnDispose() {
        val hostA = Any()
        val hostB = Any()
        val tokenA1 = Any()
        val tokenA2 = Any()
        val tokenB = Any()
        try {
            RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(hostA, tokenA1, 92f)
            RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(hostB, tokenB, 0f)
            // Compact window hostA must never leak its bottom bar clearance into wide/rail hostB.
            assertEquals(92f, RaftScreenshotFeedbackRuntime.activeShellBottomBarBandDpForHost(hostA))
            assertEquals(0f, RaftScreenshotFeedbackRuntime.activeShellBottomBarBandDpForHost(hostB))

            // Idempotent write with same height must not churn revision.
            val revBefore = RaftScreenshotFeedbackRuntime.shellBottomBarBandRevision.value
            RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(hostA, tokenA1, 92f)
            assertEquals(revBefore, RaftScreenshotFeedbackRuntime.shellBottomBarBandRevision.value)

            // Multiple tokens on the same host track max and fall back cleanly on dispose.
            RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(hostA, tokenA2, 100f)
            assertEquals(100f, RaftScreenshotFeedbackRuntime.activeShellBottomBarBandDpForHost(hostA))
            RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(hostA, tokenA2, null)
            assertEquals(92f, RaftScreenshotFeedbackRuntime.activeShellBottomBarBandDpForHost(hostA))
            RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(hostA, tokenA1, null)
            assertNull(RaftScreenshotFeedbackRuntime.activeShellBottomBarBandDpForHost(hostA))
            RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(hostB, tokenB, null)
            assertNull(RaftScreenshotFeedbackRuntime.activeShellBottomBarBandDpForHost(hostB))
        } finally {
            RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(hostA, tokenA1, null)
            RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(hostA, tokenA2, null)
            RaftScreenshotFeedbackRuntime.setActiveShellBottomBarBandDp(hostB, tokenB, null)
        }
    }

    @Test
    fun settingsScreenshotPillLiftsAboveBottomTabBarWhenReturningToHomeWhileKeepingSettingsContext() =
        runRobolectricComposeUiTest {
            val settingsContext =
                RaftScreenshotFeedbackPageContext(
                    pageName = "compose_settings_home",
                    serverId = "srv-1",
                )
            var currentHostPage by mutableStateOf("compose_app_shell_server_page")
            var activeTabBarBand by mutableStateOf<androidx.compose.ui.unit.Dp?>(92.dp)
            var tappedContext: RaftScreenshotFeedbackPageContext? = null
            val state =
                RaftScreenshotFeedbackState.Prompting(
                    sessionId = 7L,
                    screenshot = screenshot,
                    pageContext = settingsContext,
                )

            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        RaftScreenshotFeedbackOverlayContent(
                            state = state,
                            hostPageName = currentHostPage,
                            activeShellBottomBarBandHeight = activeTabBarBand,
                            onPromptTap = { tappedContext = state.pageContext },
                            onPromptDismiss = {},
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

            val onSettingsBounds = onNodeWithTag(RaftScreenshotFeedbackPromptPillTag).getUnclippedBoundsInRoot()

            // Return from Settings to Home (app shell with Liquid Glass floating UITabBar).
            currentHostPage = "compose_slock_app_shell"
            waitForIdle()

            val onHomeBounds = onNodeWithTag(RaftScreenshotFeedbackPromptPillTag).getUnclippedBoundsInRoot()
            // Pill lifted from 20dp above bottom to 92 + 16 = 108dp above bottom (+88dp higher).
            assertTrue(
                onSettingsBounds.bottom - onHomeBounds.bottom >= 80.dp,
                "Expected pill to lift cleanly above the bottom tab bar; settingsBottom=${onSettingsBounds.bottom} homeBottom=${onHomeBounds.bottom}",
            )

            // When the shell hides its bottom bar (e.g. switches to wide rail), pill settles back down.
            activeTabBarBand = 0.dp
            waitForIdle()
            val onRailBounds = onNodeWithTag(RaftScreenshotFeedbackPromptPillTag).getUnclippedBoundsInRoot()
            assertEquals(onSettingsBounds.bottom, onRailBounds.bottom)

            // Tapping the pill still reports the Settings page where the screenshot was taken.
            onNodeWithTag(RaftScreenshotFeedbackPromptActionTag).performClick()
            waitForIdle()
            assertEquals("compose_settings_home", tappedContext?.pageName)
        }
}
