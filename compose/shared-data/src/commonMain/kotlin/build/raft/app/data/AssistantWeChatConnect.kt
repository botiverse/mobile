package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ServerId
import build.raft.app.reportUnknownTypeDropped
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Connecting the personal assistant to WeChat (web assistant/wechatClient.ts `startWeChatAttempt`,
 * `pollWeChatAttempt`; server botiverse/slock staging 34511fe7
 * packages/server/src/routes/assistantWeChat.ts, services/wechatBridgeService.ts,
 * services/oauthBrowserBinding.ts):
 *
 *   POST servers/:id/assistant/wechat/attempts {}
 *     → 201 { attemptId, qrContent, expiresAt } + Set-Cookie raft_wechat_bind_<16 hex>=<state>;
 *       Path=…/attempts; Max-Age=300; HttpOnly; SameSite=Lax
 *       429 wechat_rate_limited, 404 wechat_unavailable / assistant_not_found,
 *       409 wechat_already_connected / wechat_attempt_in_progress, 502 wechat_upstream_unavailable
 *   POST servers/:id/assistant/wechat/attempts/:attemptId/poll {} | { verifyCode }
 *     → { status: wait | scanned | need_verify_code | confirmed | expired | blocked | already_bound }
 *       (a long poll: the server waits up to 15 s on WeChat; a final answer clears the cookie with
 *       Max-Age=0), 400 invalid_verify_code, 403 wechat_attempt_unbound, 404 wechat_attempt_not_found,
 *       410 wechat_attempt_used / wechat_attempt_expired, 429 wechat_poll_in_flight,
 *       409 wechat_account_in_use / assistant_not_found, 502 wechat_upstream_unavailable (try again) /
 *       wechat_upstream_refused (final), 404 wechat_unavailable.
 *   Errors are { error, code }.
 *
 * The attempt is bound to the client that started it by the start's HttpOnly cookie; only a poll
 * that carries it is answered. The app's HTTP client keeps no cookies (iOS AcceptPolicyNever, no
 * OkHttp CookieJar), so the binding cookie is carried by hand: [WeChatAttemptCookie] is read from
 * the start's own `Set-Cookie`, held in memory for that one attempt, and sent as `Cookie:` only on
 * that attempt's poll. It is never persisted and never logged (its toString says so).
 */

/** The binding cookie's name prefix (wechatBridgeService.ts `BROWSER_BINDING_PREFIX` + `_`). */
@RaftCoreInternalApi const val WECHAT_BIND_COOKIE_PREFIX: String = "raft_wechat_bind_"

/** Web `WECHAT_VERIFY_CODE_RE`: what WeChat's verification code may be (else 400 invalid_verify_code). */
@RaftCoreInternalApi val WeChatVerifyCodePattern: Regex = Regex("^[0-9A-Za-z]{1,16}$")

private val weChatConnectJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
private data class WireWeChatAttempt(
    val attemptId: String? = null,
    val qrContent: String? = null,
    val expiresAt: String? = null,
)

@Serializable
private data class WireWeChatPoll(val status: String? = null)

/** The poll's body: `{}`, or `{ "verifyCode": "…" }` after a `need_verify_code` answer. */
@Serializable
@RaftCoreInternalApi data class WeChatPollRequestBody(val verifyCode: String? = null)

/** Web `WeChatAttempt`: the QR code to show and when it runs out (ISO-8601). */
data class WeChatAttempt(val attemptId: String, val qrContent: String, val expiresAt: String)

/** Web `WeChatAttemptStatus`, in the server's spelling. */
enum class WeChatAttemptStatus(val wire: String) {
    Wait("wait"),
    Scanned("scanned"),
    NeedVerifyCode("need_verify_code"),
    Confirmed("confirmed"),
    Expired("expired"),
    Blocked("blocked"),
    AlreadyBound("already_bound");

    companion object {
        fun fromWire(value: String?): WeChatAttemptStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The binding cookie of one attempt, as `name=value` (the server's encoded value, sent back as is).
 * Bound to [attemptId]: the poll of any other attempt never carries it. Not a data class, and its
 * [toString] leaves the value out, so it cannot reach a log line by accident.
 */
class WeChatAttemptCookie @RaftCoreInternalApi constructor(
    val attemptId: String,
    @RaftCoreInternalApi val name: String,
    private val value: String,
) {
    /** The `Cookie` request header's value. */
    @RaftCoreInternalApi fun headerValue(): String = "$name=$value"

    override fun equals(other: Any?): Boolean =
        other is WeChatAttemptCookie && other.attemptId == attemptId && other.name == name && other.value == value

    override fun hashCode(): Int = (attemptId.hashCode() * 31 + name.hashCode()) * 31 + value.hashCode()

    override fun toString(): String = "WeChatAttemptCookie(attemptId=$attemptId, value=<redacted>)"
}

/** One `raft_wechat_bind_*` cookie a response set: its name, value and whether it was a removal. */
@RaftCoreInternalApi data class WeChatBindSetCookie(val name: String, val value: String, val cleared: Boolean) {
    override fun toString(): String = "WeChatBindSetCookie(name=$name, cleared=$cleared, value=<redacted>)"
}

// name=value up to `;` or `,` (the value is encodeURIComponent'd, so it has neither), then the
// attributes up to the next folded cookie (NSHTTPURLResponse joins several Set-Cookie with ", ").
private val BindSetCookiePattern =
    Regex("""(?:^|[,\s])(${WECHAT_BIND_COOKIE_PREFIX}[^=;,\s]+)=([^;,]*)((?:;[^,;]*)*)""")
private val MaxAgeZeroPattern = Regex("""(?i);\s*max-age\s*=\s*0*0\s*(?:;|$)""")

/**
 * The `raft_wechat_bind_*` cookies among [responseHeaders]' `Set-Cookie` values (any header name
 * case; one header per cookie, or several folded into one value). Every other cookie is ignored.
 */
@RaftCoreInternalApi fun weChatBindSetCookies(responseHeaders: Map<String, List<String>>): List<WeChatBindSetCookie> =
    responseHeaders.entries
        .filter { (name, _) -> name.equals("set-cookie", ignoreCase = true) }
        .flatMap { (_, values) -> values }
        .flatMap { value ->
            BindSetCookiePattern.findAll(value).map { match ->
                val cookieValue = match.groupValues[2].trim()
                val attributes = match.groupValues[3]
                WeChatBindSetCookie(
                    name = match.groupValues[1],
                    value = cookieValue,
                    cleared = cookieValue.isEmpty() || MaxAgeZeroPattern.containsMatchIn("$attributes;"),
                )
            }.toList()
        }

/** The binding cookie a start answer set for [attemptId]; null when it set none (or only a removal). */
@RaftCoreInternalApi fun weChatAttemptCookieOf(attemptId: String, responseHeaders: Map<String, List<String>>): WeChatAttemptCookie? =
    weChatBindSetCookies(responseHeaders)
        .lastOrNull { !it.cleared }
        ?.let { WeChatAttemptCookie(attemptId = attemptId, name = it.name, value = it.value) }

/** Whether a poll answer removed [cookie] (Max-Age=0 / empty value for its name). */
@RaftCoreInternalApi fun weChatAttemptCookieCleared(cookie: WeChatAttemptCookie, responseHeaders: Map<String, List<String>>): Boolean =
    weChatBindSetCookies(responseHeaders).any { it.cleared && it.name == cookie.name }

/** A 201 start body; null when a field is missing (web `invalid_response`). */
fun parseSharedWeChatAttempt(body: String?): WeChatAttempt? {
    val text = body?.takeIf(String::isNotBlank) ?: return null
    val wire =
        try {
            weChatConnectJson.decodeFromString(WireWeChatAttempt.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    val attemptId = wire.attemptId?.takeIf(String::isNotEmpty) ?: return null
    val qrContent = wire.qrContent?.takeIf(String::isNotEmpty) ?: return null
    val expiresAt = wire.expiresAt?.takeIf(String::isNotEmpty) ?: return null
    return WeChatAttempt(attemptId = attemptId, qrContent = qrContent, expiresAt = expiresAt)
}

/** A poll body's `status`; null when missing or unknown (an unknown one is reported, not dropped silently). */
fun parseSharedWeChatPollStatus(body: String?): WeChatAttemptStatus? {
    val text = body?.takeIf(String::isNotBlank) ?: return null
    val raw =
        try {
            weChatConnectJson.decodeFromString(WireWeChatPoll.serializer(), text).status
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    val status = WeChatAttemptStatus.fromWire(raw)
    if (status == null && raw != null) {
        reportUnknownTypeDropped(source = "assistant_wechat_poll", field = "status", value = raw, fallback = "retry")
    }
    return status
}

/** A refused or failed request (web `WeChatRequestError`): [httpStatus] null when no answer came. */
data class WeChatRequestFailure(val httpStatus: Int?, val error: ScreenError)

/** What one start gave. */
sealed interface WeChatStartResult {
    /** [cookie] is null when the 201 carried no binding cookie (every poll would be refused). */
    data class Started(val attempt: WeChatAttempt, val cookie: WeChatAttemptCookie?) : WeChatStartResult

    data class Failed(val failure: WeChatRequestFailure) : WeChatStartResult
}

/** What one poll gave; [cookieCleared] when the answer removed the attempt's binding cookie. */
sealed interface WeChatPollResult {
    val cookieCleared: Boolean

    data class Answered(val status: WeChatAttemptStatus, override val cookieCleared: Boolean) : WeChatPollResult

    data class Failed(val failure: WeChatRequestFailure, override val cookieCleared: Boolean) : WeChatPollResult
}

interface RaftAssistantWeChatConnectApi {
    suspend fun startWeChatAttempt(serverId: ServerId): WeChatStartResult

    /** Polls [cookie]'s attempt, carrying that cookie only; [verifyCode] after `need_verify_code`. */
    suspend fun pollWeChatAttempt(serverId: ServerId, cookie: WeChatAttemptCookie, verifyCode: String?): WeChatPollResult
}

private fun weChatFailureOf(code: Int, bodyText: String?, traceId: String?, fallback: String): WeChatRequestFailure =
    WeChatRequestFailure(
        httpStatus = code.takeIf { it > 0 },
        error = ScreenError(code = assistantWeChatErrorCode(bodyText) ?: "${fallback}_$code", traceId = traceId),
    )

@RaftCoreInternalApi fun weChatStartResultOf(code: Int, bodyText: String?, responseHeaders: Map<String, List<String>>, traceId: String?): WeChatStartResult {
    if (code !in 200..299) return WeChatStartResult.Failed(weChatFailureOf(code, bodyText, traceId, "wechat_start_failed"))
    val attempt =
        parseSharedWeChatAttempt(bodyText)
            ?: return WeChatStartResult.Failed(WeChatRequestFailure(null, ScreenError(code = "invalid_response", traceId = traceId)))
    return WeChatStartResult.Started(attempt, weChatAttemptCookieOf(attempt.attemptId, responseHeaders))
}

@RaftCoreInternalApi fun weChatPollResultOf(
    cookie: WeChatAttemptCookie,
    code: Int,
    bodyText: String?,
    responseHeaders: Map<String, List<String>>,
    traceId: String?,
): WeChatPollResult {
    val cleared = weChatAttemptCookieCleared(cookie, responseHeaders)
    if (code !in 200..299) return WeChatPollResult.Failed(weChatFailureOf(code, bodyText, traceId, "wechat_poll_failed"), cleared)
    val status =
        parseSharedWeChatPollStatus(bodyText)
            // Web: an unreadable answer is a WeChatRequestError with no status, which is tried again.
            ?: return WeChatPollResult.Failed(WeChatRequestFailure(null, ScreenError(code = "invalid_response", traceId = traceId)), cleared)
    return WeChatPollResult.Answered(status, cleared)
}

/** The error body's `code` (`{ error, code }`). */
private fun assistantWeChatErrorCode(bodyText: String?): String? {
    val text = bodyText?.takeIf(String::isNotBlank) ?: return null
    val body = runCatching { weChatConnectJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
    return (body["code"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}

@RaftCoreInternalApi class RaftAssistantWeChatConnectNetworkApi(
    private val networkClient: RaftNetworkClient,
) : RaftAssistantWeChatConnectApi {
    override suspend fun startWeChatAttempt(serverId: ServerId): WeChatStartResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "servers/${serverId.value}/assistant/wechat/attempts",
                    method = "POST",
                    serverIdOverride = serverId,
                    bodyText = "{}",
                )
            }.getOrElse {
                return WeChatStartResult.Failed(WeChatRequestFailure(null, ScreenError(code = "network", traceId = null)))
            }
        return weChatStartResultOf(response.code, response.bodyText ?: response.errorBodyText, response.responseHeaders, response.traceId)
    }

    override suspend fun pollWeChatAttempt(serverId: ServerId, cookie: WeChatAttemptCookie, verifyCode: String?): WeChatPollResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.requestWithHeaders(
                    // A long poll the person is watching; Visible, so it never holds a Foreground slot.
                    priority = DataRequestPriority.Visible,
                    path = "servers/${serverId.value}/assistant/wechat/attempts/${encodeWeChatPathSegment(cookie.attemptId)}/poll",
                    headers = mapOf(WECHAT_COOKIE_HEADER to cookie.headerValue()),
                    method = "POST",
                    serverIdOverride = serverId,
                    bodyText = weChatConnectJson.encodeToString(WeChatPollRequestBody.serializer(), WeChatPollRequestBody(verifyCode)),
                )
            }.getOrElse {
                return WeChatPollResult.Failed(WeChatRequestFailure(null, ScreenError(code = "network", traceId = null)), cookieCleared = false)
            }
        return weChatPollResultOf(cookie, response.code, response.bodyText ?: response.errorBodyText, response.responseHeaders, response.traceId)
    }
}

@RaftCoreInternalApi const val WECHAT_COOKIE_HEADER: String = "Cookie"

/** A path segment as `encodeURIComponent` writes it (attempt ids are UUIDs). */
private fun encodeWeChatPathSegment(value: String): String =
    buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-_.!~*'()") append(c) else {
                append('%')
                append(((byte.toInt() shr 4) and 0xF).toString(16).uppercase())
                append((byte.toInt() and 0xF).toString(16).uppercase())
            }
        }
    }
