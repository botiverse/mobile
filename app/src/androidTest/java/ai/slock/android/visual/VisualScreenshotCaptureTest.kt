package ai.slock.android.visual

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.BeforeClass
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
        clearMirroredCaseArtifacts(caseId)
        val imageFile = File(outputDir, "$caseId.png")
        imageFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val metadataFile = File(outputDir, "$caseId.metadata.json")
        metadataFile.writeText(
            """
            {
              "provider": "android",
              "providerType": "android-compose-connected-test",
              "source": "android-native",
              "caseId": "$caseId",
              "image": "${imageFile.absolutePath}",
              "viewport": { "width": ${bitmap.width}, "height": ${bitmap.height}, "density": 1 },
              "systemBars": "not-applicable",
              "crop": {
                "mode": "compose-test-tag",
                "contract": "shell-canvas",
                "rect": { "x": 0, "y": 0, "width": ${bitmap.width}, "height": ${bitmap.height} }
              },
              "capture": {
                "method": "compose-test-tag-captureToImage",
                "systemBars": "not-applicable"
              },
              "capturedAt": "${System.currentTimeMillis()}"
            }
            """.trimIndent() + "\n"
        )
        mirrorFileForAdbPull(imageFile)
        mirrorFileForAdbPull(metadataFile)
    }

    private fun visualOutputDir(): File {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.filesDir, "visual-testing/android")
    }

    private fun mirrorFileForAdbPull(file: File) {
        VisualAndroidArtifacts.mirrorFile(file)
    }

    private fun clearMirroredCaseArtifacts(caseId: String) {
        File(visualOutputDir(), "$caseId.png").delete()
        File(visualOutputDir(), "$caseId.metadata.json").delete()
        deletePublicDownloadArtifactsWithShell(caseId)
    }

    private fun deletePublicDownloadArtifactsWithShell(caseId: String) {
        VisualAndroidArtifacts.deleteCaseArtifacts(caseId)
    }


    private companion object {
        const val VisualCaseTag = "visual-testing-case-root"

        @BeforeClass
        @JvmStatic
        fun ensureVisualOutputDirectory() {
            VisualAndroidArtifacts.ensurePublicOutput()
        }
    }
}
