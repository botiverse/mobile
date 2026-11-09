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
    fun theOpenCacheUriEndsInTheHashNameWhichIsWhatWeChatShowed() {
        val cached = viewerCacheFile()
        val source = checkNotNull(androidAttachmentPrivateOpenSource(context, cached, "invoice.pdf", "application/pdf"))
        val segment = Uri.parse(source.contentUri).lastPathSegment.orEmpty()
        assertEquals(cached.name, segment)
        assertFalse(segment.endsWith(".pdf"))
    }

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
    fun openWithViewIntentCarriesTheCleanNameAndType() {
        val cached = viewerCacheFile()
        val privateSource =
            checkNotNull(androidAttachmentPrivateOpenSource(context, cached, "发票 2026.pdf", "application/octet-stream"))
        val mime = androidAttachmentShareMimeType(privateSource.displayName, privateSource.mimeType)
        val staged = stageShare(privateSource, mime)
        val source = androidAttachmentShareSource(context, staged, mime)
        val intents =
            androidAttachmentOpenIntents(
                context = context,
                spec = androidAttachmentOpenLaunchSpec(source, packageArchive = false),
                chooserTitle = "Open with"
            )
        val target = intents.targetIntent
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals("application/pdf", target.type)
        assertEquals("发票 2026.pdf", target.data?.lastPathSegment)
        assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(target.data, intents.launchIntent.clipData?.getItemAt(0)?.uri)
        context.contentResolver.query(target.data!!, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
            checkNotNull(cursor)
            assertTrue(cursor.moveToFirst())
            assertEquals("发票 2026.pdf", cursor.getString(0))
        }
        val bytes = checkNotNull(context.contentResolver.openInputStream(target.data!!)).use { it.readBytes() }
        assertArrayEquals(cached.readBytes(), bytes)
    }

    @Test
    fun aPrivateCacheSourceIsHardLinkedInsteadOfCopied() {
        val cached = viewerCacheFile()
        var opened = false
        var linkedFrom: File? = null
        val staged =
            stageAndroidAttachmentShareFile(
                shareRoot = androidAttachmentShareRoot(context),
                displayName = "clip.mp4",
                mimeType = "video/mp4",
                expectedSizeBytes = cached.length(),
                linkFrom = cached,
                linkFile = { source, target ->
                    linkedFrom = source
                    source.copyTo(target)
                }
            ) {
                opened = true
                cached.inputStream()
            }
        assertEquals(cached, linkedFrom)
        assertFalse(opened)
        assertEquals("clip.mp4", staged.name)
        assertArrayEquals(cached.readBytes(), staged.readBytes())
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
    fun sharedIntoRaftAndPickedFilesWithAGenericTypeAreTypedFromTheirName() {
        assertEquals("application/pdf", build.raft.app.AndroidExternalShareInbox.resolveMimeType("invoice.pdf", ""))
        assertEquals(
            "application/pdf",
            build.raft.app.AndroidExternalShareInbox.resolveMimeType("invoice.pdf", "application/octet-stream")
        )
        assertEquals("image/png", build.raft.app.AndroidExternalShareInbox.resolveMimeType("invoice.pdf", "IMAGE/PNG"))
        assertEquals(
            "application/octet-stream",
            build.raft.app.AndroidExternalShareInbox.resolveMimeType("blob", "application/octet-stream")
        )
        // The picker resolves its upload type with the same rule.
        assertEquals("application/pdf", androidAttachmentShareMimeType("invoice.pdf", null))
        assertEquals("video/mp4", androidAttachmentShareMimeType("clip.mp4", "application/octet-stream"))
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

    @Test
    fun aShortCopyIsRejectedAndRemoved() {
        val root = androidAttachmentShareRoot(context)
        val failure =
            runCatching {
                stageAndroidAttachmentShareFile(root, "invoice.pdf", "application/pdf", expectedSizeBytes = 10_000L) {
                    pdfBytes.copyOf(100).inputStream()
                }
            }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun mimeTypeIsNormalisedAndGenericTypesDeferToTheExtension() {
        assertEquals("application/pdf", androidAttachmentShareMimeType("invoice.pdf", "application/PDF; charset=binary"))
        assertEquals("application/pdf", androidAttachmentShareMimeType("invoice.pdf", "binary/octet-stream"))
        assertEquals("application/pdf", androidAttachmentShareMimeType("invoice.PDF", null))
        assertEquals("text/plain", androidAttachmentShareMimeType("notes.pdf", "text/plain;charset=utf-8"))
        assertEquals("application/octet-stream", androidAttachmentShareMimeType("blob", "application/octet-stream"))
        assertEquals("application/octet-stream", androidAttachmentShareMimeType("blob", "garbage"))
    }

    @Test
    fun displayNamesAreSanitisedToOneSafeSegment() {
        assertEquals("invoice.pdf", sanitizeAndroidAttachmentShareFileName("invoice.pdf", "application/pdf"))
        assertEquals("发票 2026.pdf", sanitizeAndroidAttachmentShareFileName("发票 2026.pdf", "application/pdf"))
        assertEquals("b_c_.pdf", sanitizeAndroidAttachmentShareFileName("../a/b:c?.pdf", "application/pdf"))
        assertEquals("evil.pdf", sanitizeAndroidAttachmentShareFileName("..\\..\\evil.pdf", null))
        assertEquals("hidden.pdf", sanitizeAndroidAttachmentShareFileName("..hidden.pdf", null))
        assertEquals("attachment.pdf", sanitizeAndroidAttachmentShareFileName("  ", "application/pdf"))
        assertEquals("report.pdf", sanitizeAndroidAttachmentShareFileName("report", "application/pdf"))
        assertEquals("a_b.txt", sanitizeAndroidAttachmentShareFileName("a\u0000b.txt", null))
        val long = sanitizeAndroidAttachmentShareFileName("长".repeat(300) + ".pdf", "application/pdf")
        assertTrue(long.endsWith(".pdf"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 200)
    }

    @Test
    fun sweepRemovesEntriesOlderThanADayAndForeignChildren() {
        val root = File(context.cacheDir, "sweep-test").apply { deleteRecursively(); mkdirs() }
        val now = 10L * 24L * 60L * 60L * 1_000L
        fun entry(name: String, ageMs: Long) =
            File(root, name).apply {
                mkdirs()
                File(this, "a.pdf").writeBytes(byteArrayOf(1))
                setLastModified(now - ageMs)
            }
        val fresh = entry("00000000-0000-0000-0000-000000000001", 60_000L)
        val old = entry("00000000-0000-0000-0000-000000000002", ANDROID_ATTACHMENT_SHARE_MAX_AGE_MS + 1L)
        val foreign = entry("not-a-share-entry", 0L)
        assertTrue(sweepAndroidAttachmentShareStaging(root, nowMillis = now))
        assertTrue(fresh.exists())
        assertFalse(old.exists())
        assertFalse(foreign.exists())
    }

    @Test
    fun sweepCapsTheNumberOfEntries() {
        val root = File(context.cacheDir, "sweep-cap-test").apply { deleteRecursively(); mkdirs() }
        val now = 10L * 24L * 60L * 60L * 1_000L
        val entries =
            (0 until 5).map { index ->
                File(root, "00000000-0000-0000-0000-00000000000$index").apply {
                    mkdirs()
                    setLastModified(now - index * 1_000L)
                }
            }
        assertTrue(sweepAndroidAttachmentShareStaging(root, nowMillis = now, maxEntries = 3))
        assertEquals(listOf(true, true, true, false, false), entries.map(File::exists))
    }
}
