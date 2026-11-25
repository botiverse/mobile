package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Settings › Appearance › "Show member descriptions" (slock #16597): when off, hides sidebar DM
 * row descriptions for both human and agent rows; blank descriptions always stay omitted.
 */
class HomeSidebarDirectMessageSubtitleTest {
    @Test
    fun hidesSidebarDmDescriptionWhenSettingOffAndPreservesWhenOn() {
        assertEquals(
            "Ships the mobile client",
            homeSidebarDirectMessageSubtitle("Ships the mobile client", showMemberDescriptions = true),
        )
        assertNull(
            homeSidebarDirectMessageSubtitle("Ships the mobile client", showMemberDescriptions = false),
        )
        assertNull(
            homeSidebarDirectMessageSubtitle("   ", showMemberDescriptions = true),
        )
        assertNull(
            homeSidebarDirectMessageSubtitle(null, showMemberDescriptions = true),
        )
    }
}
