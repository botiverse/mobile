package build.raft.app

import android.app.Application
import android.content.Context
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.core.data.installAndroidAppScopePlatformFileResolver
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.auth.installAndroidAppScopeSharedKeyValueStores
import build.raft.app.core.data.diagnostics.RaftAndroidLocalDiagnostics
import build.raft.app.data.raftClearLegacyFontPreference
import build.hands.update.HandsCrash
import build.raft.app.shared.AndroidAppScopeRealtimeOwner
import build.raft.app.shared.AndroidRealtimeKeepAliveController
import build.raft.app.shared.RaftAndroidHostHooks
import build.raft.app.shared.ProcessAndroidAttachmentMediaStoreCleanup
import build.raft.app.shared.ProcessAndroidAttachmentPublicDownloads
import build.raft.app.shared.installAndroidThreadComposerTemporaryFileRetirement
import build.raft.app.shared.isAndroidSystemDark
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.RaftAppAppearanceRuntime
import build.raft.app.data.installAndroidAppScopeEventBus
import build.raft.app.network.installAndroidRaftAppIdentity
import build.raft.app.network.installAndroidRaftUserAgentDevice
import build.raft.app.push.AndroidPushRegistration
import kotlinx.coroutines.runBlocking

class RaftApplication : Application() {
    override fun attachBaseContext(base: Context) {
        installCoroutineWorkerKeepAlive()
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        // Page routing: every :shared page renders through RaftPageRegistry; debug builds also
        // admit the debug-only :ui-cmp pages.
        RaftCmpRouter.configure(debugBuild = BuildConfig.DEBUG)
        RaftPageRegistry.install()
        RaftAndroidHostHooks.install(this)
        // All notification channels up front, independent of permission (see RaftNotificationChannels).
        build.raft.app.shared.RaftNotificationChannels.ensureAll(this)
        // Download centre notifications attach when the centre is first used, not at startup.
        val application = this
        build.raft.app.downloads.RaftDownloadCenterProcess.onCreated = { center ->
            build.raft.app.shared.RaftDownloadNotifications.attach(application, center)
        }
        recordCoroutineWorkerKeepAliveDiagnostics()
        ProcessAndroidAttachmentPublicDownloads.start(this)
        ProcessAndroidAttachmentMediaStoreCleanup.start(this)
        // Finished in-app update APKs for installed versions: drop them and their system notification.
        build.raft.app.shared.HandsUpdateDownloadCleanup.start(this)
        // task #6 WebView warm-up is scheduled from the first CMP frame (RaftCmpActivity), not
        // here: from onCreate it ran before the launch activity existed and lengthened cold start.
        RaftAndroidLocalDiagnostics.init(this)
        installAndroidRaftAppIdentity(
            name = "Raft",
            version = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.VERSION_CODE.toString(),
            buildType = BuildConfig.BUILD_TYPE
        )
        // Phone vs tablet in the User-Agent (`Mobile` on phones): the server's product-event
        // `$device_type` comes from it.
        installAndroidRaftUserAgentDevice(this)
        installRaftPlatformLogRecordSink { record ->
            when (record.hint) {
                RaftPlatformLogHint.AuthCritical ->
                    RaftAndroidLocalDiagnostics.recordCriticalLog(record.level, record.tag, record.message)
                RaftPlatformLogHint.None ->
                    RaftAndroidLocalDiagnostics.recordLog(record.level, record.tag, record.message)
            }
        }
        installAndroidAppScopeSharedKeyValueStores(this)
        // PROTOTYPE (agent/lagoon/ios-native-nav-proto): the login-free capture backend, only in a
        // debug visual-fixture build and only when the capture created this flag file.
        build.raft.app.network.RaftCaptureBackend.installIfRequested(
            BuildConfig.DEBUG && java.io.File(filesDir, "raft_capture_fake_backend").exists()
        )
        // Settings > Appearance > Theme, applied before any page composes.
        RaftAppAppearanceRuntime.onSystemDarkChanged(isAndroidSystemDark(resources.configuration.uiMode))
        RaftAppAppearanceRuntime.ensureLoaded()
        // The font picker is gone (the design system fixes the faces); drop its stored choice.
        raftClearLegacyFontPreference()
        installAndroidAppScopePlatformFileResolver(this)
        installAndroidThreadComposerTemporaryFileRetirement(this)
        installAndroidExternalShareInbox(this)
        installAndroidAppScopeEventBus(this)
        AndroidAppScopeRealtimeOwner.install(this)
        AndroidPushRegistration.start(this)
        AndroidRealtimeKeepAliveController.installNoPlayServicesFallback(this)
        // The one main-thread `runBlocking` on the auth-session lock that stays (allowlisted in
        // scripts/ci/check_source_constraints.py). The #3823 deadlock needs a Main-dispatched
        // coroutine already queued on the lock; here the main looper has not run a single message
        // yet (no Activity, Service, receiver or composition exists), and nothing installed above
        // starts a Main.immediate coroutine that reads the session, so a holder on another thread
        // releases to this caller's event loop. It stays synchronous on purpose: the cloned-install
        // id must be discarded and the diagnostics user context set before HandsCrash and any
        // Activity start. Do not add Main/Main.immediate session readers above this line.
        runCatching {
            runBlocking {
                val authStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(this@RaftApplication))
                // A restored/cloned install may carry an installation id without its session.
                authStore.discardAuthInstallationIdWithoutSession()
                authStore.readSession()
            }
        }.getOrNull()?.let { session ->
            RaftAndroidLocalDiagnostics.updateUserContext(
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
                RaftAndroidLocalDiagnostics.exportRecentDiagnosticsText(this)
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
private fun RaftApplication.recordCoroutineWorkerKeepAliveDiagnostics() {
    val readBack =
        runCatching {
            val tasksClass = Class.forName("kotlinx.coroutines.scheduling.TasksKt")
            val field = tasksClass.getDeclaredField("IDLE_WORKER_KEEP_ALIVE_NS")
            field.isAccessible = true
            field.getLong(null)
        }.getOrElse { -1L }
    RaftAndroidLocalDiagnostics.recordLog(
        "info",
        "SlockCoroutineWorkerKeepAlive",
        "coroutine_keepalive_ns=$readBack property=$COROUTINE_WORKER_KEEP_ALIVE_SEC"
    )
}
