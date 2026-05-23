package ai.slock.android.visual

import android.os.Environment
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
        publicOutputDir()
            .listFiles()
            ?.filter { file -> file.name.startsWith(caseId) }
            ?.forEach { file -> file.delete() }
    }

    private fun runShell(command: String): String {
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return descriptor.use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }
}
