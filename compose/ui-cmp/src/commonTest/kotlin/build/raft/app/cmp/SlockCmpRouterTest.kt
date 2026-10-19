package build.raft.app.cmp

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
    }

    @Test
    fun releaseBuildRoutesNoPageToCmpUntilThePilotLands() {
        SlockCmpRouter.configure(debugBuild = false)
        assertTrue(SlockCmpPages.migrated.isEmpty())
        assertFalse(SlockCmpRouter.rendersOnCmp(SlockCmpPages.CMP_HELLO))
        assertNull(SlockCmpRouter.page(SlockCmpPages.CMP_HELLO))
    }

    @Test
    fun debugBuildRoutesTheHelloPageToCmp() {
        SlockCmpRouter.configure(debugBuild = true)
        assertTrue(SlockCmpRouter.rendersOnCmp(SlockCmpPages.CMP_HELLO))
        assertEquals(SlockCmpPages.CMP_HELLO, SlockCmpRouter.page(SlockCmpPages.CMP_HELLO)?.pageName)
    }

    @Test
    fun pagesWithoutACmpImplementationStayOnKuikly() {
        SlockCmpRouter.configure(debugBuild = true) { true }
        for (kuiklyPage in listOf("compose_slock_app_shell", "compose_thread", "compose_settings_storage", "", "router")) {
            assertFalse(SlockCmpRouter.rendersOnCmp(kuiklyPage), kuiklyPage)
            assertNull(SlockCmpRouter.page(kuiklyPage), kuiklyPage)
        }
    }

    @Test
    fun remoteOverrideRollsAPageBackToKuiklyButKeepsAnOpenHostRendering() {
        val seenKeys = mutableListOf<String>()
        SlockCmpRouter.configure(debugBuild = true) { page ->
            seenKeys += page.rollbackFlagKey
            false
        }
        assertFalse(SlockCmpRouter.rendersOnCmp(SlockCmpPages.CMP_HELLO))
        assertEquals(listOf("mobile_cmp_page_compose_cmp_hello"), seenKeys)
        assertNotNull(SlockCmpRouter.page(SlockCmpPages.CMP_HELLO))
    }

    @Test
    fun nullOrThrowingOverrideKeepsTheCodeDefault() {
        SlockCmpRouter.configure(debugBuild = true) { null }
        assertTrue(SlockCmpRouter.rendersOnCmp(SlockCmpPages.CMP_HELLO))
        SlockCmpRouter.configure(debugBuild = true) { error("flag store unavailable") }
        assertTrue(SlockCmpRouter.rendersOnCmp(SlockCmpPages.CMP_HELLO))
    }

    @Test
    fun allowlistEntriesAreUniqueAndCarryARollbackKey() {
        val pages = SlockCmpPages.migrated + SlockCmpPages.debugOnly
        assertEquals(pages.size, pages.map { it.pageName }.toSet().size)
        assertEquals(pages.size, pages.map { it.rollbackFlagKey }.toSet().size)
        for (page in pages) {
            assertTrue(page.pageName.startsWith("compose_"), page.pageName)
            assertEquals("mobile_cmp_page_${page.pageName}", page.rollbackFlagKey)
        }
    }
}
