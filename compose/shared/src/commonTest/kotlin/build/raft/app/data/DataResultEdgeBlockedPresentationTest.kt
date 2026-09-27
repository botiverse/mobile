package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class DataResultEdgeBlockedPresentationTest {
    @Test
    fun edgeBlockedMarkerOnPermissionBlockerYieldsLocalizedEdgeBlocked() {
        val presentation =
            PermissionBlocker(code = "home_http_403", serverMessage = "edge_blocked")
                .errorPresentation()

        assertEquals(ErrorPresentationKey.EdgeBlocked, presentation.key)
        assertEquals(
            RawServerMessagePriority.LocalizedCopyFirst,
            presentation.rawServerMessagePriority
        )
    }

    @Test
    fun edgeBlockedMarkerOnScreenErrorYieldsLocalizedEdgeBlocked() {
        val presentation =
            ScreenError(code = "home_http_403", serverMessage = "edge_blocked")
                .errorPresentation()

        assertEquals(ErrorPresentationKey.EdgeBlocked, presentation.key)
        assertEquals(
            RawServerMessagePriority.LocalizedCopyFirst,
            presentation.rawServerMessagePriority
        )
    }

    @Test
    fun ordinaryServerMessageOn403StaysPermissionDeniedWithServerReason() {
        val presentation =
            PermissionBlocker(code = "home_http_403", serverMessage = "you are not a member")
                .errorPresentation()

        assertEquals(ErrorPresentationKey.PermissionDenied, presentation.key)
        assertEquals(
            RawServerMessagePriority.ServerMessageFirst,
            presentation.rawServerMessagePriority
        )
    }

    @Test
    fun edgeBlockedCodeResolvesToEdgeBlockedKey() {
        assertEquals(
            ErrorPresentationKey.EdgeBlocked,
            "edge_blocked".errorPresentationKeyOrNull()
        )
    }
}
