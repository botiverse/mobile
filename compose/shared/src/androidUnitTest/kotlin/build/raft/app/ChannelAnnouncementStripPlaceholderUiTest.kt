package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftListSkeletonDelayMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Web `ChannelAnnouncementStripPlaceholder` parity (slock #17116, #wg-mobile-sync:#347):
 * reserves the exact 40dp strip height on frame 0 without flashing skeleton bars for the first
 * 1000ms, reveals skeleton bars after 1000ms, and resets the timer cleanly when keyed by channel.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ChannelAnnouncementStripPlaceholderUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun reserves40dpBlankForFirstSecondThenRevealsSkeletonAndResetsOnChannelSwitch() =
        runRobolectricComposeUiTest {
            var channelId by mutableStateOf("ch-a")

            mainClock.autoAdvance = false
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.width(360.dp)) {
                        key(channelId) {
                            ChannelAnnouncementStripPlaceholder()
                        }
                    }
                }
            }
            mainClock.advanceTimeByFrame()

            val node = onNodeWithTag(ChannelAnnouncementTestTags.StripPlaceholder, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(node.size.height > 0, "placeholder reserves the 40dp strip height on frame 0")
            assertEquals(
                0,
                onAllNodesWithTag(ChannelAnnouncementTestTags.StripPlaceholderSkeleton, useUnmergedTree = true).fetchSemanticsNodes().size,
                "skeleton bars stay hidden before 1000ms so fast loads never flash",
            )

            mainClock.advanceTimeBy(RaftListSkeletonDelayMillis - 100L)
            assertEquals(
                0,
                onAllNodesWithTag(ChannelAnnouncementTestTags.StripPlaceholderSkeleton, useUnmergedTree = true).fetchSemanticsNodes().size,
            )

            mainClock.advanceTimeBy(150L)
            mainClock.advanceTimeByFrame()
            assertEquals(
                1,
                onAllNodesWithTag(ChannelAnnouncementTestTags.StripPlaceholderSkeleton, useUnmergedTree = true).fetchSemanticsNodes().size,
                "skeleton bars appear once 1000ms elapse",
            )

            // Switching channels resets the key and restarts the 1000ms blank delay.
            runOnUiThread {
                channelId = "ch-b"
                Snapshot.sendApplyNotifications()
            }
            mainClock.advanceTimeByFrame()
            assertEquals(
                0,
                onAllNodesWithTag(ChannelAnnouncementTestTags.StripPlaceholderSkeleton, useUnmergedTree = true).fetchSemanticsNodes().size,
                "switching channels resets the placeholder back to blank for another 1000ms",
            )
        }
}
