package build.raft.app.data

import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.createPlatformRaftHttpExecutor
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Android/iOS show the browser web login by default. Turning this pre-auth flag
 * ON brings back the email/social login page. It is inverted on purpose: the public flag endpoint answers
 * `false` for unknown, unmarked and disabled keys alike, so a pre-auth flag must be "off by default, server
 * turns on" (slock spec v3 "登录前可评估的 Feature Flag").
 */
const val MOBILE_LEGACY_LOGIN_FEATURE_FLAG_KEY: String = "mobile_legacy_login_v0"

private const val LEGACY_LOGIN_DECISION_PREFIX = "legacyLogin:"
private const val LEGACY_LOGIN_ON = "on"
private const val LEGACY_LOGIN_OFF = "off"

/** Budget for one fetch; a later answer is still cached for the next login page. */
private const val MOBILE_LEGACY_LOGIN_FETCH_TIMEOUT_MS = 10_000L

/** A fresh answer may switch the visible login page only this soon after it opened, never mid-typing. */
internal const val MOBILE_LEGACY_LOGIN_APPLY_WINDOW_MS = 2_000L

/**
 * The login page renders before sign-in, so the decision comes from `GET /api/public/feature-flags`
 * (no auth). The last answer is cached per API base URL (official and self-hosted servers decide separately)
 * and used when the request fails, times out or 404s (server without the endpoint). With no answer ever,
 * web login is used.
 */
class MobileWebLoginGate internal constructor(
    private val store: RaftKeyValueStore,
    private val fetchLegacyLogin: suspend (apiBaseUrl: String) -> Boolean?,
) {
    fun isEnabled(apiBaseUrl: String): Boolean = store.getString(decisionKey(apiBaseUrl)) != LEGACY_LOGIN_ON

    /** Fetches a fresh answer, caches it, and returns whether web login is enabled. */
    suspend fun refresh(apiBaseUrl: String): Boolean {
        val legacy = fetchLegacyLogin(apiBaseUrl) ?: return isEnabled(apiBaseUrl)
        store.putString(decisionKey(apiBaseUrl), if (legacy) LEGACY_LOGIN_ON else LEGACY_LOGIN_OFF)
        return !legacy
    }

    private fun decisionKey(apiBaseUrl: String) = LEGACY_LOGIN_DECISION_PREFIX + apiBaseUrl.trimEnd('/')
}

/** `{"flags":{"<key>":true|false}}` → the value; anything else (other status, shape or key missing) → null. */
internal fun parsePublicFeatureFlag(code: Int, bodyText: String?, key: String): Boolean? {
    if (code !in 200..299 || bodyText.isNullOrBlank()) return null
    return runCatching {
        (Json.parseToJsonElement(bodyText).jsonObject["flags"] as? JsonObject)
            ?.get(key)
            ?.jsonPrimitive
            ?.takeUnless { it.isString }
            ?.booleanOrNull
    }.getOrNull()
}

internal suspend fun fetchPublicLegacyLoginFlag(executor: RaftHttpExecutor, apiBaseUrl: String): Boolean? {
    if (apiBaseUrl.isBlank()) return null
    val response =
        runCatchingPreservingCancellation {
            executor.execute(
                priority = RaftRequestPriority.Foreground,
                url = "${apiBaseUrl.trimEnd('/')}/public/feature-flags" +
                    "?keys=$MOBILE_LEGACY_LOGIN_FEATURE_FLAG_KEY&platform=mobile",
                method = "GET",
                headers = emptyMap(),
                bodyText = null,
                jsonBody = false,
            )
        }.getOrNull() ?: return null
    return parsePublicFeatureFlag(response.code, response.bodyText, MOBILE_LEGACY_LOGIN_FEATURE_FLAG_KEY)
}

/** Process-wide gate over the app-scope store that also holds the pending browser login. */
object RaftMobileWebLoginGate {
    private val gate by lazy {
        val executor = createPlatformRaftHttpExecutor {}
        MobileWebLoginGate(
            RaftSharedKeyValueStoreProvider.create(RAFT_APP_LOGIN_MMKV_NAMESPACE, "App login").store,
        ) { apiBaseUrl ->
            withTimeoutOrNull(MOBILE_LEGACY_LOGIN_FETCH_TIMEOUT_MS) { fetchPublicLegacyLoginFlag(executor, apiBaseUrl) }
        }
    }

    fun isEnabled(apiBaseUrl: String): Boolean = runCatching { gate.isEnabled(apiBaseUrl) }.getOrDefault(true)

    suspend fun refresh(apiBaseUrl: String): Boolean =
        runCatchingPreservingCancellation { gate.refresh(apiBaseUrl) }.getOrElse { isEnabled(apiBaseUrl) }
}
