package build.raft.app.data

/**
 * Android/iOS browser web login (the HarmonyOS login flow) instead of the email/social login page.
 * Default ON; the server turns it off with an explicit decision (artin 2026-10-14).
 */
const val MOBILE_WEB_LOGIN_FEATURE_FLAG_KEY: String = "mobile_web_login_v0"

/** Server evaluation reason for a key that has no flag row at all: not a decision. */
private const val MISSING_FLAG_REASON = "missing_flag"

private const val WEB_LOGIN_DECISION_KEY = "webLoginDecision"
private const val WEB_LOGIN_DECISION_ON = "on"
private const val WEB_LOGIN_DECISION_OFF = "off"

/**
 * The login page renders before sign-in, and feature flags are only evaluated for a signed-in account
 * (`/api/feature-flags` requires auth). So the last explicit decision for [MOBILE_WEB_LOGIN_FEATURE_FLAG_KEY]
 * is kept per device and survives logout: once the server turns the flag off, every device that has been
 * online since shows the email/social login on its next login page. A device with no decision (fresh install,
 * the flag row missing) uses web login.
 */
class MobileWebLoginGate(private val store: SlockKeyValueStore) {
    fun isEnabled(): Boolean = store.getString(WEB_LOGIN_DECISION_KEY) != WEB_LOGIN_DECISION_OFF

    /** One online evaluation of the flag. `missing_flag` is no decision and clears a stored one. */
    fun record(enabled: Boolean, reason: String?) {
        val value =
            when {
                reason == MISSING_FLAG_REASON -> null
                enabled -> WEB_LOGIN_DECISION_ON
                else -> WEB_LOGIN_DECISION_OFF
            }
        store.putString(WEB_LOGIN_DECISION_KEY, value)
    }
}

/** Process-wide gate over the app-scope store that also holds the pending browser login. */
object SlockMobileWebLoginGate {
    private val gate by lazy {
        MobileWebLoginGate(SlockSharedKeyValueStoreProvider.create(SLOCK_APP_LOGIN_MMKV_NAMESPACE, "App login").store)
    }

    fun isEnabled(): Boolean = runCatching { gate.isEnabled() }.getOrDefault(true)

    fun record(enabled: Boolean, reason: String?) {
        runCatching { gate.record(enabled, reason) }
    }
}
