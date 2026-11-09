package build.raft.app.cmp

import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.designsystem.components.RaftToastHostState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which window's host [RaftCmpToasts.post] picks, and the hand-off when a window leaves the screen. */
class RaftCmpToastsTest {
    private val wire = mutableListOf<String>()
    private val windows = mutableListOf<RaftCmpToastWindow>()

    private fun capabilities() = BridgeWireHostCapabilities(HostBridgeTransport { method, _, _ -> wire += method })

    private fun window(visible: Boolean, resumed: Boolean = visible): RaftCmpToastWindow =
        RaftCmpToasts.attach(RaftToastHostState()).also {
            it.setLifecycle(visible = visible, resumed = resumed)
            windows += it
        }

    private fun RaftCmpToastWindow.titles() = state.visible.map { it.title }

    @AfterTest
    fun detachAll() {
        windows.forEach { it.detach() }
    }

    @Test
    fun aToastGoesToTheWindowShowingItsPage() {
        val pageA = capabilities()
        val a = window(visible = true)
        a.addOrigin(pageA)
        val b = window(visible = true) // resumed last: the active window

        assertTrue(RaftCmpToasts.post("Saved.", pageA))
        assertTrue(RaftCmpToasts.post("Copied"))
        assertEquals(listOf("Saved."), a.titles())
        assertEquals(listOf("Copied"), b.titles(), "no origin: the active window")
    }

    @Test
    fun aPageInABackgroundWindowToastsOnTheForegroundOne() {
        val pageA = capabilities()
        val a = window(visible = false)
        a.addOrigin(pageA)
        val b = window(visible = true)

        assertTrue(RaftCmpToasts.post("Upload finished.", pageA))
        assertEquals(emptyList(), a.titles())
        assertEquals(listOf("Upload finished."), b.titles())
        assertSame(b, RaftCmpToasts.target(pageA))
    }

    @Test
    fun withNoForegroundWindowTheCallerFallsBackToTheNativeToast() {
        val page = capabilities()
        val a = window(visible = false)
        a.addOrigin(page)

        assertFalse(RaftCmpToasts.post("Saved.", page))
        page.toast("Saved.")
        assertEquals(listOf("toast"), wire, "the wire capabilities send the native toast")
        assertEquals(emptyList(), a.titles())

        assertTrue(RaftCmpToasts.post("Saved.", page, foregroundOnly = false))
        assertEquals(listOf("Saved."), a.titles())
    }

    @Test
    fun theWireCapabilitiesPostToAnAttachedWindowInsteadOfTheBridge() {
        val page = capabilities()
        val a = window(visible = true)
        a.addOrigin(page)

        page.toast("Link copied.")
        assertEquals(listOf("Link copied."), a.titles())
        assertEquals(emptyList(), wire)
    }

    @Test
    fun aWindowLeavingTheScreenHandsItsToastsToTheForegroundWindow() {
        val a = window(visible = true)
        val b = window(visible = true, resumed = false)
        a.state.show("Saved.")

        // A page closed right after its toast: the Activity under it comes back first.
        b.setLifecycle(visible = true, resumed = true)
        a.setLifecycle(visible = false)
        assertEquals(emptyList(), a.titles())
        assertEquals(listOf("Saved."), b.titles())

        // A detached window takes nothing.
        b.detach()
        assertFalse(RaftCmpToasts.post("Deleted."))
        assertEquals(listOf("Saved."), b.titles())
    }

    @Test
    fun theAppGoingToTheBackgroundKeepsTheToasts() {
        val a = window(visible = true)
        a.state.show("Saved.")
        a.setLifecycle(visible = false)
        assertEquals(listOf("Saved."), a.titles())
    }
}
