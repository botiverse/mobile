package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Step-up with a legacy passkey in a system web session, for an account whose only passkeys are
 * under the legacy RP ID, which iOS can't assert natively (`passkey_rp_unavailable`). Contract:
 * botiverse/slock `docs/contracts/app-web-step-up.md` (#15593 @ 7daeae2d), server
 * `services/appStepUpService.ts` + `routes/appLogin.ts`. Flag `app_web_step_up_v1` (per person).
 *
 *     POST /api/auth/app-login/step-up/start      (the app's bearer)
 *       { returnUri, codeChallenge, codeChallengeMethod: "S256", clientId?, action }
 *       → 201 { requestId, stepUpUrl: ".../login/app/verify?request=<id>", expiresAt, client: { id, name } }
 *       400 invalid_request | return_uri_not_allowed | client_mismatch | code_challenge_invalid | invalid_action
 *       403 session_family_required · 404 not_enabled | passkey_not_found
 *       409 second_factor_not_enabled · 503 app_login_url_unavailable
 *     POST /api/auth/app-login/step-up/complete   (the app's bearer, same sign-in as start)
 *       { code, codeVerifier, returnUri } → 200 { verified: true, verifiedAt }
 *       400 invalid_request | pkce_mismatch | return_uri_mismatch (code not used up)
 *       403 session_family_required · 404 code_not_found (or not_enabled) · 410 code_expired | code_consumed
 *
 * Refusals are the server's envelope (`lib/refusal.ts`): `{ error, code, errorCode }`, `errorCode`
 * repeating `code`; the 503 is `{ error, code }`. The page hands back to
 * `<returnUri>?requestId=…&code=…` (or `error=access_denied` / `error=expired`), the same callback
 * the browser login uses ([parseAppLoginCallback]). A request lives 5 min, a code 2 min.
 */
internal const val APP_WEB_STEP_UP_START_PATH = "auth/app-login/step-up/start"
internal const val APP_WEB_STEP_UP_COMPLETE_PATH = "auth/app-login/step-up/complete"

private val appWebStepUpJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Start's body. [action]: the refused action's `StepUpAction` key (slock `stepUpGate.ts`
 * `STEP_UP_ACTIONS`), shown on the page; required (400 `invalid_action` otherwise), so the dialog
 * offers Continue in browser only for an action it can name.
 */
@Serializable
data class AppWebStepUpStartBody(
    val returnUri: String,
    val codeChallenge: String,
    val codeChallengeMethod: String = "S256",
    val clientId: String? = null,
    val action: String,
)

/** The app client the server matched the return URI to. */
@Serializable
internal data class AppWebStepUpClient(val id: String = "", val name: String = "")

/** Start's 201. */
@Serializable
internal data class AppWebStepUpStartResponse(
    val requestId: String = "",
    val stepUpUrl: String = "",
    val expiresAt: String = "",
    val client: AppWebStepUpClient? = null,
)

/** Complete's body: [returnUri] is start's. */
@Serializable
data class AppWebStepUpCompleteBody(
    val code: String,
    val codeVerifier: String,
    val returnUri: String,
)

@Serializable
private data class AppWebStepUpCompleteResponse(val verified: Boolean = false, val verifiedAt: String? = null)

@Serializable
private data class AppWebStepUpRefusalDto(
    val error: String? = null,
    val code: String? = null,
    val errorCode: String? = null,
    val redirectUrl: String? = null,
)

sealed interface AppWebStepUpStartResult {
    data class Started(val requestId: String, val stepUpUrl: String, val expiresAt: String) : AppWebStepUpStartResult
    /** 404 `not_enabled` (flag off) or `passkey_not_found` (no legacy passkey): Open on the web instead. */
    data object Unavailable : AppWebStepUpStartResult
    /** 409 `second_factor_not_enabled`: two-step verification went off meanwhile; the action just goes again. */
    data object NotEnabled : AppWebStepUpStartResult
    /** 403 `session_family_required`: this sign-in can't be verified; sign in again. */
    data object SessionFamilyRequired : AppWebStepUpStartResult
    data class Failed(val serverMessage: String? = null, val serverCode: String? = null, val traceId: String? = null) : AppWebStepUpStartResult
}

sealed interface AppWebStepUpCompleteResult {
    data object Verified : AppWebStepUpCompleteResult
    /** 410 `code_expired` (also denied by a newer start or a revoke) or `code_consumed`. */
    data class Expired(val traceId: String? = null) : AppWebStepUpCompleteResult
    /** 403 `session_family_required`. */
    data object SessionFamilyRequired : AppWebStepUpCompleteResult
    data class Failed(val serverMessage: String? = null, val serverCode: String? = null, val traceId: String? = null) : AppWebStepUpCompleteResult
}

private fun parseAppWebStepUpRefusal(bodyText: String?): AppWebStepUpRefusalDto? =
    bodyText?.takeIf { it.isNotBlank() }?.let { body ->
        runCatching { appWebStepUpJson.decodeFromString(AppWebStepUpRefusalDto.serializer(), body) }.getOrNull()
    }

internal fun buildAppWebStepUpStartBody(body: AppWebStepUpStartBody): String =
    appWebStepUpJson.encodeToString(AppWebStepUpStartBody.serializer(), body)

internal fun buildAppWebStepUpCompleteBody(body: AppWebStepUpCompleteBody): String =
    appWebStepUpJson.encodeToString(AppWebStepUpCompleteBody.serializer(), body)

/** Start's answer from its status, body and `X-Trace-Id`. */
internal fun parseAppWebStepUpStart(statusCode: Int, bodyText: String?, traceId: String? = null): AppWebStepUpStartResult {
    if (statusCode in 200..299) {
        val response =
            bodyText?.takeIf { it.isNotBlank() }?.let { body ->
                runCatching { appWebStepUpJson.decodeFromString(AppWebStepUpStartResponse.serializer(), body) }.getOrNull()
            }
        if (response == null || response.requestId.isBlank() || response.stepUpUrl.isBlank()) {
            return AppWebStepUpStartResult.Failed(serverCode = "app_web_step_up_contract_mismatch", traceId = traceId)
        }
        return AppWebStepUpStartResult.Started(response.requestId, response.stepUpUrl, response.expiresAt)
    }
    val refusal = parseAppWebStepUpRefusal(bodyText)
    return when {
        statusCode == 404 -> AppWebStepUpStartResult.Unavailable
        refusal?.code == "second_factor_not_enabled" -> AppWebStepUpStartResult.NotEnabled
        refusal?.code == "session_family_required" -> AppWebStepUpStartResult.SessionFamilyRequired
        else ->
            AppWebStepUpStartResult.Failed(
                serverMessage = refusal?.error?.takeIf { it.isNotBlank() },
                serverCode = refusal?.code ?: "http_$statusCode",
                traceId = traceId,
            )
    }
}

/** Complete's answer from its status, body and `X-Trace-Id`. */
internal fun parseAppWebStepUpComplete(statusCode: Int, bodyText: String?, traceId: String? = null): AppWebStepUpCompleteResult {
    if (statusCode in 200..299) {
        val verified =
            bodyText?.takeIf { it.isNotBlank() }?.let { body ->
                runCatching { appWebStepUpJson.decodeFromString(AppWebStepUpCompleteResponse.serializer(), body) }.getOrNull()
            }?.verified == true
        return if (verified) {
            AppWebStepUpCompleteResult.Verified
        } else {
            AppWebStepUpCompleteResult.Failed(serverCode = "app_web_step_up_contract_mismatch", traceId = traceId)
        }
    }
    val refusal = parseAppWebStepUpRefusal(bodyText)
    return when {
        statusCode == 410 -> AppWebStepUpCompleteResult.Expired(traceId)
        refusal?.code == "session_family_required" -> AppWebStepUpCompleteResult.SessionFamilyRequired
        else ->
            AppWebStepUpCompleteResult.Failed(
                serverMessage = refusal?.error?.takeIf { it.isNotBlank() },
                serverCode = refusal?.code ?: "http_$statusCode",
                traceId = traceId,
            )
    }
}

/**
 * The two authenticated calls of the web step-up, through the shared network client (this
 * sign-in's bearer; user-blocking: Foreground). Made for a traced attempt
 * ([AppWebStepUpRequestTrace] in the coroutine context), they carry its `traceparent` and note
 * the answer's status and refusal code for its span.
 */
internal class AppWebStepUpRepository(private val networkClient: RaftNetworkClient) {
    suspend fun start(body: AppWebStepUpStartBody): AppWebStepUpStartResult =
        try {
            val response = post(APP_WEB_STEP_UP_START_PATH, buildAppWebStepUpStartBody(body))
            parseAppWebStepUpStart(response.code, response.answerText(), response.traceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AppWebStepUpStartResult.Failed(serverCode = "network")
        }

    suspend fun complete(body: AppWebStepUpCompleteBody): AppWebStepUpCompleteResult =
        try {
            val response = post(APP_WEB_STEP_UP_COMPLETE_PATH, buildAppWebStepUpCompleteBody(body))
            parseAppWebStepUpComplete(response.code, response.answerText(), response.traceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AppWebStepUpCompleteResult.Failed(serverCode = "network")
        }

    private suspend fun post(path: String, bodyText: String): HomeNetworkResponse {
        val trace = currentCoroutineContext()[AppWebStepUpRequestTrace]
        val response =
            networkClient.requestWithHeaders(
                priority = DataRequestPriority.Foreground,
                path = path,
                headers = trace?.headers().orEmpty(),
                method = "POST",
                bodyText = bodyText,
            )
        trace?.answered(response.code, parseAppWebStepUpRefusal(response.answerText())?.let { it.code ?: it.errorCode })
        return response
    }

    private fun HomeNetworkResponse.answerText(): String? = bodyText?.takeIf { it.isNotBlank() } ?: errorBodyText
}
