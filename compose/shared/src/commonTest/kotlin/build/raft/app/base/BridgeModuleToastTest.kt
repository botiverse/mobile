package build.raft.app.base

import build.raft.app.cmp.RaftCmpToastWindow
import build.raft.app.cmp.RaftCmpToasts
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.Module
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.designsystem.components.RaftToastHostState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `BridgeModule.toast` (229 call sites) shows the design-system toast of the window that renders
 * the page when that window is in the foreground, and sends the native bridge `toast` only when no
 * window can show it.
 */
class BridgeModuleToastTest {
    private class Call(val method: String, val params: String)

    private val calls = mutableListOf<Call>()
    private val transport = HostBridgeTransport { method, params, _ -> calls += Call(method, params) }
    private val capabilities = BridgeWireHostCapabilities(transport)
    private val navigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) = Unit

            override fun closePage() = Unit
        }
    private val windows = mutableListOf<RaftCmpToastWindow>()

    private fun bridge(): BridgeModule {
        val pager =
            object : Pager() {
                override fun createExternalModules(): Map<String, Module> = mapOf(BridgeModule.MODULE_NAME to BridgeModule())
            }
        pager.pagerId = "bridge-toast-test"
        pager.pageData = PageData(JSONObject())
        pager.host = RaftCmpHost(capabilities, navigator, bridgeTransport = transport)
        return pager.acquireModule(BridgeModule.MODULE_NAME)
    }

    private fun window(visible: Boolean): RaftCmpToastWindow =
        RaftCmpToasts.attach(RaftToastHostState()).also { window ->
            window.setLifecycle(visible = visible, resumed = visible)
            windows += window
        }

    private fun nativeToasts(): List<String> =
        calls.filter { it.method == "toast" }.map { Json.parseToJsonElement(it.params).jsonObject.getValue("content").jsonPrimitive.content }

    @AfterTest
    fun detach() {
        windows.forEach { it.detach() }
    }

    @Test
    fun postsToTheForegroundWindowRenderingThePage() {
        val window = window(visible = true)
        window.addOrigin(capabilities)

        bridge().toast("Link copied.")

        assertEquals(listOf("Link copied."), window.state.visible.map { it.title })
        assertEquals(emptyList(), nativeToasts())
    }

    @Test
    fun fallsBackToTheNativeToastWithNoWindowAttached() {
        bridge().toast("Saved.")

        assertEquals(listOf("Saved."), nativeToasts())
    }

    @Test
    fun fallsBackToTheNativeToastWhileTheWindowIsInTheBackground() {
        val window = window(visible = false)
        window.addOrigin(capabilities)

        bridge().toast("Saved.")

        assertEquals(emptyList(), window.state.visible.map { it.title })
        assertEquals(listOf("Saved."), nativeToasts())
    }
}
