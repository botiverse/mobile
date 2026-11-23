package build.raft.app

import build.raft.app.data.RaftExternalShareEnvelopeHostMutationResult
import build.raft.app.data.RaftExternalShareEnvelopeHostReadResult
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
    fun concurrentPendingLaunchProbeDuringWritePendingTokenNeverPoisonsNegativeCache() {
        val root = Files.createTempDirectory("external-share-concurrent-write").toFile()
        val pendingWriteStarted = CountDownLatch(1)
        val allowPendingWriteFinish = CountDownLatch(1)
        val inbox =
            AndroidExternalShareInbox(
                root = root,
                source = FakeSource(emptyMap(), emptyMap()),
                limits = limits(),
                tokenFactory = { "concurrent-token-123456" },
                clock = { 1_000L },
                atomicWriter = { file, bytes ->
                    if (file.name == AndroidExternalShareInbox.PendingFileName) {
                        pendingWriteStarted.countDown()
                        assertTrue(allowPendingWriteFinish.await(5L, TimeUnit.SECONDS))
                    }
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    file.setLastModified(1_000L)
                },
                atomicReader = { file -> file.readText() }
            )
        val admitted = AtomicReference<AndroidExternalShareIntakeResult?>(null)
        val writerThread =
            Thread {
                admitted.set(inbox.admit(spec("concurrent-share")))
            }
        try {
            writerThread.start()
            assertTrue(pendingWriteStarted.await(5L, TimeUnit.SECONDS))

            // pending.txt is not renamed into place yet, so the probe observes null,
            // but it must NOT poison the negative cache while the write is in flight.
            assertEquals(null, inbox.pendingLaunchOrNull())
            assertFalse(inbox.isKnownNoPendingLaunch())

            allowPendingWriteFinish.countDown()
            writerThread.join(5_000L)
            assertFalse(writerThread.isAlive)

            val launch = (admitted.get() as AndroidExternalShareIntakeResult.Admitted).launch
            assertFalse(inbox.isKnownNoPendingLaunch())
            assertEquals(launch, inbox.pendingLaunchOrNull())
        } finally {
            allowPendingWriteFinish.countDown()
            writerThread.join(1_000L)
            root.deleteRecursively()
        }
    }

    @Test
    fun preselectedTargetHintRoundTripsSeparatesFingerprintAndDropsMalformedHints() = runTest {
        val root = Files.createTempDirectory("external-share-hint").toFile()
        val source = FakeSource(mapOf("content://one" to byteArrayOf(1, 2)), mapOf("content://one" to 2L))
        var nextTokenIndex = 0
        val inbox =
            AndroidExternalShareInbox(
                root = root,
                source = source,
                limits = limits(),
                tokenFactory = {
                    nextTokenIndex += 1
                    "hint-token-12345678-$nextTokenIndex"
                },
                clock = { 1_000L },
                atomicWriter = { file, bytes ->
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    file.setLastModified(1_000L)
                },
                atomicReader = { file -> file.readText() }
            )
        try {
            val dmHint = "raft_share:v1:srv-1:dm:dm-agent-1"
            val channelHint = "raft_share:v1:srv-1:channel:ch-general"

            val first =
                inbox.admit(
                    AndroidExternalShareSpec(
                        text = "same payload",
                        subject = "",
                        uris = listOf("content://one"),
                        preselectedTargetHint = dmHint
                    )
                ) as AndroidExternalShareIntakeResult.Admitted
            val firstLoaded = inbox.load(first.launch.token) as RaftExternalShareEnvelopeHostReadResult.Content
            assertEquals(dmHint, firstLoaded.record.preselectedTargetHint)

            // Same payload to the same Direct Share target within 30s deduplicates.
            val dup =
                inbox.admit(
                    AndroidExternalShareSpec(
                        text = "same payload",
                        subject = "",
                        uris = listOf("content://one"),
                        preselectedTargetHint = dmHint
                    )
                ) as AndroidExternalShareIntakeResult.Admitted
            assertEquals(first.launch.token, dup.launch.token)

            // Same payload to a DIFFERENT Direct Share target within 30s must NOT reuse the first token.
            val second =
                inbox.admit(
                    AndroidExternalShareSpec(
                        text = "same payload",
                        subject = "",
                        uris = listOf("content://one"),
                        preselectedTargetHint = channelHint
                    )
                ) as AndroidExternalShareIntakeResult.Admitted
            assertTrue(first.launch.token != second.launch.token)
            val secondLoaded = inbox.load(second.launch.token) as RaftExternalShareEnvelopeHostReadResult.Content
            assertEquals(channelHint, secondLoaded.record.preselectedTargetHint)

            // Same payload without any Direct Share hint must also NOT reuse a hinted envelope.
            val plain =
                inbox.admit(
                    AndroidExternalShareSpec(
                        text = "same payload",
                        subject = "",
                        uris = listOf("content://one"),
                        preselectedTargetHint = null
                    )
                ) as AndroidExternalShareIntakeResult.Admitted
            assertTrue(plain.launch.token != first.launch.token && plain.launch.token != second.launch.token)
            val plainLoaded = inbox.load(plain.launch.token) as RaftExternalShareEnvelopeHostReadResult.Content
            assertEquals(null, plainLoaded.record.preselectedTargetHint)

            // Malformed hint is stripped cleanly during admission.
            val malformed =
                inbox.admit(
                    AndroidExternalShareSpec(
                        text = "bad hint payload",
                        subject = "",
                        uris = emptyList(),
                        preselectedTargetHint = "not-a-raft-hint"
                    )
                ) as AndroidExternalShareIntakeResult.Admitted
            val malformedLoaded = inbox.load(malformed.launch.token) as RaftExternalShareEnvelopeHostReadResult.Content
            assertEquals(null, malformedLoaded.record.preselectedTargetHint)
        } finally {
            root.deleteRecursively()
        }
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
