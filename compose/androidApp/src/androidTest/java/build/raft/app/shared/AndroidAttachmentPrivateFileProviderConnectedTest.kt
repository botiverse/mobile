package build.raft.app.shared

import android.app.Activity
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAttachmentPrivateFileProviderConnectedTest {
    @Test
    fun resolverEmptySaveAsContractPreservesMetadataAndProducesExternallyReadableBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val cacheDirectory =
            File(
                File(context.cacheDir, ANDROID_ATTACHMENT_PRIVATE_STAGING_DIR_NAME),
                ANDROID_ATTACHMENT_PRIVATE_OPEN_DIR_NAME
            )
        assertTrue(cacheDirectory.mkdirs() || cacheDirectory.isDirectory)
        val token = UUID.randomUUID().toString().replace("-", "")
        val displayName = "save-as-$token.txt"
        val bytes = "Raft Save As $token".toByteArray()
        val sourceFile = File(cacheDirectory, "save-as-source-$token")
        var destination: Uri? = null

        try {
            sourceFile.writeBytes(bytes)
            val source =
                checkNotNull(
                    androidAttachmentPrivateOpenSource(
                        context = context,
                        file = sourceFile,
                        displayName = displayName,
                        mimeType = "text/plain"
                    )
                )
            val createDocument = androidAttachmentCreateDocumentIntent(source)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, createDocument.action)
            assertTrue(createDocument.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
            assertEquals("text/plain", createDocument.type)
            assertEquals(displayName, createDocument.getStringExtra(Intent.EXTRA_TITLE))
            assertTrue(createDocument.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(createDocument.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)

            destination =
                context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/RaftTest")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                )
            val resolvedDestination = checkNotNull(destination)
            assertEquals(
                bytes.size.toLong(),
                copyAndroidAttachmentToDocument(context.contentResolver, source, resolvedDestination)
            )
            assertTrue(
                androidAttachmentDocumentMatchesSource(
                    context.contentResolver,
                    source,
                    resolvedDestination
                )
            )
            assertTrue(
                context.contentResolver.update(
                    resolvedDestination,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                ) > 0
            )
            assertEquals(displayName, queryDisplayName(context, resolvedDestination))
            assertArrayEquals(
                bytes,
                context.contentResolver.openInputStream(resolvedDestination)!!.use { it.readBytes() }
            )

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val probe =
                Intent(Intent.ACTION_VIEW).apply {
                    component =
                        ComponentName(
                            instrumentation.context.packageName,
                            AndroidAttachmentExternalReadProbeActivity::class.java.name
                        )
                    setDataAndType(resolvedDestination, "text/plain")
                    clipData = ClipData.newUri(context.contentResolver, displayName, resolvedDestination)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            val externalRead = launchProbe(context, probe)
            assertEquals(Activity.RESULT_OK, externalRead.resultCode)
            assertArrayEquals(
                bytes,
                externalRead.data?.getByteArray(AndroidAttachmentExternalReadProbeActivity.EXTRA_READ_BYTES)
            )
        } finally {
            destination?.let { context.contentResolver.delete(it, null, null) }
            sourceFile.delete()
        }
    }

    private fun launchProbe(context: Context, intent: Intent): ProbeResult {
        val completed = CountDownLatch(1)
        val resultCode = AtomicInteger(Int.MIN_VALUE)
        val resultData = AtomicReference<Bundle?>()
        val receiver =
            object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    resultCode.set(code)
                    resultData.set(data)
                    completed.countDown()
                }
            }
        val probeIntent =
            Intent(intent).apply {
                putExtra(AndroidAttachmentExternalReadProbeActivity.EXTRA_RESULT_RECEIVER, receiver)
            }

        context.startActivity(probeIntent)

        assertTrue("external attachment reader did not finish", completed.await(10L, TimeUnit.SECONDS))
        assertNotNull(resultData.get())
        return ProbeResult(resultCode.get(), resultData.get())
    }

    private data class ProbeResult(
        val resultCode: Int,
        val data: Bundle?
    )

    private fun queryDisplayName(context: Context, uri: Uri): String =
        checkNotNull(
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
}
