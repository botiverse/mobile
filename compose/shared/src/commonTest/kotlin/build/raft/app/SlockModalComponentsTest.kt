package build.raft.app

import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockModalComponentsTest {
    @Test
    fun unifiedDialogOuterClearanceKeepsCardInsideSafeViewport() {
        val clearance =
            slockUnifiedDialogOuterClearance(
                outerPadding = 16.dp,
                topInset = 40.dp,
                bottomInset = 88.dp
            )

        assertEquals(16.dp, clearance.start)
        assertEquals(56.dp, clearance.top)
        assertEquals(16.dp, clearance.end)
        assertEquals(104.dp, clearance.bottom)
    }

    @Test
    fun unifiedDialogOuterClearancePreservesZeroInsetFixtures() {
        val clearance =
            slockUnifiedDialogOuterClearance(
                outerPadding = 16.dp,
                topInset = 0.dp,
                bottomInset = 0.dp
            )

        assertEquals(16.dp, clearance.start)
        assertEquals(16.dp, clearance.top)
        assertEquals(16.dp, clearance.end)
        assertEquals(16.dp, clearance.bottom)
    }
}
