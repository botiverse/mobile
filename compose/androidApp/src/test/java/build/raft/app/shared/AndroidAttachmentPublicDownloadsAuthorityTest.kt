package build.raft.app.shared

import java.io.Closeable
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentPublicDownloadsAuthorityTest {
    @Test
    fun committedExactUriSurvivesRestartAndRepeatedSaveDoesNotCreateAnotherRow() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity()
        val first = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        val committed = commit(first, backend, identity, URI)

        val restarted = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        val resolved = restarted.resolve(identity)

        assertEquals(committed, resolved)
        assertEquals(setOf(URI), backend.rows.keys)
        assertEquals(1L, resolved?.generation)
    }

    @Test
    fun externalDeletionClearsMappingAndNextTransactionUsesFreshGeneration() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity()
        val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        commit(authority, backend, identity, URI)
        backend.rows.remove(URI)

        assertNull(authority.resolve(identity))
        assertTrue(store.snapshot.records.isEmpty())

        val next = authority.begin(identity, DISPLAY_NAME, MIME_TYPE)
        assertEquals(2L, next.generation)
    }

    @Test
    fun preparedRestartDeletesExactPendingOrPublishedRowAndClearsJournal() {
        listOf(
            AndroidAttachmentPublicUriAvailability.Pending,
            AndroidAttachmentPublicUriAvailability.Published
        ).forEach { availability ->
            val store = FakeRecordStore()
            val backend = FakeUriBackend()
            val identity = identity(attachmentId = "attachment-$availability")
            val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
            val prepared = authority.begin(identity, DISPLAY_NAME, MIME_TYPE)
            assertTrue(authority.attachExactUri(prepared, URI + availability.name) != null)
            backend.rows[URI + availability.name] = state(availability)

            assertTrue(AndroidAttachmentPublicDownloadsAuthority(store, backend).recoverPrepared())

            assertFalse(backend.rows.containsKey(URI + availability.name))
            assertTrue(store.snapshot.records.isEmpty())
        }
    }

    @Test
    fun insertCopyPublishAndIndexFailuresCanAbortExactRowWithoutLeavingJournal() {
        AndroidAttachmentPublicUriAvailability.entries
            .filter { it != AndroidAttachmentPublicUriAvailability.Missing }
            .forEachIndexed { index, availability ->
                val store = FakeRecordStore()
                val backend = FakeUriBackend()
                val identity = identity(attachmentId = "failure-$index")
                val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
                val prepared = authority.begin(identity, DISPLAY_NAME, MIME_TYPE)
                val uri = "$URI-$index"
                assertTrue(authority.attachExactUri(prepared, uri) != null)
                backend.rows[uri] = state(availability)

                assertTrue(authority.abort(prepared))
                assertFalse(backend.rows.containsKey(uri))
                assertTrue(store.snapshot.records.isEmpty())
            }

        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity(attachmentId = "index-failure")
        val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        val transaction =
            AndroidAttachmentPublicDownloadTransaction(
                authority,
                identity,
                DISPLAY_NAME,
                MIME_TYPE,
                backend::deleteExact
            )
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Pending)
        assertTrue(transaction.attach(URI))
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Published)
        store.failNextWrites = 1

        assertTrue(transaction.verifyPublished(URI, SIZE_BYTES))
        val result =
            PrivateStagingFixture("journal-write-failure").use { staging ->
                finalizePublishedAttachmentDownload(
                    transaction = transaction,
                    result = savedResult(),
                    deletePrivateStaging = staging::delete
                ).also { staging.assertCleared() }
            }

        assertEquals("storage_failed", result.status)
        assertEquals("export_commit", result.failurePhase)
        assertEquals("commit_journal_write_failed_rollback_ok", result.storageDiagnosticCode)
        assertEquals(SIZE_BYTES, result.sizeBytes)
        assertFalse(backend.rows.containsKey(URI))
        assertTrue(transaction.abort())
        assertTrue(store.snapshot.records.isEmpty())
    }

    @Test
    fun appDataClearDoesNotInspectOrAdoptExistingPublicFileByName() {
        val backend = FakeUriBackend()
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Published)
        val authority = AndroidAttachmentPublicDownloadsAuthority(FakeRecordStore(), backend)

        assertNull(authority.resolve(identity()))
        assertEquals(0, backend.inspectCount)
        assertTrue(backend.rows.containsKey(URI))
    }

    @Test
    fun identityMutexLetsConcurrentClicksPublishOnlyOnce() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity()
        val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val publications = Collections.synchronizedList(mutableListOf<String>())
        val first = thread {
            authority.withIdentityLock(identity) {
                assertNull(authority.resolve(identity))
                entered.countDown()
                release.await()
                commit(authority, backend, identity, URI)
                publications += URI
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val second = thread {
            authority.withIdentityLock(identity) {
                if (authority.resolve(identity) == null) {
                    publications += "$URI-duplicate"
                }
            }
        }
        release.countDown()
        first.join(2_000L)
        second.join(2_000L)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(listOf(URI), publications)
        assertEquals(setOf(URI), backend.rows.keys)
    }

    private fun commit(
        authority: AndroidAttachmentPublicDownloadsAuthority,
        backend: FakeUriBackend,
        identity: AndroidAttachmentPublicIdentity,
        uri: String
    ): AndroidAttachmentPublicRecord {
        val prepared = authority.begin(identity, DISPLAY_NAME, MIME_TYPE)
        backend.rows[uri] = state(AndroidAttachmentPublicUriAvailability.Pending)
        val attached = checkNotNull(authority.attachExactUri(prepared, uri))
        backend.rows[uri] = state(AndroidAttachmentPublicUriAvailability.Published)
        val verified = authority.verifyPublished(attached, uri, SIZE_BYTES)
        assertTrue(verified is AndroidAttachmentPublicVerificationResult.Verified)
        val committed =
            authority.commit(
                attached,
                uri,
                SIZE_BYTES,
                (verified as AndroidAttachmentPublicVerificationResult.Verified).state
            )
        assertTrue(committed is AndroidAttachmentPublicCommitResult.Committed)
        return (committed as AndroidAttachmentPublicCommitResult.Committed).record
    }

    private fun transaction(
        authority: AndroidAttachmentPublicDownloadsAuthority,
        backend: FakeUriBackend,
        identity: AndroidAttachmentPublicIdentity
    ) = AndroidAttachmentPublicDownloadTransaction(
        authority,
        identity,
        DISPLAY_NAME,
        MIME_TYPE,
        backend::deleteExact
    )

    private fun identity(
        principal: String = "a".repeat(64),
        attachmentId: String = "attachment"
    ) = AndroidAttachmentPublicIdentity(principal, attachmentId)

    private fun state(availability: AndroidAttachmentPublicUriAvailability) =
        AndroidAttachmentPublicUriState(
            availability = availability,
            displayName = DISPLAY_NAME,
            mimeType = MIME_TYPE,
            sizeBytes = SIZE_BYTES,
            relativePath = ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH
        )

    private fun savedResult() =
        AttachmentDownloadsExportResult(
            status = "saved",
            message = "Saved to Downloads",
            uri = URI,
            fileName = DISPLAY_NAME,
            sizeBytes = SIZE_BYTES
        )

    private class FakeRecordStore : AndroidAttachmentPublicRecordStore {
        var snapshot = AndroidAttachmentPublicRecordsSnapshot()
        var failNextWrites = 0

        override fun read(): AndroidAttachmentPublicRecordsSnapshot = snapshot

        override fun write(snapshot: AndroidAttachmentPublicRecordsSnapshot): Boolean {
            if (failNextWrites > 0) {
                failNextWrites -= 1
                return false
            }
            this.snapshot = snapshot
            return true
        }
    }

    private class PrivateStagingFixture(prefix: String) : Closeable {
        private val directory = Files.createTempDirectory("public-attachment-$prefix").toFile()
        private val target = directory.resolve(DISPLAY_NAME).apply { writeText("private final") }
        private val part = directory.resolve("$DISPLAY_NAME.part-attempt").apply { writeText("private part") }

        fun delete(): String = deleteDirectAttachmentCache(target, expectedAttemptToken = null)

        fun assertCleared() {
            assertFalse(target.exists())
            assertFalse(part.exists())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }

        override fun close() {
            directory.deleteRecursively()
        }
    }

    private class FakeUriBackend : AndroidAttachmentPublicUriBackend {
        val rows = linkedMapOf<String, AndroidAttachmentPublicUriState>()
        val deletedUris = mutableListOf<String>()
        private val scriptedInspections = mutableMapOf<String, ArrayDeque<AndroidAttachmentPublicUriState>>()
        var inspectCount = 0
        var failDeletes = false

        fun scriptInspections(uri: String, vararg states: AndroidAttachmentPublicUriState) {
            scriptedInspections[uri] = ArrayDeque(states.toList())
        }

        override fun inspectExact(uri: String): AndroidAttachmentPublicUriState {
            inspectCount += 1
            scriptedInspections[uri]?.pollFirst()?.let { return it }
            return rows[uri] ?: AndroidAttachmentPublicUriState(AndroidAttachmentPublicUriAvailability.Missing)
        }

        override fun deleteExact(uri: String): Boolean {
            deletedUris += uri
            if (failDeletes) return false
            return rows.remove(uri) != null
        }
    }

    private companion object {
        const val URI = "content://downloads/public_downloads/41"
        const val HANDS_URI = "content://downloads/public_downloads/99"
        const val DISPLAY_NAME = "report.pdf"
        const val MIME_TYPE = "application/pdf"
        const val SIZE_BYTES = 4096L
    }
}
