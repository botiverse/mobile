package build.raft.app

import android.app.Application
import build.raft.app.core.data.installAndroidAppScopePlatformFileResolver
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.auth.installAndroidAppScopeSharedKeyValueStores
import build.raft.app.core.data.diagnostics.SlockAndroidLocalDiagnostics
import build.hands.update.HandsCrash
import build.raft.app.shared.AndroidAppScopeRealtimeOwner
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.installAndroidAppScopeEventBus
import build.raft.app.data.warmUpSlockJsonParseDispatcher
import com.tencent.kuiklybase.markdown.model.warmUpSlockMarkdownParseDispatcher
import kotlinx.coroutines.runBlocking

class SlockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SlockAndroidLocalDiagnostics.init(this)
        installSlockPlatformLogSink { level, tag, message ->
            if (tag == "SlockSharedAuth" || message.startsWith("shared_realtime_auth_refresh_")) {
                SlockAndroidLocalDiagnostics.recordAuthLog(level, tag, message)
            } else {
                SlockAndroidLocalDiagnostics.recordLog(level, tag, message)
            }
        }
        installAndroidAppScopeSharedKeyValueStores(this)
        installAndroidAppScopePlatformFileResolver(this)
        installAndroidAppScopeEventBus(this)
        warmUpSlockJsonParseDispatcher()
        warmUpSlockMarkdownParseDispatcher()
        AndroidAppScopeRealtimeOwner.install(this)
        runCatching {
            runBlocking {
                KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(this@SlockApplication)).readSession()
            }
        }.getOrNull()?.let { session ->
            SlockAndroidLocalDiagnostics.updateUserContext(
                context = this,
                userId = session.user.id,
                selectedServerId = session.currentServerId,
                selectedServerSlug = session.selectedServerSlug
            )
        }
        HandsCrash.install(
            context = this,
            baseUrl = BuildConfig.QUIVER_BASE_URL,
            appSlug = BuildConfig.QUIVER_APP_SLUG,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            channel = BuildConfig.QUIVER_CHANNEL,
            clientKey = BuildConfig.QUIVER_CLIENT_KEY,
            extraContext = {
                SlockAndroidLocalDiagnostics.exportRecentDiagnosticsText(this)
            }
        )
    }
}
