package build.raft.designsystem

import build.raft.designsystem.components.raftPopupClosesForClippedAnchor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftPopupAnchorDismissRuleTest {
    /**
     * #306 review: a popup whose trigger is clipped out of view closes (raft-ui
     * `usePopupAnchorDismiss`), but not while people type in its search: the keyboard that
     * search brings up shrinks a centred dialog and clips the field the popup hangs from.
     */
    @Test
    fun aClippedTriggerClosesThePopupUnlessItsSearchIsBeingTyped() {
        assertFalse(raftPopupClosesForClippedAnchor(clipped = true, typing = true), "clipped while its search has focus: stays open")
        assertTrue(raftPopupClosesForClippedAnchor(clipped = true, typing = false), "clipped, search not focused: closes")
        assertFalse(raftPopupClosesForClippedAnchor(clipped = false, typing = false), "in view: stays open")
    }
}
