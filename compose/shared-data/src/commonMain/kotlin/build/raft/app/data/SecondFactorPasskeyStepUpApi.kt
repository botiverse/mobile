package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

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
 * Answering a step-up with a passkey (botiverse/slock staging e21bf9b978ac8061dfbdf9dfc3770b592c642ee6,
 * which carries slock #15468, the allow-listed `android:apk-key-hash:` origins, and the per-credential
 * RP IDs: `webauthn_credentials.rp_id`, `WEBAUTHN_RP_ID` + `WEBAUTHN_LEGACY_RP_ID`).
 *
 * 1. `POST /api/auth/second-factor/passkeys/assertion/options` with `{ rp? }` on the signed-in session
 *    (routes/secondFactor.ts → services/secondFactor/passkeyManagement.ts `startStepUpPasskeyAssertion`
 *    → passkeys.ts `startPasskeyAssertion` → webauthn.ts `passkeyAssertionOptions`) answers
 *    `{ options, rp?, otherRps? }`. `options` is a `PublicKeyCredentialRequestOptionsJSON`:
 *    `{ rpId, challenge, allowCredentials: [{ id, transports?, type: "public-key" }], timeout: 300000,
 *    userVerification: "required" }`, for the person's passkeys in ONE RP group. `rp` (`current` |
 *    `legacy`, webauthn.ts `PasskeyRpGroup`) picks it; without it the server takes `current` when the
 *    person has a passkey there, else `legacy`. While a legacy RP ID is set the answer also says `rp`
 *    (the group the options are for) and `otherRps` (the other groups the person has a passkey in,
 *    present only when non-empty). The challenge is bound to this sign-in (its session family), to
 *    the options' RP ID, and is used once. Refusals: 400 `invalid_request` (an `rp` other than the
 *    two), 404 `passkey_not_found` (no passkey at all), 404 `passkey_rp_unavailable`
 *    `{ rp, availableRps }` (none in the group asked for, some in `availableRps`), 503
 *    `passkeys_unavailable` (no relying party), 403 `session_family_required` (router
 *    `requireWebSessionFamily`).
 * 2. The platform asserts those options as they are (the app never picks an RP ID; it takes `rpId`
 *    and `allowCredentials` only from the server's options).
 * 3. `POST /api/auth/second-factor/step-up` `{ webauthn }` (SecondFactorStepUpApi.kt) with the
 *    browser-shaped `PublicKeyCredential` JSON, shape-checked by webauthn.ts
 *    `parsePasskeyAssertionResponse`: `type: "public-key"`, `id == rawId`, base64url
 *    `response.clientDataJSON / authenticatorData / signature`, optional `userHandle`. The server
 *    verifies it under the stored passkey's own RP ID, which must be the challenge's. A refused
 *    assertion is 400 `second_factor_code_invalid` and counts toward the second-factor lock. The
 *    answer's `swapSuggested` / `passkeySwap` follow only a web-origin legacy passkey; the app
 *    ignores them.
 */
@RaftCoreInternalApi const val SECOND_FACTOR_PASSKEY_ASSERTION_OPTIONS_PATH = "auth/second-factor/passkeys/assertion/options"

/** webauthn.ts `PasskeyRpGroup`: the passkeys under `WEBAUTHN_RP_ID`. */
const val SECOND_FACTOR_PASSKEY_RP_CURRENT = "current"

/** webauthn.ts `PasskeyRpGroup`: the passkeys under `WEBAUTHN_LEGACY_RP_ID`. */
const val SECOND_FACTOR_PASSKEY_RP_LEGACY = "legacy"

/**
 * The options request body. [rp] (`current` | `legacy`) asks for one RP group; null leaves the
 * server's default (`{}`). Android sends none (both groups assert natively there), iOS sends
 * `current` ([raftNativePasskeyRpGroup]).
 */
@Serializable
data class SecondFactorPasskeyAssertionOptionsRequestBody(
    val rp: String? = null,
)

/** The `{ options, rp?, otherRps? }` envelope; [options] is kept whole for the platform. */
@Serializable
@RaftCoreInternalApi data class SecondFactorPasskeyAssertionOptionsEnvelopeDto(
    val options: JsonObject? = null,
    val rp: String? = null,
    val otherRps: List<String>? = null,
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
     * `GetPublicKeyCredentialOption` takes); [options]: its checked fields. [rp]: the group the
     * options are for (null without a legacy RP ID); [otherRps]: the person's other groups with a
     * passkey (`current` / `legacy` only; empty when none or no legacy RP ID).
     */
    data class Ready(
        val requestJson: String,
        val options: SecondFactorPasskeyRequestOptionsDto,
        val rp: String? = null,
        val otherRps: List<String> = emptyList(),
    ) : SecondFactorPasskeyOptionsResult
    /** Refused, or no response: mapped like a step-up refusal. */
    data class Refused(val result: SecondFactorStepUpResult) : SecondFactorPasskeyOptionsResult
}

@RaftCoreInternalApi val SECOND_FACTOR_PASSKEY_RP_GROUPS = setOf(SECOND_FACTOR_PASSKEY_RP_CURRENT, SECOND_FACTOR_PASSKEY_RP_LEGACY)

fun buildSharedSecondFactorPasskeyAssertionOptionsBody(rp: String? = null): String =
    secondFactorPasskeyJson.encodeToString(SecondFactorPasskeyAssertionOptionsRequestBody.serializer(), SecondFactorPasskeyAssertionOptionsRequestBody(rp))

/** The options call's answer from its status, body and `X-Trace-Id`. */
fun parseSharedSecondFactorPasskeyAssertionOptions(statusCode: Int, bodyText: String?, traceId: String? = null): SecondFactorPasskeyOptionsResult {
    if (statusCode !in 200..299) return SecondFactorPasskeyOptionsResult.Refused(parseSharedSecondFactorRefusalResult(statusCode, bodyText, traceId))
    val mismatch = SecondFactorPasskeyOptionsResult.Refused(
        SecondFactorStepUpResult.Failed(serverCode = "passkey_options_contract_mismatch", traceId = traceId)
    )
    val envelope =
        bodyText?.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { secondFactorPasskeyJson.decodeFromString(SecondFactorPasskeyAssertionOptionsEnvelopeDto.serializer(), body) }.getOrNull()
        } ?: return mismatch
    val options = envelope.options ?: return mismatch
    val checked =
        runCatching { secondFactorPasskeyJson.decodeFromJsonElement(SecondFactorPasskeyRequestOptionsDto.serializer(), options) }.getOrNull()
            ?.takeIf { it.challenge.isNotBlank() }
            ?: return mismatch
    return SecondFactorPasskeyOptionsResult.Ready(
        requestJson = options.toString(),
        options = checked,
        rp = envelope.rp?.takeIf { it in SECOND_FACTOR_PASSKEY_RP_GROUPS },
        otherRps = envelope.otherRps.orEmpty().filter { it in SECOND_FACTOR_PASSKEY_RP_GROUPS }.distinct(),
    )
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
@RaftCoreInternalApi fun encodeSharedPasskeyAssertion(assertion: SecondFactorPasskeyAssertionDto): String =
    secondFactorPasskeyJson.encodeToString(SecondFactorPasskeyAssertionDto.serializer(), assertion)
