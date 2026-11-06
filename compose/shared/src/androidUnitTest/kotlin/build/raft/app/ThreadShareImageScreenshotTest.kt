package build.raft.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The share image Share preview's Copy / Download hands out, as the Android export draws it
 * ([renderThreadShareImageBitmap]) around a message row captured in share-capture mode: the
 * web's card (slock `selectScreenshot.ts`) — the yellow Raft header bar with its 2dp ink rule and
 * `raft.build`, then the row 18dp in from the sides (16dp padding + the row's 2dp frame), 12dp
 * under the header and 16dp + 6dp above the bottom — on a phone (390dp card) and a desktop
 * chat column (640dp card). Pixel parity with the web export is the connected capture's job
 * (`components.thread.message-share.preview[-desktop]`); these guard the layout.
 *
 * Goldens: `src/androidUnitTest/screenshots/<name>.png`; record with `:shared:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadShareImageScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
    fun phone() = capture("thread.share-image.phone", cardWidthDp = 390)

    @Test
    @Config(sdk = [34], qualifiers = "w680dp-h480dp-xxhdpi")
    fun desktop() = capture("thread.share-image.desktop", cardWidthDp = 640)

    /**
     * artin's case: one message picked that the timeline draws as a continuation (same author
     * within the grouping window, no avatar or name). The image regroups the selection only
     * ([threadShareSelectionSuppressesAuthor], web `shouldShowGroupedMessageHeader`), so its only
     * message carries the author's avatar, name and time.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
    fun continuationPickedAlone() {
        val previous = ShareImageMessage.copy(id = "msg-share-image-run", timestampLabel = "2026-10-21T16:20:00Z")
        val continuation =
            ShareImageMessage.copy(
                id = "msg-share-image-continuation",
                timestampLabel = "2026-10-21T16:21:00Z",
                body = "A follow-up picked on its own: the image still names who said it.",
            )
        val timelineSuppresses = shouldSuppressCompactThreadAuthor(previous, continuation, localOffsetMinutes = 0)
        assertTrue(timelineSuppresses, "the timeline draws the picked row as a continuation")
        val suppress =
            threadShareSelectionSuppressesAuthor(
                timelineSuppressesAuthor = timelineSuppresses,
                selectionActive = true,
                selected = true,
                previousSelected = false,
            )
        assertFalse(suppress, "the image's first message shows its author")
        capture(
            "thread.share-image.continuation",
            cardWidthDp = 390,
            message = continuation,
            suppressRepeatedAuthor = suppress,
            timestampLabel = "4:21 PM",
        )
    }

    /**
     * The off-screen share image (Android, desktop) of a message whose table is wider than a phone
     * and whose code has a line longer than any image: laid out again at the picked width, the
     * table fits it (cells wrap, header included) and the code wraps; nothing is cut at the edge.
     * The viewport is a phone either way: the image no longer depends on the screen's width.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
    fun wideTablePhone() = captureOffscreen("thread.share-image.wide-table.390", cardWidthDp = 390)

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
    fun wideTableWide() = captureOffscreen("thread.share-image.wide-table.768", cardWidthDp = 768)

    /** Share preview with its width picker: Phone (the default), Wide (the web's 768), Custom with its slider. */
    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
    fun previewPhone() = capturePreview("thread.share-preview.width-phone", ThreadShareImageWidthChoice())

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
    fun previewWide() =
        capturePreview("thread.share-preview.width-wide", ThreadShareImageWidthChoice(preset = ThreadShareImageWidth.Wide))

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
    fun previewCustom() =
        capturePreview(
            "thread.share-preview.width-custom",
            ThreadShareImageWidthChoice(preset = ThreadShareImageWidth.Custom, customWidthDp = 560),
        )

    private fun captureOffscreen(name: String, cardWidthDp: Int) {
        var rendered: ImageBitmap? = null
        val image =
            RaftRobolectricScreenshot.render(
                appearance = RaftAppAppearance.Light,
                activities = activities,
                content = {
                    ShareImageHost {
                        ShareImageRenderer(cardWidthDp) { rendered = it }
                    }
                },
            ) { _, _ -> assertNotNull(rendered, "no share image").asAndroidBitmap() }
        // The whole card is the picked width at the image's scale (xxhdpi: 3), whatever the screen.
        assertEquals(cardWidthDp * 3, image.width)
        RaftRobolectricScreenshot.verifyOrRecord(name, image, File("build/reports/thread-screenshots"))
    }

    private fun capturePreview(name: String, choice: ThreadShareImageWidthChoice) {
        RaftRobolectricScreenshot.capture(
            name,
            RaftAppAppearance.Light,
            reportDir = "build/reports/thread-screenshots",
            activities = activities,
            root = { activity -> dialogFittedToItsWindow() ?: activity.window.decorView },
        ) {
            ShareImageHost {
                var image by remember { mutableStateOf<ImageBitmap?>(null) }
                ShareImageRenderer(choice.widthDp) { image = it }
                ThreadSharePreviewModal(
                    messages = listOf(ThreadShareImageWideTableVisualMessage),
                    image = null,
                    onDismiss = {},
                    onCopyMarkdown = {},
                    onDownloadImage = {},
                    offscreen =
                        ThreadSharePreviewOffscreen(
                            image = image,
                            rendering = image == null,
                            choice = choice,
                            onChoiceChange = {},
                        ),
                )
            }
        }
    }

    @Composable
    private fun ShareImageHost(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme { content() }
        }
    }

    @Composable
    private fun ShareImageRenderer(cardWidthDp: Int, onRendered: (ImageBitmap?) -> Unit) {
        ThreadShareImageOffscreenRenderer(
            cardWidthDp = cardWidthDp,
            rowCount = 1,
            renderKey = ThreadShareImageWideTableVisualMessage,
            background = RaftColor.Canvas,
            onRendering = {},
            onRendered = onRendered,
            // Frames only: Robolectric's clock moves with the looper, a coroutine delay does not.
            settle = { repeat(4) { withFrameNanos {} } },
        ) {
            ThreadShareImageMessageRow(
                message = ThreadShareImageWideTableVisualMessage,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                compactConsecutiveMessages = true,
                suppressRepeatedAuthor = false,
            )
        }
    }

    /** As [AnnouncementModalScreenshotTest]: the dialog window, its content measured to the window. */
    private fun dialogFittedToItsWindow(): android.view.View? {
        val decor = ShadowDialog.getLatestDialog()?.window?.decorView ?: return null
        val content = findCompose(decor) ?: return decor
        val parent = content.parent as? android.view.View ?: return decor
        val exactly = android.view.View.MeasureSpec.EXACTLY
        content.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(parent.width, exactly),
            android.view.View.MeasureSpec.makeMeasureSpec(parent.height, exactly),
        )
        content.layout(0, 0, parent.width, parent.height)
        return decor
    }

    private fun findCompose(view: android.view.View): android.view.View? {
        if (view is androidx.compose.ui.platform.ViewRootForTest) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findCompose(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun capture(
        name: String,
        cardWidthDp: Int,
        message: RaftThreadMessageItem = ShareImageMessage,
        suppressRepeatedAuthor: Boolean? = null,
        timestampLabel: String? = null,
    ) {
        var rowSize = IntSize.Zero
        val rowWidth = cardWidthDp.dp - ThreadShareImageRowInset * 2
        val image =
            RaftRobolectricScreenshot.render(
                appearance = RaftAppAppearance.Light,
                activities = activities,
                content = {
                    val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                    CompositionLocalProvider(
                        LocalActivity provides RaftPageActivity(pager),
                        LocalThreadShareCaptureActive provides true,
                    ) {
                        RaftDesignSystemTheme {
                            Box(Modifier.fillMaxSize().wrapContentSize(Alignment.TopStart)) {
                                Box(
                                    Modifier
                                        .width(rowWidth)
                                        .background(RaftColor.SurfaceWhite)
                                        .onGloballyPositioned { rowSize = it.size },
                                ) {
                                    ThreadMessageCard(
                                        message = message,
                                        messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                                        compactConsecutiveMessages = suppressRepeatedAuthor != null,
                                        suppressRepeatedAuthor = suppressRepeatedAuthor == true,
                                        timestampLabel = timestampLabel ?: message.timestampLabel,
                                        onShowActions = {},
                                        onOpenMember = {},
                                        onOpenThread = {},
                                        onOpenTask = {},
                                        onOpenTaskStatusMenu = {},
                                        onToggleReaction = {},
                                        onOpenAttachment = {},
                                        onOpenMarkdownTag = { _, _ -> },
                                        onOpenMarkdownUrl = {},
                                    )
                                }
                            }
                        }
                    }
                },
            ) { activity, root ->
                val row = Bitmap.createBitmap(root, 0, 0, rowSize.width, rowSize.height)
                assertNotNull(renderThreadShareImageBitmap(activity, listOf(row)), "no share image")
            }
        // The card is the row plus 18dp a side at the screen's density (xxhdpi: 3).
        assertEquals((cardWidthDp * 3f).roundToInt(), image.width)
        RaftRobolectricScreenshot.verifyOrRecord(name, image, File("build/reports/thread-screenshots"))
    }

    private companion object {
        val ShareImageMessage =
            RaftThreadMessageItem(
                id = "msg-share-image",
                authorId = "agent-cindy",
                authorName = "Cindy",
                authorDescription = "Keeps visual testing release notes aligned.",
                authorKind = "agent",
                avatarLabel = "CI",
                timestampLabel = "16:20",
                body =
                    "Share image check: the avatar, name, body and link line should sit where the web puts them.\n\n" +
                        "https://github.com/botiverse/mobile/pull/4325",
            )
    }
}
