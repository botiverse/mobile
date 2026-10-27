package build.raft.app.shared

import android.content.Context
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.data.KeyValueAuthSessionStore

internal object AndroidRealtimeSessionAvailability {
    fun hasStoredRealtimeSession(context: Context): Boolean =
        runCatching {
            val appContext = context.applicationContext
            // Lock-free: callers include Activity resume and Service.onStartCommand on the main
            // thread, where waiting on the session lock can deadlock (#3823).
            KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext))
                .readCommittedSessionSnapshot()
                ?.let { session ->
                    !session.accessToken.isNullOrBlank() && !session.currentServerId.isNullOrBlank()
                } == true
        }.getOrDefault(false)
}
