package build.raft.app.shared

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Share of a cached attachment (WeChat showed `<hex>...invoice.pdf` with a "?" icon): the Send
 * intent must carry a URI whose last path segment and DISPLAY_NAME are the clean attachment name,
 * the PDF MIME type, and exactly the bytes the in-app viewer reads from the private open cache.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidAttachmentShareStagingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    /**
     * FileProvider caches its path strategy per authority in a static map, but Robolectric gives
     * every test a fresh cache directory; drop the cached strategy so roots match this test's dirs.
     */
    @Before
    fun resetFileProviderPathCache() {
        val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        synchronized(field.get(null)) { (field.get(null) as MutableMap<*, *>).clear() }
    }

    private val principal = "a".repeat(64)
    private val pdfBytes =
        ("%PDF-1.7\n" + "x".repeat(4096) + "\n%%EOF\n").toByteArray(Charsets.ISO_8859_1) +
            ByteArray(2048) { index -> (index * 31).toByte() }

    /** The file the viewer opens: `cache/slock-attachments/open/<principal>-<identity hash>`. */
    private fun viewerCacheFile(): File {
        val identity = AndroidAttachmentPublicIdentity(principalKey = principal, attachmentId = "att-1")
        val dir =
            File(context.cacheDir, "$ANDROID_ATTACHMENT_PRIVATE_STAGING_DIR_NAME/$ANDROID_ATTACHMENT_PRIVATE_OPEN_DIR_NAME")
        dir.mkdirs()
        return File(dir, androidAttachmentPrivateCacheFileName(identity)).apply { writeBytes(pdfBytes) }
    }

    private fun stageShare(source: AndroidAttachmentContentUriSource, mimeType: String): File =
        stageAndroidAttachmentShareFile(
            shareRoot = androidAttachmentShareRoot(context),
            displayName = source.displayName,
            mimeType = mimeType,
            expectedSizeBytes = source.sizeBytes
        ) { checkNotNull(context.contentResolver.openInputStream(Uri.parse(source.contentUri))) }

    @Test
    fun sendIntentCarriesTheCleanNameThePdfTypeAndTheViewerBytes() {
        val cached = viewerCacheFile()
        val serverMime = "application/octet-stream"
        val privateSource =
            checkNotNull(androidAttachmentPrivateOpenSource(context, cached, "invoice.pdf", serverMime))
        val shareMime = androidAttachmentShareMimeType(privateSource.displayName, privateSource.mimeType)
        assertEquals("application/pdf", shareMime)

        val staged = stageShare(privateSource, shareMime)
        val shareSource = androidAttachmentShareSource(context, staged, shareMime)
        val intents =
            androidAttachmentOpenIntents(
                context = context,
                spec = androidAttachmentOpenLaunchSpec(shareSource, packageArchive = false, share = true),
                chooserTitle = "Share"
            )

        val target = intents.targetIntent
        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals("application/pdf", target.type)
        @Suppress("DEPRECATION")
        val stream = target.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(Uri.parse(shareSource.contentUri), stream)
        assertNull(target.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(stream, target.clipData?.getItemAt(0)?.uri)

        val chooser = intents.launchIntent
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(stream, chooser.clipData?.getItemAt(0)?.uri)

        val uri = checkNotNull(stream)
        assertEquals("invoice.pdf", uri.lastPathSegment)
        assertNull(uri.query)
        val resolver = context.contentResolver
        assertEquals("application/pdf", resolver.getType(uri))
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null).use { cursor ->
            checkNotNull(cursor)
            assertTrue(cursor.moveToFirst())
            assertEquals("invoice.pdf", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(pdfBytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val sharedBytes = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        assertArrayEquals(cached.readBytes(), sharedBytes)
        assertEquals(pdfBytes.size.toLong(), shareSource.sizeBytes)
    }

    @Test
    fun aFailedLinkFallsBackToACopy() {
        val cached = viewerCacheFile()
        val staged =
            stageAndroidAttachmentShareFile(
                shareRoot = androidAttachmentShareRoot(context),
                displayName = "invoice.pdf",
                mimeType = "application/pdf",
                expectedSizeBytes = cached.length(),
                linkFrom = cached,
                linkFile = { _, _ -> throw java.io.IOException("EXDEV") }
            ) { cached.inputStream() }
        assertArrayEquals(cached.readBytes(), staged.readBytes())
    }

    @Test
    fun eachShareGetsItsOwnEntrySoTwoSharesNeverCollide() {
        val cached = viewerCacheFile()
        val source = checkNotNull(androidAttachmentPrivateOpenSource(context, cached, "invoice.pdf", "application/pdf"))
        val first = stageShare(source, "application/pdf")
        val second = stageShare(source, "application/pdf")
        assertNotEquals(first.parentFile, second.parentFile)
        assertEquals(first.name, second.name)
        assertTrue(first.isFile && second.isFile)
    }
}
