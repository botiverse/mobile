package build.raft.app

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidExternalShareIntakeTest {
    @Test
    fun declaredOversizeRejectsBeforeOpeningAndRollsBack() = withFixture(
        files = mapOf("content://one" to ByteArray(1)),
        declaredSizes = mapOf("content://one" to 4L),
        limits = limits(maxItemBytes = 3),
    ) { fixture ->
        val result = fixture.intake.admit(spec("declared", "content://one"))

        assertRejected("external_share_item_too_large", result)
        assertEquals(0, fixture.provider.openCalls)
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun streamedOversizeRejectsAtBoundAndDeletesPartialFile() = withFixture(
        files = mapOf("content://one" to ByteArray(4)),
        declaredSizes = mapOf("content://one" to null),
        limits = limits(maxItemBytes = 3, copyBufferBytes = 2),
    ) { fixture ->
        val result = fixture.intake.admit(spec("streamed", "content://one"))

        assertRejected("external_share_item_too_large", result)
        assertEquals(1, fixture.provider.openCalls)
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun totalBoundRejectsSecondStreamAndRollsBackWholeEnvelope() = withFixture(
        files = mapOf("content://one" to ByteArray(2), "content://two" to ByteArray(2)),
        declaredSizes = mapOf("content://one" to null, "content://two" to null),
        limits = limits(maxItemBytes = 3, maxTotalBytes = 3, copyBufferBytes = 1),
    ) { fixture ->
        val result = fixture.intake.admit(spec("total", "content://one", "content://two"))

        assertRejected("external_share_total_too_large", result)
        assertEquals(2, fixture.provider.openCalls)
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun atomicWriterFailureNeverPublishesTokenAndRollsBack() {
        val root = Files.createTempDirectory("external-share-atomic").toFile()
        var retained = ""
        val intake = AndroidExternalShareIntake(
            inboxRoot = root,
            provider = FakeProvider(emptyMap(), emptyMap()),
            limits = limits(),
            tokenFactory = { "atomic-token-123456" },
            clock = { 1_000L },
            atomicEnvelopeWriter = { _, _ -> error("short_write") },
        )
        try {
            val result = intake.admit(
                spec = AndroidExternalShareSpec("atomic", "", emptyList()),
                retainToken = { retained = it },
            )

            assertRejected("external_share_copy_failed", result)
            assertEquals("", retained)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun expiredIncompleteAndPublishedDirectoriesArePrunedBeforeAdmission() = withFixture(
        files = emptyMap(),
        declaredSizes = emptyMap(),
        limits = limits(envelopeTtlMillis = 100),
        now = 1_000L,
    ) { fixture ->
        val incomplete = File(fixture.root, "incomplete").apply { mkdirs() }
        File(incomplete, "partial").writeText("partial")
        val expired = File(fixture.root, "expired").apply { mkdirs() }
        File(expired, AndroidExternalShareIntake.EnvelopeFileName).apply {
            writeText("{}")
            setLastModified(1L)
        }

        val result = fixture.intake.admit(AndroidExternalShareSpec("ttl", "", emptyList()))

        assertTrue(result is AndroidExternalShareIntakeResult.Admitted)
        assertFalse(incomplete.exists())
        assertFalse(expired.exists())
    }

    @Test
    fun liveRetainedTokenIsReusedWithoutASecondWrite() {
        val root = Files.createTempDirectory("external-share-redelivery").toFile()
        var writes = 0
        val intake = AndroidExternalShareIntake(
            inboxRoot = root,
            provider = FakeProvider(emptyMap(), emptyMap()),
            limits = limits(),
            tokenFactory = { "retained-token-123456" },
            clock = { 1_000L },
            atomicEnvelopeWriter = { file, bytes ->
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                file.setLastModified(1_000L)
                writes += 1
            },
        )
        try {
            val first = intake.admit(AndroidExternalShareSpec("redelivery", "", emptyList()))
            val second = intake.admit(
                spec = AndroidExternalShareSpec("", "", emptyList()),
                retainedToken = "retained-token-123456",
            )

            assertEquals("retained-token-123456", (first as AndroidExternalShareIntakeResult.Admitted).launch.token)
            assertEquals("retained-token-123456", (second as AndroidExternalShareIntakeResult.Admitted).launch.token)
            assertEquals(1, writes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedTextIsRejectedInsteadOfSilentlyTruncated() = withFixture(
        files = emptyMap(),
        declaredSizes = emptyMap(),
        limits = limits(),
    ) { fixture ->
        val result = fixture.intake.admit(
            AndroidExternalShareSpec("", "", emptyList(), textTooLong = true)
        )

        assertRejected("external_share_text_too_large", result)
        assertTrue(fixture.root.listFiles().orEmpty().isEmpty())
    }

    private fun spec(label: String, vararg refs: String): AndroidExternalShareSpec =
        AndroidExternalShareSpec(text = label, subject = "", uris = refs.toList())

    private fun limits(
        maxItemBytes: Long = 8,
        maxTotalBytes: Long = 16,
        copyBufferBytes: Int = 2,
        envelopeTtlMillis: Long = 1_000,
    ) = AndroidExternalShareLimits(
        maxItems = 9,
        maxItemBytes = maxItemBytes,
        maxTotalBytes = maxTotalBytes,
        copyBufferBytes = copyBufferBytes,
        envelopeTtlMillis = envelopeTtlMillis,
    )

    private fun withFixture(
        files: Map<String, ByteArray>,
        declaredSizes: Map<String, Long?>,
        limits: AndroidExternalShareLimits,
        now: Long = 1_000L,
        block: (Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("external-share-intake").toFile()
        val provider = FakeProvider(files, declaredSizes)
        val intake = AndroidExternalShareIntake(
            inboxRoot = root,
            provider = provider,
            limits = limits,
            tokenFactory = { "fixture-token-${root.name.takeLast(8)}" },
            clock = { now },
            atomicEnvelopeWriter = { file, bytes ->
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                file.setLastModified(now)
            },
        )
        try {
            block(Fixture(root, provider, intake))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertRejected(code: String, result: AndroidExternalShareIntakeResult) {
        assertEquals(code, (result as AndroidExternalShareIntakeResult.Rejected).code)
    }

    private data class Fixture(
        val root: File,
        val provider: FakeProvider,
        val intake: AndroidExternalShareIntake,
    )

    private class FakeProvider(
        private val files: Map<String, ByteArray>,
        private val declaredSizes: Map<String, Long?>,
    ) : AndroidExternalShareProvider {
        var openCalls: Int = 0

        override fun isGrantedContentRef(ref: String): Boolean = ref.startsWith("content://")

        override fun metadata(ref: String): AndroidExternalShareSourceMetadata =
            AndroidExternalShareSourceMetadata(
                displayName = ref.substringAfterLast('/'),
                mimeType = "application/octet-stream",
                declaredSize = declaredSizes[ref],
            )

        override fun open(ref: String): InputStream? {
            openCalls += 1
            return files[ref]?.let(::ByteArrayInputStream)
        }
    }
}
