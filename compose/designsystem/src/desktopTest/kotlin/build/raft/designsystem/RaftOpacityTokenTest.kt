package build.raft.designsystem

import build.raft.designsystem.tokens.RaftOpacity
import build.raft.designsystem.tokens.RaftUiComponentTokens
import kotlin.test.Test
import kotlin.test.assertEquals

/** [RaftOpacity.Dimmed] is a const (const callers can't read a token); it stays raft-ui's `opacity.dimmed`. */
class RaftOpacityTokenTest {
    @Test
    fun dimmedIsTheToken() {
        for (context in listOf(RaftUiComponentTokens.Touch, RaftUiComponentTokens.Fine, RaftUiComponentTokens.Desktop)) {
            assertEquals(context.opacityDimmed, RaftOpacity.Dimmed)
        }
    }
}
