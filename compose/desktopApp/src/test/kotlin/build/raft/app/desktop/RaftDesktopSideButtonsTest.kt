package build.raft.app.desktop

import dev.nucleusframework.window.tao.TaoMouseButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A mouse's side buttons in the Tao window: Nucleus gives back and forward one code and its host would
 * turn it into a primary click on whatever is under the pointer, so the window drops them.
 */
class RaftDesktopSideButtonsTest {
    @Test fun `a side button never reaches the window's content, the other buttons do`() {
        val received = mutableListOf<Pair<Int, Boolean>>()
        val listener = RaftDesktopSideButtons.withoutSideButtons { button, pressed -> received += button to pressed }

        listener(TaoMouseButton.OTHER, true)
        listener(TaoMouseButton.OTHER, false)
        listener(TaoMouseButton.LEFT, true)
        listener(TaoMouseButton.LEFT, false)
        listener(TaoMouseButton.RIGHT, true)
        listener(TaoMouseButton.MIDDLE, false)

        assertEquals(
            listOf(TaoMouseButton.LEFT to true, TaoMouseButton.LEFT to false, TaoMouseButton.RIGHT to true, TaoMouseButton.MIDDLE to false),
            received,
        )
    }

    @Test fun `the pinned Nucleus window has the listener slot the filter wraps`() {
        // A Nucleus bump that renames it would let side buttons click again: caught here, not in QA.
        assertNotNull(RaftDesktopSideButtons.listenerField)
    }
}
