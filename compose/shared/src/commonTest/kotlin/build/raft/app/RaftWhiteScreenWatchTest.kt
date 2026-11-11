package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * White-screen diagnostics (Hands f0be77a8): the stage a surface without real content is stuck at,
 * most fundamental first, and the lines that reach the feedback zip.
 */
class RaftWhiteScreenWatchTest {
    @Test
    fun aHiddenWindowExplainsEverythingAfterIt() {
        assertEquals(
            RaftWhiteScreenStage.WindowNotVisible,
            raftWhiteScreenStage(windowVisible = false, sessionReady = false, webViewLoading = true, dataReady = false, drawn = false),
        )
    }

    @Test
    fun stagesInOrder() {
        assertEquals(RaftWhiteScreenStage.Session, raftWhiteScreenStage(sessionReady = false, dataReady = false, drawn = false))
        assertEquals(RaftWhiteScreenStage.WebViewLoading, raftWhiteScreenStage(webViewLoading = true, dataReady = false))
        assertEquals(RaftWhiteScreenStage.Data, raftWhiteScreenStage(dataReady = false, drawn = false))
        assertEquals(RaftWhiteScreenStage.NoDraw, raftWhiteScreenStage(drawn = false))
        assertNull(raftWhiteScreenStage())
    }

    @Test
    fun appShellWaitsOnTheSessionUnlessCachedHomeShows() {
        assertEquals(
            RaftWhiteScreenStage.Session,
            appShellWhiteScreenStage(restoring = true, cachedHomePreview = false, selectedTab = "activity", homeContentReady = true),
        )
        assertNull(appShellWhiteScreenStage(restoring = true, cachedHomePreview = true, selectedTab = "home", homeContentReady = true))
    }

    @Test
    fun appShellHomeWaitsOnItsFirstSnapshotOtherTabsDoNot() {
        assertEquals(
            RaftWhiteScreenStage.Data,
            appShellWhiteScreenStage(restoring = false, cachedHomePreview = false, selectedTab = "home", homeContentReady = false),
        )
        assertNull(appShellWhiteScreenStage(restoring = false, cachedHomePreview = false, selectedTab = "activity", homeContentReady = false))
    }

    @Test
    fun linesCarryFixedTokensOnly() {
        assertEquals(
            "white_screen_suspect page=app_shell ms=1003 stage=session scope=page",
            raftWhiteScreenSuspectLine("app_shell", 1003, RaftWhiteScreenStage.Session, scope = "page"),
        )
        assertEquals(
            "first_frame page=compose_slock_app_shell ms=0 scope=window",
            raftFirstFrameLine("first_frame", "compose_slock_app_shell", -4, scope = "window"),
        )
        // Anything that is not a token character is replaced, so a stray value can't carry text.
        assertEquals(
            "first_content page=webview:a_b_c ms=12 scope=page",
            raftFirstFrameLine("first_content", "webview:a b/c", 12, scope = "page"),
        )
    }
}
