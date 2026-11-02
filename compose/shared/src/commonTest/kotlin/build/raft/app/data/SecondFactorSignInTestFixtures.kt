package build.raft.app.data

/*
 * Wire payloads of the second step at sign-in, from botiverse/slock v1.21.44
 * (a5f6a26765 #12247): packages/server/src/services/secondFactor/signIn.ts
 * (`startSecondFactorSignIn`, `STATUS_OF` and its messages), routes/auth.ts
 * (`sendPasswordSignIn`, `sendWebSocialSignIn`) and the key sets pinned by
 * routes/secondFactorSignIn.api.test.ts (`assertChallenge`, the `/login` baseline, the
 * `/google/complete` baseline with `returnTo: "/s/home"`, `backupCodesRemaining: 9`, the lock's
 * `methods`).
 */

/** A 43-character base64url token, as `createAuthChallenge` mints (32 random bytes). */
internal const val SECOND_FACTOR_CHALLENGE_FIXTURE = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

internal const val SECOND_FACTOR_EXPIRES_AT_FIXTURE = "2026-11-02T08:05:00.000Z"
internal const val SECOND_FACTOR_EXPIRES_AT_MILLIS_FIXTURE = 1_793_606_700_000L

internal fun secondFactorChallengeBody(methods: String = """["totp","backup_code"]"""): String =
    """{"secondFactorRequired":true,"challenge":"$SECOND_FACTOR_CHALLENGE_FIXTURE","expiresAt":"$SECOND_FACTOR_EXPIRES_AT_FIXTURE","methods":$methods,"maskedEmail":"a***@example.com"}"""

/** `/second-factor/verify` after `/login`: the `/login` body, plus the count after a backup code. */
internal const val SECOND_FACTOR_PASSWORD_VERIFIED_BODY =
    """{"user":{"id":"user-1","email":"alice@example.com","displayName":"Alice"},"accessToken":"access-2fa","refreshToken":"refresh-2fa","backupCodesRemaining":9}"""

/** `/second-factor/verify` after `/google/complete`: the web social body. */
internal const val SECOND_FACTOR_SOCIAL_VERIFIED_BODY =
    """{"provider":"google","mode":"login","returnTo":"/s/home","accessToken":"access-2fa","refreshToken":"refresh-2fa"}"""

internal const val SECOND_FACTOR_CODE_INVALID_BODY =
    """{"error":"That code didn't work. Check your authenticator app and try again.","code":"second_factor_code_invalid"}"""

internal const val SECOND_FACTOR_EXPIRED_BODY =
    """{"error":"This sign-in has expired. Sign in again.","code":"challenge_expired"}"""

internal fun secondFactorPending(
    flow: SecondFactorSignInFlow = SecondFactorSignInFlow.Password,
    methods: List<String> = listOf("totp", "backup_code"),
): SecondFactorSignInPending =
    SecondFactorSignInPending(
        challenge =
            SecondFactorSignInChallenge(
                challenge = SECOND_FACTOR_CHALLENGE_FIXTURE,
                expiresAtEpochMillis = SECOND_FACTOR_EXPIRES_AT_MILLIS_FIXTURE,
                methods = methods,
                maskedEmail = "a***@example.com",
            ),
        flow = flow,
    )

/**
 * A [RaftAuthTransport] that answers the sign-in routes from fixed bodies (and records the
 * verify bodies it got); the rest of the session finish (`/auth/me`, `/servers`,
 * `/servers/order`) answers like a signed-in account with one server.
 */
internal class SecondFactorSignInScriptedTransport(
    private val firstStep: AuthNetworkResponse,
    private val verifyResponses: ArrayDeque<AuthNetworkResponse> = ArrayDeque(),
) : RaftAuthTransport {
    val verifyBodies = mutableListOf<String>()
    val serversAuthorizations = mutableListOf<String>()

    override suspend fun login(bodyText: String): AuthNetworkResponse = firstStep

    override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse = firstStep

    override suspend fun verifySecondFactorSignIn(bodyText: String): AuthNetworkResponse {
        verifyBodies += bodyText
        return verifyResponses.removeFirstOrNull() ?: AuthNetworkResponse(500, null)
    }

    override suspend fun currentUser(authorization: String): AuthNetworkResponse =
        AuthNetworkResponse(200, """{"id":"user-1","email":"alice@example.com","displayName":"Alice"}""")

    override suspend fun servers(authorization: String): AuthNetworkResponse {
        serversAuthorizations += authorization
        return AuthNetworkResponse(200, """[{"id":"server-1","name":"Raft","slug":"raft","role":"owner"}]""")
    }

    override suspend fun serverOrder(authorization: String): AuthNetworkResponse =
        AuthNetworkResponse(200, """{"serverOrder":[]}""")

    override suspend fun socialAuthProviders(platform: String): AuthNetworkResponse = AuthNetworkResponse(501, null)

    override suspend fun startMobileOAuthLink(provider: String, bodyText: String, authorization: String): AuthNetworkResponse =
        AuthNetworkResponse(501, null)

    override suspend fun completeMobileOAuthLink(provider: String, bodyText: String, authorization: String): AuthNetworkResponse =
        AuthNetworkResponse(501, null)

    override suspend fun resendVerification(authorization: String): AuthNetworkResponse = AuthNetworkResponse(501, null)

    override suspend fun verifyEmail(bodyText: String): AuthNetworkResponse = AuthNetworkResponse(501, null)

    override suspend fun refresh(bodyText: String): AuthNetworkResponse = AuthNetworkResponse(501, null)

    override suspend fun logout(bodyText: String): AuthNetworkResponse = AuthNetworkResponse(200, """{"ok":true}""")

    override suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse =
        AuthNetworkResponse(501, null)
}
