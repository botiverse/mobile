package ai.slock.android

import android.app.Application
import ai.slock.android.core.data.auth.androidMMKVAuthKeyValueStore
import ai.slock.android.core.data.diagnostics.SlockAndroidBuglyDiagnostics
import ai.slock.android.core.data.diagnostics.SlockCrashReporter
import ai.slock.android.shared.AndroidAppScopeRealtimeOwner
import ai.slock.compose.data.KuiklyMMKVAuthSessionStore
import ai.slock.compose.data.warmUpSlockJsonParseDispatcher
import com.tencent.kuiklybase.markdown.model.warmUpSlockMarkdownParseDispatcher
import kotlinx.coroutines.runBlocking

class SlockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SlockAndroidBuglyDiagnostics.init(this)
        warmUpSlockJsonParseDispatcher()
        warmUpSlockMarkdownParseDispatcher()
        AndroidAppScopeRealtimeOwner.install(this)
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
