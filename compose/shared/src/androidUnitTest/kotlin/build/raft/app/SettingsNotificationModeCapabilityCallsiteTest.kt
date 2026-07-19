package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsNotificationModeCapabilityCallsiteTest {
    @Test
    fun notificationsUiRendersOnlyRepositoryAdvertisedModes() {
        val source = commonMainSource("build/raft/app/SettingsHomePage.kt")
        val start = source.indexOf("private fun SettingsServerNotificationModeCard(")
        val end = source.indexOf("private fun ServerPushMode.settingsNotificationModeTitle", start)
        assertTrue(start >= 0 && end > start)
        val card = source.substring(start, end)

        assertTrue("preference.options.mapNotNull" in card)
        assertFalse("ServerPushMode.entries.forEach" in card)
    }

    private fun commonMainSource(relativePath: String): String {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(10) {
            val candidate = File(cursor, "src/commonMain/kotlin/$relativePath")
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate commonMain source: $relativePath")
    }
}
