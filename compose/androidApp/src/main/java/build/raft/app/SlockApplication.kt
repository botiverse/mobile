package build.raft.app

import android.app.Application
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.diagnostics.SlockAndroidBuglyDiagnostics
import build.raft.app.core.data.diagnostics.SlockCrashReporter
import build.raft.app.shared.AndroidAppScopeRealtimeOwner
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
