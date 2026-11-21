package build.raft.designsystem

import androidx.compose.ui.ExperimentalComposeUiApi
import build.raft.designsystem.components.RaftSheetWindowDialogProperties
import build.raft.designsystem.components.RaftSideDrawerWindowProperties
import build.raft.designsystem.components.RaftWindowDialogProperties
import build.raft.designsystem.components.raftDialogSheetWindowProperties
import build.raft.designsystem.components.raftWindowDialogPlatformProperties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compose's own window transition (skiko: iOS and desktop share it): centered dialogs keep it
 * (they fade in while growing slightly, as on the web and Android); every sheet and drawer that
 * slides itself turns it off so the two motions don't stack.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RaftWindowDialogTransitionTest {
    @Test
    fun dialogsKeepThePlatformTransitionAndEverySelfSlidingSurfaceDropsIt() {
        assertTrue(raftWindowDialogPlatformProperties(RaftWindowDialogProperties()).animateTransition)
        assertTrue(raftWindowDialogPlatformProperties(raftDialogSheetWindowProperties(centered = true)).animateTransition)
        assertTrue(raftDialogSheetWindowProperties(centered = true).drawScrim)

        assertFalse(raftWindowDialogPlatformProperties(RaftSheetWindowDialogProperties).animateTransition)
        assertFalse(RaftSheetWindowDialogProperties.drawScrim)

        assertFalse(raftWindowDialogPlatformProperties(raftDialogSheetWindowProperties(centered = false)).animateTransition)
        assertFalse(raftDialogSheetWindowProperties(centered = false).drawScrim)

        assertFalse(raftWindowDialogPlatformProperties(RaftSideDrawerWindowProperties).animateTransition)
        assertFalse(RaftSideDrawerWindowProperties.drawScrim)
    }
}
