package build.raft.app.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val secondFactorPasskeyJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Answering a step-up with a passkey (botiverse/slock staging 2e16c9b353690120d5e981edffe3abec0c734946,
 * which carries slock #15468, the allow-listed `android:apk-key-hash:` origins).
 *
 * 1. `POST /api/auth/second-factor/passkeys/assertion/options` with `{}` on the signed-in session
 *    (routes/secondFactor.ts `managementRoute(startStepUpPasskeyAssertion)` →
 *    services/secondFactor/passkeyManagement.ts `startStepUpPasskeyAssertion` → passkeys.ts
 *    `startPasskeyAssertion` → webauthn.ts `passkeyAssertionOptions`, @simplewebauthn/server 14
 *    `generateAuthenticationOptions`) answers `{ options }`, a `PublicKeyCredentialRequestOptionsJSON`:
 *    `{ rpId, challenge, allowCredentials: [{ id, transports?, type: "public-key" }], timeout: 300000,
 *    userVerification: "required" }`. The challenge is bound to this sign-in (its session family) and
 *    used once. Refusals: 404 `passkey_not_found` (no passkey), 503 `passkeys_unavailable` (no relying
 *    party), 403 `session_family_required` (router `requireWebSessionFamily`).
 * 2. The platform asserts those options as they are (the app never picks an RP ID; it takes `rpId`
 *    and `allowCredentials` only from the server's options).
 * 3. `POST /api/auth/second-factor/step-up` `{ webauthn }` (SecondFactorStepUpApi.kt) with the
 *    browser-shaped `PublicKeyCredential` JSON, shape-checked by webauthn.ts
 *    `parsePasskeyAssertionResponse`: `type: "public-key"`, `id == rawId`, base64url
 *    `response.clientDataJSON / authenticatorData / signature`, optional `userHandle`. A refused
 *    assertion is 400 `second_factor_code_invalid` and counts toward the second-factor lock.
 */
internal const val SECOND_FACTOR_PASSKEY_ASSERTION_OPTIONS_PATH = "auth/second-factor/passkeys/assertion/options"

/**
 * The options request. [rp] (`legacy` | `current`) chooses the server's credential group per RP ID
 * (#proj-mobile:#189): PENDING CONTRACT VERIFICATION. It is not in slock staging yet, and it may end
 * up a query parameter instead of a body field. Android sends none (both groups work natively),
 * iOS sends `current` ([raftNativePasskeyRpGroup]). Absent, the body is `{}`: today's contract.
 */
@Serializable
data class SecondFactorPasskeyAssertionOptionsRequestBody(
    val rp: String? = null,
)

/** The `{ options }` envelope; [options] is kept whole for the platform. */
@Serializable
internal data class SecondFactorPasskeyAssertionOptionsEnvelopeDto(
    val options: JsonObject? = null,
)

/** The fields of `PublicKeyCredentialRequestOptionsJSON` the app checks before handing it on. */
@Serializable
data class SecondFactorPasskeyRequestOptionsDto(
    val challenge: String = "",
    val rpId: String? = null,
    val allowCredentials: List<SecondFactorPasskeyCredentialDescriptorDto> = emptyList(),
    val timeout: Long? = null,
    val userVerification: String? = null,
)

@Serializable
data class SecondFactorPasskeyCredentialDescriptorDto(
    val id: String,
    val type: String = "public-key",
    val transports: List<String>? = null,
)

/** The options call's answer. */
sealed interface SecondFactorPasskeyOptionsResult {
    /**
     * [requestJson]: the server's `options` object, verbatim (what Android's
     * `GetPublicKeyCredentialOption` takes); [options]: its checked fields.
     */
    data class Ready(val requestJson: String, val options: SecondFactorPasskeyRequestOptionsDto) : SecondFactorPasskeyOptionsResult
    /** Refused, or no response: mapped like a step-up refusal. */
    data class Refused(val result: SecondFactorStepUpResult) : SecondFactorPasskeyOptionsResult
}

fun buildSharedSecondFactorPasskeyAssertionOptionsBody(rp: String? = null): String =
    secondFactorPasskeyJson.encodeToString(SecondFactorPasskeyAssertionOptionsRequestBody.serializer(), SecondFactorPasskeyAssertionOptionsRequestBody(rp))

/** The options call's answer from its status, body and `X-Trace-Id`. */
fun parseSharedSecondFactorPasskeyAssertionOptions(statusCode: Int, bodyText: String?, traceId: String? = null): SecondFactorPasskeyOptionsResult {
    if (statusCode !in 200..299) return SecondFactorPasskeyOptionsResult.Refused(parseSharedSecondFactorRefusalResult(statusCode, bodyText, traceId))
    val mismatch = SecondFactorPasskeyOptionsResult.Refused(
        SecondFactorStepUpResult.Failed(serverCode = "passkey_options_contract_mismatch", traceId = traceId)
    )
    val options =
        bodyText?.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { secondFactorPasskeyJson.decodeFromString(SecondFactorPasskeyAssertionOptionsEnvelopeDto.serializer(), body) }.getOrNull()
        }?.options ?: return mismatch
    val checked =
        runCatching { secondFactorPasskeyJson.decodeFromJsonElement(SecondFactorPasskeyRequestOptionsDto.serializer(), options) }.getOrNull()
            ?.takeIf { it.challenge.isNotBlank() }
            ?: return mismatch
    return SecondFactorPasskeyOptionsResult.Ready(requestJson = options.toString(), options = checked)
}

/** `AuthenticatorAssertionResponseJSON`. */
@Serializable
data class SecondFactorPasskeyAssertionResponseDto(
    val clientDataJSON: String,
    val authenticatorData: String,
    val signature: String,
    val userHandle: String? = null,
)

/**
 * `AuthenticationResponseJSON` (what the web's `PublicKeyCredential.toJSON()` sends, and Android's
 * `PublicKeyCredential.authenticationResponseJson` already is). [type] and [clientExtensionResults]
 * are always written (the server requires `type: "public-key"`).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SecondFactorPasskeyAssertionDto(
    val id: String,
    val rawId: String,
    @EncodeDefault val type: String = "public-key",
    val response: SecondFactorPasskeyAssertionResponseDto,
    val authenticatorAttachment: String? = null,
    @EncodeDefault val clientExtensionResults: JsonObject = JsonObject(emptyMap()),
)

private val base64UrlField = Regex("^[A-Za-z0-9_-]+={0,2}$")

private fun String?.isBase64UrlField(): Boolean = this != null && isNotEmpty() && base64UrlField.matches(this)

/**
 * The platform's assertion JSON as the step-up body's `webauthn`, checked as the server's
 * `parsePasskeyAssertionResponse` does; null when it would be refused for its shape.
 */
fun parseSharedPasskeyAssertionResponse(json: String): SecondFactorPasskeyAssertionDto? {
    val dto = runCatching { secondFactorPasskeyJson.decodeFromString(SecondFactorPasskeyAssertionDto.serializer(), json) }.getOrNull() ?: return null
    val response = dto.response
    val shaped =
        dto.type == "public-key" &&
            dto.id.isBase64UrlField() &&
            dto.rawId == dto.id &&
            response.clientDataJSON.isBase64UrlField() &&
            response.authenticatorData.isBase64UrlField() &&
            response.signature.isBase64UrlField() &&
            (response.userHandle == null || response.userHandle.isBase64UrlField())
    return dto.takeIf { shaped }
}

/** Encodes an assertion the iOS actual built from its parts (the same JSON the web sends). */
internal fun encodeSharedPasskeyAssertion(assertion: SecondFactorPasskeyAssertionDto): String =
    secondFactorPasskeyJson.encodeToString(SecondFactorPasskeyAssertionDto.serializer(), assertion)
