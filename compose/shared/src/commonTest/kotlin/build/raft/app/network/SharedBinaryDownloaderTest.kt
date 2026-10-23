package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedBinaryDownloaderTest {
    @Test
    fun combineDownloadedChunksPreservesChunkOrderAndBytes() {
        val chunks =
            listOf(
                byteArrayOf(0x52, 0x41),
                byteArrayOf(0x46, 0x54),
                byteArrayOf(0x00, 0x7f)
            )

        assertContentEquals(
            byteArrayOf(0x52, 0x41, 0x46, 0x54, 0x00, 0x7f),
            combineDownloadedChunks(chunks, totalBytes = 6)
        )
    }

    @Test
    fun binaryDownloadUrlDiagnosticsRedactsPathAndQuery() {
        val diagnostics =
            binaryDownloadUrlDiagnostics(
                "https://api.slock.ai/api/attachments/download/sensitive-file-id.png?access_token=secret&signature=private"
            )

        assertTrue("scheme=https" in diagnostics)
        assertTrue("host=api.slock.ai" in diagnostics)
        assertTrue("pathHash=" in diagnostics)
        assertTrue("urlHash=" in diagnostics)
        assertFalse("sensitive-file-id" in diagnostics)
        assertFalse("access_token" in diagnostics)
        assertFalse("secret" in diagnostics)
        assertFalse("signature" in diagnostics)
        assertFalse("private" in diagnostics)
    }

    @Test
    fun conditionalRequestAddsOnlyExactTypedValidators() {
        val headers =
            sharedBinaryConditionalHeaders(
                validators =
                    SharedBinaryConditionalValidators(
                        eTag = "\"avatar-v1\"",
                        lastModified = "Wed, 05 Aug 2026 10:00:00 GMT"
                    )
            )

        assertEquals("*/*", headers["Accept"])
        assertEquals("\"avatar-v1\"", headers["If-None-Match"])
        assertEquals("Wed, 05 Aug 2026 10:00:00 GMT", headers["If-Modified-Since"])
        assertEquals(3, headers.size)

        assertEquals(mapOf("Accept" to "*/*"), sharedBinaryConditionalHeaders(null))
    }

    @Test
    fun conditionalResponseDistinguishes304From2xxAndRejectsRedirectOrError() {
        val notModified =
            assertIs<SharedBinaryConditionalDownloadResult.NotModified>(
                sharedBinaryConditionalResult(
                    statusCode = 304,
                    headers =
                        mapOf(
                            "etag" to listOf(" \"avatar-v1\" "),
                            "LAST-MODIFIED" to listOf("Wed, 05 Aug 2026 10:00:00 GMT")
                        )
                )
            )
        assertEquals(304, notModified.statusCode)
        assertEquals("\"avatar-v1\"", notModified.eTag)

        val modified =
            assertIs<SharedBinaryConditionalDownloadResult.Modified>(
                sharedBinaryConditionalResult(
                    statusCode = 206,
                    headers =
                        mapOf(
                            "Content-Type" to listOf("image/webp; charset=binary"),
                            "ETag" to listOf("\"avatar-v2\"")
                        )
                )
            )
        assertEquals(206, modified.statusCode)
        assertEquals("image/webp", modified.contentType)
        assertEquals("\"avatar-v2\"", modified.eTag)
        assertNull(sharedBinaryConditionalResult(302, emptyMap()))
        assertNull(sharedBinaryConditionalResult(404, emptyMap()))
    }

    @Test
    fun conditionalHttpFailureCarriesStatusWithoutChangingMessage() {
        listOf(404, 410, 401, 403, 429, 500).forEach { status ->
            val failure =
                assertIs<SharedBinaryConditionalHttpException>(
                    sharedBinaryConditionalFailure(
                        statusCode = status,
                        failurePrefix = "Conditional download failed",
                        safeErrorMessage = "none"
                    )
                )
            assertEquals(status, failure.statusCode)
            assertEquals("Conditional download failed: HTTP $status", failure.message)
        }
    }

    @Test
    fun conditionalTransportFailureRetainsUntypedIllegalStateContract() {
        listOf<Int?>(null, 0).forEach { status ->
            val failure =
                sharedBinaryConditionalFailure(
                    statusCode = status,
                    failurePrefix = "Conditional download failed",
                    safeErrorMessage = "timeout"
                )

            assertEquals(IllegalStateException::class, failure::class)
            assertEquals("Conditional download failed: HTTP ${status ?: 0} (timeout)", failure.message)
        }
    }
}
