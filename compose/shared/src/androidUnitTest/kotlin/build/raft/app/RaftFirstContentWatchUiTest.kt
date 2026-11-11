package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A page that still shows its placeholder a second after it opened writes one
 * `white_screen_suspect` with the stage it waits on; its first frame of real content writes one
 * `first_content`. A page that shows content in time writes no suspect line.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftFirstContentWatchUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun aSlowPageLogsTheSuspectStageThenItsFirstContent() = runRobolectricComposeUiTest {
        val lines = mutableListOf<Pair<Boolean, String>>()
        var stage by mutableStateOf<RaftWhiteScreenStage?>(RaftWhiteScreenStage.Session)
        mainClock.autoAdvance = false
        setContent { RaftFirstContentWatch(page = "app_shell", stage = stage, log = { warn, line -> lines += warn to line }) }
        mainClock.advanceTimeBy(RAFT_WHITE_SCREEN_SUSPECT_MS + 100)
        assertEquals(1, lines.size)
        assertTrue(lines[0].first)
        assertTrue(lines[0].second.startsWith("white_screen_suspect page=app_shell ms="), lines[0].second)
        assertTrue(lines[0].second.endsWith(" stage=session scope=page"), lines[0].second)
        stage = null
        // A write from outside composition: let it recompose, then run the next frame.
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeBy(100)
        assertEquals(2, lines.size)
        assertTrue(!lines[1].first && lines[1].second.startsWith("first_content page=app_shell ms="), lines[1].second)
    }

    @Test
    fun aPageWithContentInTimeLogsNoSuspect() = runRobolectricComposeUiTest {
        val lines = mutableListOf<Pair<Boolean, String>>()
        mainClock.autoAdvance = false
        setContent { RaftFirstContentWatch(page = "webview:settings", stage = null, log = { warn, line -> lines += warn to line }) }
        mainClock.advanceTimeBy(RAFT_WHITE_SCREEN_SUSPECT_MS * 2)
        assertEquals(listOf(false), lines.map { it.first })
        assertTrue(lines[0].second.startsWith("first_content page=webview:settings ms="), lines[0].second)
    }
}
