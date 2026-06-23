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
        environmentForKey(preferences.getString(KEY_ENVIRONMENT, null))

    fun select(key: String): AndroidApiServerEnvironment? {
        val environment = predefined.firstOrNull { it.key == key } ?: return null
        preferences.edit().putString(KEY_ENVIRONMENT, environment.key).commit()
        return environment
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
                apiBaseUrl = "https://raft-app-staging.botiverse.dev/api/",
                socketBaseUrl = "https://raft-app-staging.botiverse.dev",
                webUrl = "https://raft-app-staging.botiverse.dev/s/botiverse/"
            )

        val predefined: List<AndroidApiServerEnvironment> = listOf(Production, Staging)

        fun environmentForKey(key: String?): AndroidApiServerEnvironment =
            predefined.firstOrNull { it.key == key } ?: Production
    }
}
