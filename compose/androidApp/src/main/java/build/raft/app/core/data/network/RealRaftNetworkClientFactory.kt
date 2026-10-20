package build.raft.app.core.data.network

import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.createRaftNetworkClient

/**
 * Widget-process entry to the shared network client. Since task #12 this is
 * the same seam-backed NetworkKMM client the app shell uses — the parallel
 * legacy Ktor stack is gone.
 */
object RealRaftNetworkClientFactory {
    /**
     * [baseUrl] has no default: a production fallback sent a self-hosted session's token to production
     * (the home-screen widgets did). Pass [AndroidAuthApiFactory.currentBaseUrl] for the live environment.
     */
    fun create(
        store: RaftAuthSessionStore,
        baseUrl: String,
    ): RaftNetworkClient =
        createRaftNetworkClient(
            sessionStore = store,
            baseUrl = baseUrl,
            warningLogger = { message -> RaftAppLog.w(TAG, message) }
        )

    private const val TAG = "SlockNetworkClient"
}
