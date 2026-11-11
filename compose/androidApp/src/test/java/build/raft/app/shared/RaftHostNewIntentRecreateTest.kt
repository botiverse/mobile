package build.raft.app.shared

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hands f0be77a8 (HUAWEI CMU-AL10, Android 16, Multiple app windows): a server switch to a server
 * whose window already existed brought that stopped window forward with a new Intent, and the
 * host recreated itself inside onNewIntent. The page composed, but the window stayed white until
 * the app went to the background and back. The rebuild now waits for the resume that follows.
 */
class RaftHostNewIntentRecreateTest {
    @Test
    fun aNewIntentBelowResumedRebuildsOnTheNextResume() {
        assertFalse(raftHostRecreatesOnNewIntentNow(Lifecycle.State.CREATED))
        assertFalse(raftHostRecreatesOnNewIntentNow(Lifecycle.State.STARTED))
    }

    @Test
    fun aResumedHostRebuildsAtOnce() {
        assertTrue(raftHostRecreatesOnNewIntentNow(Lifecycle.State.RESUMED))
    }

    @Test
    fun firstFrameLineMeasuresFromTheSwitchTap() {
        assertEquals(
            "app_shell_new_window_first_frame ms=412 path=reused page=compose_slock_app_shell",
            raftNewWindowFirstFrameLine(
                tapEpochMillis = 1_000L,
                nowEpochMillis = 1_412L,
                path = RaftNewWindowPath.Reused,
                page = "compose_slock_app_shell",
            ),
        )
        assertEquals(
            "app_shell_new_window_first_frame ms=0 path=created page=compose_slock_app_shell",
            raftNewWindowFirstFrameLine(1_000L, 1_000L, RaftNewWindowPath.Created, "compose_slock_app_shell"),
        )
    }

    @Test
    fun noLineWithoutATapOrForAClockThatWentBack() {
        assertNull(raftNewWindowFirstFrameLine(0L, 5_000L, RaftNewWindowPath.Created, "p"))
        assertNull(raftNewWindowFirstFrameLine(5_000L, 4_000L, RaftNewWindowPath.Created, "p"))
    }

    @Test
    fun onlyTheLightHomeSplashKeepsTheCreamWindow() {
        val canvas = 0xFFFFFFFF.toInt()
        val cream = 0xFFFFFAEF.toInt()
        val dark = 0xFF1B1B19.toInt()
        assertEquals(cream, raftHostWindowBackgroundArgb(dark = false, homeSplash = true, canvasArgb = canvas, splashArgb = cream))
        // A server switch's window opened on its tab: the canvas, light or dark.
        assertEquals(canvas, raftHostWindowBackgroundArgb(dark = false, homeSplash = false, canvasArgb = canvas, splashArgb = cream))
        assertEquals(dark, raftHostWindowBackgroundArgb(dark = true, homeSplash = false, canvasArgb = dark, splashArgb = cream))
        assertEquals(dark, raftHostWindowBackgroundArgb(dark = true, homeSplash = true, canvasArgb = dark, splashArgb = cream))
    }
}
