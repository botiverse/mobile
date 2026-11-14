package build.raft.realtime.socket

/**
 * A `connect_error` from the server's auth middleware (botiverse/slock `packages/server/src/socket/index.ts`
 * `io.use`), typed from the exact message string. The strings are generated from slock into
 * compose/realtime-contract/realtime_contract.json (`handshakeErrors`) and from there into
 * [GeneratedSocketHandshakeErrors]; there is no keyword matching.
 */
enum class RaftSocketHandshakeErrorKind(
    /** Retrying can never succeed with any token: stop until the app asks again ([RaftSocketIoClient.kick]). */
    val terminal: Boolean,
    /** The next attempt must use a refreshed access token (the auth provider is told so). */
    val requiresTokenRefresh: Boolean,
) {
    /** No usable `auth.token` (or `auth` was not an object). */
    AuthenticationRequired(terminal = false, requiresTokenRefresh = true),

    /** The token is not an access token. */
    InvalidTokenType(terminal = false, requiresTokenRefresh = true),

    /** Expired, revoked or unverifiable token. */
    InvalidOrExpiredToken(terminal = false, requiresTokenRefresh = true),

    /** The account changed (password, sign-out elsewhere) while the handshake ran: sign in again silently. */
    AuthenticationChanged(terminal = false, requiresTokenRefresh = true),

    /** Signed in, but not a member of `auth.serverId`. */
    NotServerMember(terminal = true, requiresTokenRefresh = false),

    /**
     * The server requires its SSO and this session was not signed in by it (RFC 083 §6.2). The token is
     * valid, so refreshing would loop; the next API call's 403 `SSO_REQUIRED` asks the person to sign in.
     */
    SsoRequired(terminal = true, requiresTokenRefresh = false),

    /** Any other message (a newer server's error, a proxy's). Retried with backoff, no refresh. */
    Unknown(terminal = false, requiresTokenRefresh = false),
}

data class RaftSocketHandshakeError(
    val kind: RaftSocketHandshakeErrorKind,
    /** The server's message, verbatim. Never contains a token. */
    val message: String,
) {
    val terminal: Boolean get() = kind.terminal
    val requiresTokenRefresh: Boolean get() = kind.requiresTokenRefresh

    companion object {
        fun fromMessage(message: String): RaftSocketHandshakeError =
            RaftSocketHandshakeError(kindOf(message), message)

        fun kindOf(message: String): RaftSocketHandshakeErrorKind =
            when (message) {
                GeneratedSocketHandshakeErrors.AuthenticationRequired -> RaftSocketHandshakeErrorKind.AuthenticationRequired
                GeneratedSocketHandshakeErrors.InvalidTokenType -> RaftSocketHandshakeErrorKind.InvalidTokenType
                GeneratedSocketHandshakeErrors.InvalidOrExpiredToken -> RaftSocketHandshakeErrorKind.InvalidOrExpiredToken
                GeneratedSocketHandshakeErrors.AuthenticationChangedReconnectRequired ->
                    RaftSocketHandshakeErrorKind.AuthenticationChanged
                GeneratedSocketHandshakeErrors.NotServerMember -> RaftSocketHandshakeErrorKind.NotServerMember
                GeneratedSocketHandshakeErrors.SsoRequired -> RaftSocketHandshakeErrorKind.SsoRequired
                else -> RaftSocketHandshakeErrorKind.Unknown
            }
    }
}
