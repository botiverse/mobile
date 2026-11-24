package build.raft.app.cmp

import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.designsystem.components.RaftToastHostState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun aWindowLeavingTheScreenHandsItsToastsToTheForegroundWindow() {
        val a = window(visible = true)
        val b = window(visible = true, resumed = false)
        a.state.show("Saved.")

        // A page closed right after its toast: the Activity under it comes back first.
        b.setLifecycle(visible = true, resumed = true)
        a.setLifecycle(visible = false)
        assertEquals(emptyList(), a.titles())
        assertEquals(listOf("Saved."), b.titles())
        assertTrue(b.state.visible.single().appearAtOnce, "handed-off toast does not replay entry slide")

        // A detached window takes nothing.
        b.detach()
        assertFalse(RaftCmpToasts.post("Deleted."))
        assertEquals(listOf("Saved."), b.titles())
    }

    @Test
    fun multiplePagesSharingOneSceneWindowKeepSingleLiveToastAcrossPopsWithoutHandOff() {
        val sceneWindow = window(visible = true, resumed = true)
        val parentPage = capabilities()
        val childPage = capabilities()
        val removeParent = sceneWindow.addOrigin(parentPage)
        val removeChild = sceneWindow.addOrigin(childPage)

        assertTrue(RaftCmpToasts.post("Link copied.", childPage))
        val originalId = sceneWindow.state.visible.single().id

        // Child page pops and removes its origin: the scene toast stays undisturbed with the same id.
        removeChild()
        assertEquals(listOf("Link copied."), sceneWindow.titles())
        assertEquals(originalId, sceneWindow.state.visible.single().id)
        assertFalse(sceneWindow.state.visible.single().appearAtOnce)

        // Parent page posts directly into the same scene window.
        assertTrue(RaftCmpToasts.post("Saved.", parentPage))
        assertEquals(listOf("Saved."), sceneWindow.titles())
        removeParent()
    }
}
