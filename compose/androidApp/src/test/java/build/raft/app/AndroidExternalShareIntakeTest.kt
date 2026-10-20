package build.raft.app

import build.raft.app.data.RaftExternalShareEnvelopeHostMutationResult
import build.raft.app.data.RaftExternalShareEnvelopeHostReadResult
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidExternalShareIntakeTest {
    @Test
    fun privateEnvelopeRoundTripKeepsFilesUntilExplicitDeleteAndConsumesIdempotently() = withFixture(
        files = mapOf("content://one" to byteArrayOf(1, 2, 3)),
        declaredSizes = mapOf("content://one" to 3L)
    ) { fixture ->
        val admitted = fixture.inbox.admit(spec("hello", "content://one"))
        val token = (admitted as AndroidExternalShareIntakeResult.Admitted).launch.token

        val loaded = fixture.inbox.load(token) as RaftExternalShareEnvelopeHostReadResult.Content
        assertEquals("hello", loaded.record.text)
        assertEquals(1, loaded.record.items.size)
        assertTrue(loaded.record.items.single().localRef.startsWith("file:"))
        val localFile = File(checkNotNull(java.net.URI(loaded.record.items.single().localRef).path))
        assertTrue(localFile.isFile)
        assertEquals(3L, localFile.length())
        assertEquals(AndroidExternalShareLaunch(token), fixture.inbox.pendingLaunchOrNull())

        val consumptionId = "external-share-consume:$token"
        assertEquals(
            RaftExternalShareEnvelopeHostMutationResult.Success,
            fixture.inbox.consume(token, consumptionId)
        )
        assertEquals(
            RaftExternalShareEnvelopeHostMutationResult.Success,
            fixture.inbox.consume(token, consumptionId)
        )
        assertEquals(null, fixture.inbox.pendingLaunchOrNull())
        assertTrue(localFile.isFile)

        fixture.inbox.delete(token)
        assertFalse(localFile.exists())
    }

    @Test
    fun declaredAndStreamedBoundsRejectBeforePublishingAnything() = withFixture(
        files = mapOf("content://one" to ByteArray(4)),
        declaredSizes = mapOf("content://one" to 4L),
        limits = limits(maxItemBytes = 3)
    ) { fixture ->
        val declared = fixture.inbox.admit(spec("declared", "content://one"))
        assertRejected("external_share_item_too_large", declared)
        assertEquals(0, fixture.source.openCalls)
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun unknownSizeStreamOverflowRollsBackPartialDirectory() = withFixture(
        files = mapOf("content://one" to ByteArray(4)),
        declaredSizes = mapOf("content://one" to null),
        limits = limits(maxItemBytes = 3, copyBufferBytes = 2)
    ) { fixture ->
        val streamed = fixture.inbox.admit(spec("streamed", "content://one"))
        assertRejected("external_share_item_too_large", streamed)
        assertEquals(1, fixture.source.openCalls)
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun atomicEnvelopeFailureNeverPublishesPendingToken() {
        val root = Files.createTempDirectory("external-share-atomic").toFile()
        val inbox =
            AndroidExternalShareInbox(
                root = root,
                source = FakeSource(emptyMap(), emptyMap()),
                limits = limits(),
                tokenFactory = { "atomic-token-123456" },
                clock = { 1_000L },
                atomicWriter = { _, _ -> error("short_write") },
                atomicReader = { file -> file.readText() }
            )
        try {
            val result = inbox.admit(spec("atomic"))
            assertRejected("external_share_copy_failed", result)
            assertEquals(null, inbox.pendingLaunchOrNull())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun samePayloadQueueCopiesOnceAndLatestListenerSurvivesReattach() = runTest {
        val root = Files.createTempDirectory("external-share-queue").toFile()
        val source = FakeSource(mapOf("content://one" to byteArrayOf(1, 2)), mapOf("content://one" to 2L))
        val inbox =
            AndroidExternalShareInbox(
                root = root,
                source = source,
                limits = limits(),
                tokenFactory = { "queue-token-123456" },
                clock = { 1_000L },
                atomicWriter = { file, bytes ->
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    file.setLastModified(1_000L)
                },
                atomicReader = { file -> file.readText() }
            )
        val published = mutableListOf<String>()
        val completed = CompletableDeferred<Unit>()
        val owner =
            AndroidExternalShareAdmissionOwner<AndroidExternalShareSpec>(backgroundScope) { input ->
                inbox.admit(input)
            }
        val staleListener: (Long, AndroidExternalShareIntakeResult) -> Unit = { _, _ -> error("stale listener") }
        owner.attach(staleListener)
        owner.detach(staleListener)
        owner.submit(spec("same", "content://one"))
        owner.submit(spec("same", "content://one"))
        owner.attach { generation, result ->
            published += (result as AndroidExternalShareIntakeResult.Admitted).launch.token
            owner.acknowledge(generation)
            completed.complete(Unit)
        }
        try {
            runCurrent()
            completed.await()
            runCurrent()

            assertEquals(1, source.openCalls)
            assertEquals(listOf("queue-token-123456"), published)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun conflictingConsumptionIdFailsClosed() = withFixture(emptyMap(), emptyMap()) { fixture ->
        val token =
            (fixture.inbox.admit(spec("text")) as AndroidExternalShareIntakeResult.Admitted).launch.token

        assertEquals(
            RaftExternalShareEnvelopeHostMutationResult.Success,
            fixture.inbox.consume(token, "external-share-consume:$token")
        )
        val conflict = fixture.inbox.consume(token, "external-share-consume:another-token")
        assertEquals(
            RaftExternalShareEnvelopeHostMutationResult.Failure(
                "external_share_consumption_identity_invalid",
                retryable = false
            ),
            conflict
        )
    }

    private fun spec(text: String, vararg refs: String): AndroidExternalShareSpec =
        AndroidExternalShareSpec(text = text, subject = "", uris = refs.toList())

    private fun limits(maxItemBytes: Long = 8, copyBufferBytes: Int = 2) =
        AndroidExternalShareLimits(
            maxItems = 9,
            maxItemBytes = maxItemBytes,
            maxTotalBytes = 16,
            copyBufferBytes = copyBufferBytes,
            envelopeTtlMillis = 1_000
        )

    private fun withFixture(
        files: Map<String, ByteArray>,
        declaredSizes: Map<String, Long?>,
        limits: AndroidExternalShareLimits = limits(),
        block: suspend (Fixture) -> Unit
    ) = runTest {
        val root = Files.createTempDirectory("external-share-inbox").toFile()
        val source = FakeSource(files, declaredSizes)
        val inbox =
            AndroidExternalShareInbox(
                root = root,
                source = source,
                limits = limits,
                tokenFactory = { "fixture-token-123456" },
                clock = { 1_000L },
                atomicWriter = { file, bytes ->
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    file.setLastModified(1_000L)
                },
                atomicReader = { file -> file.readText() }
            )
        try {
            block(Fixture(root, source, inbox))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertRejected(code: String, result: AndroidExternalShareIntakeResult) {
        assertEquals(code, (result as AndroidExternalShareIntakeResult.Rejected).code)
    }

    private data class Fixture(
        val root: File,
        val source: FakeSource,
        val inbox: AndroidExternalShareInbox
    )

    private class FakeSource(
        private val files: Map<String, ByteArray>,
        private val sizes: Map<String, Long?>
    ) : AndroidExternalShareSource {
        var openCalls = 0

        override fun isGrantedContentRef(ref: String): Boolean = ref.startsWith("content://")

        override fun metadata(ref: String): AndroidExternalShareSourceMetadata =
            AndroidExternalShareSourceMetadata(
                displayName = ref.substringAfterLast('/'),
                mimeType = "application/octet-stream",
                declaredSize = sizes[ref]
            )

        override fun open(ref: String): InputStream? {
            openCalls += 1
            return files[ref]?.let(::ByteArrayInputStream)
        }
    }
}
