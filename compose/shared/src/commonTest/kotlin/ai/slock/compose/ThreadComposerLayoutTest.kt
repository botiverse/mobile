package ai.slock.compose

import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadComposerLayoutTest {
    @Test
    fun threadComposerBottomPaddingIncludesClosedKeyboardSafeArea() {
        assertEquals(40.dp, threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 24.dp))
    }

    @Test
    fun threadComposerBottomPaddingDoesNotDoubleCountSafeAreaWhenKeyboardOpen() {
        assertEquals(136.dp, threadComposerBottomPadding(keyboardInsetDp = 120f, bottomSafeArea = 24.dp))
    }

    @Test
    fun threadComposerBottomPaddingKeepsSmallLiftWithoutSafeArea() {
        assertEquals(16.dp, threadComposerBottomPadding(keyboardInsetDp = -1f, bottomSafeArea = 0.dp))
    }
}
