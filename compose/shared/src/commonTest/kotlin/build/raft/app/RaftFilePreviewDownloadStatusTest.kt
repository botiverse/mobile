package build.raft.app

import build.raft.app.resources.*
import build.raft.app.base.BridgeAttachmentDownloadProgress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftFilePreviewDownloadStatusTest {
    private val englishDownloadTerms =
        RaftFilePreviewDownloadStatusTerms(
            downloading = "Downloading",
            paused = "Paused",
            saving = "Saving"
        )
    private val englishActionTerms =
        RaftFilePreviewActionTerms(
            download = "Download",
            install = "Install",
            open = "Open",
            openWith = "Open with..."
        )
    private val englishVideoTerms =
        RaftFilePreviewVideoStateTerms(
            playing = "Playing",
            buffering = "Buffering",
            paused = "Paused",
            ended = "Ended",
            playbackFailed = "Playback failed",
            ready = "Ready"
        )

    private fun publicDownloadQuery(attachmentId: String, principalPrefix: String) =
        RaftFilePreviewPublicDownloadQuery(
            attachmentId = attachmentId,
            fileName = "report.pdf",
            owner =
                AttachmentOwnerLease(
                    shellInstanceId = "shell-$principalPrefix",
                    scopeId = "scope-$principalPrefix",
                    authEpoch = 1L,
                    principalKey = AttachmentPrincipalKey(principalPrefix.repeat(64)),
                    generation = 1L
                ),
            mimeType = "application/pdf"
        )

    @Test
    fun nativeProgressMailboxDefersUiMutationToItsSingleConsumer() = runBlocking {
        val owner =
            AttachmentOwnerLease(
                shellInstanceId = "shell-1",
                scopeId = "scope-1",
                authEpoch = 1L,
                principalKey = AttachmentPrincipalKey("a".repeat(64)),
                generation = 1L
            )
        val operation = AttachmentOperationToken(owner, "operation-1", "download-1")
        val progress =
            BridgeAttachmentDownloadProgress(
                downloadId = "download-1",
                attemptToken = "attempt-1",
                sequence = 2L,
                phase = "receiving",
                status = "progress",
                percent = 12,
                downloadedBytes = 69_765L,
                totalBytes = 581_375L
            )
        val mailbox = RaftFilePreviewProgressMailbox()
        var renderedBytes = 0L

        withContext(Dispatchers.Default) {
            assertTrue(mailbox.offer(operation, progress))
        }
        assertEquals(0L, renderedBytes)

        val delivered = mailbox.nextOrNull()
        assertEquals(operation, delivered?.operation)
        renderedBytes = delivered?.progress?.downloadedBytes ?: 0L
        assertEquals(69_765L, renderedBytes)

        mailbox.close()
        assertNull(mailbox.nextOrNull())
    }

    @Test
    fun cachedOpenHoldsAdmissionUntilNativeTerminal() {
        var status = "idle"
        var launches = 0
        fun tapCachedOpen() {
            if (!raftFilePreviewCanStartExternalOperation(status)) return
            status = raftFilePreviewExternalOpenAdmissionStatus(trackProgress = false)
            launches += 1
        }

        tapCachedOpen()
        tapCachedOpen()
        assertEquals("opening", status)
        assertEquals(1, launches)
        assertFalse(raftFilePreviewShowsDownloadProgress(status))

        status = "idle"
        tapCachedOpen()
        assertEquals(2, launches)
    }

    @Test
    fun uncachedOpenKeepsDownloadProgressVisibleWhileNativeDownloads() {
        val status = raftFilePreviewExternalOpenAdmissionStatus(trackProgress = true)

        assertEquals("downloading", status)
        assertTrue(raftFilePreviewShowsDownloadProgress(status))
        assertFalse(raftFilePreviewCanStartExternalOperation(status))
    }

    @Test
    fun successfulSaveToastNamesTheActualPlatformDestination() {
        assertEquals(
            "已保存到系统下载",
            raftFilePreviewSavedMessage(
                exportPrivateDownload = false,
                toPhotos = false,
                supportsPublicDownloadReadback = true,
                supportsPrivateAttachmentExport = false,
                downloadSaved = "已保存到系统下载",
                privateDownloadSaved = "下载完成",
                fileExportSaved = "已保存到文件",
                imageSavedToPhotos = "已保存到照片"
            )
        )
        assertEquals(
            "已保存到文件",
            raftFilePreviewSavedMessage(
                exportPrivateDownload = false,
                toPhotos = false,
                supportsPublicDownloadReadback = false,
                supportsPrivateAttachmentExport = false,
                downloadSaved = "已保存到系统下载",
                privateDownloadSaved = "下载完成",
                fileExportSaved = "已保存到文件",
                imageSavedToPhotos = "已保存到照片"
            )
        )
        assertEquals(
            "下载完成",
            raftFilePreviewSavedMessage(
                exportPrivateDownload = false,
                toPhotos = false,
                supportsPublicDownloadReadback = true,
                supportsPrivateAttachmentExport = true,
                downloadSaved = "已保存到系统下载",
                privateDownloadSaved = "下载完成",
                fileExportSaved = "已保存到文件",
                imageSavedToPhotos = "已保存到照片"
            )
        )
        assertEquals(
            "已保存到照片",
            raftFilePreviewSavedMessage(
                exportPrivateDownload = false,
                toPhotos = true,
                supportsPublicDownloadReadback = true,
                supportsPrivateAttachmentExport = false,
                downloadSaved = "已保存到系统下载",
                privateDownloadSaved = "下载完成",
                fileExportSaved = "已保存到文件",
                imageSavedToPhotos = "已保存到照片"
            )
        )
        assertEquals(
            "已保存到文件",
            raftFilePreviewSavedMessage(
                exportPrivateDownload = true,
                toPhotos = false,
                supportsPublicDownloadReadback = true,
                supportsPrivateAttachmentExport = true,
                downloadSaved = "已保存到系统下载",
                privateDownloadSaved = "下载完成",
                fileExportSaved = "已保存到文件",
                imageSavedToPhotos = "已保存到照片"
            )
        )
    }

    @Test
    fun editorSelectedToolUsesTheActiveIconTone() {
        assertEquals(
            RaftColor.AccentRedArgb,
            raftFilePreviewEditorToolIconColor(selected = true)
        )
    }

    @Test
    fun editorIdleToolUsesTheDefaultIconTone() {
        assertEquals(
            RaftColor.TextBlackArgb,
            raftFilePreviewEditorToolIconColor(selected = false)
        )
    }

    @Test
    fun composerImageEraseRemovesFinalMatchingStroke() {
        val remaining =
            raftComposerImageEraseStrokes(
                strokes =
                    listOf(
                        RaftComposerImageStroke(
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
            raftComposerImageMosaicTilesAt(
                point = Offset(35f, 25f),
                tileSizePx = 10f,
                brushRadius = 1,
            )

        assertEquals(9, tiles.size)
        assertTrue(RaftComposerImageMosaicTile(column = 3, row = 2) in tiles)
        assertTrue(RaftComposerImageMosaicTile(column = 2, row = 1) in tiles)
        assertTrue(RaftComposerImageMosaicTile(column = 4, row = 3) in tiles)
    }

    @Test
    fun composerImageDefaultMosaicBrushUsesTheCompactThreeByThreeFootprint() {
        val tiles = raftComposerImageMosaicTilesAt(point = Offset(90f, 90f))

        assertEquals(9, tiles.size)
        assertEquals(4, tiles.minOf { it.column })
        assertEquals(6, tiles.maxOf { it.column })
        assertEquals(4, tiles.minOf { it.row })
        assertEquals(6, tiles.maxOf { it.row })
    }

    @Test
    fun composerImageMosaicAccumulatorMutatesInPlaceWithoutDuplicatingVisitedTiles() {
        val tiles = mutableSetOf<RaftComposerImageMosaicTile>()

        assertTrue(
            raftComposerImageAccumulateMosaicTiles(
                target = tiles,
                point = Offset(35f, 25f),
                tileSizePx = 10f,
                brushRadius = 1,
            )
        )
        assertEquals(9, tiles.size)
        assertFalse(
            raftComposerImageAccumulateMosaicTiles(
                target = tiles,
                point = Offset(35f, 25f),
                tileSizePx = 10f,
                brushRadius = 1,
            )
        )
        assertEquals(9, tiles.size)
    }

    @Test
    fun composerImageMosaicAccumulateAlongLineFillsGapBetweenFarTouchPoints() {
        val target = mutableSetOf<RaftComposerImageMosaicTile>()
        val changed =
            raftComposerImageAccumulateMosaicTilesAlongLine(
                target = target,
                start = Offset(10f, 10f),
                end = Offset(90f, 10f),
                tileSizePx = 10f,
                brushRadius = 0,
            )

        assertTrue(changed)
        assertTrue(target.size >= 9)
        for (col in 1..9) {
            assertTrue(RaftComposerImageMosaicTile(column = col, row = 1) in target)
        }
    }

    @Test
    fun composerImageCropRectComputesFractionsAndIdentifiesUncropped() {
        assertTrue(RaftComposerImageCropRect.Uncropped.isUncropped)
        assertEquals(1f, RaftComposerImageCropRect.Uncropped.widthFraction)
        assertEquals(1f, RaftComposerImageCropRect.Uncropped.heightFraction)

        val crop = RaftComposerImageCropRect(left = 0.2f, top = 0.1f, right = 0.8f, bottom = 0.7f)
        assertFalse(crop.isUncropped)
        assertEquals(0.6f, crop.widthFraction, 0.001f)
        assertEquals(0.6f, crop.heightFraction, 0.001f)
    }

    @Test
    fun composerImageCaptureSizeAppliesCropRectWithoutLetterbox() {
        val crop = RaftComposerImageCropRect(left = 0.1f, top = 0.1f, right = 0.6f, bottom = 0.35f)
        assertEquals(0.5f, crop.widthFraction, 0.001f)
        assertEquals(0.25f, crop.heightFraction, 0.001f)

        val captureSize =
            raftComposerImageEditorCaptureSize(
                imageWidth = 4000,
                imageHeight = 4000,
                maxWidth = 400f,
                maxHeight = 600f,
                cropRect = crop,
            )

        assertNotNull(captureSize)
        assertEquals(400f, captureSize.width, 0.01f)
        assertEquals(200f, captureSize.height, 0.01f)
    }

    @Test
    fun composerImageCropDetectHandleIdentifiesCornersEdgesAndCenter() {
        val crop = RaftComposerImageCropRect(left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f)
        val canvasSize = Size(100f, 100f)

        assertEquals(RaftCropDragHandle.TopLeft, raftCropDetectHandle(Offset(20f, 20f), crop, canvasSize))
        assertEquals(RaftCropDragHandle.TopRight, raftCropDetectHandle(Offset(80f, 20f), crop, canvasSize))
        assertEquals(RaftCropDragHandle.BottomLeft, raftCropDetectHandle(Offset(20f, 80f), crop, canvasSize))
        assertEquals(RaftCropDragHandle.BottomRight, raftCropDetectHandle(Offset(80f, 80f), crop, canvasSize))

        assertEquals(RaftCropDragHandle.Top, raftCropDetectHandle(Offset(50f, 20f), crop, canvasSize))
        assertEquals(RaftCropDragHandle.Bottom, raftCropDetectHandle(Offset(50f, 80f), crop, canvasSize))
        assertEquals(RaftCropDragHandle.Left, raftCropDetectHandle(Offset(20f, 50f), crop, canvasSize))
        assertEquals(RaftCropDragHandle.Right, raftCropDetectHandle(Offset(80f, 50f), crop, canvasSize))

        assertEquals(RaftCropDragHandle.Center, raftCropDetectHandle(Offset(50f, 50f), crop, canvasSize))
        assertEquals(RaftCropDragHandle.None, raftCropDetectHandle(Offset(5f, 5f), crop, canvasSize))
    }

    @Test
    fun composerImageCropApplyDragClampsToBoundsAndMinDimension() {
        val crop = RaftComposerImageCropRect(left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f)
        val canvasSize = Size(100f, 100f)

        val draggedCorner =
            raftCropApplyDrag(
                handle = RaftCropDragHandle.TopLeft,
                cropRect = crop,
                dragAmount = Offset(10f, 10f),
                canvasSize = canvasSize,
            )
        assertEquals(0.3f, draggedCorner.left, 0.001f)
        assertEquals(0.3f, draggedCorner.top, 0.001f)
        assertEquals(0.8f, draggedCorner.right, 0.001f)
        assertEquals(0.8f, draggedCorner.bottom, 0.001f)

        val draggedCenter =
            raftCropApplyDrag(
                handle = RaftCropDragHandle.Center,
                cropRect = crop,
                dragAmount = Offset(10f, 0f),
                canvasSize = canvasSize,
            )
        assertEquals(0.3f, draggedCenter.left, 0.001f)
        assertEquals(0.9f, draggedCenter.right, 0.001f)
        assertEquals(0.2f, draggedCenter.top, 0.001f)
        assertEquals(0.8f, draggedCenter.bottom, 0.001f)

        val clampedCenter =
            raftCropApplyDrag(
                handle = RaftCropDragHandle.Center,
                cropRect = crop,
                dragAmount = Offset(50f, 0f),
                canvasSize = canvasSize,
            )
        assertEquals(0.4f, clampedCenter.left, 0.001f)
        assertEquals(1.0f, clampedCenter.right, 0.001f)
    }

    @Test
    fun composerImageCropRemapTranslatesStrokesAndAnnotationsToCroppedRegion() {
        val oldCrop = RaftComposerImageCropRect.Uncropped
        val newCrop = RaftComposerImageCropRect(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f)
        val oldCanvas = Size(400f, 400f)
        val newCanvas = Size(400f, 400f)

        val centerRemapped = raftRemapPoint(Offset(200f, 200f), oldCrop, newCrop, oldCanvas, newCanvas)
        assertEquals(200f, centerRemapped.x, 0.01f)
        assertEquals(200f, centerRemapped.y, 0.01f)

        val topLeftRemapped = raftRemapPoint(Offset(100f, 100f), oldCrop, newCrop, oldCanvas, newCanvas)
        assertEquals(0f, topLeftRemapped.x, 0.01f)
        assertEquals(0f, topLeftRemapped.y, 0.01f)

        val bottomRightRemapped = raftRemapPoint(Offset(300f, 300f), oldCrop, newCrop, oldCanvas, newCanvas)
        assertEquals(400f, bottomRightRemapped.x, 0.01f)
        assertEquals(400f, bottomRightRemapped.y, 0.01f)
    }

    @Test
    fun composerImageCaptureFitsLandscapePixelsWithoutExportingViewportLetterbox() {
        assertEquals(
            Size(width = 390f, height = 195f),
            raftComposerImageEditorCaptureSize(
                imageWidth = 4000,
                imageHeight = 2000,
                maxWidth = 390f,
                maxHeight = 600f,
            ),
        )
    }

    @Test
    fun composerImageCaptureFitsPortraitPixelsWithoutChangingTheirAspect() {
        assertEquals(
            Size(width = 300f, height = 600f),
            raftComposerImageEditorCaptureSize(
                imageWidth = 2000,
                imageHeight = 4000,
                maxWidth = 390f,
                maxHeight = 600f,
            ),
        )
        assertNull(
            raftComposerImageEditorCaptureSize(
                imageWidth = 0,
                imageHeight = 4000,
                maxWidth = 390f,
                maxHeight = 600f,
            )
        )
    }

    @Test
    fun composerImageMosaicSamplesTheAspectFitSourcePixel() {
        val sample =
            raftComposerImageMosaicSample(
                tile = RaftComposerImageMosaicTile(column = 2, row = 2),
                imageWidth = 100,
                imageHeight = 50,
                canvasWidth = 200f,
                canvasHeight = 200f,
                tileSizePx = 20f,
            )

        assertEquals(
            RaftComposerImageMosaicSample(
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
            raftComposerImageMosaicSample(
                tile = RaftComposerImageMosaicTile(column = 2, row = 0),
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
                RaftComposerImageMosaicTile(column = 2, row = 2),
                RaftComposerImageMosaicTile(column = 8, row = 8),
            )

        val remaining =
            raftComposerImageEraseMosaicTiles(
                tiles = tiles,
                point = Offset(25f, 25f),
                tileSizePx = 10f,
            )

        assertEquals(setOf(RaftComposerImageMosaicTile(column = 8, row = 8)), remaining)
    }

    @Test
    fun composerImageUndoRestoresThePreviousMosaicMask() {
        val first = RaftComposerImageMosaicTile(column = 1, row = 1)
        val second = RaftComposerImageMosaicTile(column = 2, row = 2)
        val afterFirst =
            RaftComposerImageEditorHistory().apply(
                RaftComposerImageEditorContent(mosaicTiles = setOf(first))
            )
        val afterSecond =
            afterFirst.apply(
                afterFirst.content.copy(mosaicTiles = setOf(first, second))
            )

        assertEquals(setOf(first), afterSecond.undo().content.mosaicTiles)
    }

    @Test
    fun composerImageUndoRemovesTheFirstAndFinalCommittedMosaicGesture() {
        val first = RaftComposerImageMosaicTile(column = 1, row = 1)
        val final = RaftComposerImageMosaicTile(column = 8, row = 8)
        val afterFirst =
            raftComposerImageCommitMosaicGesture(
                history = RaftComposerImageEditorHistory(),
                tiles = setOf(first),
            )
        val afterFinal =
            raftComposerImageCommitMosaicGesture(
                history = afterFirst,
                tiles = setOf(final),
            )

        val withoutFinal = afterFinal.undo()
        assertEquals(setOf(first), withoutFinal.content.mosaicTiles)
        assertEquals(
            listOf(RaftComposerImageDrawOperation.Mosaic(setOf(first))),
            withoutFinal.content.raftComposerImageResolvedDrawOperations(),
        )
        val withoutFirst = withoutFinal.undo()
        assertTrue(withoutFirst.content.mosaicTiles.isEmpty())
        assertTrue(withoutFirst.content.raftComposerImageResolvedDrawOperations().isEmpty())
        assertFalse(withoutFirst.canUndo)

        val restoredFirst = withoutFirst.redo()
        assertEquals(setOf(first), restoredFirst.content.mosaicTiles)
        assertEquals(
            listOf(RaftComposerImageDrawOperation.Mosaic(setOf(first))),
            restoredFirst.content.raftComposerImageResolvedDrawOperations(),
        )
        val restoredFinal = restoredFirst.redo()
        assertEquals(setOf(first, final), restoredFinal.content.mosaicTiles)
        assertEquals(
            listOf(
                RaftComposerImageDrawOperation.Mosaic(setOf(first)),
                RaftComposerImageDrawOperation.Mosaic(setOf(final)),
            ),
            restoredFinal.content.raftComposerImageResolvedDrawOperations(),
        )
    }

    @Test
    fun composerImageDrawOperationsPreserveGestureCommitOrder() {
        val stroke =
            RaftComposerImageStroke(
                points = listOf(Offset(4f, 4f), Offset(40f, 40f)),
            )
        val tile = RaftComposerImageMosaicTile(column = 2, row = 3)

        val pencilThenMosaic =
            raftComposerImageCommitMosaicGesture(
                history =
                    RaftComposerImageEditorHistory(
                        content = RaftComposerImageEditorContent().raftComposerImageAppendStroke(stroke)
                    ),
                tiles = setOf(tile),
            ).content.raftComposerImageResolvedDrawOperations()
        val mosaicThenPencil =
            raftComposerImageCommitMosaicGesture(
                history = RaftComposerImageEditorHistory(),
                tiles = setOf(tile),
            ).content.raftComposerImageAppendStroke(stroke).raftComposerImageResolvedDrawOperations()

        assertEquals(
            listOf(
                RaftComposerImageDrawOperation.Pencil(stroke),
                RaftComposerImageDrawOperation.Mosaic(setOf(tile)),
            ),
            pencilThenMosaic,
        )
        assertEquals(
            listOf(
                RaftComposerImageDrawOperation.Mosaic(setOf(tile)),
                RaftComposerImageDrawOperation.Pencil(stroke),
            ),
            mosaicThenPencil,
        )
    }

    @Test
    fun composerImageActiveMosaicMatchesThePostCommitDeltaOverExistingLayers() {
        val existingTile = RaftComposerImageMosaicTile(column = 2, row = 2)
        val newTile = RaftComposerImageMosaicTile(column = 3, row = 3)
        val stroke = RaftComposerImageStroke(points = listOf(Offset(0f, 0f), Offset(40f, 40f)))
        val before =
            raftComposerImageCommitMosaicGesture(
                history = RaftComposerImageEditorHistory(),
                tiles = setOf(existingTile),
            ).let { history ->
                history.copy(content = history.content.raftComposerImageAppendStroke(stroke))
            }
        val touched = setOf(existingTile, newTile)

        val activeDelta =
            raftComposerImageActiveMosaicTiles(
                touchedTiles = touched,
                committedTiles = before.content.mosaicTiles,
            )
        val after = raftComposerImageCommitMosaicGesture(history = before, tiles = touched)

        assertEquals(setOf(newTile), activeDelta)
        assertEquals(
            before.content.raftComposerImageResolvedDrawOperations() +
                RaftComposerImageDrawOperation.Mosaic(activeDelta),
            after.content.raftComposerImageResolvedDrawOperations(),
        )
    }

    @Test
    fun composerImageFullyOverlappingMosaicIsInvisibleAndDoesNotCreateHistory() {
        val tile = RaftComposerImageMosaicTile(column = 2, row = 2)
        val before =
            raftComposerImageCommitMosaicGesture(
                history = RaftComposerImageEditorHistory(),
                tiles = setOf(tile),
            )

        assertTrue(
            raftComposerImageActiveMosaicTiles(
                touchedTiles = setOf(tile),
                committedTiles = before.content.mosaicTiles,
            ).isEmpty()
        )
        assertEquals(before, raftComposerImageCommitMosaicGesture(history = before, tiles = setOf(tile)))
    }

    @Test
    fun composerImageEraseRetainsOnlyVisibleDrawOperationFragments() {
        val firstStroke = RaftComposerImageStroke(points = listOf(Offset(0f, 0f), Offset(5f, 5f)))
        val secondStroke = RaftComposerImageStroke(points = listOf(Offset(50f, 50f), Offset(60f, 60f)))
        val firstTile = RaftComposerImageMosaicTile(column = 1, row = 1)
        val secondTile = RaftComposerImageMosaicTile(column = 2, row = 2)
        val content =
            RaftComposerImageEditorContent(
                strokes = listOf(firstStroke, secondStroke),
                mosaicTiles = setOf(firstTile, secondTile),
                drawOperations =
                    listOf(
                        RaftComposerImageDrawOperation.Pencil(firstStroke),
                        RaftComposerImageDrawOperation.Mosaic(setOf(firstTile, secondTile)),
                        RaftComposerImageDrawOperation.Pencil(secondStroke),
                    ),
            )

        val retained =
            content.raftComposerImageRetainDrawOperations(
                strokes = listOf(secondStroke),
                mosaicTiles = setOf(firstTile),
            )

        assertEquals(
            listOf(
                RaftComposerImageDrawOperation.Mosaic(setOf(firstTile)),
                RaftComposerImageDrawOperation.Pencil(secondStroke),
            ),
            retained.drawOperations,
        )
    }

    @Test
    fun composerImageSingleCharacterUsesTheMeasuredHitTarget() {
        val annotation = RaftComposerImageTextAnnotation(id = 7, text = "A", position = Offset(100f, 80f))

        assertTrue(annotation.hitTest(point = Offset(143f, 105f), layoutSize = IntSize(144, 102)))
        assertFalse(annotation.hitTest(point = Offset(250f, 105f), layoutSize = IntSize(144, 102)))
    }

    @Test
    fun composerImageTextHitSelectsTheTopmostCommittedAnnotation() {
        val bottom = RaftComposerImageTextAnnotation(id = 1, text = "bottom", position = Offset(20f, 20f))
        val top = RaftComposerImageTextAnnotation(id = 2, text = "top", position = Offset(20f, 20f))

        assertEquals(
            top,
            raftComposerImageTopmostTextAnnotationAt(
                annotations = listOf(bottom, top),
                point = Offset(30f, 30f),
            ),
        )
        assertEquals(
            null,
            raftComposerImageTopmostTextAnnotationAt(
                annotations = listOf(bottom, top),
                point = Offset(300f, 300f),
            ),
        )
    }

    @Test
    fun composerImageLongTextHitUsesTheFullMeasuredMultilineBounds() {
        val annotation =
            RaftComposerImageTextAnnotation(
                id = 5,
                text = "a".repeat(80),
                position = Offset(20f, 30f),
            )
        val measuredSize = IntSize(width = 260, height = 82)

        assertEquals(
            annotation,
            raftComposerImageTopmostTextAnnotationAt(
                annotations = listOf(annotation),
                point = Offset(270f, 105f),
                layoutSizes = mapOf(annotation.id to measuredSize),
            ),
        )
        assertEquals(
            null,
            raftComposerImageTopmostTextAnnotationAt(
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
            raftComposerImageClampTextPosition(
                position = Offset(-20f, -30f),
                annotationSize = annotationSize,
                canvasSize = canvasSize,
            ),
        )
        assertEquals(
            Offset(130f, 438f),
            raftComposerImageClampTextPosition(
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
            raftComposerImageTextFallbackSize(
                text = "a".repeat(80),
                densityScale = 2f,
                fontScale = 1f,
            ),
        )
    }

    @Test
    fun composerImageFirstMeasuredBoundsReclampANewAnnotationWithoutNewHistory() {
        val annotation =
            RaftComposerImageTextAnnotation(
                id = 11,
                text = "a".repeat(80),
                position = Offset(300f, 500f),
            )
        val added =
            RaftComposerImageEditorHistory().apply(
                RaftComposerImageEditorContent(textAnnotations = listOf(annotation))
            )
        val actualPosition =
            raftComposerImageClampTextPosition(
                position = annotation.position,
                annotationSize = IntSize(width = 260, height = 82),
                canvasSize = IntSize(width = 390, height = 520),
            )
        val reclamped =
            added.replaceWithoutHistory(
                raftComposerImageMoveTextAnnotation(
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
            RaftComposerImageTextAnnotation(
                id = 12,
                text = "a".repeat(80),
                position = Offset(20f, 30f),
            )

        assertTrue(
            raftComposerImageEraseTextAnnotations(
                annotations = listOf(annotation),
                point = Offset(270f, 105f),
                layoutSizes = mapOf(annotation.id to IntSize(width = 260, height = 82)),
            ).isEmpty()
        )
        assertEquals(
            listOf(annotation),
            raftComposerImageEraseTextAnnotations(
                annotations = listOf(annotation),
                point = Offset(281f, 113f),
                layoutSizes = mapOf(annotation.id to IntSize(width = 260, height = 82)),
            ),
        )
    }

    @Test
    fun composerImageTextMoveCommitsOneUndoableTransactionAndRedoRestoresIt() {
        val original = RaftComposerImageTextAnnotation(id = 7, text = "move", position = Offset(20f, 20f))
        val origin = RaftComposerImageEditorContent(textAnnotations = listOf(original))
        val initial = RaftComposerImageEditorHistory(content = origin)
        val movedContent =
            raftComposerImageMoveTextAnnotation(
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
        val first = RaftComposerImageTextAnnotation(id = 1, text = "first", position = Offset(10f, 10f))
        val second = RaftComposerImageTextAnnotation(id = 2, text = "second", position = Offset(30f, 30f))

        val moved =
            raftComposerImageMoveTextAnnotation(
                content = RaftComposerImageEditorContent(textAnnotations = listOf(first, second)),
                annotationId = second.id,
                position = Offset(90f, 100f),
            )

        assertEquals(first, moved.textAnnotations[0])
        assertEquals(Offset(90f, 100f), moved.textAnnotations[1].position)
    }

    @Test
    fun composerImageAnnotationDraftPreservesUnlimitedUnicodeAndImageIsolation() {
        val longUnicodeDraft = "prefix-" + "界".repeat(81) + "-🙂‍🚀-끝"
        assertTrue(longUnicodeDraft.length > 80)

        val firstImageDraft =
            RaftFilePreviewAnnotationDraft(imageUrl = "image-a")
                .updated(imageUrl = "image-a", nextText = longUnicodeDraft)

        assertEquals(longUnicodeDraft, firstImageDraft.text)

        val secondImageDraft =
            firstImageDraft.updated(
                imageUrl = "image-b",
                nextText = "second image 🙂",
            )

        assertEquals("image-b", secondImageDraft.imageUrl)
        assertEquals("second image 🙂", secondImageDraft.text)
        assertEquals(longUnicodeDraft, firstImageDraft.text)
        assertEquals(
            RaftFilePreviewAnnotationDraft(imageUrl = "image-b"),
            secondImageDraft.cleared(),
        )
    }

    @Test
    fun composerImageAnnotationSubmissionTrimsOuterWhitespaceWithoutTruncating() {
        val submittedText = "批注" + "界".repeat(81) + "🙂‍🚀"
        val draft =
            RaftFilePreviewAnnotationDraft(imageUrl = "image-a")
                .updated(imageUrl = "image-a", nextText = "  $submittedText  ")

        val committed =
            raftComposerImageAddTextDraft(
                content = RaftComposerImageEditorContent(),
                draft = draft.text,
                position = Offset(44f, 55f),
                annotationId = 9,
            )

        assertEquals(submittedText, committed.textAnnotations.single().text)
        assertTrue(committed.textAnnotations.single().text.length > 80)
        assertEquals("  $submittedText  ", draft.text)
    }

    @Test
    fun composerImagePendingTextDraftIsCommittedBeforeDoneCaptureContent() {
        val committed =
            raftComposerImageAddTextDraft(
                content = RaftComposerImageEditorContent(),
                draft = "  pending caption  ",
                position = Offset(44f, 55f),
                annotationId = 9,
            )

        assertEquals(
            RaftComposerImageTextAnnotation(
                id = 9,
                text = "pending caption",
                position = Offset(44f, 55f),
            ),
            committed.textAnnotations.single(),
        )
        assertEquals(
            committed,
            raftComposerImageAddTextDraft(
                content = committed,
                draft = "   ",
                position = Offset.Zero,
                annotationId = 10,
            ),
        )
    }

    @Test
    fun composerImageUndoUsesChronologicalHistoryAcrossTextAndPen() {
        val text = RaftComposerImageTextAnnotation(id = 1, text = "note", position = Offset(20f, 20f))
        val stroke = RaftComposerImageStroke(points = listOf(Offset(10f, 10f), Offset(80f, 10f)))
        val afterText =
            RaftComposerImageEditorHistory().apply(
                RaftComposerImageEditorContent(textAnnotations = listOf(text))
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
        val stroke = RaftComposerImageStroke(points = listOf(Offset(10f, 10f), Offset(80f, 10f)))
        val text = RaftComposerImageTextAnnotation(id = 1, text = "note", position = Offset(20f, 20f))
        val original = RaftComposerImageEditorContent(strokes = listOf(stroke), textAnnotations = listOf(text))
        val beforeErase = RaftComposerImageEditorHistory(content = original)
        val duringErase = beforeErase.replaceWithoutHistory(RaftComposerImageEditorContent())
        val afterErase = duringErase.commitTransaction(original)

        val undone = afterErase.undo()

        assertEquals(original, undone.content)
        assertFalse(undone.canUndo)
    }

    @Test
    fun composerImageUndoRestoresContentClearedInOneAction() {
        val original =
            RaftComposerImageEditorContent(
                strokes =
                    listOf(
                        RaftComposerImageStroke(
                            points = listOf(Offset(10f, 10f), Offset(80f, 10f))
                        )
                    ),
                textAnnotations =
                    listOf(
                        RaftComposerImageTextAnnotation(
                            id = 1,
                            text = "note",
                            position = Offset(20f, 20f)
                        )
                    )
            )
        val cleared = RaftComposerImageEditorHistory(content = original).apply(RaftComposerImageEditorContent())
        val restored = cleared.undo()

        assertEquals(original, restored.content)
        assertEquals(RaftComposerImageEditorContent(), restored.redo().content)
    }

    @Test
    fun composerImageRedoRestoresTheUndoneEdit() {
        val stroke = RaftComposerImageStroke(points = listOf(Offset(10f, 10f), Offset(80f, 10f)))
        val edited =
            RaftComposerImageEditorHistory().apply(
                RaftComposerImageEditorContent(strokes = listOf(stroke))
            )

        val undone = edited.undo()
        val redone = undone.redo()

        assertEquals(edited.content, redone.content)
        assertTrue(redone.canUndo)
        assertFalse(redone.canRedo)
    }

    @Test
    fun composerImageNewEditClearsTheRedoBranch() {
        val firstStroke = RaftComposerImageStroke(points = listOf(Offset(10f, 10f), Offset(80f, 10f)))
        val replacementStroke = RaftComposerImageStroke(points = listOf(Offset(20f, 20f), Offset(90f, 20f)))
        val undone =
            RaftComposerImageEditorHistory()
                .apply(RaftComposerImageEditorContent(strokes = listOf(firstStroke)))
                .undo()

        val branched = undone.apply(RaftComposerImageEditorContent(strokes = listOf(replacementStroke)))

        assertFalse(branched.canRedo)
        assertEquals(listOf(replacementStroke), branched.content.strokes)
    }

    @Test
    fun composerImageNoOpDoesNotCreateUndoHistory() {
        val history = RaftComposerImageEditorHistory()

        val unchanged = history.apply(history.content).commitTransaction(history.content)

        assertFalse(unchanged.canUndo)
        assertEquals(history, unchanged)
    }

    @Test
    fun imageEditorControlsReserveTheMainToolbarHeight() {
        assertEquals(
            80.dp,
            raftFilePreviewEditorControlsHeight(
                secondaryControlsVisible = false,
                bottomPadding = 30.dp,
            )
        )
    }

    @Test
    fun imageEditorControlsReserveTheExpandedPencilToolbarHeight() {
        assertEquals(
            132.dp,
            raftFilePreviewEditorControlsHeight(
                secondaryControlsVisible = true,
                bottomPadding = 30.dp,
            )
        )
    }

    @Test
    fun imageEditorPencilButtonTogglesItsOptions() {
        assertFalse(
            raftFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = RaftComposerImageEditorTool.Pencil,
                selectedTool = RaftComposerImageEditorTool.Pencil,
                currentlyExpanded = true,
            )
        )
        assertTrue(
            raftFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = RaftComposerImageEditorTool.Pencil,
                selectedTool = RaftComposerImageEditorTool.Pencil,
                currentlyExpanded = false,
            )
        )
    }

    @Test
    fun imageEditorSelectingPencilFromAnotherToolOpensItsOptions() {
        assertTrue(
            raftFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = RaftComposerImageEditorTool.Text,
                selectedTool = RaftComposerImageEditorTool.Pencil,
                currentlyExpanded = false,
            )
        )
    }

    @Test
    fun imageEditorTextAndMosaicHidePencilOptions() {
        assertFalse(
            raftFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = RaftComposerImageEditorTool.Pencil,
                selectedTool = RaftComposerImageEditorTool.Text,
                currentlyExpanded = true,
            )
        )
        assertFalse(
            raftFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = RaftComposerImageEditorTool.Pencil,
                selectedTool = RaftComposerImageEditorTool.Mosaic,
                currentlyExpanded = true,
            )
        )
        assertFalse(
            raftFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = RaftComposerImageEditorTool.Pencil,
                selectedTool = RaftComposerImageEditorTool.Crop,
                currentlyExpanded = true,
            )
        )
    }

    @Test
    fun imageEditorBottomPaddingUsesSafeAreaWhenKeyboardIsHidden() {
        assertEquals(
            30.dp,
            raftFilePreviewEditorBottomPadding(
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
            raftFilePreviewEditorBottomPadding(
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
            raftFilePreviewEditorBottomPadding(
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
                RaftFilePreviewComposerImageActionSlot.Edit,
                RaftFilePreviewComposerImageActionSlot.FlexibleSpacer,
            ),
            raftFilePreviewComposerImageActionSlots(canEdit = true, canComplete = false),
        )
    }

    @Test
    fun composerImageActionSlotsKeepDoneOnlyRight() {
        assertEquals(
            listOf(
                RaftFilePreviewComposerImageActionSlot.FlexibleSpacer,
                RaftFilePreviewComposerImageActionSlot.Done,
            ),
            raftFilePreviewComposerImageActionSlots(canEdit = false, canComplete = true),
        )
    }

    @Test
    fun composerImageActionSlotsKeepDualActionsAtOppositeEdges() {
        assertEquals(
            listOf(
                RaftFilePreviewComposerImageActionSlot.Edit,
                RaftFilePreviewComposerImageActionSlot.FlexibleSpacer,
                RaftFilePreviewComposerImageActionSlot.Done,
            ),
            raftFilePreviewComposerImageActionSlots(canEdit = true, canComplete = true),
        )
    }

    @Test
    fun composerImageActionSlotsPutOriginalImageOnConfirmBar() {
        assertEquals(
            listOf(
                RaftFilePreviewComposerImageActionSlot.Edit,
                RaftFilePreviewComposerImageActionSlot.OriginalImage,
                RaftFilePreviewComposerImageActionSlot.FlexibleSpacer,
                RaftFilePreviewComposerImageActionSlot.Done,
            ),
            raftFilePreviewComposerImageActionSlots(
                canEdit = true,
                canComplete = true,
                originalImageOptionEnabled = true,
            ),
        )
        assertEquals(
            listOf(
                RaftFilePreviewComposerImageActionSlot.Edit,
                RaftFilePreviewComposerImageActionSlot.FlexibleSpacer,
            ),
            raftFilePreviewComposerImageActionSlots(
                canEdit = true,
                canComplete = false,
                originalImageOptionEnabled = true,
            ),
        )
    }

    @Test
    fun downloadStatusLabelIncludesPercentAndSpeed() {
        val label =
            raftFilePreviewDownloadStatusLabel(
                BridgeAttachmentDownloadProgress(
                    percent = 42,
                    downloadedBytes = 4L * 1024L * 1024L,
                    totalBytes = 10L * 1024L * 1024L,
                    bytesPerSecond = 512L * 1024L
                ),
                englishDownloadTerms
            )

        assertEquals("Downloading 42% · 512.0 KB/s", label)
    }

    @Test
    fun downloadStatusLabelShowsPausedState() {
        val label =
            raftFilePreviewDownloadStatusLabel(
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
            raftFilePreviewDownloadStatusLabel(
                BridgeAttachmentDownloadProgress(
                    percent = null,
                    downloadedBytes = 1536L,
                    totalBytes = null,
                    bytesPerSecond = null
                ),
                englishDownloadTerms
            )

        assertEquals("Downloading 1.5 KB", label)
    }

    @Test
    fun initialDownloadStatusDoesNotPretendBytesHaveTransferred() {
        val label = raftFilePreviewDownloadStatusLabel(RaftFilePreviewInitialDownloadProgress, englishDownloadTerms)

        assertEquals("Downloading...", label)
    }

    @Test
    fun mediaStoreExportUsesSavingLabel() {
        val label =
            raftFilePreviewDownloadStatusLabel(
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
            RaftFilePreviewDownloadStatusTerms(
                downloading = "下载中",
                paused = "已暂停",
                saving = "保存中"
            )

        assertEquals(
            "下载中 42% · 512.0 KB/s",
            raftFilePreviewDownloadStatusLabel(
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
            raftFilePreviewActiveDownloadStatusLabel(
                progress = null,
                status = "paused",
                terms = terms
            )
        )
        assertEquals(
            "保存中 42%",
            raftFilePreviewDownloadStatusLabel(
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

        assertFalse(raftAttachmentDownloadControlAllowed(progress, "pause"))
        assertFalse(raftAttachmentDownloadControlAllowed(progress, "resume"))
        assertTrue(raftAttachmentDownloadControlAllowed(progress, "stop"))
    }

    @Test
    fun activeDownloadStatusStaysIndeterminateBeforeNativeProgress() {
        val label =
            raftFilePreviewActiveDownloadStatusLabel(
                progress = null,
                status = "downloading",
                terms = englishDownloadTerms
            )

        assertEquals("Downloading...", label)
    }

    @Test
    fun pausedDownloadStatusStaysIndeterminateBeforeNativeProgress() {
        val label =
            raftFilePreviewActiveDownloadStatusLabel(
                progress = null,
                status = "paused",
                terms = englishDownloadTerms
            )

        assertEquals("Paused...", label)
    }

    @Test
    fun primaryActionShowsDownloadWhenPublicDownloadIsMissing() {
        val label =
            raftFilePreviewPrimaryActionLabel(
                RaftFilePreviewPayload(externalFileName = "report.pdf"),
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
                terms = englishActionTerms
            )

        assertEquals("Download", label)
    }

    @Test
    fun primaryActionShowsOpenWhenExactPublicDownloadIsPresent() {
        val label =
            raftFilePreviewPrimaryActionLabel(
                RaftFilePreviewPayload(externalFileName = "report.pdf"),
                downloadState = RaftFilePreviewDownloadUiState.Downloaded,
                terms = englishActionTerms
            )

        assertEquals("Open", label)
    }

    @Test
    fun primaryActionShowsInstallForDownloadedApk() {
        val label =
            raftFilePreviewPrimaryActionLabel(
                RaftFilePreviewPayload(
                    externalFileName = "raft.apk",
                    externalMimeType = "application/vnd.android.package-archive"
                ),
                downloadState = RaftFilePreviewDownloadUiState.Downloaded,
                terms = englishActionTerms
            )

        assertEquals("Install", label)
    }

    @Test
    fun overflowActionKeepsOpenWithSeparateFromExplicitDownload() {
        val label =
            raftFilePreviewOverflowActionLabel(
                RaftFilePreviewPayload(externalFileName = "report.pdf"),
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
                terms = englishActionTerms
            )

        assertEquals("Open with...", label)
    }

    @Test
    fun filePreviewActionsUseLocalizedTerms() {
        val terms =
            RaftFilePreviewActionTerms(
                download = "下载",
                install = "安装",
                open = "打开",
                openWith = "打开方式…"
            )

        assertEquals(
            "下载",
            raftFilePreviewPrimaryActionLabel(
                RaftFilePreviewPayload(externalFileName = "report.pdf"),
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
                terms = terms
            )
        )
        assertEquals(
            "安装",
            raftFilePreviewPrimaryActionLabel(
                RaftFilePreviewPayload(
                    externalFileName = "raft.apk",
                    externalMimeType = "application/vnd.android.package-archive"
                ),
                downloadState = RaftFilePreviewDownloadUiState.Downloaded,
                terms = terms
            )
        )
        assertEquals(
            "打开方式…",
            raftFilePreviewOverflowActionLabel(
                RaftFilePreviewPayload(externalFileName = "report.pdf"),
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
                terms = terms
            )
        )
    }

    @Test
    fun primaryActionUsesPublicDownloadAuthorityAndSuppressesUnresolvedPreflight() {
        assertNull(raftFilePreviewPrimaryAction(downloadState = null))
        assertEquals(
            RaftFilePreviewPrimaryAction.Save,
            raftFilePreviewPrimaryAction(RaftFilePreviewDownloadUiState.NotDownloaded)
        )
        assertEquals(
            RaftFilePreviewPrimaryAction.Open,
            raftFilePreviewPrimaryAction(RaftFilePreviewDownloadUiState.Downloaded)
        )
        assertNull(raftFilePreviewPrimaryAction(RaftFilePreviewDownloadUiState.Downloading))
    }

    @Test
    fun downloadedOpenRequiresPublicAuthorityWhileMissingOpenMayMaterialize() {
        assertEquals(
            RaftFilePreviewExternalOpenPolicy(
                requirePublicDownload = true,
                trackProgress = false
            ),
            raftFilePreviewExternalOpenPolicy(
                RaftFilePreviewPublicDownloadState.Downloaded(
                    generation = 7L,
                    sizeBytes = 25L,
                    persistedUri = "content://downloads/public-7"
                )
            )
        )
        assertEquals(
            RaftFilePreviewExternalOpenPolicy(
                requirePublicDownload = false,
                trackProgress = true
            ),
            raftFilePreviewExternalOpenPolicy(RaftFilePreviewPublicDownloadState.NotDownloaded)
        )
        assertNull(raftFilePreviewExternalOpenPolicy(RaftFilePreviewPublicDownloadState.Checking))
    }

    @Test
    fun preflightRequiresCompleteExactPublicAuthorityBeforeClaimingDownloaded() {
        val present =
            JSONObject()
                .put("status", "ok")
                .put("cached", true)
                .put("recordVersion", 7L)
                .put("sizeBytes", 25L)
                .put("uri", "content://downloads/public-7")

        assertEquals(
            RaftFilePreviewPublicDownloadObservation(
                state =
                    RaftFilePreviewPublicDownloadState.Downloaded(
                        generation = 7L,
                        sizeBytes = 25L,
                        persistedUri = "content://downloads/public-7"
                    )
            ),
            raftFilePreviewPublicDownloadObservation(present)
        )

        listOf(
            JSONObject().put("status", "ok").put("cached", false),
            JSONObject().put("status", "storage_failed").put("cached", true),
            JSONObject().put("status", "ok").put("cached", true).put("sizeBytes", 25L)
                .put("uri", "content://downloads/public-7"),
            JSONObject().put("status", "ok").put("cached", true).put("recordVersion", 7L)
                .put("uri", "content://downloads/public-7"),
            JSONObject().put("status", "ok").put("cached", true).put("recordVersion", 7L)
                .put("sizeBytes", 25L),
            JSONObject().put("status", "ok").put("cached", true).put("recordVersion", 7L)
                .put("sizeBytes", 25L).put("uri", "file:///Download/Raft/report.pdf"),
            null
        ).forEach { response ->
            assertEquals(
                RaftFilePreviewPublicDownloadState.NotDownloaded,
                raftFilePreviewPublicDownloadObservation(response).state
            )
        }
    }

    @Test
    fun preflightAcceptsExactAppPrivateFileOnlyWithExplicitStorageKind() {
        val privateUri = "file://build.raft.mobile/data/storage/el2/base/files/slock-attachment-downloads/report.pdf"
        val response =
            JSONObject()
                .put("status", "ok")
                .put("cached", true)
                .put("recordVersion", 11L)
                .put("sizeBytes", 4096L)
                .put("uri", privateUri)
                .put("storageKind", "app_private")

        assertEquals(
            RaftFilePreviewPublicDownloadState.Downloaded(
                generation = 11L,
                sizeBytes = 4096L,
                persistedUri = privateUri
            ),
            raftFilePreviewPublicDownloadObservation(response).state
        )
        assertEquals(
            RaftFilePreviewPublicDownloadState.NotDownloaded,
            raftFilePreviewPublicDownloadObservation(
                JSONObject(response.toString()).put("storageKind", "")
            ).state
        )
    }

    @Test
    fun privateExportLifecycleNeverMutatesDownloadStatusProgressOrAttempt() {
        val progress =
            BridgeAttachmentDownloadProgress(
                downloadId = "download-7",
                attemptToken = "attempt-7",
                percent = 42,
                downloadedBytes = 42L,
                totalBytes = 100L
            )
        val initial =
            RaftFilePreviewExportIsolationState(
                downloadStatus = "paused",
                downloadProgress = progress,
                activeDownloadId = "download-7",
                exportInProgress = false
            )

        RaftFilePreviewExportIsolationEvent.entries.forEach { event ->
            val reduced = reduceRaftFilePreviewExportIsolationState(initial, event)
            assertEquals(initial.downloadStatus, reduced.downloadStatus, event.name)
            assertEquals(initial.downloadProgress, reduced.downloadProgress, event.name)
            assertEquals(initial.activeDownloadId, reduced.activeDownloadId, event.name)
            assertEquals(event == RaftFilePreviewExportIsolationEvent.Started, reduced.exportInProgress, event.name)
        }
    }

    @Test
    fun unresolvedPreflightIsNotRenderedAsFalseNotDownloadedState() {
        assertNull(
            raftFilePreviewDownloadUiState(
                publicDownloadState = RaftFilePreviewPublicDownloadState.Checking,
                downloadStatus = "idle"
            )
        )
        assertEquals(
            RaftFilePreviewDownloadUiState.NotDownloaded,
            raftFilePreviewDownloadUiState(
                publicDownloadState = RaftFilePreviewPublicDownloadState.NotDownloaded,
                downloadStatus = "idle"
            )
        )
        assertEquals(
            RaftFilePreviewDownloadUiState.Downloading,
            raftFilePreviewDownloadUiState(
                publicDownloadState = RaftFilePreviewPublicDownloadState.NotDownloaded,
                downloadStatus = "downloading"
            )
        )
        assertEquals(
            RaftFilePreviewDownloadUiState.Downloaded,
            raftFilePreviewDownloadUiState(
                publicDownloadState =
                    RaftFilePreviewPublicDownloadState.Downloaded(
                        generation = 7L,
                        sizeBytes = 25L,
                        persistedUri = "content://downloads/public-7"
                    ),
                downloadStatus = "idle"
            )
        )
    }

    @Test
    fun stalePreflightForPreviousAttachmentOrPrincipalCannotCommit() {
        val first = publicDownloadQuery("attachment-1", "a")
        val second = publicDownloadQuery("attachment-2", "b")
        val observation =
            RaftFilePreviewPublicDownloadObservation(
                RaftFilePreviewPublicDownloadState.Downloaded(
                    generation = 7L,
                    sizeBytes = 25L,
                    persistedUri = "content://downloads/public-7"
                )
            )

        assertNull(
            raftFilePreviewCommitPublicDownloadObservation(
                request = first,
                current = second,
                observation = observation
            )
        )
        assertEquals(
            RaftFilePreviewPublicDownloadState.Checking,
            raftFilePreviewVisiblePublicDownloadState(
                current = second,
                observedFor = first,
                observedState = observation.state
            )
        )
        assertEquals(
            observation,
            raftFilePreviewCommitPublicDownloadObservation(
                request = second,
                current = second,
                observation = observation
            )
        )
    }

    @Test
    fun clickTimeMissingOrUnverifiablePublicFileDowngradesImmediately() {
        val downloaded =
            RaftFilePreviewPublicDownloadState.Downloaded(
                generation = 7L,
                sizeBytes = 25L,
                persistedUri = "content://downloads/public-7"
            )

        listOf("missing", "storage_failed", "download_failed").forEach { terminal ->
            val downgraded =
                raftFilePreviewPublicDownloadStateAfterOpen(
                    current = downloaded,
                    result = JSONObject().put("status", terminal).put("cached", false)
                )
            assertEquals(
                RaftFilePreviewPublicDownloadState.NotDownloaded,
                downgraded
            )
            assertEquals(
                RaftFilePreviewDownloadUiState.NotDownloaded,
                raftFilePreviewDownloadUiState(
                    publicDownloadState = downgraded,
                    downloadStatus = "failed"
                )
            )
            assertEquals(
                RaftFilePreviewPrimaryAction.Save,
                raftFilePreviewPrimaryAction(RaftFilePreviewDownloadUiState.NotDownloaded)
            )
            assertTrue(raftFilePreviewCanStartExternalOperation("failed"))
        }
    }

    @Test
    fun unsupportedDownloadableFilesShowDownloadEntryMessage() {
        val label =
            raftFilePreviewUnsupportedFileMessage(
                RaftFilePreviewPayload(
                    kind = "unsupported",
                    externalFileName = "archive.zip",
                    messageDesc = Res.string.file_preview_no_preview.desc()
                ),
                downloadable = true
            )

        assertEquals(Res.string.file_preview_download_to_open.desc(), label)
    }

    @Test
    fun unsupportedNonDownloadableFilesKeepPreviewMessage() {
        val label =
            raftFilePreviewUnsupportedFileMessage(
                RaftFilePreviewPayload(
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
            raftFilePreviewFileIconName(
                RaftFilePreviewPayload(
                    externalFileName = "raft.apk",
                    externalMimeType = "application/vnd.android.package-archive"
                )
            )

        assertEquals(RaftLucideIconName.Package, icon)
    }

    @Test
    fun fileIconUsesArchiveForCompressedFiles() {
        val icon =
            raftFilePreviewFileIconName(
                RaftFilePreviewPayload(externalFileName = "logs.zip")
            )

        assertEquals(RaftLucideIconName.FileArchive, icon)
    }

    @Test
    fun fileIconUsesCodeForSourceFiles() {
        val icon =
            raftFilePreviewFileIconName(
                RaftFilePreviewPayload(externalFileName = "ThreadPage.kt")
            )

        assertEquals(RaftLucideIconName.FileCode, icon)
    }

    @Test
    fun fileIconUsesTypeForPlainTextFiles() {
        val icon =
            raftFilePreviewFileIconName(
                RaftFilePreviewPayload(externalFileName = "notes.txt")
            )

        assertEquals(RaftLucideIconName.FileType, icon)
    }

    @Test
    fun fileIconUsesPresentationForPowerPointFiles() {
        val icon =
            raftFilePreviewFileIconName(
                RaftFilePreviewPayload(externalFileName = "deck.pptx")
            )

        assertEquals(RaftLucideIconName.Presentation, icon)
    }

    @Test
    fun fileIconUsesVideoForMp4Files() {
        val icon =
            raftFilePreviewFileIconName(
                RaftFilePreviewPayload(externalFileName = "clip.mp4")
            )

        assertEquals(RaftLucideIconName.FileVideoCamera, icon)
    }

    @Test
    fun videoContainerDetectorCoversPlayerFormatsWithoutBlanketVideoMimeAdmission() {
        val cases =
            listOf(
                Triple("clip.bin", "video/mp4; codecs=avc1", RaftPreviewableVideoContainer.Mp4),
                Triple("camera.MOV?token=1", "application/octet-stream", RaftPreviewableVideoContainer.QuickTime),
                Triple("clip.bin", "video/x-m4v", RaftPreviewableVideoContainer.M4v),
                Triple("clip.3gp", "", RaftPreviewableVideoContainer.ThreeGpp),
                Triple("clip.bin", "video/3gpp2", RaftPreviewableVideoContainer.ThreeGpp2),
                Triple("clip.WEBM#frame", "", RaftPreviewableVideoContainer.WebM),
                Triple("clip.bin", "video/x-matroska", RaftPreviewableVideoContainer.Matroska),
                Triple("clip.m2ts", "application/octet-stream", RaftPreviewableVideoContainer.MpegTs),
                Triple("clip.bin", "video/mpeg", RaftPreviewableVideoContainer.MpegPs),
                Triple("clip.flv", "", RaftPreviewableVideoContainer.Flv)
            )

        cases.forEach { (fileName, mimeType, expected) ->
            assertEquals(expected, raftFilePreviewVideoContainer(fileName, mimeType), "$fileName / $mimeType")
        }
        assertEquals(null, raftFilePreviewVideoContainer("clip.avi", "video/x-msvideo"))
        assertEquals(null, raftFilePreviewVideoContainer("clip.bin", "video/vnd.future-container"))
        assertEquals(null, raftFilePreviewVideoContainer("archive.zip", "application/zip"))
    }

    @Test
    fun videoPlayerProfilesMatchTheThreeConcreteHosts() {
        val all = RaftPreviewableVideoContainer.entries.toSet()
        val apple =
            setOf(
                RaftPreviewableVideoContainer.Mp4,
                RaftPreviewableVideoContainer.QuickTime,
                RaftPreviewableVideoContainer.M4v,
                RaftPreviewableVideoContainer.ThreeGpp,
                RaftPreviewableVideoContainer.ThreeGpp2
            )

        assertEquals(
            all,
            all.filterTo(mutableSetOf()) {
                raftVideoPlayerFormatProfileSupports(RaftVideoPlayerFormatProfile.AndroidMedia3, it)
            }
        )
        assertEquals(
            apple,
            all.filterTo(mutableSetOf()) {
                raftVideoPlayerFormatProfileSupports(RaftVideoPlayerFormatProfile.IosAvPlayer, it)
            }
        )
        // The Compose desktop app plays through AVFoundation too (ComposeMediaPlayer on macOS).
        assertEquals(
            apple,
            all.filterTo(mutableSetOf()) {
                raftVideoPlayerFormatProfileSupports(RaftVideoPlayerFormatProfile.DesktopMacAvFoundation, it)
            }
        )
    }

    @Test
    fun audioOnlyKeepsTheSharedControlsWhereTheHostCannotPlayAudio() {
        // Android / iOS: the native controller owns audio and video alike.
        assertTrue(raftFilePreviewVideoUsesNativeController(audioOnly = true, platformNativeController = true, platformPlaysAudioOnly = true))
        assertTrue(raftFilePreviewVideoUsesNativeController(audioOnly = false, platformNativeController = true, platformPlaysAudioOnly = true))
        // Desktop: its player takes video, audio keeps the shared card and controls.
        assertTrue(raftFilePreviewVideoUsesNativeController(audioOnly = false, platformNativeController = true, platformPlaysAudioOnly = false))
        assertFalse(raftFilePreviewVideoUsesNativeController(audioOnly = true, platformNativeController = true, platformPlaysAudioOnly = false))
        // No native controller: shared controls for everything.
        assertFalse(raftFilePreviewVideoUsesNativeController(audioOnly = false, platformNativeController = false, platformPlaysAudioOnly = true))
    }

    @Test
    fun audioClassifierMatchesFullWebAudioAttachmentContract() {
        val audioMimes =
            listOf(
                "audio/mpeg",
                "audio/mp3",
                "audio/wav",
                "audio/x-wav",
                "audio/wave",
                "audio/aac",
                "audio/mp4",
                "audio/x-m4a",
                "audio/ogg",
                "audio/opus",
                "audio/webm",
                "audio/flac",
                "audio/x-flac"
            )
        for (mime in audioMimes) {
            assertEquals(true, raftFilePreviewIsAudioAttachment(fileName = "file.bin", mimeType = mime))
            assertEquals(true, raftFilePreviewIsAudioAttachment(fileName = "file.bin", mimeType = "$mime; charset=binary"))
        }

        val audioExtensions =
            listOf(
                "mp3",
                "wav",
                "m4a",
                "aac",
                "ogg",
                "oga",
                "opus",
                "weba",
                "flac"
            )
        for (ext in audioExtensions) {
            assertEquals(true, raftFilePreviewIsAudioAttachment(fileName = "track.$ext", mimeType = ""))
            assertEquals(true, raftFilePreviewIsAudioAttachment(fileName = "track.${ext.uppercase()}?token=xyz", mimeType = "application/octet-stream"))
        }

        assertEquals(false, raftFilePreviewIsAudioAttachment(fileName = "clip.mp4", mimeType = "video/mp4"))
        assertEquals(false, raftFilePreviewIsAudioAttachment(fileName = "archive.zip", mimeType = "application/zip"))
        assertEquals(false, raftFilePreviewIsAudioAttachment(fileName = "doc.pdf", mimeType = "application/pdf"))
    }

    @Test
    fun mp3AudioClassifierAcceptsOnlyMp3ForMobilePreview() {
        // task #556: mp3 plays through the same player panel as mp4 video.
        assertEquals(true, raftFilePreviewIsMp3AudioAttachment(fileName = "song.mp3", mimeType = ""))
        assertEquals(true, raftFilePreviewIsMp3AudioAttachment(fileName = "song.bin", mimeType = "audio/mpeg"))
        assertEquals(true, raftFilePreviewIsMp3AudioAttachment(fileName = "song.bin", mimeType = "audio/mp3"))
        assertEquals(true, raftFilePreviewIsMp3AudioAttachment(fileName = "song.MP3?token=1", mimeType = ""))
        assertEquals(false, raftFilePreviewIsMp3AudioAttachment(fileName = "song.wav", mimeType = "audio/wav"))
        assertEquals(false, raftFilePreviewIsMp3AudioAttachment(fileName = "song.m4a", mimeType = "audio/mp4"))
        assertEquals(false, raftFilePreviewIsMp3AudioAttachment(fileName = "clip.mp4", mimeType = "video/mp4"))
    }

    @Test
    fun htmlClassifierUsesSandboxPreviewContract() {
        assertEquals(true, raftFilePreviewIsHtmlAttachment(fileName = "report.html", mimeType = ""))
        assertEquals(true, raftFilePreviewIsHtmlAttachment(fileName = "report.htm?token=1", mimeType = ""))
        assertEquals(true, raftFilePreviewIsHtmlAttachment(fileName = "report.bin", mimeType = "text/html; charset=utf-8"))
        assertEquals(false, raftFilePreviewIsHtmlAttachment(fileName = "notes.txt", mimeType = "text/plain"))
    }

    @Test
    fun videoTimeLabelIncludesStateAndDuration() {
        assertEquals(
            "Playing · 0:12 / 1:05",
            raftFilePreviewVideoTimeLabel(
                currentTimeMs = 12_000,
                totalTimeMs = 65_000,
                playState = PlayState.PLAYING,
                terms = englishVideoTerms
            )
        )
        assertEquals(
            "Playback failed · 0:00",
            raftFilePreviewVideoTimeLabel(
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
            RaftFilePreviewVideoStateTerms(
                playing = "播放中",
                buffering = "缓冲中",
                paused = "已暂停",
                ended = "播放结束",
                playbackFailed = "播放失败",
                ready = "就绪"
            )

        assertEquals(
            "播放中 · 0:12 / 1:05",
            raftFilePreviewVideoTimeLabel(
                currentTimeMs = 12_000,
                totalTimeMs = 65_000,
                playState = PlayState.PLAYING,
                terms = terms
            )
        )
        assertEquals(
            "播放失败 · 0:00",
            raftFilePreviewVideoTimeLabel(
                currentTimeMs = 0,
                totalTimeMs = 0,
                playState = PlayState.ERROR,
                terms = terms
            )
        )
    }

    @Test
    fun videoProgressPercentTracksDurationBounds() {
        assertEquals(25, raftFilePreviewVideoProgressPercent(currentTimeMs = 15_000, totalTimeMs = 60_000))
        assertEquals(0, raftFilePreviewVideoProgressPercent(currentTimeMs = -1_000, totalTimeMs = 60_000))
        assertEquals(100, raftFilePreviewVideoProgressPercent(currentTimeMs = 75_000, totalTimeMs = 60_000))
        assertEquals(null, raftFilePreviewVideoProgressPercent(currentTimeMs = 15_000, totalTimeMs = 0))
    }

    @Test
    fun videoSeekTimeTracksTapPosition() {
        assertEquals(30_000, raftFilePreviewVideoSeekTimeFromTap(tapX = 50f, widthPx = 100f, totalTimeMs = 60_000))
        assertEquals(0, raftFilePreviewVideoSeekTimeFromTap(tapX = -10f, widthPx = 100f, totalTimeMs = 60_000))
        assertEquals(60_000, raftFilePreviewVideoSeekTimeFromTap(tapX = 120f, widthPx = 100f, totalTimeMs = 60_000))
        assertEquals(null, raftFilePreviewVideoSeekTimeFromTap(tapX = 50f, widthPx = 0f, totalTimeMs = 60_000))
        assertEquals(null, raftFilePreviewVideoSeekTimeFromTap(tapX = 50f, widthPx = 100f, totalTimeMs = 0))
    }

    @Test
    fun fileIconUsesSpreadsheetForWorkbookFiles() {
        val icon =
            raftFilePreviewFileIconName(
                RaftFilePreviewPayload(externalFileName = "budget.xlsx")
            )

        assertEquals(RaftLucideIconName.FileSpreadsheet, icon)
    }

    @Test
    fun imageAttachmentDetectionFallsBackToFileExtension() {
        assertTrue(
            raftFilePreviewIsImageAttachment(
                fileName = "picked-photo.PNG?download=1",
                mimeType = "application/octet-stream"
            )
        )
        assertTrue(
            RaftThreadAttachmentItem(
                id = "att-1",
                name = "screen.webp",
                mimeType = ""
            ).isImageAttachment()
        )
    }

    @Test
    fun threadImageLazyPreviewKeepsThumbnailAsInitialImageUrl() {
        val payload =
            RaftThreadAttachmentItem(
                id = "att-1",
                name = "photo.jpg",
                mimeType = "",
                thumbnailUrl = "https://cdn.slock.test/thumb.jpg"
            ).toThreadAttachmentLazyPreviewPayload(kind = "image")

        assertEquals("loading", payload.kind)
        assertEquals(Res.string.file_preview_image.desc(), payload.titleDesc)
        assertEquals("https://cdn.slock.test/thumb.jpg", payload.imageUrl)
        assertEquals("att-1", payload.attachmentId)
        assertTrue(payload.shouldRenderImagePreview())
        assertEquals("https://cdn.slock.test/thumb.jpg", payload.currentImageThumbnailUrl())
    }

    @Test
    fun resolvedThreadImageKeepsGalleryThumbnailAsRenderFallback() {
        val payload =
            RaftFilePreviewPayload(
                kind = "image",
                requestedKind = "image",
                attachmentId = "att-1",
                imageUrl = "https://cdn.slock.test/full.jpg?signature=private",
                imageGallery =
                    listOf(
                        RaftFilePreviewGalleryImage(
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
            RaftFilePreviewPayload(
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
            RaftFilePreviewPayload(
                kind = "image",
                imageGallery =
                    listOf(
                        RaftFilePreviewGalleryImage(attachmentId = "att-1"),
                        RaftFilePreviewGalleryImage(attachmentId = "att-2"),
                        RaftFilePreviewGalleryImage(attachmentId = "att-3")
                    ),
                imageGalleryIndex = 1
            )

        assertEquals("2/3", payload.imageGalleryProgressLabel())
    }

    @Test
    fun imageGalleryIndexChangeRebuildsPayloadForSelectedAttachment() {
        val payload =
            RaftFilePreviewPayload(
                kind = "image",
                attachmentId = "att-1",
                name = "one.png",
                externalMimeType = "image/png",
                imageGallery =
                    listOf(
                        RaftFilePreviewGalleryImage(
                            attachmentId = "att-1",
                            name = "one.png",
                            mimeType = "image/png",
                            sizeLabel = "1 KB",
                            thumbnailUrl = "thumb-one"
                        ),
                        RaftFilePreviewGalleryImage(
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
                RaftFilePreviewGalleryImage(
                    attachmentId = "local-1",
                    name = "one.png",
                    mimeType = "image/png",
                    thumbnailUrl = "file://one",
                    composerImageFileRef = "file://one",
                    composerImagePreviewUri = "file://one",
                    composerImageSizeBytes = 10L
                ),
                RaftFilePreviewGalleryImage(
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
            RaftFilePreviewPayload(
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
            RaftFilePreviewPayload(
                composerImageFileRef = "file://one",
                imageGallery =
                    listOf(
                        RaftFilePreviewGalleryImage(
                            attachmentId = "local-1",
                            composerImageFileRef = "file://one"
                        ),
                        RaftFilePreviewGalleryImage(attachmentId = "local-2")
                    )
            )

        assertFalse(payload.canCompleteComposerImageSelection())
    }

    @Test
    fun composerImageGalleryEditReplacesOnlyCurrentImage() {
        val payload =
            RaftFilePreviewPayload(
                imageGalleryIndex = 1,
                name = "two.jpg",
                externalFileName = "two.jpg",
                externalMimeType = "image/jpeg",
                composerImageFileName = "two.jpg",
                composerImageMimeType = "image/jpeg",
                composerImageSizeBytes = 20L,
                imageGallery =
                    listOf(
                        RaftFilePreviewGalleryImage(
                            attachmentId = "local-1",
                            name = "one.jpg",
                            mimeType = "image/jpeg",
                            composerImageFileRef = "file://one",
                            composerImagePreviewUri = "file://one",
                            composerImageSizeBytes = 10L
                        ),
                        RaftFilePreviewGalleryImage(
                            attachmentId = "local-2",
                            name = "two.jpg",
                            mimeType = "image/jpeg",
                            sizeLabel = "20 B",
                            composerImageFileRef = "file://two",
                            composerImagePreviewUri = "file://two",
                            composerImageSizeBytes = 20L
                        )
                    )
            )

        val edited = payload.withComposerImageEditResult("file://two-edited")

        assertEquals("file://one", edited.imageGallery[0].composerImageFileRef)
        assertEquals("one.jpg", edited.imageGallery[0].name)
        assertEquals("image/jpeg", edited.imageGallery[0].mimeType)
        assertEquals("file://two-edited", edited.imageGallery[1].composerImageFileRef)
        assertEquals("two.png", edited.imageGallery[1].name)
        assertEquals("image/png", edited.imageGallery[1].mimeType)
        assertEquals("", edited.imageGallery[1].sizeLabel)
        assertNull(edited.imageGallery[1].composerImageSizeBytes)
        assertEquals("file://two-edited", edited.imageUrl)
        assertEquals("two.png", edited.composerImageFileName)
        assertEquals("image/png", edited.composerImageMimeType)
        assertNull(edited.composerImageSizeBytes)
        assertTrue(edited.canCompleteComposerImageSelection())
    }

    @Test
    fun filePreviewPresentationHidesMimeWhileKeepingUsefulMetadata() {
        val payload =
            RaftFilePreviewPayload(
                name = "report.pdf",
                path = "report.pdf",
                meta = "42 KB · application/pdf",
                subtitle = "application/pdf",
                externalMimeType = "application/pdf"
            )

        assertEquals("42 KB", payload.raftFilePreviewVisibleSubtitle(headerTitle = "report.pdf"))
        assertEquals("42 KB", payload.meta.withoutUserVisibleMimeType())
        assertEquals("folder/file", "folder/file".withoutUserVisibleMimeType())
    }

    @Test
    fun filePreviewDoesNotUseExternalMimeAsSubtitleFallback() {
        val payload =
            RaftFilePreviewPayload(
                name = "report.pdf",
                path = "report.pdf",
                subtitle = "application/pdf",
                externalMimeType = "application/pdf"
            )

        assertEquals(null, payload.raftFilePreviewVisibleSubtitle(headerTitle = "report.pdf"))
    }

    @Test
    fun imageGalleryIndexChangeReusesResolvedPayloadForSelectedAttachment() {
        val gallery =
            listOf(
                RaftFilePreviewGalleryImage(
                    attachmentId = "att-1",
                    name = "one.png",
                    mimeType = "image/png",
                    thumbnailUrl = "thumb-one"
                ),
                RaftFilePreviewGalleryImage(
                    attachmentId = "att-2",
                    name = "two.png",
                    mimeType = "image/png",
                    thumbnailUrl = "thumb-two"
                )
            )
        val payload =
            RaftFilePreviewPayload(
                kind = "image",
                attachmentId = "att-1",
                name = "one.png",
                externalMimeType = "image/png",
                imageGallery = gallery
            )
        val resolved =
            RaftFilePreviewPayload(
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
                RaftFilePreviewGalleryImage(
                    attachmentId = "att-1",
                    name = "one.png",
                    mimeType = "image/png",
                    thumbnailUrl = "thumb-one"
                ),
                RaftFilePreviewGalleryImage(
                    attachmentId = "att-2",
                    name = "two.png",
                    mimeType = "image/png",
                    thumbnailUrl = "thumb-two"
                )
            )
        val first =
            RaftFilePreviewPayload(
                kind = "loading",
                requestedKind = "image",
                name = "one.png",
                imageUrl = "thumb-one",
                source = "thread_attachment",
                attachmentId = "att-1",
                imageGallery = gallery
            )
        val second = first.withImageGalleryIndex(1)!!
        var state = RaftFilePreviewImageGalleryResolutionState()

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
                RaftFilePreviewGalleryImage(attachmentId = "att-1", thumbnailUrl = "thumb-one"),
                RaftFilePreviewGalleryImage(attachmentId = "att-2", thumbnailUrl = "thumb-two"),
                RaftFilePreviewGalleryImage(attachmentId = "att-3", thumbnailUrl = "thumb-three")
            )
        val first =
            RaftFilePreviewPayload(
                kind = "loading",
                requestedKind = "image",
                imageUrl = "thumb-one",
                source = "thread_attachment",
                attachmentId = "att-1",
                imageGallery = gallery
            )
        val second = first.withImageGalleryIndex(1)!!
        val third = first.withImageGalleryIndex(2)!!
        var state = RaftFilePreviewImageGalleryResolutionState().beginResolution(first).state
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
            RaftFilePreviewPayload(
                kind = "loading",
                requestedKind = "image",
                imageUrl = "thumb-one",
                source = "thread_attachment",
                attachmentId = "att-1",
                imageGallery =
                    listOf(
                        RaftFilePreviewGalleryImage(
                            attachmentId = "att-1",
                            thumbnailUrl = "thumb-one"
                        )
                    )
            )
        val firstStart = RaftFilePreviewImageGalleryResolutionState().beginResolution(payload)
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
                RaftFilePreviewGalleryImage(attachmentId = "att-1", thumbnailUrl = "thumb-one"),
                RaftFilePreviewGalleryImage(attachmentId = "att-2", thumbnailUrl = "thumb-two"),
                RaftFilePreviewGalleryImage(attachmentId = "att-3", thumbnailUrl = "thumb-three"),
                RaftFilePreviewGalleryImage(attachmentId = "att-4", thumbnailUrl = "thumb-four")
            )
        val current =
            RaftFilePreviewPayload(
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
