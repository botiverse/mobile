package build.raft.app.base

import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.Module
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Kuikly `BridgeModule` and the CMP `BridgeWireHostCapabilities` send the same `reconcile` text,
 * with and without the multi-session flag, so a native host reads `multiSession` the same way from both.
 */
class BridgeModuleRealtimeReconcileWireTest {
    private val calls = mutableListOf<Pair<String, String>>()
    private val transport = HostBridgeTransport { method, params, _ -> calls += method to params }
    private val capabilities = BridgeWireHostCapabilities(transport)
    private val navigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) = Unit

            override fun closePage() = Unit
        }

    private fun bridge(): BridgeModule {
        val pager =
            object : Pager() {
                override fun createExternalModules(): Map<String, Module> = mapOf(BridgeModule.MODULE_NAME to BridgeModule())
            }
        pager.pagerId = "bridge-realtime-reconcile-test"
        pager.pageData = PageData(JSONObject())
        pager.host = RaftCmpHost(capabilities, navigator, bridgeTransport = transport)
        return pager.acquireModule(BridgeModule.MODULE_NAME)
    }

    @Test
    fun kuiklyAndCmpReconcileWireMatchWithAndWithoutMultiSession() {
        val bridge = bridge()
        listOf(false, true).forEach { multiSession ->
            calls.clear()
            bridge.realtimeReconcile(
                serverIds = setOf("s2", "s1"),
                accessToken = "t",
                environmentId = "env",
                authorityScopeLeaseId = "lease",
                authorityPrincipalId = "p",
                authorityAuthGeneration = 2L,
                authorityConnectionFamilyId = "fam",
                multiSession = multiSession,
            )
            capabilities.realtimeReconcile(
                serverIds = setOf("s2", "s1"),
                accessToken = "t",
                environmentId = "env",
                authorityScopeLeaseId = "lease",
                authorityPrincipalId = "p",
                authorityAuthGeneration = 2L,
                authorityConnectionFamilyId = "fam",
                multiSession = multiSession,
            )
            val (kuikly, cmp) = calls
            assertEquals(kuikly, cmp, "multiSession=$multiSession")
            val wire = Json.parseToJsonElement(kuikly.second).jsonObject
            val decoded = wire["multiSession"]?.jsonPrimitive?.booleanOrNull
            if (multiSession) assertEquals(true, decoded) else assertNull(decoded, "the flag-off wire has no multiSession key")
            // Flag off: sorted as before. Multi-session: the caller's order, primary (front window) server first.
            assertEquals(
                if (multiSession) listOf("s2", "s1") else listOf("s1", "s2"),
                wire.getValue("serverIds").jsonArray.map { it.jsonPrimitive.content },
                "multiSession=$multiSession",
            )
        }
    }
}
