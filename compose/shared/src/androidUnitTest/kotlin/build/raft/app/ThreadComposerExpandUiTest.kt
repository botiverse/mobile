package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.RaftComposerSendKey
import build.raft.app.data.SharedMembersLoadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Expandable wide-viewport conversation composer (botiverse/slock #16670 & #16767):
 * - Expand keeps the framed ink card, grows over the message list (leaving a 120dp strip), adds a
 *   top Markdown format row with Collapse at its end (Esc in tooltip only), and removes the bottom
 *   Expand button; Collapse brings it back keeping the same editor, draft, and focus.
 * - Esc collapses the expanded composer after any open suggestion list has taken its own Esc.
 * - While expanded, Enter inserts a newline and Ctrl/⌘+Enter sends for every account, with the
 *   send-key hint shown even when the draft is empty.
 * - Hidden on phone widths and when the host does not provide [LocalThreadComposerExpandState].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h700dp-xhdpi-keysexposed-qwerty")
class ThreadComposerExpandUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun wideExpandAndCollapseGrowsComposerKeepsDraftAndFocus() =
        runRobolectricComposeUiTest {
            val events = showComposer(expandable = true)
            val editor = onNode(hasSetTextAction())
            editor.performTextInput("a long message\nsecond line")
            waitForIdle()

            onNodeWithTag(ThreadComposerExpandedSurfaceTestTag, useUnmergedTree = true).assertDoesNotExist()
            val collapsedComposerBounds = onNodeWithTag(ThreadComposerCardTestTag).getUnclippedBoundsInRoot()
            val collapsedComposerHeight = collapsedComposerBounds.bottom - collapsedComposerBounds.top

            onNodeWithTag(ThreadComposerExpandToggleTestTag, useUnmergedTree = true).assertExists()
            onNodeWithContentDescription("Expand").assertExists().performClick()
            waitForIdle()

            onNodeWithTag(ThreadComposerExpandedSurfaceTestTag, useUnmergedTree = true).assertExists()
            onNodeWithTag(ThreadComposerFormatBarTestTag, useUnmergedTree = true).assertExists()
            onNodeWithTag(ThreadComposerCollapseButtonTestTag, useUnmergedTree = true).assertExists()
            onNodeWithTag(ThreadComposerExpandToggleTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(ThreadComposerSendKeyHintTestTag, useUnmergedTree = true).assertExists()
            onNodeWithText("Esc", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithContentDescription("Collapse").assertExists()
            editor.assertIsFocused()
            assertEquals("a long message\nsecond line", events.valueOf("change"))

            val expandedListBounds = onNodeWithTag(MessageListTag).getUnclippedBoundsInRoot()
            val expandedListHeight = expandedListBounds.bottom - expandedListBounds.top
            assertTrue(
                expandedListHeight <= ThreadComposerExpandedMessageListStripHeight + 2.dp,
                "Expected ~120dp list strip while expanded, got $expandedListHeight",
            )
            val expandedComposerBounds = onNodeWithTag(ThreadComposerCardTestTag).getUnclippedBoundsInRoot()
            val expandedComposerHeight = expandedComposerBounds.bottom - expandedComposerBounds.top
            assertTrue(
                expandedComposerHeight > collapsedComposerHeight + 200.dp,
                "Expected expanded composer ($expandedComposerHeight) to grow well past collapsed ($collapsedComposerHeight)",
            )

            onNodeWithContentDescription("Collapse").performClick()
            waitForIdle()

            onNodeWithTag(ThreadComposerExpandedSurfaceTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(ThreadComposerFormatBarTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithContentDescription("Expand").assertExists()
            editor.assertIsFocused()
            assertEquals("a long message\nsecond line", events.valueOf("change"))
        }

    @Test
    fun escClosesMentionListFirstThenCollapsesExpandedComposer() =
        runRobolectricComposeUiTest {
            val events = showComposer(expandable = true, members = listOf(ADA))
            val editor = onNode(hasSetTextAction())

            onNodeWithContentDescription("Expand").performClick()
            waitForIdle()
            onNodeWithTag(ThreadComposerExpandedSurfaceTestTag, useUnmergedTree = true).assertExists()

            editor.performTextInput("@")
            waitForIdle()
            onNodeWithText(ADA.displayName, useUnmergedTree = true).assertExists("typing @ opens the member list")

            editor.performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithText(ADA.displayName, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(ThreadComposerExpandedSurfaceTestTag, useUnmergedTree = true)
                .assertExists("first Esc closes the suggestion list and keeps composer expanded")

            editor.performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithTag(ThreadComposerExpandedSurfaceTestTag, useUnmergedTree = true).assertDoesNotExist()
            assertEquals("@", events.valueOf("change"), "Esc keeps the draft")
        }

    @Test
    fun expandedEnterInsertsNewlineAndModEnterSendsAndCollapsesComposer() =
        runRobolectricComposeUiTest {
            val events = showComposer(expandable = true)
            val editor = onNode(hasSetTextAction())

            onNodeWithContentDescription("Expand").performClick()
            waitForIdle()
            onNodeWithTag(ThreadComposerExpandedSurfaceTestTag, useUnmergedTree = true).assertExists()
            // Send-key hint shows immediately while expanded, even on an empty box.
            onNodeWithTag(ThreadComposerSendKeyHintTestTag, useUnmergedTree = true).assertExists()

            editor.performTextInput("sent from the big box")
            waitForIdle()

            // Expanded: plain Enter inserts a newline instead of sending.
            editor.performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertTrue(events.none { it.first == "send" }, "Plain Enter must not send while expanded: $events")
            onNodeWithTag(ThreadComposerExpandedSurfaceTestTag, useUnmergedTree = true).assertExists()

            // Ctrl+Enter sends and collapses the composer.
            editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
            waitForIdle()

            assertEquals(1, events.count { it.first == "send" })
            assertTrue(events.valueOf("send")?.startsWith("sent from the big box") == true)
            onNodeWithTag(ThreadComposerExpandedSurfaceTestTag, useUnmergedTree = true).assertDoesNotExist()

            // Once collapsed, the account's Enter-to-send mode applies again.
            editor.performTextInput("collapsed again")
            waitForIdle()
            editor.performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals(2, events.count { it.first == "send" })
        }

    @Test
    fun expandedFormatToolbarInsertsMarkdownAndKeepsFocusInEditor() =
        runRobolectricComposeUiTest {
            val events = showComposer(expandable = true)
            val editor = onNode(hasSetTextAction())

            onNodeWithContentDescription("Expand").performClick()
            waitForIdle()
            onNodeWithTag(ThreadComposerFormatBarTestTag, useUnmergedTree = true).assertExists()

            editor.performTextInput("hello")
            waitForIdle()

            onNodeWithContentDescription("Bulleted list").assertExists().performClick()
            waitForIdle()
            assertEquals("- hello", events.valueOf("change"))
            editor.assertIsFocused()

            onNodeWithContentDescription("Bold").assertExists().performClick()
            waitForIdle()
            assertEquals("- hello****", events.valueOf("change"))
            editor.assertIsFocused()
        }

    @Test
    fun nonExpandableHostHidesExpandButtonOnWideWindow() =
        runRobolectricComposeUiTest {
            showComposer(expandable = false)
            onNodeWithTag(ThreadComposerExpandToggleTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithContentDescription("Expand").assertDoesNotExist()
            onNodeWithTag(ThreadComposerExpandedSurfaceTestTag, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    @Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi-keysexposed-qwerty")
    fun phoneViewportHidesExpandButton() =
        runRobolectricComposeUiTest {
            showComposer(expandable = true)
            onNodeWithTag(ThreadComposerExpandToggleTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithContentDescription("Expand").assertDoesNotExist()
            onNodeWithTag(ThreadComposerExpandedSurfaceTestTag, useUnmergedTree = true).assertDoesNotExist()
        }

    private fun ComposeUiTest.showComposer(
        expandable: Boolean,
        members: List<RaftComposerMemberItem> = emptyList(),
    ): List<Pair<String, String?>> {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val events = mutableListOf<Pair<String, String?>>()
        setContent {
            val portal = remember { ThreadComposerSuggestionPortalState() }
            val expandState = remember(expandable) { if (expandable) ThreadComposerExpandState() else null }
            val surface = remember { ThreadComposerSurface(channelId = "chan-expand") }
            val expanded =
                expandState?.isExpanded(
                    conversationKey = surface.expandConversationKey,
                    canExpand = threadComposerCanExpand(expandable = true, windowWidthDp = raftWindowWidth()),
                ) == true
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalThreadComposerSuggestionPortal provides portal,
                    LocalThreadComposerExpandState provides expandState,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            modifier =
                                (if (expanded) {
                                    Modifier.fillMaxWidth().height(ThreadComposerExpandedMessageListStripHeight)
                                } else {
                                    Modifier.fillMaxWidth().weight(1f)
                                }).testTag(MessageListTag)
                        )
                        ThreadComposerDock(
                            modifier = if (expanded) Modifier.weight(1f) else Modifier,
                            expanded = expanded,
                        ) {
                            ThreadComposerContent(
                                surface = surface,
                                state =
                                    ThreadComposerUiState(
                                        instanceId = "expand-ui-test",
                                        memberSuggestions = members,
                                        memberSuggestionsStatus =
                                            if (members.isNotEmpty()) {
                                                SharedMembersLoadStatus.Loaded
                                            } else {
                                                SharedMembersLoadStatus.Idle
                                            },
                                    ),
                                onEvent = { action, value, _, _ -> events += action to value },
                                onKeyboardHeightChange = {},
                                contentPaddingTop = 0.dp,
                                contentPaddingBottom = 0.dp,
                                composerSendKeyEnabled = true,
                                composerSendKey = RaftComposerSendKey.Enter,
                                hardwareKeyboardConnected = true,
                                attachmentPreviewEnabled = true,
                            )
                        }
                    }
                }
                ThreadComposerSuggestionPortalHost(portal)
            }
        }
        return events
    }

    private fun List<Pair<String, String?>>.valueOf(action: String): String? =
        lastOrNull { it.first == action }?.second

    private companion object {
        const val MessageListTag = "expand-test-message-list"
        val ADA =
            RaftComposerMemberItem(
                displayName = "Ada Lovelace",
                handle = "ada",
                memberId = "0a0a0a0a-0000-4000-8000-000000000001",
            )
    }
}
