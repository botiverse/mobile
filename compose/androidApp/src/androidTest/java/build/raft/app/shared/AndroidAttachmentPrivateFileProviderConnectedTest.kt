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

    @Test
    fun identityScopedPrivateFilesWithSameDisplayNameExposeDistinctReadableUris() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(ProcessAndroidAttachmentPublicDownloads.awaitRecovery())
        val cacheDirectory =
            File(
                File(context.cacheDir, ANDROID_ATTACHMENT_PRIVATE_STAGING_DIR_NAME),
                ANDROID_ATTACHMENT_PRIVATE_OPEN_DIR_NAME
            )
        val directoryCreatedByTest = !cacheDirectory.exists()
        assertTrue(cacheDirectory.mkdirs() || cacheDirectory.isDirectory)

        val token = UUID.randomUUID().toString().replace("-", "")
        val principalKey = "a".repeat(64)
        val displayName = "same-name.txt"
        val firstAttachmentId = "attachment-one-$token"
        val secondAttachmentId = "attachment-two-$token"
        val firstIdentity = AndroidAttachmentPublicIdentity(principalKey, firstAttachmentId)
        val secondIdentity = AndroidAttachmentPublicIdentity(principalKey, secondAttachmentId)
        val firstFile = File(cacheDirectory, androidAttachmentPrivateCacheFileName(firstIdentity))
        val secondFile = File(cacheDirectory, androidAttachmentPrivateCacheFileName(secondIdentity))
        val firstBytes = "first attachment $token".toByteArray()
        val secondBytes = "second attachment $token".toByteArray()
        val outsideProviderRoot = File(context.filesDir, "outside-provider-root-$token.txt")
        var firstSource: AndroidAttachmentContentUriSource? = null

        try {
            secondFile.writeBytes(secondBytes)
            outsideProviderRoot.writeBytes("outside".toByteArray())

            assertEquals(cacheDirectory.canonicalFile, firstFile.parentFile?.canonicalFile)
            assertEquals(cacheDirectory.canonicalFile, secondFile.parentFile?.canonicalFile)
            assertTrue(secondFile.isFile && secondFile.length() == secondBytes.size.toLong())
            assertNotEquals(firstFile.name, secondFile.name)

            val coordinator =
                AndroidAttachmentOpenCoordinator<File>(
                    ownerIsCurrent = { true },
                    withIdentityLock = { requestedIdentity, block ->
                        assertEquals(firstIdentity, requestedIdentity)
                        block()
                    },
                    resolvePublicSource = { requestedIdentity ->
                        assertEquals(firstIdentity, requestedIdentity)
                        null
                    },
                    awaitPrivateRecovery = { true },
                    materializePrivate = { requestedIdentity ->
                        assertEquals(firstIdentity, requestedIdentity)
                        firstFile.writeBytes(firstBytes)
                        AndroidAttachmentPrivateMaterialization.Ready(firstFile)
                    },
                    buildPrivateSource = { file ->
                        androidAttachmentPrivateOpenSource(
                            context = context,
                            file = file,
                            displayName = displayName,
                            mimeType = "text/plain"
                        ).also { source -> firstSource = source }
                    },
                    launch = { source, privateFile ->
                        assertEquals(firstFile, privateFile)
                        val sourceUri = Uri.parse(source.contentUri)
                        assertExternalTargetCannotReadWithoutGrant(context, sourceUri)
                        launchSelectedExternalTarget(
                            targetContext = context,
                            source = source,
                            privateFile = checkNotNull(privateFile),
                            expectedBytes = firstBytes
                        )
                    },
                    failureResult = { throwable ->
                        throw AssertionError("attachment coordinator or external probe failed", throwable)
                    }
                )

            val coordinatorResult = coordinator.open(firstIdentity)

            assertEquals(AndroidAttachmentOpenStatus.Opened, coordinatorResult.status)
            assertEquals(AndroidAttachmentOpenSourceKind.PrivateCache, coordinatorResult.source)
            assertTrue(firstFile.isFile && firstFile.length() == firstBytes.size.toLong())
            val resolvedFirstSource = checkNotNull(firstSource)
            val secondSource =
                checkNotNull(
                    androidAttachmentPrivateOpenSource(
                        context = context,
                        file = secondFile,
                        displayName = displayName,
                        mimeType = "text/plain"
                    )
                )
            val firstUri = Uri.parse(resolvedFirstSource.contentUri)
            val secondUri = Uri.parse(secondSource.contentUri)
            val authority = "${context.packageName}.fileprovider"

            assertEquals("content", firstUri.scheme)
            assertEquals(authority, firstUri.authority)
            assertEquals("content", secondUri.scheme)
            assertEquals(authority, secondUri.authority)
            assertNotEquals(firstUri, secondUri)
            assertEquals(displayName, queryDisplayName(context, firstUri))
            assertEquals(displayName, queryDisplayName(context, secondUri))
            assertArrayEquals(
                firstBytes,
                context.contentResolver.openInputStream(firstUri)!!.use { it.readBytes() }
            )
            assertArrayEquals(
                secondBytes,
                context.contentResolver.openInputStream(secondUri)!!.use { it.readBytes() }
            )
            assertNull(
                androidAttachmentPrivateOpenSource(
                    context = context,
                    file = outsideProviderRoot,
                    displayName = displayName,
                    mimeType = "text/plain"
                )
            )
        } finally {
            firstFile.delete()
            secondFile.delete()
            outsideProviderRoot.delete()
            if (directoryCreatedByTest) cacheDirectory.delete()
        }
    }

    private fun assertExternalTargetCannotReadWithoutGrant(
        targetContext: Context,
        uri: Uri
    ) {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val probeClassName = AndroidAttachmentExternalReadProbeActivity::class.java.name
        val ungranted =
            Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(testContext.packageName, probeClassName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(
                    AndroidAttachmentExternalReadProbeActivity.EXTRA_UNGRANTED_URI,
                    uri.toString()
                )
            }

        val denied = launchProbe(targetContext, ungranted)

        assertEquals(Activity.RESULT_CANCELED, denied.resultCode)
        assertEquals(uri.toString(), denied.data?.getString(AndroidAttachmentExternalReadProbeActivity.EXTRA_READ_URI))
        assertEquals(
            SecurityException::class.java.simpleName,
            denied.data?.getString(AndroidAttachmentExternalReadProbeActivity.EXTRA_READ_ERROR)
        )
        assertNull(denied.data?.getByteArray(AndroidAttachmentExternalReadProbeActivity.EXTRA_READ_BYTES))
    }

    private fun launchSelectedExternalTarget(
        targetContext: Context,
        source: AndroidAttachmentContentUriSource,
        privateFile: File,
        expectedBytes: ByteArray
    ): AndroidAttachmentOpenResult {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        assertNotEquals(targetContext.applicationInfo.uid, testContext.applicationInfo.uid)
        val uri = Uri.parse(source.contentUri)
        val intents =
            androidAttachmentOpenIntents(
                context = targetContext,
                spec = androidAttachmentOpenLaunchSpec(source, packageArchive = false),
                chooserTitle = "Open with"
            )
        assertEquals(Intent.ACTION_CHOOSER, intents.launchIntent.action)
        assertTrue(intents.targetIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intents.launchIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, intents.targetIntent.clipData?.getItemAt(0)?.uri)
        assertEquals(uri, intents.launchIntent.clipData?.getItemAt(0)?.uri)

        val probeClassName = AndroidAttachmentExternalReadProbeActivity::class.java.name
        val visibleHandlers =
            targetContext.packageManager.queryIntentActivities(
                intents.targetIntent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
        assertTrue(
            "the selected-target probe must be visible through the production MIME query",
            visibleHandlers.any { handler ->
                handler.activityInfo.packageName == testContext.packageName &&
                    handler.activityInfo.name == probeClassName
            }
        )
        return AndroidAttachmentSourceLauncher(
            sourceIsReadable = {
                privateFile.isFile && privateFile.length() == source.sizeBytes
            },
            hasVisibleHandler = {
                targetContext.packageManager
                    .queryIntentActivities(
                        intents.targetIntent,
                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                    )
                    .any { handler ->
                        handler.activityInfo.packageName == testContext.packageName &&
                            handler.activityInfo.name == probeClassName
                    }
            },
            runIfOwnerCurrent = { block -> block() },
            launchSource = {
                val selectedTargetIntent =
                    Intent(intents.targetIntent).apply {
                        component = ComponentName(testContext.packageName, probeClassName)
                    }
                val granted = launchProbe(targetContext, selectedTargetIntent)
                assertEquals(Activity.RESULT_OK, granted.resultCode)
                assertEquals(
                    uri.toString(),
                    granted.data?.getString(AndroidAttachmentExternalReadProbeActivity.EXTRA_READ_URI)
                )
                assertNull(
                    granted.data?.getString(AndroidAttachmentExternalReadProbeActivity.EXTRA_READ_ERROR)
                )
                assertArrayEquals(
                    expectedBytes,
                    granted.data?.getByteArray(AndroidAttachmentExternalReadProbeActivity.EXTRA_READ_BYTES)
                )
            },
            isActivityNotFound = { throwable -> throwable is ActivityNotFoundException }
        ).launch(
            source = source,
            action = AndroidAttachmentOpenAction.Open,
            requiresVisibleHandler = true
        )
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
