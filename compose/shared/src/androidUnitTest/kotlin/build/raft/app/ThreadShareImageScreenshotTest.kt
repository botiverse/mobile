package build.raft.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    private fun capture(name: String, cardWidthDp: Int) {
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
                                        message = ShareImageMessage,
                                        messageBodyTextStyle = raftMessageBodyTextStyle("md"),
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
