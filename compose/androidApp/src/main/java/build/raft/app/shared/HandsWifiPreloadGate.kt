package build.raft.app.shared

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import build.raft.app.data.SharedSettingsUpdateTransactionState

/** Minimum battery percentage required for silent Wi-Fi pre-download when unplugged. */
internal const val HANDS_WIFI_PRELOAD_MIN_BATTERY_PERCENT = 15

/**
 * Pure input snapshot evaluated by [decideHandsWifiPreload] before enqueueing an automatic
 * background Hands APK pre-download.
 */
internal data class HandsWifiPreloadContext(
    val apkUpdateEnabled: Boolean,
    val automaticAllowed: Boolean,
    val unmeteredWifi: Boolean,
    val batteryNotLow: Boolean,
    val updateAvailable: Boolean,
    val currentVersionCode: Long,
    val latestVersionCode: Long,
    val transactionState: SharedSettingsUpdateTransactionState,
    val alreadyAttemptedVersionCode: Long,
)

internal enum class HandsWifiPreloadDecision(val diagnosticReason: String) {
    Preload("preload"),
    SkipDisabled("disabled"),
    SkipPrivacyRestricted("privacy_restricted"),
    SkipMeteredOrNotWifi("metered_or_not_wifi"),
    SkipBatteryConstrained("battery_constrained"),
    SkipNotAvailable("not_available"),
    SkipAlreadyActiveOrReady("already_active_or_ready"),
    SkipAlreadyAttempted("already_attempted"),
}

internal fun decideHandsWifiPreload(context: HandsWifiPreloadContext): HandsWifiPreloadDecision {
    if (!context.apkUpdateEnabled) return HandsWifiPreloadDecision.SkipDisabled
    if (!context.automaticAllowed) return HandsWifiPreloadDecision.SkipPrivacyRestricted
    if (!context.unmeteredWifi) return HandsWifiPreloadDecision.SkipMeteredOrNotWifi
    if (!context.batteryNotLow) return HandsWifiPreloadDecision.SkipBatteryConstrained
    if (
        !context.updateAvailable ||
        context.latestVersionCode <= 0L ||
        (context.currentVersionCode > 0L && context.latestVersionCode <= context.currentVersionCode)
    ) {
        return HandsWifiPreloadDecision.SkipNotAvailable
    }
    if (context.transactionState.blocksSilentWifiPreload()) {
        return HandsWifiPreloadDecision.SkipAlreadyActiveOrReady
    }
    if (context.alreadyAttemptedVersionCode > 0L && context.alreadyAttemptedVersionCode == context.latestVersionCode) {
        return HandsWifiPreloadDecision.SkipAlreadyAttempted
    }
    return HandsWifiPreloadDecision.Preload
}

internal fun isHandsWifiPreloadBatteryNotLow(
    batteryPercent: Int?,
    isCharging: Boolean,
    isPowerSaveMode: Boolean,
): Boolean {
    if (isPowerSaveMode) return false
    if (isCharging) return true
    return batteryPercent != null && batteryPercent > HANDS_WIFI_PRELOAD_MIN_BATTERY_PERCENT
}

/**
 * Reads device network and power eligibility for silent Hands Wi-Fi pre-download:
 * `Pair(unmeteredWifi, batteryNotLow)`. Fails closed to `false` on any missing service or
 * [SecurityException].
 */
internal fun readHandsWifiPreloadDeviceConditions(context: Context): Pair<Boolean, Boolean> {
    val appContext = context.applicationContext
    val unmeteredWifi =
        runCatching {
            val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
                ?: return@runCatching false
            val activeNetwork = connectivityManager.activeNetwork ?: return@runCatching false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                ?: return@runCatching false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.getOrDefault(false)

    val batteryNotLow =
        runCatching {
            val powerManager = appContext.getSystemService(PowerManager::class.java)
                ?: return@runCatching false
            if (powerManager.isPowerSaveMode) return@runCatching false
            val batteryManager = appContext.getSystemService(BatteryManager::class.java)
            val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging =
                batteryManager?.isCharging == true ||
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val intentPercent = if (level >= 0 && scale > 0) (level * 100) / scale else null
            val managerPercent =
                batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    ?.takeIf { it in 0..100 }
            isHandsWifiPreloadBatteryNotLow(
                batteryPercent = intentPercent ?: managerPercent,
                isCharging = isCharging,
                isPowerSaveMode = false,
            )
        }.getOrDefault(false)

    return unmeteredWifi to batteryNotLow
}

private fun SharedSettingsUpdateTransactionState.blocksSilentWifiPreload(): Boolean =
    when (this) {
        SharedSettingsUpdateTransactionState.Checking,
        SharedSettingsUpdateTransactionState.Patching,
        SharedSettingsUpdateTransactionState.Downloading,
        SharedSettingsUpdateTransactionState.ReadyToInstall,
        SharedSettingsUpdateTransactionState.InstallerOpened,
        SharedSettingsUpdateTransactionState.ReadyToRestart,
        SharedSettingsUpdateTransactionState.Installed -> true
        SharedSettingsUpdateTransactionState.Unknown,
        SharedSettingsUpdateTransactionState.Idle,
        SharedSettingsUpdateTransactionState.Failed,
        SharedSettingsUpdateTransactionState.Stale -> false
    }
