package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

private val secondFactorSignInJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/*
 * The second step of a sign-in (slock v1.21.44, #12247 server / #12259 web; behind
 * `auth_second_factor_v1`, only for people who turned two-step verification on).
 *
 * Server (packages/server/src/services/secondFactor/signIn.ts, routes/auth.ts):
 *   - `POST /api/auth/login` (auth.ts:1446) and `POST /api/auth/:provider/complete`
 *     (auth.ts:1332) answer 200 with no tokens for such a person:
 *
 *         { secondFactorRequired: true, challenge, expiresAt, methods, maskedEmail }
 *
 *     (signIn.ts `SecondFactorRequiredBody` / `startSecondFactorSignIn`; keys pinned by
 *     routes/secondFactorSignIn.api.test.ts `assertChallenge`). `challenge` is a 43-character
 *     base64url token, `expiresAt` an ISO time five minutes ahead (single use), `methods`
 *     `("totp" | "passkey" | "backup_code" | "recovery")[]` (only what is set up; `recovery`
 *     once an email reset is past its wait), `maskedEmail` `a***@example.com`.
 *   - `POST /api/auth/second-factor/verify` (app.ts:682, auth.ts:1522
 *     `secondFactorVerifyHandler`) `{ challenge, code | backupCode | webauthn | recovery: true,
 *     codeVerifier? }` answers with the same body the first step would have given (plus
 *     `backupCodesRemaining` after a backup code), or `{ error, code, ...extra }`
 *     (signIn.ts `STATUS_OF`): 400 `second_factor_code_invalid` / `backup_code_used` /
 *     `invalid_request` / `pkce_mismatch`, 410 `challenge_expired`, 429
 *     `challenge_attempts_exhausted` (five answers per challenge) / `second_factor_locked`
 *     (`lockedUntil`, `methods`), 503 `second_factor_unavailable`.
 *
 * The app answers with an authenticator code or a backup code ([SecondFactorStepUpMethod], as
 * the step-up dialog); passkeys and finishing a recovery stay on the web for now.
 */
private const val SECOND_FACTOR_SIGN_IN_CHALLENGE_PATTERN = "^[A-Za-z0-9_-]{43}$"
private val secondFactorSignInChallengeToken = Regex(SECOND_FACTOR_SIGN_IN_CHALLENGE_PATTERN)

@Serializable
internal data class SecondFactorSignInChallengeDto(
    val secondFactorRequired: Boolean = false,
    val challenge: String? = null,
    val expiresAt: String? = null,
    val methods: List<String>? = null,
    val maskedEmail: String? = null,
)

/** A first step's answer that asks for the second step. */
data class SecondFactorSignInChallenge(
    val challenge: String,
    val expiresAtEpochMillis: Long,
    /** As the server listed them (`recovery` and `passkey` included). */
    val methods: List<String>,
    val maskedEmail: String,
) {
    /** What the app can answer with, authenticator code first; empty when only a passkey is listed. */
    val usableMethods: List<SecondFactorStepUpMethod>
        get() = SecondFactorStepUpMethod.entries.filter { it.wireName in methods }
}

/**
 * The challenge of a first step's 2xx body; null for any other body (a normal token answer
 * signs in exactly as before). Mirrors web `parseSecondFactorChallenge`.
 */
fun parseSharedSecondFactorSignInChallenge(bodyText: String?): SecondFactorSignInChallenge? {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val dto =
        runCatching { secondFactorSignInJson.decodeFromString(SecondFactorSignInChallengeDto.serializer(), body) }
            .getOrNull() ?: return null
    if (!dto.secondFactorRequired) return null
    val challenge = dto.challenge?.takeIf { secondFactorSignInChallengeToken.matches(it) } ?: return null
    val expiresAt = dto.expiresAt?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() } ?: return null
    val maskedEmail = dto.maskedEmail ?: return null
    return SecondFactorSignInChallenge(
        challenge = challenge,
        expiresAtEpochMillis = expiresAt,
        methods = dto.methods.orEmpty(),
        maskedEmail = maskedEmail,
    )
}

/** Which first step a challenge came from: its verify answer has that step's success body. */
sealed interface SecondFactorSignInFlow {
    /** `POST /api/auth/login`: `{ user, accessToken, refreshToken, linkedProvider? }`. */
    data object Password : SecondFactorSignInFlow

    /** `POST /api/auth/:provider/complete`: `{ provider, mode, returnTo, accessToken, refreshToken, linkedProvider? }`. */
    data class Social(val provider: String) : SecondFactorSignInFlow
}

/** A second step this app is waiting on: the challenge and the first step it came from. */
data class SecondFactorSignInPending(
    val challenge: SecondFactorSignInChallenge,
    val flow: SecondFactorSignInFlow,
    /** A mobile PKCE handoff's verifier (`mobile_social_login`); none of the app's current first steps use one. */
    val codeVerifier: String? = null,
)

@Serializable
data class SecondFactorSignInVerifyRequestBody(
    val challenge: String,
    val code: String? = null,
    val backupCode: String? = null,
    val codeVerifier: String? = null,
)

fun buildSharedSecondFactorSignInVerifyBody(
    challenge: String,
    answer: SecondFactorStepUpAnswer,
    codeVerifier: String? = null,
): String =
    secondFactorSignInJson.encodeToString(
        SecondFactorSignInVerifyRequestBody.serializer(),
        when (answer) {
            is SecondFactorStepUpAnswer.AuthenticatorCode ->
                SecondFactorSignInVerifyRequestBody(challenge = challenge, code = answer.code, codeVerifier = codeVerifier)
            is SecondFactorStepUpAnswer.BackupCode ->
                SecondFactorSignInVerifyRequestBody(challenge = challenge, backupCode = answer.code, codeVerifier = codeVerifier)
        }
    )

/** How `POST /api/auth/second-factor/verify` went. */
sealed interface SecondFactorSignInResult {
    /** Signed in, exactly as the first step would have without the second one. */
    data class SignedIn(val session: StoredAuthSession, val backupCodesRemaining: Int? = null) : SecondFactorSignInResult
    data object CodeInvalid : SecondFactorSignInResult
    data object BackupCodeUsed : SecondFactorSignInResult
    /** 410 `challenge_expired` (also a password changed since the first step): sign in again. */
    data object Expired : SecondFactorSignInResult
    /** 429 `challenge_attempts_exhausted`: five answers used; sign in again. */
    data object AttemptsExhausted : SecondFactorSignInResult
    /** 429 `second_factor_locked`; [lockedUntilEpochMillis] null when the time could not be read. */
    data class Locked(val lockedUntilEpochMillis: Long?) : SecondFactorSignInResult
    /** 503 `second_factor_unavailable`. */
    data object Unavailable : SecondFactorSignInResult
    /** Anything else (network, 5xx, an unknown code, a session that could not be saved). */
    data class Failed(val serverMessage: String? = null, val serverCode: String? = null) : SecondFactorSignInResult
}

/** A refused `POST /api/auth/second-factor/verify`, from its status and error body. */
fun parseSharedSecondFactorSignInRefusal(statusCode: Int, bodyText: String?): SecondFactorSignInResult {
    val refusal =
        bodyText?.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { secondFactorSignInJson.decodeFromString(SecondFactorRefusalDto.serializer(), body) }.getOrNull()
        }
    return when (refusal?.code) {
        "second_factor_code_invalid" -> SecondFactorSignInResult.CodeInvalid
        "backup_code_used" -> SecondFactorSignInResult.BackupCodeUsed
        "challenge_expired" -> SecondFactorSignInResult.Expired
        "challenge_attempts_exhausted" -> SecondFactorSignInResult.AttemptsExhausted
        "second_factor_locked" ->
            SecondFactorSignInResult.Locked(
                refusal.lockedUntil?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() }
            )
        "second_factor_unavailable" -> SecondFactorSignInResult.Unavailable
        else ->
            SecondFactorSignInResult.Failed(
                serverMessage = refusal?.error?.takeIf { it.isNotBlank() },
                serverCode = refusal?.code ?: "http_$statusCode",
            )
    }
}

/**
 * The second step a sign-in is waiting on, in memory only (web `useSecondFactorSignIn`): a
 * process restart starts the sign-in again, as an expired challenge would. A first step that
 * answered a challenge puts it here; the sign-in page shows the second step while it is set.
 */
object RaftSecondFactorSignIn {
    private val _pending = MutableStateFlow<SecondFactorSignInPending?>(null)
    val pending: StateFlow<SecondFactorSignInPending?> = _pending.asStateFlow()

    fun start(pending: SecondFactorSignInPending) {
        _pending.value = pending
    }

    /** Drops [expected] (or whatever is pending when null); a newer challenge stays. */
    fun clear(expected: SecondFactorSignInPending? = null) {
        if (expected == null || _pending.value == expected) _pending.value = null
    }
}
