package build.raft.app.shared

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.RaftBackgroundWhitelistGuide
import java.util.Locale

internal enum class AndroidOemVendor(val id: String) {
    Huawei("huawei"),
    Honor("honor"),
    LenovoZui("lenovo_zui"),
    Xiaomi("xiaomi"),
    Oppo("oppo"),
    Vivo("vivo"),
    Samsung("samsung"),
    Zte("zte"),
    Generic("generic"),
}

internal data class VendorWhitelistTarget(
    val id: String,
    val action: String? = null,
    val componentPackage: String? = null,
    val componentClass: String? = null,
    val packageUri: Boolean = false,
    val extras: Map<String, String> = emptyMap(),
) {
    fun toIntent(packageName: String, appLabel: String = "Raft"): Intent {
        val target = this
        return Intent().apply {
            if (target.action != null) setAction(target.action)
            if (target.componentPackage != null && target.componentClass != null) {
                component = ComponentName(target.componentPackage, target.componentClass)
            }
            if (target.packageUri) {
                data = Uri.parse("package:$packageName")
            }
            target.extras.forEach { (key, value) ->
                val resolved =
                    when (value) {
                        PACKAGE_NAME_PLACEHOLDER -> packageName
                        APP_LABEL_PLACEHOLDER -> appLabel
                        else -> value
                    }
                putExtra(key, resolved)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        const val PACKAGE_NAME_PLACEHOLDER = "\$PACKAGE_NAME"
        const val APP_LABEL_PLACEHOLDER = "\$APP_LABEL"
    }
}

/**
 * Unified Android background keep-alive & OEM whitelist settings launcher (#proj-mobile:#220, #325).
 *
 * All Android background-settings entry points (Settings › Notifications battery-optimization row
 * and OEM auto-start/background management row) route through this single target table and
 * [launchFirstAvailable] fallback chain:
 * - [batteryOptimizationTargets] (`allowDirectPrompt = true`, direct/non-Play builds only):
 *   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` -> `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
 *   -> `ACTION_APPLICATION_DETAILS_SETTINGS`.
 * - [batteryOptimizationTargets] (`allowDirectPrompt = false`, Google Play builds):
 *   `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` -> `ACTION_APPLICATION_DETAILS_SETTINGS`
 *   (never invokes `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
 * - [targetsFor] (OEM auto-start / background whitelist, safe on both direct and Play builds):
 *   vendor-specific OEM intents -> `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` ->
 *   `ACTION_APPLICATION_DETAILS_SETTINGS`.
 */
internal object RaftAndroidVendorWhitelist {
    private const val LOG_TAG = "RaftVendorWhitelist"

    internal const val TARGET_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = "request_ignore_battery_optimizations"
    internal const val TARGET_BATTERY_OPTIMIZATION_SETTINGS = "battery_optimization_settings"
    internal const val TARGET_APP_DETAILS = "app_details"

    private val requestBatteryOptimizationTarget =
        VendorWhitelistTarget(
            id = TARGET_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            packageUri = true,
        )

    private val batteryOptimizationSettingsFallback =
        VendorWhitelistTarget(
            id = TARGET_BATTERY_OPTIMIZATION_SETTINGS,
            action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        )

    private val appDetailsFallback =
        VendorWhitelistTarget(
            id = TARGET_APP_DETAILS,
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            packageUri = true,
        )

    private val huaweiTargets =
        listOf(
            VendorWhitelistTarget(
                id = "huawei_startup_normal",
                componentPackage = "com.huawei.systemmanager",
                componentClass = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            VendorWhitelistTarget(
                id = "huawei_app_startup_action",
                action = "huawei.intent.action.APP_STARTUP_SETTINGS",
            ),
            VendorWhitelistTarget(
                id = "huawei_bootapp_action",
                action = "huawei.intent.action.HSM_BOOTAPP_MANAGER",
            ),
            VendorWhitelistTarget(
                id = "huawei_startup_control",
                componentPackage = "com.huawei.systemmanager",
                componentClass = "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            ),
            VendorWhitelistTarget(
                id = "huawei_protect",
                componentPackage = "com.huawei.systemmanager",
                componentClass = "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        )

    private val honorTargets =
        listOf(
            VendorWhitelistTarget(
                id = "honor_startup_normal",
                componentPackage = "com.hihonor.systemmanager",
                componentClass = "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            VendorWhitelistTarget(
                id = "honor_startup_control",
                componentPackage = "com.hihonor.systemmanager",
                componentClass = "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity",
            ),
            VendorWhitelistTarget(
                id = "honor_protect",
                componentPackage = "com.hihonor.systemmanager",
                componentClass = "com.hihonor.systemmanager.optimize.process.ProtectActivity",
            ),
        ) + huaweiTargets

    private val lenovoZuiTargets =
        listOf(
            VendorWhitelistTarget(
                id = "zui_startup_list",
                componentPackage = "com.zui.safecenter",
                componentClass = "com.zui.safecenter.startup.StartupAppListActivity",
            ),
            VendorWhitelistTarget(
                id = "zui_autorun",
                componentPackage = "com.zui.safecenter",
                componentClass = "com.zui.safecenter.autorun.AutoRunActivity",
            ),
            VendorWhitelistTarget(
                id = "zui_safecenter_main_tab",
                componentPackage = "com.zui.safecenter",
                componentClass = "com.zui.safecenter.MainTabActivity",
            ),
            VendorWhitelistTarget(
                id = "zui_safecenter_main",
                componentPackage = "com.zui.safecenter",
                componentClass = "com.zui.safecenter.MainActivity",
            ),
            VendorWhitelistTarget(
                id = "lenovo_pure_background",
                componentPackage = "com.lenovo.security",
                componentClass = "com.lenovo.security.purebackground.PureBackgroundActivity",
            ),
            VendorWhitelistTarget(
                id = "lenovo_performance_switches",
                componentPackage = "com.lenovo.performancecenter",
                componentClass = "com.lenovo.performancecenter.core.ui.switches.SwitchesActivity",
            ),
        )

    private val xiaomiTargets =
        listOf(
            VendorWhitelistTarget(
                id = "xiaomi_autostart",
                componentPackage = "com.miui.securitycenter",
                componentClass = "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            VendorWhitelistTarget(
                id = "xiaomi_powerkeeper_app",
                componentPackage = "com.miui.powerkeeper",
                componentClass = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                extras =
                    mapOf(
                        "package_name" to VendorWhitelistTarget.PACKAGE_NAME_PLACEHOLDER,
                        "package_label" to VendorWhitelistTarget.APP_LABEL_PLACEHOLDER,
                    ),
            ),
            VendorWhitelistTarget(
                id = "xiaomi_powerkeeper_list",
                componentPackage = "com.miui.powerkeeper",
                componentClass = "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity",
            ),
        )

    private val oppoTargets =
        listOf(
            VendorWhitelistTarget(
                id = "coloros_startup",
                componentPackage = "com.coloros.safecenter",
                componentClass = "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            VendorWhitelistTarget(
                id = "coloros_startup_legacy",
                componentPackage = "com.coloros.safecenter",
                componentClass = "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            VendorWhitelistTarget(
                id = "oplus_battery",
                componentPackage = "com.oplus.battery",
                componentClass = "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
            ),
            VendorWhitelistTarget(
                id = "oneplus_chainlaunch",
                componentPackage = "com.oneplus.security",
                componentClass = "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            ),
            VendorWhitelistTarget(
                id = "oppo_safe_startup",
                componentPackage = "com.oppo.safe",
                componentClass = "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        )

    private val vivoTargets =
        listOf(
            VendorWhitelistTarget(
                id = "iqoo_whitelist",
                componentPackage = "com.iqoo.secure",
                componentClass = "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            ),
            VendorWhitelistTarget(
                id = "iqoo_bg_startup",
                componentPackage = "com.iqoo.secure",
                componentClass = "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
            VendorWhitelistTarget(
                id = "vivo_bg_startup",
                componentPackage = "com.vivo.permissionmanager",
                componentClass = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            VendorWhitelistTarget(
                id = "vivo_power_manager",
                componentPackage = "com.vivo.abe",
                componentClass = "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity",
            ),
        )

    private val samsungTargets =
        listOf(
            VendorWhitelistTarget(
                id = "samsung_device_care_battery",
                componentPackage = "com.samsung.android.lool",
                componentClass = "com.samsung.android.sm.battery.ui.BatteryActivity",
            ),
            VendorWhitelistTarget(
                id = "samsung_sm_cn_battery",
                componentPackage = "com.samsung.android.sm_cn",
                componentClass = "com.samsung.android.sm.battery.ui.BatteryActivity",
            ),
            VendorWhitelistTarget(
                id = "samsung_lool_battery_legacy",
                componentPackage = "com.samsung.android.lool",
                componentClass = "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
            VendorWhitelistTarget(
                id = "samsung_sm_battery",
                componentPackage = "com.samsung.android.sm",
                componentClass = "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
        )

    private val zteTargets =
        listOf(
            VendorWhitelistTarget(
                id = "zte_autorun",
                componentPackage = "com.zte.heartyservice",
                componentClass = "com.zte.heartyservice.autorun.AppAutoRunManager",
            ),
            VendorWhitelistTarget(
                id = "zte_clear_app",
                componentPackage = "com.zte.heartyservice",
                componentClass = "com.zte.heartyservice.setting.ClearAppSettingsActivity",
            ),
            VendorWhitelistTarget(
                id = "zte_power_save",
                componentPackage = "com.zte.powersavemode",
                componentClass = "com.zte.powersavemode.PowerSaveModeActivity",
            ),
        )

    fun resolveVendor(
        manufacturer: String? = Build.MANUFACTURER,
        brand: String? = Build.BRAND,
    ): AndroidOemVendor {
        val m = manufacturer.orEmpty().trim().lowercase(Locale.US)
        val b = brand.orEmpty().trim().lowercase(Locale.US)
        fun matches(vararg tokens: String): Boolean = tokens.any { token -> token in b || token in m }
        return when {
            // Check Honor before Huawei so legacy Honor devices (manufacturer=HUAWEI, brand=HONOR)
            // try com.hihonor.systemmanager first and still fall back to com.huawei.systemmanager.
            matches("honor", "hihonor") -> AndroidOemVendor.Honor
            matches("huawei") -> AndroidOemVendor.Huawei
            matches("lenovo", "zuk") -> AndroidOemVendor.LenovoZui
            matches("xiaomi", "redmi", "poco") -> AndroidOemVendor.Xiaomi
            matches("oppo", "realme", "oneplus") -> AndroidOemVendor.Oppo
            matches("vivo", "iqoo") -> AndroidOemVendor.Vivo
            matches("samsung") -> AndroidOemVendor.Samsung
            matches("zte", "nubia") -> AndroidOemVendor.Zte
            else -> AndroidOemVendor.Generic
        }
    }

    fun guideFor(vendor: AndroidOemVendor): RaftBackgroundWhitelistGuide =
        when (vendor) {
            AndroidOemVendor.Huawei, AndroidOemVendor.Honor -> RaftBackgroundWhitelistGuide.HuaweiAppLaunch
            AndroidOemVendor.LenovoZui -> RaftBackgroundWhitelistGuide.LenovoZui
            AndroidOemVendor.Xiaomi,
            AndroidOemVendor.Oppo,
            AndroidOemVendor.Vivo,
            AndroidOemVendor.Samsung,
            AndroidOemVendor.Zte -> RaftBackgroundWhitelistGuide.VendorSettings
            AndroidOemVendor.Generic -> RaftBackgroundWhitelistGuide.AppDetails
        }

    fun batteryOptimizationTargets(allowDirectPrompt: Boolean): List<VendorWhitelistTarget> =
        if (allowDirectPrompt) {
            listOf(
                requestBatteryOptimizationTarget,
                batteryOptimizationSettingsFallback,
                appDetailsFallback,
            )
        } else {
            listOf(
                batteryOptimizationSettingsFallback,
                appDetailsFallback,
            )
        }

    fun targetsFor(vendor: AndroidOemVendor): List<VendorWhitelistTarget> {
        val vendorSpecific =
            when (vendor) {
                AndroidOemVendor.Huawei -> huaweiTargets
                AndroidOemVendor.Honor -> honorTargets
                AndroidOemVendor.LenovoZui -> lenovoZuiTargets
                AndroidOemVendor.Xiaomi -> xiaomiTargets
                AndroidOemVendor.Oppo -> oppoTargets
                AndroidOemVendor.Vivo -> vivoTargets
                AndroidOemVendor.Samsung -> samsungTargets
                AndroidOemVendor.Zte -> zteTargets
                AndroidOemVendor.Generic -> emptyList()
            }
        return vendorSpecific + batteryOptimizationSettingsFallback + appDetailsFallback
    }

    fun targetsFor(
        manufacturer: String? = Build.MANUFACTURER,
        brand: String? = Build.BRAND,
    ): List<VendorWhitelistTarget> = targetsFor(resolveVendor(manufacturer, brand))

    /**
     * Tries each target directly without `resolveActivity()` (Android 11+ `<queries>` filters OEM
     * packages), falling through on any [RuntimeException] (including `ActivityNotFoundException`,
     * `SecurityException`, `IllegalArgumentException`, `IllegalStateException`, and OEM
     * `AndroidRuntimeException`) so the system battery optimization settings and `app_details`
     * fallbacks are always reached.
     */
    internal fun launchFirstAvailable(
        targets: List<VendorWhitelistTarget>,
        onFailure: (VendorWhitelistTarget, RuntimeException) -> Unit = { _, _ -> },
        launcher: (VendorWhitelistTarget) -> Unit,
    ): String? {
        for (target in targets) {
            val launched =
                try {
                    launcher(target)
                    true
                } catch (error: RuntimeException) {
                    onFailure(target, error)
                    false
                }
            if (launched) return target.id
        }
        return null
    }

    fun openBatteryOptimization(
        context: Context,
        allowDirectPrompt: Boolean,
        launcher: (Intent) -> Unit = { intent -> context.applicationContext.startActivity(intent) },
    ): String? {
        val appContext = context.applicationContext
        val targets = batteryOptimizationTargets(allowDirectPrompt = allowDirectPrompt)
        val launched =
            launchFirstAvailable(
                targets = targets,
                onFailure = { target, error ->
                    RaftAppLog.d(
                        LOG_TAG,
                        "battery_optimization_target_failed target=${target.id} allowDirect=$allowDirectPrompt class=${error.javaClass.simpleName}",
                    )
                },
            ) { target ->
                launcher(target.toIntent(appContext.packageName))
            }
        if (launched != null) {
            RaftAppLog.i(LOG_TAG, "battery_optimization_opened target=$launched allowDirect=$allowDirectPrompt")
        } else {
            RaftAppLog.w(LOG_TAG, "battery_optimization_unavailable allowDirect=$allowDirectPrompt")
        }
        return launched
    }

    fun open(
        context: Context,
        vendor: AndroidOemVendor,
        launcher: (Intent) -> Unit = { intent -> context.applicationContext.startActivity(intent) },
    ): String? {
        val appContext = context.applicationContext
        val targets = targetsFor(vendor)
        val appLabel =
            runCatching { appContext.applicationInfo.loadLabel(appContext.packageManager).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "Raft"
        val launched =
            launchFirstAvailable(
                targets = targets,
                onFailure = { target, error ->
                    RaftAppLog.d(
                        LOG_TAG,
                        "vendor_whitelist_target_failed vendor=${vendor.id} target=${target.id} class=${error.javaClass.simpleName}",
                    )
                },
            ) { target ->
                launcher(target.toIntent(appContext.packageName, appLabel))
            }
        if (launched != null) {
            RaftAppLog.i(LOG_TAG, "vendor_whitelist_opened vendor=${vendor.id} target=$launched")
        } else {
            RaftAppLog.w(LOG_TAG, "vendor_whitelist_unavailable vendor=${vendor.id}")
        }
        return launched
    }

    fun open(
        context: Context,
        manufacturer: String? = Build.MANUFACTURER,
        brand: String? = Build.BRAND,
        launcher: (Intent) -> Unit = { intent -> context.applicationContext.startActivity(intent) },
    ): String? = open(context = context, vendor = resolveVendor(manufacturer, brand), launcher = launcher)
}
