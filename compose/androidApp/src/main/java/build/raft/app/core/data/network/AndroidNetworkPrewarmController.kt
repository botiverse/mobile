package build.raft.app.core.data.network

import android.content.Context
import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.network.HEADER_AUTHORIZATION
import build.raft.app.network.HEADER_SERVER_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object AndroidNetworkPrewarmController {
    private const val LOG_TAG = "SlockNetworkPrewarm"
    internal const val MIN_PREWARM_INTERVAL_MILLIS = 2L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var prewarmJob: Job? = null
    private var lastAttemptAtMillis: Long? = null

    fun prewarmForegroundConnection(
        context: Context,
        source: String = "unknown"
    ) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (!shouldStartPrewarm(now, lastAttemptAtMillis, MIN_PREWARM_INTERVAL_MILLIS)) {
                RaftAppLog.d(LOG_TAG, "network_prewarm_skip reason=rate_limited source=$source")
                return
            }
            if (prewarmJob?.isActive == true) {
                RaftAppLog.d(LOG_TAG, "network_prewarm_skip reason=in_flight source=$source")
                return
            }
            lastAttemptAtMillis = now
            prewarmJob =
                scope.launch {
                    try {
                        runPrewarm(appContext, source)
                    } finally {
                        synchronized(lock) {
                            if (prewarmJob === coroutineContext[Job]) {
                                prewarmJob = null
                            }
                        }
                    }
                }
        }
    }

    fun cancel(reason: String = "unknown") {
        val job =
            synchronized(lock) {
                prewarmJob.also { prewarmJob = null }
            }
        job?.cancel()
        RaftAppLog.d(LOG_TAG, "network_prewarm_cancel reason=$reason")
    }

    private suspend fun runPrewarm(
        context: Context,
        source: String
    ) {
        val startedAt = System.currentTimeMillis()
        val sessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(context))
        val session = sessionStore.readSession()
        val accessToken = session?.accessToken?.takeIf { it.isNotBlank() }
        val serverId = session?.currentServerId?.takeIf { it.isNotBlank() }
        if (accessToken == null || serverId == null) {
            RaftAppLog.d(
                LOG_TAG,
                "network_prewarm_skip reason=missing_session source=$source hasToken=${accessToken != null} hasServer=${serverId != null}"
            )
            return
        }

        val baseUrl = AndroidAuthApiFactory.currentBaseUrl(context)
        val url = "${baseUrl.trimEnd('/')}/auth/me"
        RaftAppLog.d(LOG_TAG, "network_prewarm_start source=$source path=auth/me")
        try {
            val response =
                RealRaftNetworkClientFactory
                    .create(sessionStore, baseUrl = baseUrl)
                    .requestRawAbsoluteUrl(
                        priority = DataRequestPriority.Background,
                        url = url,
                        headers =
                            mapOf(
                                HEADER_AUTHORIZATION to "Bearer $accessToken",
                                HEADER_SERVER_ID to serverId
                            )
                    )
            RaftAppLog.w(
                LOG_TAG,
                "endpoint category=network_prewarm path=auth/me method=GET " +
                    "requestBytes=0 responseBytes=${response.bodyText?.encodeToByteArray()?.size ?: 0} " +
                    "errorBytes=${response.errorBodyText?.encodeToByteArray()?.size ?: 0} " +
                    "code=${response.code} durationMs=${System.currentTimeMillis() - startedAt} " +
                    "result=${if (response.isSuccessful) "success" else "http_or_network_error"}"
            )
            RaftAppLog.d(
                LOG_TAG,
                "network_prewarm_result source=$source path=auth/me code=${response.code} " +
                    "durationMs=${System.currentTimeMillis() - startedAt} success=${response.isSuccessful}"
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            RaftAppLog.w(
                LOG_TAG,
                "network_prewarm_failed source=$source path=auth/me " +
                    "durationMs=${System.currentTimeMillis() - startedAt} reason=${error::class.simpleName.orEmpty()}"
            )
        }
    }

    internal fun shouldStartPrewarm(
        nowMillis: Long,
        lastAttemptAtMillis: Long?,
        minIntervalMillis: Long = MIN_PREWARM_INTERVAL_MILLIS
    ): Boolean {
        val lastAttempt = lastAttemptAtMillis ?: return true
        return nowMillis - lastAttempt >= minIntervalMillis
    }
}
