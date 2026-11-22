package build.raft.app.assistant

import build.raft.app.data.DelegatedRetractResult
import build.raft.app.data.RaftAssistantDmCardsApi
import build.raft.app.data.WeChatBinding
import build.raft.app.data.WeChatDisconnectResult
import build.raft.app.data.WeChatStatus
import build.raft.app.data.WeChatStatusResult
import build.raft.app.data.weChatStatusResultOf
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The owner's WeChat row and dialog state (web wechatClient.ts `useWeChatStatus`,
 * `refreshWeChatStatus`, `disconnectWeChat`), on the bridge's real answers at botiverse/slock
 * staging 34511fe7e4a36d0d5c054f836be70821fa8b4608 (`routes/assistantWeChat.ts`: `GET /status`
 * `{ available, binding }`, `DELETE /binding` `{ binding: null }` / 404 `wechat_not_connected` /
 * 409 `wechat_binding_changed`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantWeChatStoreTest {
    private val bindingId = "0b6f4a3e-8c21-4d7a-9f10-3e2d1c0b9a87"
    private val connected =
        weChatStatusResultOf(
            200,
            """{"available":true,"binding":{"bindingId":"$bindingId","state":"active","connectedAt":"2026-11-16T08:12:00.000Z"}}""",
        )

    private class ScriptedApi(
        var status: WeChatStatusResult,
        var disconnect: WeChatDisconnectResult = WeChatDisconnectResult.Disconnected,
        val disconnectGate: CompletableDeferred<Unit>? = null,
    ) : RaftAssistantDmCardsApi {
        var reads = 0
        val disconnects = mutableListOf<String>()

        override suspend fun retractDelegated(serverId: ServerId, messageId: String): DelegatedRetractResult =
            error("not used")

        override suspend fun weChatStatus(serverId: ServerId): WeChatStatusResult {
            reads += 1
            return status
        }

        override suspend fun disconnectWeChat(serverId: ServerId, bindingId: String): WeChatDisconnectResult {
            disconnects += bindingId
            disconnectGate?.await()
            return disconnect
        }
    }

    @Test
    fun theRowShowsWhileWeChatMayBeConnectedOrIs() {
        assertTrue(connected.readyStatus().offersWeChatRow())
        assertTrue(weChatStatusResultOf(200, """{"available":true,"binding":null}""").readyStatus().offersWeChatRow())
        // A binding shows even while unavailable, so it can still be disconnected.
        assertTrue(
            weChatStatusResultOf(
                200,
                """{"available":false,"binding":{"bindingId":"$bindingId","state":"reconnect_required","connectedAt":""}}""",
            ).readyStatus().offersWeChatRow()
        )
        assertFalse(weChatStatusResultOf(200, """{"available":false,"binding":null}""").readyStatus().offersWeChatRow())
        // A server without the bridge (404), or a status that could not be read: no row.
        assertFalse(weChatStatusResultOf(404, null).readyStatus().offersWeChatRow())
        assertFalse(weChatStatusResultOf(503, null).readyStatus().offersWeChatRow())
        assertTrue(WeChatBinding(bindingId, "reconnect_required", "").stoppedWorking)
        assertFalse(WeChatBinding(bindingId, "active", "").stoppedWorking)
    }

    @Test
    fun aSecondConfirmWaitsForTheSameDisconnect() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val api = ScriptedApi(connected, disconnectGate = gate)
            val store = AssistantWeChatStore(api, "s1", backgroundScope)
            store.refresh()
            val first = async { store.disconnect(bindingId) }
            val second = async { store.disconnect(bindingId) }
            runCurrent()
            assertTrue(store.disconnecting.value)
            gate.complete(Unit)
            assertEquals(WeChatDisconnectResult.Disconnected, first.await())
            assertEquals(WeChatDisconnectResult.Disconnected, second.await())
            assertEquals(listOf(bindingId), api.disconnects, "one DELETE for both confirms")
            assertFalse(store.disconnecting.value)
            // Raft forgot the binding: the dialog turns to Connect WeChat.
            assertEquals(WeChatStatus(available = true, binding = null), store.status.value.readyStatus())
        }
}
