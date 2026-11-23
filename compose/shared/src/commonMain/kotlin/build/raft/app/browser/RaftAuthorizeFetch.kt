package build.raft.app

/** Timeout for the unfollowed GET to Raft's authorize endpoint (slock desktop `AUTHORIZE_FETCH_TIMEOUT_MS`). */
internal const val RAFT_AUTHORIZE_FETCH_TIMEOUT_MS: Long = 10_000L

/**
 * Issues a single unauthenticated `GET` to [url] (Raft's authorize endpoint, #proj-workspace:#356 §5):
 * no cookies, no cache, no redirect followed, `Accept: text/html`, [RAFT_AUTHORIZE_FETCH_TIMEOUT_MS]
 * timeout, and the response body left unread. Returns the status code and `Location` header, or
 * null on malformed URL, timeout, or transport failure.
 */
internal expect suspend fun raftFetchAuthorizeUnfollowed(url: String): RaftAuthorizeResponse?
