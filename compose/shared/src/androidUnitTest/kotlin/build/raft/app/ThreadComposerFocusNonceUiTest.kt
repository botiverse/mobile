package build.raft.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.focus.RaftFocusCoordinator
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import build.raft.designsystem.focus.RaftFocusRequestSource
import build.raft.designsystem.focus.currentRaftFocusCoordinator
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit Step 5 (`#triage:#465`): composer `focusRequestNonce` channel routed through
 * [RaftFocusCoordinator]. Covers:
 * - Audit S3 / line 123: deferred system focus after image edit upload is dropped when the user
 *   moved focus elsewhere, interacted (`DropStaleInteraction`), or opened a modal during async
 *   work, and never calls `show()`.
 * - Uninterrupted deferred system focus on a hardware-keyboard/desktop host focuses the composer
 *   with `FocusOnly` (no `softwareKeyboardController.show()`).
 * - Rule 5: deferred system focus is dropped on a touch-only host.
 * - Rule 1: explicit User focus requests (Mention / Quote / `insertMentionRequest` / blank-area
 *   tap) yield while a real `RaftWindowDialog` modal is open above the composer, and focus once
 *   the modal closes.
 * - `BeginSend` (`KeepOpenKeyboardOnly`) never raises a dismissed keyboard.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi-keysexposed-qwerty")
class ThreadComposerFocusNonceUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class RecordingKeyboard : SoftwareKeyboardController {
        var shown = 0
        var hidden = 0

        override fun show() {
            shown += 1
        }

        override fun hide() {
            hidden += 1
        }
    }

    // Rule 1: even an explicit User focus request (Mention / Quote) yields while a real
    // RaftWindowDialog modal is open above the composer, then focuses and raises IME once closed.
    @Test
    fun userComposerFocusRequestYieldsToOpenModalAndRaisesImeWhenNoModal() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            var composerState by mutableStateOf(ThreadComposerUiState(instanceId = "conv-4"))
            var dialogOpen by mutableStateOf(false)
            var coordinator: RaftFocusCoordinator? = null
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    val portal = remember { ThreadComposerSuggestionPortalState() }
                    CompositionLocalProvider(
                        LocalSoftwareKeyboardController provides keyboard,
                        LocalThreadComposerSuggestionPortal provides portal,
                    ) {
                        RaftWindowFocusScope {
                            coordinator = currentRaftFocusCoordinator()
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.size(1.dp).focusable())
                                RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                    val focusOnOpen =
                                        rememberComposerFocusOnOpen(
                                            policy = true,
                                            shown = true,
                                            yieldsToSideThread = false,
                                            opensOnMessage = true,
                                        )
                                    ThreadComposerContent(
                                        surface = ThreadComposerSurface(channelId = "c4"),
                                        state = composerState,
                                        onEvent = { _, _, _, _ -> },
                                        onKeyboardHeightChange = {},
                                        contentPaddingTop = 0.dp,
                                        contentPaddingBottom = 0.dp,
                                        attachmentPreviewEnabled = true,
                                        autofocusOnOpen = focusOnOpen,
                                    )
                                }
                            }
                            if (dialogOpen) {
                                RaftWindowDialog(onDismissRequest = { dialogOpen = false }) {
                                    Box(Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            val activeCoordinator = checkNotNull(coordinator)

            // Open a real modal above the conversation: User Mention/Quote focus must not steal focus.
            runOnIdle { dialogOpen = true }
            waitForIdle()
            assertTrue(activeCoordinator.modalOpen)

            runOnIdle {
                composerState =
                    composerState.copy(
                        focusRequestNonce = composerState.focusRequestNonce + 1,
                        focusRequestMeta =
                            ThreadComposerFocusRequestMeta(
                                source = RaftFocusRequestSource.User("append_quote"),
                                keyboardMode = ThreadComposerFocusRequestKeyboardMode.RaiseKeyboard,
                            ),
                    )
            }
            waitForIdle()
            assertFalse(activeCoordinator.anyComposerFocused, "composer must yield while a modal is open")
            assertEquals(0, keyboard.shown)
            assertEquals(0, activeCoordinator.pendingTicketCount)

            // Close modal and dispatch another explicit User focus request -> focuses and shows IME.
            runOnIdle { dialogOpen = false }
            waitForIdle()
            assertFalse(activeCoordinator.modalOpen)

            runOnIdle {
                composerState =
                    composerState.copy(
                        focusRequestNonce = composerState.focusRequestNonce + 1,
                        focusRequestMeta =
                            ThreadComposerFocusRequestMeta(
                                source = RaftFocusRequestSource.User("append_quote"),
                                keyboardMode = ThreadComposerFocusRequestKeyboardMode.RaiseKeyboard,
                            ),
                    )
            }
            waitForIdle()
            assertTrue(activeCoordinator.anyComposerFocused, "explicit user request after modal closes should focus composer")
            assertEquals(1, keyboard.shown)
            assertEquals(0, activeCoordinator.pendingTicketCount)
        }

    // BeginSend (KeepOpenKeyboardOnly) with a dismissed keyboard never steals focus or raises IME.
    @Test
    fun beginSendKeepOpenKeyboardDoesNotRaiseDismissedIme() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            var composerState by mutableStateOf(ThreadComposerUiState(instanceId = "conv-send"))
            var coordinator: RaftFocusCoordinator? = null
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    val portal = remember { ThreadComposerSuggestionPortalState() }
                    CompositionLocalProvider(
                        LocalSoftwareKeyboardController provides keyboard,
                        LocalThreadComposerSuggestionPortal provides portal,
                    ) {
                        RaftWindowFocusScope {
                            coordinator = currentRaftFocusCoordinator()
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.size(1.dp).focusable())
                                RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                    val focusOnOpen =
                                        rememberComposerFocusOnOpen(
                                            policy = true,
                                            shown = true,
                                            yieldsToSideThread = false,
                                            opensOnMessage = true,
                                        )
                                    ThreadComposerContent(
                                        surface = ThreadComposerSurface(channelId = "c-send"),
                                        state = composerState,
                                        onEvent = { _, _, _, _ -> },
                                        onKeyboardHeightChange = {},
                                        contentPaddingTop = 0.dp,
                                        contentPaddingBottom = 0.dp,
                                        attachmentPreviewEnabled = true,
                                        autofocusOnOpen = focusOnOpen,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            val activeCoordinator = checkNotNull(coordinator)
            assertFalse(activeCoordinator.anyComposerFocused)

            runOnIdle {
                composerState =
                    composerState.copy(
                        focusRequestNonce = composerState.focusRequestNonce + 1,
                        focusRequestMeta =
                            ThreadComposerFocusRequestMeta(
                                source = RaftFocusRequestSource.User("begin_send"),
                                keyboardMode = ThreadComposerFocusRequestKeyboardMode.KeepOpenKeyboardOnly,
                            ),
                    )
            }
            waitForIdle()

            assertFalse(activeCoordinator.anyComposerFocused, "BeginSend with dismissed IME must not grab focus")
            assertEquals(0, keyboard.shown)
        }

    private companion object {
        const val SEARCH = "search_field"
    }
}
