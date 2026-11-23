package build.raft.app

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CookieJar
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies JVM/Android unfollowed authorize fetch ([raftFetchAuthorizeUnfollowed] /
 * [buildRaftAuthorizeFetchOkHttpClient]): redirects are never followed, no cookies are stored or
 * sent, `Location` and status code are returned verbatim, and invalid URLs or transport errors
 * fail closed to null.
 */
class RaftAuthorizeFetchJvmTest {
    private val server = MockWebServer()

    @BeforeTest
    fun startServer() {
        server.start()
    }

    @AfterTest
    fun stopServer() {
        runCatching { server.close() }
    }

    @Test
    fun clientDisablesRedirectsCookiesAndCache() {
        val client = buildRaftAuthorizeFetchOkHttpClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
    }

    @Test
    fun redirectIsNotFollowedAndReturnsStatusAndLocationWithoutCookies() = runBlocking {
        val target = "https://raft.example/oauth/authorize?client_id=app1&state=xyz"
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", target)
                .addHeader("Set-Cookie", "sid=secret; Path=/")
                .body("ignored body")
                .build()
        )
        // Second response must never be fetched if redirect is not followed.
        server.enqueue(MockResponse.Builder().code(200).body("must not follow").build())

        val result = raftFetchAuthorizeUnfollowed(server.url("/authorize").toString())

        assertEquals(RaftAuthorizeResponse(status = 302, location = target), result)
        assertEquals(1, server.requestCount)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("text/html", recorded.headers["Accept"])
        assertNull(recorded.headers["Cookie"])

        // A second request against the same server still carries no Cookie from the first Set-Cookie.
        val secondResult = raftFetchAuthorizeUnfollowed(server.url("/second").toString())
        assertEquals(RaftAuthorizeResponse(status = 200, location = null), secondResult)
        assertEquals(2, server.requestCount)
        assertNull(server.takeRequest().headers["Cookie"])
    }

    @Test
    fun rejectsNonHttpOrMalformedUrlsAndReturnsNullOnConnectionFailure() = runBlocking {
        assertNull(raftFetchAuthorizeUnfollowed(""))
        assertNull(raftFetchAuthorizeUnfollowed("   "))
        assertNull(raftFetchAuthorizeUnfollowed("javascript:alert(1)"))
        assertNull(raftFetchAuthorizeUnfollowed("file:///etc/passwd"))
        assertNull(raftFetchAuthorizeUnfollowed("not a url"))

        val refusedUrl = server.url("/refused").toString()
        server.close()
        assertNull(raftFetchAuthorizeUnfollowed(refusedUrl))
    }
}
