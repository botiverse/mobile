package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GzipAcceptEncodingTest {
    @Test
    fun opensGzipOnlyWherePlatformNeedsTheExplicitOptIn() {
        // OHOS-style: libcurl wrapper needs the header to negotiate + decompress.
        assertEquals("gzip", resolveGzipAcceptEncoding(platformNeedsExplicit = true, callerHeaders = emptyMap()))
        // Android/iOS: engines auto-negotiate; setting the header would break
        // their transparent decompression, so leave the request untouched.
        assertNull(resolveGzipAcceptEncoding(platformNeedsExplicit = false, callerHeaders = emptyMap()))
    }

    @Test
    fun neverOverridesAnAcceptEncodingTheCallerAlreadySet() {
        assertNull(
            resolveGzipAcceptEncoding(
                platformNeedsExplicit = true,
                callerHeaders = mapOf("Accept-Encoding" to "identity")
            )
        )
        // Case-insensitive: header names are not normalized upstream.
        assertNull(
            resolveGzipAcceptEncoding(
                platformNeedsExplicit = true,
                callerHeaders = mapOf("accept-encoding" to "br")
            )
        )
    }

    @Test
    fun unrelatedCallerHeadersDoNotBlockTheOptIn() {
        assertEquals(
            "gzip",
            resolveGzipAcceptEncoding(
                platformNeedsExplicit = true,
                callerHeaders = mapOf("Authorization" to "Bearer x", "Content-Type" to "application/json")
            )
        )
    }
}
