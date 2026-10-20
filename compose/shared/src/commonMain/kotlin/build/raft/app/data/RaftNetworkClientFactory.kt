package build.raft.app.data

/**
 * Public constructor for the shared network client (task #12): host modules
 * (e.g. the Android home-screen widgets) build the same seam-backed client the
 * app shell uses, instead of maintaining a parallel legacy HTTP stack.
 */
fun createRaftNetworkClient(
    sessionStore: RaftAuthSessionStore,
    baseUrl: String = RAFT_API_BASE_URL,
    warningLogger: (String) -> Unit = {}
): RaftNetworkClient =
    SharedRaftNetworkClient(
        sessionStore = sessionStore,
        baseUrl = baseUrl,
        warningLogger = warningLogger
    )
