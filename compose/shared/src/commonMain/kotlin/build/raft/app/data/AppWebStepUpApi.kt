package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Step-up with a legacy passkey in a system web session (design "App step-up with a legacy passkey
 * via an in-app web session", §4): for an account whose only passkeys are under the legacy RP ID,
 * which iOS can't assert natively (`passkey_rp_unavailable`).
 *
 * PENDING CONTRACT VERIFICATION: the shapes below are the approved design's, not yet a merged
 * botiverse/slock route. Every name marked `Pending` is to be checked against the server PR (and
 * renamed if the server's differ) before the flag `app_web_step_up_v1` is turned on anywhere.
 *
 *     POST /api/auth/app-login/step-up/start      (bearer; this sign-in's session family)
 *       { returnUri, codeChallenge, codeChallengeMethod: "S256", clientId?, action? }
 *       → 201 { requestId, stepUpUrl, expiresAt }
 *       404 (flag off, or no legacy passkey: `passkey_not_found`) · 409 `second_factor_not_enabled`
 *       · 400 bad return URI / PKCE
 *     POST /api/auth/app-login/step-up/complete   (bearer; the same family and user as at start)
 *       { code, codeVerifier, returnUri } → 200 { verified: true }
 *       410 expired / consumed · 400 · 404 `code_not_found` (also for another family)
 *
 * The browser hands back to `<scheme>://login/callback?requestId=…&code=…` (or `error=expired` /
 * `error=access_denied`), the same callback the browser login uses ([parseAppLoginCallback]).
 */
internal const val APP_WEB_STEP_UP_START_PATH_PENDING = "auth/app-login/step-up/start"
internal const val APP_WEB_STEP_UP_COMPLETE_PATH_PENDING = "auth/app-login/step-up/complete"

private val appWebStepUpJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Start's body (pending contract verification). [action]: the refused action's `StepUpAction` key
 * (slock `stepUpGate.ts` `STEP_UP_ACTIONS`), shown on the web page; omitted when the caller can't
 * name it.
 */
@Serializable
data class AppWebStepUpStartBodyPending(
    val returnUri: String,
    val codeChallenge: String,
    val codeChallengeMethod: String = "S256",
    val clientId: String? = null,
    val action: String? = null,
)

/** Start's answer (pending contract verification). */
@Serializable
internal data class AppWebStepUpStartResponsePending(
    val requestId: String = "",
    val stepUpUrl: String = "",
    val expiresAt: String = "",
)

/** Complete's body (pending contract verification). */
@Serializable
data class AppWebStepUpCompleteBodyPending(
    val code: String,
    val codeVerifier: String,
    val returnUri: String,
)

@Serializable
private data class AppWebStepUpCompleteResponsePending(val verified: Boolean = false)

@Serializable
private data class AppWebStepUpRefusalDto(val error: String? = null, val code: String? = null)

sealed interface AppWebStepUpStartResult {
    data class Started(val requestId: String, val stepUpUrl: String, val expiresAt: String) : AppWebStepUpStartResult
    /** 404: the server has no web step-up for this person (flag off, or no legacy passkey): Open on the web instead. */
    data object Unavailable : AppWebStepUpStartResult
    /** 409 `second_factor_not_enabled`: two-step verification went off meanwhile; the action just goes again. */
    data object NotEnabled : AppWebStepUpStartResult
    data class Failed(val serverMessage: String? = null, val serverCode: String? = null, val traceId: String? = null) : AppWebStepUpStartResult
}

sealed interface AppWebStepUpCompleteResult {
    data object Verified : AppWebStepUpCompleteResult
    /** 409 `second_factor_not_enabled`: as [Verified], the action goes again. */
    data object NotEnabled : AppWebStepUpCompleteResult
    /** 410: the request or its code ran out (or was used). */
    data class Expired(val traceId: String? = null) : AppWebStepUpCompleteResult
    data class Failed(val serverMessage: String? = null, val serverCode: String? = null, val traceId: String? = null) : AppWebStepUpCompleteResult
}

private fun parseAppWebStepUpRefusal(bodyText: String?): AppWebStepUpRefusalDto? =
    bodyText?.takeIf { it.isNotBlank() }?.let { body ->
        runCatching { appWebStepUpJson.decodeFromString(AppWebStepUpRefusalDto.serializer(), body) }.getOrNull()
    }

internal fun buildAppWebStepUpStartBody(body: AppWebStepUpStartBodyPending): String =
    appWebStepUpJson.encodeToString(AppWebStepUpStartBodyPending.serializer(), body)

internal fun buildAppWebStepUpCompleteBody(body: AppWebStepUpCompleteBodyPending): String =
    appWebStepUpJson.encodeToString(AppWebStepUpCompleteBodyPending.serializer(), body)

/** Start's answer from its status, body and `X-Trace-Id` (pending contract verification). */
internal fun parseAppWebStepUpStart(statusCode: Int, bodyText: String?, traceId: String? = null): AppWebStepUpStartResult {
    if (statusCode in 200..299) {
        val response =
            bodyText?.takeIf { it.isNotBlank() }?.let { body ->
                runCatching { appWebStepUpJson.decodeFromString(AppWebStepUpStartResponsePending.serializer(), body) }.getOrNull()
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
        else ->
            AppWebStepUpStartResult.Failed(
                serverMessage = refusal?.error?.takeIf { it.isNotBlank() },
                serverCode = refusal?.code ?: "http_$statusCode",
                traceId = traceId,
            )
    }
}

/** Complete's answer from its status, body and `X-Trace-Id` (pending contract verification). */
internal fun parseAppWebStepUpComplete(statusCode: Int, bodyText: String?, traceId: String? = null): AppWebStepUpCompleteResult {
    if (statusCode in 200..299) {
        val verified =
            bodyText?.takeIf { it.isNotBlank() }?.let { body ->
                runCatching { appWebStepUpJson.decodeFromString(AppWebStepUpCompleteResponsePending.serializer(), body) }.getOrNull()
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
        refusal?.code == "second_factor_not_enabled" -> AppWebStepUpCompleteResult.NotEnabled
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
 * sign-in's bearer; user-blocking: Foreground).
 */
internal class AppWebStepUpRepository(private val networkClient: RaftNetworkClient) {
    suspend fun start(body: AppWebStepUpStartBodyPending): AppWebStepUpStartResult =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = APP_WEB_STEP_UP_START_PATH_PENDING,
                    method = "POST",
                    bodyText = buildAppWebStepUpStartBody(body),
                )
            parseAppWebStepUpStart(response.code, response.bodyText?.takeIf { it.isNotBlank() } ?: response.errorBodyText, response.traceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AppWebStepUpStartResult.Failed(serverCode = "network")
        }

    suspend fun complete(body: AppWebStepUpCompleteBodyPending): AppWebStepUpCompleteResult =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = APP_WEB_STEP_UP_COMPLETE_PATH_PENDING,
                    method = "POST",
                    bodyText = buildAppWebStepUpCompleteBody(body),
                )
            parseAppWebStepUpComplete(response.code, response.bodyText?.takeIf { it.isNotBlank() } ?: response.errorBodyText, response.traceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AppWebStepUpCompleteResult.Failed(serverCode = "network")
        }
}
