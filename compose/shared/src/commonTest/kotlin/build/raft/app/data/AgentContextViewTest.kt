package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentContextViewTest {

    private class FakeAgentContextViewApi : RaftAgentContextViewApi {
        var statusHandler: suspend (String, ServerId?) -> HomeNetworkResponse = { _, _ ->
            HomeNetworkResponse(code = 404, bodyText = """{"error":"not_found"}""")
        }
        var viewContextHandler: suspend (String, String, ServerId?) -> HomeNetworkResponse = { _, _, _ ->
            HomeNetworkResponse(code = 404, bodyText = """{"error":"not_found"}""")
        }
        var viewedByHandler: suspend (String, String, ServerId?) -> HomeNetworkResponse = { _, _, _ ->
            HomeNetworkResponse(code = 200, bodyText = """{"views":[]}""")
        }
        var listGrantsHandler: suspend (ServerId) -> HomeNetworkResponse = {
            HomeNetworkResponse(code = 200, bodyText = """{"grants":[]}""")
        }
        var grantAccessHandler: suspend (ServerId, String) -> HomeNetworkResponse = { _, _ ->
            HomeNetworkResponse(code = 500, bodyText = null)
        }
        var revokeGrantHandler: suspend (ServerId, String) -> HomeNetworkResponse = { _, _ ->
            HomeNetworkResponse(code = 200, bodyText = """{"ok":true,"revoked":true}""")
        }

        var statusCallCount: Int = 0
        var viewContextCallCount: Int = 0
        var lastGrantBody: String? = null

        override suspend fun status(agentId: String, serverId: ServerId?): HomeNetworkResponse {
            statusCallCount += 1
            return statusHandler(agentId, serverId)
        }

        override suspend fun viewContext(agentId: String, messageId: String, serverId: ServerId?): HomeNetworkResponse {
            viewContextCallCount += 1
            return viewContextHandler(agentId, messageId, serverId)
        }

        override suspend fun viewedBy(agentId: String, messageId: String, serverId: ServerId?): HomeNetworkResponse =
            viewedByHandler(agentId, messageId, serverId)

        override suspend fun listGrants(serverId: ServerId): HomeNetworkResponse = listGrantsHandler(serverId)

        override suspend fun grantAccess(serverId: ServerId, body: String): HomeNetworkResponse {
            lastGrantBody = body
            return grantAccessHandler(serverId, body)
        }

        override suspend fun revokeGrant(serverId: ServerId, grantId: String): HomeNetworkResponse =
            revokeGrantHandler(serverId, grantId)
    }

    @Test
    fun featureFlagIsRegisteredAndDefaultsOff() {
        assertEquals("agent_context_view_v0", AGENT_CONTEXT_VIEW_FEATURE_FLAG_KEY)
        assertTrue(AGENT_CONTEXT_VIEW_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse(AGENT_CONTEXT_VIEW_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
    }

    @Test
    fun statusStoreCachesForThirtySecondsAndInvalidatesAfterTtl() = runTest {
        var now = 1_000L
        val api = FakeAgentContextViewApi()
        api.statusHandler = { _, _ ->
            HomeNetworkResponse(
                code = 200,
                bodyText = """{
                    "runtime":"claude-code",
                    "computer":{"id":"m1","name":"Studio"},
                    "computerOwner":{"id":"u1","name":"Alice"},
                    "retainedSince":"2026-11-01T00:00:00Z",
                    "retentionDays":30,
                    "state":"available"
                }""",
            )
        }
        val store = AgentContextViewStatusStore(api = api, nowMillis = { now })

        val first = store.loadStatus("a1", ServerId("s1"))
        assertEquals(1, api.statusCallCount)
        assertEquals(AgentContextViewStatusState.Available, first.status?.state)
        assertEquals(AgentContextViewMenuEntryState.ShowOpen, agentContextViewMenuEntryState(first))

        // Within 30s TTL -> cache hit, no network call.
        now += 29_999L
        val cached = store.loadStatus("a1", ServerId("s1"))
        assertEquals(1, api.statusCallCount)
        assertEquals(first, cached)

        // Past 30s TTL -> re-fetched.
        now += 1L
        store.loadStatus("a1", ServerId("s1"))
        assertEquals(2, api.statusCallCount)

        // force = true bypasses fresh cache.
        store.loadStatus("a1", ServerId("s1"), force = true)
        assertEquals(3, api.statusCallCount)
    }

    @Test
    fun statusStoreCaches404AsGoneAndHidesMenuEntry() = runTest {
        var now = 5_000L
        val api = FakeAgentContextViewApi()
        api.statusHandler = { _, _ -> HomeNetworkResponse(code = 404, bodyText = """{"error":"not_found"}""") }
        val store = AgentContextViewStatusStore(api = api, nowMillis = { now })

        val entry = store.loadStatus("a1", ServerId("s1"))
        assertTrue(entry.gone)
        assertFalse(entry.failed)
        assertNull(entry.status)
        assertEquals(AgentContextViewMenuEntryState.Hidden, agentContextViewMenuEntryState(entry))
        assertEquals(AgentContextViewBodyState.Gone, agentContextViewBodyState(entry))

        now += 10_000L
        store.loadStatus("a1", ServerId("s1"))
        assertEquals(1, api.statusCallCount)
    }

    @Test
    fun statusStoreDoesNotCacheFailuresAndExposesMenuRetry() = runTest {
        var now = 10_000L
        val api = FakeAgentContextViewApi()
        api.statusHandler = { _, _ ->
            HomeNetworkResponse(
                code = 503,
                bodyText = null,
                responseHeaders = mapOf("X-Trace-Id" to listOf("0123456789abcdef0123456789abcdef")),
            )
        }
        val store = AgentContextViewStatusStore(api = api, nowMillis = { now })

        val failed = store.loadStatus("a1", ServerId("s1"))
        assertTrue(failed.failed)
        assertFalse(failed.gone)
        assertFalse(failed.isFresh(now))
        assertEquals("0123456789abcdef0123456789abcdef", failed.traceId)
        assertEquals(AgentContextViewMenuEntryState.ShowRetry(retrying = false), agentContextViewMenuEntryState(failed))

        val body = assertIs<AgentContextViewBodyState.Banner>(agentContextViewBodyState(failed))
        assertEquals(AgentContextViewBannerReason.StatusFailed, body.reason)
        assertTrue(body.retry)
        assertEquals("0123456789abcdef0123456789abcdef", body.traceId)

        // Not cached -> next call hits the network again.
        store.loadStatus("a1", ServerId("s1"))
        assertEquals(2, api.statusCallCount)
    }

    @Test
    fun statusStoreDeduplicatesConcurrentInFlightRequests() = runTest {
        val gate = CompletableDeferred<HomeNetworkResponse>()
        val api = FakeAgentContextViewApi()
        api.statusHandler = { _, _ -> gate.await() }
        val store = AgentContextViewStatusStore(api = api, nowMillis = { 100L })

        val first = async { store.loadStatus("a1", ServerId("s1")) }
        val second = async { store.loadStatus("a1", ServerId("s1")) }
        testScheduler.runCurrent()
        assertEquals(1, api.statusCallCount)

        gate.complete(
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"runtime":"codex","state":"unavailable","reason":"offline","computer":{"id":"m1","name":"Mini"}}""",
            )
        )
        val r1 = first.await()
        val r2 = second.await()
        assertEquals(r1, r2)
        assertEquals(
            AgentContextViewStatusState.Unavailable(AgentContextViewStatusReason.Offline),
            r1.status?.state,
        )
        assertEquals(AgentContextViewMenuEntryState.ShowOpen, agentContextViewMenuEntryState(r1))
    }

    @Test
    fun unsupportedRuntimeHidesMenuEntryButOfflineShowsOpen() {
        val unsupported =
            AgentContextViewStatusEntry(
                status =
                    AgentContextViewStatus(
                        runtime = "external",
                        computer = null,
                        computerOwner = null,
                        retainedSince = null,
                        state = AgentContextViewStatusState.Unavailable(AgentContextViewStatusReason.UnsupportedRuntime),
                    ),
                atMillis = 100L,
            )
        assertEquals(AgentContextViewMenuEntryState.Hidden, agentContextViewMenuEntryState(unsupported))
        assertEquals(
            AgentContextViewBodyState.Empty(AgentContextViewEmptyReason.UnsupportedRuntime),
            agentContextViewBodyState(unsupported),
        )

        val offline =
            unsupported.copy(
                status =
                    unsupported.status!!.copy(
                        state = AgentContextViewStatusState.Unavailable(AgentContextViewStatusReason.Offline),
                    )
            )
        assertEquals(AgentContextViewMenuEntryState.ShowOpen, agentContextViewMenuEntryState(offline))
        val offlineBody = assertIs<AgentContextViewBodyState.Banner>(agentContextViewBodyState(offline))
        assertEquals(AgentContextViewBannerReason.Offline, offlineBody.reason)
        assertTrue(offlineBody.retry)
    }

    @Test
    fun parseContextViewAnswerHandlesFullPayloadUnknownPartAndTriggerCrossCheck() {
        val json = """{
            "ok": true,
            "context": {
                "runtime": "claude-code",
                "model": "claude-opus-4-6",
                "sessionId": "sess-1",
                "requestAt": "2026-11-25T10:00:00Z",
                "messageId": "m-1",
                "tokens": {"input": 1200, "cacheRead": 800, "total": 2000},
                "system": [{"text": "You are Lagoon."}],
                "tools": [{"name": "Bash", "description": "Run shell commands", "inputSchema": {"type": "object"}}],
                "messages": [
                    {"role": "user", "parts": [{"kind": "text", "text": "[target=#general msg=abcd1234] hi"}]},
                    {"role": "assistant", "parts": [
                        {"kind": "future_part_kind", "foo": "bar"},
                        {"kind": "tool_use", "toolName": "Bash", "toolUseId": "call-1", "input": {"command": "echo ok"}},
                        {"kind": "tool_use", "toolName": "Bash", "toolUseId": "call-send", "input": {"command": "raft message send --target #general"}}
                    ]},
                    {"role": "user", "parts": [
                        {"kind": "tool_result", "toolName": "Bash", "toolUseId": "call-1", "output": "ok\n", "isError": false}
                    ]}
                ],
                "compaction": {"happened": true, "summary": {"kind": "text", "text": "Earlier work summarized"}},
                "trigger": {"messageIndex": 1, "partIndex": 2, "toolUseId": "call-send"},
                "redactions": {"github_token": 2},
                "source": {"kind": "native", "format": "anthropic", "version": "1.25.15"},
                "verification": "verified"
            }
        }"""

        val answer = assertIs<AgentContextViewAnswer.Ok>(parseAgentContextViewAnswer(json))
        val ctx = answer.context
        assertEquals("claude-code", ctx.runtime)
        assertEquals("claude-opus-4-6", ctx.model)
        assertEquals(2000L, ctx.tokens.total)
        assertEquals(800L, ctx.tokens.cacheRead)
        assertEquals(1, ctx.system.size)
        assertEquals(1, ctx.tools.size)
        assertNotNull(ctx.tools.single().inputSchemaJson)
        assertEquals(3, ctx.messages.size)
        // Unknown part preserved so trigger.partIndex = 2 stays aligned!
        val unknownPart = assertIs<AgentContextViewPart.Unknown>(ctx.messages[1].parts[0])
        assertEquals("future_part_kind", unknownPart.originalKind)
        assertEquals(AgentContextViewTrigger(1, 2, "call-send"), ctx.trigger)
        assertEquals(mapOf(AgentContextViewRedactionKind.GithubToken to 2), ctx.redactions)
        assertEquals(AgentContextViewVerification.Verified, ctx.verification)
        assertIs<AgentContextViewCompaction.Happened>(ctx.compaction)

        // Tool pairing pairs call-1 with message 2's tool_result, excluding trigger call-send.
        val pairs = buildAgentContextViewToolPairs(ctx)
        assertEquals(1, pairs.absorbedInto[2])
        assertEquals(AgentContextViewPartPlace(2, 0), pairs.resultOf["1:1"])
        assertNull(pairs.resultOf["1:2"])

        // Filter buckets & search.
        val rowsByFilter = buildAgentContextViewRowsByFilter(ctx, pairs)
        assertEquals(1, rowsByFilter[AgentContextViewMessageFilter.Notices]?.size)
        assertEquals(1, rowsByFilter[AgentContextViewMessageFilter.Tools]?.size)
        val matches = findAgentContextViewMatchingRows(ctx, rowsByFilter.getValue(AgentContextViewMessageFilter.All), "echo ok")
        assertEquals(listOf(1), matches)
    }

    @Test
    fun parseContextViewAnswerRejectsImageBytesAndMalformedJsonAsReadFailed() {
        val imageBytesJson = """{
            "ok": true,
            "context": {
                "runtime": "claude-code",
                "model": "claude-opus-4-6",
                "sessionId": "s1",
                "requestAt": "2026-11-26T10:00:00Z",
                "messageId": "m1",
                "tokens": {"input": 10, "cacheRead": 0, "total": 10},
                "system": [],
                "tools": [],
                "messages": [
                    {"role": "user", "parts": [{"kind": "image", "mediaType": "image/png", "sizeBytes": 128, "data": "iVBORw0KGgo="}]}
                ],
                "compaction": {"happened": false},
                "trigger": null,
                "redactions": {},
                "source": {"kind": "native", "format": "anthropic", "version": null}
            }
        }"""
        val rejected = assertIs<AgentContextViewAnswer.Failed>(parseAgentContextViewAnswer(imageBytesJson, traceId = "abc"))
        assertEquals(AgentContextViewFailureReason.ReadFailed, rejected.reason)
        assertEquals("abc", rejected.traceId)

        val truncated = assertIs<AgentContextViewAnswer.Failed>(parseAgentContextViewAnswer("""{"ok":true,"context":{""", traceId = "def"))
        assertEquals(AgentContextViewFailureReason.ReadFailed, truncated.reason)
        assertEquals("def", truncated.traceId)
    }

    @Test
    fun parseContextViewAnswerMapsTooLargeCausesAndFailures() {
        for ((wire, expected) in listOf(
            "session_file" to AgentContextViewTooLargeCause.SessionFile,
            "request" to AgentContextViewTooLargeCause.Request,
            "restore_limit" to AgentContextViewTooLargeCause.RestoreLimit,
        )) {
            val failed =
                assertIs<AgentContextViewAnswer.Failed>(
                    parseAgentContextViewAnswer("""{"ok":false,"reason":"too_large","tooLargeCause":"$wire"}""")
                )
            assertEquals(AgentContextViewFailureReason.TooLarge, failed.reason)
            assertEquals(expected, failed.tooLargeCause)
        }
    }

    @Test
    fun repositoryNeverCachesContextAndMarksGoneOn404AndPatchesOwnViewedBy() = runTest {
        val api = FakeAgentContextViewApi()
        api.viewContextHandler = { _, _, _ -> HomeNetworkResponse(code = 404, bodyText = null) }
        api.viewedByHandler = { _, _, _ ->
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"views":[
                    {"viewer":{"id":"a-auditor","name":"AuditBot"},"viewerKind":"agent","at":"2026-11-26T10:02:00Z","outcome":"ok"},
                    {"viewer":{"id":"u-me","name":"Artin"},"at":"2026-11-26T10:01:00Z","outcome":"expired"},
                    {"viewer":null,"at":"2026-11-26T10:00:00Z","outcome":"offline"}
                ]}""",
            )
        }
        val statusStore = AgentContextViewStatusStore(api = api, nowMillis = { 1_000L })
        val repo = AgentContextViewRepository(api = api, statusStore = statusStore)

        val gone = repo.fetchContext("a1", "m1", ServerId("s1"))
        assertEquals(AgentContextViewAnswer.Gone, gone)
        assertTrue(statusStore.entryFor("a1", ServerId("s1"))?.gone == true)

        // Second call still hits the network (context payloads are never cached).
        repo.fetchContext("a1", "m1", ServerId("s1"))
        assertEquals(2, api.viewContextCallCount)

        val views = repo.fetchViewedBy("a1", "m1", viewerUserId = "u-me", ownOutcome = AgentContextViewOutcome.Ok, serverId = ServerId("s1"))
        assertNotNull(views)
        assertEquals(3, views.size)
        assertTrue(views[0].viewerIsAgent)
        assertEquals(AgentContextViewOutcome.Ok, views[1].outcome)
        assertNull(views[2].viewer)
        assertEquals(AgentContextViewOutcome.Offline, views[2].outcome)
    }

    @Test
    fun grantsRepositoryParsesUnusableReasonsGrantsAndRevoke404Semantics() = runTest {
        val api = FakeAgentContextViewApi()
        api.listGrantsHandler = {
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"grants":[
                    {"id":"g1","agent":{"id":"a1","name":"Lagoon"},"granter":{"id":"u1","name":"Artin"},"grantedAt":"2026-11-20T08:00:00Z"},
                    {"id":"g2","agent":{"id":"a2","name":"Scout"},"granter":null,"grantedAt":"2026-11-21T09:00:00Z","usable":false,"unusableReason":"granter_gone"},
                    {"id":"g3","agent":{"id":"a3","name":"Triage"},"granter":{"id":"u2","name":"Bob"},"grantedAt":"2026-11-22T09:00:00Z","usable":false,"unusableReason":"granter_not_admin"},
                    {"id":"g4","agent":{"id":"a4","name":"Helper"},"granter":{"id":"u3","name":"Cara"},"grantedAt":"2026-11-23T09:00:00Z","usable":false,"unusableReason":"agent_ineligible"}
                ]}""",
            )
        }
        val repo = AgentContextViewGrantsRepository(api)
        val listed = assertIs<DataResult.Content<List<AgentContextViewGrant>>>(repo.listGrants(ServerId("s1"))).data
        assertEquals(4, listed.size)
        assertTrue(listed[0].usable)
        assertNull(listed[0].unusableReason)
        assertFalse(listed[1].usable)
        assertNull(listed[1].granter)
        assertEquals(AgentContextViewGrantUnusableReason.GranterGone, listed[1].unusableReason)
        assertEquals(AgentContextViewGrantUnusableReason.GranterNotAdmin, listed[2].unusableReason)
        assertEquals(AgentContextViewGrantUnusableReason.AgentIneligible, listed[3].unusableReason)

        // Grant POST { agentId }
        api.grantAccessHandler = { _, _ ->
            HomeNetworkResponse(
                code = 201,
                bodyText = """{"grant":{"id":"g5","agent":{"id":"a5","name":"NewBot"},"granter":{"id":"u1","name":"Artin"},"grantedAt":"2026-11-26T10:00:00Z","usable":true},"created":true}""",
            )
        }
        val created = assertIs<DataResult.Content<AgentContextViewGrantCreated>>(repo.grantAccess(ServerId("s1"), "a5")).data
        assertTrue(created.created)
        assertEquals("g5", created.grant.id)
        assertEquals("""{"agentId":"a5"}""", api.lastGrantBody)

        // Revoke 404 context_view_grant_not_found -> Gone; other 404 -> Error
        api.revokeGrantHandler = { _, _ ->
            HomeNetworkResponse(
                code = 404,
                bodyText = null,
                errorBodyText = """{"error":"Grant not found","code":"context_view_grant_not_found"}""",
            )
        }
        val gone = assertIs<DataResult.Content<AgentContextViewGrantRevokeOutcome>>(repo.revokeGrant(ServerId("s1"), "g1")).data
        assertEquals(AgentContextViewGrantRevokeOutcome.Gone, gone)

        api.revokeGrantHandler = { _, _ ->
            HomeNetworkResponse(
                code = 404,
                bodyText = null,
                errorBodyText = """{"error":"Unavailable","code":"context_view_grants_unavailable"}""",
            )
        }
        val err = assertIs<DataResult.Error>(repo.revokeGrant(ServerId("s1"), "g1"))
        assertEquals("context_view_grants_unavailable", err.error.code)
    }

    @Test
    fun grantCandidateEligibilityExcludesPersonalAssistantsAppBotsDeletedAndGranted() {
        val granted = setOf("a-granted")
        assertTrue(isEligibleContextViewGrantCandidate("a-ok", true, false, false, null, granted))
        assertFalse(isEligibleContextViewGrantCandidate("a-granted", true, false, false, null, granted))
        assertFalse(isEligibleContextViewGrantCandidate("a-pa", true, false, true, null, granted))
        assertFalse(isEligibleContextViewGrantCandidate("a-app", true, false, false, "install-1", granted))
        assertFalse(isEligibleContextViewGrantCandidate("a-del", true, true, false, null, granted))
        assertFalse(isEligibleContextViewGrantCandidate("u-human", false, false, false, null, granted))
    }
}
