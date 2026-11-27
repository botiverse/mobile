package build.raft.app

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Bundle
import build.raft.webcontainer.WebContainerAndroidKeepAlive
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.core.data.installAndroidAppScopePlatformFileResolver
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.auth.installAndroidAppScopeSharedKeyValueStores
import build.raft.app.core.data.diagnostics.AndroidSystemKillReporter
import build.raft.app.core.data.diagnostics.RaftAndroidBackgroundKillNoticeHost
import build.raft.app.core.data.diagnostics.RaftAndroidLocalDiagnostics
import build.raft.app.core.data.diagnostics.RaftHandsLaunchUploads
import build.raft.app.core.data.diagnostics.RaftStartupPhaseMarker
import build.raft.app.core.data.diagnostics.RaftWebViewRendererGoneReporter
import build.raft.app.data.raftClearLegacyFontPreference
import build.hands.update.HandsCrash
import build.raft.app.shared.AndroidAppScopeRealtimeOwner
import build.raft.app.shared.AndroidDynamicShortcutCoordinator
import build.raft.app.shared.AndroidRealtimeKeepAliveController
import build.raft.app.shared.RaftAndroidHostHooks
import build.raft.app.shared.RaftAndroidInstallReferrer
import build.raft.app.shared.RaftAndroidStoreReview
import build.raft.app.shared.RaftAndroidNightMode
import build.raft.app.shared.RaftBuildStamp
import build.raft.app.shared.handsEffectiveReleaseChannelForRuntime
import build.raft.app.shared.installAndroidEarlyAccessPolicyHost
import build.raft.app.shared.ProcessAndroidAttachmentMediaStoreCleanup
import build.raft.app.shared.ProcessAndroidAttachmentPublicDownloads
import build.raft.app.shared.installAndroidThreadComposerTemporaryFileRetirement
import build.raft.app.shared.isAndroidSystemDark
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.RaftAppAppearanceRuntime
import build.raft.app.data.RaftAuthSessionState
import build.raft.app.data.RaftPushDeliveryTrace
import build.raft.app.data.installAndroidAppScopeEventBus
import build.raft.app.data.sessionOrNull
import build.raft.app.network.installAndroidRaftAppIdentity
import build.raft.app.network.installAndroidRaftUserAgentDevice
import build.raft.app.push.AndroidPushDeliveryTracker
import build.raft.app.push.AndroidPushRegistration
import kotlinx.coroutines.runBlocking

class RaftApplication : Application() {
    override fun attachBaseContext(base: Context) {
        installCoroutineWorkerKeepAlive()
        super.attachBaseContext(base)
        // Before ContentProvider init and onCreate, so an ANR or system kill anywhere in startup
        // still records which build exited (RaftBuildStamp).
        RaftBuildStamp.stampProcess(base, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
        // How far this process gets through startup, for the next launch's exit report (#250).
        RaftStartupPhaseMarker.start(base.filesDir, Application.getProcessName().orEmpty())
    }

    override fun onCreate() {
        super.onCreate()
        // Page routing: every :shared page renders through RaftPageRegistry; debug builds also
        // admit the debug-only :ui-cmp pages.
        RaftCmpRouter.configure(debugBuild = BuildConfig.DEBUG)
        // Composer-draft timing lines for the draft-kill acceptance (task #246): debuggable
        // builds only; release and Play never install the logcat sink.
        if (BuildConfig.DEBUG) {
            RaftDraftTimingLogcat.install(
                buildConfigDebug = BuildConfig.DEBUG,
                appDebuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
            )
        }
        RaftPageRegistry.install()
        RaftAndroidHostHooks.install(this)
        // Native LiveKit audio engine for Agent Voice calls + RECORD_AUDIO permission bridge.
        AndroidAgentVoiceCallEngine.install(this)
        // Native PCM16 microphone recorder for composer streaming voice dictation (#wg-mobile-sync:#334).
        AndroidVoiceDictationAudioRecorder.install(this)
        // The Google Play rating prompt for RaftRatingPrompt (`play` builds only).
        RaftAndroidStoreReview.install(this)
        // The install channel the minimum build gate asks: Hands (APK builds) or Google Play (`play`).
        build.raft.app.shared.RaftAndroidInstallChannels.install(this)
        // Alpha builds default the Navigation 3 page stack on; set before any window reads it.
        build.raft.app.nav.RaftNav3StackFlag.buildDefaultOn = BuildConfig.NAV3_STACK_DEFAULT_ON
        // All notification channels up front on IO, independent of permission (see RaftNotificationChannels).
        build.raft.app.shared.RaftNotificationChannels.ensureAllAsync(this)
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
        // Local Android background-kill whitelist guidance host (registered before diagnostics evaluates).
        RaftAndroidBackgroundKillNoticeHost.install(this)
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
                    // Shared lines were already admitted by the shared RaftDiagnosticsVolumePolicy.
                    RaftAndroidLocalDiagnostics.recordSharedLog(
                        record.level,
                        record.tag,
                        record.message,
                        record.persist,
                        recent = record.recent,
                    )
            }
        }
        installAndroidAppScopeSharedKeyValueStores(this)
        AndroidPushDeliveryTracker.install(this)
        // Settings > Appearance > Theme, applied before any page composes.
        RaftAppAppearanceRuntime.onSystemDarkChanged(isAndroidSystemDark(resources.configuration.uiMode))
        RaftAppAppearanceRuntime.ensureLoaded()
        // The window theme follows that choice (DayNight Theme.Raft): WebViews, system dialogs,
        // pickers and bar icons match the app, not just the OS.
        RaftAndroidNightMode.install(this)
        // The font picker is gone (the design system fixes the faces); drop its stored choice.
        raftClearLegacyFontPreference()
        installAndroidAppScopePlatformFileResolver(this)
        installAndroidThreadComposerTemporaryFileRetirement(this)
        installAndroidExternalShareInbox(this)
        AndroidDynamicShortcutCoordinator.install(this)
        installAndroidAppScopeEventBus(this)
        AndroidAppScopeRealtimeOwner.install(this)
        AndroidPushRegistration.start(this)
        AndroidRealtimeKeepAliveController.installNoPlayServicesFallback(this)
        // Install source (Play Install Referrer), read once per install on a background thread;
        // reported once after the first sign-in (RaftInstallSourceReport).
        RaftAndroidInstallReferrer.start(this)
        // The one main-thread `runBlocking` on the auth-session lock that stays (allowlisted in
        // scripts/ci/check_source_constraints.py). The #3823 deadlock needs a Main-dispatched
        // coroutine already queued on the lock; here the main looper has not run a single message
        // yet (no Activity, Service, receiver or composition exists), and nothing installed above
        // starts a Main.immediate coroutine that reads the session, so a holder on another thread
        // releases to this caller's event loop. It stays synchronous on purpose: the cloned-install
        // id must be discarded and the diagnostics user context set before HandsCrash and any
        // Activity start. Do not add Main/Main.immediate session readers above this line.
        val initialSessionState =
            runCatching {
                runBlocking {
                    val authStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(this@RaftApplication))
                    // A restored/cloned install may carry an installation id without its session.
                    authStore.discardAuthInstallationIdWithoutSession()
                    authStore.readSessionState()
                }
            }.getOrNull()
        if (initialSessionState is RaftAuthSessionState.SignedOut) {
            AndroidDynamicShortcutCoordinator.clear("cold_start_signed_out")
            RaftPushDeliveryTrace.onSignedOut()
        }
        initialSessionState?.sessionOrNull?.let { session ->
            RaftPushDeliveryTrace.setKnownAccountHint(
                accountId = session.user.id,
                serverId = session.currentServerId,
            )
            RaftAndroidLocalDiagnostics.updateUserContext(
                context = this,
                userId = session.user.id,
                selectedServerId = session.currentServerId,
                selectedServerSlug = session.selectedServerSlug
            )
        }
        // Keep our `raft-build` stamp (AndroidSystemKillReporter reads it); the SDK's ANR
        // reporter reads it too, so it need not rewrite it to its own `hands-build` format.
        HandsCrash.setAnrBuildStampingEnabled(false)
        installAndroidEarlyAccessPolicyHost(this)
        val effectiveHandsChannel = handsEffectiveReleaseChannelForRuntime(this)
        HandsCrash.install(
            context = this,
            baseUrl = BuildConfig.HANDS_BASE_URL,
            appSlug = BuildConfig.HANDS_APP_SLUG,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            channel = effectiveHandsChannel,
            clientKey = BuildConfig.HANDS_CLIENT_KEY,
            // ANRs are filed with the hung build, read from each exit's RaftBuildStamp.
            captureAnrs = true,
            // RAFT_PRIVACY_MODE: the SDK records as before, but its launch uploads (device ping,
            // crashes, ANRs, native crashes) are run by RaftHandsLaunchUploads, only once every
            // signed-in server answered privacy mode off. Release-health sessions (sent to Hands as
            // they happen) stay off: they come only with Hands.install(trackSessions = true), which
            // this app does not call, and the SDK (0.14.3) cannot start them after install.
            uploadOnLaunch = false,
            reportDeviceAnalytics = false,
            extraContext = {
                RaftAndroidLocalDiagnostics.exportRecentDiagnosticsText(this)
            }
        )
        RaftHandsLaunchUploads.start(
            context = this,
            baseUrl = BuildConfig.HANDS_BASE_URL,
            appSlug = BuildConfig.HANDS_APP_SLUG,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            channel = effectiveHandsChannel,
            clientKey = BuildConfig.HANDS_CLIENT_KEY,
        )
        // System kills (low memory, freezer, OEM `reason=other`) leave no crash for HandsCrash to
        // catch; report the ones the user saw as crash tickets (ANRs stay with HandsCrash). Sent
        // only once every signed-in server answered privacy mode off (RaftServicesPrivacyGate).
        AndroidSystemKillReporter.start(
            context = this,
            baseUrl = BuildConfig.HANDS_BASE_URL,
            appSlug = BuildConfig.HANDS_APP_SLUG,
            channel = effectiveHandsChannel,
            clientKey = BuildConfig.HANDS_CLIENT_KEY,
        )
        // A WebView renderer death the app survived (every WebView handles onRenderProcessGone):
        // a non-fatal ticket for a crash, at most one a minute and a few a day.
        RaftWebViewRendererGoneReporter.install(
            context = this,
            baseUrl = BuildConfig.HANDS_BASE_URL,
            appSlug = BuildConfig.HANDS_APP_SLUG,
            channel = effectiveHandsChannel,
            clientKey = BuildConfig.HANDS_CLIENT_KEY,
        )
        RaftStartupPhaseMarker.reached(RaftStartupPhaseMarker.Phase.ApplicationCreated)
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    RaftStartupPhaseMarker.reached(RaftStartupPhaseMarker.Phase.ActivityCreated)
                }
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        // Debug-only StrictMode guardrail (log-only): installed after intentional cold-start
        // setup so accidental main-thread disk I/O or slow calls surface in logcat without
        // crashing debug builds or ever running in release/Play builds.
        if (BuildConfig.DEBUG) {
            RaftStrictMode.installIfDebug(
                buildConfigDebug = BuildConfig.DEBUG,
                appDebuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
            )
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { WebContainerAndroidKeepAlive.onTrimMemory(level) }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { WebContainerAndroidKeepAlive.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
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
