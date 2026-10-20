package build.raft.app.compat.module

import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RaftModuleCallbackTest {
    private class TestModule : Module() {
        override fun moduleName(): String = "RaftTestModule"
    }

    private val pendingResults = mutableListOf<(String?) -> Unit>()
    private val transport = HostBridgeTransport { _, _, callback -> if (callback != null) pendingResults += callback }
    private val navigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) = Unit

            override fun closePage() = Unit
        }

    private fun livePager(id: String): Pager =
        object : Pager() {
            override fun createExternalModules(): Map<String, Module> = mapOf("RaftTestModule" to TestModule())
        }.also { pager ->
            pager.pagerId = id
            pager.pageData = PageData(JSONObject())
            pager.host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
            PagerManager.register(pager)
        }

    @Test
    fun lateNativeResultsOfADestroyedPageAreDropped() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val pager = livePager("raft-module-test-pager")
            val module = pager.acquireModule<TestModule>("RaftTestModule")
            val delivered = mutableListOf<String>()
            module.toNative(methodName = "beforeDestroy", param = null, callback = { delivered += "before" })
            module.toNative(
                keepCallbackAlive = true,
                methodName = "stream",
                param = null,
                callback = { delivered += "stream" },
            )
            pendingResults[0]("{}")
            pendingResults[1]("{}")
            advanceUntilIdle()
            assertEquals(listOf("before", "stream"), delivered)

            module.toNative(methodName = "pending", param = null, callback = { delivered += "pending" })
            PagerManager.unregister(pager)
            // The native side answers after the page is gone: nothing may run.
            pendingResults[1]("{}")
            pendingResults[2]("{}")
            // A call made after destroy is still sent, but its callback never runs.
            module.toNative(methodName = "afterDestroy", param = null, callback = { delivered += "after" })
            pendingResults[3]("{}")
            advanceUntilIdle()
            assertEquals(listOf("before", "stream"), delivered)
            assertEquals(4, pendingResults.size)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
