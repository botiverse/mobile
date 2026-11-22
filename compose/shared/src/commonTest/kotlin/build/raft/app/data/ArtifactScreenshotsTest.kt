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
}
