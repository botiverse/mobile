package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellRestoreLandingVisualContractTest {
    @Test
    fun restoreLandingReusesTheServerSwitcherVectorChrome() {
        val homeComponents =
            sourceFile("shared/src/commonMain/kotlin/build/raft/app/SlockHomeComponents.kt").readText()
        val settingsHome =
            sourceFile("shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt").readText()
        val landing =
            settingsHome
                .substringAfter("internal fun AppShellRestoreSplashContent()")
                .substringBefore("private fun PageErrorContent(")
        val sharedSurface =
            homeComponents
                .substringAfter("internal fun SlockTiltedBadgeSurface(")
                .substringBefore("private fun TiltedBadgeChrome(")

        assertTrue(landing.contains("AppShellRestoreLogoBadge(\"RAFT\")"))
        assertTrue(landing.contains("SlockTiltedBadgeSurface("))
        assertFalse(landing.contains("padding(end = 4.dp, bottom = 4.dp).rotate(-2f)"))
        assertTrue(sharedSurface.contains("TiltedBadgeChrome(modifier = Modifier.matchParentSize())"))
        assertTrue(sharedSurface.contains("Box(modifier = Modifier.rotate(HomeServerBadgeTiltDegrees))"))
        assertTrue(
            sharedSurface.indexOf("TiltedBadgeChrome(modifier = Modifier.matchParentSize())") <
                sharedSurface.indexOf("Modifier.rotate(HomeServerBadgeTiltDegrees)")
        )
    }

    @Test
    fun simplifiedChineseTaglineBreaksBeforeTogetherCreate() {
        val strings = sourceFile("shared/src/commonMain/resources/MR/zh-rCN/strings.xml").readText()

        assertTrue(
            strings.contains(
                "<string name=\"auth_restore_tagline\">让人类和 AI Agent\\n一起创造。</string>"
            )
        )
    }

    private fun sourceFile(relativePath: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        repeat(8) {
            File(current, relativePath).takeIf(File::isFile)?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from $userDir")
    }
}
