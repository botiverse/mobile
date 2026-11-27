package build.raft.app

import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.image.parseRaftHttpCacheControl
import build.raft.app.image.raftAttachmentOriginalImageCacheKey
import build.raft.app.image.raftImageDiskEntryFresh
import build.raft.app.image.raftImageDiskKeyIsImmutable
import build.raft.app.image.raftImageDiskMetadataFromHttpHeaders
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.ThreadInlineAttachmentUrlBatcher
import build.raft.app.thread.ThreadInlineImageFallbackState
import build.raft.app.thread.key
import build.raft.app.thread.threadInlineImageShowsFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Product-hypothesis tests for #proj-mobile:#293 (Hands 5971e9ea):
 * 1. Missing-thumbnail image attachments on the same screen issue ONE `POST /api/attachments/urls`
 *    batch request, share the valid signed URL with full-screen preview, and pass the inline image
 *    as preview placeholder so opening preview never black-screens or re-signs.
 * 2. Original attachment images key on attachment id (stable across 5-minute presigned R2 URLs),
 *    and HTTP disk cache honors `Cache-Control` (`max-age`, `immutable`, `no-cache`, `no-store`)
 *    and `ETag` (`304 Not Modified`).
 */
class ThreadInlineImageWebParityTest {
    private val owner =
        AuthenticatedRouteOwner(
            environmentId = "https://api.raft.build",
            accountId = "user-1",
            serverId = "server-1",
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun missingThumbnailAttachmentsInOneScreenSendOneBatchRequestAndShareUrlWithPreview() = runTest {
        val urlCache = RaftFilePreviewAttachmentUrlCache()
        var state = ThreadInlineImageFallbackState()
        val batches = mutableListOf<List<String>>()
        val batcher =
            ThreadInlineAttachmentUrlBatcher(
                coroutineScope = this,
                ownerProvider = { owner },
                urlCache = urlCache,
                fetchBatch = { ids ->
                    batches += ids
                    ThreadApiResult.Success(
                        ids.associateWith { id ->
                            ThreadAttachmentOpenTarget(
                                url = "https://r2.example/$id.png?X-Amz-Signature=sig-$id",
                                expiresAt = null,
                            )
                        }
                    )
                },
                readState = { state },
                writeState = { state = it },
                debounceMillis = 30L,
            )

        // Four missing-thumbnail images mount on the same screen: must coalesce into ONE batch call.
        listOf("att-1", "att-2", "att-3", "att-4").forEach(batcher::request)
        advanceUntilIdle()

        assertEquals(1, batches.size, "same-screen missing-thumbnail images must issue one batch request")
        assertEquals(listOf("att-1", "att-2", "att-3", "att-4"), batches.single())

        // The shared preview URL cache holds the exact signed inline URL (zero re-sign on tap).
        val cachedUrl = urlCache.get(RaftFilePreviewAttachmentUrlCacheKey(owner, "att-1", "inline"))
        assertEquals("https://r2.example/att-1.png?X-Amz-Signature=sig-att-1", cachedUrl)

        // Opening full-screen preview starts on the inline image as placeholder instead of blank,
        // and gallery resolution accepts the same URL on cache hit instead of discarding it.
        val attachment =
            RaftThreadAttachmentItem(
                id = "att-1",
                name = "screenshot.png",
                mimeType = "image/png",
                sizeLabel = "1.2 MB",
                thumbnailUrl = null,
            )
        val previewPayload =
            attachment.toThreadAttachmentLazyPreviewPayload(
                kind = "image",
                imageGallery = listOf(attachment),
                imageGalleryIndex = 0,
                inlineImageUrlsByAttachmentId = state.resolvedUrls,
            )
        assertEquals(cachedUrl, previewPayload.imageUrl)
        assertEquals(cachedUrl, previewPayload.currentImageThumbnailUrl())
        assertTrue(previewPayload.raftFilePreviewShowsImage())

        val resolvedPayload = previewPayload.copy(kind = "image", imageUrl = cachedUrl, error = null)
        val galleryState =
            RaftFilePreviewImageGalleryResolutionState()
                .completeResolution(requestPayload = previewPayload, resolvedPayload = resolvedPayload)
        assertEquals(resolvedPayload, galleryState.resolvedPayloadByAttachmentId["att-1"])

        // An expired inline URL load failure invalidates the cache and triggers one fresh re-sign.
        batcher.invalidateAndRetry("att-1", cachedUrl!!)
        advanceUntilIdle()
        assertEquals(2, batches.size)
        assertEquals(listOf("att-1"), batches.last())

        // Owner sign-out / account switch while a batch POST is in flight drops the stale result.
        var activeOwner: AuthenticatedRouteOwner? = owner
        val fencedCache = RaftFilePreviewAttachmentUrlCache()
        var fencedState = ThreadInlineImageFallbackState()
        val fencedBatcher =
            ThreadInlineAttachmentUrlBatcher(
                coroutineScope = this,
                ownerProvider = { activeOwner },
                urlCache = fencedCache,
                fetchBatch = { ids ->
                    activeOwner = null
                    ThreadApiResult.Success(
                        ids.associateWith { id -> ThreadAttachmentOpenTarget("https://r2.example/$id.png", null) }
                    )
                },
                readState = { fencedState },
                writeState = { fencedState = it },
                debounceMillis = 10L,
            )
        fencedBatcher.request("att-signed-out")
        advanceUntilIdle()
        assertNull(fencedCache.get(RaftFilePreviewAttachmentUrlCacheKey(owner, "att-signed-out", "inline")))
        assertNull(fencedState.resolvedUrls["att-signed-out"])
    }

    @Test
    fun originalAttachmentKeySurvivesUrlResignAndDiskPolicyHonorsHttpCacheHeaders() {
        val key = assertNotNull(raftAttachmentOriginalImageCacheKey("4239508c-1111-4222-8333-444444444444"))
        assertTrue(raftImageDiskKeyIsImmutable(key))
        assertEquals(key, raftAttachmentOriginalImageCacheKey("4239508c-1111-4222-8333-444444444444"))

        // Cache-Control: no-store must never persist on disk.
        assertNull(
            raftImageDiskMetadataFromHttpHeaders(
                mimeType = "image/png",
                cacheControlHeader = "private, no-store",
                eTag = "\"v1\"",
                lastModified = null,
                ageSeconds = null,
                nowEpochMillis = 1_000_000L,
            )
        )

        // Cache-Control: max-age + ETag honors freshness window and refreshes on 304 without re-download.
        val parsed = parseRaftHttpCacheControl("public, max-age=600, immutable")
        assertTrue(parsed.immutable)
        assertEquals(600L, parsed.maxAgeSeconds)

        val meta =
            assertNotNull(
                raftImageDiskMetadataFromHttpHeaders(
                    mimeType = "image/webp",
                    cacheControlHeader = "public, max-age=120",
                    eTag = "\"etag-1\"",
                    lastModified = null,
                    ageSeconds = 20L,
                    nowEpochMillis = 1_000_000L,
                )
            )
        assertTrue(raftImageDiskEntryFresh(meta, expires = true, nowEpochMillis = 1_050_000L))
        assertFalse(raftImageDiskEntryFresh(meta, expires = true, nowEpochMillis = 1_100_001L))
        assertFalse(raftImageDiskEntryFresh(meta, expires = true, nowEpochMillis = 999_999L), "backwards clock jump must revalidate")

        // Very large max-age saturates without overflowing negative.
        val hugeMeta =
            assertNotNull(
                raftImageDiskMetadataFromHttpHeaders(
                    mimeType = "image/png",
                    cacheControlHeader = "public, max-age=9223372036854775807",
                    eTag = null,
                    lastModified = null,
                    ageSeconds = 0L,
                    nowEpochMillis = 1_000_000L,
                )
            )
        assertTrue(raftImageDiskEntryFresh(hugeMeta, expires = true, nowEpochMillis = 2_000_000L))

        val refreshed =
            meta.refreshedOnNotModified(
                cacheControlHeader = "public, max-age=300",
                eTag = null,
                lastModified = null,
                ageSeconds = 0L,
                nowEpochMillis = 1_200_000L,
            )
        assertEquals("\"etag-1\"", refreshed.eTag)
        assertTrue(raftImageDiskEntryFresh(refreshed, expires = true, nowEpochMillis = 1_400_000L))

        // Cache-Control: no-cache always requires conditional revalidation.
        val noCacheMeta =
            assertNotNull(
                raftImageDiskMetadataFromHttpHeaders(
                    mimeType = "image/png",
                    cacheControlHeader = "no-cache",
                    eTag = "\"etag-2\"",
                    lastModified = null,
                    ageSeconds = null,
                    nowEpochMillis = 1_000_000L,
                )
            )
        assertFalse(raftImageDiskEntryFresh(noCacheMeta, expires = true, nowEpochMillis = 1_000_001L))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun batchFailuresExceptionsAndOmissionsMarkFailedWithRetryAndSyncPeekWorksOnFrame1() = runTest {
        val urlCache = RaftFilePreviewAttachmentUrlCache()
        var state = ThreadInlineImageFallbackState()
        var mode = "partial"
        var batchCalls = 0
        val batcher =
            ThreadInlineAttachmentUrlBatcher(
                coroutineScope = this,
                ownerProvider = { owner },
                urlCache = urlCache,
                fetchBatch = { ids ->
                    batchCalls += 1
                    when (mode) {
                        "partial" ->
                            ThreadApiResult.Success(
                                mapOf(
                                    "att-ok" to ThreadAttachmentOpenTarget("https://r2.example/att-ok.png", null),
                                    "att-blank" to ThreadAttachmentOpenTarget("   ", null),
                                )
                            )
                        "failure" -> ThreadApiResult.Failure(code = 500, contractCode = "internal")
                        "throw" -> throw IllegalStateException("network boom")
                        else ->
                            ThreadApiResult.Success(
                                ids.associateWith { id ->
                                    ThreadAttachmentOpenTarget("https://r2.example/$id-recovered.png", null)
                                }
                            )
                    }
                },
                readState = { state },
                writeState = { state = it },
                debounceMillis = 10L,
            )

        // 1. Success omitting att-missing and returning blank url for att-blank marks both failed.
        listOf("att-ok", "att-blank", "att-missing").forEach(batcher::request)
        advanceUntilIdle()
        assertEquals("https://r2.example/att-ok.png", state.resolvedUrls["att-ok"])
        assertEquals("https://r2.example/att-ok.png", batcher.peekCachedUrl("att-ok"))
        assertFalse("att-ok" in state.failedAttachmentIds)
        assertTrue("att-blank" in state.failedAttachmentIds)
        assertTrue("att-missing" in state.failedAttachmentIds)
        assertTrue(
            threadInlineImageShowsFailure(
                sourceCount = 0,
                failedSources = 0,
                awaitingRemoteFallback = false,
            ),
            "failed attachment must show failed placeholder instead of blank forever",
        )

        // 2. API Failure marks requested IDs failed.
        mode = "failure"
        batcher.request("att-fail")
        advanceUntilIdle()
        assertTrue("att-fail" in state.failedAttachmentIds)

        // 3. Non-cancellation exception marks requested IDs failed instead of hanging blank forever.
        mode = "throw"
        batcher.request("att-throw")
        advanceUntilIdle()
        assertTrue("att-throw" in state.failedAttachmentIds)

        // 4. Explicit retry clears failed state and resolves when backend recovers.
        mode = "recover"
        batcher.retry("att-throw")
        advanceUntilIdle()
        assertFalse("att-throw" in state.failedAttachmentIds)
        assertEquals("https://r2.example/att-throw-recovered.png", state.resolvedUrls["att-throw"])
        assertEquals("https://r2.example/att-throw-recovered.png", batcher.peekCachedUrl("att-throw"))
        assertEquals(4, batchCalls)
    }
}
