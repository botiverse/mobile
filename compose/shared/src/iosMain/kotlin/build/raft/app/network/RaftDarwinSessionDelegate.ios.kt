package build.raft.app.network

import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.UnsafeNumber
// Foundation categories (NSHTTPURLRequest, NSExtendedDate) import as extensions.
import platform.Foundation.*
import platform.darwin.NSObject

/**
 * The lane session's delegate. NSURLSession takes one delegate, and Ktor's [KtorNSURLSessionDelegate]
 * must receive the calls it documents as required (data, completion, redirect, challenge), so this
 * forwards those unchanged and adds two of its own:
 *
 * - `didSendBodyData`: upload progress as NSURLSession sends the body (Hands 1b6dd022). Counting at the
 *   body writer reported 100% at once: Ktor buffers a WriteChannelContent in a ~1 MB channel before the
 *   4 KB stream NSURLSession reads, so a photo was "sent" before its first byte left the device.
 * - `didFinishCollectingMetrics`: the real protocol, connection reuse and DNS / connect / TLS times
 *   (Ktor's Darwin response always says HTTP/1.1), returned once per `x-request-id`.
 *
 * WebSocket callbacks are not forwarded: app sessions never open a WebSocket (realtime is Socket.IO in Swift).
 */
@OptIn(UnsafeNumber::class)
internal class RaftDarwinSessionDelegate(
    private val ktor: KtorNSURLSessionDelegate,
) : NSObject(), NSURLSessionDataDelegateProtocol {

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        ktor.URLSession(session, dataTask, didReceiveData)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        ktor.URLSession(session, task, didCompleteWithError)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit
    ) {
        ktor.URLSession(session, task, willPerformHTTPRedirection, newRequest, completionHandler)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit
    ) {
        ktor.URLSession(session, task, didReceiveChallenge, completionHandler)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didSendBodyData: Long,
        totalBytesSent: Long,
        totalBytesExpectedToSend: Long
    ) {
        RaftDarwinUploadProgress.report(task, totalBytesSent, totalBytesExpectedToSend)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didFinishCollectingMetrics: NSURLSessionTaskMetrics
    ) {
        RaftDarwinTransportFacts.record(task, didFinishCollectingMetrics)
    }
}

/**
 * Upload progress measured by NSURLSession. A request that carries [HEADER] has it removed before the
 * task is created (it never reaches the wire) and the listener id kept as an NSURLProtocol property of
 * the request, which the task's `originalRequest` carries to `didSendBodyData`. The listener gets the
 * raw counts NSURLSession reports: bytes of the body sent so far and its total (null when unknown).
 */
internal object RaftDarwinUploadProgress {
    const val HEADER = "X-Raft-Upload-Progress"
    private const val PROPERTY_KEY = "build.raft.app.uploadProgressId"

    private val lock = SynchronizedObject()
    private val listeners = HashMap<String, (RaftTransferProgress) -> Unit>()
    private val nextId = atomic(0L)

    val tracker =
        RaftUploadProgressTracker { listener ->
            if (listener == null) {
                RaftUploadProgressRoute(null, null) {}
            } else {
                val id = nextId.incrementAndGet().toString()
                synchronized(lock) { listeners[id] = listener }
                RaftUploadProgressRoute(null, HEADER to id) { synchronized(lock) { listeners.remove(id) } }
            }
        }

    /** Ktor `configureRequest`: runs on every request after Ktor built it, before the task exists. */
    fun moveMarkerToProperty(request: NSMutableURLRequest) {
        val id = request.valueForHTTPHeaderField(HEADER) ?: return
        request.setValue(null, forHTTPHeaderField = HEADER)
        NSURLProtocol.setProperty(id, forKey = PROPERTY_KEY, inRequest = request)
    }

    fun report(task: NSURLSessionTask, totalBytesSent: Long, totalBytesExpectedToSend: Long) {
        val request = task.originalRequest ?: return
        val id = NSURLProtocol.propertyForKey(PROPERTY_KEY, inRequest = request) as? String ?: return
        val listener = synchronized(lock) { listeners[id] } ?: return
        runCatching {
            listener(
                RaftTransferProgress(
                    bytesTransferred = totalBytesSent,
                    bytesTotal = totalBytesExpectedToSend.takeIf { it >= 0L },
                )
            )
        }
    }
}

/**
 * Connection facts from NSURLSessionTaskMetrics, keyed by the request's `x-request-id`, in the same
 * `key=value` vocabulary as Android's OkHttp facts. Bounded: entries nobody takes (streamed GETs do not
 * ask) are evicted oldest first after [MAX_ENTRIES].
 */
internal object RaftDarwinTransportFacts {
    private const val MAX_ENTRIES = 256
    private const val REQUEST_ID_HEADER = "x-request-id"

    private val lock = SynchronizedObject()
    private val completed = LinkedHashMap<String, String>()

    fun record(task: NSURLSessionTask, metrics: NSURLSessionTaskMetrics) {
        val requestId = task.originalRequest?.valueForHTTPHeaderField(REQUEST_ID_HEADER) ?: return
        val transaction = metrics.transactionMetrics.lastOrNull() as? NSURLSessionTaskTransactionMetrics ?: return
        val facts =
            "reusedConnection=${transaction.reusedConnection} " +
                "dnsMs=${millisBetween(transaction.domainLookupStartDate, transaction.domainLookupEndDate)} " +
                "connectTotalMs=${millisBetween(transaction.connectStartDate, transaction.connectEndDate)} " +
                "tlsMs=${millisBetween(transaction.secureConnectionStartDate, transaction.secureConnectionEndDate)} " +
                "responseWaitMs=${millisBetween(transaction.requestEndDate, transaction.responseStartDate)} " +
                "negotiatedProtocol=${transaction.networkProtocolName?.toSafeDiagnosticsToken() ?: "none"} " +
                "transactions=${metrics.transactionMetrics.size}"
        synchronized(lock) {
            completed.remove(requestId)
            completed[requestId] = facts
            while (completed.size > MAX_ENTRIES) {
                completed.remove(completed.keys.first())
            }
        }
    }

    fun take(requestId: String): String? =
        if (requestId.isEmpty()) null else synchronized(lock) { completed.remove(requestId) }

    private fun millisBetween(start: NSDate?, end: NSDate?): Long =
        if (start == null || end == null) 0L else (end.timeIntervalSinceDate(start) * 1000.0).toLong().coerceAtLeast(0L)
}
