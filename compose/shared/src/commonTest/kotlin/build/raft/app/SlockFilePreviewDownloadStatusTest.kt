package build.raft.app

import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.views.PlayState
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlinx.coroutines.Job
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class SlockFilePreviewDownloadStatusTest {
    @Test
    fun editorDestructiveToolUsesTheOpaqueDangerToken() {
        assertEquals(
            SlockColor.AccentRed,
            slockFilePreviewEditorToolBackground(selected = false, destructive = true)
        )
    }

    @Test
    fun editorSelectedToolKeepsPriorityOverDestructiveTone() {
        assertEquals(
            SlockColor.BrandYellow,
            slockFilePreviewEditorToolBackground(selected = true, destructive = true)
        )
    }

    @Test
    fun filePreviewLaunchPayloadOnlyCarriesPreviewIdentity() {
        val descriptor = WireSlockFilePreviewLaunchPayload.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map(descriptor::getElementName)

        assertEquals(listOf("previewId"), names)
    }

    @Test
    fun filePreviewLaunchPageDataDoesNotSerializeProductState() {
        val pageData = slockFilePreviewLaunchPageData("preview-1")
        val encodedPayload = pageData.optString("payload")

        assertEquals(SLOCK_FILE_PREVIEW_PAGE, pageData.optString("pageName"))
        listOf(
            "title",
            "subtitle",
            "name",
            "path",
            "meta",
            "content",
            "imageUrl",
            "videoUrl",
            "externalUrl",
            "externalFileName",
            "externalMimeType",
            "source",
            "attachmentId",
            "agentId",
            "imageGallery"
        ).forEach { forbidden ->
            assertFalse(encodedPayload.contains(forbidden), "$forbidden must stay outside compose_file_preview launch pageData")
        }
    }

    @Test
    fun filePreviewLaunchPayloadResolvesFromProcessHandoffStore() {
        val previewId =
            SlockFilePreviewLaunchPayloadStore.put(
                SlockFilePreviewPayload(
                    kind = "image",
                    title = "Picked image",
                    imageUrl = "file:///tmp/picked.png"
                )
            )
        val pageData = slockFilePreviewLaunchPageData(previewId)

        val trace =
            slockFilePreviewResolveLaunchPayload(
                source = pageData.optString("payload"),
                loadPreviewPayload = SlockFilePreviewLaunchPayloadStore::get
            )

        assertEquals("success", trace.result)
        assertEquals("Picked image", trace.payload.title)
        assertEquals("file:///tmp/picked.png", trace.payload.imageUrl)
    }

    @Test
    fun filePreviewLaunchPayloadReportsMissingPreviewWhenStoreMisses() {
        val pageData = slockFilePreviewLaunchPageData("missing-preview")

        val trace =
            slockFilePreviewResolveLaunchPayload(
                source = pageData.optString("payload"),
                loadPreviewPayload = { null }
            )

        assertEquals("missing_preview", trace.result)
        assertEquals(MR.strings.file_preview_unavailable.desc(), trace.payload.titleDesc)
        assertEquals("preview_not_found", trace.payload.error)
    }

    @Test
    fun composerImageEraseRemovesFinalMatchingStroke() {
        val remaining =
            slockComposerImageEraseStrokes(
                strokes =
                    listOf(
                        listOf(
                            Offset(10f, 10f),
                            Offset(80f, 10f)
                        )
                    ),
                point = Offset(48f, 12f)
            )

        assertTrue(remaining.isEmpty())
    }

    @Test
    fun composerImageUndoUsesChronologicalHistoryAcrossTextAndPen() {
        val text = SlockComposerImageTextAnnotation(id = 1, text = "note", position = Offset(20f, 20f))
        val stroke = listOf(Offset(10f, 10f), Offset(80f, 10f))
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
        val stroke = listOf(Offset(10f, 10f), Offset(80f, 10f))
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
                strokes = listOf(listOf(Offset(10f, 10f), Offset(80f, 10f))),
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
        val stroke = listOf(Offset(10f, 10f), Offset(80f, 10f))
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
        val firstStroke = listOf(Offset(10f, 10f), Offset(80f, 10f))
        val replacementStroke = listOf(Offset(20f, 20f), Offset(90f, 20f))
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
                )
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
                )
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
                )
            )

        assertEquals("Downloading 1 KB", label)
    }

    @Test
    fun initialDownloadStatusDoesNotPretendBytesHaveTransferred() {
        val label = slockFilePreviewDownloadStatusLabel(SlockFilePreviewInitialDownloadProgress)

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
                )
            )

        assertEquals("Saving 42%", label)
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
        val label = slockFilePreviewActiveDownloadStatusLabel(progress = null, status = "downloading")

        assertEquals("Downloading...", label)
    }

    @Test
    fun pausedDownloadStatusStaysIndeterminateBeforeNativeProgress() {
        val label = slockFilePreviewActiveDownloadStatusLabel(progress = null, status = "paused")

        assertEquals("Paused...", label)
    }

    @Test
    fun threadAttachmentOpeningStatusUsesIndeterminateFallback() {
        val label = ThreadAttachmentOpenUiState(status = "downloading").threadAttachmentStatusLabel()

        assertEquals("Downloading...", label)
    }

    @Test
    fun threadAttachmentPausedStatusUsesIndeterminateFallback() {
        val label = ThreadAttachmentOpenUiState(status = "paused").threadAttachmentStatusLabel()

        assertEquals("Paused...", label)
    }

    @Test
    fun primaryActionShowsDownloadBeforeCacheExists() {
        val label =
            slockFilePreviewPrimaryActionLabel(
                SlockFilePreviewPayload(externalFileName = "report.pdf"),
                cached = false
            )

        assertEquals("Download", label)
    }

    @Test
    fun primaryActionShowsOpenWhenCached() {
        val label =
            slockFilePreviewPrimaryActionLabel(
                SlockFilePreviewPayload(externalFileName = "report.pdf"),
                cached = true
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
                cached = true
            )

        assertEquals("Install", label)
    }

    @Test
    fun overflowActionKeepsOpenWithSeparateFromExplicitDownload() {
        val label =
            slockFilePreviewOverflowActionLabel(
                SlockFilePreviewPayload(externalFileName = "report.pdf"),
                cached = false
            )

        assertEquals("Open with...", label)
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
                playState = PlayState.PLAYING
            )
        )
        assertEquals(
            "Playback failed · 0:00",
            slockFilePreviewVideoTimeLabel(
                currentTimeMs = 0,
                totalTimeMs = 0,
                playState = PlayState.ERROR
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
