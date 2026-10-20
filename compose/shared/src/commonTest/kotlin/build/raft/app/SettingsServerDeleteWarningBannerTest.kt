package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsServerDeleteWarningBannerTest {
    @Test
    fun deleteWarningEmphasizesServerName() {
        val annotated = settingsServerDeleteWarningBannerText(serverName = "Visual Server")

        assertEquals(
            "This will permanently delete Visual Server and all its data (agents, channels, messages). This cannot be undone.",
            annotated.text
        )
        val serverNameStart = "This will permanently delete ".length
        val serverNameEnd = serverNameStart + "Visual Server".length
        val serverNameSpan = annotated.spanStyles.single()

        assertEquals(serverNameStart, serverNameSpan.start)
        assertEquals(serverNameEnd, serverNameSpan.end)
        assertEquals(RaftType.ButtonBold.fontFamily, serverNameSpan.item.fontFamily)
        assertEquals(RaftType.ButtonBold.fontWeight, serverNameSpan.item.fontWeight)
    }

    @Test
    fun deleteWarningFallbackAndOverrideStayPlain() {
        val fallback = settingsServerDeleteWarningBannerText(serverName = null)
        val override = settingsServerDeleteWarningBannerText(serverName = null, text = "Delete failed")

        assertEquals(
            "This will permanently delete this server and all its data (agents, channels, messages). This cannot be undone.",
            fallback.text
        )
        assertTrue(fallback.spanStyles.isEmpty())
        assertEquals("Delete failed", override.text)
        assertTrue(override.spanStyles.isEmpty())
    }
}
