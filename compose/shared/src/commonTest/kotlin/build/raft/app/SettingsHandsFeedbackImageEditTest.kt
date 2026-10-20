package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.InMemorySlockKeyValueStore
import build.raft.app.data.PlatformFile
import build.raft.app.data.SettingsHandsFeedbackDraftStore
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class SettingsHandsFeedbackImageEditTest {
    @Test
    fun settingsEventRequiresTheOwningPreviewSessionAndPageInstance() {
        val ownerA = SettingsHandsFeedbackImageEditSessionTracker()
        val ownerB = SettingsHandsFeedbackImageEditSessionTracker()
        val sessionA = session(expectedCount = 1).copy(previewSessionId = "preview-a")
        val sessionB = session(expectedCount = 1).copy(previewSessionId = "preview-b")

        ownerA.track("preview-a")
        ownerB.track("preview-b")

        assertTrue(
            settingsHandsFeedbackImageEditEventMatchesSession(
                session = sessionA,
                tracker = ownerA,
                eventSessionId = "preview-a",
                eventThreadKey = sessionA.threadKey
            )
        )
        assertFalse(
            settingsHandsFeedbackImageEditEventMatchesSession(
                session = sessionA,
                tracker = ownerB,
                eventSessionId = "preview-a",
                eventThreadKey = sessionA.threadKey
            )
        )
        assertFalse(
            settingsHandsFeedbackImageEditEventMatchesSession(
                session = sessionA,
                tracker = ownerA,
                eventSessionId = sessionB.previewSessionId,
                eventThreadKey = sessionA.threadKey
            )
        )
        assertFalse(
            settingsHandsFeedbackImageEditEventMatchesSession(
                session = null,
                tracker = ownerA,
                eventSessionId = "preview-a",
                eventThreadKey = sessionA.threadKey
            )
        )

        val closed = ownerA.closeAndSnapshot()
        assertEquals(setOf("preview-a"), closed.abortSessionIds)
        assertTrue(closed.inFlightSessionIds.isEmpty())
        assertFalse(
            settingsHandsFeedbackImageEditEventMatchesSession(
                session = sessionA,
                tracker = ownerA,
                eventSessionId = "preview-a",
                eventThreadKey = sessionA.threadKey
            )
        )
    }

    @Test
    fun settingsDisposeAbortsClaimedAndPartialSessionsAndRetiresRefs() {
        val registry = SlockFilePreviewEphemeralActionRegistry()
        val ledger = SlockFilePreviewEphemeralCleanupLedger(InMemorySlockKeyValueStore())
        val tracker = SettingsHandsFeedbackImageEditSessionTracker()
        val sessionId = "settings-preview-a"
        val threadKey = "feedback-group-a"
        val refs =
            mapOf(
                "action-0" to "file:///cache/settings-zero.jpg",
                "action-1" to "file:///cache/settings-one.jpg"
            )

        registry.register(
            sessionId = sessionId,
            threadKey = threadKey,
            actionIds = refs.keys,
            ownedTemporaryFileRefs = refs.values.toSet()
        )
        val pending = requireNotNull(
            registry.prepareDeliveryBatch(
                sessionId = sessionId,
                threadKey = threadKey,
                fileRefByActionId = refs
            )
        )
        ledger.replace(sessionId, pending.pendingFileRefs)
        assertIs<SlockFilePreviewEphemeralActionClaim.Accepted>(
            registry.claim(sessionId, threadKey, "action-0", refs.getValue("action-0"))
        )
        tracker.track(sessionId)

        val retired = mutableMapOf<String, Set<String>>()
        val ownedAtDispose = tracker.closeAndSnapshot()
        assertEquals(setOf(sessionId), ownedAtDispose.abortSessionIds)
        assertTrue(ownedAtDispose.inFlightSessionIds.isEmpty())
        assertTrue(tracker.isDisposed())
        abortSettingsHandsFeedbackImageEditSessions(
            sessionIds = ownedAtDispose.abortSessionIds + ownedAtDispose.inFlightSessionIds,
            registry = registry,
            cleanupLedger = ledger,
            retire = { id, fileRefs -> retired[id] = fileRefs }
        )

        assertEquals(refs.values.toSet(), retired.getValue(sessionId))
        assertTrue(registry.activeSessionIds().isEmpty())
        assertTrue(ledger.take(sessionId).isEmpty())
        assertFalse(tracker.owns(sessionId))
    }

    @Test
    fun settingsHandoffIsDeferredUntilEventJobFinally() {
        val tracker = SettingsHandsFeedbackImageEditSessionTracker()
        assertTrue(tracker.track("in-flight"))
        assertTrue(tracker.markHandoffStarted("in-flight"))
        val closed = tracker.closeAndSnapshot()
        assertTrue(closed.abortSessionIds.isEmpty())
        assertEquals(setOf("in-flight"), closed.inFlightSessionIds)
        assertFalse(tracker.owns("in-flight"))
        assertTrue(tracker.isHandoffStarted("in-flight"))
        tracker.release("in-flight")
        assertFalse(tracker.isHandoffStarted("in-flight"))
    }

    @Test
    fun replacingPickerRevokesPartialWitnessBeforeRegistryRetirement() {
        val tracker = SettingsHandsFeedbackImageEditSessionTracker()
        assertTrue(tracker.track("partial"))
        assertEquals(setOf("partial"), tracker.takePartialSessionIds())
        assertFalse(tracker.owns("partial"))
        // A callback which raced the revocation cannot promote the session into an in-flight
        // handoff after its refs have been scheduled for retirement.
        assertFalse(tracker.markHandoffStarted("partial"))

        assertTrue(tracker.track("in-flight"))
        assertTrue(tracker.markHandoffStarted("in-flight"))
        assertTrue(tracker.takePartialSessionIds().isEmpty())
        assertTrue(tracker.isHandoffStarted("in-flight"))
    }

    @Test
    fun settingsEventJobsCloseAdmissionAndExposeActiveJobsForJoin() = runPortableCoroutineTest {
        val tracker = SettingsHandsFeedbackImageEditEventJobTracker()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job =
            requireNotNull(
                tracker.launch(this) {
                    started.complete(Unit)
                    release.await()
                }
            )
        started.await()
        val snapshot = tracker.closeAndSnapshot()
        assertTrue(job in snapshot)
        assertNull(tracker.launch(this) {})
        release.complete(Unit)
        job.join()
        assertTrue(job.isCompleted)
    }

    @Test
    fun durableEditedImageBuildsPreviewableLocalFilePayload() {
        val payload =
            PlatformFile(
                id = "edited",
                fileName = "photo.png",
                mimeType = "image/png",
                sizeBytes = 765_527L,
                previewUri = "file:///data/user/0/build.raft.mobile/photo.png",
                fileRef = "file:///data/user/0/build.raft.mobile/photo.png",
                source = "settings_feedback_draft"
            ).toSettingsHandsFeedbackAttachmentPreviewPayload("Attachment")

        requireNotNull(payload)
        assertEquals("image", payload.kind)
        assertEquals("image", payload.requestedKind)
        assertEquals("file:///data/user/0/build.raft.mobile/photo.png", payload.imageUrl)
        assertEquals(payload.imageUrl, payload.externalUrl)
        assertEquals("photo.png", payload.externalFileName)
        assertEquals("image/png", payload.externalMimeType)
        assertEquals("settings_feedback_draft", payload.source)
    }

    @Test
    fun requestCarriesExplicitScopedGroupAndEditableGalleryIntent() {
        val sourceStoreScope = SlockShellStoreScope.create("settings-shell-a", "server-a")
        val files =
            listOf(
                image("original-0", "file:///picked/zero.png"),
                image("original-1", "file:///picked/one.png")
            )

        val request =
            settingsHandsFeedbackImageEditRequest(
                accountId = "account-a",
                pageGeneration = "page-generation-a",
                files = files,
                sourceStoreScope = sourceStoreScope,
                groupId = "group-a"
            )!!

        assertEquals("group-a", request.session.groupId)
        assertEquals("feedback-group-a", request.session.threadKey)
        assertEquals("account-a", request.session.accountId)
        assertEquals("page-generation-a", request.session.pageGeneration)
        assertEquals(2, request.session.expectedCount)
        assertEquals(sourceStoreScope, request.sourceStoreScope)
        assertEquals("feedback-group-a", request.previewPayload.composerImageThreadKey)
        assertEquals("feedback-image-group-a", request.previewPayload.composerImageActionId)
        assertEquals("group-a", request.previewPayload.composerImageGroupId)
        assertTrue(request.previewPayload.composerImageCompressAfterEdit)
        assertFalse(request.previewPayload.showsComposerOriginalImageOption())
        assertEquals("file:///picked/zero.png", request.previewPayload.composerImageFileRef)
        assertEquals(
            listOf("file:///picked/zero.png", "file:///picked/one.png"),
            request.previewPayload.imageGallery.map { it.composerImageFileRef }
        )
        assertTrue(request.previewPayload.composerImageCanComplete)
        assertTrue(request.previewPayload.composerImageCanEdit)
    }

    @Test
    fun requestRejectsMissingSessionOrDurablePickerInputs() {
        val sourceStoreScope = SlockShellStoreScope.create("settings-shell-a", "server-a")
        val validFile = image("original", "file:///picked/original.png")

        assertNull(
            settingsHandsFeedbackImageEditRequest(
                "account",
                "",
                listOf(validFile),
                sourceStoreScope,
                "group"
            )
        )
        assertNull(
            settingsHandsFeedbackImageEditRequest(
                "account",
                "page",
                emptyList(),
                sourceStoreScope,
                "group"
            )
        )
        assertNull(
            settingsHandsFeedbackImageEditRequest(
                "account",
                "page",
                listOf(validFile),
                sourceStoreScope,
                ""
            )
        )
        assertNull(
            settingsHandsFeedbackImageEditRequest(
                "account",
                "page",
                listOf(validFile.copy(fileRef = null)),
                sourceStoreScope,
                "group"
            )
        )
    }

    @Test
    fun ohosPickerResultCarriesSettingsAuthorityIntoFilePreviewLaunch() {
        val sourceStoreScope = SlockShellStoreScope.create("legacy", "server-a")
        val picked =
            PlatformFile(
                id = "ohos-picked",
                fileName = "IMG_11604",
                mimeType = "image/jpeg",
                sizeBytes = 214_885L,
                fileRef = "file://media/Photo/11604/IMG_11604.jpg"
            )
        val normalized = normalizeSettingsHandsFeedbackPickerFiles(listOf(picked), imageOnly = true)
        assertEquals(
            ThreadPickedAttachmentComposerAction.OpenImageAnnotation,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachImage",
                fileCount = normalized.size,
                allFilesAreImages = slockComposerImagePickerFilesAreStrictImages(normalized)
            )
        )

        val request =
            requireNotNull(
                settingsHandsFeedbackImageEditRequest(
                    accountId = "account-a",
                    pageGeneration = "page-a",
                    files = normalized,
                    sourceStoreScope = sourceStoreScope,
                    groupId = "ohos-group"
                )
            )
        val resolvedScope =
            resolveSlockFilePreviewSourceStoreScope(
                sourcePageData = JSONObject(),
                sourceStoreScope = request.sourceStoreScope
            )
        val pageData =
            slockFilePreviewLaunchPageData(
                launch =
                    slockFilePreviewLaunchDescriptor(
                        sessionId = "ohos-preview",
                        payload = request.previewPayload
                    ),
                storeScope = requireNotNull(resolvedScope)
            )

        assertEquals(SLOCK_FILE_PREVIEW_PAGE, pageData.optString("pageName"))
        assertEquals("legacy", pageData.optString(SLOCK_SHELL_INSTANCE_ID))
        assertEquals("server-a", pageData.optString(SLOCK_SHELL_SERVER_ID))
        assertEquals("file://media/Photo/11604/IMG_11604.jpg", request.previewPayload.imageUrl)
        assertTrue(request.previewPayload.composerImageCanEdit)
        assertTrue(request.previewPayload.composerImageCanComplete)
    }

    @Test
    fun generatedEditIdsAreUniqueAcrossRequests() {
        var next = 0L

        val first = newSettingsHandsFeedbackImageEditId { ++next }
        val second = newSettingsHandsFeedbackImageEditId { ++next }

        assertNotEquals(first, second)
        assertEquals("feedback-image-1-2", first)
        assertEquals("feedback-image-3-4", second)
    }

    @Test
    fun groupedSingleImageCompletesOnlyAtOneOfOne() {
        val session = session(expectedCount = 1)
        val parsed = parseSettingsHandsFeedbackImageEditEvent(eventJson(index = 0, total = 1))!!

        assertEquals(SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE, parsed.source)
        assertFalse(parsed.compressed)
        assertEquals("settings_feedback_draft", parsed.file.source)

        val result =
            foldSettingsHandsFeedbackImageEditEvent(
                session = session,
                event = parsed,
                currentAccountId = session.accountId,
                currentPageGeneration = session.pageGeneration
            )

        val complete = assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(result)
        assertEquals(listOf("file:///edited/0.png"), complete.files.map { it.fileRef })
    }

    @Test
    fun compressedWireMetadataIsRetainedWhileSettingsOwnsDurableSource() {
        val parsed =
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson().apply {
                    put("fileName", "edited-0.jpg")
                    put("mimeType", "image/jpeg")
                    put("source", SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE)
                    put("compressed", true)
                }
            )!!

        assertEquals(SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE, parsed.source)
        assertTrue(parsed.compressed)
        assertEquals("image/jpeg", parsed.file.mimeType)
        assertEquals("settings_feedback_draft", parsed.file.source)
    }

    @Test
    fun multiImageEventsMayArriveOutOfOrderButCompleteInOriginalIndexOrder() {
        val initial = session(expectedCount = 3)

        val afterTwo = assertPending(fold(initial, event(index = 2, total = 3)))
        val afterZero = assertPending(fold(afterTwo, event(index = 0, total = 3)))
        val complete =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(
                fold(afterZero, event(index = 1, total = 3))
            )

        assertEquals(
            listOf("file:///edited/0.png", "file:///edited/1.png", "file:///edited/2.png"),
            complete.files.map { it.fileRef }
        )
    }

    @Test
    fun missingGroupEventNeverProducesACommitCandidateOrMutatesDraft() {
        val preferences = InMemorySlockKeyValueStore()
        val store = SettingsHandsFeedbackDraftStore(preferences)
        val existing = image("existing", "file:///draft/existing.png")
        store.save("account-a", "Keep this draft", listOf(existing))

        val pending = assertPending(fold(session(expectedCount = 2), event(index = 0, total = 2)))

        assertFalse(pending.groupState.isCommittable)
        assertEquals(
            listOf("file:///draft/existing.png"),
            store.read("account-a").attachments.map { it.fileRef }
        )
    }

    @Test
    fun identicalReplayIsIdempotentAndStillAllowsOneCompleteGroup() {
        val firstEvent = event(index = 0, total = 2)
        val afterFirst = assertPending(fold(session(expectedCount = 2), firstEvent))
        val afterReplay = assertPending(fold(afterFirst, firstEvent))

        assertEquals(afterFirst.filesByIndex, afterReplay.filesByIndex)
        assertTrue(afterReplay.groupState.rejects.isEmpty())
        val complete =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(
                fold(afterReplay, event(index = 1, total = 2))
            )
        assertEquals(2, complete.files.size)
    }

    @Test
    fun conflictingReplayPoisonsTheGroupWithoutDraftMutation() {
        val preferences = InMemorySlockKeyValueStore()
        val store = SettingsHandsFeedbackDraftStore(preferences)
        store.save("account-a", "Draft before edit")
        val afterFirst = assertPending(fold(session(expectedCount = 2), event(index = 0, total = 2)))
        val conflict =
            event(index = 0, total = 2).copy(
                actionId = "action-conflict",
                file = image("edited-conflict", "file:///edited/conflict.png")
            )

        val failed =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Failed>(fold(afterFirst, conflict))

        assertEquals(SlockImageEditGroupReconcile.REJECT_DUPLICATE_CONFLICT, failed.reason)
        assertEquals("Draft before edit", store.read("account-a").text)
        assertTrue(store.read("account-a").attachments.isEmpty())
    }

    @Test
    fun crossScopeEventsAreIgnoredAndWrongGroupIsRejected() {
        val session = session(expectedCount = 2)
        val validEvent = event(index = 0, total = 2)

        assertIs<SettingsHandsFeedbackImageEditFoldResult.Ignored>(
            foldSettingsHandsFeedbackImageEditEvent(
                session,
                validEvent,
                currentAccountId = "different-account",
                currentPageGeneration = session.pageGeneration
            )
        )
        assertIs<SettingsHandsFeedbackImageEditFoldResult.Ignored>(
            foldSettingsHandsFeedbackImageEditEvent(
                session,
                validEvent,
                currentAccountId = session.accountId,
                currentPageGeneration = "different-page"
            )
        )
        assertIs<SettingsHandsFeedbackImageEditFoldResult.Ignored>(
            fold(session, validEvent.copy(threadKey = "feedback-other-group"))
        )
        val wrongGroup =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Failed>(
                fold(session, validEvent.copy(groupId = "other-group"))
            )
        assertEquals(SlockImageEditGroupReconcile.REJECT_WRONG_GROUP, wrongGroup.reason)
    }

    @Test
    fun malformedOrNonCompleteEventsCannotEnterAGroup() {
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("action", "replace")))
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("actionId", "")))
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("groupId", "")))
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson().put("fileRef", "")))

        // Finalization metadata is a strict wire contract. Missing, non-Boolean, unknown, and
        // source/MIME-mismatched markers must never be normalized into a promotable PNG/JPEG.
        assertNull(
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson(includeSource = false)
            )
        )
        assertNull(
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson(includeCompressed = false)
            )
        )
        assertNull(
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson().apply { put("compressed", "false") }
            )
        )
        assertNull(
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson().apply { put("source", "unknown-editor") }
            )
        )
        assertNull(
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson().apply {
                    put("source", SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE)
                    put("compressed", true)
                }
            )
        )

        // The shared parser owns group bounds now; malformed totals are rejected before the
        // Settings fold/reconciliation layer can observe them.
        assertNull(parseSettingsHandsFeedbackImageEditEvent(eventJson(total = 0)))
    }

    @Test
    fun fullResultIdentityMakesMetadataChangesConflicting() {
        val original = event(index = 0, total = 2)

        assertNotEquals(
            settingsHandsFeedbackImageEditItemKey(original),
            settingsHandsFeedbackImageEditItemKey(
                original.copy(file = original.file.copy(previewUri = "file:///different-preview.png"))
            )
        )
    }

    @Test
    fun limitsDedupeAndByteCapApplyAfterTheWholeGroupCompletes() {
        val existing =
            (0 until 8).map { index ->
                image("existing-$index", "file:///draft/existing-$index.png", sizeBytes = 100)
            }
        var pending = session(expectedCount = 4)
        val edited =
            listOf(
                image("edited-duplicate", "file:///draft/existing-0.png", sizeBytes = 100),
                image("edited-new-1", "file:///edited/new-1.png", sizeBytes = 100),
                image("edited-new-2", "file:///edited/new-2.png", sizeBytes = 100),
                image("edited-too-large", "file:///edited/large.png", sizeBytes = 2_000)
            )
        edited.dropLast(1).forEachIndexed { index, file ->
            pending = assertPending(fold(pending, event(index, 4).copy(file = file)))
        }
        val complete =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(
                fold(pending, event(index = 3, total = 4).copy(file = edited.last()))
            )

        val promotion =
            promoteSettingsHandsFeedbackEditedAttachments(
                existing = existing,
                edited = complete.files.map { it.copy(source = "settings_feedback_draft") },
                attachmentMaxBytes = 1_000,
                attachmentLimit = 9
            )

        assertEquals(1, promotion.rejectedTooLargeCount)
        assertTrue(promotion.trimmedByLimit)
        assertEquals(9, promotion.attachments.size)
        assertEquals(1, promotion.attachments.count { it.fileRef == "file:///draft/existing-0.png" })
        assertEquals("file:///edited/new-1.png", promotion.attachments.last().fileRef)
    }

    @Test
    fun editedFileRefsPersistInOriginalOrderAcrossDraftStoreRecreation() {
        val preferences = InMemorySlockKeyValueStore()
        val initial = session(expectedCount = 2)
        val afterOne = assertPending(fold(initial, event(index = 1, total = 2)))
        val complete =
            assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(
                fold(afterOne, event(index = 0, total = 2))
            )
        val promotion =
            promoteSettingsHandsFeedbackEditedAttachments(
                existing = emptyList(),
                edited =
                    complete.files.map { file ->
                        file.copy(source = "settings_feedback_draft")
                    },
                attachmentMaxBytes = 10_000,
                attachmentLimit = 9
            )
        SettingsHandsFeedbackDraftStore(preferences).save(
            accountId = "account-a",
            text = "Edited screenshots attached",
            attachments = promotion.attachments
        )

        val restored = SettingsHandsFeedbackDraftStore(preferences).read("account-a")

        assertEquals(
            listOf("file:///edited/0.png", "file:///edited/1.png"),
            restored.attachments.map { it.fileRef }
        )
        assertEquals(
            listOf("settings_feedback_draft", "settings_feedback_draft"),
            restored.attachments.map { it.source }
        )
    }

    @Test
    fun trustedImageOnlyPickerNormalizesOpaquePhotoForTheProductionRoute() {
        val opaque =
            PlatformFile(
                id = "opaque",
                fileName = "opaque-id",
                mimeType = "image/jpeg",
                fileRef = "photo://media/opaque-id"
            )

        val normalized = normalizeSettingsHandsFeedbackPickerFiles(listOf(opaque), imageOnly = true)

        assertEquals("opaque-id.jpg", normalized.single().fileName)
        assertTrue(slockComposerImagePickerFilesAreStrictImages(normalized))
        val video = opaque.copy(fileName = "opaque-video", mimeType = "video/mp4")
        assertEquals("opaque-video", normalizeSettingsHandsFeedbackPickerFiles(listOf(video), true).single().fileName)
        assertFalse(slockComposerImagePickerFilesAreStrictImages(listOf(video)))
        assertEquals(
            "opaque-id",
            normalizeSettingsHandsFeedbackPickerFiles(listOf(opaque), imageOnly = false).single().fileName
        )
    }

    @Test
    fun platformPromotionFailurePerformsNoDraftWrite() = runBlocking {
        var persistCount = 0

        val result =
            commitSettingsHandsFeedbackEditedGroup(
                existing = emptyList(),
                edited = listOf(image("edited", "file:///editor-temp.png")),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 9,
                isCurrent = { true },
                promote = {
                    DataResult.Error(
                        build.raft.app.data.ScreenError("settings_feedback_attachment_promotion_failed")
                    )
                },
                persist = { persistCount += 1 }
            )

        assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Failed>(result)
        assertEquals(0, persistCount)
    }

    @Test
    fun platformPromotionExceptionPerformsNoDraftWrite() = runBlocking {
        var persistCount = 0

        val result =
            commitSettingsHandsFeedbackEditedGroup(
                existing = emptyList(),
                edited = listOf(image("edited", "file:///editor-temp.png")),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 9,
                isCurrent = { true },
                promote = { error("native promotion failed") },
                persist = { persistCount += 1 }
            )

        assertEquals(
            "settings_feedback_attachment_promotion_failed",
            assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Failed>(result).code
        )
        assertEquals(0, persistCount)
    }

    @Test
    fun actualPromotedSizeRejectsOldSmallEventThatBecameTooLarge() = runBlocking {
        var persistCount = 0
        var discarded = emptyList<PlatformFile>()
        val oldSmall = image("edited", "file:///editor-temp.png", sizeBytes = 10)
        val actualLarge =
            oldSmall.copy(
                fileRef = "file:///durable/edited.png",
                previewUri = "file:///durable/edited.png",
                sizeBytes = 1_001,
                source = "settings_feedback_draft"
            )

        val result =
            commitSettingsHandsFeedbackEditedGroup(
                existing = emptyList(),
                edited = listOf(oldSmall),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 9,
                isCurrent = { true },
                promote = { DataResult.Content(listOf(actualLarge)) },
                persist = { persistCount += 1 },
                discard = { discarded = it }
            )

        assertEquals(
            "settings_feedback_attachment_too_large",
            assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Failed>(result).code
        )
        assertEquals(0, persistCount)
        assertEquals(listOf(actualLarge), discarded)
    }

    @Test
    fun actualPromotedSizeAcceptsOldLargeEventThatBecameCompliantAndPersistsNewMetadata() = runBlocking {
        val oldLarge = image("edited", "file:///editor-temp.png", sizeBytes = 2_000)
        val actualSmall =
            oldLarge.copy(
                fileRef = "file:///durable/edited.png",
                previewUri = "file:///durable/edited.png",
                sizeBytes = 800,
                source = "settings_feedback_draft"
            )
        var persisted = emptyList<PlatformFile>()

        val result =
            commitSettingsHandsFeedbackEditedGroup(
                existing = emptyList(),
                edited = listOf(oldLarge),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 9,
                isCurrent = { true },
                promote = { DataResult.Content(listOf(actualSmall)) },
                persist = { persisted = it }
            )

        assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Committed>(result)
        assertEquals(listOf(actualSmall), persisted)
        assertEquals(800L, persisted.single().sizeBytes)
        assertEquals("settings_feedback_draft", persisted.single().source)
    }

    @Test
    fun sessionThatTurnsStaleDuringPromotionDiscardsDurableOutputAndNeverPersists() = runBlocking {
        var currentChecks = 0
        var persistCount = 0
        var discardCount = 0
        val durable =
            image("edited", "file:///durable/edited.png")
                .copy(source = "settings_feedback_draft")

        val result =
            commitSettingsHandsFeedbackEditedGroup(
                existing = emptyList(),
                edited = listOf(image("edited", "file:///editor-temp.png")),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 9,
                isCurrent = { ++currentChecks == 1 },
                promote = { DataResult.Content(listOf(durable)) },
                persist = { persistCount += 1 },
                discard = { discardCount += it.size }
            )

        assertEquals(
            "settings_feedback_attachment_session_stale",
            assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Failed>(result).code
        )
        assertEquals(0, persistCount)
        assertEquals(1, discardCount)
    }

    @Test
    fun attachmentAddedWhilePromotionIsSuspendedIsMergedFromCommitTimeDraftState() = runBlocking {
        val existing = image("existing", "file:///draft/existing.png")
        val concurrent = image("concurrent", "file:///draft/concurrent.pdf")
        val durable =
            image("edited", "file:///durable/edited.png")
                .copy(source = "settings_feedback_draft")
        val promotionStarted = CompletableDeferred<Unit>()
        val finishPromotion = CompletableDeferred<Unit>()
        var currentAttachments = listOf(existing)
        var persisted = emptyList<PlatformFile>()

        val pendingCommit =
            async {
                commitSettingsHandsFeedbackEditedGroup(
                    existing = currentAttachments,
                    edited = listOf(image("edited", "file:///editor-temp.png")),
                    attachmentMaxBytes = 1_000,
                    attachmentLimit = 9,
                    isCurrent = { true },
                    promote = {
                        promotionStarted.complete(Unit)
                        finishPromotion.await()
                        DataResult.Content(listOf(durable))
                    },
                    persist = { persisted = it },
                    currentExisting = { currentAttachments }
                )
            }

        promotionStarted.await()
        currentAttachments = currentAttachments + concurrent
        finishPromotion.complete(Unit)

        val committed =
            assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Committed>(pendingCommit.await())
        val expected = listOf(existing, concurrent, durable)
        assertEquals(expected, committed.promotion.attachments)
        assertEquals(expected, persisted)
    }

    @Test
    fun attachmentAddedIntoLastSlotWhilePromotionIsSuspendedDiscardsTheWholeEditedGroup() =
        runBlocking {
            val existing =
                (0 until 7).map { index ->
                    image("existing-$index", "file:///draft/existing-$index.png")
                }
            val concurrent = image("concurrent", "file:///draft/concurrent.pdf")
            val durable =
                listOf(
                    image("edited-0", "file:///durable/edited-0.png")
                        .copy(source = "settings_feedback_draft"),
                    image("edited-1", "file:///durable/edited-1.png")
                        .copy(source = "settings_feedback_draft")
                )
            val promotionStarted = CompletableDeferred<Unit>()
            val finishPromotion = CompletableDeferred<Unit>()
            var currentAttachments = existing
            var persistCount = 0
            var discarded = emptyList<PlatformFile>()

            val pendingCommit =
                async {
                    commitSettingsHandsFeedbackEditedGroup(
                        existing = currentAttachments,
                        edited =
                            listOf(
                                image("edited-0", "file:///editor-temp-0.png"),
                                image("edited-1", "file:///editor-temp-1.png")
                            ),
                        attachmentMaxBytes = 1_000,
                        attachmentLimit = 9,
                        isCurrent = { true },
                        promote = {
                            promotionStarted.complete(Unit)
                            finishPromotion.await()
                            DataResult.Content(durable)
                        },
                        persist = { persistCount += 1 },
                        currentExisting = { currentAttachments },
                        discard = { discarded = it }
                    )
                }

            promotionStarted.await()
            currentAttachments = currentAttachments + concurrent
            finishPromotion.complete(Unit)

            assertEquals(
                "settings_feedback_attachment_limit_reached",
                assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Failed>(pendingCommit.await()).code
            )
            assertEquals(0, persistCount)
            assertEquals(durable, discarded)
            assertEquals(existing + concurrent, currentAttachments)
        }

    @Test
    fun attachmentRemovedWhilePromotionIsSuspendedIsNotRestoredByTheCommit() = runBlocking {
        val kept = image("kept", "file:///draft/kept.png")
        val removed = image("removed", "file:///draft/removed.png")
        val durable =
            image("edited", "file:///durable/edited.png")
                .copy(source = "settings_feedback_draft")
        val promotionStarted = CompletableDeferred<Unit>()
        val finishPromotion = CompletableDeferred<Unit>()
        var currentAttachments = listOf(kept, removed)
        var persisted = emptyList<PlatformFile>()

        val pendingCommit =
            async {
                commitSettingsHandsFeedbackEditedGroup(
                    existing = currentAttachments,
                    edited = listOf(image("edited", "file:///editor-temp.png")),
                    attachmentMaxBytes = 1_000,
                    attachmentLimit = 9,
                    isCurrent = { true },
                    promote = {
                        promotionStarted.complete(Unit)
                        finishPromotion.await()
                        DataResult.Content(listOf(durable))
                    },
                    persist = { persisted = it },
                    currentExisting = { currentAttachments }
                )
            }

        promotionStarted.await()
        currentAttachments = listOf(kept)
        finishPromotion.complete(Unit)

        val committed =
            assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Committed>(pendingCommit.await())
        assertEquals(listOf(kept, durable), committed.promotion.attachments)
        assertEquals(listOf(kept, durable), persisted)
        assertFalse(persisted.contains(removed))
    }

    @Test
    fun draftClearedWhilePromotionIsSuspendedFailsStaleAndNeverOverwritesTheClear() = runBlocking {
        val existing = image("existing", "file:///draft/existing.png")
        val durable =
            image("edited", "file:///durable/edited.png")
                .copy(source = "settings_feedback_draft")
        val promotionStarted = CompletableDeferred<Unit>()
        val finishPromotion = CompletableDeferred<Unit>()
        var currentAttachments = listOf(existing)
        var draftResetGeneration = 7L
        val initialDraftResetGeneration = draftResetGeneration
        var persistCount = 0
        var discarded = emptyList<PlatformFile>()

        val pendingCommit =
            async {
                commitSettingsHandsFeedbackEditedGroup(
                    existing = currentAttachments,
                    edited = listOf(image("edited", "file:///editor-temp.png")),
                    attachmentMaxBytes = 1_000,
                    attachmentLimit = 9,
                    isCurrent = { true },
                    promote = {
                        promotionStarted.complete(Unit)
                        finishPromotion.await()
                        DataResult.Content(listOf(durable))
                    },
                    persist = { persistCount += 1 },
                    currentExisting = { currentAttachments },
                    initialDraftResetGeneration = initialDraftResetGeneration,
                    currentDraftResetGeneration = { draftResetGeneration },
                    discard = { discarded = it }
                )
            }

        promotionStarted.await()
        currentAttachments = emptyList()
        draftResetGeneration += 1L
        finishPromotion.complete(Unit)

        assertEquals(
            "settings_feedback_attachment_session_stale",
            assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Failed>(pendingCommit.await()).code
        )
        assertEquals(0, persistCount)
        assertEquals(listOf(durable), discarded)
        assertTrue(currentAttachments.isEmpty())
    }

    @Test
    fun persistenceFailureDiscardsPromotedOutputAndNeverCommitsComposeState() = runBlocking {
        val durable =
            image("edited", "file:///durable/edited.png")
                .copy(source = "settings_feedback_draft")
        var discarded = emptyList<PlatformFile>()

        val result =
            commitSettingsHandsFeedbackEditedGroup(
                existing = emptyList(),
                edited = listOf(image("edited", "file:///editor-temp.png")),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 9,
                isCurrent = { true },
                promote = { DataResult.Content(listOf(durable)) },
                persist = { error("draft persistence failed") },
                discard = { discarded = it }
            )

        assertEquals(
            "settings_feedback_attachment_persistence_failed",
            assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Failed>(result).code
        )
        assertEquals(listOf(durable), discarded)
    }

    @Test
    fun invalidPromotionRemainsFailedWhenBestEffortDiscardAlsoFails() = runBlocking {
        var persistCount = 0
        val invalid =
            image("edited", "file:///durable/edited.png")
                .copy(source = "image_annotation")

        val result =
            commitSettingsHandsFeedbackEditedGroup(
                existing = emptyList(),
                edited = listOf(image("edited", "file:///editor-temp.png")),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 9,
                isCurrent = { true },
                promote = { DataResult.Content(listOf(invalid)) },
                persist = { persistCount += 1 },
                discard = { error("host cleanup failed") }
            )

        assertEquals(
            "settings_feedback_attachment_promotion_incomplete",
            assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Failed>(result).code
        )
        assertEquals(0, persistCount)
    }

    @Test
    fun terminalCommitGateAllowsOneInFlightGroupAndRevalidatesCurrentScope() {
        val gate = SettingsHandsFeedbackImageEditCommitGate()
        val claimed = session(expectedCount = 2)
        val other = claimed.copy(groupId = "group-b", threadKey = "feedback-group-b")

        assertTrue(gate.tryClaim(claimed))
        assertFalse(gate.tryClaim(claimed))
        assertFalse(gate.tryClaim(other))
        assertTrue(
            gate.isCurrent(
                session = claimed,
                currentAccountId = claimed.accountId,
                currentPageGeneration = claimed.pageGeneration
            )
        )
        assertFalse(
            gate.isCurrent(
                session = claimed,
                currentAccountId = "different-account",
                currentPageGeneration = claimed.pageGeneration
            )
        )
        assertFalse(
            gate.isCurrent(
                session = claimed,
                currentAccountId = claimed.accountId,
                currentPageGeneration = "different-page"
            )
        )

        gate.release(other)
        assertFalse(gate.tryClaim(other))
        gate.release(claimed)
        assertTrue(gate.tryClaim(other))
    }

    @Test
    fun attachFileKeepsTheDirectAttachmentPath() {
        assertEquals(
            ThreadPickedAttachmentComposerAction.UploadToComposer,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachFile",
                fileCount = 1,
                allFilesAreImages = true
            )
        )
    }

    @Test
    fun feedbackImagePickerMountsDirectlyWithoutAnnotationDetour() {
        // task #300: feedback image picks must mount like attachFile — the merge helper is the
        // only post-pick path, regardless of what the thread resolver would say about images.
        val picked =
            listOf(
                image("photo-1", "file:///picked/1.jpg"),
                image("photo-2", "file:///picked/2.jpg")
            )
        assertEquals(
            ThreadPickedAttachmentComposerAction.OpenImageAnnotation,
            resolveThreadPickedAttachmentComposerAction(
                action = "attachImage",
                fileCount = picked.size,
                allFilesAreImages = true
            )
        )
        val merge =
            settingsHandsFeedbackMergedAttachments(
                existing = emptyList(),
                picked = picked,
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3
            )
        assertEquals(picked.map { it.fileRef }, merge.attachments.map { it.fileRef })
        assertEquals(0, merge.rejectedCount)
        assertFalse(merge.limitTrimmed)
    }

    @Test
    fun feedbackAttachmentMergeFiltersOversizeAndTrimsToLimit() {
        val ok = image("ok", "file:///picked/ok.jpg").copy(sizeBytes = 100L)
        val tooBig = image("big", "file:///picked/big.jpg").copy(sizeBytes = 2_000L)
        val merge =
            settingsHandsFeedbackMergedAttachments(
                existing = emptyList(),
                picked = listOf(ok, tooBig),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3
            )
        assertEquals(listOf("file:///picked/ok.jpg"), merge.attachments.map { it.fileRef })
        assertEquals(1, merge.rejectedCount)
        assertFalse(merge.limitTrimmed)

        val existing = image("existing", "file:///draft/existing.jpg")
        val trimmed =
            settingsHandsFeedbackMergedAttachments(
                existing = listOf(existing),
                picked = (1..4).map { image("p$it", "file:///picked/$it.jpg") },
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3
            )
        assertEquals(3, trimmed.attachments.size)
        assertEquals("file:///draft/existing.jpg", trimmed.attachments.first().fileRef)
        assertTrue(trimmed.limitTrimmed)

        val dup = existing.copy(id = "different-id")
        val deduped =
            settingsHandsFeedbackMergedAttachments(
                existing = listOf(existing),
                picked = listOf(dup, image("new", "file:///picked/new.jpg")),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3
            )
        assertEquals(
            listOf("file:///draft/existing.jpg", "file:///picked/new.jpg"),
            deduped.attachments.map { it.fileRef }
        )
        assertFalse(deduped.limitTrimmed)
    }

    @Test
    fun previewEditRequestBuildsReplaceBoundEditablePayload() {
        val sourceStoreScope = SlockShellStoreScope.create("legacy", "server-a")
        val file =
            PlatformFile(
                id = "att-1",
                fileName = "shot.png",
                mimeType = "image/png",
                sizeBytes = 512L,
                previewUri = "file:///draft/shot-preview.png",
                fileRef = "file:///draft/shot.png",
                source = "settings_feedback_draft"
            )

        val request =
            requireNotNull(
                settingsHandsFeedbackImageEditPreviewRequest(
                    accountId = "account-a",
                    pageGeneration = "page-a",
                    file = file,
                    sourceStoreScope = sourceStoreScope,
                    groupId = "edit-group"
                )
            )

        assertEquals(1, request.session.expectedCount)
        assertEquals("feedback-edit-group", request.session.threadKey)
        assertTrue(request.previewPayload.composerImageCanEdit)
        assertTrue(request.previewPayload.composerImageCanComplete)
        assertEquals("edit-group", request.previewPayload.composerImageGroupId)
        val galleryImage = request.previewPayload.imageGallery.single()
        assertEquals("file:///draft/shot.png", galleryImage.composerImageFileRef)
        assertEquals(
            file.fileRef,
            galleryImage.composerImageReplaceAttachmentId
        )
    }

    @Test
    fun previewEditRequestRejectsBlankRefs() {
        val file =
            PlatformFile(
                id = "att-1",
                fileName = "shot.png",
                mimeType = "image/png",
                fileRef = null
            )
        assertNull(
            settingsHandsFeedbackImageEditPreviewRequest(
                accountId = "account-a",
                pageGeneration = "page-a",
                file = file,
                sourceStoreScope = SlockShellStoreScope.create("legacy", "server-a"),
                groupId = "edit-group"
            )
        )
    }

    @Test
    fun replaceEventParsesAndFoldsToSingleComplete() {
        val session = session(expectedCount = 1)
        val parsed =
            parseSettingsHandsFeedbackImageEditEvent(
                eventJson(index = 0, total = 1).apply {
                    put("action", "replace")
                    put("replaceAttachmentId", "file:///draft/original.png")
                }
            )!!

        assertEquals("replace", parsed.action)
        assertEquals("file:///draft/original.png", parsed.replaceAttachmentId)

        val result =
            foldSettingsHandsFeedbackImageEditEvent(
                session = session,
                event = parsed,
                currentAccountId = session.accountId,
                currentPageGeneration = session.pageGeneration
            )

        val complete = assertIs<SettingsHandsFeedbackImageEditFoldResult.Complete>(result)
        assertEquals("file:///draft/original.png", complete.replaceAttachmentId)
        assertEquals(listOf("file:///edited/0.png"), complete.files.map { it.fileRef })
    }

    @Test
    fun replaceCommitSwapsTargetInPlaceAndKeepsOrder() = runBlocking {
        val before = image("before", "file:///draft/before.png")
        val target = image("target", "file:///draft/target.png")
        val after = image("after", "file:///draft/after.png")
        val edited =
            image("edited", "file:///editor-temp/edited.png")
                .copy(source = "settings_feedback_draft")
        var currentAttachments = listOf(before, target, after)
        var persisted = emptyList<PlatformFile>()

        val result =
            commitSettingsHandsFeedbackEditedGroup(
                existing = currentAttachments,
                edited = listOf(edited),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3,
                replaceAttachmentId = "file:///draft/target.png",
                isCurrent = { true },
                promote = { files -> DataResult.Content(files.map { it.copy(source = "settings_feedback_draft") }) },
                persist = { persisted = it },
                currentExisting = { currentAttachments }
            )

        val committed = assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Committed>(result)
        assertEquals(
            listOf("file:///draft/before.png", "file:///editor-temp/edited.png", "file:///draft/after.png"),
            committed.promotion.attachments.map { it.fileRef }
        )
        assertEquals(committed.promotion.attachments, persisted)
    }

    @Test
    fun replaceCommitFallsBackToAppendWhenTargetWasRemoved() = runBlocking {
        val kept = image("kept", "file:///draft/kept.png")
        val edited =
            image("edited", "file:///editor-temp/edited.png")
                .copy(source = "settings_feedback_draft")
        var currentAttachments = listOf(kept)

        val result =
            commitSettingsHandsFeedbackEditedGroup(
                existing = currentAttachments,
                edited = listOf(edited),
                attachmentMaxBytes = 1_000,
                attachmentLimit = 3,
                replaceAttachmentId = "file:///draft/gone.png",
                isCurrent = { true },
                promote = { files -> DataResult.Content(files.map { it.copy(source = "settings_feedback_draft") }) },
                persist = {},
                currentExisting = { currentAttachments }
            )

        val committed = assertIs<SettingsHandsFeedbackEditedGroupCommitResult.Committed>(result)
        assertEquals(
            listOf("file:///draft/kept.png", "file:///editor-temp/edited.png"),
            committed.promotion.attachments.map { it.fileRef }
        )
    }

    private fun session(expectedCount: Int): SettingsHandsFeedbackImageEditSession =
        SettingsHandsFeedbackImageEditSession(
            groupId = "group-a",
            threadKey = "feedback-group-a",
            accountId = "account-a",
            pageGeneration = "page-generation-a",
            expectedCount = expectedCount
        )

    private fun event(
        index: Int,
        total: Int
    ): SettingsHandsFeedbackImageEditEvent =
        SettingsHandsFeedbackImageEditEvent(
            threadKey = "feedback-group-a",
            actionId = "action-$index",
            groupId = "group-a",
            index = index,
            total = total,
            source = SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE,
            compressed = false,
            file = image("edited-$index", "file:///edited/$index.png")
        )

    private fun eventJson(
        index: Int = 0,
        total: Int = 1,
        includeSource: Boolean = true,
        includeCompressed: Boolean = true
    ): JSONObject =
        JSONObject().apply {
            put("previewSessionId", "feedback-preview-group-a")
            put("threadKey", "feedback-group-a")
            put("actionId", "action-$index")
            put("action", "complete")
            put("groupId", "group-a")
            put("groupIndex", index)
            put("groupTotal", total)
            put("fileRef", "file:///edited/$index.png")
            put("fileName", "edited-$index.png")
            put("mimeType", "image/png")
            put("sizeBytes", 100L)
            put("previewUri", "file:///edited/$index-preview.png")
            if (includeSource) put("source", SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE)
            if (includeCompressed) put("compressed", false)
        }

    private fun fold(
        session: SettingsHandsFeedbackImageEditSession,
        event: SettingsHandsFeedbackImageEditEvent
    ): SettingsHandsFeedbackImageEditFoldResult =
        foldSettingsHandsFeedbackImageEditEvent(
            session = session,
            event = event,
            currentAccountId = session.accountId,
            currentPageGeneration = session.pageGeneration
        )

    private fun assertPending(
        result: SettingsHandsFeedbackImageEditFoldResult
    ): SettingsHandsFeedbackImageEditSession =
        assertIs<SettingsHandsFeedbackImageEditFoldResult.Pending>(result).session

    private fun image(
        id: String,
        fileRef: String,
        sizeBytes: Long = 100
    ): PlatformFile =
        PlatformFile(
            id = id,
            fileName = "$id.png",
            mimeType = "image/png",
            sizeBytes = sizeBytes,
            previewUri = "$fileRef-preview",
            fileRef = fileRef,
            source = SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE
        )
}
