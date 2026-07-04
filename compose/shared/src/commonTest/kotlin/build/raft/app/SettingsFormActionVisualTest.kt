package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsFormActionVisualTest {
    @Test
    fun settingsFormSubmitDisabledBackgroundIsNeutralWhenActionIsUnavailable() {
        assertEquals(
            SlockColor.EmptyStateGray,
            settingsFormSubmitDisabledBackgroundColor(
                activeColor = SlockColor.AccentPink,
                preserveActiveDisabledVisual = false
            )
        )
    }

    @Test
    fun settingsFormSubmitDisabledBackgroundCanPreserveActiveColorWhileBusy() {
        assertEquals(
            SlockColor.AccentPink.copy(alpha = 0.50f),
            settingsFormSubmitDisabledBackgroundColor(
                activeColor = SlockColor.AccentPink,
                preserveActiveDisabledVisual = true
            )
        )
    }
}
