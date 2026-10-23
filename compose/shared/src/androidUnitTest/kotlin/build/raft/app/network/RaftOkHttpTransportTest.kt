@file:OptIn(okhttp3.ExperimentalOkHttpApi::class)

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
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy
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
        runCatching { server.shutdown() }
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
        server.enqueue(MockResponse.Builder().socketPolicy(SocketPolicy.DisconnectAfterRequest).build())
        server.enqueue(MockResponse.Builder().body("retry success").build())

        assertEquals("seed connection pool", executor.get("/seed").bodyText)
        val get = executor.get("/read")
        assertEquals(200, get.code, get.transportDiagnostics)
        assertEquals("retry success", get.bodyText)
        assertEquals(3, server.requestCount)

        server.enqueue(MockResponse.Builder().socketPolicy(SocketPolicy.DisconnectAfterRequest).build())
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
        server.shutdown()
        val refused = executor.execute(RaftRequestPriority.Visible, refusedUrl, "GET", emptyMap(), null, false)
        assertTrue("kind=CONNECT " in refused.transportDiagnostics.orEmpty(), refused.transportDiagnostics)
    }

    @Test
    fun readTimeoutFromTheEngineClassifiesAsTimeout() = runBlocking {
        // The property under test is the exception chain Ktor + OkHttp produce for a stalled response,
        // not a duration: the server never answers and the socket timeout ends the call.
        server.enqueue(MockResponse.Builder().socketPolicy(SocketPolicy.NoResponse).build())
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
