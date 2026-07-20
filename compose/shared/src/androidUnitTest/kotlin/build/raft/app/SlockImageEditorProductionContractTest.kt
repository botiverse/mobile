package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class SlockImageEditorProductionContractTest {
    @Test
    fun committedCanvasRemountsAndReplaysForEveryContentRevision() {
        val source = commonMainSource("build/raft/app/SlockFilePreview.kt")
        val editor =
            source.window(
                "private fun SlockFilePreviewComposerImageEditor(",
                "private fun SlockFilePreviewImageEditorPrimaryControls(",
            )
        val committedSurface =
            editor.window(
                "val committedContent = state.history.content",
                "activeMosaicRevision",
            )

        assertContains(
            committedSurface,
            "key(committedContent)",
            message =
                "Every committed content revision must replace Kuikly's retained native Canvas instead of reusing stale pixels.",
        )
        assertContains(
            committedSurface,
            "committedContent.slockComposerImageResolvedDrawOperations()",
            message = "The replacement surface must replay only the current operation stack.",
        )
        assertTrue(
            committedSurface.indexOf("key(committedContent)") < committedSurface.indexOf("Canvas(modifier"),
            "The Canvas itself, not merely its draw lambda, must live inside the revision key boundary.",
        )
    }

    @Test
    fun textDragLivesOnTheMeasuredAnnotationHitTarget() {
        val source = commonMainSource("build/raft/app/SlockFilePreview.kt")
        val editor =
            source.window(
                "private fun SlockFilePreviewComposerImageEditor(",
                "private fun SlockFilePreviewImageEditorPrimaryControls(",
            )
        val annotation =
            editor.window(
                "state.history.content.textAnnotations.forEach { annotation ->",
                "if (tool == SlockComposerImageEditorTool.Pencil",
            )

        assertContains(annotation, ".onGloballyPositioned { coordinates ->")
        assertContains(annotation, ".pointerInput(payload.imageUrl, annotation.id, tool)")
        assertContains(annotation, "val nextPosition = current.position + dragAmount")
    }

    @Test
    fun composerPickerFailsClosedBeforeOpeningImageEditor() {
        val source = commonMainSource("build/raft/app/ThreadPage.kt")
        val dispatch =
            source.window(
                "resolveThreadPickedAttachmentComposerAction(",
                "\"removeAttachment\" ->",
                useLastStart = true,
            )

        assertContains(dispatch, "allFilesAreImages =")
        assertContains(dispatch, "slockComposerImagePickerFilesAreStrictImages(files)")
        assertTrue(
            "ThreadPickedAttachmentComposerAction.OpenImageAnnotation ->" in dispatch,
            "Only the fail-closed resolver result may enter the image editor.",
        )
    }

    @Test
    fun activeMosaicUsesTheSameNewTileDeltaAsCommit() {
        val source = commonMainSource("build/raft/app/SlockFilePreview.kt")
        val editor =
            source.window(
                "private fun SlockFilePreviewComposerImageEditor(",
                "private fun SlockFilePreviewImageEditorPrimaryControls(",
            )
        val activeDraw = editor.window("val activeMosaicSnapshot =", "state.history.content.textAnnotations")

        assertContains(activeDraw, "slockComposerImageActiveMosaicTiles(")
        assertContains(activeDraw, "committedTiles = state.history.content.mosaicTiles")
        assertContains(activeDraw, "val activeStrokeSnapshot = activeStrokePoints.toList()")
        assertContains(activeDraw, "key(activeMosaicRevision, activeStrokeRevision, selectedPenColor)")
        assertContains(activeDraw, "tiles = activeMosaicSnapshot")
        assertContains(activeDraw, "activeStrokeSnapshot.windowed(2)")
        assertTrue(
            activeDraw.indexOf("key(activeMosaicRevision, activeStrokeRevision, selectedPenColor)") <
                activeDraw.indexOf("Canvas(modifier"),
            "The transient Canvas node must be replaced whenever active Mosaic/Pencil state changes or clears.",
        )
    }

    private fun commonMainSource(path: String): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("Could not locate shared commonMain Kotlin sources")
        val file = File(commonMain, path)
        assertTrue(file.isFile, "Missing production source $path")
        return file.readText().replace("\r\n", "\n")
    }

    private fun String.window(
        startMarker: String,
        endMarker: String,
        useLastStart: Boolean = false,
    ): String {
        val start = if (useLastStart) lastIndexOf(startMarker) else indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) { "Missing production window: $startMarker .. $endMarker" }
        return substring(start, end)
    }
}
