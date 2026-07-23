package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualArtifactResetProtocolSourceContractTest {
    @Test
    fun laterInstrumentationProcessesPreserveEarlierVisualArtifacts() {
        val root = findRepoRoot()
        val captureTest =
            File(
                root,
                "compose/androidApp/src/androidTest/java/build/raft/app/visual/" +
                    "KmpVisualScreenshotCaptureTest.kt"
            ).readText().replace("\r\n", "\n")
        val beforeClass =
            sourceWindow(
                captureTest,
                "fun clearVisualOutputDirectory()",
                "private fun confirmImmersiveEducationDismissed()"
            )

        assertTrue(beforeClass.contains("getString(VISUAL_RESET_PUBLIC_OUTPUT_ARG)"))
        assertTrue(beforeClass.contains("?.equals(\"false\", ignoreCase = true) != true"))
        assertTrue(beforeClass.contains("if (shouldResetPublicOutput)"))
        assertTrue(beforeClass.contains("VisualAndroidArtifacts.resetPublicOutput()"))
        assertTrue(beforeClass.contains("VisualAndroidArtifacts.ensurePublicOutput()"))

        val resetBranch =
            sourceWindow(
                beforeClass,
                "if (shouldResetPublicOutput)",
                "} else {"
            )
        assertTrue(resetBranch.contains("CAPTURE_TIMING_FILE"))
        assertTrue(resetBranch.contains(".delete()"))

        val preserveBranch =
            sourceWindow(
                beforeClass,
                "} else {",
                "confirmImmersiveEducationDismissed()"
            )
        assertFalse(preserveBranch.contains("resetPublicOutput"))
        assertFalse(preserveBranch.contains("CAPTURE_TIMING_FILE"))
        assertFalse(preserveBranch.contains(".delete()"))
    }

    private fun sourceWindow(source: String, startNeedle: String, endNeedle: String): String {
        val start = source.indexOf(startNeedle)
        val end = source.indexOf(endNeedle, start + startNeedle.length)
        assertTrue("Missing source window start: $startNeedle", start >= 0)
        assertTrue("Missing source window end: $endNeedle", end > start)
        return source.substring(start, end)
    }

    private fun findRepoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).canonicalFile
        repeat(8) {
            if (File(current, "compose/settings.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        assertEquals("Unable to locate repository root from $userDir", true, false)
        error("unreachable")
    }
}
