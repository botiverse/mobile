package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.designsystem.components.RaftSlotKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric UI verification for agent sidebar row per-side unread indicators
 * (`botiverse/slock#17269` + `#17277`):
 * - DM-only shows the Private side badge (lock + count) with side-specific accessibility text.
 * - Lounge-only shows the Lounge side badge (sofa + count) with side-specific accessibility text.
 * - Both sides show Private first then Lounge side by side.
 * - Muted row draws the Private count in quiet grey (`sidebar-item-meta`) while keeping Lounge loud
 *   (`sidebar-item-count`).
 * - Unread belonging to neither side (`unreadCount > 0`, both side counts 0) falls back to the
 *   plain total (`sidebar-agent-unread-total`, `"N unread"`, grey meta when muted), never nothing.
 * - Non-merged plain rows keep the standard single unread count without side badges.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class HomeSidebarAgentUnreadSidesUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun setup() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun mergedAgentRowShowsPerSideIndicatorsInOrderAndRespectsMute() =
        runRobolectricComposeUiTest {
            var unreadCount by mutableIntStateOf(3)
            var dmSideUnread by mutableStateOf<Int?>(3)
            var loungeSideUnread by mutableStateOf<Int?>(0)
            var muted by mutableStateOf(false)

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxWidth()) {
                        HomeSidebarDirectMessageRow(
                            name = "Nova",
                            avatarLabel = "NO",
                            peerType = "agent",
                            memberId = "agent-nova",
                            muted = muted,
                            unreadCount = unreadCount,
                            dmSideUnread = dmSideUnread,
                            loungeSideUnread = loungeSideUnread,
                        )
                    }
                }
            }

            // DM-only unread -> Private badge only.
            waitForIdle()
            val dmOnlyNode = onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadPrivate, useUnmergedTree = true)
            dmOnlyNode.assertIsDisplayed()
            assertEquals(
                listOf("3 unread in Private chat"),
                dmOnlyNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
            )
            assertEquals("sidebar-item-count", dmOnlyNode.fetchSemanticsNode().config.getOrNull(RaftSlotKey))
            onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadLounge, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadTotal, useUnmergedTree = true).assertDoesNotExist()

            // Lounge-only unread -> Lounge badge only.
            runOnIdle {
                unreadCount = 2
                dmSideUnread = 0
                loungeSideUnread = 2
            }
            waitForIdle()
            val loungeOnlyNode = onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadLounge, useUnmergedTree = true)
            loungeOnlyNode.assertIsDisplayed()
            assertEquals(
                listOf("2 unread in the Lounge"),
                loungeOnlyNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
            )
            assertEquals("sidebar-item-count", loungeOnlyNode.fetchSemanticsNode().config.getOrNull(RaftSlotKey))
            onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadPrivate, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadTotal, useUnmergedTree = true).assertDoesNotExist()

            // Both sides -> Private first, then Lounge side by side.
            runOnIdle {
                unreadCount = 3
                dmSideUnread = 1
                loungeSideUnread = 2
            }
            waitForIdle()
            val privateSemantics =
                onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadPrivate, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
            val loungeSemantics =
                onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadLounge, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
            assertTrue(
                privateSemantics.boundsInRoot.left < loungeSemantics.boundsInRoot.left,
                "Private side badge (${privateSemantics.boundsInRoot.left}) must precede Lounge badge (${loungeSemantics.boundsInRoot.left})",
            )
            assertEquals(
                listOf("1 unread in Private chat"),
                privateSemantics.config.getOrNull(SemanticsProperties.ContentDescription),
            )
            assertEquals(
                listOf("2 unread in the Lounge"),
                loungeSemantics.config.getOrNull(SemanticsProperties.ContentDescription),
            )
            onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadTotal, useUnmergedTree = true).assertDoesNotExist()

            // Muted row with both sides -> Private switches to grey meta while Lounge stays loud count sticker.
            runOnIdle {
                unreadCount = 5
                dmSideUnread = 3
                loungeSideUnread = 2
                muted = true
            }
            waitForIdle()
            assertEquals(
                "sidebar-item-meta",
                onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadPrivate, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(RaftSlotKey),
            )
            assertEquals(
                "sidebar-item-count",
                onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadLounge, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(RaftSlotKey),
            )

            // Plain non-merged row (side counts null) -> none of the agent side/total tags render.
            runOnIdle {
                unreadCount = 4
                dmSideUnread = null
                loungeSideUnread = null
                muted = false
            }
            waitForIdle()
            onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadPrivate, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadLounge, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadTotal, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun neitherSideUnreadFallsBackToPlainTotalAndUsesMutedMetaWhenMuted() =
        runRobolectricComposeUiTest {
            var unreadCount by mutableIntStateOf(3)
            var muted by mutableStateOf(false)

            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxWidth()) {
                        HomeSidebarDirectMessageRow(
                            name = "Nova",
                            avatarLabel = "NO",
                            peerType = "agent",
                            memberId = "agent-nova",
                            muted = muted,
                            unreadCount = unreadCount,
                            dmSideUnread = 0,
                            loungeSideUnread = 0,
                        )
                    }
                }
            }

            // Unmuted neither-side fallback -> loud count sticker with "3 unread", no side badges.
            waitForIdle()
            val loudTotal =
                onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadTotal, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
            assertEquals(
                listOf("3 unread"),
                loudTotal.config.getOrNull(SemanticsProperties.ContentDescription),
            )
            assertEquals("sidebar-item-count", loudTotal.config.getOrNull(RaftSlotKey))
            onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadPrivate, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadLounge, useUnmergedTree = true).assertDoesNotExist()

            // Muted neither-side fallback -> grey meta slot with full "120 unread" announcement.
            runOnIdle {
                unreadCount = 120
                muted = true
            }
            waitForIdle()
            val mutedTotal =
                onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadTotal, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
            assertEquals(
                listOf("120 unread"),
                mutedTotal.config.getOrNull(SemanticsProperties.ContentDescription),
            )
            assertEquals("sidebar-item-meta", mutedTotal.config.getOrNull(RaftSlotKey))

            // Cleared unread -> total fallback disappears.
            runOnIdle { unreadCount = 0 }
            waitForIdle()
            onNodeWithTag(AgentLoungeTestTags.SidebarAgentUnreadTotal, useUnmergedTree = true).assertDoesNotExist()
        }
}
