package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resource-qualifier styles replace the base style definition instead of
 * merging with it. Task #543: the values-v31 variant of Theme.Slock.Starting
 * dropped android:windowBackground, so Android 12+ exposed a white window
 * behind the splash exit while older API levels stayed cream. Every
 * load-bearing splash item must therefore be present in BOTH variants.
 */
class StartingThemeSplashContractTest {

    @Test
    fun baseStartingThemeKeepsSplashContinuityItems() {
        val style = startingStyle(sourceFile("androidApp/src/main/res/values/themes.xml"))
        assertContainsCreamItem(style, "android:windowBackground", "values")
        assertContainsCreamItem(style, "windowSplashScreenBackground", "values")
        assertTrue(
            "values variant must keep postSplashScreenTheme",
            style.contains("postSplashScreenTheme")
        )
    }

    @Test
    fun v31StartingThemeKeepsSplashContinuityItems() {
        val style = startingStyle(sourceFile("androidApp/src/main/res/values-v31/themes.xml"))
        assertContainsCreamItem(style, "android:windowBackground", "values-v31")
        assertContainsCreamItem(style, "android:windowSplashScreenBackground", "values-v31")
        assertTrue(
            "values-v31 variant must keep postSplashScreenTheme",
            style.contains("postSplashScreenTheme")
        )
    }

    private fun assertContainsCreamItem(style: String, itemName: String, variant: String) {
        assertTrue(
            "$variant Theme.Slock.Starting must set $itemName to slock_surface_cream; " +
                "qualifier styles replace the base style, they do not merge (task #543)",
            Regex(
                "<item name=\"" + Regex.escape(itemName) +
                    "\">@color/slock_surface_cream</item>"
            ).containsMatchIn(style)
        )
    }

    private fun startingStyle(file: File): String {
        val source = file.readText().replace("\r\n", "\n")
        val start = source.indexOf("<style name=\"Theme.Slock.Starting\"")
        assertTrue("Theme.Slock.Starting style missing in ${file.path}", start >= 0)
        val end = source.indexOf("</style>", start)
        assertTrue("Theme.Slock.Starting style unterminated in ${file.path}", end > start)
        return source.substring(start, end)
    }

    private fun sourceFile(relativePath: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        repeat(8) {
            File(current, relativePath).takeIf(File::isFile)?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
