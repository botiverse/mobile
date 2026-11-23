package build.raft.app

import build.raft.app.network.buildRaftBaseOkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal fun buildRaftAuthorizeFetchOkHttpClient(
    base: OkHttpClient = buildRaftBaseOkHttpClient(eventListenerFactory = null),
): OkHttpClient =
    base.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(RAFT_AUTHORIZE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(RAFT_AUTHORIZE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(RAFT_AUTHORIZE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(RAFT_AUTHORIZE_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

private val authorizeFetchClient: OkHttpClient by lazy { buildRaftAuthorizeFetchOkHttpClient() }

internal actual suspend fun raftFetchAuthorizeUnfollowed(url: String): RaftAuthorizeResponse? =
    raftFetchAuthorizeUnfollowedWithClient(url, authorizeFetchClient)

internal suspend fun raftFetchAuthorizeUnfollowedWithClient(
    url: String,
    client: OkHttpClient,
): RaftAuthorizeResponse? {
    val parsed = url.trim().toHttpUrlOrNull() ?: return null
    val request =
        Request.Builder()
            .url(parsed)
            .get()
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "text/html")
            .build()
    return suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result =
                        response.use { res ->
                            // Leave the response body unread; closing cancels the stream without buffering.
                            RaftAuthorizeResponse(status = res.code, location = res.header("Location"))
                        }
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        )
    }
}
