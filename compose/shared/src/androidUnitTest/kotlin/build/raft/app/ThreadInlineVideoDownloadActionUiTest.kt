package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Web #16551: the inline video card offers its own Download left of "Open in preview" (the inline
 * player has no download of its own), and neither shows while the message is still sending.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ThreadInlineVideoDownloadActionUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Calls {
        var download = 0
        var preview = 0
    }

    private val attachment =
        RaftThreadAttachmentItem(id = "vid-1", name = "clip.mp4", mimeType = "video/mp4", sizeLabel = "4.2 MB")

    private val host =
        ThreadInlineVideoPreviewHost(
            states = mapOf("vid-1" to ThreadInlineVideoPreviewUiState(ThreadInlineVideoPreviewStatus.Ready, url = "https://cdn.example/vid-1.mp4")),
            requestPreview = { _, _ -> },
        )

    private fun ComposeUiTest.show(pending: Boolean, calls: Calls) {
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadInlineVideoPreviewHost provides host,
                LocalThreadInlineVideoPlayer provides { _, _, _, _, modifier -> Box(modifier) },
            ) {
                RaftDesignSystemTheme {
                    ThreadInlineVideoAttachmentCard(
                        attachment = attachment,
                        onDownload = { calls.download++ },
                        onOpenPreview = { calls.preview++ },
                        pending = pending,
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.shown(description: String): Boolean =
        onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun readyCardDownloadsDirectlyFromTheButtonLeftOfOpenInPreview() = runRobolectricComposeUiTest {
        val calls = Calls()
        show(pending = false, calls = calls)

        val download = onNodeWithContentDescription("Download clip.mp4").assertIsDisplayed()
        val preview = onNodeWithContentDescription("Open clip.mp4 in preview").assertIsDisplayed()
        val downloadBounds = download.getBoundsInRoot()
        val previewBounds = preview.getBoundsInRoot()
        assertTrue(downloadBounds.right <= previewBounds.left, "Download sits left of Open in preview")
        assertEquals(downloadBounds.top, previewBounds.top, "both actions share the top row")

        download.performClick()
        waitForIdle()
        assertEquals(1, calls.download)
        assertEquals(0, calls.preview, "Download does not open the preview")
    }

    @Test
    fun aMessageStillSendingShowsNeitherCornerAction() = runRobolectricComposeUiTest {
        show(pending = true, calls = Calls())

        assertTrue(!shown("Download clip.mp4"), "no Download while the upload is in flight")
        assertTrue(!shown("Open clip.mp4 in preview"), "no Open in preview while the upload is in flight")
    }
}
