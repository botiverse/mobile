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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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

    // Audit line 123 / S3: user finishes editing an image (ticket captured before async upload),
    // focuses a Search field while uploading, then upload completes and dispatches
    // RequestDeferredFocus -> dropped cleanly; Search keeps focus and IME is never raised.
    @Test
    fun deferredComposerRequestAfterImageEditDroppedWhenUserFocusedElsewhere() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            val searchFocus = FocusRequester()
            var composerState by mutableStateOf(ThreadComposerUiState(instanceId = "conv-1"))
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
                                // Keep Robolectric's initial non-touch focus off SEARCH before the
                                // test explicitly focuses SEARCH during async work.
                                Box(Modifier.size(1.dp).focusable())
                                RaftFocusRegionHost(RaftFocusRegion.SupportingPane) {
                                    TextField(
                                        modifier = Modifier.testTag(SEARCH),
                                        value = "",
                                        autoFocus = false,
                                        onValueChange = {},
                                        focusRequester = searchFocus,
                                    )
                                }
                                RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                    val focusOnOpen =
                                        rememberComposerFocusOnOpen(
                                            policy = true,
                                            shown = true,
                                            yieldsToSideThread = false,
                                            opensOnMessage = true,
                                        )
                                    ThreadComposerContent(
                                        surface = ThreadComposerSurface(channelId = "c1"),
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
            onNodeWithTag(SEARCH).assertIsNotFocused()
            assertFalse(activeCoordinator.anyComposerFocused)

            // 1. Capture ticket when image edit completes (before async compress+upload).
            val ticket =
                activeCoordinator.request(
                    targetKey = ThreadComposerFocusTargetKey("conv-1"),
                    region = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("image_edit_complete"),
                    isText = true,
                )
            val focusEpochBefore = activeCoordinator.focusEpoch

            // 2. While async upload is running, the user moves focus into Search.
            runOnIdle { searchFocus.requestFocus() }
            waitForIdle()
            onNodeWithTag(SEARCH).assertIsFocused()
            assertTrue(activeCoordinator.focusEpoch > focusEpochBefore)

            // 3. Upload finishes and dispatches RequestDeferredFocus(ticket).
            runOnIdle {
                composerState =
                    composerState.copy(
                        focusRequestNonce = composerState.focusRequestNonce + 1,
                        focusRequestMeta =
                            ThreadComposerFocusRequestMeta(
                                source = ticket.source,
                                keyboardMode = ThreadComposerFocusRequestKeyboardMode.FocusOnly,
                                deferredTicket = ticket,
                            ),
                    )
            }
            waitForIdle()

            onNodeWithTag(SEARCH).assertIsFocused()
            assertFalse(activeCoordinator.anyComposerFocused, "stale deferred focus must not steal focus from Search")
            assertEquals(0, keyboard.shown, "deferred image-edit focus must never raise the soft keyboard")
            assertEquals(0, activeCoordinator.pendingTicketCount)
        }

    // Rule 3 (DropStaleInteraction): user interacts without moving focus while async upload runs
    // -> deferred focus is dropped cleanly.
    @Test
    fun deferredComposerRequestDroppedOnUserInteractionDuringAsyncWork() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            var composerState by mutableStateOf(ThreadComposerUiState(instanceId = "conv-stale"))
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
                                RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                    Column {
                                        Box(Modifier.size(1.dp).focusable())
                                        val focusOnOpen =
                                            rememberComposerFocusOnOpen(
                                                policy = true,
                                                shown = true,
                                                yieldsToSideThread = false,
                                                opensOnMessage = true,
                                            )
                                        ThreadComposerContent(
                                            surface = ThreadComposerSurface(channelId = "c-stale"),
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
            }
            waitForIdle()
            val activeCoordinator = checkNotNull(coordinator)
            assertFalse(activeCoordinator.anyComposerFocused)

            val ticket =
                activeCoordinator.request(
                    targetKey = ThreadComposerFocusTargetKey("conv-stale"),
                    region = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("image_edit_complete"),
                    isText = true,
                )
            runOnIdle {
                activeCoordinator.recordUserInteraction()
                composerState =
                    composerState.copy(
                        focusRequestNonce = composerState.focusRequestNonce + 1,
                        focusRequestMeta =
                            ThreadComposerFocusRequestMeta(
                                source = ticket.source,
                                keyboardMode = ThreadComposerFocusRequestKeyboardMode.FocusOnly,
                                deferredTicket = ticket,
                            ),
                    )
            }
            waitForIdle()

            assertFalse(activeCoordinator.anyComposerFocused, "user interaction during upload must drop deferred focus")
            assertEquals(0, keyboard.shown)
            assertEquals(0, activeCoordinator.pendingTicketCount)
        }

    // Rule 1: opening a real RaftWindowDialog while async upload runs drops the deferred focus.
    @Test
    fun deferredComposerRequestDroppedWhenModalOpensDuringAsyncWork() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            var composerState by mutableStateOf(ThreadComposerUiState(instanceId = "conv-modal"))
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
                                RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                    Column {
                                        Box(Modifier.size(1.dp).focusable())
                                        val focusOnOpen =
                                            rememberComposerFocusOnOpen(
                                                policy = true,
                                                shown = true,
                                                yieldsToSideThread = false,
                                                opensOnMessage = true,
                                            )
                                        ThreadComposerContent(
                                            surface = ThreadComposerSurface(channelId = "c-modal"),
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
            val ticket =
                activeCoordinator.request(
                    targetKey = ThreadComposerFocusTargetKey("conv-modal"),
                    region = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("image_edit_complete"),
                    isText = true,
                )
            runOnIdle { dialogOpen = true }
            waitForIdle()
            assertTrue(activeCoordinator.modalOpen)

            runOnIdle {
                composerState =
                    composerState.copy(
                        focusRequestNonce = composerState.focusRequestNonce + 1,
                        focusRequestMeta =
                            ThreadComposerFocusRequestMeta(
                                source = ticket.source,
                                keyboardMode = ThreadComposerFocusRequestKeyboardMode.FocusOnly,
                                deferredTicket = ticket,
                            ),
                    )
            }
            waitForIdle()

            assertFalse(activeCoordinator.anyComposerFocused, "open modal during upload must drop deferred focus")
            assertEquals(0, keyboard.shown)
            assertEquals(0, activeCoordinator.pendingTicketCount)
        }

    // Uninterrupted deferred system focus on a hardware-keyboard/desktop host focuses the composer
    // without calling softwareKeyboardController.show().
    @Test
    fun deferredComposerRequestAfterImageEditFocusesWithoutRaisingImeWhenUninterrupted() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            var composerState by mutableStateOf(ThreadComposerUiState(instanceId = "conv-2"))
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
                                RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                    Column {
                                        // Keep initial Robolectric non-touch focus inside Conversation
                                        // (off the composer) so the deferred ticket starts unfocused
                                        // without creating focus in a different region.
                                        Box(Modifier.size(1.dp).focusable())
                                        val focusOnOpen =
                                            rememberComposerFocusOnOpen(
                                                policy = true,
                                                shown = true,
                                                yieldsToSideThread = false,
                                                opensOnMessage = true,
                                            )
                                        ThreadComposerContent(
                                            surface = ThreadComposerSurface(channelId = "c2"),
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
            }
            waitForIdle()
            val activeCoordinator = checkNotNull(coordinator)
            assertFalse(activeCoordinator.anyComposerFocused)

            val ticket =
                activeCoordinator.request(
                    targetKey = ThreadComposerFocusTargetKey("conv-2"),
                    region = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("image_edit_complete"),
                    isText = true,
                )
            runOnIdle {
                composerState =
                    composerState.copy(
                        focusRequestNonce = composerState.focusRequestNonce + 1,
                        focusRequestMeta =
                            ThreadComposerFocusRequestMeta(
                                source = ticket.source,
                                keyboardMode = ThreadComposerFocusRequestKeyboardMode.FocusOnly,
                                deferredTicket = ticket,
                            ),
                    )
            }
            waitForIdle()

            assertTrue(activeCoordinator.anyComposerFocused, "uninterrupted deferred system focus should focus composer")
            assertEquals(0, keyboard.shown, "FocusOnly deferred request must not call softwareKeyboardController.show()")
            assertEquals(0, activeCoordinator.pendingTicketCount)
        }

    // Rule 5: on a touch-only host (policy = false), deferred system focus is dropped so it never
    // steals focus or raises the soft keyboard.
    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi-keyshidden-nokeys")
    fun deferredComposerRequestAfterImageEditDroppedOnTouchOnlyHost() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            var composerState by mutableStateOf(ThreadComposerUiState(instanceId = "conv-3"))
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
                                RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                    Column {
                                        // Keep initial Robolectric non-touch focus inside Conversation
                                        // (off the composer) so Rule 5 (touchPrimary) is what drops it.
                                        Box(Modifier.size(1.dp).focusable())
                                        val focusOnOpen =
                                            rememberComposerFocusOnOpen(
                                                policy = false,
                                                shown = true,
                                                yieldsToSideThread = false,
                                                opensOnMessage = true,
                                            )
                                        ThreadComposerContent(
                                            surface = ThreadComposerSurface(channelId = "c3"),
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
            }
            waitForIdle()
            val activeCoordinator = checkNotNull(coordinator)
            assertFalse(activeCoordinator.anyComposerFocused)

            val ticket =
                activeCoordinator.request(
                    targetKey = ThreadComposerFocusTargetKey("conv-3"),
                    region = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("image_edit_complete"),
                    isText = true,
                )
            runOnIdle {
                composerState =
                    composerState.copy(
                        focusRequestNonce = composerState.focusRequestNonce + 1,
                        focusRequestMeta =
                            ThreadComposerFocusRequestMeta(
                                source = ticket.source,
                                keyboardMode = ThreadComposerFocusRequestKeyboardMode.FocusOnly,
                                deferredTicket = ticket,
                            ),
                    )
            }
            waitForIdle()

            assertFalse(activeCoordinator.anyComposerFocused, "touch-only host must drop deferred system focus")
            assertEquals(0, keyboard.shown)
            assertEquals(0, activeCoordinator.pendingTicketCount)
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

    // insertMentionRequest inserts the @handle text, yields focus while a real RaftWindowDialog
    // modal is open, and focuses + raises IME once no modal is open.
    @Test
    fun insertMentionRequestYieldsToOpenModalAndRaisesImeWhenNoModal() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            var composerState by mutableStateOf(ThreadComposerUiState(instanceId = "conv-mention"))
            var insertRequest by mutableStateOf<ThreadComposerInsertMentionRequest?>(null)
            var dialogOpen by mutableStateOf(false)
            val consumedNonces = mutableListOf<Int>()
            val changeTexts = mutableListOf<String>()
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
                                        surface = ThreadComposerSurface(channelId = "c-mention"),
                                        state = composerState,
                                        onEvent = { name, value, _, _ ->
                                            if (name == "change" && value != null) changeTexts += value
                                        },
                                        onKeyboardHeightChange = {},
                                        contentPaddingTop = 0.dp,
                                        contentPaddingBottom = 0.dp,
                                        attachmentPreviewEnabled = true,
                                        insertMentionRequest = insertRequest,
                                        onInsertMentionConsumed = { consumedNonces += it.nonce },
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

            runOnIdle { dialogOpen = true }
            waitForIdle()
            assertTrue(activeCoordinator.modalOpen)

            runOnIdle {
                insertRequest = ThreadComposerInsertMentionRequest(nonce = 1, handle = "alice")
            }
            waitForIdle()
            assertEquals(listOf(1), consumedNonces)
            assertTrue(changeTexts.any { it.contains("@alice") }, "mention text should still be inserted")
            assertFalse(activeCoordinator.anyComposerFocused, "insertMentionRequest must yield focus while modal is open")
            assertEquals(0, keyboard.shown)
            assertEquals(0, activeCoordinator.pendingTicketCount)

            runOnIdle { dialogOpen = false }
            waitForIdle()
            runOnIdle {
                insertRequest = ThreadComposerInsertMentionRequest(nonce = 2, handle = "bob")
            }
            waitForIdle()
            assertEquals(listOf(1, 2), consumedNonces)
            assertTrue(activeCoordinator.anyComposerFocused, "insertMentionRequest should focus composer once modal closes")
            assertEquals(1, keyboard.shown)
            assertEquals(0, activeCoordinator.pendingTicketCount)
        }

    // Blank-area composer card tap routes through RaftFocusCoordinator: yields while a real
    // RaftWindowDialog is open and focuses once the modal closes.
    @Test
    fun blankAreaComposerTapYieldsToOpenModalAndFocusesWhenNoModal() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            val composerState = ThreadComposerUiState(instanceId = "conv-tap")
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
                                        surface = ThreadComposerSurface(channelId = "c-tap"),
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
            assertFalse(activeCoordinator.anyComposerFocused)

            runOnIdle { dialogOpen = true }
            waitForIdle()
            assertTrue(activeCoordinator.modalOpen)
            onNodeWithTag(ThreadComposerCardTestTag).performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertFalse(activeCoordinator.anyComposerFocused, "blank-area tap must yield while modal is open")
            assertEquals(0, activeCoordinator.pendingTicketCount)

            runOnIdle { dialogOpen = false }
            waitForIdle()
            assertFalse(activeCoordinator.modalOpen)
            onNodeWithTag(ThreadComposerCardTestTag).performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertTrue(activeCoordinator.anyComposerFocused, "blank-area tap should focus composer when no modal is open")
            assertEquals(0, activeCoordinator.pendingTicketCount)
        }

    // policy = true with yieldsToSideThread = true sets enabled = false for open-autofocus, yet
    // deferredTouchPrimary must read policy (not enabled) so it is never misclassified as touch-only.
    @Test
    fun deferredComposerRequestUsesPlatformPolicyRatherThanYieldsToSideThread() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            var composerState by mutableStateOf(ThreadComposerUiState(instanceId = "conv-policy"))
            var coordinator: RaftFocusCoordinator? = null
            var capturedFocusOnOpen: ComposerFocusOnOpen? = null
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
                                RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                    Column {
                                        Box(Modifier.size(1.dp).focusable())
                                        val focusOnOpen =
                                            rememberComposerFocusOnOpen(
                                                policy = true,
                                                shown = true,
                                                yieldsToSideThread = true,
                                                opensOnMessage = true,
                                            )
                                        capturedFocusOnOpen = focusOnOpen
                                        ThreadComposerContent(
                                            surface = ThreadComposerSurface(channelId = "c-policy"),
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
            }
            waitForIdle()
            val activeCoordinator = checkNotNull(coordinator)
            val focusState = checkNotNull(capturedFocusOnOpen)
            assertFalse(focusState.enabled, "yieldsToSideThread=true disables open-autofocus")
            assertTrue(focusState.policy, "platform policy stays true independent of yieldsToSideThread")
            assertFalse(activeCoordinator.anyComposerFocused)

            val ticket =
                activeCoordinator.request(
                    targetKey = ThreadComposerFocusTargetKey("conv-policy"),
                    region = RaftFocusRegion.Conversation,
                    source = RaftFocusRequestSource.System("image_edit_complete"),
                    isText = true,
                )
            runOnIdle {
                composerState =
                    composerState.copy(
                        focusRequestNonce = composerState.focusRequestNonce + 1,
                        focusRequestMeta =
                            ThreadComposerFocusRequestMeta(
                                source = ticket.source,
                                keyboardMode = ThreadComposerFocusRequestKeyboardMode.FocusOnly,
                                deferredTicket = ticket,
                            ),
                    )
            }
            waitForIdle()

            assertTrue(
                activeCoordinator.anyComposerFocused,
                "deferred focus on a non-touch host must not be dropped as touch-only just because yieldsToSideThread=true",
            )
            assertEquals(0, keyboard.shown)
            assertEquals(0, activeCoordinator.pendingTicketCount)
        }

    private companion object {
        const val SEARCH = "search_field"
    }
}
