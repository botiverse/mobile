package ai.slock.android.visual

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

class VisualScreenshotCaptureTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureTokenPalette() = captureCase("tokens.palette")

    @Test
    fun captureButtonStates() = captureCase("components.buttons.states")

    @Test
    fun captureInputStates() = captureCase("components.input.states")

    @Test
    fun captureMemberRowData() = captureCase("components.member-row.data")

    private fun captureCase(caseId: String) {
        val spec = VisualScreenshotCases.require(caseId)
        composeRule.setContent {
            VisualScreenshotCase(
                spec = spec,
                modifier = Modifier.testTag(VisualCaseTag)
            )
        }
        composeRule.waitForIdle()

        val bitmap = composeRule.onNodeWithTag(VisualCaseTag).captureToImage().asAndroidBitmap()
        val outputDir = visualOutputDir()
        outputDir.mkdirs()
        val imageFile = File(outputDir, "$caseId.png")
        imageFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val metadataFile = File(outputDir, "$caseId.metadata.json")
        metadataFile.writeText(
            """
            {
              "provider": "android-connected",
              "providerType": "android-compose-connected-test",
              "caseId": "$caseId",
              "image": "${imageFile.absolutePath}",
              "viewport": { "width": ${bitmap.width}, "height": ${bitmap.height}, "density": 1 },
              "capturedAt": "${System.currentTimeMillis()}"
            }
            """.trimIndent() + "\n"
        )
        mirrorFileForAdbPull(imageFile)
        mirrorFileForAdbPull(metadataFile)
    }

    private fun visualOutputDir(): File {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.filesDir, "visual-testing/android-connected")
    }

    private fun mirrorFileForAdbPull(file: File) {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath =
                "${Environment.DIRECTORY_DOWNLOADS}/slock-visual-testing/android-connected/"
            val resolver = context.contentResolver
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(file.name, relativePath)
            )
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(file))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri =
                requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
                    "Unable to create visual screenshot artifact ${file.name}"
                }
            resolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "Unable to open visual screenshot artifact ${file.name}" }
                file.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val outputDir =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "slock-visual-testing/android-connected"
                )
            outputDir.mkdirs()
            file.copyTo(File(outputDir, file.name), overwrite = true)
        }
    }

    private fun mimeTypeFor(file: File): String =
        when (file.extension) {
            "png" -> "image/png"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }

    private companion object {
        const val VisualCaseTag = "visual-testing-case-root"
    }
}
