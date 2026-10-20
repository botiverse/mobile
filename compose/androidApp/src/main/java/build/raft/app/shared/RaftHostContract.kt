package build.raft.app.shared

import org.json.JSONObject

internal object RaftPages {
    const val LOGIN = "compose_login"
    const val REGISTER = "compose_register"
    const val FORGOT_PASSWORD = "compose_forgot_password"
    const val AUTH_LEGAL_ACCEPTANCE = "compose_auth_legal_acceptance"
    const val AUTH_CHOOSE_SERVER = "compose_auth_choose_server"
    const val APP_SHELL = "compose_slock_app_shell"
    const val THREAD = "compose_thread"
    const val THREAD_COMPOSER_VISUAL = "compose_thread_composer_visual"
    const val MARKDOWN_RERENDER_VISUAL = "compose_markdown_rerender_visual"
    const val FLOATING_OVERLAY_TOUCH_GUARD_VISUAL = "compose_floating_overlay_touch_guard_visual"
    const val HOME = "compose_home"
    // The old Home 动态 page. Activity is an app-shell bottom tab now (artin 2026-10-20); a launch
    // of this name is redirected onto the shell's Activity tab (raftLegacyAppShellTabRedirect).
    const val HOME_ACTIVITY = "compose_home_activity"
    const val MEMBERS = "compose_members"
    const val SETTINGS_HOME = "compose_settings_home"
    const val SETTINGS_ACCOUNT = "compose_settings_account"
    const val SETTINGS_STORAGE = "compose_settings_storage"
    const val SETTINGS_BILLING = "compose_settings_billing"
    const val ICON_GALLERY = "compose_icon_gallery"
    // Keep in sync with RAFT_IN_APP_BROWSER_PAGE in shared/InAppBrowserPage.kt; that const is
    // internal to the shared module and not visible here.
    const val IN_APP_BROWSER = "compose_in_app_browser"
    const val ROUTER = "router"
}

internal object RaftRouteKeys {
    const val AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD = "slockAuthChooseServerSkipInitialLoad"
    const val AUTH_LEGAL_PROVIDER = "slockAuthLegalProvider"
    const val AUTH_LEGAL_PLATFORM = "slockAuthLegalPlatform"
    const val AUTH_LEGAL_CODE = "slockAuthLegalCode"
    const val AUTH_LEGAL_CODE_VERIFIER = "slockAuthLegalCodeVerifier"
    const val AUTH_SOCIAL_PROVIDERS = "slockSocialAuthProviders"
}

internal data class RaftPageSpec(
        val pageName: String,
        val pageData: RaftPageData = RaftPageData.empty()
)

internal class RaftPageData private constructor(
        private val json: JSONObject
) {
    val text: String = json.toString()
    val instanceId: String get() = json.optString("instanceId")
    val shellInstanceId: String get() = json.optString("slockShellInstanceId")
    val payloadBytes: Int get() = json.optString("payload").length

    fun withInstanceId(instanceId: String): RaftPageData {
        if (instanceId.isBlank()) {
            return this
        }
        return fromJson(json).put("instanceId", instanceId)
    }

    fun toKuiklyMap(): MutableMap<String, Any> =
            json.toMap().toMutableMap().apply {
                put("appId", RAFT_APP_ID)
            }

    private fun put(key: String, value: Any): RaftPageData =
            fromJson(json).also { it.json.put(key, value) }

    companion object {
        fun empty(): RaftPageData = RaftPageData(JSONObject())

        fun fromJson(json: JSONObject): RaftPageData =
                RaftPageData(JSONObject(json.toString()))

        fun fromText(text: String?): RaftPageData =
                if (text.isNullOrBlank()) {
                    empty()
                } else {
                    runCatching { RaftPageData(JSONObject(text)) }.getOrElse { empty() }
                }
    }
}

private const val RAFT_APP_ID = 1

/**
 * Page names that became app-shell tabs. A launch of one opens the app shell on that tab instead
 * of a standalone page, carrying the launch's shell/scope keys along. Returns the app-shell
 * pageData, or null when [pageName] is not such a page.
 */
internal fun raftLegacyAppShellTabRedirect(pageName: String, pageData: JSONObject): JSONObject? {
    if (pageName != RaftPages.HOME_ACTIVITY) return null
    return JSONObject(pageData.toString()).apply {
        remove("pageName")
        remove("destination")
        put(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB, "activity")
    }
}
