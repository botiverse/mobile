package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.resources.*
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone image editor (opened from the image viewer's ⋯ > Edit): crop mode with the
 * 1:1 aspect picked, and pen mode with the pen options open over a picture turned a quarter turn
 * with a thin and a thick stroke; Rotate joins the tools in the bottom bar. Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class ComposerImageEditorScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun cropModeLight() = capture("file.preview.image-editor.crop", RaftAppAppearance.Light) { image ->
        EditorPage(
            image = image,
            history =
                RaftComposerImageEditorHistory(
                    content =
                        RaftComposerImageEditorContent(
                            cropRect = raftCropRectForAspect(RaftComposerImageCropRect.Uncropped, 1f, image.width, image.height),
                        )
                ),
            tool = RaftComposerImageEditorTool.Crop,
            aspect = RaftComposerImageCropAspect.Square,
        )
    }

    @Test
    fun penModeLight() = capture("file.preview.image-editor.pen", RaftAppAppearance.Light) { image ->
        EditorPage(image = image, history = penHistory(), tool = RaftComposerImageEditorTool.Pencil, penOptions = true)
    }

    @Test
    fun penModeDark() = capture("file.preview.image-editor.pen.dark", RaftAppAppearance.Dark) { image ->
        EditorPage(image = image, history = penHistory(), tool = RaftComposerImageEditorTool.Pencil, penOptions = true)
    }

    private fun penHistory(): RaftComposerImageEditorHistory {
        // Canvas pixels of the turned 4:3 picture on the 390dp stage; the exact spot only matters
        // for the golden.
        val thin =
            RaftComposerImageStroke(
                points = listOf(Offset(120f, 160f), Offset(260f, 240f), Offset(420f, 200f)),
                color = Color(0xFFFF3B30), // raft-color: image-editor pen palette
            )
        val thick =
            RaftComposerImageStroke(
                points = listOf(Offset(140f, 420f), Offset(320f, 380f), Offset(520f, 460f)),
                color = Color(0xFF32ADE6), // raft-color: image-editor pen palette
                width = RaftComposerImagePenWidth.Thick.px,
            )
        val content =
            RaftComposerImageEditorContent(rotationQuarterTurns = 1)
                .raftComposerImageAppendStroke(thin)
                .raftComposerImageAppendStroke(thick)
        return RaftComposerImageEditorHistory(content = content, undoStack = listOf(RaftComposerImageEditorContent()))
    }

    @Composable
    private fun EditorPage(
        image: ImageBitmap,
        history: RaftComposerImageEditorHistory,
        tool: RaftComposerImageEditorTool,
        aspect: RaftComposerImageCropAspect = RaftComposerImageCropAspect.Free,
        penOptions: Boolean = false,
    ) {
        val state = remember { RaftComposerImageEditorState(history) }
        RaftFilePreviewPageScaffold(
            title = stringResource(Res.string.file_preview_editor_title),
            iconName = RaftLucideIconName.Pencil,
            onBack = {},
            contentPadding = 0.dp,
            trailingContent = { RaftFilePreviewImageEditorHistoryActions(state = state, saving = false) },
        ) {
            RaftFilePreviewComposerImageEditor(
                payload = payload,
                state = state,
                saving = false,
                onCaptureRef = {},
                onDone = {},
                initialTool = tool,
                initialPencilOptionsExpanded = penOptions,
                initialCropAspect = aspect,
                previewImage = image,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }

    private fun capture(
        name: String,
        appearance: RaftAppAppearance,
        content: @Composable (ImageBitmap) -> Unit,
    ) {
        val image = testPicture()
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                    content(image)
                }
            }
        }
    }

    /** A 1200x900 "photo": sky, sun and a dark hill, so crop and turn read at a glance. */
    private fun testPicture(): ImageBitmap {
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

    private val payload =
        RaftFilePreviewPayload(
            kind = "image",
            title = "Image preview",
            name = "IMG_2042.jpg",
            imageUrl = "file:///test/IMG_2042.jpg",
            composerImageActionId = "test-image-editor",
            composerImageFileRef = "test-image-editor-file",
            composerImageCanEdit = true,
            composerImageCanComplete = true,
        )
}
