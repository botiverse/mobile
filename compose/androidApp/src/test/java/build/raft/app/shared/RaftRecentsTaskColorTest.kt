package build.raft.app.shared

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.res.Configuration
import android.os.Build
import build.raft.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The recents card shows a white placeholder when the system has no snapshot of the task: its
 * colour is the task description's background, which came from the platform theme rather than the
 * app's canvas (HUAWEI, Android 16, 1.22.0: a white card for an app drawn dark). The host gives the
 * task its canvas, the window themes' colorBackground is the canvas, and onStop records the state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftRecentsTaskColorTest {
    private val defaultRecorder = RaftRecentsTaskColor.recordDiagnostics

    @After
    fun restoreRecorder() {
        RaftRecentsTaskColor.recordDiagnostics = defaultRecorder
    }

    @Test
    @Config(qualifiers = "notnight")
    fun taskBackgroundFollowsTheLightCanvas() {
        val activity = themedActivity()
        assertTrue(RaftRecentsTaskColor.apply(activity, LIGHT_CANVAS))
        val description = taskDescription(activity)
        assertEquals(LIGHT_CANVAS, description.backgroundColor)
        // Label and icon stay the activity's own.
        assertNull(description.label)
        assertNull(description.icon)
    }

    @Test
    @Config(qualifiers = "night")
    fun taskBackgroundFollowsTheDarkCanvasAndIsOpaque() {
        val activity = themedActivity()
        assertTrue(RaftRecentsTaskColor.apply(activity, DARK_CANVAS))
        assertEquals(DARK_CANVAS, taskDescription(activity).backgroundColor)
        // A translucent canvas still gives an opaque card.
        assertTrue(RaftRecentsTaskColor.apply(activity, DARK_CANVAS and 0x80FFFFFF.toInt()))
        assertEquals(DARK_CANVAS, taskDescription(activity).backgroundColor)
    }

    @Test
    @Config(qualifiers = "night")
    fun taskBackgroundFollowsAServerThemeCanvasNotJustTheTheme() {
        val activity = themedActivity()
        assertEquals(DARK_CANVAS, taskDescription(activity).backgroundColor)
        assertTrue(RaftRecentsTaskColor.apply(activity, SERVER_CANVAS))
        assertEquals(SERVER_CANVAS, taskDescription(activity).backgroundColor)
    }

    @Test
    fun belowApi33TheTaskDescriptionIsLeftAlone() {
        val activity = themedActivity()
        val before = taskDescription(activity).backgroundColor
        assertFalse(RaftRecentsTaskColor.apply(activity, SERVER_CANVAS, sdkInt = Build.VERSION_CODES.S_V2))
        assertEquals(before, taskDescription(activity).backgroundColor)
    }

    @Test
    @Config(sdk = [29, 34], qualifiers = "notnight")
    fun lightWindowThemeBackgroundIsTheCanvas() {
        assertEquals(LIGHT_CANVAS, colorBackground(R.style.Theme_Raft))
    }

    @Test
    @Config(sdk = [29, 34], qualifiers = "night")
    fun darkWindowThemeBackgroundIsTheCanvas() {
        assertEquals(DARK_CANVAS, colorBackground(R.style.Theme_Raft))
    }

    @Test
    @Config(qualifiers = "night")
    fun stopRecordsModeNightBitsAndCanvas() {
        val lines = mutableListOf<String>()
        RaftRecentsTaskColor.recordDiagnostics = { lines += it }
        val activity = themedActivity()
        RaftRecentsTaskColor.recordStop(activity, "compose_slock_app_shell", DARK_CANVAS, DARK_CANVAS)
        val line = lines.single()
        assertTrue(line, line.startsWith("recents_task_on_stop page=compose_slock_app_shell appearance="))
        assertTrue(line, line.contains(" appDark="))
        assertTrue(line, line.contains(" configNight=yes "))
        assertTrue(line, line.contains(" canvas=#FF1B1B19 "))
        assertTrue(line, line.endsWith(" taskColorApplied=true"))
    }

    @Test
    fun stopLineReportsAMissingOrStaleTaskColour() {
        val light = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO
        assertEquals(
            "recents_task_on_stop page=p appearance=Dark appDark=true configNight=no canvas=none " +
                "taskColorApplied=false",
            RaftRecentsTaskColor.stopLine("p", "Dark", appDark = true, uiMode = light, canvasArgb = null, taskColorApplied = false),
        )
    }

    private fun themedActivity(): Activity {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.Theme_Raft)
        return controller.create().get()
    }

    private fun taskDescription(activity: Activity): ActivityManager.TaskDescription =
        ReflectionHelpers.getField(activity, "mTaskDescription")

    private fun colorBackground(style: Int): Int {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val theme = context.resources.newTheme().apply { applyStyle(style, true) }
        val array = theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        try {
            return array.getColor(0, 0)
        } finally {
            array.recycle()
        }
    }

    private companion object {
        const val LIGHT_CANVAS = 0xFFFFFAEF.toInt()
        const val DARK_CANVAS = 0xFF1B1B19.toInt()
        const val SERVER_CANVAS = 0xFF203040.toInt()
    }
}
