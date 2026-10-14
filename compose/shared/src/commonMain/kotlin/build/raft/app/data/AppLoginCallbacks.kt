package build.raft.app.data

import build.raft.app.slockLogInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Return URIs the browser login may hand back to. Must match the server's allowlist
 * (slock `appLoginService.ts`): release builds use `raft`, the internal channels their own scheme.
 */
val APP_LOGIN_RETURN_URIS: Set<String> =
    setOf(
        "raft://login/callback",
        "raft-alpha://login/callback",
        "raft-beta://login/callback",
        "raft-debug://login/callback",
    )

/** One browser login callback: a [requestId] plus exactly one of [code] or [error]. */
data class AppLoginCallback(
    val returnUri: String,
    val requestId: String,
    val code: String,
    val error: String,
)

/**
 * Strict parse of `<scheme>://login/callback?requestId=…&code=…` (or `error=…`), the same rules as the
 * HarmonyOS host: no fragment, only `requestId` / `code` / `error`, no duplicates or empty values, and
 * exactly `requestId` plus one of `code` / `error`. Anything else is not a login callback.
 */
fun parseAppLoginCallback(uri: String?): AppLoginCallback? {
    if (uri == null || '#' in uri) return null
    val returnUri = APP_LOGIN_RETURN_URIS.firstOrNull { uri.startsWith("$it?") } ?: return null
    val values = linkedMapOf<String, String>()
    for (pair in uri.substring(returnUri.length + 1).split('&')) {
        val separator = pair.indexOf('=')
        if (separator < 1) return null
        val name = percentDecodeStrict(pair.substring(0, separator)) ?: return null
        val value = percentDecodeStrict(pair.substring(separator + 1)) ?: return null
        if (name !in APP_LOGIN_CALLBACK_PARAMS || name in values || value.isEmpty()) return null
        values[name] = value
    }
    if (values.size != 2 || "requestId" !in values || ("code" in values) == ("error" in values)) return null
    return AppLoginCallback(
        returnUri = returnUri,
        requestId = values.getValue("requestId"),
        code = values["code"].orEmpty(),
        error = values["error"].orEmpty(),
    )
}

private val APP_LOGIN_CALLBACK_PARAMS = setOf("requestId", "code", "error")

/** `decodeURIComponent`: `%XX` as UTF-8, `+` kept literal; malformed escapes or invalid UTF-8 → null. */
private fun percentDecodeStrict(raw: String): String? {
    if ('%' !in raw) return raw
    val bytes = ArrayList<Byte>(raw.length)
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char == '%') {
            if (index + 2 > raw.lastIndex) return null
            val hex = raw.substring(index + 1, index + 3)
            val byte = hex.toIntOrNull(16) ?: return null
            bytes += byte.toByte()
            index += 3
        } else {
            raw.substring(index, index + 1).encodeToByteArray().forEach { bytes += it }
            index += 1
        }
    }
    return runCatching { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) }.getOrNull()
}

/**
 * Hosts hand a browser login callback here (Android `MainActivity`, iOS `onOpenURL` / the web auth
 * session, the HarmonyOS KNOI export). The exchange runs in the background; the login page observes
 * [SlockWebLoginCompletion]. On a cold start the shared store registry may not exist yet, so the
 * callback is retried briefly in memory (the pending request itself survives on disk).
 */
object SlockAppLoginBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Parses [uri]; false (and nothing started) when it is not a well-formed login callback. */
    fun completeCallbackUri(uri: String?): Boolean {
        val callback = parseAppLoginCallback(uri) ?: return false
        return completeCallback(callback.returnUri, callback.requestId, callback.code, callback.error)
    }

    fun completeCallback(returnUri: String, requestId: String, code: String, error: String): Boolean {
        if (returnUri !in APP_LOGIN_RETURN_URIS || requestId.isBlank() || code.isBlank() == error.isBlank()) {
            return false
        }
        scope.launch {
            repeat(APP_LOGIN_CALLBACK_RETRIES) { attempt ->
                val result =
                    runCatchingPreservingCancellation { SlockAppLogin.complete(returnUri, requestId, code, error) }
                        .onFailure { slockLogInfo("SlockAppLogin", "app_login_callback_threw type=${it::class.simpleName}") }
                        .getOrDefault("login_error")
                if (result != "retry") {
                    slockLogInfo("SlockAppLogin", "app_login_callback_result result=$result attempt=$attempt")
                    if (result != "ignored") SlockWebLoginCompletion.publish(result)
                    return@launch
                }
                delay(APP_LOGIN_CALLBACK_RETRY_DELAY_MS)
            }
            slockLogInfo("SlockAppLogin", "app_login_callback_result result=login_error reason=retries_exhausted")
            SlockWebLoginCompletion.publish("login_error")
        }
        return true
    }
}

private const val APP_LOGIN_CALLBACK_RETRIES = 50
private const val APP_LOGIN_CALLBACK_RETRY_DELAY_MS = 100L
