package build.raft.app

import android.app.Application
import build.raft.app.core.data.installAndroidAppScopePlatformFileResolver
import build.raft.app.core.data.auth.androidMMKVApiEnvironmentKeyValueStore
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.auth.androidMMKVNetworkRecoveryKeyValueStore
import build.raft.app.core.data.auth.installAndroidAppScopeSharedKeyValueStores
import build.raft.app.core.data.diagnostics.SlockAndroidLocalDiagnostics
import build.hands.update.HandsCrash
import build.raft.app.shared.AndroidAppScopeRealtimeOwner
import build.raft.app.shared.ProcessAndroidAttachmentMediaStoreCleanup
import build.raft.app.shared.ProcessAndroidAttachmentPublicDownloads
import build.raft.app.shared.installAndroidThreadComposerTemporaryFileRetirement
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.installAndroidAppScopeEventBus
import build.raft.app.network.installAndroidNetworkKmmReusedHttp2RecoveryAtStartup
import build.raft.app.network.installAndroidNetworkKmmAppIdentity
import build.raft.app.network.installAndroidSlockNetworkTransportAtStartup
import kotlinx.coroutines.runBlocking

class SlockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessAndroidAttachmentPublicDownloads.start(this)
        ProcessAndroidAttachmentMediaStoreCleanup.start(this)
        SlockAndroidLocalDiagnostics.init(this)
        installAndroidNetworkKmmAppIdentity(
            name = "Raft",
            version = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.VERSION_CODE.toString(),
            buildType = BuildConfig.BUILD_TYPE
        )
        runCatching {
            installAndroidSlockNetworkTransportAtStartup(this)
        }.onSuccess { diagnostics ->
            SlockAndroidLocalDiagnostics.recordLog("info", "SlockNetworkKmm", diagnostics)
        }.onFailure { error ->
            SlockAndroidLocalDiagnostics.recordCriticalLog(
                "error",
                "SlockNetworkKmm",
                "networkkmm_curl_bootstrap platform=android configured=false " +
                    "errorType=${error::class.simpleName ?: "unknown"}"
            )
        }
        runCatching {
            installAndroidNetworkKmmReusedHttp2RecoveryAtStartup(
                store = androidMMKVNetworkRecoveryKeyValueStore(this),
                currentEnvironment = {
                    SharedApiEnvironmentStore(
                        androidMMKVApiEnvironmentKeyValueStore(this)
                    ).current()
                }
            )
        }.onSuccess { diagnostics ->
            if (
                diagnostics.contains("remoteKillLatchRead=failed") ||
                diagnostics.contains("apiEnvironmentRead=failed")
            ) {
                SlockAndroidLocalDiagnostics.recordCriticalLog("error", "SlockNetworkKmm", diagnostics)
            } else {
                SlockAndroidLocalDiagnostics.recordLog("info", "SlockNetworkKmm", diagnostics)
            }
        }.onFailure { error ->
            SlockAndroidLocalDiagnostics.recordCriticalLog(
                "error",
                "SlockNetworkKmm",
                "networkkmm_reused_h2_recovery startup_failed=true recoveryEnabled=unknown " +
                    "errorType=${error::class.simpleName ?: "unknown"}"
            )
        }
        installSlockPlatformLogRecordSink { record ->
            when (record.hint) {
                SlockPlatformLogHint.AuthCritical ->
                    SlockAndroidLocalDiagnostics.recordCriticalLog(record.level, record.tag, record.message)
                SlockPlatformLogHint.None ->
                    SlockAndroidLocalDiagnostics.recordLog(record.level, record.tag, record.message)
            }
        }
        installAndroidAppScopeSharedKeyValueStores(this)
        installAndroidAppScopePlatformFileResolver(this)
        installAndroidThreadComposerTemporaryFileRetirement(this)
        installAndroidExternalShareInbox(this)
        installAndroidAppScopeEventBus(this)
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
            baseUrl = BuildConfig.HANDS_BASE_URL,
            appSlug = BuildConfig.HANDS_APP_SLUG,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            channel = BuildConfig.HANDS_CHANNEL,
            clientKey = BuildConfig.HANDS_CLIENT_KEY,
            extraContext = {
                SlockAndroidLocalDiagnostics.exportRecentDiagnosticsText(this)
            }
        )
    }
}
