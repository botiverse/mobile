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
    fun missingBackingFileDeletesOnlyTheMappedRowAndAllowsFreshGeneration() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity(attachmentId = "missing-backing")
        val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        commit(authority, backend, identity, URI)
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.BackingFileMissing)
        backend.rows[HANDS_URI] =
            state(AndroidAttachmentPublicUriAvailability.Published).copy(displayName = "same-name-user-file.pdf")

        assertNull(authority.resolve(identity))

        assertFalse(backend.rows.containsKey(URI))
        assertTrue(backend.rows.containsKey(HANDS_URI))
        assertEquals(listOf(URI), backend.deletedUris)
        assertTrue(store.snapshot.records.isEmpty())
        val next = authority.begin(identity, DISPLAY_NAME, MIME_TYPE)
        assertEquals(2L, next.generation)
        assertTrue(authority.abort(next))
    }

    @Test
    fun externalDeletionMakesNextActionPerformFreshDirectAttempt() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity()
        val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        commit(authority, backend, identity, URI)
        var directAttempts = 0

        authority.withIdentityLock(identity) {
            if (authority.resolve(identity) == null) directAttempts += 1
        }
        assertEquals(0, directAttempts)

        backend.rows.remove(URI)
        authority.withIdentityLock(identity) {
            if (authority.resolve(identity) == null) directAttempts += 1
        }
        assertEquals(1, directAttempts)
    }

    @Test
    fun unknownOrPendingCommittedInspectionRetainsExactRecordUntilSameUriIsPublishedAgain() {
        listOf(
            AndroidAttachmentPublicUriAvailability.Unknown,
            AndroidAttachmentPublicUriAvailability.Pending
        ).forEach { unavailableState ->
            val store = FakeRecordStore()
            val backend = FakeUriBackend()
            val identity = identity(attachmentId = "retained-$unavailableState")
            val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
            commit(authority, backend, identity, URI)
            backend.rows[URI] = state(unavailableState)
            var freshDirectAttempts = 0

            val failedInspection =
                runCatching {
                    authority.withIdentityLock(identity) {
                        if (authority.resolve(identity) == null) freshDirectAttempts += 1
                    }
                }

            assertTrue(failedInspection.exceptionOrNull() is AndroidAttachmentPublicStorageException)
            assertEquals(0, freshDirectAttempts)
            assertEquals(AndroidAttachmentPublicRecordPhase.Committed, store.snapshot.records.values.single().phase)
            assertTrue(backend.rows.containsKey(URI))

            backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Published)
            assertEquals(URI, authority.resolve(identity)?.uri)
            assertEquals(0, freshDirectAttempts)
            assertEquals(setOf(URI), backend.rows.keys)
        }
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
    fun privateCleanupAndExactRollbackFailureNeverExposeSavedAcrossRetry() {
        val directory = Files.createTempDirectory("public-attachment-double-cleanup-failure").toFile()
        val privateTarget = directory.resolve(DISPLAY_NAME).apply { writeText("private staging") }
        try {
            val store = FakeRecordStore()
            val backend = FakeUriBackend()
            val identity = identity(attachmentId = "double-cleanup-failure")
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
            assertTrue(transaction.verifyPublished(URI, SIZE_BYTES))
            backend.failDeletes = true

            val first =
                finalizePublishedAttachmentDownload(
                    transaction = transaction,
                    result = savedResult(),
                    deletePrivateStaging = { "failed" }
                )

            assertEquals("storage_failed", first.status)
            assertTrue(privateTarget.exists())
            assertEquals(AndroidAttachmentPublicRecordPhase.Prepared, store.snapshot.records.values.single().phase)
            assertTrue(backend.rows.containsKey(URI))

            val restarted = AndroidAttachmentPublicDownloadsAuthority(store, backend)
            var freshAttempts = 0
            val blockedRetry =
                runCatching {
                    restarted.withIdentityLock(identity) {
                        if (restarted.resolve(identity) == null) freshAttempts += 1
                    }
                }
            assertTrue(blockedRetry.exceptionOrNull() is AndroidAttachmentPublicStorageException)
            assertEquals(0, freshAttempts)
            assertEquals(AndroidAttachmentPublicRecordPhase.Prepared, store.snapshot.records.values.single().phase)

            backend.failDeletes = false
            assertNull(restarted.resolve(identity))
            assertTrue(store.snapshot.records.isEmpty())
            assertEquals("deleted", deleteDirectAttachmentCache(privateTarget, expectedAttemptToken = null))
            assertFalse(privateTarget.exists())
            assertEquals(2L, restarted.begin(identity, DISPLAY_NAME, MIME_TYPE).generation)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun staleGenerationCannotCommitOrReplaceFreshSuccessor() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity()
        val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        val stale = authority.begin(identity, DISPLAY_NAME, MIME_TYPE)
        assertTrue(authority.abort(stale))
        val fresh = authority.begin(identity, DISPLAY_NAME, MIME_TYPE)
        val freshUri = "$URI-fresh"
        val attachedFresh = checkNotNull(authority.attachExactUri(fresh, freshUri))
        backend.rows[freshUri] = state(AndroidAttachmentPublicUriAvailability.Published)

        assertNull(authority.attachExactUri(stale, "$URI-stale"))
        val staleCommit =
            authority.commit(
                stale,
                "$URI-stale",
                SIZE_BYTES,
                state(AndroidAttachmentPublicUriAvailability.Published)
            )
        assertEquals(
            AndroidAttachmentPublicCommitFailure.StalePrepared,
            (staleCommit as AndroidAttachmentPublicCommitResult.Failed).reason
        )
        val freshCommit =
            authority.commit(
                attachedFresh,
                freshUri,
                SIZE_BYTES,
                state(AndroidAttachmentPublicUriAvailability.Published)
            )
        assertTrue(freshCommit is AndroidAttachmentPublicCommitResult.Committed)
        assertEquals(freshUri, authority.resolve(identity)?.uri)
    }

    @Test
    fun sameFileNameAcrossAttachmentsAndPrincipalsNeverAliases() {
        val first = identity(principal = "a".repeat(64), attachmentId = "attachment-a")
        val second = identity(principal = "a".repeat(64), attachmentId = "attachment-b")
        val third = identity(principal = "b".repeat(64), attachmentId = "attachment-a")

        assertNotEquals(first.storageKey, second.storageKey)
        assertNotEquals(first.storageKey, third.storageKey)
        assertNotEquals(second.storageKey, third.storageKey)
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
    fun providerPresentationChangesRemainBoundToTheSameExactUri() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity()
        val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        commit(authority, backend, identity, URI)
        backend.rows[URI] =
            state(AndroidAttachmentPublicUriAvailability.Published).copy(
                displayName = "renamed-by-provider.pdf",
                mimeType = "application/octet-stream"
            )

        val resolved = checkNotNull(authority.resolve(identity))
        assertEquals(URI, resolved.uri)
        assertEquals("renamed-by-provider.pdf", resolved.displayName)
        assertEquals("application/octet-stream", resolved.mimeType)
        assertEquals(1, store.snapshot.records.size)
        assertTrue(backend.rows.containsKey(URI))
    }

    @Test
    fun normalizedNameAndMimeBetweenVerifyAndCommitAreCommittedForTheExactUri() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity(attachmentId = "normalized-between-reads")
        val authority = fastAuthority(store, backend)
        val transaction = transaction(authority, backend, identity)
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Pending)
        assertTrue(transaction.attach(URI))
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Published)
        assertTrue(transaction.verifyPublished(URI, SIZE_BYTES))
        backend.rows[URI] =
            state(AndroidAttachmentPublicUriAvailability.Published).copy(
                displayName = "report (1).pdf",
                mimeType = "application/octet-stream"
            )

        val result =
            finalizePublishedAttachmentDownload(
                transaction = transaction,
                result = savedResult(),
                deletePrivateStaging = { "deleted" }
            )

        assertEquals("saved", result.status)
        assertEquals("report (1).pdf", result.fileName)
        assertEquals("commit_presentation_normalized", result.storageDiagnosticCode)
        assertEquals(1, result.storageVerificationAttempts)
        assertEquals(1, result.storageCommitAttempts)
        val restarted = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        val resolved = checkNotNull(restarted.resolve(identity))
        assertEquals(URI, resolved.uri)
        assertEquals("report (1).pdf", resolved.displayName)
        assertEquals("application/octet-stream", resolved.mimeType)
        assertEquals(setOf(URI), backend.rows.keys)
    }

    @Test
    fun existingSameNameWithoutJournalIsNeverAdoptedAndProviderSelectedNameCommits() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        backend.rows[HANDS_URI] =
            state(AndroidAttachmentPublicUriAvailability.Published).copy(sizeBytes = SIZE_BYTES + 1L)
        val identity = identity(attachmentId = "new-exact-row")
        val authority = fastAuthority(store, backend)
        val transaction = transaction(authority, backend, identity)
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Pending)
        assertTrue(transaction.attach(URI))
        backend.rows[URI] =
            state(AndroidAttachmentPublicUriAvailability.Published).copy(
                displayName = "report (1).pdf",
                mimeType = "application/octet-stream"
            )

        assertTrue(transaction.verifyPublished(URI, SIZE_BYTES))
        val result =
            finalizePublishedAttachmentDownload(
                transaction,
                savedResult(),
                deletePrivateStaging = { "deleted" }
            )

        assertEquals("saved", result.status)
        assertEquals("report (1).pdf", result.fileName)
        assertEquals(setOf(HANDS_URI, URI), backend.rows.keys)
        assertEquals(URI, authority.resolve(identity)?.uri)
        assertTrue(backend.rows.containsKey(HANDS_URI))
    }

    @Test
    fun transientUnknownAndPendingCommitReadbacksRetryTheExactUriOnly() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity(attachmentId = "transient-readback")
        val authority = fastAuthority(store, backend)
        val transaction = transaction(authority, backend, identity)
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Pending)
        assertTrue(transaction.attach(URI))
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Published)
        assertTrue(transaction.verifyPublished(URI, SIZE_BYTES))
        backend.scriptInspections(
            URI,
            state(AndroidAttachmentPublicUriAvailability.Unknown),
            state(AndroidAttachmentPublicUriAvailability.Pending),
            state(AndroidAttachmentPublicUriAvailability.Published)
        )

        val result =
            PrivateStagingFixture("persistent-unknown").use { staging ->
                finalizePublishedAttachmentDownload(
                    transaction,
                    savedResult(),
                    deletePrivateStaging = staging::delete
                ).also { staging.assertCleared() }
            }

        assertEquals("saved", result.status)
        assertEquals("commit_ok", result.storageDiagnosticCode)
        assertEquals(1, result.storageVerificationAttempts)
        assertEquals(3, result.storageCommitAttempts)
        assertEquals(setOf(URI), backend.rows.keys)
        assertEquals(URI, authority.resolve(identity)?.uri)
    }

    @Test
    fun persistentUnknownCommitReadbackFailsClosedThenFreshGenerationCanRetry() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity(attachmentId = "persistent-unknown")
        val authority = fastAuthority(store, backend)
        val transaction = transaction(authority, backend, identity)
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Pending)
        assertTrue(transaction.attach(URI))
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Published)
        assertTrue(transaction.verifyPublished(URI, SIZE_BYTES))
        backend.scriptInspections(
            URI,
            state(AndroidAttachmentPublicUriAvailability.Unknown),
            state(AndroidAttachmentPublicUriAvailability.Unknown),
            state(AndroidAttachmentPublicUriAvailability.Unknown)
        )

        val result =
            finalizePublishedAttachmentDownload(
                transaction,
                savedResult(),
                deletePrivateStaging = { "deleted" }
            )

        assertEquals("storage_failed", result.status)
        assertEquals("export_commit", result.failurePhase)
        assertEquals("commit_row_unknown_rollback_ok", result.storageDiagnosticCode)
        assertEquals(1, result.storageVerificationAttempts)
        assertEquals(3, result.storageCommitAttempts)
        assertEquals(SIZE_BYTES, result.sizeBytes)
        assertFalse(backend.rows.containsKey(URI))
        assertTrue(store.snapshot.records.isEmpty())
        val retryUri = "$URI-retry"
        val retry = transaction(authority, backend, identity)
        backend.rows[retryUri] = state(AndroidAttachmentPublicUriAvailability.Pending)
        assertTrue(retry.attach(retryUri))
        backend.rows[retryUri] = state(AndroidAttachmentPublicUriAvailability.Published)
        assertTrue(retry.verifyPublished(retryUri, SIZE_BYTES))
        val retryResult =
            finalizePublishedAttachmentDownload(
                retry,
                savedResult().copy(uri = retryUri),
                deletePrivateStaging = { "deleted" }
            )
        assertEquals("saved", retryResult.status)
        assertEquals(2L, store.snapshot.records.values.single().generation)
        assertEquals(retryUri, authority.resolve(identity)?.uri)
    }

    @Test
    fun publishedReconciliationPreservesTheOriginalVerifyFailureAndAttempts() {
        listOf(false, true).forEachIndexed { index, failRollback ->
            val store = FakeRecordStore()
            val backend = FakeUriBackend()
            val identity = identity(attachmentId = "verify-reconciled-$index")
            val authority = fastAuthority(store, backend)
            val transaction = transaction(authority, backend, identity)
            val uri = "$URI-reconciled-$index"
            backend.rows[uri] = state(AndroidAttachmentPublicUriAvailability.Pending)
            assertTrue(transaction.attach(uri))
            backend.rows[uri] = state(AndroidAttachmentPublicUriAvailability.Published)
            backend.scriptInspections(
                uri,
                state(AndroidAttachmentPublicUriAvailability.Unknown),
                state(AndroidAttachmentPublicUriAvailability.Unknown),
                state(AndroidAttachmentPublicUriAvailability.Unknown)
            )
            assertFalse(transaction.verifyPublished(uri, SIZE_BYTES))
            backend.failDeletes = failRollback

            val result =
                finalizePublishedAttachmentDownload(
                    transaction,
                    // Models coordinator reconciliation observing that the exact row became Published.
                    savedResult().copy(uri = uri),
                    deletePrivateStaging = { "deleted" }
                )

            assertEquals("storage_failed", result.status)
            assertEquals("export_commit", result.failurePhase)
            assertEquals(
                "verify_row_unknown_rollback_${if (failRollback) "failed" else "ok"}",
                result.storageDiagnosticCode
            )
            assertEquals(3, result.storageVerificationAttempts)
            assertEquals(0, result.storageCommitAttempts)
            if (failRollback) {
                assertEquals(AndroidAttachmentPublicRecordPhase.Prepared, store.snapshot.records.values.single().phase)
                assertTrue(backend.rows.containsKey(uri))
            } else {
                assertTrue(store.snapshot.records.isEmpty())
                assertFalse(backend.rows.containsKey(uri))
            }
        }
    }

    @Test
    fun interruptedCommitRetryIsTypedAndStillCompensatesTheExactRow() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity(attachmentId = "interrupted-commit")
        var interruptRetry = false
        val authority =
            AndroidAttachmentPublicDownloadsAuthority(
                store = store,
                backend = backend,
                publicationReadbackAttempts = 3,
                publicationReadbackDelayMillis = 1L,
                waitBeforeReadbackRetry = {
                    if (interruptRetry) throw InterruptedException("test interruption")
                }
            )
        val transaction = transaction(authority, backend, identity)
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Pending)
        assertTrue(transaction.attach(URI))
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Published)
        assertTrue(transaction.verifyPublished(URI, SIZE_BYTES))
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Pending)
        interruptRetry = true

        val result =
            finalizePublishedAttachmentDownload(
                transaction,
                savedResult(),
                deletePrivateStaging = { "deleted" }
            )

        assertEquals("storage_failed", result.status)
        assertEquals("commit_readback_interrupted_rollback_ok", result.storageDiagnosticCode)
        assertEquals(1, result.storageVerificationAttempts)
        assertEquals(1, result.storageCommitAttempts)
        assertTrue(Thread.interrupted())
        assertFalse(backend.rows.containsKey(URI))
        assertTrue(store.snapshot.records.isEmpty())
    }

    @Test
    fun commitReadbackMismatchAndExternalDeleteRemainFailClosed() {
        val mutations =
            listOf(
                AndroidAttachmentPublicCommitFailure.SizeMismatch to
                    state(AndroidAttachmentPublicUriAvailability.Published).copy(sizeBytes = SIZE_BYTES + 1L),
                AndroidAttachmentPublicCommitFailure.PathMismatch to
                    state(AndroidAttachmentPublicUriAvailability.Published).copy(relativePath = "Download/Elsewhere"),
                AndroidAttachmentPublicCommitFailure.BackingFileMissing to
                    state(AndroidAttachmentPublicUriAvailability.BackingFileMissing),
                AndroidAttachmentPublicCommitFailure.RowMissing to
                    AndroidAttachmentPublicUriState(AndroidAttachmentPublicUriAvailability.Missing)
            )
        mutations.forEachIndexed { index, (expectedFailure, secondRead) ->
            val store = FakeRecordStore()
            val backend = FakeUriBackend()
            val identity = identity(attachmentId = "commit-mismatch-$index")
            val uri = "$URI-$index"
            val authority = fastAuthority(store, backend)
            val transaction = transaction(authority, backend, identity)
            backend.rows[uri] = state(AndroidAttachmentPublicUriAvailability.Pending)
            assertTrue(transaction.attach(uri))
            backend.rows[uri] = state(AndroidAttachmentPublicUriAvailability.Published)
            assertTrue(transaction.verifyPublished(uri, SIZE_BYTES))
            if (secondRead.availability == AndroidAttachmentPublicUriAvailability.Missing) {
                backend.rows.remove(uri)
            } else {
                backend.rows[uri] = secondRead
            }

            val result =
                finalizePublishedAttachmentDownload(
                    transaction,
                    savedResult().copy(uri = uri),
                    deletePrivateStaging = { "deleted" }
                )

            assertEquals("storage_failed", result.status)
            assertEquals(
                "commit_${expectedFailure.diagnosticCode}_rollback_ok",
                result.storageDiagnosticCode
            )
            assertFalse(backend.rows.containsKey(uri))
            assertTrue(store.snapshot.records.isEmpty())
            val next = authority.begin(identity, DISPLAY_NAME, MIME_TYPE)
            assertEquals(2L, next.generation)
            assertTrue(authority.abort(next))
        }
    }

    @Test
    fun commitFailureWithCompensationDeleteFailureRetainsPreparedExactUri() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity(attachmentId = "commit-rollback-failure")
        val authority = fastAuthority(store, backend)
        val transaction = transaction(authority, backend, identity)
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Pending)
        assertTrue(transaction.attach(URI))
        backend.rows[URI] = state(AndroidAttachmentPublicUriAvailability.Published)
        assertTrue(transaction.verifyPublished(URI, SIZE_BYTES))
        backend.scriptInspections(
            URI,
            state(AndroidAttachmentPublicUriAvailability.Unknown),
            state(AndroidAttachmentPublicUriAvailability.Unknown),
            state(AndroidAttachmentPublicUriAvailability.Unknown)
        )
        backend.failDeletes = true

        val result =
            PrivateStagingFixture("rollback-failure").use { staging ->
                finalizePublishedAttachmentDownload(
                    transaction,
                    savedResult(),
                    deletePrivateStaging = staging::delete
                ).also { staging.assertCleared() }
            }

        assertEquals("storage_failed", result.status)
        assertEquals("commit_row_unknown_rollback_failed", result.storageDiagnosticCode)
        assertEquals(AndroidAttachmentPublicRecordPhase.Prepared, store.snapshot.records.values.single().phase)
        assertEquals(URI, store.snapshot.records.values.single().uri)
        assertTrue(backend.rows.containsKey(URI))
        val blocked = runCatching { authority.begin(identity, DISPLAY_NAME, MIME_TYPE) }
        assertTrue(blocked.isFailure)

        backend.failDeletes = false
        val restarted = fastAuthority(store, backend)
        assertNull(restarted.resolve(identity))
        val retryUri = "$URI-after-restart"
        val retry = transaction(restarted, backend, identity)
        backend.rows[retryUri] = state(AndroidAttachmentPublicUriAvailability.Pending)
        assertTrue(retry.attach(retryUri))
        backend.rows[retryUri] = state(AndroidAttachmentPublicUriAvailability.Published)
        assertTrue(retry.verifyPublished(retryUri, SIZE_BYTES))
        val retryResult =
            finalizePublishedAttachmentDownload(
                retry,
                savedResult().copy(uri = retryUri),
                deletePrivateStaging = { "deleted" }
            )
        assertEquals("saved", retryResult.status)
        assertEquals(2L, store.snapshot.records.values.single().generation)
        assertEquals(retryUri, restarted.resolve(identity)?.uri)
    }

    @Test
    fun deleteRemovesOnlyMappedExactUriAndLeavesHandsRowUntouched() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity()
        val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        commit(authority, backend, identity, URI)
        backend.rows[HANDS_URI] = state(AndroidAttachmentPublicUriAvailability.Published).copy(displayName = "raft-update.apk")

        assertTrue(authority.delete(identity))
        assertFalse(backend.rows.containsKey(URI))
        assertTrue(backend.rows.containsKey(HANDS_URI))
        assertEquals(listOf(URI), backend.deletedUris)
    }

    @Test
    fun publishedPublicRecordRemainsAfterPrivateFinalAndPartsAreRemoved() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity()
        val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        commit(authority, backend, identity, URI)
        val directory = Files.createTempDirectory("public-attachment-finalize").toFile()
        try {
            val target = directory.resolve("report.pdf").apply { writeText("private final") }
            val part = directory.resolve("report.pdf.part-attempt").apply { writeText("private part") }

            assertEquals("deleted", deleteDirectAttachmentCache(target, expectedAttemptToken = null))

            assertFalse(target.exists())
            assertFalse(part.exists())
            assertEquals(URI, authority.resolve(identity)?.uri)
            assertTrue(backend.rows.containsKey(URI))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun processRestartClearsPrivateFinalAndPartsAfterCommittedCrashPoint() {
        val directory = Files.createTempDirectory("public-attachment-restart").toFile()
        try {
            val target = directory.resolve("report.pdf").apply { writeText("private final") }
            val part = directory.resolve("report.pdf.part-attempt").apply { writeText("private part") }

            assertTrue(recoverAndroidAttachmentPrivateStaging(directory))

            assertFalse(target.exists())
            assertFalse(part.exists())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun processRestartPreservesFreshPrivateOpenFinalAndRemovesStagingOrExpiredBytes() {
        val directory = Files.createTempDirectory("private-open-recovery").toFile()
        val nowMillis = System.currentTimeMillis()
        try {
            val openDirectory = directory.resolve(ANDROID_ATTACHMENT_PRIVATE_OPEN_DIR_NAME).apply { mkdirs() }
            val freshIdentity = identity(attachmentId = "fresh-private-open")
            val staleIdentity = identity(attachmentId = "stale-private-open")
            val fresh =
                openDirectory
                    .resolve(androidAttachmentPrivateCacheFileName(freshIdentity))
                    .apply {
                        writeText("fresh private open")
                        setLastModified(nowMillis)
                    }
            val stale =
                openDirectory
                    .resolve(androidAttachmentPrivateCacheFileName(staleIdentity))
                    .apply {
                        writeText("stale private open")
                        setLastModified(nowMillis - ANDROID_ATTACHMENT_PRIVATE_OPEN_MAX_AGE_MS - 1L)
                    }
            val interruptedPart =
                openDirectory.resolve("${fresh.name}.part-attempt").apply { writeText("partial") }
            val publicSaveStaging = directory.resolve("published-copy.txt").apply { writeText("staging") }

            assertTrue(recoverAndroidAttachmentPrivateStaging(directory, nowMillis))

            assertTrue(fresh.isFile)
            assertEquals("fresh private open", fresh.readText())
            assertFalse(stale.exists())
            assertFalse(interruptedPart.exists())
            assertFalse(publicSaveStaging.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun settingsStagingClearRemovesFinalAndPartFilesThroughFencedTargets() {
        val directory = Files.createTempDirectory("public-attachment-settings-clear").toFile()
        try {
            val first = directory.resolve("first.pdf").apply { writeText("private final") }
            val firstPart = directory.resolve("first.pdf.part-attempt").apply { writeText("private part") }
            val secondPart = directory.resolve("second.apk.part").apply { writeText("legacy part") }

            assertTrue(clearDirectAttachmentStaging(directory))

            assertFalse(first.exists())
            assertFalse(firstPart.exists())
            assertFalse(secondPart.exists())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun openAndApkInstallSpecsUseExactPublicUriWithReadGrant() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        val committed = commit(authority, backend, identity(), URI)

        val open = androidAttachmentOpenLaunchSpec(androidAttachmentPublicOpenSource(committed), packageArchive = false)
        val install =
            androidAttachmentOpenLaunchSpec(
                androidAttachmentPublicOpenSource(
                    committed.copy(mimeType = "application/vnd.android.package-archive")
                ),
                packageArchive = true
            )

        assertEquals(URI, open.source.contentUri)
        assertEquals(AndroidAttachmentOpenIntentAction.View, open.action)
        assertTrue(open.grantsReadPermission)
        assertEquals(URI, install.source.contentUri)
        assertEquals(AndroidAttachmentOpenIntentAction.InstallPackage, install.action)
        assertTrue(install.grantsReadPermission)
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

    @Test
    fun exactDeleteWaitsForInFlightOpenSectionToReleaseIdentityMutex() {
        val store = FakeRecordStore()
        val backend = FakeUriBackend()
        val identity = identity()
        val authority = AndroidAttachmentPublicDownloadsAuthority(store, backend)
        commit(authority, backend, identity, URI)
        val openResolved = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val deleteFinished = CountDownLatch(1)

        val open = thread {
            authority.withIdentityLock(identity) {
                assertEquals(URI, authority.resolve(identity)?.uri)
                openResolved.countDown()
                releaseOpen.await()
                assertTrue(backend.rows.containsKey(URI))
            }
        }
        assertTrue(openResolved.await(2, TimeUnit.SECONDS))
        val delete = thread {
            assertTrue(authority.withIdentityLock(identity) { authority.delete(identity) })
            deleteFinished.countDown()
        }

        assertFalse(deleteFinished.await(100, TimeUnit.MILLISECONDS))
        assertTrue(backend.rows.containsKey(URI))
        releaseOpen.countDown()
        open.join(2_000L)
        delete.join(2_000L)

        assertFalse(open.isAlive)
        assertFalse(delete.isAlive)
        assertTrue(deleteFinished.await(0, TimeUnit.MILLISECONDS))
        assertFalse(backend.rows.containsKey(URI))
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

    private fun fastAuthority(
        store: FakeRecordStore,
        backend: FakeUriBackend
    ) = AndroidAttachmentPublicDownloadsAuthority(
        store = store,
        backend = backend,
        publicationReadbackAttempts = 3,
        publicationReadbackDelayMillis = 0L,
        waitBeforeReadbackRetry = { _ -> }
    )

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
