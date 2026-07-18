package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

class AppShellPrewarmProductionWiringTest {
    @Test
    fun everyForegroundAppShellTabInvalidatesTheSharedPrewarmGate() {
        val source = settingsHomePageSource()

        listOf(
            "home_intent:",
            "home_scroll",
            "tasks_intent:",
            "tasks_scroll",
            "members_retry",
            "members_scroll",
            "members_${'$'}reason",
            "settings_intent:",
            "settings_destination:"
        ).forEach { marker -> assertContains(source, marker) }
    }

    private fun settingsHomePageSource(): String {
        val commonMain =
            requireNotNull(
                listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                    .firstOrNull(File::isDirectory)
            ) { "Unable to locate shared commonMain source" }
        return File(commonMain, "build/raft/app/SettingsHomePage.kt")
            .readText()
            .replace("\r\n", "\n")
    }
}
