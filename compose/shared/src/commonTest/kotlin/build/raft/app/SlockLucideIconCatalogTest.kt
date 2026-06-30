package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockLucideIconCatalogTest {
    @Test
    fun membersAppsIconUsesGeneratedLink2Icon() {
        assertEquals(SlockLucideIconName.Link2, MembersAgentDetailTab.Apps.iconName)
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.Link2.wireName))
        assertFalse(SlockLucideIcons.Names.contains("plug"))
    }

    @Test
    fun threadConvertToTaskIconUsesGeneratedClipboardCheckIcon() {
        assertTrue(SlockLucideIcons.Names.contains(SlockLucideIconName.ClipboardCheck.wireName))
    }
}
