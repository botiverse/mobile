package build.raft.app.thread

import coil3.compose.AsyncImagePainter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ConnectedAppLogo background & fallback state machine tests (botiverse/slock #16706):
 * - No logo, loading, or failed -> lavender sticker fill with initials fallback.
 * - Loaded -> transparent sticker fill (no lavender behind transparent corners) and no fallback underneath.
 */
class ConnectedAppLogoStateTest {
    @Test
    fun missingOrBlankLogoStartsInNoneWithLavenderFillAndNoImageLayer() {
        for (url in listOf(null, "", "   ")) {
            val state = initialConnectedAppLogoImageState(url)
            assertEquals(ConnectedAppLogoImageState.None, state)
            assertFalse(connectedAppLogoUsesTransparentFill(state))
            assertFalse(connectedAppLogoShowsFallbackBehindImage(state))
            assertFalse(connectedAppLogoShowsImageLayer(url, state))
        }
    }

    @Test
    fun validLogoTransitionsLoadingToLoadedWithTransparentFillAndFailedBackToLavender() {
        val url = "https://cdn.raft.build/logos/linear.png"
        val loading = initialConnectedAppLogoImageState(url)
        assertEquals(ConnectedAppLogoImageState.Loading, loading)
        assertFalse(connectedAppLogoUsesTransparentFill(loading))
        assertTrue(connectedAppLogoShowsFallbackBehindImage(loading))
        assertTrue(connectedAppLogoShowsImageLayer(url, loading))

        val loaded = reduceConnectedAppLogoImageState(url, ConnectedAppLogoPainterEvent.Success)
        assertEquals(ConnectedAppLogoImageState.Loaded, loaded)
        assertTrue(connectedAppLogoUsesTransparentFill(loaded))
        assertFalse(connectedAppLogoShowsFallbackBehindImage(loaded))
        assertTrue(connectedAppLogoShowsImageLayer(url, loaded))

        val failed = reduceConnectedAppLogoImageState(url, ConnectedAppLogoPainterEvent.Error)
        assertEquals(ConnectedAppLogoImageState.Failed, failed)
        assertFalse(connectedAppLogoUsesTransparentFill(failed))
        assertFalse(connectedAppLogoShowsFallbackBehindImage(failed))
        assertFalse(connectedAppLogoShowsImageLayer(url, failed))

        // Blank URL always stays None even if an event arrives.
        assertEquals(
            ConnectedAppLogoImageState.None,
            reduceConnectedAppLogoImageState("", ConnectedAppLogoPainterEvent.Success),
        )
    }

    @Test
    fun emptyAndLoadingCoilStatesMapToLoading() {
        val url = "https://cdn.raft.build/logos/stamp.png"
        assertEquals(
            ConnectedAppLogoImageState.Loading,
            reduceConnectedAppLogoImageState(url, AsyncImagePainter.State.Empty),
        )
        assertEquals(
            ConnectedAppLogoImageState.Loading,
            reduceConnectedAppLogoImageState(url, AsyncImagePainter.State.Loading(painter = null)),
        )
    }
}
