package ai.slock.android.visual

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.IOException

internal object VisualAndroidArtifacts {
    private const val PublicOutputPath = "/sdcard/Download/slock-visual-testing/android"

    fun resetPublicOutput() {
        runShell("rm -rf $PublicOutputPath && mkdir -p $PublicOutputPath")
    }

    fun ensurePublicOutput() {
        runShell("mkdir -p $PublicOutputPath")
    }

    fun publicOutputDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "slock-visual-testing/android"
        )

    fun mirrorFile(file: File) {
        runShell("mkdir -p $PublicOutputPath && rm -f $PublicOutputPath/${file.name}")
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

    fun deleteCaseArtifacts(caseId: String) {
        runShell("rm -f $PublicOutputPath/${caseId}*")
        publicOutputDir()
            .listFiles()
            ?.filter { file -> file.name.startsWith(caseId) }
            ?.forEach { file -> file.delete() }
    }

    private fun mirrorWithMediaStore(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
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

    private fun runShell(command: String): String {
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return descriptor.use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }
}
