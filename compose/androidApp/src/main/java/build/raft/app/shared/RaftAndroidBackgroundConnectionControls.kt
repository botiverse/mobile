package build.raft.app.shared

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import build.raft.app.core.data.auth.AndroidAppScopeMMKV
import build.raft.app.data.RaftBackgroundConnectionControls
import build.raft.app.data.RaftBackgroundWhitelistGuide
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unified Android Settings › Notifications host controls for background keep-alive (#proj-mobile:#325, #373).
 *
 * Installed on every Android build (no-Play-services direct, GMS direct, and Google Play).
 * - Direct-install GMS foreground-service override ([canOverrideWithForegroundService]) is ONLY
 *   exposed on direct-install APKs whose manifest declares [RaftBackgroundConnectionService] when
 *   Google Play services is available; on Google Play builds it is always false and setter calls
 *   are ignored.
 * - All system/OEM intents route through [RaftAndroidVendorWhitelist]; the direct exemption prompt
 *   (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) is only ever invoked when this build's merged
 *   manifest declares [Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] (stripped from the
 *   `play` build via `src/play/AndroidManifest.xml`).
 */
internal class RaftAndroidBackgroundConnectionControls(
    context: Context,
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val batteryOptimizationExemptProvider: (Context) -> Boolean = ::readBatteryOptimizationExempt,
    private val batteryOptimizationPermissionDeclaredProvider: (Context) -> Boolean = ::isBatteryOptimizationPermissionDeclared,
    private val noPlayServicesFallbackProvider: (Context) -> Boolean = AndroidRealtimeKeepAliveController::noPlayServicesFallbackApplies,
    private val directGmsKeepAliveOverrideAllowedProvider: (Context) -> Boolean = AndroidRealtimeKeepAliveController::directGmsKeepAliveOverrideAllowed,
    private val directGmsKeepAliveOverrideEnabledProvider: (Context) -> Boolean = AndroidRealtimeKeepAliveController::isDirectGmsKeepAliveOverrideEnabled,
    private val directGmsKeepAliveOverrideSetter: (Context, Boolean) -> Unit = { appContext, enabled ->
        AndroidAppScopeMMKV.keepAliveRunner {
            AndroidRealtimeKeepAliveController.setDirectGmsKeepAliveOverrideEnabled(appContext, enabled)
        }
    },
    private val vendorProvider: () -> AndroidOemVendor = { RaftAndroidVendorWhitelist.resolveVendor() },
    private val intentLauncher: (Intent) -> Unit = { intent -> context.applicationContext.startActivity(intent) },
) : RaftBackgroundConnectionControls {
    private val appContext = context.applicationContext

    @Volatile
    private var cachedBatteryOptimizationPermissionDeclared: Boolean? = null

    override fun isOffered(): Boolean = noPlayServicesFallbackProvider(appContext)

    override fun canOverrideWithForegroundService(): Boolean =
        runCatching {
            directGmsKeepAliveOverrideAllowedProvider(appContext) && !noPlayServicesFallbackProvider(appContext)
        }.getOrDefault(false)

    private val overrideStateLock = Any()
    private val overrideStateVersion = AtomicLong(0L)
    private val directGmsOverrideState = MutableStateFlow(false)
    private val directGmsOverrideStateView = directGmsOverrideState.asStateFlow()
    private val initialOverrideRefreshScheduled = AtomicBoolean(false)

    override val directGmsKeepAliveOverrideState: StateFlow<Boolean>
        get() {
            if (initialOverrideRefreshScheduled.compareAndSet(false, true)) {
                val scheduledVersion = overrideStateVersion.get()
                refreshScope.launch { refreshDirectGmsKeepAliveOverrideStatus(expectedVersion = scheduledVersion) }
            }
            return directGmsOverrideStateView
        }

    /** Called on startup IO and activity resume to keep the UI toggle aligned with stored state. */
    fun refreshDirectGmsKeepAliveOverrideStatus() {
        refreshDirectGmsKeepAliveOverrideStatus(expectedVersion = overrideStateVersion.get())
    }

    private fun refreshDirectGmsKeepAliveOverrideStatus(expectedVersion: Long) {
        initialOverrideRefreshScheduled.set(true)
        if (overrideStateVersion.get() != expectedVersion) return
        val resolved =
            if (canOverrideWithForegroundService()) {
                runCatching { directGmsKeepAliveOverrideEnabledProvider(appContext) }.getOrDefault(false)
            } else {
                false
            }
        synchronized(overrideStateLock) {
            if (overrideStateVersion.get() == expectedVersion) {
                directGmsOverrideState.value = resolved
            }
        }
    }

    override fun setDirectGmsKeepAliveOverrideEnabled(enabled: Boolean) {
        if (!canOverrideWithForegroundService()) {
            synchronized(overrideStateLock) {
                overrideStateVersion.incrementAndGet()
                directGmsOverrideState.value = false
            }
            return
        }
        initialOverrideRefreshScheduled.set(true)
        synchronized(overrideStateLock) {
            overrideStateVersion.incrementAndGet()
            directGmsOverrideState.value = enabled
        }
        directGmsKeepAliveOverrideSetter(appContext, enabled)
    }

    override fun canRequestBatteryOptimizationExemption(): Boolean =
        cachedBatteryOptimizationPermissionDeclared ?: runCatching {
            batteryOptimizationPermissionDeclaredProvider(appContext)
        }.getOrDefault(false).also { declared ->
            cachedBatteryOptimizationPermissionDeclared = declared
        }

    override fun whitelistGuide(): RaftBackgroundWhitelistGuide =
        RaftAndroidVendorWhitelist.guideFor(vendorProvider())

    override fun isBatteryOptimizationExempt(): Boolean =
        runCatching {
            batteryOptimizationExemptProvider(appContext)
        }.getOrDefault(false)

    // Avoid calling PowerManager.isIgnoringBatteryOptimizations() synchronously in the constructor
    // on Main. Initialized asynchronously on IO at startup (when the direct exemption prompt applies),
    // on resume, or on first observation.
    private val exemptState = MutableStateFlow(false)
    private val exemptStateView = exemptState.asStateFlow()
    private val initialRefreshScheduled = AtomicBoolean(false)

    override val batteryOptimizationExemptState: StateFlow<Boolean>
        get() {
            if (initialRefreshScheduled.compareAndSet(false, true)) {
                refreshScope.launch { refreshBatteryOptimizationStatus() }
            }
            return exemptStateView
        }

    /** Called on activity resume (or cold-start IO when direct battery exemption applies). */
    fun refreshBatteryOptimizationStatus() {
        initialRefreshScheduled.set(true)
        exemptState.value = isBatteryOptimizationExempt()
    }

    /**
     * User-initiated only (Settings › Notifications switch or "Allow running in background" row).
     * When this build declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, tries the direct exemption
     * prompt first, then system battery optimization settings, then App Info. Otherwise (Play build)
     * skips the direct prompt and opens system battery optimization settings -> App Info.
     */
    override fun requestBatteryOptimizationExemption() {
        RaftAndroidVendorWhitelist.openBatteryOptimization(
            context = appContext,
            allowDirectPrompt = canRequestBatteryOptimizationExemption(),
            launcher = intentLauncher,
        )
    }

    /**
     * User-initiated only (Settings › Notifications OEM/background settings row).
     * Tries OEM auto-start/background settings -> system battery optimization settings -> App Info.
     * Never invokes `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (safe on both direct and Play builds).
     */
    override fun openVendorBackgroundSettings() {
        RaftAndroidVendorWhitelist.open(
            context = appContext,
            vendor = vendorProvider(),
            launcher = intentLauncher,
        )
    }
}

internal fun isBatteryOptimizationPermissionDeclared(context: Context): Boolean =
    runCatching {
        val packageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
        packageInfo.requestedPermissions
            ?.contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) == true
    }.getOrDefault(false)

private fun readBatteryOptimizationExempt(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
