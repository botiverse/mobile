package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.BasePager
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.TabPager
import build.raft.app.components.TabPagerGesturePolicy
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page

const val SLOCK_TAB_PAGER_PROBE_PAGE = "compose_tab_pager_probe"

// Param: initial selected tab (0..2).
const val TAB_PAGER_PROBE_INITIAL_TAB = "initialTab"

// Distinct page backgrounds so screenshots can prove which tab is visible.
private val TabPagerProbeColors =
    listOf(
        Color(0xFFFFE0E0), // tab 0 — red tint
        Color(0xFFE0FFE0), // tab 1 — green tint
        Color(0xFFE0E0FF) // tab 2 — blue tint
    )
private val TabPagerProbeLabels = listOf("Alpha", "Beta", "Gamma")

/**
 * task #232 acceptance carrier (task #233, Jony's iOS evidence standard): a
 * login-free page hosting the REAL [TabPager] — same SegmentedControl sync,
 * same platform-injected edge policy as the Activity integration — with three
 * synthetic tabs whose bodies are independently scrollable lists. An action
 * runner can drive all five gesture groups against it and read the outcome
 * from pixels + row text alone:
 *
 * 1. content-area swipe follows the finger and switches tabs (background
 *    color changes; SegmentedControl selection follows);
 * 2. tapping a tab animates the pager to that page;
 * 3. a horizontal drag born inside the platform start-edge inset belongs to
 *    the system (edge-back), not the pager;
 * 4. slow/cancelled/reversed drags that settle back do not change the
 *    selection;
 * 5. each tab's list scroll position survives switching away and back.
 */
@Page(SLOCK_TAB_PAGER_PROBE_PAGE, supportInLocal = true)
internal class SlockTabPagerProbePage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockTabPagerProbeScreen() }
    }
}

@Composable
private fun SlockTabPagerProbeScreen() {
    val initialTab =
        LocalActivity.current.pageData.params
            .optInt(TAB_PAGER_PROBE_INITIAL_TAB, 0)
            .coerceIn(0, TabPagerProbeLabels.lastIndex)
    var selectedTab by remember { mutableStateOf(initialTab) }
    // task #233 round-3: on-screen event chain. Newest first, bounded, with a
    // monotonic sequence number so screenshots prove ordering and liveness.
    var eventSeq by remember { mutableStateOf(0) }
    val eventLog = remember { mutableStateListOf<String>() }
    val logEvent: (String) -> Unit = { line ->
        eventSeq += 1
        eventLog.add(0, "#$eventSeq $line")
        while (eventLog.size > 10) eventLog.removeAt(eventLog.size - 1)
    }
    val pageListStates = listOf(
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
    )
    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        TabPager(
            selectedIndex = selectedTab,
            onSelectedIndexChange = {
                logEvent("onSelectedIndexChange -> $it")
                selectedTab = it
            },
            onDebugEvent = logEvent,
            pageCount = TabPagerProbeLabels.size,
            gesturePolicy = TabPagerGesturePolicy(
                startEdgeInset = slockPlatformTabPagerStartEdgeInsetDp().dp,
            ),
            tabBar = {
                // Textual sync readout so acceptance screenshots prove the
                // selected state, not just the page pixels (task #233 round 2).
                Text(
                    text = "selected=$selectedTab (${TabPagerProbeLabels[selectedTab]})",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
                // Event-chain readout: which link is dead shows up as the last
                // line that ever appears (gesture never reaching the pager /
                // settle never firing / effect not running / callback lost).
                eventLog.forEach { line ->
                    Text(
                        text = line,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
                    SegmentedControl(
                        selectedValue = TabPagerProbeLabels[selectedTab],
                        options = TabPagerProbeLabels.map { SegmentedControlOption(value = it, label = it) },
                        onValueSelected = { value ->
                            logEvent("tap $value")
                            TabPagerProbeLabels.indexOf(value).takeIf { it >= 0 }?.let { selectedTab = it }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { page, _ ->
            LazyColumn(
                state = pageListStates[page],
                modifier = Modifier.fillMaxSize().background(TabPagerProbeColors[page])
            ) {
                items(60) { row ->
                    Text(
                        text = "${TabPagerProbeLabels[page]} row $row",
                        modifier = Modifier
                            .fillMaxWidth()
                            // #2224 review blocker 2 carrier: rows are
                            // clickable so acceptance can prove a stationary
                            // tap inside the platform edge inset reaches the
                            // content (the guard must only claim horizontal
                            // drags). The event log line is the evidence.
                            // ORDER IS PART OF THE ACCEPTANCE CONTRACT:
                            // clickable BEFORE padding makes the hit band the
                            // full row (text + 16dp padding, bands tile with
                            // no dead gap) — task #246 tap-attribution math
                            // maps y-coordinates to rows assuming exactly
                            // this. Swapping to .padding().clickable() would
                            // shrink the band and silently change the
                            // acceptance criteria.
                            .clickable { logEvent("rowTap ${TabPagerProbeLabels[page]} $row") }
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}
