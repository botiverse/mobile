package ai.slock.android

import android.app.Application
import ai.slock.android.core.data.auth.androidMMKVAuthKeyValueStore
import ai.slock.android.core.data.diagnostics.SlockAndroidBuglyDiagnostics
import ai.slock.android.core.data.diagnostics.SlockCrashReporter
import ai.slock.compose.data.KuiklyMMKVAuthSessionStore
import kotlinx.coroutines.runBlocking

class SlockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SlockAndroidBuglyDiagnostics.init(this)
        runCatching {
            runBlocking {
                KuiklyMMKVAuthSessionStore(androidMMKVAuthKeyValueStore(this@SlockApplication)).readSession()
            }
        }.getOrNull()?.let { session ->
            SlockAndroidBuglyDiagnostics.updateUserContext(
                context = this,
                userId = session.user.id,
                selectedServerId = session.currentServerId,
                selectedServerSlug = session.selectedServerSlug
            )
        }
        SlockCrashReporter.install(this)
    }
}
