package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The publish check's screenshot on an Artifact card (botiverse/slock #13046
 * `artifactScreenshot.ts`; contract Remy 11-05): the card's `preview` read, which file a layout
 * shows, its route, the authenticated read, and the in-memory cache (64 / 32 MiB, failures 30 s).
 */
class ArtifactScreenshotsTest {
    private val server = "11111111-1111-4111-8111-111111111111"
    private val cardId = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"

    private fun card(preview: String) =
        """
        {"id":"$cardId","url":"https://app.raft.build/artifacts/$cardId","title":"Q3 report","description":null,"kind":"html",
         "facts":{"sizeBytes":2048,"fileCount":1},"preview":$preview,
         "version":3,"pinned":false,"latestVersion":3,
         "author":{"type":"agent","id":"a1","name":"lagoon","displayName":"Lagoon","avatarUrl":null},
         "createdAt":"2026-10-20T10:00:00.000Z","updatedAt":"2026-10-21T10:00:00.000Z","commentCount":0,
         "home":{"kind":"conversation","conversation":{"id":"c1","name":"proj-workspace","type":"channel"}}}
        """.trimIndent()

    private fun summaryOf(preview: String): ArtifactSummary {
        val answers = parseArtifactSummariesAnswer("""{"kind":"artifact","summaries":[{"ref":{"id":"$cardId"},"ok":true,"summary":${card(preview)}}]}""")!!
        return assertIs<ArtifactSummaryAnswer.Ok>(answers[cardId]).summary
    }

    @Test
    fun screenshotWithPhoneShotIsRead() {
        val summary = summaryOf("""{"kind":"screenshot","desktop":"of_desktop","phone":"of_phone"}""")
        assertEquals(ArtifactScreenshot(desktop = "of_desktop", phone = "of_phone"), summary.screenshot)
        // A screenshot is not an excerpt: no description comes from it.
        assertNull(summary.description)
    }

    @Test
    fun screenshotWithoutDesktopShotIsNone() {
        assertNull(summaryOf("""{"kind":"screenshot","phone":"of_phone"}""").screenshot)
        assertNull(summaryOf("""{"kind":"screenshot","desktop":"","phone":"of_phone"}""").screenshot)
        // A file id of another type: the card still shows, without a screenshot.
        assertNull(summaryOf("""{"kind":"screenshot","desktop":42,"phone":{"id":"x"}}""").screenshot)
    }

    @Test
    fun phoneLayoutShowsThePhoneShotWhenThereIsOne() {
        val both = ArtifactScreenshot("of_desktop", "of_phone")
        assertEquals("of_phone", artifactScreenshotFile(both, phone = true))
        assertEquals("of_desktop", artifactScreenshotFile(both, phone = false))
        val desktopOnly = ArtifactScreenshot("of_desktop", null)
        assertEquals("of_desktop", artifactScreenshotFile(desktopOnly, phone = true))
        assertEquals("of_desktop", artifactScreenshotFile(desktopOnly, phone = false))
        assertNull(artifactScreenshotFile(null, phone = true))
        assertNull(artifactScreenshotFile(ArtifactScreenshot(" ", "of_phone"), phone = true))
    }

    @Test
    fun routeIsTheObjectFileOfTheCard() {
        assertEquals("objects/artifact/$cardId/files/of_desktop", artifactScreenshotPath(cardId, "of_desktop"))
        // encodeURIComponent per segment, as objectWebPaths.file.
        assertEquals("objects/artifact/pt_a%2Fb/files/of%20x%3F", artifactScreenshotPath("pt_a/b", "of x?"))
    }

    private class FakeBytesNetwork(private val answer: (String) -> HomeNetworkBytesResponse) : RaftNetworkClient {
        val reads = mutableListOf<Triple<String, ServerId?, Int>>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse = error("unexpected request $path")

        override suspend fun requestBytes(
            priority: DataRequestPriority,
            path: String,
            serverIdOverride: ServerId?,
            maxBytes: Int,
        ): HomeNetworkBytesResponse {
            reads += Triple(path, serverIdOverride, maxBytes)
            return answer(path)
        }
    }

    @Test
    fun fetcherReadsTheFileAuthenticatedOnTheCardsServer() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val network = FakeBytesNetwork { HomeNetworkBytesResponse(200, bytes, "image/png") }
        val read = NetworkArtifactScreenshotFetcher(network).fetch(ArtifactScreenshotKey(server, cardId, "of_phone"))
        assertContentEquals(bytes, read)
        assertEquals(listOf<Triple<String, ServerId?, Int>>(Triple("objects/artifact/$cardId/files/of_phone", ServerId(server), ARTIFACT_SCREENSHOT_MAX_FILE_BYTES)), network.reads)
    }

    @Test
    fun fetcherAnswersNoneForFailuresAndNonImages() = runTest {
        val key = ArtifactScreenshotKey(server, cardId, "of_desktop")
        for (answer in
            listOf(
                HomeNetworkBytesResponse(404),
                HomeNetworkBytesResponse(429),
                HomeNetworkBytesResponse(0),
                HomeNetworkBytesResponse(200, byteArrayOf(1), "text/html"),
                HomeNetworkBytesResponse(200, byteArrayOf(1), null),
                HomeNetworkBytesResponse(200, ByteArray(0), "image/png"),
            )) {
            assertNull(NetworkArtifactScreenshotFetcher(FakeBytesNetwork { answer }).fetch(key))
        }
        for (type in listOf("image/jpeg", "image/webp", "image/gif")) {
            assertContentEquals(byteArrayOf(9), NetworkArtifactScreenshotFetcher(FakeBytesNetwork { HomeNetworkBytesResponse(200, byteArrayOf(9), type) }).fetch(key))
        }
    }

    private class FakeFetcher(var answer: (ArtifactScreenshotKey) -> ByteArray?) : ArtifactScreenshotFetcher {
        val calls = mutableListOf<ArtifactScreenshotKey>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun fetch(key: ArtifactScreenshotKey): ByteArray? {
            calls += key
            gate?.await()
            return answer(key)
        }
    }

    private fun TestScope.store(
        fetcher: ArtifactScreenshotFetcher,
        maxKept: Int = ARTIFACT_SCREENSHOT_MAX_KEPT,
        maxKeptBytes: Long = ARTIFACT_SCREENSHOT_MAX_KEPT_BYTES,
    ) = SharedArtifactScreenshotStore(
        fetcher = fetcher,
        scope = backgroundScope,
        nowMillis = { testScheduler.currentTime },
        maxKept = maxKept,
        maxKeptBytes = maxKeptBytes,
    )

    private fun key(file: String, card: String = cardId, serverId: String = server) = ArtifactScreenshotKey(serverId, card, file)

    @Test
    fun theOldestGoFirstPastTheBytes() = runTest {
        val store = store(FakeFetcher { ByteArray(400) }, maxKeptBytes = 1_000)
        store.read(key("a"))
        store.read(key("b"))
        assertEquals(800, store.keptByteCount())
        store.read(key("c"))
        assertEquals(2, store.keptCount())
        assertEquals(800, store.keptByteCount())
        assertNull(store.kept(key("a")))
    }

    @Test
    fun clearAllForgetsAndDropsAReadInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeFetcher { byteArrayOf(3) }
        val store = store(fetcher)
        store.read(key("kept"))
        assertEquals(1, store.keptCount())
        fetcher.gate = gate
        val inFlight = async { store.read(key("late")) }
        runCurrent()
        store.clearAll()
        gate.complete(Unit)
        inFlight.await()
        assertEquals(0, store.keptCount())
        assertNull(store.kept(key("late")))
        // Asked again after it: read afresh.
        assertContentEquals(byteArrayOf(3), store.read(key("kept")))
        assertEquals(3, fetcher.calls.size)
    }

    @Test
    fun diskCachePaintsImmediatelyThenRevalidatesEvictsOnGoneAndDropsServerInFlight() = runTest {
        val disk =
            object : ArtifactScreenshotDiskCache {
                val entries = linkedMapOf<Pair<ArtifactScreenshotDiskOwner, ArtifactScreenshotKey>, ByteArray>()

                override suspend fun read(owner: ArtifactScreenshotDiskOwner, key: ArtifactScreenshotKey): ByteArray? =
                    entries[owner to key]

                override suspend fun write(
                    owner: ArtifactScreenshotDiskOwner,
                    key: ArtifactScreenshotKey,
                    bytes: ByteArray,
                    writeToken: Long,
                ) {
                    entries[owner to key] = bytes
                }

                override suspend fun remove(
                    owner: ArtifactScreenshotDiskOwner,
                    key: ArtifactScreenshotKey,
                    writeToken: Long,
                ) {
                    entries.remove(owner to key)
                }

                override suspend fun clearServer(serverId: String, accountId: String?, environmentId: String?) {
                    entries.keys.filter { (o, k) ->
                        k.serverId == serverId &&
                            (accountId == null || o.accountId == accountId) &&
                            (environmentId == null || o.environmentId == environmentId)
                    }.forEach(entries::remove)
                }
            }
        val owner = ArtifactScreenshotDiskOwner("https://api.example", "user-1")
        val target = key("shot-1")
        disk.write(owner, target, byteArrayOf(1, 2))

        var outcome: ArtifactScreenshotFetchResult = ArtifactScreenshotFetchResult.TransientFailure
        var gate: CompletableDeferred<Unit>? = null
        val fetcher =
            object : ArtifactScreenshotFetcher {
                override suspend fun fetch(key: ArtifactScreenshotKey): ByteArray? = null
                override suspend fun fetchOutcome(key: ArtifactScreenshotKey): ArtifactScreenshotFetchResult {
                    gate?.await()
                    return outcome
                }
            }
        val store =
            SharedArtifactScreenshotStore(
                fetcher = fetcher,
                scope = backgroundScope,
                nowMillis = { testScheduler.currentTime },
                diskCache = disk,
                currentOwner = { owner },
            )

        // Transient failure: paints disk copy immediately and keeps it on disk.
        val cachedCallbacks = mutableListOf<ByteArray>()
        assertContentEquals(byteArrayOf(1, 2), store.read(target) { cachedCallbacks += it })
        assertEquals(1, cachedCallbacks.size)
        assertContentEquals(byteArrayOf(1, 2), disk.read(owner, target))

        // 403/404 Gone (after clearAll resets cooldown): evicts disk copy and returns null.
        store.clearAll()
        outcome = ArtifactScreenshotFetchResult.Gone
        assertNull(store.read(target))
        assertNull(disk.read(owner, target))

        // dropServer cancels in-flight write-back and caller never receives revoked bytes.
        val otherServerKey = key("shot-other", serverId = "server-2")
        disk.write(owner, otherServerKey, byteArrayOf(7))
        outcome = ArtifactScreenshotFetchResult.Ok(byteArrayOf(9, 9))
        gate = CompletableDeferred()
        val inFlight = async { store.read(target) }
        runCurrent()
        store.dropServer(server, owner.accountId)
        gate.complete(Unit)
        assertNull(inFlight.await(), "caller suspended across dropServer must not receive revoked bytes")
        assertNull(store.kept(target))
        assertNull(disk.read(owner, target))
        assertContentEquals(byteArrayOf(7), disk.read(owner, otherServerKey))

        // Updated-bytes revalidation: paints old disk bytes first, then replaces with fresh network bytes.
        disk.write(owner, target, byteArrayOf(1, 2))
        outcome = ArtifactScreenshotFetchResult.Ok(byteArrayOf(3, 4, 5))
        gate = null
        val revalCallbacks = mutableListOf<ByteArray>()
        assertContentEquals(byteArrayOf(3, 4, 5), store.read(target) { revalCallbacks += it })
        assertEquals(1, revalCallbacks.size)
        assertContentEquals(byteArrayOf(1, 2), revalCallbacks.single())
        assertContentEquals(byteArrayOf(3, 4, 5), disk.read(owner, target))

        // clearAll during in-flight read returns null (never leaks bytes across sign-out).
        store.clearAll()
        disk.write(owner, target, byteArrayOf(5))
        outcome = ArtifactScreenshotFetchResult.Ok(byteArrayOf(8, 8))
        gate = CompletableDeferred()
        val clearInFlight = async { store.read(target) }
        runCurrent()
        store.clearAll()
        gate.complete(Unit)
        assertNull(clearInFlight.await(), "caller suspended across clearAll must not receive prior session bytes")
        assertNull(store.kept(target))
    }

    @Test
    fun networkFetcherDistinguishesGoneFromTransientFailureAndOk() = runTest {
        val responses =
            ArrayDeque(
                listOf(
                    HomeNetworkBytesResponse(code = 403, bytes = null, contentType = "application/json"),
                    HomeNetworkBytesResponse(code = 404, bytes = null, contentType = "application/json"),
                    HomeNetworkBytesResponse(code = 429, bytes = null, contentType = "application/json"),
                    HomeNetworkBytesResponse(code = 500, bytes = null, contentType = "text/plain"),
                    HomeNetworkBytesResponse(code = 200, bytes = byteArrayOf(1), contentType = "text/html"),
                    HomeNetworkBytesResponse(code = 200, bytes = byteArrayOf(7, 8), contentType = "image/png"),
                )
            )
        val fetcher = NetworkArtifactScreenshotFetcher(FakeBytesNetwork { responses.removeFirst() })
        assertEquals(ArtifactScreenshotFetchResult.Gone, fetcher.fetchOutcome(key("a")))
        assertEquals(ArtifactScreenshotFetchResult.Gone, fetcher.fetchOutcome(key("b")))
        assertEquals(ArtifactScreenshotFetchResult.TransientFailure, fetcher.fetchOutcome(key("c")))
        assertEquals(ArtifactScreenshotFetchResult.TransientFailure, fetcher.fetchOutcome(key("d")))
        assertEquals(ArtifactScreenshotFetchResult.TransientFailure, fetcher.fetchOutcome(key("e")))
        val ok = assertIs<ArtifactScreenshotFetchResult.Ok>(fetcher.fetchOutcome(key("f")))
        assertContentEquals(byteArrayOf(7, 8), ok.bytes)
    }
}
