package ai.slock.compose

import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadComposerLayoutTest {
    @Test
    fun threadComposerBottomPaddingIncludesClosedKeyboardSafeArea() {
        assertEquals(38.dp, threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 24.dp))
    }

    @Test
    fun threadComposerBottomPaddingDoesNotDoubleCountSafeAreaWhenKeyboardOpen() {
        assertEquals(134.dp, threadComposerBottomPadding(keyboardInsetDp = 120f, bottomSafeArea = 24.dp))
    }

    @Test
    fun threadComposerBottomPaddingKeepsSmallLiftWithoutSafeArea() {
        assertEquals(14.dp, threadComposerBottomPadding(keyboardInsetDp = -1f, bottomSafeArea = 0.dp))
    }

    @Test
    fun threadLoadingComposerPlaceholderReservesClosedComposerHeight() {
        assertEquals(118.dp, threadLoadingComposerPlaceholderHeight(bottomSafeArea = 24.dp))
    }
}
