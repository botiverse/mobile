package build.raft.app

import android.app.Application
import android.content.Context
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
import build.raft.app.push.AndroidPushRegistration
import kotlinx.coroutines.runBlocking

class SlockApplication : Application() {
    override fun attachBaseContext(base: Context) {
        installCoroutineWorkerKeepAlive()
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        recordCoroutineWorkerKeepAliveDiagnostics()
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
        AndroidPushRegistration.start(this)
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

internal const val COROUTINE_WORKER_KEEP_ALIVE_SEC = "315360000"

internal const val COROUTINE_WORKER_KEEP_ALIVE_PROPERTY =
    "kotlinx.coroutines.scheduler.keep.alive.sec"

/**
 * task #312: kotlinx.coroutines' `CoroutineScheduler.Worker.indexInArray` setter calls
 * `Thread.setName` while it may run inside `synchronized(workers)`. On ART that setName
 * suspends a peer thread while another worker blocks on the same monitor, forming a
 * SuspendThreadByPeer <-> workers-monitor deadlock (~1007s -> SIGABRT). The setName path is
 * only reachable from `tryTerminateWorker`, which runs only when a worker has been idle past
 * `IDLE_WORKER_KEEP_ALIVE_NS`. Pinning that keep-alive far in the future makes worker
 * termination (and therefore the setName/compaction path) unreachable.
 *
 * Must run before any coroutine/`Dispatchers` use, so it lives in `attachBaseContext`, the
 * earliest app-controlled point (`ContentProvider.onCreate` runs later). The value is
 * deliberately not `Long.MAX_VALUE`: worker park computes `nanoTime() + keepAliveNs` and a
 * too-large value overflows negative, which would terminate immediately.
 */
internal fun installCoroutineWorkerKeepAlive() {
    System.setProperty(COROUTINE_WORKER_KEEP_ALIVE_PROPERTY, COROUTINE_WORKER_KEEP_ALIVE_SEC)
}

/**
 * Read back the keep-alive the DefaultScheduler actually consumed (task #312). Reflects
 * the internal `TasksKt.IDLE_WORKER_KEEP_ALIVE_NS` so a device diagnostics bundle can
 * confirm the pin took effect at runtime. Best-effort; never throws.
 */
private fun SlockApplication.recordCoroutineWorkerKeepAliveDiagnostics() {
    val readBack =
        runCatching {
            val tasksClass = Class.forName("kotlinx.coroutines.scheduling.TasksKt")
            val field = tasksClass.getDeclaredField("IDLE_WORKER_KEEP_ALIVE_NS")
            field.isAccessible = true
            field.getLong(null)
        }.getOrElse { -1L }
    SlockAndroidLocalDiagnostics.recordLog(
        "info",
        "SlockCoroutineWorkerKeepAlive",
        "coroutine_keepalive_ns=$readBack property=$COROUTINE_WORKER_KEEP_ALIVE_SEC"
    )
}
