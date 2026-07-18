package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellStartingThemeContractTest {
    @Test
    fun api31AppShellHoldKeepsCenteredForegroundIcon() {
        val theme = sourceFile("androidApp/src/main/res/values-v31/themes.xml").readText()
        val hold = sourceFile("androidApp/src/main/res/layout/slock_app_shell_splash_hold.xml").readText()

        assertTrue(
            "API31 app-shell starting theme must keep the cream handoff background",
            theme.contains(
                "<item name=\"android:windowBackground\">" +
                    "@color/slock_surface_cream</item>"
            )
        )
        assertTrue(
            "API31 app-shell hold must use a regular theme so the icon-bearing window is visible",
            theme.contains(
                "<style name=\"Theme.Slock.AppShellStarting\" parent=\"Theme.Slock\">"
            )
        )
        assertTrue(
            "API31 app-shell must not replace the launcher splash with an iconless preview",
            theme.contains(
                "<item name=\"android:windowDisablePreview\">true</item>"
            )
        )
        assertTrue(
            "app-shell native hold must keep the cream surface",
            hold.contains("android:background=\"@color/slock_surface_cream\"")
        )
        assertTrue(
            "app-shell native hold must keep the launcher foreground",
            hold.contains("android:src=\"@mipmap/ic_launcher_foreground\"")
        )
        assertTrue(
            "app-shell native hold foreground must stay centered",
            hold.contains("android:layout_gravity=\"center\"")
        )
    }

    @Test
    fun productionShellUsesDedicatedStartingTheme() {
        val manifest = sourceFile("androidApp/src/main/AndroidManifest.xml").readText()
        val activity =
            sourceFile(
                "androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt"
            ).readText()

        assertTrue(
            "SlockAppShellActivity must declare the dedicated starting theme",
            Regex(
                "android:name=\"\\.shared\\.SlockAppShellActivity\"[\\s\\S]*?" +
                    "android:theme=\"@style/Theme\\.Slock\\.AppShellStarting\""
            ).containsMatchIn(manifest)
        )
        assertTrue(
            "runtime cold-start theme must match the manifest theme",
            activity.contains("setTheme(R.style.Theme_Slock_AppShellStarting)")
        )
        assertTrue(
            "API31 shell must install the native icon hold instead of the iconless second-Activity splash",
            activity.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S") &&
                activity.contains("installHomeColdStartNativeHoldIfNeeded()") &&
                activity.contains("R.layout.slock_app_shell_splash_hold")
        )
        assertTrue(
            "Kuikly attach must wait until the native hold has drawn once",
            activity.contains("attachKuiklyHostAfterHoldFirstDraw()") &&
                activity.contains("ViewTreeObserver.OnDrawListener") &&
                activity.indexOf("installHomeColdStartNativeHoldIfNeeded()") <
                activity.indexOf("attachKuiklyHostAfterHoldFirstDraw()")
        )
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
