package build.raft.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import platform.Foundation.NSHTTPCookieAcceptPolicy
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration

/**
 * iOS app HTTP: Ktor's Darwin engine on NSURLSession (artin 2026-10-23). One session per
 * [RaftRequestPriority] lane, so Background work queues behind its own per-host limit:
 *
 * - `timeoutIntervalForRequest` [RAFT_DARWIN_IDLE_TIMEOUT_SECONDS]: NSURLSession's idle timeout
 *   (no byte either way); uploads raise it per request through Ktor's socket timeout. Ktor's request
 *   timeout keeps the whole-call budget (30 s ordinary, 3 min multipart, 30 min presigned PUT).
 *   NSURLSession has no separate connect timeout; the idle timeout bounds the connect too.
 * - `waitsForConnectivity = false`: a foreground request fails at once while offline instead of
 *   waiting for a network, so the app's own retry and offline UI stay in charge.
 * - `HTTPMaximumConnectionsPerHost` per lane; HTTP/2 multiplexes on top of that.
 * - No URL cache and no cookies, like curl: every API response is fresh and auth stays in headers.
 * - System proxy / PAC and system trust apply (curl was direct with a pinned CA bundle).
 * Dead HTTP/2 connections are detected by NSURLSession's own connection health handling.
 *
 * Each lane builds its own session around [RaftDarwinSessionDelegate], which forwards Ktor's callbacks
 * and adds upload progress from NSURLSession (`didSendBodyData`) and connection facts from its task
 * metrics.
 */
internal const val RAFT_DARWIN_IDLE_TIMEOUT_SECONDS = 20.0
internal const val RAFT_DARWIN_RESOURCE_TIMEOUT_SECONDS = 1_800.0

private fun raftDarwinMaxConnectionsPerHost(priority: RaftRequestPriority): Long =
    when (priority) {
        RaftRequestPriority.Foreground -> 6L
        RaftRequestPriority.Visible -> 4L
        RaftRequestPriority.Background -> 2L
    }

internal actual fun createPlatformRaftKtorClientProvider(): RaftKtorClientProvider {
    val clients =
        RaftRequestPriority.entries.associateWith { priority ->
            val ktorDelegate = KtorNSURLSessionDelegate()
            val configuration = NSURLSessionConfiguration.defaultSessionConfiguration()
            configureRaftSession(configuration, priority)
            // Serial, as NSURLSession asks of a delegate queue: a task's metrics are then recorded before
            // its completion releases the caller. Ktor's callbacks only hand chunks to unbounded channels.
            val delegateQueue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
            val session =
                NSURLSession.sessionWithConfiguration(
                    configuration,
                    RaftDarwinSessionDelegate(ktorDelegate),
                    delegateQueue = delegateQueue
                )
            HttpClient(Darwin) {
                configureRaftKtorClient()
                engine {
                    usePreconfiguredSession(session, ktorDelegate)
                    configureRequest { RaftDarwinUploadProgress.moveMarkerToProperty(this) }
                }
            }
        }
    // Presigned PUTs and non-idempotent calls share the lane session: NSURLSession never re-sends a
    // request body on its own, and it chooses the protocol per host.
    return RaftKtorClientProvider { priority, _ -> clients.getValue(priority) }
}

private fun configureRaftSession(configuration: NSURLSessionConfiguration, priority: RaftRequestPriority) {
    configuration.timeoutIntervalForRequest = RAFT_DARWIN_IDLE_TIMEOUT_SECONDS
    configuration.timeoutIntervalForResource = RAFT_DARWIN_RESOURCE_TIMEOUT_SECONDS
    configuration.waitsForConnectivity = false
    configuration.HTTPMaximumConnectionsPerHost = raftDarwinMaxConnectionsPerHost(priority)
    configuration.requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
    configuration.URLCache = null
    configuration.HTTPShouldSetCookies = false
    configuration.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
    configuration.HTTPCookieStorage = null
}

internal actual fun raftKtorEngineLabel(): String = "ktor_darwin"

// Ktor's Darwin engine reports HTTP/1.1 for every response; the real protocol needs NSURLSessionTaskMetrics.
internal actual fun raftKtorEngineReportsProtocol(): Boolean = false

internal actual fun classifyPlatformTransportFailure(error: Throwable): RaftTransportFailureKind? {
    val origin = (error as? DarwinHttpRequestException)?.origin ?: return null
    if (origin.domain != NSURLErrorDomain) return null
    return when (origin.code.toInt()) {
        -1001 -> RaftTransportFailureKind.TIMEOUT // NSURLErrorTimedOut
        -1003, -1006 -> RaftTransportFailureKind.DNS // CannotFindHost, DNSLookupFailed
        -1004, -1009, -1018, -1020 -> RaftTransportFailureKind.CONNECT // CannotConnectToHost, NotConnected, RoamingOff, DataNotAllowed
        -1200, -1201, -1202, -1203, -1204, -1205, -1206, -2000 -> RaftTransportFailureKind.TLS // secure connection / certificate errors
        -999 -> RaftTransportFailureKind.CANCELLED // NSURLErrorCancelled
        else -> null
    }
}

internal actual fun takeRaftKtorTransportFacts(requestId: String): String? = RaftDarwinTransportFacts.take(requestId)

/** Bytes NSURLSession has sent ([RaftDarwinUploadProgress]), not bytes handed to Ktor's body channel. */
internal actual fun platformRaftUploadProgressTracker(): RaftUploadProgressTracker = RaftDarwinUploadProgress.tracker
