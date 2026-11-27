package build.raft.app.data

import build.raft.app.RaftHomeNotificationIcon
import build.raft.app.RaftHomeNotificationKind
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.homeSeatJoinBlockRows
import build.raft.app.homeSeatJoinBlocksAudience
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.SeatJoinBlocksChanged
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.settings.toHomeNotificationItem
import build.raft.app.toHomeNotificationItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seat-join-blocked notices (`GET /api/servers/:id/seat-join-blocks` and `seat-join-blocks:changed`,
 * `botiverse/slock` `#11576`, behind `pro_seat_add`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeatJoinBlocksApiTest {
    private val validTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"

    private val sampleValidBody =
        """
        {
          "blocks": [
            {
              "id": "blk-1",
              "userId": "user-grace",
              "name": "Grace Hopper",
              "inviterUserId": "user-admin",
              "inviterName": "Ada",
              "createdAt": "2026-11-10T10:00:00.000Z"
            },
            {
              "id": "blk-2",
              "userId": "user-bob",
              "name": "Bob Kahn",
              "inviterUserId": null,
              "inviterName": null,
              "createdAt": "2026-11-10T10:05:00.000Z"
            }
          ],
          "usage": {
            "seats": 5,
            "humans": 5,
            "agents": 12,
            "pricing": "person_v2"
          }
        }
        """.trimIndent()

    @Test
    fun parsesBlocksAndUsageIncludingPricingAndSkipsMalformedRows() {
        val parsed = parseSeatJoinBlocksResponse(200, sampleValidBody)
        val content = assertIs<DataResult.Content<SeatJoinBlocksResponse>>(parsed)
        assertEquals(2, content.data.blocks.size)
        assertEquals(
            SeatJoinBlock(
                id = "blk-1",
                userId = "user-grace",
                name = "Grace Hopper",
                inviterUserId = "user-admin",
                inviterName = "Ada",
                createdAt = "2026-11-10T10:00:00.000Z",
            ),
            content.data.blocks[0],
        )
        assertEquals(
            SeatJoinSeatUsage(seats = 5, humans = 5, agents = 12, pricing = ProPricing.PersonV2),
            content.data.usage,
        )

        // Omitted pricing defaults to SeatV1; malformed block entries are skipped.
        val partial =
            parseSeatJoinBlocksResponse(
                200,
                """{"blocks":[{"id":"","userId":"u1","name":"Bad"},{"id":"blk-ok","userId":"u2","name":"Lin"}],"usage":{"seats":3,"humans":3,"agents":0}}""",
            )
        val partialContent = assertIs<DataResult.Content<SeatJoinBlocksResponse>>(partial)
        assertEquals(1, partialContent.data.blocks.size)
        assertEquals("blk-ok", partialContent.data.blocks.single().id)
        assertEquals(ProPricing.SeatV1, partialContent.data.usage?.pricing)

        // Empty blocks drops usage (matching web useSeatJoinBlocks.ts).
        val empty = parseSeatJoinBlocksResponse(200, """{"blocks":[],"usage":{"seats":5,"humans":2,"agents":0}}""")
        val emptyContent = assertIs<DataResult.Content<SeatJoinBlocksResponse>>(empty)
        assertTrue(emptyContent.data.blocks.isEmpty())
        assertNull(emptyContent.data.usage)
    }

    @Test
    fun failsClosedOnHttpErrorOrContractMismatchAndPreservesTraceId() {
        val httpError =
            parseSeatJoinBlocksResponse(
                statusCode = 503,
                bodyText = """{"error":"Temporarily unavailable","code":"SERVICE_UNAVAILABLE"}""",
                traceId = validTraceId,
            )
        val err = assertIs<DataResult.Error>(httpError)
        assertEquals("seat_join_blocks_http_503", err.error.code)
        assertTrue(err.error.retryable)
        assertEquals("Temporarily unavailable", err.error.serverMessage)
        assertEquals("SERVICE_UNAVAILABLE", err.error.serverCode)
        assertEquals(validTraceId, err.error.traceId)

        val forbidden = parseSeatJoinBlocksResponse(403, """{"error":"Forbidden"}""", traceId = validTraceId)
        val forbiddenErr = assertIs<DataResult.Error>(forbidden)
        assertFalse(forbiddenErr.error.retryable)

        for (bad in listOf(null, "", "not-json", "[]", """{"blocks":"nope"}""")) {
            val mismatch = parseSeatJoinBlocksResponse(200, bad, traceId = validTraceId)
            val mismatchErr = assertIs<DataResult.Error>(mismatch)
            assertEquals("seat_join_blocks_contract_mismatch", mismatchErr.error.code)
            assertEquals(validTraceId, mismatchErr.error.traceId)
        }
    }

    @Test
    fun networkDataSourceRequestsServerScopedEndpoint() = runTest {
        val captured = mutableListOf<Triple<String, String, ServerId?>>()
        val client =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse {
                    captured += Triple<String, String, ServerId?>(method, path, serverIdOverride)
                    return HomeNetworkResponse(
                        code = 200,
                        bodyText = sampleValidBody,
                        responseHeaders = mapOf("X-Trace-Id" to listOf(validTraceId)),
                    )
                }
            }
        val result = NetworkSeatJoinBlocksDataSource(client).list(ServerId("srv-42"))
        val content = assertIs<DataResult.Content<SeatJoinBlocksResponse>>(result)
        assertEquals(2, content.data.blocks.size)
        assertEquals(listOf(Triple<String, String, ServerId?>("GET", "servers/srv-42/seat-join-blocks", ServerId("srv-42"))), captured)
    }

    @Test
    fun roleAndAudienceHelpersGateGuestsAndFlagOff() {
        assertTrue(serverRoleCanReadSeatJoinBlocks("owner"))
        assertTrue(serverRoleCanReadSeatJoinBlocks("admin"))
        assertTrue(serverRoleCanReadSeatJoinBlocks("member"))
        assertTrue(serverRoleCanReadSeatJoinBlocks("  Admin "))
        assertFalse(serverRoleCanReadSeatJoinBlocks("guest"))
        assertFalse(serverRoleCanReadSeatJoinBlocks("GUEST"))
        assertFalse(serverRoleCanReadSeatJoinBlocks(null))
        assertFalse(serverRoleCanReadSeatJoinBlocks("   "))

        assertEquals("seat-join-blocked:blk-1", seatJoinBlockedHomeNotificationId("blk-1"))
        assertEquals("blk-1", "seat-join-blocked:blk-1".seatJoinBlockIdFromHomeNotificationIdOrNull())
        assertNull("seat-join-blocked:".seatJoinBlockIdFromHomeNotificationIdOrNull())
        assertNull("usage-limit:inc-1".seatJoinBlockIdFromHomeNotificationIdOrNull())

        val viewer = MemberProfile(id = MemberId("u-1"), displayName = "Viewer", handle = "viewer", kind = MemberKind.Human)
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "u-1", email = "viewer@acme.test", displayName = "Viewer"),
                accessToken = "access-tok",
                refreshToken = "refresh-tok",
            )
        val memberHome =
            SharedHomeContentState.Content(
                snapshot =
                    HomeSnapshot(
                        server = ServerSummary(id = ServerId("srv-1"), name = "Acme", role = "member"),
                        currentMember = viewer,
                        channels = emptyList(),
                        directMessages = emptyList(),
                        unreadTotal = 0,
                    ),
                session = session,
            )
        val guestHome =
            SharedHomeContentState.Content(
                snapshot =
                    HomeSnapshot(
                        server = ServerSummary(id = ServerId("srv-1"), name = "Acme", role = "guest"),
                        currentMember = viewer,
                        channels = emptyList(),
                        directMessages = emptyList(),
                        unreadTotal = 0,
                    ),
                session = session,
            )
        assertTrue(homeSeatJoinBlocksAudience(memberHome, proSeatAddEnabled = true).canRead)
        assertFalse(homeSeatJoinBlocksAudience(memberHome, proSeatAddEnabled = false).canRead)
        assertFalse(homeSeatJoinBlocksAudience(guestHome, proSeatAddEnabled = true).canRead)
    }

    @Test
    fun storeMakesZeroRequestsForGuestsOrFlagOffAndRefreshesOnPingAndStaleReturn() = runTest {
        var clock = 1_000L
        val requestedServers = mutableListOf<String>()
        var nextResponse: DataResult<SeatJoinBlocksResponse> =
            DataResult.Content(
                SeatJoinBlocksResponse(
                    blocks = listOf(SeatJoinBlock(id = "blk-1", userId = "u-1", name = "Grace")),
                    usage = SeatJoinSeatUsage(seats = 5, humans = 5, agents = 0),
                )
            )
        val dataSource =
            object : SeatJoinBlocksDataSource {
                override suspend fun list(serverId: ServerId): DataResult<SeatJoinBlocksResponse> {
                    requestedServers += serverId.value
                    return nextResponse
                }
            }
        val store = SharedSeatJoinBlocksStore(dataSource = dataSource, scope = this, nowMillis = { clock })

        // Guest and flag-off make ZERO requests.
        store.dispatch(SharedSeatJoinBlocksIntent.Load(serverId = "srv-1", proSeatAddEnabled = false, role = "owner"))
        store.dispatch(SharedSeatJoinBlocksIntent.Load(serverId = "srv-1", proSeatAddEnabled = true, role = "guest"))
        store.dispatch(SharedSeatJoinBlocksIntent.Load(serverId = null, proSeatAddEnabled = true, role = "owner"))
        runCurrent()
        assertTrue(requestedServers.isEmpty())
        assertTrue(store.state.value.visibleBlocks.isEmpty())

        // Flag ON + non-guest loads immediately.
        store.dispatch(SharedSeatJoinBlocksIntent.Load(serverId = "srv-1", proSeatAddEnabled = true, role = "admin"))
        runCurrent()
        assertEquals(listOf("srv-1"), requestedServers)
        assertEquals(listOf("blk-1"), store.state.value.homeSeatJoinBlockRows("srv-1").map { it.id })
        assertTrue(store.state.value.homeSeatJoinBlockRows("srv-other").isEmpty())

        // RefreshIfStale respects min reread window; advances after cooldown.
        clock += 10_000L
        store.dispatch(SharedSeatJoinBlocksIntent.RefreshIfStale)
        runCurrent()
        assertEquals(1, requestedServers.size)

        clock += SEAT_JOIN_BLOCKS_MIN_REREAD_MS
        store.dispatch(SharedSeatJoinBlocksIntent.RefreshIfStale)
        runCurrent()
        assertEquals(2, requestedServers.size)

        // Realtime ping triggers immediate reread for active server only.
        store.dispatch(SharedSeatJoinBlocksIntent.Changed("srv-other"))
        runCurrent()
        assertEquals(2, requestedServers.size)

        nextResponse =
            DataResult.Content(
                SeatJoinBlocksResponse(
                    blocks =
                        listOf(
                            SeatJoinBlock(id = "blk-1", userId = "u-1", name = "Grace"),
                            SeatJoinBlock(id = "blk-2", userId = "u-2", name = "Bob"),
                        ),
                    usage = SeatJoinSeatUsage(seats = 5, humans = 5, agents = 0),
                )
            )
        store.dispatch(SharedSeatJoinBlocksIntent.Changed("srv-1"))
        runCurrent()
        assertEquals(3, requestedServers.size)
        assertEquals(listOf("blk-1", "blk-2"), store.state.value.visibleBlocks.map { it.id })

        // Per-notice dismiss hides only blk-1; blk-2 remains visible.
        store.dispatch(SharedSeatJoinBlocksIntent.Dismiss("blk-1"))
        assertEquals(listOf("blk-2"), store.state.value.visibleBlocks.map { it.id })

        // Failed refresh preserves last known list for same server (matching web useSeatJoinBlocks.ts).
        nextResponse = DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        store.dispatch(SharedSeatJoinBlocksIntent.Refresh)
        runCurrent()
        assertEquals(listOf("blk-2"), store.state.value.visibleBlocks.map { it.id })

        // Turning flag off clears immediately.
        store.dispatch(SharedSeatJoinBlocksIntent.Load(serverId = "srv-1", proSeatAddEnabled = false, role = "admin"))
        runCurrent()
        assertTrue(store.state.value.blocks.isEmpty())
        assertNull(store.state.value.usage)
    }

    @Test
    fun storeDropsStaleResponseWhenSwitchingServers() = runTest {
        val srv1Deferred = CompletableDeferred<DataResult<SeatJoinBlocksResponse>>()
        val dataSource =
            object : SeatJoinBlocksDataSource {
                override suspend fun list(serverId: ServerId): DataResult<SeatJoinBlocksResponse> =
                    if (serverId.value == "srv-1") {
                        srv1Deferred.await()
                    } else {
                        DataResult.Content(
                            SeatJoinBlocksResponse(
                                blocks = listOf(SeatJoinBlock(id = "blk-2", userId = "u-2", name = "Bob")),
                            )
                        )
                    }
            }
        val store = SharedSeatJoinBlocksStore(dataSource = dataSource, scope = this)
        store.dispatch(SharedSeatJoinBlocksIntent.Load("srv-1", proSeatAddEnabled = true, role = "owner"))
        runCurrent()
        store.dispatch(SharedSeatJoinBlocksIntent.Load("srv-2", proSeatAddEnabled = true, role = "owner"))
        runCurrent()
        srv1Deferred.complete(
            DataResult.Content(
                SeatJoinBlocksResponse(blocks = listOf(SeatJoinBlock(id = "blk-stale", userId = "u-1", name = "Grace")))
            )
        )
        runCurrent()
        assertEquals("srv-2", store.state.value.serverId)
        assertEquals(listOf("blk-2"), store.state.value.visibleBlocks.map { it.id })
    }

    @Test
    fun realtimeMapperMapsSeatJoinBlocksChangedAndRowProjectionMatchesContract() {
        val mapped =
            assertIs<SeatJoinBlocksChanged>(
                RealtimeEventMapper().map(RealtimeSocketEvents.SeatJoinBlocksChanged, """{"serverId":"srv-9"}""")
            )
        assertEquals(ServerId("srv-9"), mapped.serverId)
        assertNull(RealtimeEventMapper().map(RealtimeSocketEvents.SeatJoinBlocksChanged, """{"serverId":""}"""))

        val block = SeatJoinBlock(id = "blk-1", userId = "user-grace", name = "Grace Hopper")
        val item = block.toHomeNotificationItem(title = "Acme · Grace Hopper couldn't join", body = "Upgrade needed", actionLabel = "Add seats")
        assertEquals("seat-join-blocked:blk-1", item.id)
        assertEquals("user-grace", item.targetId)
        assertEquals("Add seats", item.actionLabel)
        assertEquals(RaftHomeNotificationKind.Warning, item.kind)
        assertEquals(RaftHomeNotificationIcon.Default, item.icon)

        val iosItem = block.toHomeNotificationItem(title = "Acme · Grace Hopper couldn't join", body = "Out of seats", actionLabel = null)
        assertEquals("", iosItem.actionLabel)
    }
}
