package build.raft.app.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real Ktor 3 OkHttp engine against MockWebServer (not MockEngine): response streaming, download to
 * file and cancellation of the download paths that NetworkKMM used to carry.
 */
class RaftKtorStreamingRealEngineTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() {
        server.start()
    }

    @AfterTest
    fun stop() {
        runCatching { server.close() }
    }

    @Test
    fun binaryDownloadStreamsTheBodyInChunksWithItsLength() = runBlocking {
        val body = ByteArray(300 * 1024) { (it % 241).toByte() }
        server.enqueue(MockResponse.Builder().body(Buffer().write(body)).addHeader("Content-Type", "image/webp").build())
        val chunks = mutableListOf<ByteArray>()
        var announced: Long? = null

        val contentType =
            SharedBinaryDownloader.download(
                url = server.url("/img.webp").toString(),
                onStart = { announced = it },
                onChunk = { bytes, _, _ -> chunks += bytes },
            )

        assertEquals("image/webp", contentType)
        assertEquals(body.size.toLong(), announced)
        assertTrue(chunks.size > 1, "streamed in ${chunks.size} chunk(s)")
        assertTrue(combineDownloadedChunks(chunks, chunks.sumOf { it.size }).contentEquals(body))
        assertEquals("*/*", server.takeRequest().headers["Accept"])
    }

    @Test
    fun conditionalDownloadSendsValidatorsAndReturnsNotModifiedWithoutABody() = runBlocking {
        server.enqueue(MockResponse.Builder().code(304).addHeader("ETag", "\"v2\"").build())

        val result =
            SharedBinaryDownloader.downloadConditional(
                url = server.url("/avatar.png").toString(),
                validators = SharedBinaryConditionalValidators(eTag = "\"v1\""),
                onStart = { error("no body on 304") },
                onChunk = { _, _, _ -> error("no body on 304") },
            )

        assertIs<SharedBinaryConditionalDownloadResult.NotModified>(result)
        assertEquals("\"v2\"", result.eTag)
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun aDroppedDownloadIsAnHttp0FailureWithItsKind() = runBlocking {
        // Content-Length 7, the server writes 3 bytes and closes. Whether Ktor's OkHttp engine surfaces
        // OkHttp's "unexpected end of stream" or just ends the body channel is timing-dependent; the
        // short-body check in raftStreamingGet makes both the same transport failure of the same kind.
        server.enqueue(MockResponse.Builder().body("partial").onResponseBody(SocketEffect.CloseSocket()).build())

        val failure =
            assertFailsWith<IllegalStateException> {
                SharedBinaryDownloader.fetchBytes(server.url("/broken.bin").toString())
            }

        assertTrue(failure.message.orEmpty().startsWith("Download failed: HTTP 0 (network_error: "), failure.message)
    }

    @Test
    fun cancellingADownloadCancelsTheTransfer() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(ByteArray(512 * 1024)))
                .throttleBody(16 * 1024, 200, TimeUnit.MILLISECONDS)
                .build()
        )
        val firstChunk = CompletableDeferred<Unit>()
        var chunksAfterCancel = 0
        var cancelled = false

        val download =
            async {
                SharedBinaryDownloader.download(
                    url = server.url("/slow.bin").toString(),
                    onStart = {},
                    onChunk = { _, _, _ ->
                        if (cancelled) chunksAfterCancel += 1
                        firstChunk.complete(Unit)
                    },
                )
            }
        withTimeout(10_000) { firstChunk.await() }
        cancelled = true
        download.cancel()

        assertFailsWith<CancellationException> { download.await() }
        assertTrue(chunksAfterCancel <= 1, "chunks delivered after cancel: $chunksAfterCancel")
    }

    @Test
    fun downloadToFilePublishesTheExactBytesThroughTheRealEngine() {
        val body = ByteArray(200 * 1024) { (it % 199).toByte() }
        server.enqueue(MockResponse.Builder().body(Buffer().write(body)).addHeader("Content-Type", "application/pdf").build())
        val sink = MemorySink()
        val done = CountDownLatch(1)
        val results = mutableListOf<AttachmentDownloadResult>()
        val engine = AttachmentDownloadCoordinatorEngine(sinkFactory = AttachmentDownloadSinkFactory { _, _ -> sink })

        engine.start(
            request =
                AttachmentDownloadRequest(
                    downloadId = "d1",
                    targetKey = "/cache/file.pdf",
                    url = server.url("/file.pdf").toString(),
                    targetPath = "/cache/file.pdf",
                ),
            observer =
                object : AttachmentDownloadObserver {
                    override fun onProgress(progress: AttachmentDownloadProgress) = Unit

                    override fun onComplete(result: AttachmentDownloadResult) {
                        results += result
                        done.countDown()
                    }
                },
        )

        assertTrue(done.await(20, TimeUnit.SECONDS), "download did not finish")
        assertEquals("complete", results.single().status, results.single().toString())
        assertTrue(sink.published)
        assertTrue(sink.bytes.toByteArray().contentEquals(body))
    }

    private class MemorySink : AttachmentDownloadAtomicSink {
        val bytes = java.io.ByteArrayOutputStream()
        var published = false

        override fun begin(): Boolean = true

        override fun write(bytes: ByteArray): Boolean {
            this.bytes.write(bytes)
            return true
        }

        override fun prepare(): Boolean = true

        override fun publish(): Boolean {
            published = true
            return true
        }

        override fun abort() = Unit
    }
}
