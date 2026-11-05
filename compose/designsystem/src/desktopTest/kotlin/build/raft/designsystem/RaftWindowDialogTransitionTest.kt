package build.raft.designsystem

import androidx.compose.ui.ExperimentalComposeUiApi
import build.raft.designsystem.components.RaftWindowDialogProperties
import build.raft.designsystem.components.raftWindowDialogPlatformProperties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compose's own window transition (skiko: iOS and desktop share it): centered dialogs keep it
 * (they fade in while growing slightly, as on the web and Android); a drawer that slides itself
 * turns it off so the two motions don't stack.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RaftWindowDialogTransitionTest {
    @Test
    fun dialogsKeepThePlatformTransitionAndOnlyASelfMovingWindowDropsIt() {
        assertTrue(raftWindowDialogPlatformProperties(RaftWindowDialogProperties()).animateTransition)
        assertFalse(
            raftWindowDialogPlatformProperties(RaftWindowDialogProperties(drawScrim = false, platformTransition = false))
                .animateTransition,
        )
    }
}
