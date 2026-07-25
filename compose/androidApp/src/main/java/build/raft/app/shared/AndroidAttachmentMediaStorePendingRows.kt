package build.raft.app.shared

import android.content.ContentResolver
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val ANDROID_ATTACHMENT_PENDING_NAME_PREFIX = ".raft-pending-"
internal val ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/Raft"
internal val ANDROID_ATTACHMENT_IMAGES_RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/Raft"

internal fun pendingAttachmentMediaStoreDisplayName(requestToken: String, displayName: String): String =
    "$ANDROID_ATTACHMENT_PENDING_NAME_PREFIX$requestToken-$displayName"

internal fun isAppOwnedPendingAttachmentName(displayName: String): Boolean =
    displayName.startsWith(ANDROID_ATTACHMENT_PENDING_NAME_PREFIX)

/** Removes only app-tokenized Raft rows that are still pending. Published rows are never selected. */
internal object ProcessAndroidAttachmentMediaStoreCleanup {
    private val started = AtomicBoolean(false)
    private val completed = CountDownLatch(1)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "attachment-mediastore-cleanup").apply { isDaemon = true }
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val resolver = context.applicationContext.contentResolver
        worker.execute {
            try {
                cleanupPendingRows(resolver)
            } finally {
                completed.countDown()
            }
        }
    }

    fun awaitCompletion(timeoutMs: Long = 10_000L): Boolean =
        completed.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun cleanupPendingRows(resolver: ContentResolver) {
        cleanupPendingRows(
            resolver = resolver,
            collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            relativePath = ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH
        )
        cleanupPendingRows(
            resolver = resolver,
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativePath = ANDROID_ATTACHMENT_IMAGES_RELATIVE_PATH
        )
    }

    private fun cleanupPendingRows(
        resolver: ContentResolver,
        collection: android.net.Uri,
        relativePath: String
    ) {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection =
            "${MediaStore.MediaColumns.IS_PENDING} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val rows =
            runCatching {
                resolver.query(
                    collection,
                    projection,
                    selection,
                    arrayOf(
                        "1",
                        relativePath,
                        "$relativePath/",
                        "$ANDROID_ATTACHMENT_PENDING_NAME_PREFIX%"
                    ),
                    null
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    buildList {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIndex).orEmpty()
                            if (isAppOwnedPendingAttachmentName(name)) add(cursor.getLong(idIndex))
                        }
                    }
                }.orEmpty()
            }.getOrDefault(emptyList())
        rows.forEach { id ->
            runCatching {
                resolver.delete(
                    collection,
                        "${MediaStore.MediaColumns._ID} = ? AND " +
                        "${MediaStore.MediaColumns.IS_PENDING} = ? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf(
                        id.toString(),
                        "1",
                        relativePath,
                        "$relativePath/",
                        "$ANDROID_ATTACHMENT_PENDING_NAME_PREFIX%"
                    )
                )
            }
        }
    }
}
