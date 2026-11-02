package build.raft.app.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The shell tab under a restored stack goes back to the shell as its launch tab (identity only). */
class RaftStackRootTabTest {
    private val shell = JsonObject(mapOf("slockShellInstanceId" to JsonPrimitive("shell-1")))

    @Test fun `the phone Settings tab comes back as the shell launch tab`() {
        val data = raftAppShellPageDataWithTab(shell, "more")
        assertEquals(JsonPrimitive("more"), data["slockInitialTab"])
        assertEquals(JsonPrimitive("shell-1"), data["slockShellInstanceId"])
        assertEquals(JsonPrimitive("settings"), raftAppShellPageDataWithTab(shell, "settings")["slockInitialTab"])
    }

    @Test fun `home missing or unknown tabs leave the shell pageData unchanged`() {
        assertSame(shell, raftAppShellPageDataWithTab(shell, null))
        assertSame(shell, raftAppShellPageDataWithTab(shell, "home"))
        assertSame(shell, raftAppShellPageDataWithTab(shell, "not-a-tab"))
    }
}
