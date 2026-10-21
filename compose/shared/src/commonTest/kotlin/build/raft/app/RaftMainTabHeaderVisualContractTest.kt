package build.raft.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftMainTabHeaderVisualContractTest {
    @Test
    fun topLevelTabHeadsStartOnThePhoneMarginWithTheCompactTitle() {
        assertEquals(16.dp, RaftMainTabHeaderStartPadding)
        assertEquals(16.sp, RaftMainTabHeaderTitleStyle.fontSize)
        assertEquals(20.sp, RaftMainTabHeaderTitleStyle.lineHeight)
    }
}
