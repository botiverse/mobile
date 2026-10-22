package build.raft.app.image

import build.raft.app.RaftPendingAttachmentItem
import build.raft.app.RaftThreadAttachmentItem
import build.raft.app.RaftThreadMessageItem
import build.raft.app.RaftThreadPendingSendContext
import build.raft.app.SharedThreadComposerAttachment
import build.raft.app.ThreadMessageDeliverySending
import build.raft.app.ThreadMessageDeliverySent
import build.raft.app.absorbingCanonicalSendEchoes
import build.raft.app.threadInlineImageSourcePlan
import build.raft.app.threadSentAttachmentLocalImageSources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RaftSentAttachmentLocalImagesTest {
    private class InMemoryStore : RaftSentAttachmentImageStore {
        val entries = mutableMapOf<String, RaftSentAttachmentImageBytes>()

        override suspend fun write(key: String, bytes: ByteArray, mimeType: String?): Boolean {
            entries[key] = RaftSentAttachmentImageBytes(bytes, mimeType)
            return true
        }

        override suspend fun read(key: String): RaftSentAttachmentImageBytes? = entries[key]

        override fun remove(key: String) {
            entries.remove(key)
        }
    }

    private class Harness(
        scope: CoroutineScope,
        maxEntries: Int = 64,
        maxSeedBytes: Long = 1024,
        val files: Map<String, ByteArray> = mapOf(LocalFileRef to LocalBytes),
        val readGate: CompletableDeferred<Unit>? = null,
    ) {
        val store = InMemoryStore()
        val reads = mutableListOf<String>()
        val registry =
            RaftSentAttachmentLocalImageRegistry(
                maxEntries = maxEntries,
                maxSeedBytes = maxSeedBytes,
                scope = scope,
                readLocalFile = { fileRef ->
                    readGate?.await()
                    reads += fileRef
                    files[fileRef]
                },
                storeProvider = { store },
            )
    }

    @Test
    fun canonicalEchoOfAJustSentImageResolvesToTheLocalCopyWithoutAnyDownload() = runTest {
        // Direct-upload images reach the canonical row with no thumbnail (server makes none);
        // legacy multipart ones carry a CDN thumbnail the device has never fetched.
        listOf(null, "https://cdn.example/thumbs/att-1.webp").forEach { canonicalThumbnail ->
            val harness = Harness(scope = this)
            harness.registry.register(
                threadSentAttachmentLocalImageSources(
                    attachments = listOf(uploadedImage()),
                    attachmentIds = listOf(AttachmentId)
                )
            )
            advanceUntilIdle()

            val echo = listOf(canonicalMessage(canonicalThumbnail))
            val timeline = listOf(pendingShadow()).absorbingCanonicalSendEchoes(echo) + echo
            val canonical = timeline.single { it.id == "msg-1" }
            assertTrue(timeline.none { it.id == PendingId }, "pending shadow must be retired, thumbnail=$canonicalThumbnail")
            val attachment = canonical.attachments.single()

            val remoteFallbackRequests = mutableListOf<String>()
            val plan =
                threadInlineImageSourcePlan(
                    thumbnailUrl = attachment.thumbnailUrl,
                    fallbackUrl = null,
                    localModel = harness.registry.localModel(attachment.id)
                )
            if (plan.requestRemoteFallback) remoteFallbackRequests += attachment.id

            assertEquals(raftSentAttachmentModel(AttachmentId), plan.source, "thumbnail=$canonicalThumbnail")
            assertEquals(canonicalThumbnail, plan.fallbackSource, "remote stays only as the error fallback")
            assertEquals(emptyList(), remoteFallbackRequests, "no signed inline URL request, thumbnail=$canonicalThumbnail")
            val source = raftImageSourceOf(plan.source!!)
            assertIs<RaftImageSource.SentAttachment>(source, "local model must not be loaded over the network")
            assertEquals(AttachmentId, source.attachmentId)
            val bytes = harness.registry.read(AttachmentId)
            assertContentEquals(LocalBytes, bytes?.bytes)
            assertEquals("image/jpeg", bytes?.mimeType)
            assertEquals(listOf(LocalFileRef), harness.reads, "the local file is read exactly once")
        }
    }

    @Test
    fun echoArrivingBeforeTheLocalCopyFinishesStillResolvesLocallyAndWaitsForTheBytes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val harness = Harness(scope = this, readGate = gate)
        harness.registry.register(listOf(localSource()))

        // Registration is synchronous: the canonical row resolves before any byte is copied.
        val plan = threadInlineImageSourcePlan(null, null, harness.registry.localModel(AttachmentId))
        assertEquals(raftSentAttachmentModel(AttachmentId), plan.source)
        assertFalse(plan.requestRemoteFallback)

        val read = async { harness.registry.read(AttachmentId) }
        advanceUntilIdle()
        assertFalse(read.isCompleted, "the fetch waits for the copy, state=${harness.reads}")
        gate.complete(Unit)
        advanceUntilIdle()
        assertContentEquals(LocalBytes, read.await()?.bytes)
    }

    @Test
    fun retireWaitsForTheCopySoTheOwnedTemporaryFileIsReadFirst() = runTest {
        val gate = CompletableDeferred<Unit>()
        val harness = Harness(scope = this, readGate = gate)
        harness.registry.register(listOf(localSource()))
        val wait = async { harness.registry.awaitSeeded(listOf(AttachmentId), timeoutMillis = 5_000) }
        advanceTimeBy(4_000)
        assertFalse(wait.isCompleted, "retire must not run before the copy, reads=${harness.reads}")
        gate.complete(Unit)
        runCurrent()
        assertTrue(wait.isCompleted)
        assertEquals(listOf(LocalFileRef), harness.reads)
    }

    @Test
    fun nonImageOversizedAndUnreadableSourcesKeepTheRemotePath() = runTest {
        val harness = Harness(scope = this, maxSeedBytes = 4)
        harness.registry.register(
            listOf(
                localSource(attachmentId = "video", mimeType = "video/mp4"),
                localSource(attachmentId = "huge", sizeBytes = 5),
                localSource(attachmentId = "missing", fileRef = "file:///gone.jpg"),
            )
        )
        advanceUntilIdle()
        listOf("video", "huge", "missing").forEach { id ->
            assertNull(harness.registry.localModel(id), "id=$id")
            val plan = threadInlineImageSourcePlan(null, null, harness.registry.localModel(id))
            assertNull(plan.source)
            assertTrue(plan.requestRemoteFallback, "id=$id falls back to the inline URL")
        }
        assertTrue(harness.store.entries.isEmpty())
    }

    @Test
    fun evictedCacheBytesForgetTheLocalSourceAndFallBackToRemote() = runTest {
        val harness = Harness(scope = this)
        harness.registry.register(listOf(localSource()))
        advanceUntilIdle()
        harness.store.entries.clear()

        assertNull(harness.registry.read(AttachmentId))
        assertNull(harness.registry.localModel(AttachmentId))
        assertTrue(threadInlineImageSourcePlan(null, null, harness.registry.localModel(AttachmentId)).requestRemoteFallback)
    }

    @Test
    fun registryIsBoundedAndClearedWithTheSession() = runTest {
        val files = (1..3).associate { "file:///img-$it.jpg" to byteArrayOf(it.toByte()) }
        val harness = Harness(scope = this, maxEntries = 2, files = files)
        (1..3).forEach { index ->
            harness.registry.register(listOf(localSource(attachmentId = "att-$index", fileRef = "file:///img-$index.jpg")))
            advanceUntilIdle()
        }
        assertEquals(setOf("att-2", "att-3"), harness.registry.registeredAttachmentIds.value)
        assertEquals(
            setOf(raftSentAttachmentCacheKey("att-2"), raftSentAttachmentCacheKey("att-3")),
            harness.store.entries.keys,
            "evicted id drops its bytes"
        )

        harness.registry.clear()
        assertEquals(emptySet(), harness.registry.registeredAttachmentIds.value)
        assertNull(harness.registry.localModel("att-3"))
    }

    private companion object {
        const val AttachmentId = "att-1"
        const val PendingId = "local-send-instance-1"
        const val LocalFileRef = "file:///cache/picked/photo.jpg"
        val LocalBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)

        fun localSource(
            attachmentId: String = AttachmentId,
            fileRef: String = LocalFileRef,
            mimeType: String = "image/jpeg",
            sizeBytes: Long = LocalBytes.size.toLong(),
        ) = RaftSentAttachmentLocalSource(attachmentId, fileRef, mimeType, sizeBytes)

        fun uploadedImage() =
            SharedThreadComposerAttachment(
                id = AttachmentId,
                fileName = "photo.jpg",
                sizeBytes = LocalBytes.size.toLong(),
                mimeType = "image/jpeg",
                previewUri = LocalFileRef,
                status = "ready",
                fileRef = LocalFileRef,
                source = "platform_picker"
            )

        fun pendingShadow() =
            RaftThreadMessageItem(
                id = PendingId,
                authorId = "user-1",
                authorName = "Me",
                isOwn = true,
                body = "[1 attachment]",
                randomId = "rid-1",
                attachments =
                    listOf(
                        RaftThreadAttachmentItem(
                            id = AttachmentId,
                            name = "photo.jpg",
                            mimeType = "image/jpeg",
                            thumbnailUrl = LocalFileRef
                        )
                    ),
                deliveryState = ThreadMessageDeliverySending,
                pendingSendContext =
                    RaftThreadPendingSendContext(
                        channelId = "channel-1",
                        body = "[1 attachment]",
                        attachmentIds = listOf(AttachmentId),
                        attachments =
                            listOf(
                                RaftPendingAttachmentItem(
                                    id = AttachmentId,
                                    fileName = "photo.jpg",
                                    mimeType = "image/jpeg",
                                    previewUri = LocalFileRef
                                )
                            ),
                        randomId = "rid-1"
                    )
            )

        fun canonicalMessage(thumbnailUrl: String?) =
            RaftThreadMessageItem(
                id = "msg-1",
                authorId = "user-1",
                authorName = "Me",
                isOwn = true,
                body = "[1 attachment]",
                randomId = "rid-1",
                attachments =
                    listOf(
                        RaftThreadAttachmentItem(
                            id = AttachmentId,
                            name = "photo.jpg",
                            mimeType = "image/jpeg",
                            thumbnailUrl = thumbnailUrl
                        )
                    ),
                deliveryState = ThreadMessageDeliverySent
            )
    }
}
