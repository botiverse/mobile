package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.formatRaftTimestampLabel
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.components.LocalRaftFrameInsets
import build.raft.designsystem.components.LocalRaftWindowTitleBar
import build.raft.designsystem.components.RaftFrameInsets
import build.raft.designsystem.components.RaftWindowTitleBar
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The desktop / wide image lightbox on a real composition: Left / Right step through the
 * conversation's images and stop at its ends, Escape closes, a click on the backdrop or on the
 * page outside the image closes, a click on the image does not. Window: a 1280x800dp desktop.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class RaftImageLightboxUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Calls {
        var dismissed = 0
        val downloads = mutableListOf<String>()
    }

    private fun ComposeUiTest.showLightbox(
        calls: Calls,
        index: Int = 0,
        items: List<RaftImageLightboxItem> = lightboxFixtureItems,
        frameInsets: RaftFrameInsets = RaftFrameInsets.Zero,
        topSafeArea: Dp = 0.dp,
        timeContext: RaftTimeContext? = null,
        titleBar: RaftWindowTitleBar = RaftWindowTitleBar.None
    ) {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent {
            CompositionLocalProvider(
                LocalRaftFrameInsets provides frameInsets,
                LocalRaftWindowTitleBar provides titleBar,
                LocalRaftTimeContextProvider provides timeContext?.let { RaftTimeContextProvider(it) }
            ) {
                RaftImageLightboxContent(
                    request = RaftImageLightboxRequest(items, index),
                    resolveFullUrl = { null },
                    onDismiss = { calls.dismissed++ },
                    onDownload = { calls.downloads += it.id },
                    onOpenWith = {},
                    topSafeArea = topSafeArea,
                    pageImage = { item, _, onContentSize -> LightboxFixtureImage(item, onContentSize) }
                )
            }
        }
        mainClock.advanceTimeBy(500)
    }

    private fun ComposeUiTest.press(key: Key) {
        onNodeWithTag(RaftImageLightboxTag).performKeyInput { pressKey(key) }
        mainClock.advanceTimeBy(1_000)
    }

    private fun ComposeUiTest.assertCounter(text: String) =
        onNodeWithTag(RaftImageLightboxCounterTag).assertTextEquals(text)

    @Test
    fun arrowKeysStepThroughTheImagesAndStopAtTheEnds() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showLightbox(calls)
            assertCounter("1 of 3")
            press(Key.DirectionLeft)
            assertCounter("1 of 3")
            press(Key.DirectionRight)
            assertCounter("2 of 3")
            press(Key.DirectionRight)
            assertCounter("3 of 3")
            press(Key.DirectionRight)
            assertCounter("3 of 3")
            press(Key.DirectionLeft)
            assertCounter("2 of 3")
            assertEquals(0, calls.dismissed, "stepping must not close")
        }

    @Test
    fun escapeCloses() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showLightbox(calls, index = 1)
            press(Key.Escape)
            assertEquals(1, calls.dismissed)
        }

    @Test
    fun aClickOnTheBackdropCloses() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showLightbox(calls)
            // Bottom-left corner: below the pages, left of them (the side gutter).
            onNodeWithTag(RaftImageLightboxTag).performMouseInput { click(Offset(8f, height - 8f)) }
            mainClock.advanceTimeBy(1_000)
            assertEquals(1, calls.dismissed)
        }

    @Test
    fun aClickBesideTheImageClosesAndOnTheImageDoesNot() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showLightbox(calls)
            // A 4:3 image fitted in the 1152x688 page leaves ~117dp of page either side of it.
            onNodeWithTag(raftImageLightboxPageTag("img-1")).performMouseInput { click(center) }
            mainClock.advanceTimeBy(1_000)
            assertEquals(0, calls.dismissed, "a click on the image must not close")
            onNodeWithTag(raftImageLightboxPageTag("img-1")).performMouseInput { click(Offset(20f, centerY)) }
            mainClock.advanceTimeBy(1_000)
            assertEquals(1, calls.dismissed)
        }

    @Test
    fun downloadActsOnTheShownImage() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showLightbox(calls)
            press(Key.DirectionRight)
            // The Download button is the first icon button of the bar.
            onNodeWithTagContentDescription("Download").performMouseInput { click(center) }
            mainClock.advanceTimeBy(500)
            assertEquals(listOf("img-2"), calls.downloads)
            assertEquals(0, calls.dismissed)
        }

    /**
     * artin 11-03: on a Mac the window's controls are drawn over the lightbox's top bar; the host
     * reports them as frame insets, and the bar keeps its sender and its Close out of them.
     */
    @Test
    fun theTopBarKeepsTheSenderAndCloseOutOfTheFrameInsets() =
        runRobolectricComposeUiTest {
            showLightbox(Calls(), frameInsets = RaftFrameInsets(top = 40.dp, left = 80.dp, right = 60.dp))
            val window = onRoot().getBoundsInRoot()
            val sender = onNodeWithTag(RaftImageLightboxSenderTag).getBoundsInRoot()
            val close = onNodeWithTagContentDescription("Close").getBoundsInRoot()
            assertTrue(sender.top >= 40.dp, "sender below the top inset: ${sender.top}")
            assertTrue(sender.left >= 80.dp + 16.dp, "sender right of the left inset: ${sender.left}")
            assertTrue(close.top >= 40.dp, "Close below the top inset: ${close.top}")
            assertTrue(close.right <= window.right - 60.dp, "Close left of the right inset: ${close.right}")
        }

    @Test
    fun closeInsideTheFrameInsetsCloses() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showLightbox(calls, frameInsets = RaftFrameInsets(top = 40.dp, left = 80.dp, right = 60.dp))
            onNodeWithTagContentDescription("Close").performMouseInput { click(center) }
            mainClock.advanceTimeBy(500)
            assertEquals(1, calls.dismissed)
        }

    @Test
    fun theTopBarStartsBelowTheWindowsTopSafeArea() =
        runRobolectricComposeUiTest {
            showLightbox(Calls(), frameInsets = RaftFrameInsets(top = 20.dp), topSafeArea = 52.dp)
            val bar = onNodeWithTag(RaftImageLightboxTopBarTag).getBoundsInRoot()
            val sender = onNodeWithTag(RaftImageLightboxSenderTag).getBoundsInRoot()
            assertEquals(0.dp, bar.top)
            assertEquals(52.dp + 56.dp, bar.bottom, "the bar is its 56dp below the safe area")
            assertTrue(sender.top >= 52.dp, "sender below the safe area: ${sender.top}")
            // The pages start under the bar.
            val page = onNodeWithTag(raftImageLightboxPageTag("img-1")).getBoundsInRoot()
            assertTrue(page.top >= bar.bottom, "page under the bar: ${page.top} < ${bar.bottom}")
        }

    /** The macOS window title bar (#4193): the bar is the title bar's row, its items after the lights. */
    @Test
    fun underAWindowTitleBarTheBarIsItsRowAndCloseStillCloses() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showLightbox(
                calls,
                frameInsets = RaftFrameInsets(top = 48.dp),
                topSafeArea = 48.dp,
                titleBar = RaftWindowTitleBar(height = 48.dp, startInset = 70.dp)
            )
            val bar = onNodeWithTag(RaftImageLightboxTopBarTag).getBoundsInRoot()
            val sender = onNodeWithTag(RaftImageLightboxSenderTag).getBoundsInRoot()
            assertEquals(56.dp, bar.bottom, "one row with the title bar, not under it")
            assertTrue(sender.left >= 70.dp + 16.dp, "sender after the traffic lights: ${sender.left}")
            onNodeWithTagContentDescription("Close").performMouseInput { click(center) }
            mainClock.advanceTimeBy(500)
            assertEquals(1, calls.dismissed)
        }

    @Test
    fun noInsetsKeepTheBarAtTheTopEdge() =
        runRobolectricComposeUiTest {
            showLightbox(Calls())
            val sender = onNodeWithTag(RaftImageLightboxSenderTag).getBoundsInRoot()
            assertEquals(16.dp, sender.left)
            assertTrue(sender.top < 28.dp, "sender in the 56dp bar at the top: ${sender.top}")
        }

    /** artin 11-03: the time read "2026-11-03T03:23:35.313Z"; it is the message header's time. */
    @Test
    fun theTimeIsTheMessageHeadersTimeNotTheWireString() =
        runRobolectricComposeUiTest {
            val wire = "2026-11-03T03:23:35.313Z"
            val context =
                RaftTimeContext(
                    localOffsetMinutes = 8 * 60,
                    todayEpochDay = raftEpochDayForDate(2026, 11, 3),
                    nowEpochMillis = 1_793_680_800_000L // 04:00Z
                )
            val items = listOf(lightboxFixtureItems.first().copy(timeLabel = wire))
            showLightbox(Calls(), items = items, timeContext = context)
            val expected = formatRaftTimestampLabel(wire, context)
            assertNotEquals(wire, expected)
            onNodeWithTag(RaftImageLightboxTimeTag).assertTextEquals(expected)
        }

    private fun ComposeUiTest.onNodeWithTagContentDescription(label: String) =
        onNode(androidx.compose.ui.test.hasContentDescription(label))
}

internal val lightboxFixtureItems =
    listOf(
        RaftImageLightboxItem(
            RaftThreadAttachmentItem(id = "img-1", name = "launch-board.png", mimeType = "image/png", width = 1600, height = 1200),
            senderName = "Lagoon",
            timeLabel = "Today 14:02"
        ),
        RaftImageLightboxItem(
            RaftThreadAttachmentItem(id = "img-2", name = "lightbox.png", mimeType = "image/png", width = 1600, height = 1200),
            senderName = "Artin",
            timeLabel = "Today 14:05"
        ),
        RaftImageLightboxItem(
            RaftThreadAttachmentItem(id = "img-3", name = "pager.png", mimeType = "image/png", width = 1600, height = 1200),
            senderName = "Tenny",
            timeLabel = "Today 14:09"
        )
    )

/** A drawn 4:3 "photo" standing in for the network image, reporting its pixel size. */
@Composable
internal fun LightboxFixtureImage(item: RaftImageLightboxItem, onContentSize: (Size) -> Unit) {
    LaunchedEffect(item.attachment.id) { onContentSize(Size(1600f, 1200f)) }
    val fill =
        when (item.attachment.id) {
            "img-1" -> Color(0xFF3A7BD5)
            "img-2" -> Color(0xFFE0A030)
            else -> Color(0xFF4CAF50)
        }
    Box(Modifier.aspectRatio(4f / 3f).background(fill).testTag("lightbox_fixture_${item.attachment.id}"))
}
