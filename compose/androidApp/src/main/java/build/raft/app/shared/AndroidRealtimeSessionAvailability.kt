package build.raft.app.shared

import android.content.Context
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.data.KuiklyMMKVAuthSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal object AndroidRealtimeSessionAvailability {
    fun hasStoredRealtimeSession(context: Context): Boolean =
        runCatching {
            val appContext = context.applicationContext
            runBlocking(Dispatchers.IO) {
                KuiklyMMKVAuthSessionStore(androidMMKVAuthKeyValueStore(appContext))
                    .readSession()
                    ?.let { session ->
                        !session.accessToken.isNullOrBlank() && !session.currentServerId.isNullOrBlank()
                    } == true
            }
        }.getOrDefault(false)
}
