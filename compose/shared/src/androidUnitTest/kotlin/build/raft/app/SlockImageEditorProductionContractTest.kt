package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockImageEditorProductionContractTest {
    @Test
    fun committedCanvasObservesHistoryInsideTheDrawCallback() {
        val source = commonMainSource("build/raft/app/SlockFilePreview.kt")
        val editor =
            source.window(
                "private fun SlockFilePreviewComposerImageEditor(",
                "private fun SlockFilePreviewImageEditorPrimaryControls(",
            )
        val drawBlock =
            editor.window(
                "// Read history inside the draw callback.",
                "activeMosaicRevision",
            )

        assertContains(
            drawBlock,
            "state.history.content.slockComposerImageResolvedDrawOperations()",
            message =
                "Undo/Redo must be read by Kuikly's draw-time snapshot observer so it invalidates and clears the retained Canvas.",
        )
        assertFalse(
            "val committedContent = state.history.content" in editor,
            "Capturing committed content during composition recreates the stale-pixel Undo failure.",
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
        assertContains(dispatch, "files.all { file ->")
        assertContains(dispatch, "slockFilePreviewIsImageAttachment(")
        assertTrue(
            "ThreadPickedAttachmentComposerAction.OpenImageAnnotation ->" in dispatch,
            "Only the fail-closed resolver result may enter the image editor.",
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
