package ai.slock.android.core.data.auth

import android.content.Context
import ai.slock.compose.data.SLOCK_API_BASE_URL
import ai.slock.compose.data.SLOCK_SOCKET_BASE_URL

data class AndroidApiServerEnvironment(
    val key: String,
    val label: String,
    val apiBaseUrl: String,
    val socketBaseUrl: String,
    val webUrl: String
)

class AndroidApiServerEnvironmentStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun current(): AndroidApiServerEnvironment =
        currentEnvironment(preferences.getString(KEY_ENVIRONMENT, null))

    fun select(key: String): AndroidApiServerEnvironment? {
        val environment = predefined.firstOrNull { it.key == key } ?: return null
        preferences.edit().putString(KEY_ENVIRONMENT, environment.key).commit()
        return environment
    }

    fun save(environment: AndroidApiServerEnvironment): AndroidApiServerEnvironment? {
        predefined.firstOrNull { it.key == environment.key }?.let { predefinedEnvironment ->
            preferences.edit().putString(KEY_ENVIRONMENT, predefinedEnvironment.key).commit()
            return predefinedEnvironment
        }
        val apiBaseUrl = environment.apiBaseUrl.trim()
        val socketBaseUrl = environment.socketBaseUrl.trim()
        val webUrl = environment.webUrl.trim()
        if (!apiBaseUrl.startsWith("http") || !socketBaseUrl.startsWith("http")) return null
        val custom =
            environment.copy(
                key = CustomKey,
                label = environment.label.ifBlank { "Custom" },
                apiBaseUrl = apiBaseUrl,
                socketBaseUrl = socketBaseUrl,
                webUrl = webUrl
            )
        preferences
            .edit()
            .putString(KEY_ENVIRONMENT, CustomKey)
            .putString(KEY_CUSTOM_LABEL, custom.label)
            .putString(KEY_CUSTOM_API_BASE_URL, custom.apiBaseUrl)
            .putString(KEY_CUSTOM_SOCKET_BASE_URL, custom.socketBaseUrl)
            .putString(KEY_CUSTOM_WEB_URL, custom.webUrl)
            .commit()
        return custom
    }

    fun rewriteApiUrl(url: String): String {
        val current = current()
        val productionBase = Production.apiBaseUrl
        if (current.apiBaseUrl == productionBase) return url
        return if (url.startsWith(productionBase)) {
            current.apiBaseUrl.trimEnd('/') + "/" + url.removePrefix(productionBase).trimStart('/')
        } else {
            url
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "slock_api_server_environment"
        private const val KEY_ENVIRONMENT = "environment"
        private const val KEY_CUSTOM_LABEL = "custom_label"
        private const val KEY_CUSTOM_API_BASE_URL = "custom_api_base_url"
        private const val KEY_CUSTOM_SOCKET_BASE_URL = "custom_socket_base_url"
        private const val KEY_CUSTOM_WEB_URL = "custom_web_url"
        const val CustomKey = "custom"

        val Production =
            AndroidApiServerEnvironment(
                key = "production",
                label = "Production",
                apiBaseUrl = SLOCK_API_BASE_URL,
                socketBaseUrl = SLOCK_SOCKET_BASE_URL,
                webUrl = "https://app.raft.build"
            )

        val Staging =
            AndroidApiServerEnvironment(
                key = "staging",
                label = "Staging",
                apiBaseUrl = "https://api-aws-staging.botiverse.dev/api/",
                socketBaseUrl = "https://api-aws-staging.botiverse.dev",
                webUrl = "https://raft-app-staging.botiverse.dev/s/botiverse/"
            )

        val predefined: List<AndroidApiServerEnvironment> = listOf(Production, Staging)

        fun environmentForKey(key: String?): AndroidApiServerEnvironment =
            predefined.firstOrNull { it.key == key } ?: Production
    }

    private fun currentEnvironment(key: String?): AndroidApiServerEnvironment {
        predefined.firstOrNull { it.key == key }?.let { return it }
        if (key == CustomKey) {
            val apiBaseUrl = preferences.getString(KEY_CUSTOM_API_BASE_URL, null).orEmpty()
            val socketBaseUrl = preferences.getString(KEY_CUSTOM_SOCKET_BASE_URL, null).orEmpty()
            if (apiBaseUrl.startsWith("http") && socketBaseUrl.startsWith("http")) {
                return AndroidApiServerEnvironment(
                    key = CustomKey,
                    label = preferences.getString(KEY_CUSTOM_LABEL, null).orEmpty().ifBlank { "Custom" },
                    apiBaseUrl = apiBaseUrl,
                    socketBaseUrl = socketBaseUrl,
                    webUrl = preferences.getString(KEY_CUSTOM_WEB_URL, null).orEmpty()
                )
            }
        }
        return Production
    }
}
