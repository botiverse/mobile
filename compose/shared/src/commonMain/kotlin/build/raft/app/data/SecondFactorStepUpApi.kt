package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

private val secondFactorStepUpJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Step-up for sensitive actions (slock design v8 §3, server
 * `packages/server/src/services/secondFactor/stepUpGate.ts`).
 *
 * With two-step verification on for the person, changing the password (`PATCH /api/auth/me`),
 * connecting a sign-in method (`POST /api/auth/:provider/link/start`,
 * `POST /api/auth/mobile/oauth/:provider/link/start`) and disconnecting one
 * (`DELETE /api/auth/identities/:provider`) need this sign-in to have passed the second factor
 * in the last 10 minutes. Otherwise the route answers (routes/secondFactor.ts
 * `refuseWithoutRecentSecondFactor`, pinned by routes/secondFactorStepUp.api.test.ts):
 *
 *     403 { error, code: "second_factor_step_up_required", methods: ("totp" | "passkey" | "backup_code")[] }
 *
 * and nothing is applied. `POST /api/auth/second-factor/step-up` with `{ code }` or
 * `{ backupCode }` (or `{ webauthn }`, not used by the app) answers
 * `{ verified: true, backupCodesRemaining? }` (the count only after a backup code); then the
 * refused request goes again. Its refusals are `{ error, code, ...extra }`
 * (services/secondFactor/management.ts `SecondFactorError`):
 *   - 400 `second_factor_code_invalid`, 400 `backup_code_used`,
 *   - 429 `second_factor_locked` with `lockedUntil` (ISO time),
 *   - 409 `second_factor_not_enabled` (turned off meanwhile: the request just goes again),
 *   - 403 `session_family_required` (this sign-in can't be verified: sign in again).
 * The whole `/api/auth/second-factor` router answers 404 while the `auth_second_factor_v1`
 * flag is off for the person.
 */
const val SECOND_FACTOR_STEP_UP_REQUIRED_CODE = "second_factor_step_up_required"

private const val SECOND_FACTOR_STEP_UP_PATH = "auth/second-factor/step-up"

/** A way to answer a step-up in the app. Passkeys are not offered (v1 app scope). */
enum class SecondFactorStepUpMethod(val wireName: String) {
    Totp("totp"),
    BackupCode("backup_code"),
}

/** A 403 `second_factor_step_up_required`: the [methods] the server listed, as sent. */
data class SecondFactorStepUpChallenge(
    val methods: List<String>,
    val serverMessage: String? = null,
) {
    /** What the app can answer with, authenticator code first; empty when only a passkey is listed. */
    val usableMethods: List<SecondFactorStepUpMethod>
        get() = SecondFactorStepUpMethod.entries.filter { it.wireName in methods }
}

sealed interface SecondFactorStepUpAnswer {
    data class AuthenticatorCode(val code: String) : SecondFactorStepUpAnswer
    data class BackupCode(val code: String) : SecondFactorStepUpAnswer
}

sealed interface SecondFactorStepUpResult {
    data class Verified(val backupCodesRemaining: Int? = null) : SecondFactorStepUpResult
    data object CodeInvalid : SecondFactorStepUpResult
    data object BackupCodeUsed : SecondFactorStepUpResult
    /** [lockedUntilEpochMillis]: null when the time could not be read. */
    data class Locked(val lockedUntilEpochMillis: Long?) : SecondFactorStepUpResult
    data object NotEnabled : SecondFactorStepUpResult
    data object SessionFamilyRequired : SecondFactorStepUpResult
    /** Anything else (network, 5xx, an unknown code): the server's sentence when it sent one. */
    data class Failed(val serverMessage: String? = null, val serverCode: String? = null) : SecondFactorStepUpResult
}

@Serializable
internal data class SecondFactorRefusalDto(
    val error: String? = null,
    val code: String? = null,
    val methods: List<String>? = null,
    val lockedUntil: String? = null,
)

@Serializable
data class SecondFactorStepUpRequestBody(
    val code: String? = null,
    val backupCode: String? = null,
)

@Serializable
internal data class SecondFactorStepUpResponseDto(
    val verified: Boolean = false,
    val backupCodesRemaining: Int? = null,
)

private fun parseSecondFactorRefusal(bodyText: String?): SecondFactorRefusalDto? {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { secondFactorStepUpJson.decodeFromString(SecondFactorRefusalDto.serializer(), body) }.getOrNull()
}

/** The challenge of a 403 `second_factor_step_up_required`; null for any other response. */
fun parseSharedSecondFactorStepUpRequired(statusCode: Int, bodyText: String?): SecondFactorStepUpChallenge? {
    if (statusCode != 403) return null
    val refusal = parseSecondFactorRefusal(bodyText) ?: return null
    if (refusal.code != SECOND_FACTOR_STEP_UP_REQUIRED_CODE) return null
    return SecondFactorStepUpChallenge(
        methods = refusal.methods.orEmpty(),
        serverMessage = refusal.error?.takeIf { it.isNotBlank() },
    )
}

/** [parseSharedSecondFactorStepUpRequired] for a [HomeNetworkResponse] (body or error body). */
internal fun HomeNetworkResponse.secondFactorStepUpChallengeOrNull(): SecondFactorStepUpChallenge? =
    parseSharedSecondFactorStepUpRequired(code, bodyText?.takeIf { it.isNotBlank() } ?: errorBodyText)

fun buildSharedSecondFactorStepUpBody(answer: SecondFactorStepUpAnswer): String =
    secondFactorStepUpJson.encodeToString(
        when (answer) {
            is SecondFactorStepUpAnswer.AuthenticatorCode -> SecondFactorStepUpRequestBody(code = answer.code)
            is SecondFactorStepUpAnswer.BackupCode -> SecondFactorStepUpRequestBody(backupCode = answer.code)
        }
    )

/** `POST /api/auth/second-factor/step-up`'s answer, from its status and body. */
fun parseSharedSecondFactorStepUpResult(statusCode: Int, bodyText: String?): SecondFactorStepUpResult {
    if (statusCode in 200..299) {
        val response =
            bodyText?.takeIf { it.isNotBlank() }?.let { body ->
                runCatching { secondFactorStepUpJson.decodeFromString(SecondFactorStepUpResponseDto.serializer(), body) }.getOrNull()
            }
        return if (response?.verified == true) {
            SecondFactorStepUpResult.Verified(response.backupCodesRemaining)
        } else {
            SecondFactorStepUpResult.Failed(serverCode = "second_factor_step_up_contract_mismatch")
        }
    }
    val refusal = parseSecondFactorRefusal(bodyText)
    val message = refusal?.error?.takeIf { it.isNotBlank() }
    return when (refusal?.code) {
        "second_factor_code_invalid" -> SecondFactorStepUpResult.CodeInvalid
        "backup_code_used" -> SecondFactorStepUpResult.BackupCodeUsed
        "second_factor_locked" ->
            SecondFactorStepUpResult.Locked(
                refusal.lockedUntil?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() }
            )
        "second_factor_not_enabled" -> SecondFactorStepUpResult.NotEnabled
        "session_family_required" -> SecondFactorStepUpResult.SessionFamilyRequired
        else -> SecondFactorStepUpResult.Failed(serverMessage = message, serverCode = refusal?.code ?: "http_$statusCode")
    }
}

/** The step-up challenge an action's refusal carries, if it is one. */
fun DataResult<*>.secondFactorStepUpChallengeOrNull(): SecondFactorStepUpChallenge? =
    (this as? DataResult.Error)?.error?.secondFactorStepUp

/** `POST /api/auth/second-factor/step-up` through the shared network client (user-blocking: Foreground). */
class SecondFactorStepUpRepository(private val networkClient: RaftNetworkClient) {
    suspend fun stepUp(answer: SecondFactorStepUpAnswer): SecondFactorStepUpResult =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = SECOND_FACTOR_STEP_UP_PATH,
                    method = "POST",
                    bodyText = buildSharedSecondFactorStepUpBody(answer),
                )
            parseSharedSecondFactorStepUpResult(
                response.code,
                response.bodyText?.takeIf { it.isNotBlank() } ?: response.errorBodyText,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            SecondFactorStepUpResult.Failed(serverCode = "network")
        }
}

/**
 * A host's mobile OAuth link start answered `second_factor_step_up_required` (Android
 * RaftCmpActivity / iOS RaftIosMobileOAuthHost forward the server's `methods` comma-joined).
 * A host that forwards no methods gets the authenticator code and backup code.
 */
fun parseSharedMobileOAuthLinkStepUpChallenge(methods: String?, message: String?): SecondFactorStepUpChallenge =
    SecondFactorStepUpChallenge(
        methods =
            methods?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
                ?: SecondFactorStepUpMethod.entries.map { it.wireName },
        serverMessage = message?.takeIf { it.isNotBlank() },
    )
