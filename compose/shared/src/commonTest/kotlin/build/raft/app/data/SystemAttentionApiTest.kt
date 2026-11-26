package build.raft.app.data

import build.raft.app.RaftHomeNotificationIcon
import build.raft.app.RaftHomeNotificationKind
import build.raft.app.hasUnreadHomeNotifications
import build.raft.app.homeUsageLimitAttentionAudience
import build.raft.app.homeUsageLimitAttentionRows
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.toHomeNotificationItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner/admin runtime-account usage-limit attention (`GET /api/servers/:id/system-notifications-v2`
 * and receiver-scoped `seen` / `dismiss` receipts, `botiverse/slock` `#13079` commit `5907beadd`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemAttentionApiTest {
    private val sampleValidResponse =
        """
        {
          "contractVersion": "server-system-attention-v1",
          "generatedAt": "2026-11-05T09:28:08.000Z",
          "snapshotMode": "replace",
          "unseenCount": 1,
          "notifications": [
            {
              "id": "11111111-1111-4111-8111-111111111111",
              "kind": "usage_limit_reached",
              "severity": "warning",
              "state": "active",
              "activatedAt": "2026-11-05T09:00:00.000Z",
              "lastObservedAt": "2026-11-05T09:20:00.000Z",
              "seenAt": null,
              "machine": { "id": "machine-1", "name": "Cindy MacBook" },
              "provider": "claude",
              "windowKey": "five_hour",
              "resetsAt": "2026-11-05T14:00:00.000Z",
              "affectedAgentIds": ["agent-1", "agent-2"]
            },
            {
              "id": "22222222-2222-4222-8222-222222222222",
              "kind": "usage_limit_reached",
              "severity": "warning",
              "state": "active",
              "activatedAt": "2026-11-05T08:00:00.000Z",
              "lastObservedAt": "2026-11-05T09:15:00.000Z",
              "seenAt": "2026-11-05T09:18:00.000Z",
              "machine": { "id": "machine-2", "name": "oak-linux" },
              "provider": "antigravity",
              "windowKey": "weekly",
              "resetsAt": null,
              "affectedAgentIds": ["agent-3"]
            }
          ]
        }
        """.trimIndent()

    @Test
    fun parsesValidServerSystemAttentionPayload() {
        val result = assertIs<DataResult.Content<ServerSystemAttentionSnapshot>>(
            parseServerSystemAttentionResponse(200, sampleValidResponse)
        )
        val snapshot = result.data
        assertEquals(SERVER_SYSTEM_ATTENTION_CONTRACT_VERSION, snapshot.contractVersion)
        assertEquals("2026-11-05T09:28:08.000Z", snapshot.generatedAt)
        assertEquals("replace", snapshot.snapshotMode)
        assertEquals(1, snapshot.unseenCount)
        assertEquals(2, snapshot.notifications.size)

        val first = snapshot.notifications[0]
        assertEquals("11111111-1111-4111-8111-111111111111", first.id)
        assertEquals("usage_limit_reached", first.kind)
        assertEquals("warning", first.severity)
        assertEquals("active", first.state)
        assertNull(first.seenAt)
        assertTrue(first.unseen)
        assertEquals(UsageLimitAttentionMachine("machine-1", "Cindy MacBook"), first.machine)
        assertEquals("claude", first.provider)
        assertEquals("five_hour", first.windowKey)
        assertEquals("2026-11-05T14:00:00.000Z", first.resetsAt)
        assertEquals(listOf("agent-1", "agent-2"), first.affectedAgentIds)

        val second = snapshot.notifications[1]
        assertEquals("2026-11-05T09:18:00.000Z", second.seenAt)
        assertFalse(second.unseen)
        assertNull(second.resetsAt)
        assertEquals("antigravity", second.provider)
    }

    @Test
    fun parsesEmptyResponseAndSkipsUnknownOrMalformedItemsWithoutFailingValidSiblings() {
        val emptyJson =
            """{"contractVersion":"server-system-attention-v1","generatedAt":"2026-11-05T09:28:08.000Z","snapshotMode":"replace","unseenCount":0,"notifications":[]}"""
        val empty = assertIs<DataResult.Content<ServerSystemAttentionSnapshot>>(
            parseServerSystemAttentionResponse(200, emptyJson)
        ).data
        assertTrue(empty.notifications.isEmpty())
        assertEquals(0, empty.unseenCount)

        val mixedJson =
            """
            {
              "contractVersion": "server-system-attention-v1",
              "generatedAt": "2026-11-05T09:28:08.000Z",
              "snapshotMode": "replace",
              "unseenCount": 3,
              "notifications": [
                {
                  "id": "good-1",
                  "kind": "usage_limit_reached",
                  "seenAt": null,
                  "machine": { "id": "m-1", "name": "box-1" },
                  "provider": "codex",
                  "windowKey": "five_hour",
                  "resetsAt": null,
                  "affectedAgentIds": ["a-1", "  ", 42]
                },
                {
                  "id": "unknown-kind",
                  "kind": "future_attention_kind",
                  "seenAt": null,
                  "machine": { "id": "m-2", "name": "box-2" },
                  "provider": "claude"
                },
                {
                  "id": "missing-machine",
                  "kind": "usage_limit_reached",
                  "seenAt": null,
                  "provider": "kimi"
                },
                "not-an-object"
              ]
            }
            """.trimIndent()
        val mixed = assertIs<DataResult.Content<ServerSystemAttentionSnapshot>>(
            parseServerSystemAttentionResponse(200, mixedJson)
        ).data
        assertEquals(listOf("good-1"), mixed.notifications.map { it.id })
        assertEquals(listOf("a-1"), mixed.notifications.single().affectedAgentIds)
        // Recomputed from parsed rows so skipped items do not leave a phantom unseen count.
        assertEquals(1, mixed.unseenCount)
    }

    @Test
    fun rejectsMalformedBodiesContractMismatchAndHttpErrors() {
        val badCases =
            listOf(
                200 to null,
                200 to "",
                200 to "not json",
                200 to "[]",
                200 to """{"contractVersion":"server-system-attention-v0","notifications":[]}""",
                200 to """{"contractVersion":"server-system-attention-v1","notifications":{}}""",
                200 to """{"contractVersion":"server-system-attention-v1"}""",
                403 to """{"error":"Forbidden"}""",
                500 to """{"error":"Failed to list system attention"}""",
            )
        badCases.forEach { (code, body) ->
            assertIs<DataResult.Error>(
                parseServerSystemAttentionResponse(code, body, traceId = "trace-1"),
                "expected error for code=$code body=$body"
            )
        }

        assertIs<DataResult.Content<Unit>>(parseSystemAttentionReceiptResponse(200, """{"ok":true}""", "seen"))
        val err = assertIs<DataResult.Error>(
            parseSystemAttentionReceiptResponse(404, """{"error":"Incident not found"}""", "dismiss", traceId = "trace-404")
        )
        assertEquals("system_attention_dismiss_http_404", err.error.code)
        assertEquals("trace-404", err.error.traceId)
        assertFalse(err.error.retryable)

        val err500 = assertIs<DataResult.Error>(
            parseSystemAttentionReceiptResponse(500, """{"error":"Internal"}""", "seen")
        )
        assertTrue(err500.error.retryable)
    }

    @Test
    fun retiredFeatureFlagIsNotRegisteredAndRoleGatingMatchesOwnerAdminOnly() {
        assertFalse("usage_limit_attention_v0" in REGISTERED_SERVER_FEATURE_FLAG_KEYS)

        assertTrue(serverRoleCanReceiveSystemAttention("owner"))
        assertTrue(serverRoleCanReceiveSystemAttention("admin"))
        assertTrue(serverRoleCanReceiveSystemAttention(" OWNER "))
        assertTrue(serverRoleCanReceiveSystemAttention("Admin"))
        assertFalse(serverRoleCanReceiveSystemAttention("member"))
        assertFalse(serverRoleCanReceiveSystemAttention("guest"))
        assertFalse(serverRoleCanReceiveSystemAttention(null))
        assertFalse(serverRoleCanReceiveSystemAttention(""))
        assertFalse(serverRoleCanReceiveSystemAttention("unknown"))

        assertEquals("Claude", usageLimitProviderDisplayName("claude"))
        assertEquals("Codex", usageLimitProviderDisplayName("codex"))
        assertEquals("Kimi", usageLimitProviderDisplayName("kimi"))
        assertEquals("Grok", usageLimitProviderDisplayName("grok"))
        assertEquals("Antigravity", usageLimitProviderDisplayName("antigravity"))
        assertEquals("custom-provider", usageLimitProviderDisplayName("custom-provider"))

        assertEquals("usage-limit:inc-1", usageLimitHomeNotificationId("inc-1"))
        assertEquals("inc-1", "usage-limit:inc-1".usageLimitIdFromHomeNotificationIdOrNull())
        assertNull("usage-limit:".usageLimitIdFromHomeNotificationIdOrNull())
        assertNull("joint-invite:inc-1".usageLimitIdFromHomeNotificationIdOrNull())
    }

    @Test
    fun membersAndGuestsMakeZeroNetworkRequestsAndClearExistingCards() = runTest {
        val dataSource = FakeUsageLimitAttentionDataSource()
        val store = SharedUsageLimitAttentionStore(dataSource, this)

        // Member, guest, and missing server never hit the wire.
        store.dispatch(SharedUsageLimitAttentionIntent.Load("srv-1", canReceive = serverRoleCanReceiveSystemAttention("member")))
        store.dispatch(SharedUsageLimitAttentionIntent.Load("srv-1", canReceive = serverRoleCanReceiveSystemAttention("guest")))
        store.dispatch(SharedUsageLimitAttentionIntent.Load(null, canReceive = true))
        store.dispatch(SharedUsageLimitAttentionIntent.Refresh)
        store.dispatch(SharedUsageLimitAttentionIntent.RefreshIfStale)
        store.dispatch(SharedUsageLimitAttentionIntent.MarkSeen("inc-1"))
        store.dispatch(SharedUsageLimitAttentionIntent.Dismiss("inc-1"))
        runCurrent()

        assertEquals(0, dataSource.listCalls.size)
        assertEquals(0, dataSource.seenCalls.size)
        assertEquals(0, dataSource.dismissCalls.size)
        assertTrue(store.state.value.notifications.isEmpty())
        assertEquals(0, store.state.value.unseenCount)

        // Owner loads cards, then switching to a member server clears them with zero new requests.
        store.dispatch(SharedUsageLimitAttentionIntent.Load("srv-owner", canReceive = true))
        runCurrent()
        assertEquals(listOf(ServerId("srv-owner")), dataSource.listCalls)
        assertEquals(2, store.state.value.notifications.size)
        assertEquals(1, store.state.value.unseenCount)

        store.dispatch(SharedUsageLimitAttentionIntent.Load("srv-member", canReceive = false))
        runCurrent()
        assertEquals(listOf(ServerId("srv-owner")), dataSource.listCalls)
        assertEquals("srv-member", store.state.value.serverId)
        assertTrue(store.state.value.notifications.isEmpty())
        assertEquals(0, store.state.value.unseenCount)
        store.clear()
    }

    @Test
    fun serverSwitchDropsPreviousRowsAndLateResponsesAndFailedRefreshFailsClosed() = runTest {
        val gate = CompletableDeferred<DataResult<ServerSystemAttentionSnapshot>>()
        val dataSource = FakeUsageLimitAttentionDataSource(listGate = gate)
        val store = SharedUsageLimitAttentionStore(dataSource, this)

        store.dispatch(SharedUsageLimitAttentionIntent.Load("srv-a", canReceive = true))
        runCurrent()
        store.dispatch(SharedUsageLimitAttentionIntent.Load("srv-b", canReceive = false))
        gate.complete(DataResult.Content(snapshot(notification("late-1"))))
        runCurrent()

        assertEquals("srv-b", store.state.value.serverId)
        assertTrue(store.state.value.notifications.isEmpty())

        // Authoritative replace snapshot failure clears stale cards (fail closed, matching web).
        val failSource = FakeUsageLimitAttentionDataSource()
        val failStore = loadedStore(failSource)
        assertEquals(2, failStore.state.value.notifications.size)

        failSource.listResult = DataResult.Error(ScreenError(code = "system_attention_http_500", retryable = true))
        failStore.dispatch(SharedUsageLimitAttentionIntent.Refresh)
        runCurrent()

        assertTrue(failStore.state.value.notifications.isEmpty())
        assertEquals(0, failStore.state.value.unseenCount)
        store.clear()
        failStore.clear()
    }

    @Test
    fun markSeenUpdatesOptimisticallyAndRollsBackOnlyFailedIncidentOnError() = runTest {
        val seenGate1 = CompletableDeferred<DataResult<Unit>>()
        val seenGate2 = CompletableDeferred<DataResult<Unit>>()
        val dataSource =
            FakeUsageLimitAttentionDataSource(
                initialSnapshot = snapshot(notification("inc-1", seenAt = null), notification("inc-2", seenAt = null)),
                seenGates = mutableMapOf("inc-1" to seenGate1, "inc-2" to seenGate2),
            )
        val store = loadedStore(dataSource)
        assertEquals(2, store.state.value.unseenCount)

        // Opening Notification Center marks all unseen cards optimistically right away.
        store.dispatch(SharedUsageLimitAttentionIntent.MarkAllUnseenSeen)
        // Duplicate call while in flight sends nothing extra.
        store.dispatch(SharedUsageLimitAttentionIntent.MarkSeen("inc-1"))
        runCurrent()

        assertEquals(0, store.state.value.unseenCount)
        assertTrue(store.state.value.notifications.all { it.seenAt != null })
        assertEquals(listOf("srv-1" to "inc-1", "srv-1" to "inc-2"), dataSource.seenCalls)

        // inc-1 fails -> rolls back to unseen; inc-2 succeeds -> stays seen.
        seenGate1.complete(DataResult.Error(ScreenError(code = "system_attention_seen_http_500", retryable = true)))
        seenGate2.complete(DataResult.Content(Unit))
        runCurrent()

        assertEquals(1, store.state.value.unseenCount)
        assertNull(store.state.value.notifications.first { it.id == "inc-1" }.seenAt)
        assertTrue(store.state.value.notifications.first { it.id == "inc-2" }.seenAt != null)
        store.clear()
    }

    @Test
    fun dismissRemovesOptimisticallyAndRollsBackAtOriginalIndexOnError() = runTest {
        val dismissGate = CompletableDeferred<DataResult<Unit>>()
        val dataSource =
            FakeUsageLimitAttentionDataSource(
                initialSnapshot = snapshot(
                    notification("inc-1", seenAt = null),
                    notification("inc-2", seenAt = "2026-11-05T09:18:00.000Z"),
                ),
                dismissGate = dismissGate,
            )
        val store = loadedStore(dataSource)
        assertEquals(listOf("inc-1", "inc-2"), store.state.value.notifications.map { it.id })
        assertEquals(1, store.state.value.unseenCount)

        // Optimistic removal immediately hides inc-1 and clears unseenCount.
        store.dispatch(SharedUsageLimitAttentionIntent.Dismiss("inc-1"))
        // Second tap while in flight is ignored.
        store.dispatch(SharedUsageLimitAttentionIntent.Dismiss("inc-1"))
        runCurrent()

        assertEquals(listOf("inc-2"), store.state.value.notifications.map { it.id })
        assertEquals(0, store.state.value.unseenCount)
        assertEquals(listOf("srv-1" to "inc-1"), dataSource.dismissCalls)

        // Failure restores inc-1 at its original index (0) with unseenCount restored and emits effect.
        dismissGate.complete(DataResult.Error(ScreenError(code = "system_attention_dismiss_http_500", retryable = true, traceId = "tr-500")))
        runCurrent()

        assertEquals(listOf("inc-1", "inc-2"), store.state.value.notifications.map { it.id })
        assertEquals(1, store.state.value.unseenCount)
        val effect = assertIs<SharedUsageLimitAttentionEffect.DismissFailed>(store.state.value.effect)
        assertEquals("inc-1", effect.incidentId)
        assertEquals("tr-500", effect.traceId)
        store.dispatch(SharedUsageLimitAttentionIntent.ConsumeEffect)
        assertNull(store.state.value.effect)

        // Successful dismiss keeps the incident removed.
        val okSource = FakeUsageLimitAttentionDataSource()
        val okStore = loadedStore(okSource)
        okStore.dispatch(SharedUsageLimitAttentionIntent.Dismiss("inc-1"))
        runCurrent()
        assertEquals(listOf("inc-2"), okStore.state.value.notifications.map { it.id })
        assertNull(okStore.state.value.effect)
        store.clear()
        okStore.clear()
    }

    @Test
    fun networkDataSourceCallsExactSlockRoutesAndMethods() = runTest {
        val requests = mutableListOf<Triple<String, String, ServerId?>>()
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
                    requests += Triple(method, path, serverIdOverride)
                    return when {
                        method == "GET" -> HomeNetworkResponse(code = 200, bodyText = sampleValidResponse)
                        else -> HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
                    }
                }
            }
        val dataSource = NetworkUsageLimitAttentionDataSource(client)
        val serverId = ServerId("srv-42")
        val incidentId = "11111111-1111-4111-8111-111111111111"

        val listResult = assertIs<DataResult.Content<ServerSystemAttentionSnapshot>>(dataSource.list(serverId))
        assertEquals(2, listResult.data.notifications.size)
        assertIs<DataResult.Content<Unit>>(dataSource.markSeen(serverId, incidentId))
        assertIs<DataResult.Content<Unit>>(dataSource.dismiss(serverId, incidentId))

        assertEquals(
            listOf(
                Triple("GET", "servers/srv-42/system-notifications-v2", serverId),
                Triple("POST", "servers/srv-42/system-notifications-v2/$incidentId/seen", serverId),
                Triple("POST", "servers/srv-42/system-notifications-v2/$incidentId/dismiss", serverId),
            ),
            requests,
        )
    }

    @Test
    fun homeAudienceProjectionAndUnreadBellMappingMatchServerRoleAndSeenState() {
        val ownerContent = homeContent(serverId = "srv-1", role = "owner")
        val adminContent = homeContent(serverId = "srv-1", role = "admin")
        val memberContent = homeContent(serverId = "srv-1", role = "member")
        val guestContent = homeContent(serverId = "srv-1", role = "guest")

        assertEquals("srv-1" to true, homeUsageLimitAttentionAudience(ownerContent))
        assertEquals("srv-1" to true, homeUsageLimitAttentionAudience(adminContent))
        assertEquals("srv-1" to false, homeUsageLimitAttentionAudience(memberContent))
        assertEquals("srv-1" to false, homeUsageLimitAttentionAudience(guestContent))
        assertEquals(null to false, homeUsageLimitAttentionAudience(SharedHomeContentState.Loading()))

        val unseen = notification("inc-1", seenAt = null)
        val seen = notification("inc-2", seenAt = "2026-11-05T09:18:00.000Z")
        val state = SharedUsageLimitAttentionState(serverId = "srv-1", notifications = listOf(unseen, seen), unseenCount = 1)
        assertEquals(listOf("inc-1", "inc-2"), state.homeUsageLimitAttentionRows("srv-1").map { it.id })
        assertTrue(state.homeUsageLimitAttentionRows("srv-other").isEmpty())

        val unseenItem = unseen.toHomeNotificationItem("Claude used up", "2 agents on Cindy MacBook may pause.", "View")
        val seenItem = seen.toHomeNotificationItem("Codex used up", "1 agent on oak-linux may pause.", "View")
        assertEquals("usage-limit:inc-1", unseenItem.id)
        assertEquals("machine-inc-1", unseenItem.targetId)
        assertEquals(RaftHomeNotificationKind.Warning, unseenItem.kind)
        assertEquals(RaftHomeNotificationIcon.Default, unseenItem.icon)
        assertTrue(unseenItem.unread)
        assertFalse(seenItem.unread)

        assertTrue(listOf(unseenItem, seenItem).hasUnreadHomeNotifications())
        assertFalse(listOf(seenItem).hasUnreadHomeNotifications())
    }

    private fun TestScope.loadedStore(dataSource: FakeUsageLimitAttentionDataSource): SharedUsageLimitAttentionStore {
        val store = SharedUsageLimitAttentionStore(dataSource, this)
        store.dispatch(SharedUsageLimitAttentionIntent.Load("srv-1", canReceive = true))
        runCurrent()
        return store
    }

    private fun homeContent(serverId: String, role: String): SharedHomeContentState.Content =
        SharedHomeContentState.Content(
            snapshot =
                HomeSnapshot(
                    server = ServerSummary(id = ServerId(serverId), name = "Raft", role = role),
                    currentMember = MemberProfile(id = MemberId("u-1"), displayName = "Ada", handle = "ada", kind = MemberKind.Human),
                    channels = emptyList(),
                    directMessages = emptyList(),
                    unreadTotal = 0,
                ),
            session =
                StoredAuthSession(
                    user = StoredAuthUser(id = "u-1", email = "ada@example.com"),
                    accessToken = "tok",
                    refreshToken = "ref",
                ),
        )
}

private fun notification(
    id: String,
    seenAt: String? = null,
    resetsAt: String? = "2026-11-05T14:00:00.000Z",
): UsageLimitAttentionNotification =
    UsageLimitAttentionNotification(
        id = id,
        activatedAt = "2026-11-05T09:00:00.000Z",
        lastObservedAt = "2026-11-05T09:20:00.000Z",
        seenAt = seenAt,
        machine = UsageLimitAttentionMachine(id = "machine-$id", name = "MacBook-$id"),
        provider = "claude",
        windowKey = "five_hour",
        resetsAt = resetsAt,
        affectedAgentIds = listOf("agent-1", "agent-2"),
    )

private fun snapshot(vararg items: UsageLimitAttentionNotification): ServerSystemAttentionSnapshot =
    ServerSystemAttentionSnapshot(
        generatedAt = "2026-11-05T09:28:08.000Z",
        unseenCount = items.count { it.seenAt == null },
        notifications = items.toList(),
    )

private class FakeUsageLimitAttentionDataSource(
    initialSnapshot: ServerSystemAttentionSnapshot =
        snapshot(
            notification("inc-1", seenAt = null),
            notification("inc-2", seenAt = "2026-11-05T09:18:00.000Z"),
        ),
    private val listGate: CompletableDeferred<DataResult<ServerSystemAttentionSnapshot>>? = null,
    private val seenGates: MutableMap<String, CompletableDeferred<DataResult<Unit>>> = mutableMapOf(),
    private val dismissGate: CompletableDeferred<DataResult<Unit>>? = null,
) : UsageLimitAttentionDataSource {
    var listResult: DataResult<ServerSystemAttentionSnapshot> = DataResult.Content(initialSnapshot)
    var seenResult: DataResult<Unit> = DataResult.Content(Unit)
    var dismissResult: DataResult<Unit> = DataResult.Content(Unit)
    val listCalls = mutableListOf<ServerId>()
    val seenCalls = mutableListOf<Pair<String, String>>()
    val dismissCalls = mutableListOf<Pair<String, String>>()

    override suspend fun list(serverId: ServerId): DataResult<ServerSystemAttentionSnapshot> {
        listCalls += serverId
        return listGate?.await() ?: listResult
    }

    override suspend fun markSeen(serverId: ServerId, incidentId: String): DataResult<Unit> {
        seenCalls += serverId.value to incidentId
        return seenGates[incidentId]?.await() ?: seenResult
    }

    override suspend fun dismiss(serverId: ServerId, incidentId: String): DataResult<Unit> {
        dismissCalls += serverId.value to incidentId
        return dismissGate?.await() ?: dismissResult
    }
}
