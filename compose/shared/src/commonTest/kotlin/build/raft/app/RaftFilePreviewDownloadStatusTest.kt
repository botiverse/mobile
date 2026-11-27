package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.isImageAttachment
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
            openWith = "Open with"
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
    fun htmlClassifierUsesSandboxPreviewContract() {
        assertEquals(true, raftFilePreviewIsHtmlAttachment(fileName = "report.html", mimeType = ""))
        assertEquals(true, raftFilePreviewIsHtmlAttachment(fileName = "report.htm?token=1", mimeType = ""))
        assertEquals(true, raftFilePreviewIsHtmlAttachment(fileName = "report.bin", mimeType = "text/html; charset=utf-8"))
        assertEquals(false, raftFilePreviewIsHtmlAttachment(fileName = "notes.txt", mimeType = "text/plain"))
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
}
