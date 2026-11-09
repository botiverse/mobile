package build.raft.app.shared

import android.app.Activity
import android.app.Application
import android.app.UiModeManager
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import build.raft.app.RaftAppAppearance
import build.raft.app.data.RaftAppAppearanceRuntime
import java.util.WeakHashMap

/**
 * The window theme follows the app's own light/dark choice (Settings > Appearance: Light, Dark or
 * System), not just the OS. Theme.Raft is a day/night theme (values/ and values-night/
 * Base.Theme.Raft), so putting the choice into the configuration's night bits makes everything the
 * platform draws from the theme match the app: WebView `prefers-color-scheme`
 * (`android:isLightTheme`), AlertDialogs, pickers, the window background and the first frame's bar
 * icons. One source of truth: [RaftAppAppearanceRuntime]; System follows the device.
 *
 * - **API 31+**: [UiModeManager.setApplicationNightMode] (`MODE_NIGHT_NO` / `YES`, `AUTO` for
 *   System, which removes the override). The system persists it per package and applies it to the
 *   whole process (Activities, the application context, the system splash of the next cold
 *   start). Hosts handle uiMode in place (manifest), so a switch arrives as onConfigurationChanged
 *   a moment later; Activities that do not handle it are recreated by the system.
 * - **API 29-30** (no per-app night mode): each Activity's resources get the night bits of the
 *   choice before onCreate ([Application.ActivityLifecycleCallbacks.onActivityPreCreated]) and the
 *   theme is rebased, so the window, its theme and every view inflated from it start in the app's
 *   mode. A switch in Settings updates every live Activity in place (no recreation, so the open
 *   tab, pushed pages and scroll positions stay): resources, theme rebase, the view tree's
 *   onConfigurationChanged and the Activity's own. An Activity that does not handle uiMode is
 *   recreated, as the system would. A system configuration change can rebuild the resources from
 *   the OS setting; the night bits are put back in onConfigurationChanged (before the view tree
 *   reads them) and when an Activity starts. The application context keeps the OS mode on these
 *   versions, and the system's own starting window (before the process runs) cannot follow the
 *   app choice.
 *
 * The Activity configuration therefore carries the resolved app mode: the hosts' "system dark"
 * report ([RaftAppAppearanceRuntime.onSystemDarkChanged]) reads it, which under System is the OS
 * setting and under Light/Dark only agrees with the choice.
 */
internal object RaftAndroidNightMode {
    private const val LOG_TAG = "RaftNightMode"

    private var installed = false

    /** API 29-30: the live Activities, updated in place on a switch. Main thread. */
    private val activities = WeakHashMap<Activity, Unit>()

    private val handlesUiModeByClass = HashMap<Class<*>, Boolean>()

    /** Main thread, after the stored choice is loaded ([RaftAppAppearanceRuntime.ensureLoaded]). */
    fun install(application: Application) {
        if (installed) return
        installed = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            application.registerActivityLifecycleCallbacks(LegacyActivityCallbacks)
        }
        // Called now with the stored choice and after every change (Settings > Appearance).
        RaftAppAppearanceRuntime.setHostListener { onAppearanceChanged(application) }
    }

    private fun onAppearanceChanged(application: Application) {
        val appearance = RaftAppAppearanceRuntime.appearance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyApplicationNightMode(application, appearance)
        } else {
            activities.keys.toList().forEach { activity -> reconcile(activity, notify = true) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyApplicationNightMode(application: Application, appearance: RaftAppAppearance) {
        runCatching {
            application.getSystemService(UiModeManager::class.java)
                ?.setApplicationNightMode(raftApplicationNightMode(appearance))
        }.onFailure { error ->
            Log.w(LOG_TAG, "set_application_night_mode failed errorType=${error::class.simpleName}")
        }
    }

    /**
     * API 29-30: gives [activity]'s resources the night bits of the app choice and rebases its
     * theme. With [notify], an Activity already showing content is told: its view tree and its
     * onConfigurationChanged (or a recreation when it does not handle uiMode). Idempotent.
     */
    private fun reconcile(activity: Activity, notify: Boolean) {
        if (activity.isDestroyed) return
        val resources = activity.resources
        val current = resources.configuration
        val nightBits = raftNightBits(RaftAppAppearanceRuntime.appearance, Resources.getSystem().configuration.uiMode)
        val uiMode = raftUiModeWithNight(current.uiMode, nightBits)
        if (uiMode == current.uiMode) return
        if (notify && !handlesUiMode(activity)) {
            // A recreated Activity gets the night bits in onActivityPreCreated.
            activity.recreate()
            return
        }
        val config = Configuration(current).apply { this.uiMode = uiMode }
        // Deprecated, but the only in-place path on API 29-30 (AppCompat's night mode does the
        // same); these versions no longer change.
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        activity.theme.rebase()
        if (notify) {
            // Compose's LocalConfiguration (isSystemInDarkTheme) and WebViews follow the views'
            // onConfigurationChanged; the host repaints its window in its own.
            activity.window?.peekDecorView()?.dispatchConfigurationChanged(Configuration(config))
            activity.onConfigurationChanged(Configuration(config))
        }
        Log.i(LOG_TAG, "activity_night_mode_applied night=${isAndroidSystemDark(uiMode)} notify=$notify")
    }

    private fun handlesUiMode(activity: Activity): Boolean =
        handlesUiModeByClass.getOrPut(activity.javaClass) {
            // By class, not componentName: an activity-alias (pre-rename Slock* names) is
            // resolved to the declared Activity, whose configChanges count.
            runCatching {
                val info = activity.packageManager.getActivityInfo(ComponentName(activity, activity.javaClass), 0)
                (info.configChanges and ActivityInfo.CONFIG_UI_MODE) != 0
            }.getOrDefault(true)
        }

    private object LegacyActivityCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            activities[activity] = Unit
            reconcile(activity, notify = false)
            // Runs inside ComponentActivity.onConfigurationChanged, before the host's own code
            // and before the view tree reads the resources' configuration.
            (activity as? ComponentActivity)?.addOnConfigurationChangedListener {
                reconcile(activity, notify = false)
            }
        }

        override fun onActivityStarted(activity: Activity) {
            reconcile(activity, notify = true)
        }

        override fun onActivityDestroyed(activity: Activity) {
            activities.remove(activity)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}

/**
 * The [Configuration.UI_MODE_NIGHT_MASK] bits for [appearance]: Light and Dark pin them, System
 * takes the OS setting's from [systemUiMode] (undefined counts as light).
 */
internal fun raftNightBits(appearance: RaftAppAppearance, systemUiMode: Int): Int =
    when (appearance) {
        RaftAppAppearance.Light -> Configuration.UI_MODE_NIGHT_NO
        RaftAppAppearance.Dark -> Configuration.UI_MODE_NIGHT_YES
        RaftAppAppearance.System ->
            if (isAndroidSystemDark(systemUiMode)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }

/** [uiMode] with its night bits replaced by [nightBits]; the UI mode type stays. */
internal fun raftUiModeWithNight(uiMode: Int, nightBits: Int): Int =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (nightBits and Configuration.UI_MODE_NIGHT_MASK)

/** API 31+ [UiModeManager.setApplicationNightMode] value: `AUTO` (no override) follows the device. */
internal fun raftApplicationNightMode(appearance: RaftAppAppearance): Int =
    when (appearance) {
        RaftAppAppearance.Light -> UiModeManager.MODE_NIGHT_NO
        RaftAppAppearance.Dark -> UiModeManager.MODE_NIGHT_YES
        RaftAppAppearance.System -> UiModeManager.MODE_NIGHT_AUTO
    }
