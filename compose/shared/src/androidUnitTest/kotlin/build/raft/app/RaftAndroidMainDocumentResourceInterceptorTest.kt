package build.raft.app

import build.raft.webcontainer.resource.WebResourceRequest
import build.raft.webcontainer.resource.WebResourceSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RaftAndroidMainDocumentResourceInterceptorTest {
    @Test
    fun interceptsMainFrameGetOnlyAndFallsBackSafely() {
        val previous = RaftAndroidWebViewHooks.mainDocumentProvider
        try {
            RaftAndroidWebViewHooks.mainDocumentProvider = { url ->
                when (url) {
                    "https://app.raft.build/release-notes" ->
                        RaftWebViewDocument("<html>ok</html>".encodeToByteArray(), "text/html", "cache")
                    "https://app.raft.build/boom" -> error("disk read error")
                    else -> null
                }
            }

            val hit =
                RaftAndroidMainDocumentResourceInterceptor.intercept(
                    WebResourceRequest(url = "https://app.raft.build/release-notes", method = "GET", isMainFrame = true),
                )
            assertNotNull(hit)
            assertEquals("text/html", hit.mimeType)
            assertEquals(WebResourceSource.HOST_CACHE, hit.source)
            assertContentEquals("<html>ok</html>".encodeToByteArray(), hit.body)

            // Subframes and non-GET requests are never intercepted.
            assertNull(
                RaftAndroidMainDocumentResourceInterceptor.intercept(
                    WebResourceRequest(url = "https://app.raft.build/release-notes", method = "GET", isMainFrame = false),
                ),
            )
            assertNull(
                RaftAndroidMainDocumentResourceInterceptor.intercept(
                    WebResourceRequest(url = "https://app.raft.build/release-notes", method = "POST", isMainFrame = true),
                ),
            )
            // Provider errors fail safe to null (network fallback).
            assertNull(
                RaftAndroidMainDocumentResourceInterceptor.intercept(
                    WebResourceRequest(url = "https://app.raft.build/boom", method = "GET", isMainFrame = true),
                ),
            )
        } finally {
            RaftAndroidWebViewHooks.mainDocumentProvider = previous
        }
    }
}
