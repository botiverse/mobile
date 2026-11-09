package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppShellTabHistoryTest {
    private val phone = appShellTabHistoryRoots(navRailActive = false)
    private val rail = appShellTabHistoryRoots(navRailActive = true)

    /** Picks [tabs] in order from Home; returns the history and the tab showing. */
    private fun pick(vararg tabs: String, roots: Set<String> = phone): Pair<AppShellTabHistory, String> {
        var history = AppShellTabHistory()
        var current = "home"
        tabs.forEach { next ->
            history = history.selected(from = current, to = next, roots = roots)
            current = next
        }
        return history to current
    }

    /** Presses Back on [current]; returns the history and the tab landed on (null: leave the app). */
    private fun back(history: AppShellTabHistory, current: String, roots: Set<String> = phone): Pair<AppShellTabHistory, String?> {
        val target = history.backTarget(current, roots) ?: return history to null
        return history.arrivedByBack(target) to target
    }

    @Test
    fun phoneRootsAreTheBarTabsWithoutTheServerTab() {
        assertEquals(setOf("home", "activity", "tasks", AppShellSearchTabKey), phone)
    }

    @Test
    fun railRootsHaveNoSearchTab() {
        assertEquals(false, AppShellSearchTabKey in rail)
        assertEquals(true, "activity" in rail && "members" in rail && "settings" in rail)
    }

    @Test
    fun activityThenSearchBackReturnsToActivity() {
        val (history, current) = pick("activity", AppShellSearchTabKey)
        assertEquals("activity", back(history, current).second)
    }

    @Test
    fun backWalksThroughEveryEarlierTabThenLeavesFromHome() {
        val (history, current) = pick("activity", AppShellSearchTabKey, "tasks")
        val (h1, t1) = back(history, current)
        assertEquals(AppShellSearchTabKey, t1)
        val (h2, t2) = back(h1, t1!!)
        assertEquals("activity", t2)
        val (h3, t3) = back(h2, t2!!)
        assertEquals("home", t3)
        val (h4, t4) = back(h3, t3!!)
        assertNull(t4)
        assertEquals(emptyList(), h4.tabs)
    }

    @Test
    fun reselectingATabMovesItToTheTop() {
        val (history, current) = pick("activity", "tasks", "activity", AppShellSearchTabKey)
        assertEquals(listOf("home", "tasks", "activity"), history.tabs)
        assertEquals(AppShellSearchTabKey, current)
    }

    @Test
    fun historyKeepsOnlyTheNewestTabsUpToTheCap() {
        var history = AppShellTabHistory()
        val tabs = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        tabs.zipWithNext().forEach { (from, to) ->
            history = history.selected(from = from, to = to, roots = tabs.toSet(), cap = AppShellTabHistoryCap)
        }
        assertEquals(AppShellTabHistoryCap, history.tabs.size)
        assertEquals("i", history.tabs.last())
    }

    @Test
    fun backSwitchDoesNotPushTheTabItLeaves() {
        val (history, current) = pick("activity", AppShellSearchTabKey)
        val (afterBack, landed) = back(history, current)
        assertEquals("activity", landed)
        assertEquals(listOf("home"), afterBack.tabs)
    }

    @Test
    fun noHistoryOnAnotherTabGoesHome() {
        assertEquals("home", AppShellTabHistory().backTarget("tasks", phone))
    }

    @Test
    fun homeWithNoHistoryLeavesTheApp() {
        assertNull(AppShellTabHistory().backTarget("home", phone))
    }

    @Test
    fun homeWithHistoryReturnsToTheEarlierTab() {
        val (history, current) = pick("activity", "home")
        assertEquals("activity", back(history, current).second)
    }

    @Test
    fun sanitizeDropsTabsThatAreNoLongerAvailable() {
        val (history, current) = pick("tasks", "activity", AppShellSearchTabKey)
        val clamped = history.sanitized(listOf("home", AppShellSearchTabKey, "tasks"))
        assertEquals(listOf("home", "tasks"), clamped.tabs)
        assertEquals("tasks", clamped.backTarget(current, phone))
    }

    @Test
    fun backSkipsTabsThatAreNotRootsOfThisLayout() {
        // Search was picked on the phone; the window widened, where Search is no tab.
        val (history, _) = pick("activity", AppShellSearchTabKey, "tasks")
        assertEquals("activity", history.backTarget("tasks", rail))
    }

    @Test
    fun serverPagesAreNotRecordedButTheirOriginIs() {
        // Activity -> server panel -> Settings (more) -> Members; Back from the pages returns to
        // Activity through the server page's own Back, which drops Activity from the history.
        var history = pick("activity").first
        history = history.selected(from = "activity", to = AppShellServerTabKey, roots = phone)
        history = history.selected(from = AppShellServerTabKey, to = "members", roots = phone)
        assertEquals(listOf("home", "activity"), history.tabs)
        history = history.arrivedByBack("activity")
        assertEquals("home", history.backTarget("activity", phone))
    }

    @Test
    fun aFreshHistoryAfterAProgrammaticSwitchFallsBackToHome() {
        assertEquals("home", AppShellTabHistory().backTarget("activity", phone))
    }
}
