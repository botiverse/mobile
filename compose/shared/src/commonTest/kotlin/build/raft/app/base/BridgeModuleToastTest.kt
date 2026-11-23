package build.raft.app.base

import build.raft.app.RaftTraceIdToastStrings
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
import build.raft.designsystem.components.RaftToastIntent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @AfterTest
    fun detach() {
        windows.forEach { it.detach() }
    }

    @Test
    fun errorToastShowsTheTraceIdAndCopiesTheWholeId() {
        val window = window(visible = true)
        window.addOrigin(capabilities)
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"

        bridge().errorToast("Unable to open thread", traceId) { RaftTraceIdToastStrings({ "Trace ID $it" }, "Copy", "Trace ID copied") }

        val toast = window.state.visible.single()
        assertEquals("Unable to open thread", toast.title)
        assertNull(toast.description)
        assertEquals("Trace ID 4bf92f35…", toast.footnote)
        assertEquals(RaftToastIntent.Error, toast.intent)
        assertEquals("Copy", toast.actionLabel)
        toast.onAction!!.invoke()
        val copied = calls.single { it.method == "copyToPasteboard" }
        assertEquals(traceId, Json.parseToJsonElement(copied.params).jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals(listOf("Trace ID copied"), window.state.visible.map { it.title })
    }

    @Test
    fun structuredErrorToastSeparatesTitleReasonAndTraceIdFootnote() {
        val window = window(visible = true)
        window.addOrigin(capabilities)
        val traceId = "62ac7fa79a3785a6bee414a074f82634"
        val reason =
            "This channel has 151 messages, which is more than can be moved to a shared channel right now. Nothing was changed. Start a new shared channel instead."

        bridge().errorToast(
            title = "Acme wasn't connected",
            reason = reason,
            traceId = traceId,
        ) { RaftTraceIdToastStrings({ "Trace ID $it" }, "Copy", "Trace ID copied") }

        val toast = window.state.visible.single()
        assertEquals("Acme wasn't connected", toast.title)
        assertEquals(reason, toast.description)
        assertEquals("Trace ID 62ac7fa7…", toast.footnote)
        assertEquals(RaftToastIntent.Error, toast.intent)
        assertEquals("Copy", toast.actionLabel)
    }

    @Test
    fun structuredErrorToastWithoutTraceIdStillShowsReasonInDescription() {
        val window = window(visible = true)
        window.addOrigin(capabilities)

        bridge().errorToast(title = "Acme wasn't connected", reason = "Try again later.", traceId = null)

        val toast = window.state.visible.single()
        assertEquals("Acme wasn't connected", toast.title)
        assertEquals("Try again later.", toast.description)
        assertNull(toast.footnote)
        assertEquals(RaftToastIntent.Error, toast.intent)
        assertNull(toast.actionLabel)
    }

    @Test
    fun errorToastWithoutReasonOrTraceIdKeepsErrorIntent() {
        val window = window(visible = true)
        window.addOrigin(capabilities)

        bridge().errorToast("Long server refusal sentence without a trace id", traceId = null)

        val toast = window.state.visible.single()
        assertEquals("Long server refusal sentence without a trace id", toast.title)
        assertNull(toast.description)
        assertNull(toast.footnote)
        assertEquals(RaftToastIntent.Error, toast.intent)
        assertNull(toast.actionLabel)
    }

    @Test
    fun errorToastWithRetryOffersRetryWhenNoResponseCarriedATraceId() {
        val window = window(visible = true)
        window.addOrigin(capabilities)
        var retries = 0

        bridge().errorToastWithRetry("Couldn't load these messages.", "Retry", onRetry = { retries += 1 }, traceId = null)

        val toast = window.state.visible.single()
        assertEquals("Couldn't load these messages.", toast.title)
        assertNull(toast.description)
        assertEquals("Retry", toast.actionLabel)
        toast.onAction?.invoke()
        assertEquals(1, retries)
    }
}
