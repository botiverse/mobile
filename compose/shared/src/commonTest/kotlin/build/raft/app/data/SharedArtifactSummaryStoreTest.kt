package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
    fun aShortIdIsAskedForByItsPtId() {
        // slock staging 380482116: the summaries route reads `id` as written (webCards.ts
        // parseArtifactObjectRef, visibility.ts homesWhere), so a short id is sent as `pt_<uuid>`.
        val body = Json.parseToJsonElement(encodeArtifactSummariesRequest(listOf(ArtifactLinkTarget("2QthvfPeGfDnqwr7LG6itx", 2)))).jsonObject
        val ref = body["refs"]!!.jsonArray.single() as JsonObject
        assertEquals("pt_0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21", ref["id"]?.jsonPrimitive?.content)
        // The answer echoes the pt ref; it answers the short link's key.
        val answers =
            parseArtifactSummariesAnswer(
                """{"kind":"artifact","summaries":[{"ref":{"id":"pt_0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21","version":2},"ok":false,"reason":"unavailable"}]}""",
            )!!
        assertEquals(ArtifactSummaryAnswer.Unavailable, answers[ArtifactLinkTarget("2QthvfPeGfDnqwr7LG6itx", 2).key])
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
    fun refreshAsksAgainWithinTheTtlAndAnUnavailableAnswerReplacesTheKeptOne() = runTest {
        // The in-app Artifact viewer revalidates on every open: a kept Ok must not hide a 403/404.
        var gone = false
        val api = FakeApi { okAnswer(it, unavailable = if (gone) setOf("pt_a") else emptySet()) }
        val store = store(api)
        val target = ArtifactLinkTarget("pt_a")
        store.request(server, listOf(target))
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertTrue(store.isFresh(server, target))
        assertIs<ArtifactSummaryAnswer.Ok>(store.state.value.answersFor(server, listOf(target))["pt_a"])

        gone = true
        store.refresh(server, target)
        assertTrue(!store.isFresh(server, target), "expired at once, still shown until the new answer")
        assertIs<ArtifactSummaryAnswer.Ok>(store.state.value.answersFor(server, listOf(target))["pt_a"])
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertEquals(2, api.calls.size)
        assertTrue(store.isFresh(server, target))
        assertEquals(ArtifactSummaryAnswer.Unavailable, store.state.value.answersFor(server, listOf(target))["pt_a"])
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
    fun aDeletedAnswerWithItsTitleIsDeletedElseUnavailable() {
        // slock staging 84e41d9be webObjectsContract.ts ObjectSummaryAnswer `reason: "deleted"`,
        // workspaceArtifacts.ts ArtifactDeletedReference; web artifactFacts.ts artifactDeletedOf.
        val answers =
            parseArtifactSummariesAnswer(
                """
                {"kind":"artifact","summaries":[
                  {"ref":{"id":"pt_a"},"ok":false,"reason":"deleted","deleted":{"status":"deleted","title":"Old plan",
                    "deletedBy":{"type":"user","id":"u1","name":"martin","displayName":"Martin","avatarUrl":null},"deletedAt":"2026-10-30T10:00:00.000Z"}},
                  {"ref":{"id":"pt_b"},"ok":false,"reason":"deleted","deleted":{"status":"deleted","title":"Handle only",
                    "deletedBy":{"type":"agent","name":"lagoon","displayName":" "}}},
                  {"ref":{"id":"pt_c"},"ok":false,"reason":"deleted","deleted":{"status":"deleted","title":" "}},
                  {"ref":{"id":"pt_d"},"ok":false,"reason":"deleted"},
                  {"ref":{"id":"pt_e"},"ok":false,"reason":"revoked_by_a_newer_server"}
                ]}
                """.trimIndent(),
            )!!
        assertEquals(
            ArtifactSummaryAnswer.Deleted(ArtifactDeletedShown("Old plan", "Martin", "2026-10-30T10:00:00.000Z")),
            answers["pt_a"],
        )
        assertEquals(ArtifactSummaryAnswer.Deleted(ArtifactDeletedShown("Handle only", "lagoon", null)), answers["pt_b"])
        assertEquals(ArtifactSummaryAnswer.Unavailable, answers["pt_c"], "no title: unavailable")
        assertEquals(ArtifactSummaryAnswer.Unavailable, answers["pt_d"], "no payload: unavailable")
        assertEquals(ArtifactSummaryAnswer.Unavailable, answers["pt_e"], "an unknown reason: unavailable")
    }

    @Test
    fun theCardFieldsAreRead() {
        val answers = parseArtifactSummariesAnswer("""{"kind":"artifact","summaries":[{"ref":{"id":"pt_a"},"ok":true,"summary":${card("pt_a")}}]}""")!!
        val summary = assertIs<ArtifactSummaryAnswer.Ok>(answers["pt_a"]).summary
        assertEquals("Lagoon", summary.authorName)
        assertEquals("2026-10-21T10:00:00.000Z", summary.updatedAt)
        assertEquals(2, summary.commentCount)
        assertEquals(ArtifactFacts(sizeBytes = 2048, pages = 2), summary.facts)
        assertTrue(summary.descriptionIsExcerpt, "no description of its own: the excerpt")
    }

    @Test
    fun aFailedReadIsFailedUntilAskedAgainAndAnAnswerClearsIt() = runTest {
        var fail = true
        val api = FakeApi { if (fail) HomeNetworkResponse(500, null) else okAnswer(it) }
        val store = store(api)
        val target = listOf(ArtifactLinkTarget("pt_a"))
        store.request(server, target)
        assertEquals(emptyMap(), store.state.value.answersFor(server, target), "loading: no answer yet")
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertEquals(ArtifactSummaryAnswer.Failed, store.state.value.answersFor(server, target)["pt_a"])
        assertTrue(store.state.value.entries.isEmpty(), "a failure keeps nothing")
        fail = false
        store.request(server, target)
        assertEquals(emptyMap(), store.state.value.answersFor(server, target), "asked again: loading")
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertIs<ArtifactSummaryAnswer.Ok>(store.state.value.answersFor(server, target)["pt_a"])
        assertTrue(store.state.value.failed.isEmpty())
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

    @Test
    fun offlineDiskCacheHydratesColdStartAndEvictsUnavailableOnRevalidation() = runTest {
        val rows = InMemoryRaftOfflineResourceRows()
        val offline = RaftOfflineResourceCache(rows, environmentId = { "https://api.example" }, scope = backgroundScope)
        val resource = offline.resource(ArtifactSummaryResourceSpec)
        val target = ArtifactLinkTarget("pt_a")

        var gone = false
        val api = FakeApi { okAnswer(it, unavailable = if (gone) setOf("pt_a") else emptySet()) }
        val firstStore =
            SharedArtifactSummaryStore(
                api = api,
                scope = backgroundScope,
                nowMillis = { testScheduler.currentTime },
                offlineResource = resource,
                currentAccountId = { "user-1" },
            )
        firstStore.request(server, listOf(target))
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertIs<ArtifactSummaryAnswer.Ok>(firstStore.state.value.answersFor(server, listOf(target))["pt_a"])

        // Cold start over same disk rows: cached card paints immediately, then background network revalidation evicts gone entry.
        gone = true
        val coldStore =
            SharedArtifactSummaryStore(
                api = api,
                scope = backgroundScope,
                nowMillis = { testScheduler.currentTime },
                offlineResource = resource,
                currentAccountId = { "user-1" },
            )
        coldStore.request(server, listOf(target))
        // Disk hydration runs before the batch window fires.
        advanceTimeBy(1)
        assertIs<ArtifactSummaryAnswer.Ok>(coldStore.state.value.answersFor(server, listOf(target))["pt_a"])

        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertEquals(ArtifactSummaryAnswer.Unavailable, coldStore.state.value.answersFor(server, listOf(target))["pt_a"])
        assertEquals(null, resource.read(resource.key("user-1", server, target.key)!!))
    }

    @Test
    fun offlineDiskHydrationRetriesAfterNullAccountAndDropServerFencesInFlightAndOlderUnavailableCannotDeleteNewerRow() = runTest {
        val rows = InMemoryRaftOfflineResourceRows()
        val offline = RaftOfflineResourceCache(rows, environmentId = { "https://api.example" }, scope = backgroundScope)
        val resource = offline.resource(ArtifactSummaryResourceSpec)
        val target = ArtifactLinkTarget("pt_b")

        // Seed disk with a valid summary.
        val seedApi = FakeApi { okAnswer(it) }
        val seeder =
            SharedArtifactSummaryStore(
                api = seedApi,
                scope = backgroundScope,
                nowMillis = { testScheduler.currentTime },
                offlineResource = resource,
                currentAccountId = { "user-1" },
            )
        seeder.request(server, listOf(target))
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertIs<ArtifactSummaryAnswer.Ok>(seeder.state.value.answersFor(server, listOf(target))["pt_b"])

        // Cold start where accountId is initially null: first request must NOT poison diskHydrationAttempted.
        var accountId: String? = null
        val slowApi = FakeApi { HomeNetworkResponse(500, "{}") }
        val coldStore =
            SharedArtifactSummaryStore(
                api = slowApi,
                scope = backgroundScope,
                nowMillis = { testScheduler.currentTime },
                offlineResource = resource,
                currentAccountId = { accountId },
            )
        coldStore.request(server, listOf(target))
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertEquals(ArtifactSummaryAnswer.Failed, coldStore.state.value.answersFor(server, listOf(target))["pt_b"])

        // Once accountId becomes known, next request hydrates immediately from disk before network answers.
        accountId = "user-1"
        coldStore.request(server, listOf(target))
        advanceTimeBy(1)
        assertIs<ArtifactSummaryAnswer.Ok>(coldStore.state.value.answersFor(server, listOf(target))["pt_b"])

        // dropServer clears memory for that server without touching other servers.
        coldStore.dropServer(server)
        assertEquals(emptyMap(), coldStore.state.value.answersFor(server, listOf(target)))

        // Fenced negative removal: an older fetch's removeIfNotSuperseded cannot delete a newer fetch's persisted row.
        val olderStarted = resource.fetchStarted()
        val newerStarted = resource.fetchStarted()
        val olderKey = resource.key("user-1", server, target.key, mintedAt = olderStarted)!!
        val newerKey = resource.key("user-1", server, target.key, mintedAt = newerStarted)!!
        val newerRecord = PersistedArtifactSummaryRecord("""{"id":"pt_b","title":"Fresh","version":2}""")
        assertTrue(resource.write(newerKey, newerRecord, startedAt = newerStarted))
        assertEquals(false, resource.removeIfNotSuperseded(olderKey, startedAt = olderStarted))
        assertEquals(newerRecord, resource.read(resource.key("user-1", server, target.key)!!))
    }

    @Test
    fun patchFollowingUpdatesPinnedAndUnpinnedSummariesAndExpiresTtl() = runTest {
        val pt = "pt_0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21"
        val short = "2QthvfPeGfDnqwr7LG6itx"
        val api = FakeApi { okAnswer(it) }
        val store = store(api)
        val latest = ArtifactLinkTarget(pt)
        val pinned = ArtifactLinkTarget(pt, 2)
        val other = ArtifactLinkTarget("pt_other")
        store.request(server, listOf(latest, pinned, other))
        advanceTimeBy(ARTIFACT_SUMMARY_BATCH_WINDOW_MILLIS + 1)
        assertTrue(store.isFresh(server, latest))
        assertTrue(store.isFresh(server, pinned))

        // Patching by the short id updates both unpinned and pinned entries of the same canonical Artifact and expires their TTL.
        store.patchFollowing(server, short, false)
        assertEquals(false, assertIs<ArtifactSummaryAnswer.Ok>(store.state.value.answersFor(server, listOf(latest))[latest.key]).summary.following)
        assertEquals(false, assertIs<ArtifactSummaryAnswer.Ok>(store.state.value.answersFor(server, listOf(pinned))[pinned.key]).summary.following)
        assertEquals(null, assertIs<ArtifactSummaryAnswer.Ok>(store.state.value.answersFor(server, listOf(other))[other.key]).summary.following)
        assertEquals(false, store.isFresh(server, latest))
        assertEquals(false, store.isFresh(server, pinned))
        assertTrue(store.isFresh(server, other))
    }
}
