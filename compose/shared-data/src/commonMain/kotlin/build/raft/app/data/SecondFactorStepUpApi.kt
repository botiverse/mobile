package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

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
 * `{ backupCode }` (or `{ webauthn }`, a passkey: SecondFactorPasskeyStepUpApi.kt) answers
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

/**
 * A way to answer a step-up in the app. [Passkey] only where the platform can assert one natively
 * (Android Credential Manager, [raftNativePasskeyAssertionAvailable]) and the host wired the
 * assertion options ([SecondFactorStepUpCoordinator]); it comes first then, as on the web
 * (`secondFactorSignIn.ts` `METHOD_ORDER`).
 */
enum class SecondFactorStepUpMethod(val wireName: String) {
    Passkey("passkey"),
    Totp("totp"),
    BackupCode("backup_code"),
}

/** The methods answered with a typed code, in the dialog's order. */
@RaftCoreInternalApi val SECOND_FACTOR_STEP_UP_CODE_METHODS = listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode)

/** A 403 `second_factor_step_up_required`: the [methods] the server listed, as sent. */
data class SecondFactorStepUpChallenge(
    val methods: List<String>,
    val serverMessage: String? = null,
) {
    /** The typed-code methods listed, authenticator code first; empty when only a passkey is listed. */
    val usableMethods: List<SecondFactorStepUpMethod>
        get() = SECOND_FACTOR_STEP_UP_CODE_METHODS.filter { it.wireName in methods }

    /** The server listed a passkey (the person has one and the server has a WebAuthn relying party). */
    val passkeyListed: Boolean
        get() = SecondFactorStepUpMethod.Passkey.wireName in methods
}

sealed interface SecondFactorStepUpAnswer {
    data class AuthenticatorCode(val code: String) : SecondFactorStepUpAnswer
    data class BackupCode(val code: String) : SecondFactorStepUpAnswer
    /** The platform's assertion for the server's options ([parseSharedPasskeyAssertionResponse]). */
    data class Passkey(val assertion: SecondFactorPasskeyAssertionDto) : SecondFactorStepUpAnswer
}

/**
 * A step-up answer's result. `traceId`: the refusing response's `X-Trace-Id` (AGENTS.md "Errors carry
 * the trace ID"), null when there was no response or it carried none.
 */
sealed interface SecondFactorStepUpResult {
    data class Verified(val backupCodesRemaining: Int? = null) : SecondFactorStepUpResult
    /** 400 `second_factor_code_invalid`: a wrong code, or a passkey assertion the server refused. */
    data class CodeInvalid(val traceId: String? = null) : SecondFactorStepUpResult
    data object BackupCodeUsed : SecondFactorStepUpResult
    /** [lockedUntilEpochMillis]: null when the time could not be read. */
    data class Locked(val lockedUntilEpochMillis: Long?, val traceId: String? = null) : SecondFactorStepUpResult
    data object NotEnabled : SecondFactorStepUpResult
    data object SessionFamilyRequired : SecondFactorStepUpResult
    /** 404 `passkey_not_found`: no passkey left (options), or the one answered with was removed. */
    data class PasskeyNotFound(val traceId: String? = null) : SecondFactorStepUpResult
    /**
     * 404 `passkey_rp_unavailable` (options only): none of the person's passkeys is in the RP group
     * [rp] asked for, but some are in [availableRps] (e.g. iOS asks for `current` and the person has
     * only passkeys under the legacy RP ID).
     */
    data class PasskeyRpUnavailable(
        val rp: String? = null,
        val availableRps: List<String> = emptyList(),
        val traceId: String? = null,
    ) : SecondFactorStepUpResult
    /** 503 `passkeys_unavailable`: the server has no WebAuthn relying party right now. */
    data class PasskeysUnavailable(val traceId: String? = null) : SecondFactorStepUpResult
    /** Anything else (network, 5xx, an unknown code): the server's sentence when it sent one. */
    data class Failed(
        val serverMessage: String? = null,
        val serverCode: String? = null,
        val traceId: String? = null,
    ) : SecondFactorStepUpResult
}

@Serializable
@RaftCoreInternalApi data class SecondFactorRefusalDto(
    val error: String? = null,
    val code: String? = null,
    val methods: List<String>? = null,
    val lockedUntil: String? = null,
    /** `passkey_rp_unavailable`: the RP group asked for. */
    val rp: String? = null,
    /** `passkey_rp_unavailable`: the groups the person does have a passkey in. */
    val availableRps: List<String>? = null,
)

@Serializable
data class SecondFactorStepUpRequestBody(
    val code: String? = null,
    val backupCode: String? = null,
    val webauthn: SecondFactorPasskeyAssertionDto? = null,
)

@Serializable
@RaftCoreInternalApi data class SecondFactorStepUpResponseDto(
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
@RaftCoreInternalApi fun HomeNetworkResponse.secondFactorStepUpChallengeOrNull(): SecondFactorStepUpChallenge? =
    parseSharedSecondFactorStepUpRequired(code, bodyText?.takeIf { it.isNotBlank() } ?: errorBodyText)

fun buildSharedSecondFactorStepUpBody(answer: SecondFactorStepUpAnswer): String =
    secondFactorStepUpJson.encodeToString(
        when (answer) {
            is SecondFactorStepUpAnswer.AuthenticatorCode -> SecondFactorStepUpRequestBody(code = answer.code)
            is SecondFactorStepUpAnswer.BackupCode -> SecondFactorStepUpRequestBody(backupCode = answer.code)
            is SecondFactorStepUpAnswer.Passkey -> SecondFactorStepUpRequestBody(webauthn = answer.assertion)
        }
    )

/** `POST /api/auth/second-factor/step-up`'s answer, from its status, body and [traceId] (`X-Trace-Id`). */
fun parseSharedSecondFactorStepUpResult(statusCode: Int, bodyText: String?, traceId: String? = null): SecondFactorStepUpResult {
    if (statusCode in 200..299) {
        val response =
            bodyText?.takeIf { it.isNotBlank() }?.let { body ->
                runCatching { secondFactorStepUpJson.decodeFromString(SecondFactorStepUpResponseDto.serializer(), body) }.getOrNull()
            }
        return if (response?.verified == true) {
            SecondFactorStepUpResult.Verified(response.backupCodesRemaining)
        } else {
            SecondFactorStepUpResult.Failed(serverCode = "second_factor_step_up_contract_mismatch", traceId = traceId)
        }
    }
    return parseSharedSecondFactorRefusalResult(statusCode, bodyText, traceId)
}

/** A refused second-factor call (step-up or passkey assertion options) as a [SecondFactorStepUpResult]. */
@RaftCoreInternalApi fun parseSharedSecondFactorRefusalResult(statusCode: Int, bodyText: String?, traceId: String?): SecondFactorStepUpResult {
    val refusal = parseSecondFactorRefusal(bodyText)
    val message = refusal?.error?.takeIf { it.isNotBlank() }
    return when (refusal?.code) {
        "second_factor_code_invalid" -> SecondFactorStepUpResult.CodeInvalid(traceId)
        "backup_code_used" -> SecondFactorStepUpResult.BackupCodeUsed
        "second_factor_locked" ->
            SecondFactorStepUpResult.Locked(
                refusal.lockedUntil?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() },
                traceId,
            )
        "second_factor_not_enabled" -> SecondFactorStepUpResult.NotEnabled
        "session_family_required" -> SecondFactorStepUpResult.SessionFamilyRequired
        "passkey_not_found" -> SecondFactorStepUpResult.PasskeyNotFound(traceId)
        "passkey_rp_unavailable" ->
            SecondFactorStepUpResult.PasskeyRpUnavailable(
                rp = refusal.rp,
                availableRps = refusal.availableRps.orEmpty().filter { it in SECOND_FACTOR_PASSKEY_RP_GROUPS },
                traceId = traceId,
            )
        "passkeys_unavailable" -> SecondFactorStepUpResult.PasskeysUnavailable(traceId)
        else -> SecondFactorStepUpResult.Failed(serverMessage = message, serverCode = refusal?.code ?: "http_$statusCode", traceId = traceId)
    }
}

/**
 * A refused request's [DataResult] when it is a 403 `second_factor_step_up_required`, else null:
 * the one error shape [SecondFactorStepUpCoordinator.run] recognises, so a repository maps the
 * refusal before its own 403 handling (which would otherwise show the server's English sentence).
 */
@RaftCoreInternalApi fun <T> HomeNetworkResponse.secondFactorStepUpErrorOrNull(serverMessage: String? = null): DataResult<T>? {
    val challenge = secondFactorStepUpChallengeOrNull() ?: return null
    return DataResult.Error(
        ScreenError(
            code = SECOND_FACTOR_STEP_UP_REQUIRED_CODE,
            retryable = false,
            serverMessage = challenge.serverMessage ?: serverMessage,
            serverCode = SECOND_FACTOR_STEP_UP_REQUIRED_CODE,
            secondFactorStepUp = challenge
        )
    )
}

/** The step-up challenge an action's refusal carries, if it is one. */
fun DataResult<*>.secondFactorStepUpChallengeOrNull(): SecondFactorStepUpChallenge? =
    (this as? DataResult.Error)?.error?.secondFactorStepUp

/** `POST /api/auth/second-factor/step-up` through the shared network client (user-blocking: Foreground). */
class SecondFactorStepUpRepository(private val networkClient: RaftNetworkClient) {
    private val web = AppWebStepUpRepository(networkClient)

    /** The web step-up's start (AppWebStepUpApi.kt). */
    @RaftCoreInternalApi suspend fun webStepUpStart(body: AppWebStepUpStartBody): AppWebStepUpStartResult = web.start(body)

    /** The web step-up's code exchange (AppWebStepUpApi.kt). */
    @RaftCoreInternalApi suspend fun webStepUpComplete(body: AppWebStepUpCompleteBody): AppWebStepUpCompleteResult = web.complete(body)

    /** Continue in browser for this repository's sign-in, where the platform has it ([secondFactorWebStepUp]). */
    @RaftCoreInternalApi fun webStepUp(): SecondFactorWebStepUp? = secondFactorWebStepUp(::webStepUpStart, ::webStepUpComplete)

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
                response.traceId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            SecondFactorStepUpResult.Failed(serverCode = "network")
        }

    /**
     * `POST /api/auth/second-factor/passkeys/assertion/options` (SecondFactorPasskeyStepUpApi.kt):
     * `{}` (the server's default RP group), or `{ rp }` with [rp] (`current` | `legacy`).
     */
    suspend fun passkeyAssertionOptions(rp: String? = null): SecondFactorPasskeyOptionsResult =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = SECOND_FACTOR_PASSKEY_ASSERTION_OPTIONS_PATH,
                    method = "POST",
                    bodyText = buildSharedSecondFactorPasskeyAssertionOptionsBody(rp),
                )
            parseSharedSecondFactorPasskeyAssertionOptions(
                response.code,
                response.bodyText?.takeIf { it.isNotBlank() } ?: response.errorBodyText,
                response.traceId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            SecondFactorPasskeyOptionsResult.Refused(SecondFactorStepUpResult.Failed(serverCode = "network"))
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
                ?: SECOND_FACTOR_STEP_UP_CODE_METHODS.map { it.wireName },
        serverMessage = message?.takeIf { it.isNotBlank() },
    )
