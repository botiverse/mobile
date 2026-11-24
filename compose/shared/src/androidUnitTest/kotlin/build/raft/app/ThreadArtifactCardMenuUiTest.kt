package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.ArtifactDeletedShown
import build.raft.app.data.ArtifactFacts
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.designsystem.text.RaftText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A message's Artifact card has its own context menu (web `ArtifactCardMenu.tsx` +
 * `ArtifactHomeMenu.tsx`, botiverse/slock #16110 + #16225; #proj-workspace:#382 + #395):
 * - right-click or keyboard Shift+F10 / ContextMenu on a live card opens the card's 2-item menu
 *   (Open, Copy link; no Open in new page, no Settle) and not the message row's menu;
 * - right-click outside the card, touch long press on the card, select mode, preview cards, and
 *   deleted cards stay with the message row's menu;
 * - the Artifact viewer bar hides ⋮ when Desktop Mode is not offered and omits Open in browser.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h900dp")
class ThreadArtifactCardMenuUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private val summary =
        ArtifactSummary(
            id = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11",
            title = "Q3 revenue report",
            description = "Revenue by region with the October forecast.",
            kind = ArtifactKind.Html,
            version = 4,
            pinned = false,
            latestVersion = 4,
            home = ArtifactHome.Conversation("proj-workspace", dm = false),
            authorName = "Lagoon",
            updatedAt = null,
            facts = ArtifactFacts(sizeBytes = 24_000),
        )

    private val deleted = ArtifactDeletedShown("Old plan", "Martin", null)

    private class HarnessRecorder {
        var rowContextClicks = 0
        var rowLongPressMenus = 0
        var cardOpens = 0
        var cardCopyLinks = 0
    }

    private fun ComposeUiTest.mountMessageRow(
        recorder: HarnessRecorder,
        contextMenuEnabled: Boolean = true,
        preview: Boolean = false,
        includeDeletedCard: Boolean = false,
        keyboardMode: Boolean = false,
    ) {
        setContent {
            if (keyboardMode) {
                val inputModes = LocalInputModeManager.current
                SideEffect { inputModes.requestInputMode(InputMode.Keyboard) }
            }
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(24.dp)) {
                    MessageRowWithArtifactCard(
                        summary = summary,
                        deleted = deleted.takeIf { includeDeletedCard },
                        contextMenuEnabled = contextMenuEnabled,
                        preview = preview,
                        recorder = recorder,
                    )
                }
            }
        }
        waitForIdle()
    }

    @Composable
    private fun MessageRowWithArtifactCard(
        summary: ArtifactSummary,
        deleted: ArtifactDeletedShown?,
        contextMenuEnabled: Boolean,
        preview: Boolean,
        recorder: HarnessRecorder,
    ) {
        val owner = rememberThreadMessageLongPressGestureOwner(instanceKey = "artifact-card-menu", messageId = MESSAGE_ID)
        val childContextMenus = remember { ThreadMessageChildContextMenus() }
        val childLongPress =
            remember(owner) {
                ThreadMessageChildLongPressHandler { source, position ->
                    owner.showActionsForChild(source, position, childRole = "artifact_card")
                }
            }
        Box(
            Modifier
                .width(440.dp)
                .onGloballyPositioned { childContextMenus.rowCoordinates = it }
                .threadMessageLongPressActions(
                    messageId = MESSAGE_ID,
                    pointerSessionKey = 1,
                    gestureOwner = owner,
                    onShowActions = { recorder.rowLongPressMenus += 1 },
                    onContextClick = { recorder.rowContextClicks += 1 },
                    deferContextClickAt = { offset -> childContextMenus.containsRowOffset(offset) },
                ),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftText("See the plan", modifier = Modifier.testTag(MESSAGE_TEXT_TAG))
                CompositionLocalProvider(
                    LocalThreadMessageChildContextMenus provides childContextMenus,
                    LocalThreadMessageChildLongPress provides childLongPress.takeIf { contextMenuEnabled },
                ) {
                    ArtifactMessageCard(
                        summary = summary,
                        onOpen = { recorder.cardOpens += 1 },
                        onCopyLink = { recorder.cardCopyLinks += 1 },
                        contextMenuEnabled = contextMenuEnabled,
                        preview = preview,
                    )
                    if (deleted != null) {
                        ArtifactDeletedCard(deleted)
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.rightClick(tag: String) {
        onNodeWithTag(tag).performMouseInput {
            moveTo(Offset(width * 0.3f, height * 0.5f))
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        waitForIdle()
    }

    private fun ComposeUiTest.menuItemTexts(): List<String> =
        listOf(ArtifactCardMenuTags.Open, ArtifactCardMenuTags.CopyLink)
            .map { tag ->
                onNodeWithTag(tag)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .joinToString("") { it.text }
            }

    @Test
    fun rightClickOnLiveCardOpensArtifactMenuAndDispatchesEachItem() = runRobolectricComposeUiTest {
        val recorder = HarnessRecorder()
        mountMessageRow(recorder)

        onNodeWithContentDescription("Open in new page").assertDoesNotExist()

        rightClick(ArtifactCardMenuTags.Card)
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertIsDisplayed()
        assertEquals(listOf("Open", "Copy link"), menuItemTexts())
        onNodeWithText("Open in new page").assertDoesNotExist()
        assertEquals(0, recorder.rowContextClicks, "right-click on card must not open the message row menu")
        assertEquals(0, recorder.cardOpens, "right-click must not also tap the card")

        onNodeWithTag(ArtifactCardMenuTags.Open).performClick()
        waitForIdle()
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertDoesNotExist()
        assertEquals(1, recorder.cardOpens)

        rightClick(ArtifactCardMenuTags.Card)
        onNodeWithTag(ArtifactCardMenuTags.CopyLink).performClick()
        waitForIdle()
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertDoesNotExist()
        assertEquals(1, recorder.cardCopyLinks)
        assertEquals(0, recorder.rowContextClicks)
    }

    @Test
    fun shiftF10AndMenuKeyOpenArtifactCardMenuOnFocusedCard() = runRobolectricComposeUiTest {
        val recorder = HarnessRecorder()
        mountMessageRow(recorder, keyboardMode = true)

        onNodeWithTag(ArtifactCardMenuTags.Card).requestFocus()
        waitForIdle()
        onNodeWithTag(ArtifactCardMenuTags.Card).assertIsFocused()

        onNodeWithTag(ArtifactCardMenuTags.Card).performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) }
        }
        waitForIdle()
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertIsDisplayed()

        onNodeWithTag(ArtifactCardMenuTags.Open).performClick()
        waitForIdle()
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertDoesNotExist()
        assertEquals(1, recorder.cardOpens)
        onNodeWithTag(ArtifactCardMenuTags.Card).assertIsFocused()

        onNodeWithTag(ArtifactCardMenuTags.Card).performKeyInput { pressKey(Key.Menu) }
        waitForIdle()
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertIsDisplayed()
        onNodeWithTag(ArtifactCardMenuTags.CopyLink).performClick()
        waitForIdle()
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertDoesNotExist()
        assertEquals(1, recorder.cardCopyLinks)
        assertEquals(0, recorder.rowContextClicks)
    }

    @Test
    fun rightClickOutsideCardOpensMessageMenuNotArtifactMenu() = runRobolectricComposeUiTest {
        val recorder = HarnessRecorder()
        mountMessageRow(recorder)

        rightClick(MESSAGE_TEXT_TAG)
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertDoesNotExist()
        assertEquals(1, recorder.rowContextClicks)
    }

    @Test
    fun touchLongPressOnLiveCardOpensMessageMenuNotArtifactMenu() = runRobolectricComposeUiTest {
        val recorder = HarnessRecorder()
        mountMessageRow(recorder)

        onNodeWithTag(ArtifactCardMenuTags.Card).performTouchInput {
            longClick(Offset(width * 0.3f, height * 0.5f))
        }
        waitForIdle()
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertDoesNotExist()
        assertEquals(1, recorder.rowLongPressMenus, "touch long press on card opens the message row menu")
        assertEquals(0, recorder.cardOpens, "releasing a touch long press must not open the Artifact")
    }

    @Test
    fun selectModePreviewAndDeletedCardsFallThroughToMessageRow() = runRobolectricComposeUiTest {
        val selectMode = HarnessRecorder()
        var contextMenuEnabled by mutableStateOf(false)
        setContent {
            val inputModes = LocalInputModeManager.current
            SideEffect { inputModes.requestInputMode(InputMode.Keyboard) }
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(24.dp)) {
                    MessageRowWithArtifactCard(
                        summary = summary,
                        deleted = deleted,
                        contextMenuEnabled = contextMenuEnabled,
                        preview = false,
                        recorder = selectMode,
                    )
                }
            }
        }
        waitForIdle()

        rightClick(ArtifactCardMenuTags.Card)
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertDoesNotExist()
        assertEquals(1, selectMode.rowContextClicks, "select mode leaves right-click to the message row")

        onNodeWithTag(ArtifactCardMenuTags.Card).requestFocus()
        waitForIdle()
        onNodeWithTag(ArtifactCardMenuTags.Card).performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) }
        }
        waitForIdle()
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertDoesNotExist()

        // Leaving select mode enables the live card menu, while a deleted card still belongs to the row.
        contextMenuEnabled = true
        waitForIdle()
        rightClick(ArtifactCardMenuTags.DeletedCard)
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertDoesNotExist()
        assertEquals(2, selectMode.rowContextClicks, "deleted card leaves right-click to the message row")
    }

    @Test
    fun previewCardHasNoContextMenuAndFallsThroughToMessageRow() = runRobolectricComposeUiTest {
        val recorder = HarnessRecorder()
        mountMessageRow(recorder, contextMenuEnabled = true, preview = true)

        rightClick(ArtifactCardMenuTags.Card)
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertDoesNotExist()
        assertEquals(1, recorder.rowContextClicks, "preview card leaves right-click to the message row")
    }

    @Test
    fun viewerBarHidesMoreMenuWithoutDesktopModeAndOmitsOpenInBrowser() = runRobolectricComposeUiTest {
        var desktopMode by mutableStateOf<Boolean?>(null)
        var toggles = 0
        setContent {
            RaftDesignSystemTheme {
                RaftArtifactViewerFrame(
                    title = "Q3 revenue report",
                    onBack = {},
                    actions = RaftArtifactViewerBarActions(onToggleDesktopMode = { toggles += 1 }),
                    desktopMode = desktopMode,
                    topSafeAreaOverride = 0.dp,
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
        onNodeWithContentDescription("More actions").assertDoesNotExist()

        desktopMode = false
        waitForIdle()
        onNodeWithContentDescription("More actions").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText("Desktop mode").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText("Open in browser").assertDoesNotExist()
        assertEquals(1, toggles)
    }

    @Test
    fun messageCardMenuShowsFollowUnfollowDisablesWhilePendingAndOmitsForForeignPublic() = runRobolectricComposeUiTest {
        var cardSummary by mutableStateOf(summary.copy(following = true))
        var pending by mutableStateOf(false)
        val toggled = mutableListOf<Boolean>()
        setContent {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(24.dp)) {
                    Box(Modifier.width(440.dp)) {
                        ArtifactMessageCard(
                            summary = cardSummary,
                            onOpen = {},
                            onCopyLink = {},
                            followPending = pending,
                            onToggleFollow = { toggled += it },
                        )
                    }
                }
            }
        }
        waitForIdle()

        // Following = true -> Open, Copy link, Unfollow; clicking dispatches false.
        rightClick(ArtifactCardMenuTags.Card)
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertIsDisplayed()
        val copyTop = onNodeWithTag(ArtifactCardMenuTags.CopyLink).fetchSemanticsNode().boundsInRoot.top
        val followTop = onNodeWithTag(ArtifactCardMenuTags.Follow).fetchSemanticsNode().boundsInRoot.top
        assertEquals(true, copyTop < followTop, "Copy link precedes Unfollow")
        assertEquals("Unfollow", onNodeWithTag(ArtifactCardMenuTags.Follow).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text })
        onNodeWithTag(ArtifactCardMenuTags.Follow).performClick()
        waitForIdle()
        assertEquals(listOf(false), toggled)

        // Following = false -> Follow; disabled while pending.
        cardSummary = summary.copy(following = false)
        pending = true
        waitForIdle()
        rightClick(ArtifactCardMenuTags.Card)
        assertEquals("Follow", onNodeWithTag(ArtifactCardMenuTags.Follow).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text })
        onNodeWithTag(ArtifactCardMenuTags.Follow).assertIsNotEnabled()

        // Dismiss menu by toggling pending and reopening for Follow dispatch.
        onNodeWithTag(ArtifactCardMenuTags.Open).performClick()
        waitForIdle()
        pending = false
        waitForIdle()
        rightClick(ArtifactCardMenuTags.Card)
        onNodeWithTag(ArtifactCardMenuTags.Follow).performClick()
        waitForIdle()
        assertEquals(listOf(false, true), toggled)

        // Cross-server public card omits Follow/Unfollow.
        cardSummary = summary.copy(home = ArtifactHome.Public, fromAnotherServer = true, following = false)
        waitForIdle()
        rightClick(ArtifactCardMenuTags.Card)
        onNodeWithTag(ArtifactCardMenuTags.Menu).assertIsDisplayed()
        onNodeWithTag(ArtifactCardMenuTags.Follow).assertDoesNotExist()
    }

    private companion object {
        const val MESSAGE_ID = "00000000-0000-0000-0000-000000000382"
        const val MESSAGE_TEXT_TAG = "message-text"
    }
}
