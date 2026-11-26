package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.RaftReactionFavoriteStore
import build.raft.app.data.SharedPreferenceStore
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pointer-device hover toolbar quick reaction (botiverse/slock #13955):
 * - shows the viewer's most-used reaction (👍 default), snapshotted when the toolbar appears;
 * - one-click (or keyboard/semantics activation) toggles it directly and reflects pressed state;
 * - resting the pointer 500ms opens the reaction bar without toggling; passing over or holding down
 *   cancels the timer;
 * - touch devices (`RaftPointerInput.Touch`) never show the hover toolbar, and a touch tap on a
 *   hybrid fine-pointer surface opens the reaction bar instead of quick-reacting.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadMessageHoverQuickReactionUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class MemoryPrefs : SharedPreferenceStore {
        private val map = linkedMapOf<String, String>()

        override fun getString(key: String): String? = map[key]

        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private class Harness {
        var now = 100L
        val prefs = MemoryPrefs()
        val favoriteStore = RaftReactionFavoriteStore(storeProvider = { prefs }, nowEpochMillis = { ++now })
        var reactions by mutableStateOf<List<RaftThreadReactionItem>>(emptyList())
        var popupOpen by mutableStateOf(false)
        var shown by mutableStateOf(false)
        val toggledEmojis = mutableListOf<String>()
        var reactionBarOpenCount = 0

        fun toggleLocalReaction(emoji: String) {
            toggledEmojis += emoji
            val existing = reactions.firstOrNull { it.emoji == emoji }
            if (existing != null && existing.selected) {
                reactions = reactions.filterNot { it.emoji == emoji }
            } else {
                reactions = reactions.filterNot { it.emoji == emoji } +
                    RaftThreadReactionItem(emoji = emoji, count = 1, selected = true)
                favoriteStore.recordReactionUsage(OWNER_KEY, emoji)
            }
        }
    }

    @Test
    fun oneClickTogglesDefaultThumbsUpAndReflectsActiveState() =
        runRobolectricComposeUiTest {
            val harness = Harness()
            showRow(harness)

            onNodeWithTag(ROW).performMouseInput { moveTo(center) }
            waitForIdle()
            assertTrue(harness.shown, "mouse over row shows hover toolbar")

            onNodeWithContentDescription("Like").assertExists().assertIsOff()
            onNodeWithTag(THREAD_HOVER_TOOLBAR_QUICK_REACTION_TAG).performMouseInput { click(center) }
            waitForIdle()

            assertEquals(listOf("👍"), harness.toggledEmojis.toList())
            assertEquals(0, harness.reactionBarOpenCount, "clicking toggles directly without opening reaction bar")
            onNodeWithContentDescription("Remove like").assertExists().assertIsOn()

            // Staying over the button after clicking must not unexpectedly open the reaction bar.
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            assertEquals(0, harness.reactionBarOpenCount, "staying parked after click does not open reaction bar")

            // Clicking again removes 👍 and clears active state.
            onNodeWithTag(THREAD_HOVER_TOOLBAR_QUICK_REACTION_TAG).performMouseInput { click(center) }
            waitForIdle()
            assertEquals(listOf("👍", "👍"), harness.toggledEmojis.toList())
            onNodeWithContentDescription("Like").assertExists().assertIsOff()
        }

    @Test
    fun favoriteReactionSnapshotsWhileToolbarShowsAndUpdatesOnNextShow() =
        runRobolectricComposeUiTest {
            val harness = Harness()
            showRow(harness)

            // Toolbar opens with default 👍.
            onNodeWithTag(ROW).performMouseInput { moveTo(center) }
            waitForIdle()
            onNodeWithContentDescription("Like").assertExists()

            // Another favorite becomes dominant while this toolbar stays shown: button must NOT mutate mid-hover.
            repeat(3) { harness.favoriteStore.recordReactionUsage(OWNER_KEY, "😂") }
            harness.reactions = listOf(RaftThreadReactionItem(emoji = "👍", count = 1, selected = true))
            waitForIdle()
            onNodeWithContentDescription("Remove like").assertExists().assertIsOn()

            // Leave the row so the toolbar hides, then re-enter: the fresh toolbar picks up 😂.
            onNodeWithTag(ROW).performMouseInput { exit() }
            waitForIdle()
            assertFalse(harness.shown)

            onNodeWithTag(ROW).performMouseInput { moveTo(center) }
            waitForIdle()
            assertTrue(harness.shown)
            onNodeWithContentDescription("React with 😂").assertExists().assertIsOff()

            // Keyboard / semantics activation toggles 😂 directly.
            onNodeWithTag(THREAD_HOVER_TOOLBAR_QUICK_REACTION_TAG).performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertEquals(listOf("😂"), harness.toggledEmojis.toList())
            onNodeWithContentDescription("Remove 😂").assertExists().assertIsOn()
        }

    @Test
    fun resting500msOpensReactionBarWhilePassingOverOrHoldingPressDoesNot() =
        runRobolectricComposeUiTest {
            val harness = Harness()
            showRow(harness)

            onNodeWithTag(ROW).performMouseInput { moveTo(center) }
            waitForIdle()
            mainClock.autoAdvance = false

            // Passing over for 300ms then moving back off the button opens nothing.
            onNodeWithTag(THREAD_HOVER_TOOLBAR_QUICK_REACTION_TAG).performMouseInput { moveTo(center) }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(300, ignoreFrameDuration = true)
            onNodeWithTag(ROW).performMouseInput { moveTo(center) }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(1_000, ignoreFrameDuration = true)
            assertEquals(0, harness.reactionBarOpenCount, "passing over opens nothing")

            // Resting 200ms then pressing down cancels the hover timer.
            onNodeWithTag(THREAD_HOVER_TOOLBAR_QUICK_REACTION_TAG).performMouseInput { moveTo(center) }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(200, ignoreFrameDuration = true)
            onNodeWithTag(THREAD_HOVER_TOOLBAR_QUICK_REACTION_TAG).performMouseInput { press(MouseButton.Primary) }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(1_000, ignoreFrameDuration = true)
            assertEquals(0, harness.reactionBarOpenCount, "holding mouse press does not open reaction bar")
            // Release outside to cancel the press without clicking, then move back off the button.
            onNodeWithTag(ROW).performMouseInput {
                moveTo(center)
                release(MouseButton.Primary)
            }
            mainClock.advanceTimeByFrame()

            // Fresh hover onto the button: not open at 499ms, opens at 500ms without toggling a reaction.
            onNodeWithTag(THREAD_HOVER_TOOLBAR_QUICK_REACTION_TAG).performMouseInput { moveTo(center) }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(ThreadHoverToolbarReactionHoverOpenDelayMs - 1, ignoreFrameDuration = true)
            assertEquals(0, harness.reactionBarOpenCount, "must not open before 500ms")

            mainClock.advanceTimeBy(1, ignoreFrameDuration = true)
            assertEquals(1, harness.reactionBarOpenCount, "reaction bar opens after resting 500ms")
            assertTrue(harness.popupOpen)
            assertTrue(harness.toggledEmojis.isEmpty(), "hovering must not toggle a reaction")

            // Closing the reaction bar while the cursor stays on the button must not immediately re-open it.
            harness.popupOpen = false
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(1_000, ignoreFrameDuration = true)
            assertEquals(1, harness.reactionBarOpenCount, "closing bar does not bounce back open until pointer exits")
        }

    @Test
    fun touchDevicesShowNoHoverToolbarAndTouchTapOnHybridSurfaceOpensReactionBar() =
        runRobolectricComposeUiTest {
            val touchHarness = Harness()
            showRow(touchHarness, pointerInput = RaftPointerInput.Touch)
            onNodeWithTag(ROW).performMouseInput { moveTo(center) }
            waitForIdle()
            assertFalse(touchHarness.shown, "touch devices never show hover toolbar")
            onNodeWithTag(THREAD_HOVER_TOOLBAR_QUICK_REACTION_TAG).assertDoesNotExist()
        }

    @Test
    fun touchTapOnHybridFinePointerReactionButtonOpensReactionBarInsteadOfReacting() =
        runRobolectricComposeUiTest {
            val harness = Harness()
            showRow(harness, pointerInput = RaftPointerInput.Fine)

            onNodeWithTag(ROW).performMouseInput { moveTo(center) }
            waitForIdle()
            onNodeWithTag(THREAD_HOVER_TOOLBAR_QUICK_REACTION_TAG).performTouchInput { click() }
            waitForIdle()

            assertEquals(1, harness.reactionBarOpenCount, "touch tap opens reaction bar")
            assertTrue(harness.toggledEmojis.isEmpty(), "touch tap does not quick-react")
        }

    private fun ComposeUiTest.showRow(
        harness: Harness,
        pointerInput: RaftPointerInput = RaftPointerInput.Fine,
        canReact: Boolean = true,
    ) {
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftReactionFavoriteStore provides harness.favoriteStore,
                LocalRaftEmojiPickerOwnerKey provides OWNER_KEY,
            ) {
                RaftTheme(appearance = RaftAppearance.Light, pointerInput = pointerInput) {
                    Box(Modifier.fillMaxWidth().padding(top = 24.dp).testTag(ROW)) {
                        ThreadMessageCard(
                            message =
                                RaftThreadMessageItem(
                                    id = "hover-quick-react-1",
                                    authorId = "user-alice",
                                    authorName = "Alice",
                                    authorKind = "human",
                                    avatarLabel = "A",
                                    timestampLabel = "2026-11-26T16:40:00Z",
                                    body = "Hover toolbar quick reaction check.",
                                    reactions = harness.reactions,
                                ),
                            messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                            timestampLabel = "4:40 PM",
                            popupOpen = harness.popupOpen,
                            onShowActions = {},
                            onOpenMember = {},
                            onOpenThread = {},
                            onOpenTask = {},
                            onOpenTaskStatusMenu = {},
                            onToggleReaction = { emoji -> harness.toggleLocalReaction(emoji) },
                            onOpenAttachment = {},
                            onOpenMarkdownTag = { _, _ -> },
                            onOpenMarkdownUrl = {},
                            hoverToolbar =
                                ThreadMessageHoverToolbarActions(
                                    reaction = canReact,
                                    reply = true,
                                    forward = true,
                                    save = true,
                                    saved = false,
                                    more = true,
                                    onShowReactions = {
                                        harness.reactionBarOpenCount += 1
                                        harness.popupOpen = true
                                    },
                                    onForward = {},
                                    onToggleSave = {},
                                    onShownChange = { harness.shown = it },
                                ),
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private companion object {
        const val ROW = "hover-quick-react-row"
        const val OWNER_KEY = "https://api.example|user-1"
    }
}
