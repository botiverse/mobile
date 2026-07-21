package build.raft.app.shared

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal const val ANDROID_ATTACHMENT_PENDING_NAME_PREFIX = ".raft-pending-"
internal val ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/Raft"

internal fun pendingAttachmentMediaStoreDisplayName(requestToken: String, displayName: String): String =
    "$ANDROID_ATTACHMENT_PENDING_NAME_PREFIX$requestToken-$displayName"

internal fun isAppOwnedPendingAttachmentName(displayName: String): Boolean =
    displayName.startsWith(ANDROID_ATTACHMENT_PENDING_NAME_PREFIX)

/** Removes only app-tokenized Raft rows that are still pending. Published rows are never selected. */
internal object ProcessAndroidAttachmentMediaStoreCleanup {
    private val started = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "attachment-mediastore-cleanup").apply { isDaemon = true }
    }

    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !started.compareAndSet(false, true)) return
        val resolver = context.applicationContext.contentResolver
        worker.execute { cleanupPendingRows(resolver) }
    }

    private fun cleanupPendingRows(resolver: ContentResolver) {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection =
            "${MediaStore.MediaColumns.IS_PENDING} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val rows =
            runCatching {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    arrayOf(
                        "1",
                        ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH,
                        "$ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH/",
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
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns._ID} = ? AND " +
                        "${MediaStore.MediaColumns.IS_PENDING} = ? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf(
                        id.toString(),
                        "1",
                        ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH,
                        "$ANDROID_ATTACHMENT_DOWNLOADS_RELATIVE_PATH/",
                        "$ANDROID_ATTACHMENT_PENDING_NAME_PREFIX%"
                    )
                )
            }
        }
    }
}
