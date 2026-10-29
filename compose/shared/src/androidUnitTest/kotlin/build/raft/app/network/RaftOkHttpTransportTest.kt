package build.raft.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import java.net.InetAddress
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The OkHttp side of the Ktor transport: config, lanes, idempotent-only retry, real error mapping. */
class RaftOkHttpTransportTest {
    private val server = MockWebServer()
    private val debugLogs = java.util.Collections.synchronizedList(mutableListOf<String>())

    @BeforeTest
    fun startServer() {
        server.start()
    }

    @AfterTest
    fun stopServer() {
        runCatching { server.close() }
    }

    @Test
    fun baseClientDetectsDeadConnectionsAndFailsConnectFast() {
        val base = buildRaftBaseOkHttpClient(eventListenerFactory = null)

        assertEquals(10_000, base.pingIntervalMillis)
        assertEquals(5_000, base.connectTimeoutMillis)
        assertEquals(20_000, base.readTimeoutMillis)
        assertEquals(20_000, base.writeTimeoutMillis)
        assertEquals(0, base.callTimeoutMillis)
        assertTrue(base.retryOnConnectionFailure)
        assertTrue(base.fastFallback)
        assertSame(Dns.SYSTEM, base.dns)
    }

    @Test
    fun lanesKeepTheirOwnDispatcherAndShareOnePool() {
        val base = buildRaftBaseOkHttpClient(eventListenerFactory = null)
        val lanes =
            RaftRequestPriority.entries.associateWith { priority ->
                val dispatcher = raftLaneDispatcher(priority)
                RaftKtorCallKind.entries.associateWith { kind -> buildRaftLaneOkHttpClient(base, dispatcher, kind) }
            }

        RaftRequestPriority.entries.forEach { priority ->
            val clients = lanes.getValue(priority)
            val dispatcher = clients.getValue(RaftKtorCallKind.Idempotent).dispatcher
            assertEquals(raftOkHttpLaneLimit(priority), dispatcher.maxRequests)
            assertEquals(raftOkHttpLaneLimit(priority), dispatcher.maxRequestsPerHost)
            clients.values.forEach { client ->
                assertSame(dispatcher, client.dispatcher)
                assertSame(base.connectionPool, client.connectionPool)
            }
            assertTrue(clients.getValue(RaftKtorCallKind.Idempotent).retryOnConnectionFailure)
            assertFalse(clients.getValue(RaftKtorCallKind.NonIdempotent).retryOnConnectionFailure)
            assertFalse(clients.getValue(RaftKtorCallKind.PresignedUpload).retryOnConnectionFailure)
            assertEquals(listOf(Protocol.HTTP_1_1), clients.getValue(RaftKtorCallKind.PresignedUpload).protocols)
            assertFalse(clients.getValue(RaftKtorCallKind.MultipartUpload).retryOnConnectionFailure)
            assertEquals(listOf(Protocol.HTTP_1_1), clients.getValue(RaftKtorCallKind.MultipartUpload).protocols)
        }
        assertNotSame(
            lanes.getValue(RaftRequestPriority.Foreground).getValue(RaftKtorCallKind.Idempotent).dispatcher,
            lanes.getValue(RaftRequestPriority.Background).getValue(RaftKtorCallKind.Idempotent).dispatcher
        )
        assertEquals(listOf(64, 32, 16), RaftRequestPriority.entries.map(::raftOkHttpLaneLimit))
    }

    @Test
    fun ktorKeepsTheLaneDispatcher() = runBlocking {
        val executed = AtomicInteger()
        val threads = Executors.newCachedThreadPool()
        val counting =
            object : AbstractExecutorService() {
                override fun execute(command: Runnable) {
                    executed.incrementAndGet()
                    threads.execute(command)
                }

                override fun shutdown() = threads.shutdown()
                override fun shutdownNow(): MutableList<Runnable> = threads.shutdownNow()
                override fun isShutdown(): Boolean = threads.isShutdown
                override fun isTerminated(): Boolean = threads.isTerminated
                override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = threads.awaitTermination(timeout, unit)
            }
        val lane = Dispatcher(counting).apply { maxRequests = 4; maxRequestsPerHost = 4 }
        val client =
            raftKtorClientFor(
                buildRaftLaneOkHttpClient(buildRaftBaseOkHttpClient(eventListenerFactory = null), lane, RaftKtorCallKind.Idempotent)
            )
        server.enqueue(MockResponse.Builder().body("ok").build())

        // Ktor derives its own OkHttpClient from `preconfigured` and resets the dispatcher; the call
        // must still be admitted by the lane's dispatcher.
        client.get(server.url("/lane").toString())

        assertEquals(1, executed.get())
        threads.shutdown()
    }

    @Test
    fun staleConnectionIsRetriedForGetButNeverForPost() = runBlocking {
        val executor = executorAgainst(twoRoutes = true)
        server.enqueue(MockResponse.Builder().body("seed connection pool").build())
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(MockResponse.Builder().body("retry success").build())

        assertEquals("seed connection pool", executor.get("/seed").bodyText)
        val get = executor.get("/read")
        assertEquals(200, get.code, get.transportDiagnostics)
        assertEquals("retry success", get.bodyText)
        assertEquals(3, server.requestCount)

        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(MockResponse.Builder().body("must not be reached").build())
        val post =
            executor.execute(RaftRequestPriority.Foreground, server.url("/write").toString(), "POST", emptyMap(), "{}", true)
        assertEquals(0, post.code, "count=${server.requestCount} body=${post.bodyText} diag=${post.transportDiagnostics} logs=$debugLogs")
        assertEquals(4, server.requestCount, "POST was re-sent")
    }

    @Test
    fun realOkHttpFailuresMapToConnectTlsDnsAndTimeout() = runBlocking {
        val executor = executorAgainst(twoRoutes = false)

        // A peer that answers the TLS ClientHello with plain text.
        val plain = java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val plainServer = Thread {
            runCatching { plain.accept().use { socket -> socket.getOutputStream().write("HTTP/1.1 400 Bad\r\n\r\n".toByteArray()) } }
        }.apply { start() }
        val tls = executor.execute(RaftRequestPriority.Visible, "https://127.0.0.1:${plain.localPort}/", "GET", emptyMap(), null, false)
        plainServer.join(5_000)
        plain.close()
        assertTrue("kind=TLS " in tls.transportDiagnostics.orEmpty(), tls.transportDiagnostics)

        val dns = executorAgainst(twoRoutes = false, dns = { host -> throw java.net.UnknownHostException(host) })
            .get("/dns")
        assertTrue("kind=DNS " in dns.transportDiagnostics.orEmpty(), dns.transportDiagnostics)

        val refusedUrl = server.url("/refused").toString()
        server.close()
        val refused = executor.execute(RaftRequestPriority.Visible, refusedUrl, "GET", emptyMap(), null, false)
        assertTrue("kind=CONNECT " in refused.transportDiagnostics.orEmpty(), refused.transportDiagnostics)
    }

    @Test
    fun readTimeoutFromTheEngineClassifiesAsTimeout() = runBlocking {
        // The property under test is the exception chain Ktor + OkHttp produce for a stalled response,
        // not a duration: the server never answers and the socket timeout ends the call.
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val client = raftKtorClientFor(buildRaftBaseOkHttpClient(eventListenerFactory = null))

        val failure = runCatching { client.get(server.url("/stall").toString()) { timeout { socketTimeoutMillis = 200 } } }

        assertEquals(RaftTransportFailureKind.TIMEOUT, classifyRaftKtorFailure(failure.exceptionOrNull()!!))
    }

    @Test
    fun eventListenerReportsConnectionReuseByRequestId() = runBlocking {
        val executor =
            KtorRaftHttpExecutor(
                clients = laneProvider(buildRaftBaseOkHttpClient()),
                requestLifetimeCoordinator = RaftNetworkRequestLifetimeCoordinator(),
                engineLabel = "ktor_okhttp",
            )
        server.enqueue(MockResponse.Builder().body("a").build())
        server.enqueue(MockResponse.Builder().body("b").build())

        val first = executor.get("/a")
        val second = executor.get("/b")

        // localhost has an IPv4 and an IPv6 address: fast fallback may race both, so attempts >= 1.
        assertTrue(Regex("reusedConnection=false connectionAttempts=[12] ").containsMatchIn(first.transportDiagnostics.orEmpty()), first.transportDiagnostics)
        assertTrue("reusedConnection=true connectionAttempts=0" in second.transportDiagnostics.orEmpty(), second.transportDiagnostics)
    }

    /**
     * Hands ae47bba1: on Ktor 2.3.7 streamed upload bodies went through ktor-io's blocking adapter and
     * died with NoSuchMethodError on coroutines 1.11. These run the real Ktor 3 OkHttp engine end to end.
     */
    @Test
    fun presignedFilePutSendsExactlyTheSignedHeadersAndAFixedLength() = runBlocking {
        val executor = uploadExecutor()
        val fileBytes = ByteArray(200 * 1024) { (it % 253).toByte() }
        server.enqueue(MockResponse.Builder().code(200).body("<etag/>").build())
        val progress = mutableListOf<RaftWireTransferProgress>()

        val response =
            executor.executeFile(
                priority = RaftRequestPriority.Foreground,
                url = server.url("/bucket/obj%2Fkey.jpg?X-Amz-Signature=ab%2Bcd&X-Amz-Expires=600").toString(),
                method = "PUT",
                headers = mapOf("Content-Type" to "image/jpeg"),
                body =
                    RaftHttpFileBody(
                        path = "content://photo",
                        contentType = "image/jpeg",
                        contentLength = fileBytes.size.toLong(),
                        openStream = {
                            build.raft.app.data.boundedPlatformFileNetworkStream(fileBytes.size.toLong(), ArrayChunkSource(fileBytes))
                        }
                    ),
                onUploadProgress = { progress += it },
            )

        assertEquals(200, response.code, response.transportDiagnostics)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/bucket/obj%2Fkey.jpg?X-Amz-Signature=ab%2Bcd&X-Amz-Expires=600", recorded.target)
        assertEquals(fileBytes.size.toString(), recorded.headers["Content-Length"])
        assertEquals(null, recorded.headers["Transfer-Encoding"])
        assertEquals("image/jpeg", recorded.headers["Content-Type"])
        assertEquals(null, recorded.headers["Authorization"])
        // Nothing beyond what OkHttp / Ktor always send (Accept */*, Accept-Encoding, User-Agent, Connection)
        // and the trace ids: no Authorization or other header that would break a SigV4 signature.
        assertEquals(
            setOf(
                "content-length", "content-type", "host", "connection", "accept", "accept-encoding", "user-agent",
                "x-request-id", "traceparent",
            ),
            recorded.headers.names().map { it.lowercase() }.toSet()
        )
        assertTrue(recorded.body!!.toByteArray().contentEquals(fileBytes))
        assertEquals(RaftWireTransferProgress(fileBytes.size.toLong(), fileBytes.size.toLong()), progress.last())
    }

    @Test
    fun streamedMultipartUploadSendsAFixedLengthFormBody() = runBlocking {
        val executor = uploadExecutor()
        val fileBytes = ByteArray(150 * 1024) { 9 }
        server.enqueue(MockResponse.Builder().code(200).body("""{"attachments":[{"id":"a1"}]}""").build())

        val response =
            executor.executeMultipart(
                priority = RaftRequestPriority.Foreground,
                url = server.url("/api/attachments/upload").toString(),
                fields = mapOf("channelId" to "c1"),
                files =
                    listOf(
                        MultipartFile(
                            name = "files",
                            fileName = "85734.jpg",
                            mimeType = "image/jpeg",
                            fileRef = "content://photo",
                            sizeBytes = fileBytes.size.toLong(),
                            streamOpener = {
                                build.raft.app.data.boundedPlatformFileNetworkStream(fileBytes.size.toLong(), ArrayChunkSource(fileBytes))
                            }
                        )
                    ),
                headers = mapOf("Authorization" to "Bearer access-1", HEADER_SERVER_ID to "server-1"),
            )

        assertEquals(200, response.code, response.transportDiagnostics)
        assertEquals("""{"attachments":[{"id":"a1"}]}""", response.bodyText)
        val recorded = server.takeRequest()
        val body = recorded.body!!.toByteArray()
        assertEquals(body.size.toString(), recorded.headers["Content-Length"])
        assertEquals(null, recorded.headers["Transfer-Encoding"])
        assertEquals("Bearer access-1", recorded.headers["Authorization"])
        assertEquals("server-1", recorded.headers[HEADER_SERVER_ID])
        val contentType = recorded.headers["Content-Type"].orEmpty()
        assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType)
        val text = body.decodeToString()
        assertTrue("name=\"channelId\"\r\n\r\nc1\r\n" in text)
        assertTrue("name=\"files\"; filename=\"85734.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n" in text)
        assertTrue(text.endsWith("--\r\n"))
    }

    /**
     * Hands e9baeedb: progress must follow bytes written to the connection, not to Ktor's body
     * channel. The server never reads the body, so once the socket buffers fill the upload stalls and
     * progress must stop well short of the total (it used to read 100% at once).
     */
    @Test
    fun uploadProgressFollowsTheConnectionNotTheBodyChannel() = runBlocking {
        server.enqueue(MockResponse.Builder().doNotReadRequestBody().onResponseStart(SocketEffect.Stall).build())
        val total = 16L * 1024L * 1024L
        val fileBytes = ByteArray(total.toInt())
        val progress = java.util.concurrent.CopyOnWriteArrayList<RaftWireTransferProgress>()
        // Loopback socket buffers can hold tens of MB; pin the client's send buffer so "the
        // connection stopped taking bytes" happens early, as on a real slow uplink.
        val smallSendBuffer =
            object : javax.net.SocketFactory() {
                private val delegate = javax.net.SocketFactory.getDefault()
                private fun tune(socket: java.net.Socket) = socket.apply { sendBufferSize = 64 * 1024 }
                override fun createSocket() = tune(delegate.createSocket())
                override fun createSocket(host: String, port: Int) = tune(delegate.createSocket(host, port))
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
                    tune(delegate.createSocket(host, port, localHost, localPort))
                override fun createSocket(host: InetAddress, port: Int) = tune(delegate.createSocket(host, port))
                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) =
                    tune(delegate.createSocket(address, port, localAddress, localPort))
            }
        val executor = uploadExecutor { socketFactory(smallSendBuffer) }

        val upload =
            launch(kotlinx.coroutines.Dispatchers.IO) {
                executor.executeFile(
                    priority = RaftRequestPriority.Foreground,
                    url = server.url("/bucket/big.bin?sig=1").toString(),
                    method = "PUT",
                    headers = emptyMap(),
                    body =
                        RaftHttpFileBody(
                            path = "content://big",
                            contentType = "application/octet-stream",
                            contentLength = total,
                            openStream = {
                                build.raft.app.data.boundedPlatformFileNetworkStream(total, ArrayChunkSource(fileBytes))
                            }
                        ),
                    onUploadProgress = { progress += it },
                )
            }
        // Wait until the transfer has started and then stopped moving (socket buffers full).
        var last = -1L
        var stableSince = System.nanoTime()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val now = progress.lastOrNull()?.bytesTransferred ?: -1L
            if (now != last) {
                last = now
                stableSince = System.nanoTime()
            } else if (now > 0 && System.nanoTime() - stableSince > TimeUnit.MILLISECONDS.toNanos(750)) {
                break
            }
            Thread.sleep(25)
        }
        upload.cancel()

        assertTrue(last > 0, "no progress reported")
        // Measured with this setup: ~0.2 MB at the connection vs ~2 MB when counted at the body
        // writer (Ktor's body channel and buffers), which is what made small photos jump to 100%.
        assertTrue(last < 1024L * 1024L, "progress ran ahead of the connection: $last of $total")
        assertTrue(progress.all { it.bytesTotal == total })
    }

    private fun uploadExecutor(customize: OkHttpClient.Builder.() -> Unit = {}): KtorRaftHttpExecutor {
        val lanes = RaftOkHttpLanes(buildRaftBaseOkHttpClient(eventListenerFactory = null).newBuilder().apply(customize).build())
        return KtorRaftHttpExecutor(
            clients = lanes.ktorProvider(),
            requestLifetimeCoordinator = RaftNetworkRequestLifetimeCoordinator(),
            transportFacts = { null },
            engineLabel = "ktor_okhttp",
        )
    }

    private class ArrayChunkSource(private val bytes: ByteArray) : build.raft.app.data.RaftPlatformFileChunkSource {
        private var offset = 0

        override suspend fun read(maxBytes: Int): ByteArray {
            val end = minOf(bytes.size, offset + maxBytes)
            return bytes.copyOfRange(offset, end).also { offset = end }
        }

        override fun close() = Unit
    }

    private suspend fun KtorRaftHttpExecutor.get(path: String) =
        execute(RaftRequestPriority.Visible, server.url(path).toString(), "GET", emptyMap(), null, false)

    private fun executorAgainst(twoRoutes: Boolean, dns: Dns? = null): KtorRaftHttpExecutor {
        val resolver =
            dns ?: if (twoRoutes) {
                // OkHttp only re-runs a call when there is a route to retry on, as on a real dual-address host.
                Dns { host -> Dns.SYSTEM.lookup(host).let { it + it } }
            } else {
                Dns { host -> listOf(InetAddress.getByName(host)) }
            }
        val base = buildRaftBaseOkHttpClient(eventListenerFactory = null).newBuilder().dns(resolver).build()
        return KtorRaftHttpExecutor(
            warningLogger = { debugLogs += it },
            clients = laneProvider(base),
            requestLifetimeCoordinator = RaftNetworkRequestLifetimeCoordinator(),
            transportFacts = { null },
            engineLabel = "ktor_okhttp",
        )
    }

    private fun laneProvider(base: OkHttpClient): RaftKtorClientProvider {
        val clients = mutableMapOf<Pair<RaftRequestPriority, RaftKtorCallKind>, HttpClient>()
        return RaftKtorClientProvider { priority, kind ->
            synchronized(clients) {
                clients.getOrPut(priority to kind) {
                    raftKtorClientFor(buildRaftLaneOkHttpClient(base, raftLaneDispatcher(priority), kind))
                }
            }
        }
    }
}
