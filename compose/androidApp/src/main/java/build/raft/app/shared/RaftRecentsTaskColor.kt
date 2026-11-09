package build.raft.app.shared

import android.app.Activity
import android.app.ActivityManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import build.raft.app.core.data.diagnostics.RaftAndroidLocalDiagnostics
import build.raft.app.data.RaftAppAppearanceRuntime
import java.util.Locale

/**
 * The recents (overview) card of the app's task. When the system has no usable snapshot of the
 * task, the launcher fills the card with the task description's background colour, which otherwise
 * came from the platform theme's `android:colorBackground` (a Material default), not the
 * app's canvas: Raft drawn dark showed a white card (HUAWEI CMU-AL10, Android 16, 1.22.0). The
 * host gives the task the canvas it paints, whenever that canvas changes.
 */
internal object RaftRecentsTaskColor {
    const val LOG_TAG = "SlockRecentsTask"

    /**
     * Sets the task's background colour to [canvasArgb] (opaque). API 33+ only: older versions
     * have no public way to set it, and their placeholder falls back to the theme's
     * `android:colorBackground`, which Theme.Raft sets to the canvas colour. The label and icon stay
     * unset (the activity's own), and the theme's primary colour is kept.
     * @return true when the task description was updated.
     */
    fun apply(activity: Activity, canvasArgb: Int, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val builder = ActivityManager.TaskDescription.Builder().setBackgroundColor(opaque(canvasArgb))
            themePrimaryColor(activity)?.let(builder::setPrimaryColor)
            activity.setTaskDescription(builder.build())
        }.isSuccess
    }

    /**
     * Host onStop: records [stopLine] with the resolved app mode, [activity]'s configuration night
     * bits and the canvas last painted ([canvasArgb]) / given to the task ([taskColorArgb]).
     */
    fun recordStop(activity: Activity, page: String, canvasArgb: Int?, taskColorArgb: Int?) {
        recordDiagnostics(
            stopLine(
                page = page,
                appearance = RaftAppAppearanceRuntime.appearance.name,
                appDark = RaftAppAppearanceRuntime.isDark,
                uiMode = activity.resources.configuration.uiMode,
                canvasArgb = canvasArgb,
                taskColorApplied = taskColorArgb != null && taskColorArgb == canvasArgb,
            )
        )
    }

    /** One diagnostics line at onStop: the state the recents card is captured in. */
    fun stopLine(
        page: String,
        appearance: String,
        appDark: Boolean,
        uiMode: Int,
        canvasArgb: Int?,
        taskColorApplied: Boolean,
    ): String {
        val nightBits =
            when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> "yes"
                Configuration.UI_MODE_NIGHT_NO -> "no"
                else -> "undefined"
            }
        val canvas = canvasArgb?.let { String.format(Locale.US, "#%08X", it) } ?: "none"
        return "recents_task_on_stop page=$page appearance=$appearance appDark=$appDark " +
            "configNight=$nightBits canvas=$canvas taskColorApplied=$taskColorApplied"
    }

    /** Into the diagnostics ring (the feedback zip), whatever the verbose setting. */
    var recordDiagnostics: (String) -> Unit = { message ->
        RaftAndroidLocalDiagnostics.recordLog("info", LOG_TAG, message)
    }

    private fun opaque(argb: Int): Int = argb or (0xFF shl 24)

    private fun themePrimaryColor(activity: Activity): Int? {
        val array = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorPrimary))
        return try {
            array.getColor(0, 0).takeIf { it != 0 && Color.alpha(it) == 0xFF }
        } finally {
            array.recycle()
        }
    }
}
