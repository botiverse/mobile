package build.raft.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockMainTabHeaderVisualContractTest {
    @Test
    fun membersTasksAndSettingsUseCompactIconfulMobileHeaderTokens() {
        assertEquals(8.dp, SlockMainTabHeaderStartPadding)
        assertEquals(16.sp, SlockMainTabHeaderTitleStyle.fontSize)
        assertEquals(20.sp, SlockMainTabHeaderTitleStyle.lineHeight)
        assertEquals(SlockLucideIconName.Users, SlockMainTabHeaderSurface.Members.iconName)
        assertEquals(SlockLucideIconName.SquareCheckBig, SlockMainTabHeaderSurface.Tasks.iconName)
        assertEquals(SlockLucideIconName.Settings, SlockMainTabHeaderSurface.Settings.iconName)
    }
}
