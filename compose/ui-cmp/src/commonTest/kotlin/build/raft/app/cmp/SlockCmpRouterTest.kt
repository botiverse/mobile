package build.raft.app.cmp

import androidx.compose.runtime.Composable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockCmpRouterTest {
    @AfterTest
    fun reset() {
        SlockCmpRouter.configure(debugBuild = false)
        SlockCmpRouter.installPageProvider { null }
    }

    @Test
    fun releaseBuildDoesNotAdmitTheDebugHelloPage() {
        SlockCmpRouter.configure(debugBuild = false)
        assertFalse(SlockCmpRouter.rendersOnCmp(SlockCmpPages.CMP_HELLO))
        assertNull(SlockCmpRouter.page(SlockCmpPages.CMP_HELLO))
    }

    @Test
    fun debugBuildRendersTheHelloPage() {
        SlockCmpRouter.configure(debugBuild = true)
        assertTrue(SlockCmpRouter.rendersOnCmp(SlockCmpPages.CMP_HELLO))
        assertEquals(SlockCmpPages.CMP_HELLO, SlockCmpRouter.page(SlockCmpPages.CMP_HELLO)?.pageName)
    }

    @Test
    fun everyPageOfTheInstalledProviderRendersOnCmp() {
        val provided = setOf("compose_slock_app_shell", "compose_thread", "compose_settings_storage")
        val body: @Composable () -> Unit = {}
        SlockCmpRouter.installPageProvider { name -> if (name in provided) body else null }
        for (pageName in provided) {
            assertTrue(SlockCmpRouter.rendersOnCmp(pageName), pageName)
            assertNotNull(SlockCmpRouter.content(pageName), pageName)
            assertNull(SlockCmpRouter.page(pageName), pageName)
        }
        for (unknown in listOf("", "router", "compose_unknown")) {
            assertFalse(SlockCmpRouter.rendersOnCmp(unknown), unknown)
            assertNull(SlockCmpRouter.content(unknown), unknown)
        }
    }

    @Test
    fun aThrowingProviderCountsAsNoPage() {
        SlockCmpRouter.installPageProvider { error("registry unavailable") }
        assertFalse(SlockCmpRouter.rendersOnCmp("compose_thread"))
    }

    @Test
    fun uiCmpPagesAreUnique() {
        val pages = SlockCmpPages.debugOnly
        assertEquals(pages.size, pages.map { it.pageName }.toSet().size)
        for (page in pages) {
            assertTrue(page.pageName.startsWith("compose_"), page.pageName)
        }
    }
}
