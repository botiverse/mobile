package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftThreadOutlineApi
import build.raft.app.data.ThreadOutlineItem
import build.raft.app.data.ThreadOutlinePage
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The thread timeline on a real composition (web conversationOutline.behavior.test.tsx): a touch
 * tablet taps the rail to open the list and a row to jump; a phone-width window shows nothing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadOutlineRailUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /** The server's page: the thread's first message and four replies from people, newest first. */
    private class FakeApi : RaftThreadOutlineApi {
        var reads = 0

        override suspend fun page(channelId: String, beforeSeq: Long?): ThreadOutlinePage {
            reads++
            val replies = (1..4).map { n ->
                ThreadOutlineItem(id = "r$n", seq = 10L + n, createdAt = "2026-11-08T06:0$n:00.000Z", senderId = "u$n", senderName = "Ann", preview = "reply $n")
            }
            val lead = ThreadOutlineItem(id = "lead", seq = 900, createdAt = "2026-11-08T05:00:00.000Z", senderId = "u1", senderName = "Ann", preview = "the question")
            return ThreadOutlinePage(items = replies.reversed() + lead, hasMore = false)
        }
    }

    private fun ComposeUiTest.showOutline(api: FakeApi, scale: RaftScale, jumps: MutableList<String>) =
        setContent {
            RaftTheme(scale = scale, pointerInput = RaftPointerInput.Touch) {
                CompositionLocalProvider(LocalThreadOutlineApi provides api) {
                    Box(Modifier.size(width = 600.dp, height = 700.dp)) {
                        ThreadOutlineHost(
                            channelId = "thread-1",
                            active = true,
                            origin = null,
                            messages = emptyList(),
                            viewport = { ThreadOutlineViewport(emptyList()) },
                            jumpTargetId = null,
                            jumpNonce = 0,
                            listWidth = 600.dp,
                            formatTime = { "06:00" },
                            onJump = { jumps += it },
                        )
                    }
                }
            }
        }

    @Test
    fun aTouchTabletTapsTheRailToOpenTheListAndARowToJumpThere() =
        runRobolectricComposeUiTest {
            val jumps = mutableListOf<String>()
            showOutline(FakeApi(), RaftScale.Wide, jumps)
            onNodeWithTag(ThreadOutlineCardTestTag).assertDoesNotExist()
            onNodeWithTag(ThreadOutlineRailTestTag).assertExists().performClick()
            onNodeWithTag(ThreadOutlineCardTestTag).assertExists()
            onNodeWithTag(threadOutlineRowTestTag("r2")).assertExists().performClick()
            assertEquals(listOf("r2"), jumps)
            // A finger's list closes on the jump.
            onNodeWithTag(ThreadOutlineCardTestTag).assertDoesNotExist()
            onNodeWithTag(ThreadOutlineRailTestTag).assertExists()
        }

    @Test
    fun aPhoneWidthWindowShowsNoOutlineAndReadsNone() =
        runRobolectricComposeUiTest {
            val api = FakeApi()
            showOutline(api, RaftScale.Compact, mutableListOf())
            onNodeWithTag(ThreadOutlineRailTestTag).assertDoesNotExist()
            runOnIdle { assertEquals(0, api.reads) }
        }
}
