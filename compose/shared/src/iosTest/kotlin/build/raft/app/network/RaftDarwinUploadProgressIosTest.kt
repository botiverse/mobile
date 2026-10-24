package build.raft.app.network

import platform.Foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iOS upload progress comes from NSURLSession's sent-byte counts, keyed by a marker that never hits the wire. */
class RaftDarwinUploadProgressIosTest {
    @Test
    fun markerLeavesTheWireRequestAndRoutesSentBytesToItsListener() {
        val received = mutableListOf<RaftTransferProgress>()
        val route = RaftDarwinUploadProgress.tracker.route { received += it }
        val marker = assertNotNull(route.networkHeader)
        assertNull(route.bodyWriterProgress)
        val request = NSMutableURLRequest.requestWithURL(NSURL.URLWithString("https://api.raft.test/api/attachments/upload")!!)
        request.setValue(marker.second, forHTTPHeaderField = marker.first)

        RaftDarwinUploadProgress.moveMarkerToProperty(request)
        // Never resumed: the task only carries the request the engine would send.
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request)
        RaftDarwinUploadProgress.report(task, totalBytesSent = 65_536L, totalBytesExpectedToSend = 320_360L)
        RaftDarwinUploadProgress.report(task, totalBytesSent = 320_360L, totalBytesExpectedToSend = -1L)
        route.release()
        RaftDarwinUploadProgress.report(task, totalBytesSent = 320_360L, totalBytesExpectedToSend = 320_360L)
        task.cancel()

        assertNull(task.originalRequest?.valueForHTTPHeaderField(RaftDarwinUploadProgress.HEADER))
        assertEquals(
            listOf(
                RaftTransferProgress(bytesTransferred = 65_536L, bytesTotal = 320_360L),
                // NSURLSessionTransferSizeUnknown is reported as an unknown total, not -1.
                RaftTransferProgress(bytesTransferred = 320_360L, bytesTotal = null),
            ),
            received
        )
    }

    @Test
    fun requestsWithoutAListenerAreLeftAlone() {
        val route = RaftDarwinUploadProgress.tracker.route(null)
        assertNull(route.networkHeader)
        val request = NSMutableURLRequest.requestWithURL(NSURL.URLWithString("https://api.raft.test/api/x")!!)
        request.setValue("mobile-1", forHTTPHeaderField = "x-request-id")

        RaftDarwinUploadProgress.moveMarkerToProperty(request)

        assertEquals("mobile-1", request.valueForHTTPHeaderField("x-request-id"))
        assertTrue(request.allHTTPHeaderFields?.keys?.none { it == RaftDarwinUploadProgress.HEADER } ?: true)
    }
}
