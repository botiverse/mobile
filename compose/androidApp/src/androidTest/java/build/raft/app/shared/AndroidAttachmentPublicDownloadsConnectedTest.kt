package build.raft.app.shared

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAttachmentPublicDownloadsConnectedTest {
    @Test
    fun realMediaStoreAndAtomicFileCommitThenExternalDelete() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(ProcessAndroidAttachmentMediaStoreCleanup.awaitCompletion())
        assertTrue(ProcessAndroidAttachmentPublicDownloads.awaitRecovery())
        val resolver = context.contentResolver
        val token = UUID.randomUUID().toString().replace("-", "")
        val displayName = "raft-public-connected-$token.txt"
        val mimeType = "text/plain"
        val bytes = "public canonical attachment $token".toByteArray()
        val indexFile = File(context.cacheDir, "attachment-public-connected-$token.json")
        val backend = AndroidAttachmentPublicMediaStoreBackend(resolver)
        val authority =
            AndroidAttachmentPublicDownloadsAuthority(
                AndroidAttachmentPublicAtomicFileRecordStore(indexFile),
                backend
            )
        val identity = AndroidAttachmentPublicIdentity("a".repeat(64), "connected-$token")
        val transaction =
            AndroidAttachmentPublicDownloadTransaction(
                authority,
                identity,
                displayName,
                mimeType,
                backend::deleteExact
            )
        var uri: Uri? = null
        try {
            uri =
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
            assertTrue(uri != null)
            assertTrue(transaction.attach(checkNotNull(uri).toString()))
            resolver.openOutputStream(checkNotNull(uri), "w")!!.use { output -> output.write(bytes) }
            assertTrue(
                resolver.update(
                    checkNotNull(uri),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                ) > 0
            )
            assertTrue(transaction.verifyPublished(checkNotNull(uri).toString(), bytes.size.toLong()))
            assertTrue(transaction.commitVerified())

            val restarted =
                AndroidAttachmentPublicDownloadsAuthority(
                    AndroidAttachmentPublicAtomicFileRecordStore(indexFile),
                    backend
                )
            val committed = restarted.resolve(identity)
            assertEquals(uri.toString(), committed?.uri)
            assertEquals(bytes.size.toLong(), committed?.sizeBytes)
            val stored = resolver.openInputStream(checkNotNull(uri))!!.use { it.readBytes() }
            assertArrayEquals(bytes, stored)

            assertTrue(resolver.delete(checkNotNull(uri), null, null) > 0)
            assertNull(restarted.resolve(identity))
            uri = null
        } finally {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            indexFile.delete()
            File(indexFile.path + ".bak").delete()
            File(indexFile.path + ".new").delete()
        }
    }
}
