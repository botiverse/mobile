package build.raft.app.shared

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAttachmentPublicDownloadsConnectedTest {
    @Test
    fun realMediaStoreCollisionAndLargeZipCommitExactBytesThenExternalDelete() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val skipMediaIdle = InstrumentationRegistry.getArguments().getString("skipMediaIdle") == "true"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !skipMediaIdle) {
            // Drain MediaProvider's pending work before publishing; the CI runner additionally
            // waits for its boot scan (see scripts/ci/run-public-downloads-authority.sh).
            MediaStore.waitForIdle(context.contentResolver)
        }
        assertTrue(ProcessAndroidAttachmentMediaStoreCleanup.awaitCompletion())
        assertTrue(ProcessAndroidAttachmentPublicDownloads.awaitRecovery())
        val resolver = context.contentResolver
        val token = UUID.randomUUID().toString().replace("-", "")
        val collisionName = "raft-public-connected-$token.zip"
        val sameBytesCollisionName = "raft-public-connected-same-$token.zip"
        val requestedMimeType = "application/octet-stream"
        val existingBytes = "existing user file $token".toByteArray()
        val smallBytes = Base64.getDecoder().decode(SMALL_PROBE_ZIP_BASE64)
        val largeBytes = storedZipBytes(4 * 1024 * 1024)
        assertEquals(252, smallBytes.size)
        assertTrue(largeBytes.size > 4 * 1024 * 1024)
        assertValidZip(smallBytes)
        assertValidZip(largeBytes)
        val indexFile = File(context.cacheDir, "attachment-public-connected-$token.json")
        val stagingRoot = File(context.cacheDir, "attachment-public-connected-staging-$token").apply { mkdirs() }
        val backend = AndroidAttachmentPublicMediaStoreBackend(resolver)
        val authority =
            AndroidAttachmentPublicDownloadsAuthority(
                AndroidAttachmentPublicAtomicFileRecordStore(indexFile),
                backend
            )
        val createdUris = mutableListOf<Uri>()
        try {
            val existingUri =
                insertPublishedRow(
                    resolver = resolver,
                    token = "$token-existing",
                    displayName = collisionName,
                    mimeType = "application/zip",
                    bytes = existingBytes
                ).also(createdUris::add)
            val smallIdentity = AndroidAttachmentPublicIdentity("a".repeat(64), "connected-small-$token")
            val small =
                publishAndCommit(
                    resolver = resolver,
                    authority = authority,
                    backend = backend,
                    identity = smallIdentity,
                    token = "$token-small",
                    displayName = collisionName,
                    mimeType = requestedMimeType,
                    bytes = smallBytes,
                    stagingRoot = stagingRoot
                ).also { createdUris += it.uri }
            val sameBytesExistingUri =
                insertPublishedRow(
                    resolver = resolver,
                    token = "$token-same-existing",
                    displayName = sameBytesCollisionName,
                    mimeType = "application/zip",
                    bytes = smallBytes
                ).also(createdUris::add)
            val sameBytesIdentity = AndroidAttachmentPublicIdentity("a".repeat(64), "connected-same-bytes-$token")
            val sameBytes =
                publishAndCommit(
                    resolver = resolver,
                    authority = authority,
                    backend = backend,
                    identity = sameBytesIdentity,
                    token = "$token-same-new",
                    displayName = sameBytesCollisionName,
                    mimeType = requestedMimeType,
                    bytes = smallBytes,
                    stagingRoot = stagingRoot
                ).also { createdUris += it.uri }
            val largeIdentity = AndroidAttachmentPublicIdentity("a".repeat(64), "connected-large-$token")
            val large =
                publishAndCommit(
                    resolver = resolver,
                    authority = authority,
                    backend = backend,
                    identity = largeIdentity,
                    token = "$token-large",
                    displayName = "raft-public-connected-large-$token.zip",
                    mimeType = requestedMimeType,
                    bytes = largeBytes,
                    stagingRoot = stagingRoot
                ).also { createdUris += it.uri }

            assertNotEquals(existingUri, small.uri)
            assertNotEquals(sameBytesExistingUri, sameBytes.uri)
            assertArrayEquals(existingBytes, resolver.openInputStream(existingUri)!!.use { it.readBytes() })
            assertArrayEquals(smallBytes, resolver.openInputStream(small.uri)!!.use { it.readBytes() })
            assertArrayEquals(smallBytes, resolver.openInputStream(sameBytesExistingUri)!!.use { it.readBytes() })
            assertArrayEquals(smallBytes, resolver.openInputStream(sameBytes.uri)!!.use { it.readBytes() })
            assertArrayEquals(largeBytes, resolver.openInputStream(large.uri)!!.use { it.readBytes() })
            assertDistinctUidReadExact(small.uri, smallBytes)
            assertDistinctUidReadExact(sameBytes.uri, smallBytes)
            assertDistinctUidReadExact(large.uri, largeBytes)
            assertPublishedExact(backend, small.uri, smallBytes.size.toLong())
            assertPublishedExact(backend, sameBytes.uri, smallBytes.size.toLong())
            assertPublishedExact(backend, large.uri, largeBytes.size.toLong())
            assertOwnedByAppWhenAvailable(resolver, context.packageName, small.uri)
            assertOwnedByAppWhenAvailable(resolver, context.packageName, sameBytes.uri)
            assertOwnedByAppWhenAvailable(resolver, context.packageName, large.uri)
            assertTrue(small.record.displayName.isNotBlank())
            assertTrue(small.record.mimeType.isNotBlank())
            assertTrue(sameBytes.record.displayName.isNotBlank())
            assertTrue(sameBytes.record.mimeType.isNotBlank())
            assertTrue(large.record.displayName.isNotBlank())
            assertTrue(large.record.mimeType.isNotBlank())
            assertTrue(stagingRoot.listFiles().orEmpty().isEmpty())

            val restarted =
                AndroidAttachmentPublicDownloadsAuthority(
                    AndroidAttachmentPublicAtomicFileRecordStore(indexFile),
                    backend
                )
            assertEquals(small.uri.toString(), restarted.resolve(smallIdentity)?.uri)
            assertEquals(sameBytes.uri.toString(), restarted.resolve(sameBytesIdentity)?.uri)
            assertEquals(large.uri.toString(), restarted.resolve(largeIdentity)?.uri)
            assertEquals(3, AndroidAttachmentPublicAtomicFileRecordStore(indexFile).read().records.size)

            assertTrue(resolver.delete(small.uri, null, null) > 0)
            createdUris.remove(small.uri)
            assertNull(restarted.resolve(smallIdentity))
            assertEquals(sameBytes.uri.toString(), restarted.resolve(sameBytesIdentity)?.uri)
            assertEquals(large.uri.toString(), restarted.resolve(largeIdentity)?.uri)
            assertArrayEquals(existingBytes, resolver.openInputStream(existingUri)!!.use { it.readBytes() })
        } finally {
            createdUris.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
            indexFile.delete()
            File(indexFile.path + ".bak").delete()
            File(indexFile.path + ".new").delete()
            stagingRoot.deleteRecursively()
        }
    }

    private fun publishAndCommit(
        resolver: ContentResolver,
        authority: AndroidAttachmentPublicDownloadsAuthority,
        backend: AndroidAttachmentPublicMediaStoreBackend,
        identity: AndroidAttachmentPublicIdentity,
        token: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        stagingRoot: File
    ): CommittedScenario {
        val transaction =
            AndroidAttachmentPublicDownloadTransaction(
                authority,
                identity,
                displayName,
                mimeType,
                backend::deleteExact
            )
        val uri =
            checkNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            pendingAttachmentMediaStoreDisplayName(token, displayName)
                        )
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                )
            )
        assertTrue(transaction.attach(uri.toString()))
        resolver.openOutputStream(uri, "w")!!.use { output -> output.write(bytes) }
        assertTrue(
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null
            ) > 0
        )
        assertPublishedExact(backend, uri, bytes.size.toLong())
        assertTrue(transaction.verifyPublished(uri.toString(), bytes.size.toLong()))
        val privateTarget = stagingRoot.resolve("$token-$displayName").apply { writeBytes(bytes) }
        val privatePart = stagingRoot.resolve("${privateTarget.name}.part-$token").apply { writeText("partial") }
        val finalized =
            finalizePublishedAttachmentDownload(
                transaction = transaction,
                result =
                    AttachmentDownloadsExportResult(
                        status = "saved",
                        message = "Saved to Downloads",
                        uri = uri.toString(),
                        fileName = displayName,
                        sizeBytes = bytes.size.toLong()
                    ),
                deletePrivateStaging = {
                    deleteDirectAttachmentCache(privateTarget, expectedAttemptToken = null)
                }
            )
        assertEquals("saved", finalized.status)
        assertTrue(
            finalized.storageDiagnosticCode == "commit_ok" ||
                finalized.storageDiagnosticCode == "commit_presentation_normalized"
        )
        assertTrue(finalized.storageVerificationAttempts > 0)
        assertTrue(finalized.storageCommitAttempts > 0)
        assertFalse(privateTarget.exists())
        assertFalse(privatePart.exists())
        val record = checkNotNull(authority.resolve(identity))
        assertEquals(uri.toString(), record.uri)
        assertEquals(record.displayName, finalized.fileName)
        return CommittedScenario(uri, record)
    }

    private fun insertPublishedRow(
        resolver: ContentResolver,
        token: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray
    ): Uri {
        val uri =
            checkNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            pendingAttachmentMediaStoreDisplayName(token, displayName)
                        )
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                )
            )
        resolver.openOutputStream(uri, "w")!!.use { output -> output.write(bytes) }
        assertTrue(
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null
            ) > 0
        )
        return uri
    }

    private fun assertPublishedExact(
        backend: AndroidAttachmentPublicMediaStoreBackend,
        uri: Uri,
        expectedSize: Long
    ) {
        val state = backend.inspectExact(uri.toString())
        assertEquals(AndroidAttachmentPublicUriAvailability.Published, state.availability)
        assertEquals(expectedSize, state.sizeBytes)
        assertEquals(
            ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH.trimEnd('/'),
            state.relativePath.trimEnd('/')
        )
        assertTrue(state.displayName.isNotBlank())
    }

    private fun assertOwnedByAppWhenAvailable(
        resolver: ContentResolver,
        packageName: String,
        uri: Uri
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
            null,
            null,
            null
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val ownerIndex = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            assertTrue(ownerIndex >= 0)
            assertEquals(packageName, cursor.getString(ownerIndex))
        }
    }

    private fun assertDistinctUidReadExact(uri: Uri, expected: ByteArray) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val externalContext = instrumentation.context
        assertNotEquals(targetContext.packageName, externalContext.packageName)
        val resultCode = AtomicInteger(Int.MIN_VALUE)
        val resultData = AtomicReference<Bundle?>()
        val completed = CountDownLatch(1)
        val receiver =
            object : ResultReceiver(null) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    resultCode.set(code)
                    resultData.set(data)
                    completed.countDown()
                }
            }
        targetContext.grantUriPermission(
            externalContext.packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        try {
            targetContext.startActivity(
                Intent().apply {
                    component =
                        ComponentName(
                            externalContext.packageName,
                            AndroidAttachmentPublicDownloadsExternalReadProbeActivity::class.java.name
                        )
                    setDataAndType(uri, "application/zip")
                    clipData = ClipData.newRawUri("public-download", uri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(AndroidAttachmentPublicDownloadsExternalReadProbeActivity.EXTRA_RECEIVER, receiver)
                }
            )
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            val data = checkNotNull(resultData.get())
            assertEquals(
                "external reader failed: " +
                    data.getString(AndroidAttachmentPublicDownloadsExternalReadProbeActivity.RESULT_ERROR) +
                    " message=" +
                    data.getString(AndroidAttachmentPublicDownloadsExternalReadProbeActivity.RESULT_ERROR_MESSAGE) +
                    " uri=" + uri,
                Activity.RESULT_OK,
                resultCode.get()
            )
            assertNotEquals(
                targetContext.applicationInfo.uid,
                data.getInt(AndroidAttachmentPublicDownloadsExternalReadProbeActivity.RESULT_UID)
            )
            assertEquals(
                expected.size.toLong(),
                data.getLong(AndroidAttachmentPublicDownloadsExternalReadProbeActivity.RESULT_SIZE)
            )
            assertEquals(
                sha256(expected),
                data.getString(AndroidAttachmentPublicDownloadsExternalReadProbeActivity.RESULT_SHA256)
            )
        } finally {
            targetContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun storedZipBytes(payloadSize: Int): ByteArray {
        val payload = ByteArray(payloadSize) { index -> ((index * 17 + 11) and 0xff).toByte() }
        val crc = CRC32().apply { update(payload) }
        return ByteArrayOutputStream(payloadSize + 256).use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(
                    ZipEntry("representative-payload.bin").apply {
                        method = ZipEntry.STORED
                        size = payload.size.toLong()
                        compressedSize = payload.size.toLong()
                        this.crc = crc.value
                        time = 0L
                    }
                )
                zip.write(payload)
                zip.closeEntry()
            }
            bytes.toByteArray()
        }
    }

    private fun assertValidZip(bytes: ByteArray) {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            assertTrue(zip.nextEntry != null)
            assertTrue(zip.readBytes().isNotEmpty())
            zip.closeEntry()
            assertNull(zip.nextEntry)
        }
    }

    private data class CommittedScenario(
        val uri: Uri,
        val record: AndroidAttachmentPublicRecord
    )

    private companion object {
        const val SMALL_PROBE_ZIP_BASE64 =
            "UEsDBAoAAAAAALq4+1yIcOUEJAAAACQAAAAhABwAdGFzazgyNC1odWF3ZWktb3Blbi13aXRoLXBhc3MudHh0" +
                "VVQJAAPPc2dqz3NnanV4CwABBPUBAAAEFAAAAFJBRlRfVEFTS184MjRfSFVBV0VJX09QRU5fV0lUSF9QQVNT" +
                "ClBLAQIeAwoAAAAAALq4+1yIcOUEJAAAACQAAAAhABgAAAAAAAEAAACkgQAAAAB0YXNrODI0LWh1YXdlaS1v" +
                "cGVuLXdpdGgtcGFzcy50eHRVVAUAA89zZ2p1eAsAAQT1AQAABBQAAABQSwUGAAAAAAEAAQBnAAAAfwAAAAAA"
    }
}
