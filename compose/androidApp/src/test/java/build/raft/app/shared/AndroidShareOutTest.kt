package build.raft.app.shared

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidShareOutTest {
    @Test
    fun android13AndOlderSkipThePreSheetPickerWhenThereAreNoExtraActions() {
        // ⋯ menu Share channel / Share Lounge / Share thread / Share task sends empty actions
        // because Copy link is already beside Share in the ⋯ menu.
        assertFalse(androidShareOutNeedsPreSheetPicker(actionCount = 0, sdkInt = Build.VERSION_CODES.S))
        assertFalse(androidShareOutNeedsPreSheetPicker(actionCount = 0, sdkInt = Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun android13AndOlderKeepThePreSheetPickerWhenThereIsAChoice() {
        // Message long-press, multi-select, and image share still offer real choices.
        assertTrue(androidShareOutNeedsPreSheetPicker(actionCount = 1, sdkInt = Build.VERSION_CODES.S))
        assertTrue(androidShareOutNeedsPreSheetPicker(actionCount = 3, sdkInt = Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun android14AndNewerUseSystemCustomActionsInsteadOfThePreSheetPicker() {
        assertFalse(androidShareOutNeedsPreSheetPicker(actionCount = 0, sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertFalse(androidShareOutNeedsPreSheetPicker(actionCount = 3, sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }
}
