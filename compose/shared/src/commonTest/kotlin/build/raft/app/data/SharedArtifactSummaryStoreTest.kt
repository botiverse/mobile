package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `POST /api/objects/summaries` for Artifacts (botiverse/slock feat/workspace-artifacts 542e922d6,
 * `packages/shared/src/webObjectsContract.ts`, `workspaceArtifacts.ts` `ArtifactCard`; the payload
 * shape below follows `packages/server/src/apps/artifacts/webCards.ts`), and the store's batching,
 * dedupe, cap and TTLs (web `objectSummaryLoader.ts`).
 */
class SharedArtifactSummaryStoreTest {
    private val server = "11111111-1111-4111-8111-111111111111"

    private fun card(id: String, version: Int = 3, pinned: Boolean = false, title: String = "Q3 report") =
        """
        {"id":"$id","url":"https://app.raft.build/artifacts/$id","title":"$title","description":null,"kind":"html",
         "facts":{"sizeBytes":2048,"fileCount":1,"pages":2},"preview":{"kind":"excerpt","text":"Revenue grew"},
         "version":$version,"pinned":$pinned,"latestVersion":4,
         "author":{"type":"agent","id":"a1","name":"lagoon","displayName":"Lagoon","avatarUrl":null},
         "createdAt":"2026-10-20T10:00:00.000Z","updatedAt":"2026-10-21T10:00:00.000Z","commentCount":2,
         "home":{"kind":"conversation","conversation":{"id":"c1","name":"proj-workspace","type":"channel"}}}
        """.trimIndent()

    private class FakeApi(private val answer: (List<ArtifactLinkTarget>) -> HomeNetworkResponse) : RaftArtifactSummariesApi {
        val calls = mutableListOf<Pair<String, List<ArtifactLinkTarget>>>()
        override suspend fun summaries(serverId: String, targets: List<ArtifactLinkTarget>): HomeNetworkResponse {
            calls += serverId to targets
            return answer(targets)
        }
    }

    private fun okAnswer(targets: List<ArtifactLinkTarget>, unavailable: Set<String> = emptySet()): HomeNetworkResponse {
        val entries =
            targets.joinToString(",") { target ->
                val ref = if (target.version == null) """{"id":"${target.id}"}""" else """{"id":"${target.id}","version":${target.version}}"""
                if (target.id in unavailable) """{"ref":$ref,"ok":false,"reason":"unavailable"}"""
                else """{"ref":$ref,"ok":true,"summary":${card(target.id, target.version ?: 4, target.version != null)}}"""
            }
        return HomeNetworkResponse(200, """{"kind":"artifact","summaries":[$entries]}""")
    }

    private fun TestScope.store(api: FakeApi) =
        SharedArtifactSummaryStore(api = api, scope = backgroundScope, nowMillis = { testScheduler.currentTime })

    @Test
    fun requestBodyIsTheContractShape() {
        val body = Json.parseToJsonElement(encodeArtifactSummariesRequest(listOf(ArtifactLinkTarget("pt_a"), ArtifactLinkTarget("pt_b", 2)))).jsonObject
        assertEquals("artifact", body["kind"]?.jsonPrimitive?.content)
        val refs = body["refs"]!!.jsonArray.map { it as JsonObject }
        assertEquals(setOf("id"), refs[0].keys, "an unpinned ref has no version key")
        assertEquals("pt_b", refs[1]["id"]?.jsonPrimitive?.content)
        assertEquals("2", refs[1]["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun parsesOkAndUnavailableAndAnUnreadableSummaryAsUnavailable() {
        val answers =
            parseArtifactSummariesAnswer(
                """
                {"kind":"artifact","summaries":[
                  {"ref":{"id":"pt_a"},"ok":true,"summary":${card("pt_a")}},
                  {"ref":{"id":"pt_a","version":2},"ok":true,"summary":${card("pt_a", 2, true)}},
                  {"ref":{"id":"pt_gone"},"ok":false,"reason":"unavailable"},
                  {"ref":{"id":"pt_bad"},"ok":true,"summary":{"id":"pt_bad"}}
                ]}
                """.trimIndent(),
            )!!
        val latest = assertIs<ArtifactSummaryAnswer.Ok>(answers["pt_a"]).summary
        assertEquals("Q3 report", latest.title)
        assertEquals(ArtifactKind.Html, latest.kind)
        assertEquals(ArtifactHome.Conversation("proj-workspace", dm = false), latest.home)
        assertEquals("Revenue grew", latest.description, "no description: the excerpt")
        assertEquals(2, assertIs<ArtifactSummaryAnswer.Ok>(answers["pt_a;version=2"]).summary.version)
        assertEquals(ArtifactSummaryAnswer.Unavailable, answers["pt_gone"])
        assertEquals(ArtifactSummaryAnswer.Unavailable, answers["pt_bad"])
        assertEquals(null, parseArtifactSummariesAnswer("""{"previews":[]}"""))
    }

    @Test
    fun lookupsInOneWindowGoOutAsOneDedupedRequest() = runTest {
        val api = FakeApi { okAnswer(it, unavailable = setOf("pt_gone")) }
        val store = store(api)
        store.request(server, listOf(ArtifactLinkTarget("pt_a"), ArtifactLinkTarget("pt_gone")))
        store.request(server, listOf(ArtifactLinkTarget("pt_a"), ArtifactLinkTarget("pt_a", 2)))
        runCurrent()
        assertEquals(0, api.calls.size, "waits for the batch window")
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertEquals(1, api.calls.size)
        assertEquals(listOf("pt_a", "pt_gone", "pt_a;version=2"), api.calls.single().second.map { it.key })
        val answers = store.state.value.answersFor(server, listOf(ArtifactLinkTarget("pt_a"), ArtifactLinkTarget("pt_gone")))
        assertIs<ArtifactSummaryAnswer.Ok>(answers["pt_a"])
        assertEquals(ArtifactSummaryAnswer.Unavailable, answers["pt_gone"])
        // Kept: asking again within the TTL sends nothing.
        store.request(server, listOf(ArtifactLinkTarget("pt_a")))
        advanceTimeBy(1_000)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun moreThanFiftyReferencesSplitIntoRequestsOfFifty() = runTest {
        val api = FakeApi { okAnswer(it) }
        val store = store(api)
        store.request(server, (1..120).map { ArtifactLinkTarget("pt_$it") })
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertEquals(listOf(50, 50, 20), api.calls.map { it.second.size })
    }

    @Test
    fun unavailableExpiresSoonerThanASummaryAndAFailureKeepsNothing() = runTest {
        var fail = false
        val api = FakeApi { if (fail) HomeNetworkResponse(429, null) else okAnswer(it, unavailable = setOf("pt_gone")) }
        val store = store(api)
        val both = listOf(ArtifactLinkTarget("pt_a"), ArtifactLinkTarget("pt_gone"))
        store.request(server, both)
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        advanceTimeBy(ARTIFACT_SUMMARY_UNAVAILABLE_TTL_MILLIS)
        store.request(server, both)
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertEquals(listOf("pt_gone"), api.calls.last().second.map { it.key }, "only the expired unavailable is asked again")
        fail = true
        advanceTimeBy(ARTIFACT_SUMMARY_OK_TTL_MILLIS)
        store.request(server, both)
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        // The failed read kept nothing new; the expired answers stay shown until a new one lands.
        assertIs<ArtifactSummaryAnswer.Ok>(store.state.value.answersFor(server, both)["pt_a"])
        val calls = api.calls.size
        store.request(server, both)
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertEquals(calls + 1, api.calls.size, "asked again after a failure")
    }

    @Test
    fun clearAllDropsAnswersAndOneOnItsWayIsNotKept() = runTest {
        val api = FakeApi { okAnswer(it) }
        val store = store(api)
        store.request(server, listOf(ArtifactLinkTarget("pt_a")))
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertTrue(store.state.value.entries.isNotEmpty())
        store.clearAll()
        assertTrue(store.state.value.entries.isEmpty())
    }
}
