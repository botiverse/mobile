package build.raft.app.cmp

import androidx.compose.runtime.Composable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftCmpRouterTest {
    @AfterTest
    fun reset() {
        RaftCmpRouter.configure(debugBuild = false)
        RaftCmpRouter.installPageProvider { null }
    }

    @Test
    fun releaseBuildDoesNotAdmitTheDebugHelloPage() {
        RaftCmpRouter.configure(debugBuild = false)
        assertFalse(RaftCmpRouter.rendersOnCmp(RaftCmpPages.CMP_HELLO))
        assertNull(RaftCmpRouter.page(RaftCmpPages.CMP_HELLO))
    }

    @Test
    fun debugBuildRendersTheHelloPage() {
        RaftCmpRouter.configure(debugBuild = true)
        assertTrue(RaftCmpRouter.rendersOnCmp(RaftCmpPages.CMP_HELLO))
        assertEquals(RaftCmpPages.CMP_HELLO, RaftCmpRouter.page(RaftCmpPages.CMP_HELLO)?.pageName)
    }

    @Test
    fun everyPageOfTheInstalledProviderRendersOnCmp() {
        val provided = setOf("compose_slock_app_shell", "compose_thread", "compose_settings_storage")
        val body: @Composable () -> Unit = {}
        RaftCmpRouter.installPageProvider { name -> if (name in provided) body else null }
        for (pageName in provided) {
            assertTrue(RaftCmpRouter.rendersOnCmp(pageName), pageName)
            assertNotNull(RaftCmpRouter.content(pageName), pageName)
            assertNull(RaftCmpRouter.page(pageName), pageName)
        }
        for (unknown in listOf("", "router", "compose_unknown")) {
            assertFalse(RaftCmpRouter.rendersOnCmp(unknown), unknown)
            assertNull(RaftCmpRouter.content(unknown), unknown)
        }
    }

    @Test
    fun aThrowingProviderCountsAsNoPage() {
        RaftCmpRouter.installPageProvider { error("registry unavailable") }
        assertFalse(RaftCmpRouter.rendersOnCmp("compose_thread"))
    }

    @Test
    fun uiCmpPagesAreUnique() {
        val pages = RaftCmpPages.debugOnly
        assertEquals(pages.size, pages.map { it.pageName }.toSet().size)
        for (page in pages) {
            assertTrue(page.pageName.startsWith("compose_"), page.pageName)
        }
    }
}
