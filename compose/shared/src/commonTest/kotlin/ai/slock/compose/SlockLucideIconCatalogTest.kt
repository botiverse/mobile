package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockLucideIconCatalogTest {
    @Test
    fun membersAppsIconUsesGeneratedAppWindowIcon() {
        assertEquals(SlockLucideIconName.AppWindow, MembersAgentDetailTab.Apps.iconName)
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.AppWindow.wireName))
        assertFalse(SlockLucideIcons.Names.contains("plug"))
    }
}
