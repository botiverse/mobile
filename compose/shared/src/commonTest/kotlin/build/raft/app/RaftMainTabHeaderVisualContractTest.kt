package build.raft.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftMainTabHeaderVisualContractTest {
    @Test
    fun topLevelTabHeadsStartOnThePhoneMarginWithTheWebHeadline() {
        assertEquals(16.dp, RaftMainTabHeaderStartPadding)
        // web TabRootBar: text-headline 17/22.
        assertEquals(17.sp, RaftMainTabHeaderTitleStyle.fontSize)
        assertEquals(22.sp, RaftMainTabHeaderTitleStyle.lineHeight)
    }
}
