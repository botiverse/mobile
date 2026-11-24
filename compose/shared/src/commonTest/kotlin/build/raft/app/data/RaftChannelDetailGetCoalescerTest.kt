@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftWireTransferProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ChannelUuidA = "550e8400-e29b-41d4-a716-446655440001"
private const val ChannelUuidB = "550e8400-e29b-41d4-a716-446655440002"

class RaftChannelDetailGetCoalescerTest {
    private class ScriptedExecutor : RaftHttpExecutor {
        data class RecordedCall(
            val method: String,
            val url: String,
            val serverId: String?,
            val authorization: String?,
        )

        val calls = mutableListOf<RecordedCall>()
        var nextGate: CompletableDeferred<Unit>? = null
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        var respond: (RecordedCall, Int) -> RaftHttpExecutorResponse = { _, index ->
            RaftHttpExecutorResponse(code = 200, bodyText = """{"id":"$ChannelUuidA","seq":$index}""", errorBodyText = null)
        }

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean,
        ): RaftHttpExecutorResponse {
            val call = RecordedCall(method, url, headers["X-Server-Id"], headers["Authorization"])
            calls += call
            val index = calls.size
            val gate = gates.removeFirstOrNull() ?: nextGate
            gate?.await()
            return respond(call, index)
        }

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((RaftWireTransferProgress) -> Unit)?,
        ): RaftHttpExecutorResponse = error("unused")
    }

    private fun session(
        userId: String = "user-1",
        accessToken: String = "token-1",
        selectedServerId: String = "server-1",
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@raft.build"),
            accessToken = accessToken,
            refreshToken = "refresh-$accessToken",
            selectedServerId = selectedServerId,
            servers = listOf(
                StoredAuthServer(id = "server-1", name = "One"),
                StoredAuthServer(id = "server-2", name = "Two"),
            ),
        )

    private fun TestScope.createClient(
        executor: RaftHttpExecutor,
        sessionStore: RaftAuthSessionStore = InMemoryRaftAuthSessionStore(session()),
        baseUrlProvider: () -> String = { "https://api.raft.build/api" },
        coalescer: RaftChannelDetailGetCoalescer = RaftChannelDetailGetCoalescer(nowMillis = { testScheduler.currentTime }),
    ): SharedRaftNetworkClient =
        SharedRaftNetworkClient(
            sessionStore = sessionStore,
            baseUrlProvider = baseUrlProvider,
            executor = executor,
            inFlightGets = RaftInFlightGetDedupe(nowMillis = { testScheduler.currentTime }),
            channelDetailCoalescer = coalescer,
        )

    @Test
    fun pathHelpersMatchCanonicalChannelUuidsOnly() {
        assertEquals(ChannelUuidA, extractPlainChannelDetailId("channels/$ChannelUuidA"))
        assertEquals(ChannelUuidA, extractPlainChannelDetailId("/channels/${ChannelUuidA.uppercase()}"))
        assertNull(extractPlainChannelDetailId("channels/$ChannelUuidA/members"))
        assertNull(extractPlainChannelDetailId("channels/dm"))
        assertNull(extractPlainChannelDetailId("channels/unread"))
        assertNull(extractPlainChannelDetailId("channels/inbox"))
        assertNull(extractPlainChannelDetailId("channels/channel-1"))
        assertEquals(ChannelUuidA, extractMutatedChannelId("channels/$ChannelUuidA"))
        assertEquals(ChannelUuidA, extractMutatedChannelId("channels/$ChannelUuidA/read"))
        assertEquals(ChannelUuidA, extractMutatedChannelId("/channels/${ChannelUuidA.uppercase()}/members/user/u1"))
        assertNull(extractMutatedChannelId("channels/threads/unfollow"))
        assertTrue(isChannelRoutePath("channels/threads/unfollow"))
        assertTrue(!isChannelRoutePath("messages/$ChannelUuidA/link-cards/read"))
    }

    @Test
    fun fourConcurrentChannelGetsCoalesceIntoOneHttpCallAcrossServerOverrideAndPriority() = runTest {
        val gate = CompletableDeferred<Unit>()
        val executor = ScriptedExecutor().apply { nextGate = gate }
        val coalescer = RaftChannelDetailGetCoalescer(nowMillis = { testScheduler.currentTime })
        val client = createClient(executor, coalescer = coalescer)

        val calls =
            listOf(
                async { client.request(DataRequestPriority.Foreground, "channels/$ChannelUuidA", serverIdOverride = ServerId("server-1")) },
                async { client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA", serverIdOverride = null) },
                async { client.request(DataRequestPriority.Background, "/channels/${ChannelUuidA.uppercase()}", serverIdOverride = ServerId("server-1")) },
                async {
                    client.requestWithHeaders(
                        DataRequestPriority.Visible,
                        "channels/$ChannelUuidA",
                        headers = mapOf("X-Raft-Fetch-Reason" to "initial"),
                        serverIdOverride = ServerId("server-1"),
                    )
                },
            )
        runCurrent()
        assertEquals(1, executor.calls.size, "4 concurrent callers must share 1 HTTP call: ${executor.calls}")
        gate.complete(Unit)
        val responses = calls.awaitAll()
        assertTrue(responses.all { it.code == 200 && it.bodyText == """{"id":"$ChannelUuidA","seq":1}""" })
        assertEquals(1L, coalescer.sent)
        assertEquals(3L, coalescer.joined)
    }

    @Test
    fun shortTtlReuses200SnapshotAndRefetchesAfterExpiry() = runTest {
        val executor = ScriptedExecutor()
        val coalescer = RaftChannelDetailGetCoalescer(ttlMillis = 3_000L, nowMillis = { testScheduler.currentTime })
        val client = createClient(executor, coalescer = coalescer)

        val first = client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA", serverIdOverride = ServerId("server-1"))
        assertEquals("""{"id":"$ChannelUuidA","seq":1}""", first.bodyText)

        // Within 3s TTL (even across unrelated POST messages/:id/link-cards/read): cache hit!
        advanceTimeBy(1_000L)
        client.request(DataRequestPriority.Visible, "messages/$ChannelUuidA/link-cards/read", method = "POST", bodyText = "{}")
        advanceTimeBy(1_999L)
        val cached = client.request(DataRequestPriority.Background, "channels/$ChannelUuidA")
        assertEquals(first.bodyText, cached.bodyText)
        assertEquals(1, executor.calls.count { it.method == "GET" })
        assertEquals(1L, coalescer.cacheHits)

        // At 3_000ms: expired -> refetches.
        advanceTimeBy(1L)
        val refetched = client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA", serverIdOverride = ServerId("server-1"))
        assertEquals(2, executor.calls.count { it.method == "GET" })
        assertEquals("""{"id":"$ChannelUuidA","seq":3}""", refetched.bodyText)
    }

    @Test
    fun channelMutationsEvictCacheAndInvalidatePreMutationInFlightReads() = runTest {
        val executor = ScriptedExecutor()
        val coalescer = RaftChannelDetailGetCoalescer(ttlMillis = 3_000L, nowMillis = { testScheduler.currentTime })
        val client = createClient(executor, coalescer = coalescer)

        // Seed cache for A and B.
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA")
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidB")
        assertEquals(2, executor.calls.size)

        // PATCH channels/A evicts A but leaves B cached.
        client.request(DataRequestPriority.Foreground, "channels/$ChannelUuidA", method = "PATCH", bodyText = """{"name":"renamed"}""")
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidB")
        assertEquals(2, executor.calls.count { it.method == "GET" }, "B stays cached when A is patched")
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA")
        assertEquals(3, executor.calls.count { it.method == "GET" }, "A refetches after PATCH")

        // POST channels/A/read evicts A.
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA/read", method = "POST", bodyText = "{}")
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA")
        assertEquals(4, executor.calls.count { it.method == "GET" }, "A refetches after POST channels/A/read")

        // DELETE channels/A evicts A.
        client.request(DataRequestPriority.Foreground, "channels/$ChannelUuidA", method = "DELETE")
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA")
        assertEquals(5, executor.calls.count { it.method == "GET" }, "A refetches after DELETE")

        // Pre-mutation in-flight GET must NEVER populate cache or be joined after mutation.
        advanceTimeBy(5_000L)
        val preMutationGate = CompletableDeferred<Unit>()
        val postMutationGate = CompletableDeferred<Unit>()
        executor.gates += preMutationGate
        val preMutationGet = async { client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA") }
        runCurrent()
        val getsBeforeMutation = executor.calls.count { it.method == "GET" }

        // Mutation completes while preMutationGet is still suspended.
        client.request(DataRequestPriority.Foreground, "channels/$ChannelUuidA", method = "PUT", bodyText = """{"topic":"new"}""")

        // Post-mutation GET must NOT join preMutationGet.
        executor.gates += postMutationGate
        val postMutationGet = async { client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA") }
        runCurrent()
        assertEquals(getsBeforeMutation + 1, executor.calls.count { it.method == "GET" }, "post-mutation GET must not join pre-mutation flight")

        // Complete pre-mutation GET first: it must NOT overwrite cache with stale pre-mutation snapshot.
        preMutationGate.complete(Unit)
        preMutationGet.await()
        postMutationGate.complete(Unit)
        val fresh = postMutationGet.await()
        val afterFresh = client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA")
        assertEquals(fresh.bodyText, afterFresh.bodyText)
        assertEquals(getsBeforeMutation + 1, executor.calls.count { it.method == "GET" })
    }

    @Test
    fun non200ResponsesAreNeverCached() = runTest {
        var nextCode = 500
        val executor = ScriptedExecutor().apply {
            respond = { _, index ->
                RaftHttpExecutorResponse(
                    code = nextCode,
                    bodyText = if (nextCode == 200) """{"ok":$index}""" else null,
                    errorBodyText = if (nextCode == 200) null else """{"error":"err_$nextCode"}""",
                )
            }
        }
        val client = createClient(executor)

        for (code in listOf(500, 404, 403, 204, 0)) {
            nextCode = code
            val res = client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA")
            assertEquals(code, res.code)
        }
        nextCode = 200
        val ok = client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA")
        assertEquals(200, ok.code)
        assertEquals(6, executor.calls.size, "none of the non-200 responses may be cached")
    }

    @Test
    fun serverSessionBaseUrlAndAuthorityChangesIsolateEntries() = runTest {
        val sessionStore = InMemoryRaftAuthSessionStore(session(userId = "user-1", accessToken = "tok-1", selectedServerId = "server-1"))
        var currentBaseUrl = "https://api.raft.build/api"
        val executor = ScriptedExecutor()
        val client = createClient(executor, sessionStore = sessionStore, baseUrlProvider = { currentBaseUrl })

        // 1. server-1 vs server-2 isolation.
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA", serverIdOverride = ServerId("server-1"))
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA", serverIdOverride = ServerId("server-2"))
        assertEquals(2, executor.calls.size)

        // 2. Session change (new account / token) misses previous account's cache.
        sessionStore.saveSession(session(userId = "user-2", accessToken = "tok-2", selectedServerId = "server-1"))
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA", serverIdOverride = ServerId("server-1"))
        assertEquals(3, executor.calls.size)

        // 3. BaseUrl change isolates environment.
        currentBaseUrl = "https://staging.raft.build/api"
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA", serverIdOverride = ServerId("server-1"))
        assertEquals(4, executor.calls.size)

        // 4. Stale expectedAuthority returns 409 and never serves cached 200.
        val staleBinding =
            RaftExpectedAuthBinding(
                accountId = "user-1",
                accessToken = "tok-1",
                refreshToken = "refresh-tok-1",
                serverId = "server-1",
                environmentId = "https://staging.raft.build/api",
            )
        val rejected = client.requestBound(DataRequestPriority.Visible, "channels/$ChannelUuidA", expectedAuthority = staleBinding)
        assertEquals(409, rejected.code)
        assertEquals(4, executor.calls.size)

        // 5. Query params or static routes bypass channel detail TTL cache.
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA", query = listOf("include" to "members"))
        client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA", query = listOf("include" to "members"))
        client.request(DataRequestPriority.Visible, "channels/dm")
        client.request(DataRequestPriority.Visible, "channels/dm")
        assertEquals(8, executor.calls.size)
    }

    @Test
    fun callerCancellationNeverBreaksSiblingWaitersOrSender() = runTest {
        // Case 1: Sender cancels while waiter is waiting -> waiter retries and still gets 200.
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val executor = ScriptedExecutor().apply {
            gates += gate1
            gates += gate2
        }
        val client = createClient(executor)

        val sender = launch { client.request(DataRequestPriority.Visible, "channels/$ChannelUuidA") }
        runCurrent()
        val waiter = async { client.request(DataRequestPriority.Foreground, "channels/$ChannelUuidA") }
        runCurrent()
        assertEquals(1, executor.calls.size)

        sender.cancel()
        runCurrent()
        assertEquals(2, executor.calls.size, "waiter must transparently retry when sender cancels")
        gate2.complete(Unit)
        assertEquals(200, waiter.await().code)

        // Case 2: Waiter cancels while sender runs -> sender finishes normally with 1 HTTP call.
        advanceTimeBy(5_000L)
        val gate3 = CompletableDeferred<Unit>()
        executor.gates += gate3
        val sender2 = async { client.request(DataRequestPriority.Visible, "channels/$ChannelUuidB") }
        runCurrent()
        val waiter2 = launch { client.request(DataRequestPriority.Foreground, "channels/$ChannelUuidB") }
        runCurrent()
        assertEquals(3, executor.calls.size)

        waiter2.cancel()
        runCurrent()
        gate3.complete(Unit)
        assertEquals(200, sender2.await().code)
        assertEquals(3, executor.calls.size)
    }
}
