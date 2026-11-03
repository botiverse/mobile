package build.raft.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Edit an image of the conversation from the image viewer (artin, task #32), on a phone: the
 * viewer page with ⋯ at the bottom right and its menu open (Edit). Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class ViewerImageEditPhoneScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun viewerMenuLight() = capture("image-viewer.edit-menu", RaftAppAppearance.Light)

    @Test
    fun viewerMenuDark() = capture("image-viewer.edit-menu.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        val image = viewerEditTestPicture()
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    RaftFilePreviewPageScaffold(
                        title = "IMG_2042.jpg",
                        subtitle = "1.8 MB · image/jpeg",
                        iconName = RaftLucideIconName.Image,
                        onBack = {},
                        contentPadding = 0.dp,
                    ) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            RaftViewerImageMoreButton(
                                onSelect = {},
                                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
                            )
                            // The open menu (a popup in the app), placed above the button.
                            RaftMenuSurface(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 68.dp)) {
                                RaftMenuItem(
                                    text = stringResource(Res.string.common_edit),
                                    onClick = {},
                                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Pencil) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The same on the desktop (1440x900, a mouse): (a) the image viewer with Edit at the top right next
 * to Download / Open / Close; the editor over the window (the phone's editor with the desktop
 * chrome) with (b) Pen, (c) Text and (d) Crop at 1:1, each with its popover.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1440dp-h900dp-mdpi")
class ViewerImageEditDesktopScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun viewerEdit() =
        capture("image-viewer.edit.desktop") { _ ->
            CompositionLocalProvider(LocalRaftSpinnerStillStep provides 0) {
                RaftImageLightboxContent(
                    request = RaftImageLightboxRequest(lightboxFixtureItems, index = 1),
                    resolveFullUrl = { null },
                    onDismiss = {},
                    onDownload = {},
                    onOpenWith = {},
                    onEdit = {},
                    pageImage = { item, _, onContentSize -> LightboxFixtureImage(item, onContentSize) }
                )
            }
        }

    @Test
    fun editorPen() =
        capture("image-editor.pen.desktop") { image ->
            DesktopEditor(image, desktopPenHistory(), RaftComposerImageEditorTool.Pencil)
        }

    @Test
    fun editorText() =
        capture("image-editor.text.desktop") { image ->
            val content =
                raftComposerImageAddTextDraft(
                    content = RaftComposerImageEditorContent(),
                    draft = "Ship this one",
                    position = Offset(-120f, -160f),
                    annotationId = 0,
                    color = Color(0xFF32ADE6), // raft-color: image-editor pen palette
                )
            DesktopEditor(image, RaftComposerImageEditorHistory(content = content), RaftComposerImageEditorTool.Text)
        }

    @Test
    fun editorCrop() =
        capture("image-editor.crop.desktop") { image ->
            DesktopEditor(
                image,
                RaftComposerImageEditorHistory(
                    content =
                        RaftComposerImageEditorContent(
                            cropRect = raftCropRectForAspect(RaftComposerImageCropRect.Uncropped, 1f, image.width, image.height)
                        )
                ),
                RaftComposerImageEditorTool.Crop,
                RaftComposerImageCropAspect.Square,
            )
        }

    /** The editor as the viewer opens it on a desktop: the same editor with the desktop chrome. */
    @Composable
    private fun DesktopEditor(
        image: ImageBitmap,
        history: RaftComposerImageEditorHistory,
        tool: RaftComposerImageEditorTool,
        aspect: RaftComposerImageCropAspect = RaftComposerImageCropAspect.Free,
    ) {
        val state = remember { RaftComposerImageEditorState(history) }
        Box(Modifier.fillMaxSize().background(RaftComposerImageDesktopBackdrop)) {
            RaftFilePreviewComposerImageEditor(
                payload = RaftFilePreviewPayload(kind = "image", name = "IMG_2042.jpg", imageUrl = "file:///test/IMG_2042.jpg"),
                state = state,
                saving = false,
                onCaptureRef = {},
                onDone = {},
                initialTool = tool,
                initialCropAspect = aspect,
                previewImage = image,
                desktopChrome = RaftComposerImageEditorDesktopChrome(onCancel = {}),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    private fun desktopPenHistory(): RaftComposerImageEditorHistory {
        val thin =
            RaftComposerImageStroke(
                points = listOf(Offset(160f, 140f), Offset(320f, 230f), Offset(520f, 180f)),
                color = Color(0xFFFF3B30), // raft-color: image-editor pen palette
            )
        val thick =
            RaftComposerImageStroke(
                points = listOf(Offset(180f, 520f), Offset(420f, 470f), Offset(700f, 560f)),
                color = Color(0xFF32ADE6), // raft-color: image-editor pen palette
                width = RaftComposerImagePenWidth.Thick.px,
            )
        val content = RaftComposerImageEditorContent().raftComposerImageAppendStroke(thin).raftComposerImageAppendStroke(thick)
        return RaftComposerImageEditorHistory(content = content, undoStack = listOf(RaftComposerImageEditorContent()))
    }

    private fun capture(name: String, content: @Composable (ImageBitmap) -> Unit) {
        val image = viewerEditTestPicture()
        RaftRobolectricScreenshot.capture(name, RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = RaftAppearance.Light, pointerInput = RaftPointerInput.Fine) {
                    RaftDesignSystemTheme {
                        Box(Modifier.fillMaxSize()) { content(image) }
                    }
                }
            }
        }
    }
}

/** A 1200x900 "photo": sky, sun and a dark hill, so crop and turn read at a glance. */
internal fun viewerEditTestPicture(): ImageBitmap {
    val bitmap = android.graphics.Bitmap.createBitmap(1200, 900, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(253, 224, 71))
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.rgb(244, 114, 182)
    canvas.drawCircle(900f, 220f, 160f, paint)
    paint.color = android.graphics.Color.BLACK
    val hill =
        android.graphics.Path().apply {
            moveTo(0f, 900f)
            lineTo(330f, 480f)
            lineTo(560f, 700f)
            lineTo(760f, 560f)
            lineTo(1200f, 900f)
            close()
        }
    canvas.drawPath(hill, paint)
    return bitmap.asImageBitmap()
}
