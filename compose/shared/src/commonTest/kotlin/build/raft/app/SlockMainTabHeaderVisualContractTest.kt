package build.raft.app

import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockMainTabHeaderVisualContractTest {
    @Test
    fun membersTasksAndSettingsUseCompactIconlessMobileHeaderTokens() {
        assertEquals(8.dp, SlockMainTabHeaderStartPadding)
        assertEquals(16.sp, SlockMainTabHeaderTitleStyle.fontSize)
        assertEquals(20.sp, SlockMainTabHeaderTitleStyle.lineHeight)
        assertNull(SlockMainTabHeaderIconName)
    }
}
