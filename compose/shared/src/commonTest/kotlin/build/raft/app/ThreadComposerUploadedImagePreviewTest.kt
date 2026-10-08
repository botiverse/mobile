package build.raft.app

import build.raft.app.data.PlatformFile
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadComposerUploadedImagePreviewTest {
    @Test
    fun readyTilesRoundTripAsLocalEditorsWithExactReplacementTargets() {
        // Cover one image, both initial gallery positions, and both Original policies.
        listOf(listOf(image("first")), listOf(image("first"), image("second"))).forEach { files ->
            files.indices.forEach { selected ->
                listOf(false, true).forEach { original ->
                    val replacements = files.map { "remote-${it.id}" }
                    val payload = assertNotNull(threadComposerUploadedImagePreviewPayload(
                        threadInstanceKey = "thread-instance",
                        actionId = "edit-action",
                        files = files,
                        replacementAttachmentIds = replacements,
                        selectedIndex = selected,
                        originalImage = original
                    ))
                    val launch = slockFilePreviewLaunchDescriptor("opaque-session", payload)
                    assertEquals(SlockFilePreviewRouteEphemeral, launch.routeKind)
                    assertFalse(launch.requiresAuthenticatedRouteAuthority())
                    assertEquals("", launch.attachmentId)
                    assertTrue(launch.galleryAttachmentIds.isEmpty())
                    val trace = slockFilePreviewResolveLaunchPayload(
                        source = Json.encodeToString(WireSlockFilePreviewLaunchPayload.serializer(), launch),
                        loadPreviewPayload = { id -> if (id == "opaque-session") payload else null }
                    )
                    assertEquals("success", trace.result)
                    val restored = trace.payload
                    assertLocalImage(restored, files[selected], replacements[selected])
                    assertEquals(!original, restored.composerImageCompressAfterEdit)
                    assertTrue(restored.composerImageOriginalOptionEnabled)
                    assertEquals(files.map { it.fileRef }, restored.composerImageOwnedTemporaryFileRefs)
                    val deliveredReplacements = slockFilePreviewComposerImageEventsForPayload(
                        eventPayload = restored,
                        singleEvent = { _, _, _ -> restored.composerImageReplaceAttachmentId },
                        galleryEvent = { _, item, _, _ -> item.composerImageReplaceAttachmentId }
                    )
                    assertEquals(replacements, deliveredReplacements)
                    if (files.size > 1) {
                        assertEquals("edit-action-group", restored.composerImageGroupId)
                        files.indices.forEach { index ->
                            assertLocalImage(assertNotNull(restored.withImageGalleryIndex(index)), files[index], replacements[index])
                        }
                    } else {
                        assertEquals("", restored.composerImageGroupId)
                        assertTrue(restored.imageGallery.isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun expiredEditorCannotRecoverFileHandlesOrReplacementAuthorityFromWire() {
        val payload = assertNotNull(threadComposerUploadedImagePreviewPayload(
            "thread", "action", listOf(image("first")), listOf("remote-first"), 0, true
        ))
        val launch = slockFilePreviewLaunchDescriptor("expired-session", payload)
        val wire = Json.encodeToString(WireSlockFilePreviewLaunchPayload.serializer(), launch)
        assertFalse(wire.contains("remote-first"))
        assertFalse(wire.contains("file:///"))
        val trace = slockFilePreviewResolveLaunchPayload(wire, loadPreviewPayload = { null })
        assertEquals("ephemeral_expired", trace.result)
        assertFalse(trace.payload.composerImageCanComplete)
        assertEquals("", trace.payload.composerImageFileRef)
        assertEquals("", trace.payload.composerImageReplaceAttachmentId)
    }

    @Test
    fun missingLocalFileNeverBecomesAnEditorAndRemoteFallbackStillRequiresAuthority() {
        val thumbnailOnly = image("first").copy(fileRef = null, previewUri = "https://example.test/thumb.png")
        assertNull(threadComposerUploadedImagePreviewPayload(
            "thread", "action", listOf(thumbnailOnly), listOf("remote-first"), 0, false
        ))
        val remote = KuiklyThreadAttachmentItem(
            id = "remote-first", name = "first.png", mimeType = "image/png"
        ).toThreadAttachmentLazyPreviewPayload(kind = "image")
        val launch = slockFilePreviewLaunchDescriptor("remote-session", remote)
        assertTrue(launch.requiresAuthenticatedRouteAuthority())
        val trace = slockFilePreviewResolveLaunchPayload(
            Json.encodeToString(WireSlockFilePreviewLaunchPayload.serializer(), launch),
            loadPreviewPayload = { remote }
        )
        assertEquals("restored_thread_attachment", trace.result)
        assertTrue(trace.requiresAuthenticatedRouteAuthority())
        assertEquals(SlockFilePreviewSourceThreadAttachment, trace.payload.source)
        assertFalse(trace.payload.composerImageCanComplete)
    }

    @Test
    fun incompleteReplacementMappingIsRejectedWithoutPartialGallery() {
        listOf(emptyList(), listOf("remote-first"), listOf("remote-first", " ")).forEach { ids ->
            assertNull(threadComposerUploadedImagePreviewPayload(
                "thread", "action", listOf(image("first"), image("second")), ids, 0, false
            ))
        }
    }

    private fun assertLocalImage(payload: SlockFilePreviewPayload, file: PlatformFile, replacement: String) {
        assertEquals("image", payload.kind)
        assertTrue(payload.shouldRenderImagePreview())
        assertEquals("", payload.source) // No remote resolver/owner check on an ephemeral editor.
        assertEquals("", payload.attachmentId)
        assertEquals(file.previewUri, payload.imageUrl)
        assertEquals(file.fileRef, payload.composerImageFileRef)
        assertEquals(replacement, payload.composerImageReplaceAttachmentId)
        assertTrue(payload.composerImageCanComplete)
        assertTrue(payload.composerImageCanEdit)
    }

    private fun image(id: String) = PlatformFile(
        id = id, fileName = "$id.png", mimeType = "image/png", sizeBytes = 123L,
        fileRef = "file:///cache/$id.png", previewUri = "file:///cache/$id-preview.png",
        source = "composer_image_editor"
    )
}
