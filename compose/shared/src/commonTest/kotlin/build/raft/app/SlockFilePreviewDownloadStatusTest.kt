package build.raft.app

import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.views.PlayState
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockFilePreviewDownloadStatusTest {
    private val englishDownloadTerms =
        SlockFilePreviewDownloadStatusTerms(
            downloading = "Downloading",
            paused = "Paused",
            saving = "Saving"
        )
    private val englishActionTerms =
        SlockFilePreviewActionTerms(
            download = "Download",
            install = "Install",
            open = "Open",
            openWith = "Open with..."
        )
    private val englishVideoTerms =
        SlockFilePreviewVideoStateTerms(
            playing = "Playing",
            buffering = "Buffering",
            paused = "Paused",
            ended = "Ended",
            playbackFailed = "Playback failed",
            ready = "Ready"
        )

    @Test
    fun cachedOpenHoldsAdmissionUntilNativeTerminal() {
        var status = "idle"
        var launches = 0
        fun tapCachedOpen() {
            if (!slockFilePreviewCanStartExternalOperation(status)) return
            status = slockFilePreviewExternalOpenAdmissionStatus()
            launches += 1
        }

        tapCachedOpen()
        tapCachedOpen()
        assertEquals("downloading", status)
        assertEquals(1, launches)

        status = "idle"
        tapCachedOpen()
        assertEquals(2, launches)
    }

    @Test
    fun editorSelectedToolUsesTheActiveIconTone() {
        assertEquals(
            SlockColor.AccentRedArgb,
            slockFilePreviewEditorToolIconColor(selected = true)
        )
    }

    @Test
    fun editorIdleToolUsesTheDefaultIconTone() {
        assertEquals(
            SlockColor.TextBlackArgb,
            slockFilePreviewEditorToolIconColor(selected = false)
        )
    }

    @Test
    fun composerImageEraseRemovesFinalMatchingStroke() {
        val remaining =
            slockComposerImageEraseStrokes(
                strokes =
                    listOf(
                        SlockComposerImageStroke(
                            points =
                                listOf(
                                    Offset(10f, 10f),
                                    Offset(80f, 10f)
                                )
                        )
                    ),
                point = Offset(48f, 12f)
            )

        assertTrue(remaining.isEmpty())
    }

    @Test
    fun composerImageMosaicBrushCreatesAThreeByThreeTileMask() {
        val tiles =
            slockComposerImageMosaicTilesAt(
                point = Offset(35f, 25f),
                tileSizePx = 10f,
                brushRadius = 1,
            )

        assertEquals(9, tiles.size)
        assertTrue(SlockComposerImageMosaicTile(column = 3, row = 2) in tiles)
        assertTrue(SlockComposerImageMosaicTile(column = 2, row = 1) in tiles)
        assertTrue(SlockComposerImageMosaicTile(column = 4, row = 3) in tiles)
    }

    @Test
    fun composerImageDefaultMosaicBrushUsesTheCompactThreeByThreeFootprint() {
        val tiles = slockComposerImageMosaicTilesAt(point = Offset(90f, 90f))

        assertEquals(9, tiles.size)
        assertEquals(4, tiles.minOf { it.column })
        assertEquals(6, tiles.maxOf { it.column })
        assertEquals(4, tiles.minOf { it.row })
        assertEquals(6, tiles.maxOf { it.row })
    }

    @Test
    fun composerImageMosaicAccumulatorMutatesInPlaceWithoutDuplicatingVisitedTiles() {
        val tiles = mutableSetOf<SlockComposerImageMosaicTile>()

        assertTrue(
            slockComposerImageAccumulateMosaicTiles(
                target = tiles,
                point = Offset(35f, 25f),
                tileSizePx = 10f,
                brushRadius = 1,
            )
        )
        assertEquals(9, tiles.size)
        assertFalse(
            slockComposerImageAccumulateMosaicTiles(
                target = tiles,
                point = Offset(35f, 25f),
                tileSizePx = 10f,
                brushRadius = 1,
            )
        )
        assertEquals(9, tiles.size)
    }

    @Test
    fun composerImageMosaicSamplesTheAspectFitSourcePixel() {
        val sample =
            slockComposerImageMosaicSample(
                tile = SlockComposerImageMosaicTile(column = 2, row = 2),
                imageWidth = 100,
                imageHeight = 50,
                canvasWidth = 200f,
                canvasHeight = 200f,
                tileSizePx = 20f,
            )

        assertEquals(
            SlockComposerImageMosaicSample(
                sourceX = 25,
                sourceY = 2,
                destinationX = 40,
                destinationY = 50,
                destinationWidth = 21,
                destinationHeight = 11,
            ),
            sample,
        )
    }

    @Test
    fun composerImageMosaicDoesNotPaintOutsideTheAspectFitImage() {
        assertEquals(
            null,
            slockComposerImageMosaicSample(
                tile = SlockComposerImageMosaicTile(column = 2, row = 0),
                imageWidth = 100,
                imageHeight = 50,
                canvasWidth = 200f,
                canvasHeight = 200f,
                tileSizePx = 20f,
            ),
        )
    }

    @Test
    fun composerImageEraserRemovesNearbyMosaicTiles() {
        val tiles =
            setOf(
                SlockComposerImageMosaicTile(column = 2, row = 2),
                SlockComposerImageMosaicTile(column = 8, row = 8),
            )

        val remaining =
            slockComposerImageEraseMosaicTiles(
                tiles = tiles,
                point = Offset(25f, 25f),
                tileSizePx = 10f,
            )

        assertEquals(setOf(SlockComposerImageMosaicTile(column = 8, row = 8)), remaining)
    }

    @Test
    fun composerImageUndoRestoresThePreviousMosaicMask() {
        val first = SlockComposerImageMosaicTile(column = 1, row = 1)
        val second = SlockComposerImageMosaicTile(column = 2, row = 2)
        val afterFirst =
            SlockComposerImageEditorHistory().apply(
                SlockComposerImageEditorContent(mosaicTiles = setOf(first))
            )
        val afterSecond =
            afterFirst.apply(
                afterFirst.content.copy(mosaicTiles = setOf(first, second))
            )

        assertEquals(setOf(first), afterSecond.undo().content.mosaicTiles)
    }

    @Test
    fun composerImageUndoRemovesTheFirstAndFinalCommittedMosaicGesture() {
        val first = SlockComposerImageMosaicTile(column = 1, row = 1)
        val final = SlockComposerImageMosaicTile(column = 8, row = 8)
        val afterFirst =
            slockComposerImageCommitMosaicGesture(
                history = SlockComposerImageEditorHistory(),
                tiles = setOf(first),
            )
        val afterFinal =
            slockComposerImageCommitMosaicGesture(
                history = afterFirst,
                tiles = setOf(final),
            )

        val withoutFinal = afterFinal.undo()
        assertEquals(setOf(first), withoutFinal.content.mosaicTiles)
        assertEquals(
            listOf(SlockComposerImageDrawOperation.Mosaic(setOf(first))),
            withoutFinal.content.slockComposerImageResolvedDrawOperations(),
        )
        val withoutFirst = withoutFinal.undo()
        assertTrue(withoutFirst.content.mosaicTiles.isEmpty())
        assertTrue(withoutFirst.content.slockComposerImageResolvedDrawOperations().isEmpty())
        assertFalse(withoutFirst.canUndo)

        val restoredFirst = withoutFirst.redo()
        assertEquals(setOf(first), restoredFirst.content.mosaicTiles)
        assertEquals(
            listOf(SlockComposerImageDrawOperation.Mosaic(setOf(first))),
            restoredFirst.content.slockComposerImageResolvedDrawOperations(),
        )
        val restoredFinal = restoredFirst.redo()
        assertEquals(setOf(first, final), restoredFinal.content.mosaicTiles)
        assertEquals(
            listOf(
                SlockComposerImageDrawOperation.Mosaic(setOf(first)),
                SlockComposerImageDrawOperation.Mosaic(setOf(final)),
            ),
            restoredFinal.content.slockComposerImageResolvedDrawOperations(),
        )
    }

    @Test
    fun composerImageDrawOperationsPreserveGestureCommitOrder() {
        val stroke =
            SlockComposerImageStroke(
                points = listOf(Offset(4f, 4f), Offset(40f, 40f)),
            )
        val tile = SlockComposerImageMosaicTile(column = 2, row = 3)

        val pencilThenMosaic =
            slockComposerImageCommitMosaicGesture(
                history =
                    SlockComposerImageEditorHistory(
                        content = SlockComposerImageEditorContent().slockComposerImageAppendStroke(stroke)
                    ),
                tiles = setOf(tile),
            ).content.slockComposerImageResolvedDrawOperations()
        val mosaicThenPencil =
            slockComposerImageCommitMosaicGesture(
                history = SlockComposerImageEditorHistory(),
                tiles = setOf(tile),
            ).content.slockComposerImageAppendStroke(stroke).slockComposerImageResolvedDrawOperations()

        assertEquals(
            listOf(
                SlockComposerImageDrawOperation.Pencil(stroke),
                SlockComposerImageDrawOperation.Mosaic(setOf(tile)),
            ),
            pencilThenMosaic,
        )
        assertEquals(
            listOf(
                SlockComposerImageDrawOperation.Mosaic(setOf(tile)),
                SlockComposerImageDrawOperation.Pencil(stroke),
            ),
            mosaicThenPencil,
        )
    }

    @Test
    fun composerImageActiveMosaicMatchesThePostCommitDeltaOverExistingLayers() {
        val existingTile = SlockComposerImageMosaicTile(column = 2, row = 2)
        val newTile = SlockComposerImageMosaicTile(column = 3, row = 3)
        val stroke = SlockComposerImageStroke(points = listOf(Offset(0f, 0f), Offset(40f, 40f)))
        val before =
            slockComposerImageCommitMosaicGesture(
                history = SlockComposerImageEditorHistory(),
                tiles = setOf(existingTile),
            ).let { history ->
                history.copy(content = history.content.slockComposerImageAppendStroke(stroke))
            }
        val touched = setOf(existingTile, newTile)

        val activeDelta =
            slockComposerImageActiveMosaicTiles(
                touchedTiles = touched,
                committedTiles = before.content.mosaicTiles,
            )
        val after = slockComposerImageCommitMosaicGesture(history = before, tiles = touched)

        assertEquals(setOf(newTile), activeDelta)
        assertEquals(
            before.content.slockComposerImageResolvedDrawOperations() +
                SlockComposerImageDrawOperation.Mosaic(activeDelta),
            after.content.slockComposerImageResolvedDrawOperations(),
        )
    }

    @Test
    fun composerImageFullyOverlappingMosaicIsInvisibleAndDoesNotCreateHistory() {
        val tile = SlockComposerImageMosaicTile(column = 2, row = 2)
        val before =
            slockComposerImageCommitMosaicGesture(
                history = SlockComposerImageEditorHistory(),
                tiles = setOf(tile),
            )

        assertTrue(
            slockComposerImageActiveMosaicTiles(
                touchedTiles = setOf(tile),
                committedTiles = before.content.mosaicTiles,
            ).isEmpty()
        )
        assertEquals(before, slockComposerImageCommitMosaicGesture(history = before, tiles = setOf(tile)))
    }

    @Test
    fun composerImageEraseRetainsOnlyVisibleDrawOperationFragments() {
        val firstStroke = SlockComposerImageStroke(points = listOf(Offset(0f, 0f), Offset(5f, 5f)))
        val secondStroke = SlockComposerImageStroke(points = listOf(Offset(50f, 50f), Offset(60f, 60f)))
        val firstTile = SlockComposerImageMosaicTile(column = 1, row = 1)
        val secondTile = SlockComposerImageMosaicTile(column = 2, row = 2)
        val content =
            SlockComposerImageEditorContent(
                strokes = listOf(firstStroke, secondStroke),
                mosaicTiles = setOf(firstTile, secondTile),
                drawOperations =
                    listOf(
                        SlockComposerImageDrawOperation.Pencil(firstStroke),
                        SlockComposerImageDrawOperation.Mosaic(setOf(firstTile, secondTile)),
                        SlockComposerImageDrawOperation.Pencil(secondStroke),
                    ),
            )

        val retained =
            content.slockComposerImageRetainDrawOperations(
                strokes = listOf(secondStroke),
                mosaicTiles = setOf(firstTile),
            )

        assertEquals(
            listOf(
                SlockComposerImageDrawOperation.Mosaic(setOf(firstTile)),
                SlockComposerImageDrawOperation.Pencil(secondStroke),
            ),
            retained.drawOperations,
        )
    }

    @Test
    fun composerImageSingleCharacterUsesTheMeasuredHitTarget() {
        val annotation = SlockComposerImageTextAnnotation(id = 7, text = "A", position = Offset(100f, 80f))

        assertTrue(annotation.hitTest(point = Offset(143f, 105f), layoutSize = IntSize(144, 102)))
        assertFalse(annotation.hitTest(point = Offset(250f, 105f), layoutSize = IntSize(144, 102)))
    }

    @Test
    fun composerImageTextHitSelectsTheTopmostCommittedAnnotation() {
        val bottom = SlockComposerImageTextAnnotation(id = 1, text = "bottom", position = Offset(20f, 20f))
        val top = SlockComposerImageTextAnnotation(id = 2, text = "top", position = Offset(20f, 20f))

        assertEquals(
            top,
            slockComposerImageTopmostTextAnnotationAt(
                annotations = listOf(bottom, top),
                point = Offset(30f, 30f),
            ),
        )
        assertEquals(
            null,
            slockComposerImageTopmostTextAnnotationAt(
                annotations = listOf(bottom, top),
                point = Offset(300f, 300f),
            ),
        )
    }

    @Test
    fun composerImageLongTextHitUsesTheFullMeasuredMultilineBounds() {
        val annotation =
            SlockComposerImageTextAnnotation(
                id = 5,
                text = "a".repeat(80),
                position = Offset(20f, 30f),
            )
        val measuredSize = IntSize(width = 260, height = 82)

        assertEquals(
            annotation,
            slockComposerImageTopmostTextAnnotationAt(
                annotations = listOf(annotation),
                point = Offset(270f, 105f),
                layoutSizes = mapOf(annotation.id to measuredSize),
            ),
        )
        assertEquals(
            null,
            slockComposerImageTopmostTextAnnotationAt(
                annotations = listOf(annotation),
                point = Offset(281f, 113f),
                layoutSizes = mapOf(annotation.id to measuredSize),
            ),
        )
    }

    @Test
    fun composerImageTextPositionClampsAllFourEdgesUsingMeasuredBounds() {
        val annotationSize = IntSize(width = 260, height = 82)
        val canvasSize = IntSize(width = 390, height = 520)

        assertEquals(
            Offset.Zero,
            slockComposerImageClampTextPosition(
                position = Offset(-20f, -30f),
                annotationSize = annotationSize,
                canvasSize = canvasSize,
            ),
        )
        assertEquals(
            Offset(130f, 438f),
            slockComposerImageClampTextPosition(
                position = Offset(300f, 500f),
                annotationSize = annotationSize,
                canvasSize = canvasSize,
            ),
        )
    }

    @Test
    fun composerImageTextFallbackScalesDpAndLineHeightForDenseDisplays() {
        assertEquals(
            IntSize(width = 520, height = 164),
            slockComposerImageTextFallbackSize(
                text = "a".repeat(80),
                densityScale = 2f,
                fontScale = 1f,
            ),
        )
    }

    @Test
    fun composerImageFirstMeasuredBoundsReclampANewAnnotationWithoutNewHistory() {
        val annotation =
            SlockComposerImageTextAnnotation(
                id = 11,
                text = "a".repeat(80),
                position = Offset(300f, 500f),
            )
        val added =
            SlockComposerImageEditorHistory().apply(
                SlockComposerImageEditorContent(textAnnotations = listOf(annotation))
            )
        val actualPosition =
            slockComposerImageClampTextPosition(
                position = annotation.position,
                annotationSize = IntSize(width = 260, height = 82),
                canvasSize = IntSize(width = 390, height = 520),
            )
        val reclamped =
            added.replaceWithoutHistory(
                slockComposerImageMoveTextAnnotation(
                    content = added.content,
                    annotationId = annotation.id,
                    position = actualPosition,
                )
            )

        assertEquals(1, reclamped.undoStack.size)
        assertEquals(Offset(130f, 438f), reclamped.content.textAnnotations.single().position)
        assertTrue(reclamped.undo().content.textAnnotations.isEmpty())
    }

    @Test
    fun composerImageEraserUsesMeasuredMultilineTextBounds() {
        val annotation =
            SlockComposerImageTextAnnotation(
                id = 12,
                text = "a".repeat(80),
                position = Offset(20f, 30f),
            )

        assertTrue(
            slockComposerImageEraseTextAnnotations(
                annotations = listOf(annotation),
                point = Offset(270f, 105f),
                layoutSizes = mapOf(annotation.id to IntSize(width = 260, height = 82)),
            ).isEmpty()
        )
        assertEquals(
            listOf(annotation),
            slockComposerImageEraseTextAnnotations(
                annotations = listOf(annotation),
                point = Offset(281f, 113f),
                layoutSizes = mapOf(annotation.id to IntSize(width = 260, height = 82)),
            ),
        )
    }

    @Test
    fun composerImageTextMoveCommitsOneUndoableTransactionAndRedoRestoresIt() {
        val original = SlockComposerImageTextAnnotation(id = 7, text = "move", position = Offset(20f, 20f))
        val origin = SlockComposerImageEditorContent(textAnnotations = listOf(original))
        val initial = SlockComposerImageEditorHistory(content = origin)
        val movedContent =
            slockComposerImageMoveTextAnnotation(
                content = initial.content,
                annotationId = original.id,
                position = Offset(80f, 90f),
            )
        val duringDrag = initial.replaceWithoutHistory(movedContent)

        assertFalse(duringDrag.canUndo)
        val committed = duringDrag.commitTransaction(origin)
        assertEquals(1, committed.undoStack.size)
        assertEquals(Offset(80f, 90f), committed.content.textAnnotations.single().position)

        val undone = committed.undo()
        assertEquals(Offset(20f, 20f), undone.content.textAnnotations.single().position)
        val redone = undone.redo()
        assertEquals(Offset(80f, 90f), redone.content.textAnnotations.single().position)
    }

    @Test
    fun composerImageTextMoveOnlyChangesTheLockedAnnotation() {
        val first = SlockComposerImageTextAnnotation(id = 1, text = "first", position = Offset(10f, 10f))
        val second = SlockComposerImageTextAnnotation(id = 2, text = "second", position = Offset(30f, 30f))

        val moved =
            slockComposerImageMoveTextAnnotation(
                content = SlockComposerImageEditorContent(textAnnotations = listOf(first, second)),
                annotationId = second.id,
                position = Offset(90f, 100f),
            )

        assertEquals(first, moved.textAnnotations[0])
        assertEquals(Offset(90f, 100f), moved.textAnnotations[1].position)
    }

    @Test
    fun composerImagePendingTextDraftIsCommittedBeforeDoneCaptureContent() {
        val committed =
            slockComposerImageAddTextDraft(
                content = SlockComposerImageEditorContent(),
                draft = "  pending caption  ",
                position = Offset(44f, 55f),
                annotationId = 9,
            )

        assertEquals(
            SlockComposerImageTextAnnotation(
                id = 9,
                text = "pending caption",
                position = Offset(44f, 55f),
            ),
            committed.textAnnotations.single(),
        )
        assertEquals(
            committed,
            slockComposerImageAddTextDraft(
                content = committed,
                draft = "   ",
                position = Offset.Zero,
                annotationId = 10,
            ),
        )
    }

    @Test
    fun composerImageUndoUsesChronologicalHistoryAcrossTextAndPen() {
        val text = SlockComposerImageTextAnnotation(id = 1, text = "note", position = Offset(20f, 20f))
        val stroke = SlockComposerImageStroke(points = listOf(Offset(10f, 10f), Offset(80f, 10f)))
        val afterText =
            SlockComposerImageEditorHistory().apply(
                SlockComposerImageEditorContent(textAnnotations = listOf(text))
            )
        val afterStroke =
            afterText.apply(
                afterText.content.copy(strokes = listOf(stroke))
            )

        val undone = afterStroke.undo()

        assertEquals(emptyList(), undone.content.strokes)
        assertEquals(listOf(text), undone.content.textAnnotations)
        assertTrue(undone.canUndo)
    }

    @Test
    fun composerImageUndoRestoresObjectsRemovedByEraserTransaction() {
        val stroke = SlockComposerImageStroke(points = listOf(Offset(10f, 10f), Offset(80f, 10f)))
        val text = SlockComposerImageTextAnnotation(id = 1, text = "note", position = Offset(20f, 20f))
        val original = SlockComposerImageEditorContent(strokes = listOf(stroke), textAnnotations = listOf(text))
        val beforeErase = SlockComposerImageEditorHistory(content = original)
        val duringErase = beforeErase.replaceWithoutHistory(SlockComposerImageEditorContent())
        val afterErase = duringErase.commitTransaction(original)

        val undone = afterErase.undo()

        assertEquals(original, undone.content)
        assertFalse(undone.canUndo)
    }

    @Test
    fun composerImageUndoRestoresContentClearedInOneAction() {
        val original =
            SlockComposerImageEditorContent(
                strokes =
                    listOf(
                        SlockComposerImageStroke(
                            points = listOf(Offset(10f, 10f), Offset(80f, 10f))
                        )
                    ),
                textAnnotations =
                    listOf(
                        SlockComposerImageTextAnnotation(
                            id = 1,
                            text = "note",
                            position = Offset(20f, 20f)
                        )
                    )
            )
        val cleared = SlockComposerImageEditorHistory(content = original).apply(SlockComposerImageEditorContent())
        val restored = cleared.undo()

        assertEquals(original, restored.content)
        assertEquals(SlockComposerImageEditorContent(), restored.redo().content)
    }

    @Test
    fun composerImageRedoRestoresTheUndoneEdit() {
        val stroke = SlockComposerImageStroke(points = listOf(Offset(10f, 10f), Offset(80f, 10f)))
        val edited =
            SlockComposerImageEditorHistory().apply(
                SlockComposerImageEditorContent(strokes = listOf(stroke))
            )

        val undone = edited.undo()
        val redone = undone.redo()

        assertEquals(edited.content, redone.content)
        assertTrue(redone.canUndo)
        assertFalse(redone.canRedo)
    }

    @Test
    fun composerImageNewEditClearsTheRedoBranch() {
        val firstStroke = SlockComposerImageStroke(points = listOf(Offset(10f, 10f), Offset(80f, 10f)))
        val replacementStroke = SlockComposerImageStroke(points = listOf(Offset(20f, 20f), Offset(90f, 20f)))
        val undone =
            SlockComposerImageEditorHistory()
                .apply(SlockComposerImageEditorContent(strokes = listOf(firstStroke)))
                .undo()

        val branched = undone.apply(SlockComposerImageEditorContent(strokes = listOf(replacementStroke)))

        assertFalse(branched.canRedo)
        assertEquals(listOf(replacementStroke), branched.content.strokes)
    }

    @Test
    fun composerImageNoOpDoesNotCreateUndoHistory() {
        val history = SlockComposerImageEditorHistory()

        val unchanged = history.apply(history.content).commitTransaction(history.content)

        assertFalse(unchanged.canUndo)
        assertEquals(history, unchanged)
    }

    @Test
    fun imageEditorControlsReserveTheMainToolbarHeight() {
        assertEquals(
            80.dp,
            slockFilePreviewEditorControlsHeight(
                secondaryControlsVisible = false,
                bottomPadding = 30.dp,
            )
        )
    }

    @Test
    fun imageEditorControlsReserveTheExpandedPencilToolbarHeight() {
        assertEquals(
            132.dp,
            slockFilePreviewEditorControlsHeight(
                secondaryControlsVisible = true,
                bottomPadding = 30.dp,
            )
        )
    }

    @Test
    fun imageEditorPencilButtonTogglesItsOptions() {
        assertFalse(
            slockFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = SlockComposerImageEditorTool.Pencil,
                selectedTool = SlockComposerImageEditorTool.Pencil,
                currentlyExpanded = true,
            )
        )
        assertTrue(
            slockFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = SlockComposerImageEditorTool.Pencil,
                selectedTool = SlockComposerImageEditorTool.Pencil,
                currentlyExpanded = false,
            )
        )
    }

    @Test
    fun imageEditorSelectingPencilFromAnotherToolOpensItsOptions() {
        assertTrue(
            slockFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = SlockComposerImageEditorTool.Text,
                selectedTool = SlockComposerImageEditorTool.Pencil,
                currentlyExpanded = false,
            )
        )
    }

    @Test
    fun imageEditorTextAndMosaicHidePencilOptions() {
        assertFalse(
            slockFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = SlockComposerImageEditorTool.Pencil,
                selectedTool = SlockComposerImageEditorTool.Text,
                currentlyExpanded = true,
            )
        )
        assertFalse(
            slockFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = SlockComposerImageEditorTool.Pencil,
                selectedTool = SlockComposerImageEditorTool.Mosaic,
                currentlyExpanded = true,
            )
        )
    }

    @Test
    fun imageEditorBottomPaddingUsesSafeAreaWhenKeyboardIsHidden() {
        assertEquals(
            30.dp,
            slockFilePreviewEditorBottomPadding(
                keyboardInsetDp = 0f,
                bottomSafeArea = 24.dp,
                keyboardInsetExcludesBottomSafeArea = false,
            ),
        )
    }

    @Test
    fun imageEditorKeyboardInsetAvoidsDoubleCountingIncludedSafeArea() {
        assertEquals(
            306.dp,
            slockFilePreviewEditorBottomPadding(
                keyboardInsetDp = 300f,
                bottomSafeArea = 24.dp,
                keyboardInsetExcludesBottomSafeArea = false,
            ),
        )
    }

    @Test
    fun imageEditorKeyboardInsetAddsExcludedSafeArea() {
        assertEquals(
            330.dp,
            slockFilePreviewEditorBottomPadding(
                keyboardInsetDp = 300f,
                bottomSafeArea = 24.dp,
                keyboardInsetExcludesBottomSafeArea = true,
            ),
        )
    }

    @Test
    fun composerImageActionSlotsKeepEditOnlyLeft() {
        assertEquals(
            listOf(
                SlockFilePreviewComposerImageActionSlot.Edit,
                SlockFilePreviewComposerImageActionSlot.FlexibleSpacer,
            ),
            slockFilePreviewComposerImageActionSlots(canEdit = true, canComplete = false),
        )
    }

    @Test
    fun composerImageActionSlotsKeepDoneOnlyRight() {
        assertEquals(
            listOf(
                SlockFilePreviewComposerImageActionSlot.FlexibleSpacer,
                SlockFilePreviewComposerImageActionSlot.Done,
            ),
            slockFilePreviewComposerImageActionSlots(canEdit = false, canComplete = true),
        )
    }

    @Test
    fun composerImageActionSlotsKeepDualActionsAtOppositeEdges() {
        assertEquals(
            listOf(
                SlockFilePreviewComposerImageActionSlot.Edit,
                SlockFilePreviewComposerImageActionSlot.FlexibleSpacer,
                SlockFilePreviewComposerImageActionSlot.Done,
            ),
            slockFilePreviewComposerImageActionSlots(canEdit = true, canComplete = true),
        )
    }

    @Test
    fun downloadStatusLabelIncludesPercentAndSpeed() {
        val label =
            slockFilePreviewDownloadStatusLabel(
                BridgeAttachmentDownloadProgress(
                    percent = 42,
                    downloadedBytes = 4L * 1024L * 1024L,
                    totalBytes = 10L * 1024L * 1024L,
                    bytesPerSecond = 512L * 1024L
                ),
                englishDownloadTerms
            )

        assertEquals("Downloading 42% · 512 KB/s", label)
    }

    @Test
    fun downloadStatusLabelShowsPausedState() {
        val label =
            slockFilePreviewDownloadStatusLabel(
                BridgeAttachmentDownloadProgress(
                    status = "paused",
                    percent = 42,
                    downloadedBytes = 4L * 1024L * 1024L,
                    totalBytes = 10L * 1024L * 1024L,
                    bytesPerSecond = null
                ),
                englishDownloadTerms
            )

        assertEquals("Paused 42%", label)
    }

    @Test
    fun downloadStatusLabelFallsBackToDownloadedBytesWhenPercentUnknown() {
        val label =
            slockFilePreviewDownloadStatusLabel(
                BridgeAttachmentDownloadProgress(
                    percent = null,
                    downloadedBytes = 1536L,
                    totalBytes = null,
                    bytesPerSecond = null
                ),
                englishDownloadTerms
            )

        assertEquals("Downloading 1 KB", label)
    }

    @Test
    fun initialDownloadStatusDoesNotPretendBytesHaveTransferred() {
        val label = slockFilePreviewDownloadStatusLabel(SlockFilePreviewInitialDownloadProgress, englishDownloadTerms)

        assertEquals("Downloading...", label)
    }

    @Test
    fun mediaStoreExportUsesSavingLabel() {
        val label =
            slockFilePreviewDownloadStatusLabel(
                BridgeAttachmentDownloadProgress(
                    phase = "export_copy",
                    percent = 42,
                    downloadedBytes = 420L,
                    totalBytes = 1_000L
                ),
                englishDownloadTerms
            )

        assertEquals("Saving 42%", label)
    }

    @Test
    fun downloadStatusLabelUsesLocalizedTerms() {
        val terms =
            SlockFilePreviewDownloadStatusTerms(
                downloading = "下载中",
                paused = "已暂停",
                saving = "保存中"
            )

        assertEquals(
            "下载中 42% · 512 KB/s",
            slockFilePreviewDownloadStatusLabel(
                BridgeAttachmentDownloadProgress(
                    percent = 42,
                    downloadedBytes = 4L * 1024L * 1024L,
                    totalBytes = 10L * 1024L * 1024L,
                    bytesPerSecond = 512L * 1024L
                ),
                terms
            )
        )
        assertEquals(
            "已暂停...",
            slockFilePreviewActiveDownloadStatusLabel(
                progress = null,
                status = "paused",
                terms = terms
            )
        )
        assertEquals(
            "保存中 42%",
            slockFilePreviewDownloadStatusLabel(
                BridgeAttachmentDownloadProgress(
                    phase = "export_copy",
                    percent = 42,
                    downloadedBytes = 420L,
                    totalBytes = 1_000L
                ),
                terms
            )
        )
    }

    @Test
    fun mediaStoreExportAllowsStopButRejectsFakePauseResume() {
        val progress =
            BridgeAttachmentDownloadProgress(
                phase = "export_copy",
                percent = 42,
                downloadedBytes = 420L,
                totalBytes = 1_000L
            )

        assertFalse(slockAttachmentDownloadControlAllowed(progress, "pause"))
        assertFalse(slockAttachmentDownloadControlAllowed(progress, "resume"))
        assertTrue(slockAttachmentDownloadControlAllowed(progress, "stop"))
    }

    @Test
    fun activeDownloadStatusStaysIndeterminateBeforeNativeProgress() {
        val label =
            slockFilePreviewActiveDownloadStatusLabel(
                progress = null,
                status = "downloading",
                terms = englishDownloadTerms
            )

        assertEquals("Downloading...", label)
    }

    @Test
    fun pausedDownloadStatusStaysIndeterminateBeforeNativeProgress() {
        val label =
            slockFilePreviewActiveDownloadStatusLabel(
                progress = null,
                status = "paused",
                terms = englishDownloadTerms
            )

        assertEquals("Paused...", label)
    }

    @Test
    fun primaryActionShowsDownloadBeforeCacheExists() {
        val label =
            slockFilePreviewPrimaryActionLabel(
                SlockFilePreviewPayload(externalFileName = "report.pdf"),
                cached = false,
                terms = englishActionTerms
            )

        assertEquals("Download", label)
    }

    @Test
    fun primaryActionShowsOpenWhenCached() {
        val label =
            slockFilePreviewPrimaryActionLabel(
                SlockFilePreviewPayload(externalFileName = "report.pdf"),
                cached = true,
                terms = englishActionTerms
            )

        assertEquals("Open", label)
    }

    @Test
    fun primaryActionShowsInstallForCachedApk() {
        val label =
            slockFilePreviewPrimaryActionLabel(
                SlockFilePreviewPayload(
                    externalFileName = "raft.apk",
                    externalMimeType = "application/vnd.android.package-archive"
                ),
                cached = true,
                terms = englishActionTerms
            )

        assertEquals("Install", label)
    }

    @Test
    fun overflowActionKeepsOpenWithSeparateFromExplicitDownload() {
        val label =
            slockFilePreviewOverflowActionLabel(
                SlockFilePreviewPayload(externalFileName = "report.pdf"),
                cached = false,
                terms = englishActionTerms
            )

        assertEquals("Open with...", label)
    }

    @Test
    fun filePreviewActionsUseLocalizedTerms() {
        val terms =
            SlockFilePreviewActionTerms(
                download = "下载",
                install = "安装",
                open = "打开",
                openWith = "打开方式…"
            )

        assertEquals(
            "下载",
            slockFilePreviewPrimaryActionLabel(
                SlockFilePreviewPayload(externalFileName = "report.pdf"),
                cached = false,
                terms = terms
            )
        )
        assertEquals(
            "安装",
            slockFilePreviewPrimaryActionLabel(
                SlockFilePreviewPayload(
                    externalFileName = "raft.apk",
                    externalMimeType = "application/vnd.android.package-archive"
                ),
                cached = true,
                terms = terms
            )
        )
        assertEquals(
            "打开方式…",
            slockFilePreviewOverflowActionLabel(
                SlockFilePreviewPayload(externalFileName = "report.pdf"),
                cached = false,
                terms = terms
            )
        )
    }

    @Test
    fun primaryActionUsesExplicitSaveUntilPrivateCacheExists() {
        assertEquals(SlockFilePreviewPrimaryAction.Save, slockFilePreviewPrimaryAction(cached = false))
        assertEquals(SlockFilePreviewPrimaryAction.Open, slockFilePreviewPrimaryAction(cached = true))
    }

    @Test
    fun cachedOpenDoesNotTrackDownloadProgress() {
        assertEquals(false, slockFilePreviewShouldTrackOpenProgress(cached = true))
        assertEquals(true, slockFilePreviewShouldTrackOpenProgress(cached = false))
    }

    @Test
    fun unsupportedDownloadableFilesShowDownloadEntryMessage() {
        val label =
            slockFilePreviewUnsupportedFileMessage(
                SlockFilePreviewPayload(
                    kind = "unsupported",
                    externalFileName = "archive.zip",
                    messageDesc = MR.strings.file_preview_no_preview.desc()
                ),
                downloadable = true
            )

        assertEquals(MR.strings.file_preview_download_to_open.desc(), label)
    }

    @Test
    fun unsupportedNonDownloadableFilesKeepPreviewMessage() {
        val label =
            slockFilePreviewUnsupportedFileMessage(
                SlockFilePreviewPayload(
                    kind = "unsupported",
                    externalFileName = "archive.zip",
                    message = "Preview unavailable from workspace",
                    messageDesc = null
                ),
                downloadable = false
            )

        assertEquals("Preview unavailable from workspace".desc(), label)
    }

    @Test
    fun fileIconUsesPackageForApk() {
        val icon =
            slockFilePreviewFileIconName(
                SlockFilePreviewPayload(
                    externalFileName = "raft.apk",
                    externalMimeType = "application/vnd.android.package-archive"
                )
            )

        assertEquals(SlockLucideIconName.Package, icon)
    }

    @Test
    fun fileIconUsesArchiveForCompressedFiles() {
        val icon =
            slockFilePreviewFileIconName(
                SlockFilePreviewPayload(externalFileName = "logs.zip")
            )

        assertEquals(SlockLucideIconName.FileArchive, icon)
    }

    @Test
    fun fileIconUsesCodeForSourceFiles() {
        val icon =
            slockFilePreviewFileIconName(
                SlockFilePreviewPayload(externalFileName = "ThreadPage.kt")
            )

        assertEquals(SlockLucideIconName.FileCode, icon)
    }

    @Test
    fun fileIconUsesTypeForPlainTextFiles() {
        val icon =
            slockFilePreviewFileIconName(
                SlockFilePreviewPayload(externalFileName = "notes.txt")
            )

        assertEquals(SlockLucideIconName.FileType, icon)
    }

    @Test
    fun fileIconUsesPresentationForPowerPointFiles() {
        val icon =
            slockFilePreviewFileIconName(
                SlockFilePreviewPayload(externalFileName = "deck.pptx")
            )

        assertEquals(SlockLucideIconName.Presentation, icon)
    }

    @Test
    fun fileIconUsesVideoForMp4Files() {
        val icon =
            slockFilePreviewFileIconName(
                SlockFilePreviewPayload(externalFileName = "clip.mp4")
            )

        assertEquals(SlockLucideIconName.FileVideoCamera, icon)
    }

    @Test
    fun mp4VideoClassifierAcceptsOnlyMp4ForMobilePreview() {
        assertEquals(true, slockFilePreviewIsMp4VideoAttachment(fileName = "clip.mp4", mimeType = ""))
        assertEquals(true, slockFilePreviewIsMp4VideoAttachment(fileName = "clip.bin", mimeType = "video/mp4"))
        assertEquals(true, slockFilePreviewIsMp4VideoAttachment(fileName = "clip.MP4?token=1", mimeType = ""))
        assertEquals(false, slockFilePreviewIsMp4VideoAttachment(fileName = "clip.webm", mimeType = "video/webm"))
        assertEquals(false, slockFilePreviewIsMp4VideoAttachment(fileName = "clip.mov", mimeType = "video/quicktime"))
    }

    @Test
    fun mp3AudioClassifierAcceptsOnlyMp3ForMobilePreview() {
        // task #556: mp3 plays through the same player panel as mp4 video.
        assertEquals(true, slockFilePreviewIsMp3AudioAttachment(fileName = "song.mp3", mimeType = ""))
        assertEquals(true, slockFilePreviewIsMp3AudioAttachment(fileName = "song.bin", mimeType = "audio/mpeg"))
        assertEquals(true, slockFilePreviewIsMp3AudioAttachment(fileName = "song.bin", mimeType = "audio/mp3"))
        assertEquals(true, slockFilePreviewIsMp3AudioAttachment(fileName = "song.MP3?token=1", mimeType = ""))
        assertEquals(false, slockFilePreviewIsMp3AudioAttachment(fileName = "song.wav", mimeType = "audio/wav"))
        assertEquals(false, slockFilePreviewIsMp3AudioAttachment(fileName = "song.m4a", mimeType = "audio/mp4"))
        assertEquals(false, slockFilePreviewIsMp3AudioAttachment(fileName = "clip.mp4", mimeType = "video/mp4"))
    }

    @Test
    fun htmlClassifierUsesSandboxPreviewContract() {
        assertEquals(true, slockFilePreviewIsHtmlAttachment(fileName = "report.html", mimeType = ""))
        assertEquals(true, slockFilePreviewIsHtmlAttachment(fileName = "report.htm?token=1", mimeType = ""))
        assertEquals(true, slockFilePreviewIsHtmlAttachment(fileName = "report.bin", mimeType = "text/html; charset=utf-8"))
        assertEquals(false, slockFilePreviewIsHtmlAttachment(fileName = "notes.txt", mimeType = "text/plain"))
    }

    @Test
    fun videoTimeLabelIncludesStateAndDuration() {
        assertEquals(
            "Playing · 0:12 / 1:05",
            slockFilePreviewVideoTimeLabel(
                currentTimeMs = 12_000,
                totalTimeMs = 65_000,
                playState = PlayState.PLAYING,
                terms = englishVideoTerms
            )
        )
        assertEquals(
            "Playback failed · 0:00",
            slockFilePreviewVideoTimeLabel(
                currentTimeMs = 0,
                totalTimeMs = 0,
                playState = PlayState.ERROR,
                terms = englishVideoTerms
            )
        )
    }

    @Test
    fun videoTimeLabelUsesLocalizedStateTerms() {
        val terms =
            SlockFilePreviewVideoStateTerms(
                playing = "播放中",
                buffering = "缓冲中",
                paused = "已暂停",
                ended = "播放结束",
                playbackFailed = "播放失败",
                ready = "就绪"
            )

        assertEquals(
            "播放中 · 0:12 / 1:05",
            slockFilePreviewVideoTimeLabel(
                currentTimeMs = 12_000,
                totalTimeMs = 65_000,
                playState = PlayState.PLAYING,
                terms = terms
            )
        )
        assertEquals(
            "播放失败 · 0:00",
            slockFilePreviewVideoTimeLabel(
                currentTimeMs = 0,
                totalTimeMs = 0,
                playState = PlayState.ERROR,
                terms = terms
            )
        )
    }

    @Test
    fun videoProgressPercentTracksDurationBounds() {
        assertEquals(25, slockFilePreviewVideoProgressPercent(currentTimeMs = 15_000, totalTimeMs = 60_000))
        assertEquals(0, slockFilePreviewVideoProgressPercent(currentTimeMs = -1_000, totalTimeMs = 60_000))
        assertEquals(100, slockFilePreviewVideoProgressPercent(currentTimeMs = 75_000, totalTimeMs = 60_000))
        assertEquals(null, slockFilePreviewVideoProgressPercent(currentTimeMs = 15_000, totalTimeMs = 0))
    }

    @Test
    fun videoSeekTimeTracksTapPosition() {
        assertEquals(30_000, slockFilePreviewVideoSeekTimeFromTap(tapX = 50f, widthPx = 100f, totalTimeMs = 60_000))
        assertEquals(0, slockFilePreviewVideoSeekTimeFromTap(tapX = -10f, widthPx = 100f, totalTimeMs = 60_000))
        assertEquals(60_000, slockFilePreviewVideoSeekTimeFromTap(tapX = 120f, widthPx = 100f, totalTimeMs = 60_000))
        assertEquals(null, slockFilePreviewVideoSeekTimeFromTap(tapX = 50f, widthPx = 0f, totalTimeMs = 60_000))
        assertEquals(null, slockFilePreviewVideoSeekTimeFromTap(tapX = 50f, widthPx = 100f, totalTimeMs = 0))
    }

    @Test
    fun fileIconUsesSpreadsheetForWorkbookFiles() {
        val icon =
            slockFilePreviewFileIconName(
                SlockFilePreviewPayload(externalFileName = "budget.xlsx")
            )

        assertEquals(SlockLucideIconName.FileSpreadsheet, icon)
    }

    @Test
    fun imageAttachmentDetectionFallsBackToFileExtension() {
        assertTrue(
            slockFilePreviewIsImageAttachment(
                fileName = "picked-photo.PNG?download=1",
                mimeType = "application/octet-stream"
            )
        )
        assertTrue(
            KuiklyThreadAttachmentItem(
                id = "att-1",
                name = "screen.webp",
                mimeType = ""
            ).isImageAttachment()
        )
    }

    @Test
    fun threadImageLazyPreviewKeepsThumbnailAsInitialImageUrl() {
        val payload =
            KuiklyThreadAttachmentItem(
                id = "att-1",
                name = "photo.jpg",
                mimeType = "",
                thumbnailUrl = "https://cdn.slock.test/thumb.jpg"
            ).toThreadAttachmentLazyPreviewPayload(kind = "image")

        assertEquals("loading", payload.kind)
        assertEquals(MR.strings.file_preview_image.desc(), payload.titleDesc)
        assertEquals("https://cdn.slock.test/thumb.jpg", payload.imageUrl)
        assertEquals("att-1", payload.attachmentId)
        assertTrue(payload.shouldRenderImagePreview())
        assertEquals("https://cdn.slock.test/thumb.jpg", payload.currentImageThumbnailUrl())
    }

    @Test
    fun resolvedThreadImageKeepsGalleryThumbnailAsRenderFallback() {
        val payload =
            SlockFilePreviewPayload(
                kind = "image",
                requestedKind = "image",
                attachmentId = "att-1",
                imageUrl = "https://cdn.slock.test/full.jpg?signature=private",
                imageGallery =
                    listOf(
                        SlockFilePreviewGalleryImage(
                            attachmentId = "att-1",
                            thumbnailUrl = "https://cdn.slock.test/thumb.jpg"
                        )
                    )
            )

        assertTrue(payload.shouldRenderImagePreview())
        assertEquals("https://cdn.slock.test/thumb.jpg", payload.currentImageThumbnailUrl())
    }

    @Test
    fun nonImageLoadingPayloadDoesNotEnterImageRenderer() {
        val payload =
            SlockFilePreviewPayload(
                kind = "loading",
                requestedKind = "markdown",
                imageUrl = "file:///tmp/not-an-image-preview"
            )

        assertFalse(payload.shouldRenderImagePreview())
        assertEquals(null, payload.currentImageThumbnailUrl())
    }

    @Test
    fun imageGalleryProgressLabelUsesOneBasedCurrentIndex() {
        val payload =
            SlockFilePreviewPayload(
                kind = "image",
                imageGallery =
                    listOf(
                        SlockFilePreviewGalleryImage(attachmentId = "att-1"),
                        SlockFilePreviewGalleryImage(attachmentId = "att-2"),
                        SlockFilePreviewGalleryImage(attachmentId = "att-3")
                    ),
                imageGalleryIndex = 1
            )

        assertEquals("2/3", payload.imageGalleryProgressLabel())
    }

    @Test
    fun imageGallerySwipeChoosesPreviousAndNextIndex() {
        val payload =
            SlockFilePreviewPayload(
                kind = "image",
                imageGallery =
                    listOf(
                        SlockFilePreviewGalleryImage(attachmentId = "att-1"),
                        SlockFilePreviewGalleryImage(attachmentId = "att-2"),
                        SlockFilePreviewGalleryImage(attachmentId = "att-3")
                    ),
                imageGalleryIndex = 1
            )

        assertEquals(2, payload.nextImageGalleryIndex(dragDistancePx = -80f, thresholdPx = 56f))
        assertEquals(0, payload.nextImageGalleryIndex(dragDistancePx = 80f, thresholdPx = 56f))
        assertEquals(null, payload.nextImageGalleryIndex(dragDistancePx = 20f, thresholdPx = 56f))
    }

    @Test
    fun imageGalleryIndexChangeRebuildsPayloadForSelectedAttachment() {
        val payload =
            SlockFilePreviewPayload(
                kind = "image",
                attachmentId = "att-1",
                name = "one.png",
                externalMimeType = "image/png",
                imageGallery =
                    listOf(
                        SlockFilePreviewGalleryImage(
                            attachmentId = "att-1",
                            name = "one.png",
                            mimeType = "image/png",
                            sizeLabel = "1 KB",
                            thumbnailUrl = "thumb-one"
                        ),
                        SlockFilePreviewGalleryImage(
                            attachmentId = "att-2",
                            name = "two.png",
                            mimeType = "image/png",
                            sizeLabel = "2 KB",
                            thumbnailUrl = "thumb-two"
                        )
                    )
            )

        val next = payload.withImageGalleryIndex(1)

        assertEquals("att-2", next?.attachmentId)
        assertEquals("two.png", next?.name)
        assertEquals("2 KB", next?.meta)
        assertEquals("thumb-two", next?.imageUrl)
        assertEquals("2/2", next?.imageGalleryProgressLabel())
    }

    @Test
    fun composerImageGallerySwitchesBetweenLocalFilesWithoutNetworkLoading() {
        val gallery =
            listOf(
                SlockFilePreviewGalleryImage(
                    attachmentId = "local-1",
                    name = "one.png",
                    mimeType = "image/png",
                    thumbnailUrl = "file://one",
                    composerImageFileRef = "file://one",
                    composerImagePreviewUri = "file://one",
                    composerImageSizeBytes = 10L
                ),
                SlockFilePreviewGalleryImage(
                    attachmentId = "local-2",
                    name = "two.png",
                    mimeType = "image/png",
                    thumbnailUrl = "file://two",
                    composerImageFileRef = "file://two",
                    composerImagePreviewUri = "file://two",
                    composerImageSizeBytes = 20L
                )
            )
        val payload =
            SlockFilePreviewPayload(
                kind = "image",
                imageUrl = "file://one",
                composerImageActionId = "composer-action",
                composerImageFileRef = "file://one",
                composerImageCanComplete = true,
                composerImageCanEdit = true,
                imageGallery = gallery
            )

        val next = payload.withImageGalleryIndex(1)

        assertEquals("image", next?.kind)
        assertEquals("", next?.source)
        assertEquals("", next?.attachmentId)
        assertEquals("file://two", next?.imageUrl)
        assertEquals("file://two", next?.composerImageFileRef)
        assertEquals(1, next?.imageGalleryIndex)
        assertTrue(next?.canCompleteComposerImageSelection() == true)
    }

    @Test
    fun composerImageGalleryCompletionRequiresEveryLocalFile() {
        val payload =
            SlockFilePreviewPayload(
                composerImageFileRef = "file://one",
                imageGallery =
                    listOf(
                        SlockFilePreviewGalleryImage(
                            attachmentId = "local-1",
                            composerImageFileRef = "file://one"
                        ),
                        SlockFilePreviewGalleryImage(attachmentId = "local-2")
                    )
            )

        assertFalse(payload.canCompleteComposerImageSelection())
    }

    @Test
    fun composerImageGalleryEditReplacesOnlyCurrentImage() {
        val payload =
            SlockFilePreviewPayload(
                imageGalleryIndex = 1,
                imageGallery =
                    listOf(
                        SlockFilePreviewGalleryImage(
                            attachmentId = "local-1",
                            composerImageFileRef = "file://one",
                            composerImagePreviewUri = "file://one"
                        ),
                        SlockFilePreviewGalleryImage(
                            attachmentId = "local-2",
                            composerImageFileRef = "file://two",
                            composerImagePreviewUri = "file://two"
                        )
                    )
            )

        val edited = payload.withComposerImageEditResult("file://two-edited")

        assertEquals("file://one", edited.imageGallery[0].composerImageFileRef)
        assertEquals("file://two-edited", edited.imageGallery[1].composerImageFileRef)
        assertEquals("file://two-edited", edited.imageUrl)
        assertTrue(edited.canCompleteComposerImageSelection())
    }

    @Test
    fun filePreviewPresentationHidesMimeWhileKeepingUsefulMetadata() {
        val payload =
            SlockFilePreviewPayload(
                name = "report.pdf",
                path = "report.pdf",
                meta = "42 KB · application/pdf",
                subtitle = "application/pdf",
                externalMimeType = "application/pdf"
            )

        assertEquals("42 KB", payload.slockFilePreviewVisibleSubtitle(headerTitle = "report.pdf"))
        assertEquals("42 KB", payload.meta.withoutUserVisibleMimeType())
        assertEquals("folder/file", "folder/file".withoutUserVisibleMimeType())
    }

    @Test
    fun filePreviewDoesNotUseExternalMimeAsSubtitleFallback() {
        val payload =
            SlockFilePreviewPayload(
                name = "report.pdf",
                path = "report.pdf",
                subtitle = "application/pdf",
                externalMimeType = "application/pdf"
            )

        assertEquals(null, payload.slockFilePreviewVisibleSubtitle(headerTitle = "report.pdf"))
    }

    @Test
    fun imageGalleryIndexChangeReusesResolvedPayloadForSelectedAttachment() {
        val gallery =
            listOf(
                SlockFilePreviewGalleryImage(
                    attachmentId = "att-1",
                    name = "one.png",
                    mimeType = "image/png",
                    thumbnailUrl = "thumb-one"
                ),
                SlockFilePreviewGalleryImage(
                    attachmentId = "att-2",
                    name = "two.png",
                    mimeType = "image/png",
                    thumbnailUrl = "thumb-two"
                )
            )
        val payload =
            SlockFilePreviewPayload(
                kind = "image",
                attachmentId = "att-1",
                name = "one.png",
                externalMimeType = "image/png",
                imageGallery = gallery
            )
        val resolved =
            SlockFilePreviewPayload(
                kind = "image",
                attachmentId = "att-2",
                name = "two.png",
                externalMimeType = "image/png",
                imageUrl = "resolved-two",
                imageGallery = gallery,
                imageGalleryIndex = 1
            )

        val next = payload.withImageGalleryIndex(1, mapOf("att-2" to resolved))

        assertEquals("image", next?.kind)
        assertEquals("att-2", next?.attachmentId)
        assertEquals("resolved-two", next?.imageUrl)
        assertEquals(1, next?.imageGalleryIndex)
        assertEquals("2/2", next?.imageGalleryProgressLabel())
    }

    @Test
    fun imageGalleryResolutionStateDeduplicatesRapidRevisitAndKeepsLateResults() {
        val gallery =
            listOf(
                SlockFilePreviewGalleryImage(
                    attachmentId = "att-1",
                    name = "one.png",
                    mimeType = "image/png",
                    thumbnailUrl = "thumb-one"
                ),
                SlockFilePreviewGalleryImage(
                    attachmentId = "att-2",
                    name = "two.png",
                    mimeType = "image/png",
                    thumbnailUrl = "thumb-two"
                )
            )
        val first =
            SlockFilePreviewPayload(
                kind = "loading",
                requestedKind = "image",
                name = "one.png",
                imageUrl = "thumb-one",
                source = "thread_attachment",
                attachmentId = "att-1",
                imageGallery = gallery
            )
        val second = first.withImageGalleryIndex(1)!!
        var state = SlockFilePreviewImageGalleryResolutionState()

        val firstStart = state.beginResolution(first)
        state = firstStart.state
        assertEquals("att-1", firstStart.requestPayload?.attachmentId)

        val secondStart = state.beginResolution(second)
        state = secondStart.state
        assertEquals("att-2", secondStart.requestPayload?.attachmentId)
        assertEquals(setOf("att-1", "att-2"), state.inFlightAttachmentIds.toSet())

        val rapidFirstRevisit = state.beginResolution(first)
        state = rapidFirstRevisit.state
        assertNull(rapidFirstRevisit.requestPayload)
        assertNull(rapidFirstRevisit.cancelAttachmentId)
        assertEquals(setOf("att-1", "att-2"), state.inFlightAttachmentIds.toSet())
        assertEquals(listOf("att-2", "att-1"), state.inFlightAttachmentIds)

        val resolvedFirst = first.copy(kind = "image", imageUrl = "full-one", messageDesc = null)
        state = state.completeResolution(requestPayload = first, resolvedPayload = resolvedFirst)
        assertNull(second.withResolvedImageGalleryPayload(resolvedFirst))
        assertEquals("att-2", second.attachmentId, "A's late completion must not replace visible B")

        val returnedFirst = second.withImageGalleryIndex(0, state.resolvedPayloadByAttachmentId)
        assertEquals("image", returnedFirst?.kind)
        assertEquals("full-one", returnedFirst?.imageUrl)
        assertNull(state.beginResolution(returnedFirst!!).requestPayload)

        val resolvedSecond = second.copy(kind = "image", imageUrl = "full-two", messageDesc = null)
        state = state.completeResolution(requestPayload = second, resolvedPayload = resolvedSecond)
        assertEquals(emptyList(), state.inFlightAttachmentIds)
        assertEquals(setOf("att-1", "att-2"), state.resolvedPayloadByAttachmentId.keys)
    }

    @Test
    fun imageGalleryResolutionStateBoundsConcurrentRequestsToTwoAttachments() {
        val gallery =
            listOf(
                SlockFilePreviewGalleryImage(attachmentId = "att-1", thumbnailUrl = "thumb-one"),
                SlockFilePreviewGalleryImage(attachmentId = "att-2", thumbnailUrl = "thumb-two"),
                SlockFilePreviewGalleryImage(attachmentId = "att-3", thumbnailUrl = "thumb-three")
            )
        val first =
            SlockFilePreviewPayload(
                kind = "loading",
                requestedKind = "image",
                imageUrl = "thumb-one",
                source = "thread_attachment",
                attachmentId = "att-1",
                imageGallery = gallery
            )
        val second = first.withImageGalleryIndex(1)!!
        val third = first.withImageGalleryIndex(2)!!
        var state = SlockFilePreviewImageGalleryResolutionState().beginResolution(first).state
        state = state.beginResolution(second).state

        val thirdStart = state.beginResolution(third)

        assertEquals("att-3", thirdStart.requestPayload?.attachmentId)
        assertEquals("att-1", thirdStart.cancelAttachmentId)
        assertEquals(listOf("att-2", "att-3"), thirdStart.state.inFlightAttachmentIds)
    }

    @Test
    fun imageGalleryResolutionJobOwnershipRejectsStaleReplacement() {
        val firstJob = Job()
        val replacementJob = Job()
        val jobs = mutableMapOf("att-1" to firstJob)
        assertTrue(jobs.ownsImageGalleryResolution("att-1", firstJob))

        jobs["att-1"] = replacementJob
        firstJob.cancel()

        assertFalse(jobs.ownsImageGalleryResolution("att-1", firstJob))
        assertTrue(jobs.ownsImageGalleryResolution("att-1", replacementJob))
        assertFalse(jobs.ownsImageGalleryResolution("missing", null))
    }

    @Test
    fun imageGalleryResolutionStateRetriesAfterThumbnailOnlyFallback() {
        val payload =
            SlockFilePreviewPayload(
                kind = "loading",
                requestedKind = "image",
                imageUrl = "thumb-one",
                source = "thread_attachment",
                attachmentId = "att-1",
                imageGallery =
                    listOf(
                        SlockFilePreviewGalleryImage(
                            attachmentId = "att-1",
                            thumbnailUrl = "thumb-one"
                        )
                    )
            )
        val firstStart = SlockFilePreviewImageGalleryResolutionState().beginResolution(payload)
        val thumbnailOnlyFallback = payload.copy(kind = "image", messageDesc = null)
        val failed =
            firstStart.state.completeResolution(
                requestPayload = payload,
                resolvedPayload = thumbnailOnlyFallback
            )

        val retry = failed.beginResolution(payload)

        assertEquals(emptyMap(), failed.resolvedPayloadByAttachmentId)
        assertEquals("att-1", retry.requestPayload?.attachmentId)
        assertEquals(listOf("att-1"), retry.state.inFlightAttachmentIds)
    }

    @Test
    fun imageGalleryKeepAliveSourcesAreBoundToCurrentAndAdjacentImages() {
        val gallery =
            listOf(
                SlockFilePreviewGalleryImage(attachmentId = "att-1", thumbnailUrl = "thumb-one"),
                SlockFilePreviewGalleryImage(attachmentId = "att-2", thumbnailUrl = "thumb-two"),
                SlockFilePreviewGalleryImage(attachmentId = "att-3", thumbnailUrl = "thumb-three"),
                SlockFilePreviewGalleryImage(attachmentId = "att-4", thumbnailUrl = "thumb-four")
            )
        val current =
            SlockFilePreviewPayload(
                kind = "image",
                requestedKind = "image",
                imageUrl = "full-2",
                source = "thread_attachment",
                attachmentId = "att-2",
                imageGallery = gallery,
                imageGalleryIndex = 1
            )
        val resolvedPayloads =
            gallery.associate { image ->
                image.attachmentId to
                    current.copy(
                        attachmentId = image.attachmentId,
                        imageUrl = "full-${image.attachmentId.takeLast(1)}"
                    )
            }

        assertEquals(
            setOf(
                "thumb-one",
                "full-1",
                "thumb-two",
                "full-2",
                "thumb-three",
                "full-3"
            ),
            current.imageGalleryKeepAliveSources(resolvedPayloads)
        )
    }
}
