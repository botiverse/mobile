package build.raft.app.visual

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException

internal object VisualAndroidArtifacts {
    fun resetPublicOutput() {
        publicOutputDir().deleteRecursively()
        publicOutputDir().mkdirs()
    }

    fun ensurePublicOutput() {
        publicOutputDir().mkdirs()
    }

    fun publicOutputDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "slock-visual-testing/android"
        )

    fun mirrorFile(file: File) {
        if (mirrorWithMediaStore(file)) {
            return
        }
        val outputDir = publicOutputDir()
        outputDir.mkdirs()
        val destination = File(outputDir, file.name)
        try {
            file.inputStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: IOException) {
            // Android 15+/16 scoped storage can deny direct Download writes for the
            // test package. The authoritative artifact remains in app-internal
            // storage and can be pulled with run-as; mirroring is best-effort.
        }
    }

    // The exact artifact filenames a single case produces. Matching on these
    // (not a `startsWith(caseId)` / `caseId%` prefix) prevents a case whose id
    // is a prefix of another — e.g. "…auth.forgot-password" vs
    // "…auth.forgot-password.sent" — from deleting the other's already-written
    // files when it runs later in the shard. (#461) Mirrors the exact set
    // enumerated by clearMirroredCaseArtifacts for internal storage.
    private fun caseArtifactNames(caseId: String): List<String> =
        listOf(
            "$caseId.png",
            "$caseId.metadata.json",
            "$caseId.failure.png",
            "$caseId.failure.json"
        )

    // Exact-name deletion for supplementary evidence files (render-fail
    // diagnostics, wg-nightly task #16). Same #461 rule as caseArtifactNames:
    // callers pass full filenames, never prefixes.
    fun deleteMirroredFiles(names: List<String>) {
        if (names.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver =
                InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val selection = names.joinToString(" OR ") { "${MediaStore.Downloads.DISPLAY_NAME} = ?" }
            deleteMatchingMediaStoreDownloads(resolver, selection, names.toTypedArray())
        }
        val nameSet = names.toSet()
        publicOutputDir()
            .listFiles()
            ?.filter { file -> file.name in nameSet }
            ?.forEach { file -> file.delete() }
    }

    fun deleteCaseArtifacts(caseId: String) {
        deleteExistingMediaStoreDownloadsForCase(caseId)
        val names = caseArtifactNames(caseId).toSet()
        publicOutputDir()
            .listFiles()
            ?.filter { file -> file.name in names }
            ?.forEach { file -> file.delete() }
    }

    private fun mirrorWithMediaStore(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        deleteExistingMediaStoreDownloads(resolver, file.name)
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/slock-visual-testing/android")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun deleteExistingMediaStoreDownloads(
        resolver: android.content.ContentResolver,
        displayName: String
    ) {
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(displayName)
        deleteMatchingMediaStoreDownloads(resolver, selection, selectionArgs)
    }

    private fun deleteExistingMediaStoreDownloadsForCase(caseId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        // Exact per-case names, not a `caseId%` prefix — a prefix LIKE would drop
        // a longer case's downloads (e.g. "…forgot-password.sent"). See
        // caseArtifactNames. (#461)
        val names = caseArtifactNames(caseId)
        val selection = names.joinToString(" OR ") { "${MediaStore.Downloads.DISPLAY_NAME} = ?" }
        val selectionArgs = names.toTypedArray()
        deleteMatchingMediaStoreDownloads(resolver, selection, selectionArgs)
    }

    private fun deleteMatchingMediaStoreDownloads(
        resolver: android.content.ContentResolver,
        selection: String,
        selectionArgs: Array<String>
    ) {
        val projection = arrayOf(MediaStore.Downloads._ID)
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                resolver.delete(uri, null, null)
            }
        }
    }
}
