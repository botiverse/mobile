package build.raft.app.assistant

import build.raft.app.data.DelegatedRetractResult
import build.raft.app.data.RaftAssistantDmCardsApi
import build.raft.app.data.ScreenError
import build.raft.app.data.WeChatBinding
import build.raft.app.data.WeChatDisconnectResult
import build.raft.app.data.WeChatStatus
import build.raft.app.data.WeChatStatusResult
import build.raft.app.data.weChatDisconnectResultOf
import build.raft.app.data.weChatStatusResultOf
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun aFailedRefreshKeepsWhatWasKnown() =
        runTest {
            val api = ScriptedApi(connected)
            val store = AssistantWeChatStore(api, "s1", backgroundScope)
            assertNull(store.status.value)
            store.refresh()
            assertEquals(connected, store.status.value)
            api.status = WeChatStatusResult.Error
            store.refresh()
            assertEquals(connected, store.status.value)
            assertEquals(2, api.reads)
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

    @Test
    fun anEndedConnectionClearsAReplacedOneIsReadAgainAFailureKeepsItsTrace() =
        runTest {
            val gone =
                ScriptedApi(connected, disconnect = weChatDisconnectResultOf(404, """{"error":"WeChat is not connected.","code":"wechat_not_connected"}""", null))
            val goneStore = AssistantWeChatStore(gone, "s1", backgroundScope)
            goneStore.refresh()
            assertEquals(WeChatDisconnectResult.AlreadyEnded(bindingChanged = false), goneStore.disconnect(bindingId))
            assertNull(goneStore.status.value.readyStatus()?.binding)

            val newer = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f"
            val replaced =
                ScriptedApi(
                    connected,
                    disconnect =
                        weChatDisconnectResultOf(
                            409,
                            """{"error":"WeChat was connected again since; that connection has ended.","code":"wechat_binding_changed"}""",
                            null,
                        ),
                )
            val replacedStore = AssistantWeChatStore(replaced, "s1", backgroundScope)
            replacedStore.refresh()
            replaced.status = WeChatStatusResult.Ready(WeChatStatus(true, WeChatBinding(newer, "active", "2026-11-17T01:00:00.000Z")))
            assertEquals(WeChatDisconnectResult.AlreadyEnded(bindingChanged = true), replacedStore.disconnect(bindingId))
            runCurrent()
            assertEquals(newer, replacedStore.status.value.readyStatus()?.binding?.bindingId, "the newer connection stays")

            val failing = ScriptedApi(connected, disconnect = WeChatDisconnectResult.Failed(ScreenError("http_500", traceId = TRACE)))
            val failingStore = AssistantWeChatStore(failing, "s1", backgroundScope)
            failingStore.refresh()
            val failed = failingStore.disconnect(bindingId)
            assertEquals(TRACE, (failed as WeChatDisconnectResult.Failed).error.traceId)
            assertEquals(bindingId, failingStore.status.value.readyStatus()?.binding?.bindingId, "still connected")
        }
}

private const val TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
