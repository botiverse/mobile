package build.raft.app.cmp.host

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CMP host must hit the native bridge with the same method names and param JSON text as the
 * Kuikly `BridgeModule` (compose/shared/.../base/BridgeModule.kt), so native hosts need no change.
 */
class BridgeWireHostCapabilitiesTest {
    private class Call(val method: String, val params: String, val callback: ((String?) -> Unit)?)

    private val calls = mutableListOf<Call>()
    private val capabilities = BridgeWireHostCapabilities { method, params, callback -> calls += Call(method, params, callback) }

    private fun wire(): List<Pair<String, String>> = calls.map { it.method to it.params }

    @Test
    fun fireAndForgetCallsMatchTheKuiklyBridgeWire() {
        capabilities.toast("Saved")
        capabilities.copyToPasteboard("a/b")
        capabilities.openUrl("https://raft.build/x")
        capabilities.openUrl("https://raft.build/y", mimeType = "application/pdf")
        capabilities.openUrl("https://raft.build/z", mimeType = " ")
        capabilities.appBadge(3,"SERVER_UNREAD_SUMMARY", isStale = false)
        capabilities.syncDesktopShortcuts("[]")
        assertEquals(
            listOf(
                "toast" to """{"content": "Saved"}""",
                "copyToPasteboard" to """{"content": "a\/b"}""",
                "openUrl" to """{"url": "https:\/\/raft.build\/x"}""",
                "openUrl" to """{"url": "https:\/\/raft.build\/y","mimeType": "application\/pdf"}""",
                "openUrl" to """{"url": "https:\/\/raft.build\/z"}""",
                "appBadge" to """{"count": 3,"source": "SERVER_UNREAD_SUMMARY","isStale": false}""",
                "syncDesktopShortcuts" to """{"shortcuts": "[]"}""",
            ),
            wire(),
        )
        assertTrue(calls.all { it.callback == null })
    }

    @Test
    fun realtimeActionsMatchTheKuiklyBridgeWire() {
        capabilities.realtimeConnect(
            serverId = "s1",
            accessToken = "t",
            shellInstanceId = "shell",
            socketUrl = "wss://x",
            lastSeq = 9_000_000_000L,
            forceReconnect = true,
            authorityScopeLeaseId = "lease",
            authorityPrincipalId = "p",
            authorityAuthGeneration = 2L,
            authorityConnectionFamilyId = "fam",
        )
        capabilities.realtimeReconcile(
            serverIds = setOf(" b ","a","","a"),
            accessToken = "t",
            environmentId = "env",
            socketUrl = "wss://x",
            forceReconnect = false,
            disconnectReason = "auth_invalid",
            authorityScopeLeaseId = "",
            authorityPrincipalId = "",
            authorityAuthGeneration = 0L,
            authorityConnectionFamilyId = "",
        )
        capabilities.realtimeDisconnect(reason = "r", serverId = "s1", shellInstanceId = "", explicitClose = true)
        capabilities.realtimeAuthRefreshFinished("refresh", success = true)
        capabilities.realtimeCursorAdvance("s1", 0L, "","", 0L, "")
        capabilities.realtimeCursorAdvance(" ", 5L, "","", 0L, "")
        capabilities.realtimeCursorAdvance("s1", 5L, "lease","p", 2L, "fam")
        assertEquals(
            listOf(
                "reportRealTime" to
                    """{"action": "connect","serverId": "s1","accessToken": "t","shellInstanceId": "shell",""" +
                    """"socketUrl": "wss:\/\/x","lastSeq": 9000000000,"forceReconnect": true,""" +
                    """"authorityScopeLeaseId": "lease","authorityPrincipalId": "p","authorityAuthGeneration": 2,""" +
                    """"authorityConnectionFamilyId": "fam"}""",
                "reportRealTime" to
                    """{"action": "reconcile","serverIds": ["a","b"],"accessToken": "t","environmentId": "env",""" +
                    """"socketUrl": "wss:\/\/x","forceReconnect": false,"disconnectReason": "auth_invalid",""" +
                    """"authorityScopeLeaseId": "","authorityPrincipalId": "","authorityAuthGeneration": 0,""" +
                    """"authorityConnectionFamilyId": ""}""",
                "reportRealTime" to
                    """{"action": "disconnect","reason": "r","serverId": "s1","shellInstanceId": "","explicitClose": true}""",
                "reportRealTime" to """{"action": "authRefreshFinished","reason": "refresh","success": true}""",
                "reportRealTime" to
                    """{"action": "cursorAdvance","serverId": "s1","appliedSeq": 5,"authorityScopeLeaseId": "lease",""" +
                    """"authorityPrincipalId": "p","authorityAuthGeneration": 2,"authorityConnectionFamilyId": "fam"}""",
            ),
            wire(),
        )
    }

    @Test
    fun requestsResumeWithTheFirstResultOnly() = runTest {
        val command = JsonObject(mapOf("action" to JsonPrimitive("loadStorage")))
        val result = async(start = CoroutineStart.UNDISPATCHED) { capabilities.settingsCapability(command) }
        assertEquals(listOf("settingsCapability" to """{"action": "loadStorage"}"""), wire())
        calls.single().callback!!("""{"status": "ok"}""")
        calls.single().callback!!("""{"status": "late"}""")
        assertEquals(JsonPrimitive("ok"), result.await()!!["status"])

        val auth = async(start = CoroutineStart.UNDISPATCHED) { capabilities.socialAuth("google") }
        assertEquals("socialAuth" to """{"provider": "google"}""", wire().last())
        calls.last().callback!!(null)
        assertNull(auth.await())

        val upload = async(start = CoroutineStart.UNDISPATCHED) { capabilities.threadAttachmentUpload("c1", imageOnly = true) }
        assertEquals(
            "threadAttachmentUpload" to """{"channelId": "c1","imageOnly": true,"capture": false,"compressImages": false}""",
            wire().last(),
        )
        calls.last().callback!!("not json")
        assertNull(upload.await())
    }

    @Test
    fun settingsStreamDeliversUntilTerminalOrDispose() {
        val events = mutableListOf<String?>()
        val dispose =
            capabilities.settingsCapabilityStream(JsonObject(mapOf("action" to JsonPrimitive("feedbackDiagnosticsStart")))) {
                events += it?.get("phase")?.toString()
            }
        val callback = calls.single().callback!!
        callback("""{"phase": "upload"}""")
        callback("""{"phase": "done","terminal": true}""")
        callback("""{"phase": "after"}""")
        assertEquals<List<String?>>(listOf("\"upload\"","\"done\""), events)

        events.clear()
        val disposeSecond = capabilities.settingsCapabilityStream(JsonObject(emptyMap())) { events += "event" }
        disposeSecond()
        calls.last().callback!!("""{"phase": "x"}""")
        assertTrue(events.isEmpty())
        dispose()
    }

    @Test
    fun cancelledCompressionCancelsTheSameNativeRequest() = runTest {
        val job = async(start = CoroutineStart.UNDISPATCHED) { capabilities.compressThreadImage("ref","a.png","image/png") }
        val request = calls.single()
        assertEquals("compressThreadImage", request.method)
        val requestId = Regex(""""requestId": "([^"]+)"""").find(request.params)!!.groupValues[1]
        assertTrue(requestId.startsWith("cmp-thread-image-compress-"), requestId)
        assertEquals(
            """{"requestId": "$requestId","fileRef": "ref","fileName": "a.png","mimeType": "image\/png"}""",
            request.params,
        )
        job.cancel()
        assertEquals("cancelCompressThreadImage" to """{"requestId": "$requestId"}""", wire().last())
        request.callback!!("""{"status": "ok"}""")
        assertEquals(2, calls.size)
    }
}
