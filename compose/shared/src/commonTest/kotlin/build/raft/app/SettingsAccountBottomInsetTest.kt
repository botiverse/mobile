package build.raft.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsAccountBottomInsetTest {
    @Test
    fun standaloneAccountContentAddsNavigationInsetToSettingsSpacing() {
        assertEquals(18.dp + SlockSpacing.Lg, settingsAccountStandaloneBottomContentPadding(18.dp))
    }

    @Test
    fun zeroNavigationInsetKeepsDefaultSettingsSpacing() {
        assertEquals(SlockSpacing.Lg, settingsAccountStandaloneBottomContentPadding(0.dp))
    }
}
