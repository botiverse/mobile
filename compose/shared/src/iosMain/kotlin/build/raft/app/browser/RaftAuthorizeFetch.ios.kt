@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package build.raft.app

import kotlin.coroutines.resume
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.UnsafeNumber
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.*
import platform.darwin.NSObject

internal actual suspend fun raftFetchAuthorizeUnfollowed(url: String): RaftAuthorizeResponse? {
    val nsUrl = NSURL.URLWithString(url.trim()) ?: return null
    val scheme = nsUrl.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val timeoutSeconds = RAFT_AUTHORIZE_FETCH_TIMEOUT_MS / 1000.0
    val request =
        NSMutableURLRequest.requestWithURL(
            nsUrl,
            cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
            timeoutInterval = timeoutSeconds,
        ).apply {
            setHTTPMethod("GET")
            setValue("text/html", forHTTPHeaderField = "Accept")
        }
    val configuration =
        NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
            timeoutIntervalForRequest = timeoutSeconds
            timeoutIntervalForResource = timeoutSeconds
            waitsForConnectivity = false
            requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
            URLCache = null
            HTTPShouldSetCookies = false
            HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
            HTTPCookieStorage = null
        }
    return withTimeoutOrNull(RAFT_AUTHORIZE_FETCH_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val state = RaftIosAuthorizeFetchState(continuation)
            val delegate = RaftIosAuthorizeFetchDelegate(state::finish)
            val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
            val session = NSURLSession.sessionWithConfiguration(configuration, delegate, delegateQueue = queue)
            state.attach(session)
            continuation.invokeOnCancellation { state.cancel() }
            val task = session.dataTaskWithRequest(request)
            task.resume()
        }
    }
}

private class RaftIosAuthorizeFetchState(
    private val continuation: CancellableContinuation<RaftAuthorizeResponse?>,
) {
    private val lock = SynchronizedObject()
    private var settled = false
    private var session: NSURLSession? = null

    fun attach(activeSession: NSURLSession) {
        val cancelImmediately =
            synchronized(lock) {
                session = activeSession
                settled
            }
        if (cancelImmediately) runCatching { activeSession.invalidateAndCancel() }
    }

    fun finish(result: RaftAuthorizeResponse?, activeSession: NSURLSession?) {
        val toCancel =
            synchronized(lock) {
                if (settled) return
                settled = true
                activeSession ?: session
            }
        runCatching { toCancel?.invalidateAndCancel() }
        if (continuation.isActive) continuation.resume(result)
    }

    fun cancel() {
        val toCancel =
            synchronized(lock) {
                if (settled) return
                settled = true
                session
            }
        runCatching { toCancel?.invalidateAndCancel() }
    }
}

@OptIn(UnsafeNumber::class)
private class RaftIosAuthorizeFetchDelegate(
    private val onFinish: (RaftAuthorizeResponse?, NSURLSession) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        val response =
            RaftAuthorizeResponse(
                status = willPerformHTTPRedirection.statusCode.toInt(),
                location = willPerformHTTPRedirection.valueForHTTPHeaderField("Location"),
            )
        completionHandler(null)
        onFinish(response, session)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData,
    ) {
        val http = dataTask.response as? NSHTTPURLResponse
        onFinish(
            http?.let {
                RaftAuthorizeResponse(
                    status = it.statusCode.toInt(),
                    location = it.valueForHTTPHeaderField("Location"),
                )
            },
            session,
        )
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        if (didCompleteWithError != null) {
            onFinish(null, session)
            return
        }
        val http = task.response as? NSHTTPURLResponse
        onFinish(
            http?.let {
                RaftAuthorizeResponse(
                    status = it.statusCode.toInt(),
                    location = it.valueForHTTPHeaderField("Location"),
                )
            },
            session,
        )
    }
}
