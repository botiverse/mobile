package build.raft.app.core.data.network

import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.createSlockNetworkClient

/**
 * Widget-process entry to the shared network client. Since task #12 this is
 * the same seam-backed NetworkKMM client the app shell uses — the parallel
 * legacy Ktor stack is gone.
 */
object RealSlockNetworkClientFactory {
    /**
     * [baseUrl] has no default: a production fallback sent a self-hosted session's token to production
     * (the home-screen widgets did). Pass [AndroidAuthApiFactory.currentBaseUrl] for the live environment.
     */
    fun create(
        store: SlockAuthSessionStore,
        baseUrl: String,
    ): SlockNetworkClient =
        createSlockNetworkClient(
            sessionStore = store,
            baseUrl = baseUrl,
            warningLogger = { message -> SlockAppLog.w(TAG, message) }
        )

    private const val TAG = "SlockNetworkClient"
}
