package build.raft.designsystem.components

import kotlin.math.max
import kotlin.math.min

/*
 * The wide layout's row planned by the #189 rules (web packages/web/src/components/layout/rowPlan.ts and
 * components/ui/seamCascade.ts `solveUnits`, slock staging ec13decd3b; the design lead's prototype pt_141adb2e), as
 * pure functions of the row's room and every column's saved width:
 *
 * - Rule 2: a column may be as narrow as 160 ([RAFT_ROW_COLUMN_MIN_WIDTH_DP]); its content keeps its own minimum and
 *   is clipped.
 * - Rule 3: where the conversation and the thread can't both keep 360 side by side, or keeping them would make any
 *   column clip, the thread folds into the conversation's column.
 * - Rule 4: short of room every column gives up, in proportion, what it has above its content's minimum; then the
 *   thread folds; then the folded column gives way to 360; then all shrink in proportion and clip, to 160; past that
 *   the rightmost column becomes a tab in its left neighbour's head ([RaftRowPlan.groups]). Saved widths never change.
 * - Rule 5: one region expanded fills the row; every other column is 160.
 */

/** Web `SEAM_COLUMN_MIN_WIDTH` / `ROW_COLUMN_MIN_WIDTH`: the narrowest any column in the row may be (rule 2). */
const val RAFT_ROW_COLUMN_MIN_WIDTH_DP = 160f

/** Web `ROW_PAIR_WIDTH`: the conversation and the thread side by side at 360 each (rule 3). */
const val RAFT_ROW_PAIR_WIDTH_DP = 720f

/** Web `CONVERSATION_DEFAULT_WIDTH`: before it is ever dragged the conversation is what the others leave, at least this. */
const val RAFT_ROW_CONVERSATION_DEFAULT_WIDTH_DP = 400f

/** The conversation's region (web `CONVERSATION`): the first unit's, and in `expanded`. */
const val RAFT_ROW_CONVERSATION = "conversation"

/** The thread's region (web `THREAD`). */
const val RAFT_ROW_THREAD = "thread"

/**
 * One unit of the row (web `RowUnit` / `PlanUnit`): its [saved] width, its content's minimum [contentMin], and what it
 * gives way to before anything clips, [floor] (min([saved], [contentMin]) when null). [merged] is the conversation with
 * the thread folded into it: past the floors it gives way to [contentMin] first. [key] and [regions] name it in a plan.
 */
data class RaftRowUnit(
    val saved: Float,
    val contentMin: Float,
    val floor: Float? = null,
    val merged: Boolean = false,
    val key: String = "",
    val regions: List<String> = emptyList(),
)

/** [raftRowSolveUnits]' answer: each unit's width, and whether any had to clip under its floor. */
data class RaftRowSolved(val widths: List<Float>, val clip: Boolean)

/** Takes [deficit] from [start] in proportion to each width, none under [floor] (the prototype's shrinkProp). */
private fun shrinkInProportion(start: List<Float>, deficit: Float, floor: Float): List<Float> {
    val widths = start.toMutableList()
    var left = deficit
    var guard = 0
    while (left > 0.01f && guard < 24) {
        guard += 1
        val shrinkable = widths.indices.filter { widths[it] > floor + 0.01f }
        if (shrinkable.isEmpty()) break
        val total = shrinkable.sumOf { widths[it].toDouble() }.toFloat()
        var taken = 0f
        for (index in shrinkable) {
            val take = min(widths[index] - floor, left * widths[index] / total)
            widths[index] = widths[index] - take
            taken += take
        }
        left -= taken
    }
    return widths
}

/**
 * Web `solveUnits` (rule 4): each unit's width in [available]. Saved widths that leave room over grow in proportion;
 * short of room each unit first gives up, in proportion, what it has above its floor, so nothing clips; then the
 * merged unit gives way from its floor to its content's minimum; past that all shrink in proportion towards 160 and
 * clip.
 */
fun raftRowSolveUnits(units: List<RaftRowUnit>, available: Float): RaftRowSolved {
    val saved = units.map { max(RAFT_ROW_COLUMN_MIN_WIDTH_DP, it.saved) }
    val total = saved.sum()
    if (total <= 0f) return RaftRowSolved(saved, clip = false)
    if (total <= available + 0.01f) return RaftRowSolved(saved.map { it * available / total }, clip = false)
    var deficit = total - available
    val floors = saved.mapIndexed { i, width -> min(width, units[i].floor ?: units[i].contentMin) }
    val above = saved.indices.sumOf { (saved[it] - floors[it]).toDouble() }.toFloat()
    if (deficit <= above + 0.01f) {
        return RaftRowSolved(
            saved.mapIndexed { i, width -> if (above > 0f) width - deficit * (width - floors[i]) / above else width },
            clip = false,
        )
    }
    val widths = floors.toMutableList()
    deficit -= above
    units.forEachIndexed { i, unit ->
        if (!unit.merged || deficit <= 0f) return@forEachIndexed
        val give = min(deficit, max(0f, widths[i] - unit.contentMin))
        widths[i] = widths[i] - give
        deficit -= give
    }
    return RaftRowSolved(shrinkInProportion(widths, deficit, RAFT_ROW_COLUMN_MIN_WIDTH_DP), clip = true)
}

/** A column right of the conversation (web `PlanColumn`): its regions top to bottom with their content minimums. */
data class RaftRowPlanColumn(
    val key: String,
    val regions: List<String>,
    val mins: List<Float>,
    /** Its saved width; for the thread's column, the thread's own. */
    val saved: Float,
    /** The thread's column: its saved width once the thread has folded out of it (the sections stacked with it stay). */
    val savedWithoutThread: Float? = null,
)

/** A saved width and its content's minimum. */
data class RaftRowPlanSize(val saved: Float, val contentMin: Float)

/** Web `PlanInput`. */
data class RaftRowPlanInput(
    /** The row's width: the window less the rail and the sidebar. */
    val available: Float,
    val conversation: RaftRowPlanSize,
    /** The thread's saved width and content minimum while one is open beside the conversation; null otherwise. */
    val thread: RaftRowPlanSize?,
    /** Left to right. */
    val columns: List<RaftRowPlanColumn>,
    /** The region expanded (rule 5), or null. */
    val expanded: String? = null,
    /** Whether the thread was folded when that region was expanded: it stays so while expanded. */
    val expandFold: Boolean = false,
    /** Whether the last plan folded the thread (rule 3's way back: [RaftRowPlan.rebalance]). */
    val wasFolded: Boolean = false,
    /** Fold whatever the room (a touch tablet in portrait under 1280). */
    val forceFold: Boolean = false,
)

/** The conversation's and the thread's saved widths to keep coming out of the fold (web `rebalance`). */
data class RaftRowRebalance(val conversation: Float, val thread: Float)

/** Web `RowPlan`. */
data class RaftRowPlan(
    /** The thread is folded into the conversation's column. */
    val fold: Boolean,
    /** The row's units, left to right: the conversation first. */
    val units: List<RaftRowUnit>,
    /** Unit indices per column on screen: more than one where columns became tabs in the first one's head. */
    val groups: List<List<Int>>,
    /** Each group's width. */
    val widths: List<Float>,
    /** The region expanded, where it still is in the row. */
    val expanded: String?,
    /** The key of the unit an expanded region holds the row with. */
    val expandedUnit: String?,
    val rebalance: RaftRowRebalance?,
)

private fun threadAt(columns: List<RaftRowPlanColumn>): Int = columns.indexOfFirst { RAFT_ROW_THREAD in it.regions }

/** Web `buildUnits`: the conversation (with the thread folded into it, [fold]) and each column with a region left. */
fun raftRowBuildUnits(input: RaftRowPlanInput, fold: Boolean): List<RaftRowUnit> {
    val thread = input.thread
    val at = if (thread != null) threadAt(input.columns) else -1
    val folds = fold && at >= 0
    val alone = folds && input.columns[at].regions.size == 1
    val units = mutableListOf<RaftRowUnit>()
    if (folds) {
        val saved = input.conversation.saved + (if (alone) thread!!.saved else 0f)
        units +=
            RaftRowUnit(
                saved = saved,
                contentMin = input.conversation.contentMin,
                floor = min(saved, RAFT_ROW_PAIR_WIDTH_DP),
                merged = true,
                key = RAFT_ROW_CONVERSATION,
                regions = listOf(RAFT_ROW_CONVERSATION, RAFT_ROW_THREAD),
            )
    } else {
        units += RaftRowUnit(input.conversation.saved, input.conversation.contentMin, key = RAFT_ROW_CONVERSATION, regions = listOf(RAFT_ROW_CONVERSATION))
    }
    input.columns.forEachIndexed { index, column ->
        val keep =
            column.regions.mapIndexed { i, region -> region to (column.mins.getOrNull(i) ?: 0f) }
                .filterNot { (region, _) -> folds && index == at && region == RAFT_ROW_THREAD }
        if (keep.isEmpty()) return@forEachIndexed
        val saved = if (folds && index == at) column.savedWithoutThread ?: column.saved else column.saved
        units += RaftRowUnit(saved, max(0f, keep.maxOf { it.second }), key = column.key, regions = keep.map { it.first })
    }
    return units
}

/** Web `decideFold`'s answer. */
data class RaftRowFold(val fold: Boolean, val rebalance: RaftRowRebalance?)

/**
 * Web `decideFold` (rule 3): the thread folds where the conversation and the thread can't keep 360 + 360 side by side,
 * or where the row as it stands would make a column clip: folding comes before any clipping.
 */
fun raftRowDecideFold(input: RaftRowPlanInput): RaftRowFold {
    val thread = input.thread ?: return RaftRowFold(false, null)
    val at = threadAt(input.columns)
    if (at < 0) return RaftRowFold(false, null)
    if (input.forceFold) return RaftRowFold(true, null)
    val units = raftRowBuildUnits(input, fold = false)
    val unit = units.indexOfFirst { RAFT_ROW_THREAD in it.regions }
    val solved = raftRowSolveUnits(units, input.available)
    val conversation = solved.widths[0]
    val threadWidth = solved.widths[unit]
    val fold = solved.clip || conversation + threadWidth < RAFT_ROW_PAIR_WIDTH_DP - 0.5f
    var rebalance: RaftRowRebalance? = null
    // Out of the fold the pair shares what it was given so neither starts under its 360; only for a thread alone in
    // its column (stacked, its column's width is its sections' too).
    if (!fold && input.wasFolded && input.columns[at].regions.size == 1) {
        val pair = conversation + threadWidth
        if (threadWidth < thread.contentMin || conversation < input.conversation.contentMin) {
            val nextThread = max(thread.contentMin, min(pair - input.conversation.contentMin, threadWidth))
            val saved = input.conversation.saved + thread.saved
            val savedThread = saved * nextThread / pair
            rebalance = RaftRowRebalance(conversation = saved - savedThread, thread = savedThread)
        }
    }
    return RaftRowFold(fold, rebalance)
}

/**
 * Web `groupUnits` (rule 4's last step): while the row can't give every column its 160, the rightmost column becomes a
 * tab in its left neighbour's head. Indices into the units.
 */
fun raftRowGroupUnits(count: Int, available: Float): List<List<Int>> {
    val groups = (0 until count).map { mutableListOf(it) }.toMutableList()
    while (groups.size > 1 && groups.size * RAFT_ROW_COLUMN_MIN_WIDTH_DP > available + 0.5f) {
        val last = groups.removeAt(groups.lastIndex)
        groups[groups.lastIndex].addAll(last)
    }
    return groups
}

/** The unit holding [region] (a folded thread is the conversation's); -1 for none. */
fun raftRowUnitOf(units: List<RaftRowUnit>, region: String): Int = units.indexOfFirst { region in it.regions }

/** Web `planRow`: whether the thread folds, the units, the tabs and every column's width. */
fun raftRowPlan(input: RaftRowPlanInput): RaftRowPlan {
    val available = input.available
    val expanded = input.expanded
    if (expanded != null) {
        val fold = input.expandFold && input.thread != null && threadAt(input.columns) >= 0
        val units = raftRowBuildUnits(input, fold)
        val groups = raftRowGroupUnits(units.size, available)
        val unit = raftRowUnitOf(units, expanded)
        val group = groups.indexOfFirst { unit in it }
        if (unit >= 0 && group >= 0) {
            // Rule 5: the expanded column fills the row, every other one is 160 and clips.
            val others = groups.size - 1
            val widths =
                groups.indices.map { index ->
                    if (index == group) max(RAFT_ROW_COLUMN_MIN_WIDTH_DP, available - RAFT_ROW_COLUMN_MIN_WIDTH_DP * others)
                    else RAFT_ROW_COLUMN_MIN_WIDTH_DP
                }
            return RaftRowPlan(fold, units, groups, widths, expanded, units[unit].key, rebalance = null)
        }
        // The expanded region went: the row as if nothing were expanded.
        return raftRowPlan(input.copy(expanded = null))
    }
    val (fold, rebalance) = raftRowDecideFold(input)
    val units = raftRowBuildUnits(input, fold)
    val groups = raftRowGroupUnits(units.size, available)
    val widths = raftRowSolveUnits(groups.map { units[it.first()] }, available).widths
    return RaftRowPlan(fold, units, groups, widths, expanded = null, expandedUnit = null, rebalance = rebalance)
}

/**
 * Web `unitWidths`: each unit's width, its group's where it is the one shown there ([active] gives the unit index a
 * group shows), 0 for a tab not shown.
 */
fun raftRowUnitWidths(plan: RaftRowPlan, active: (group: Int) -> Int): List<Float> {
    val widths = MutableList(plan.units.size) { 0f }
    plan.groups.forEachIndexed { index, group ->
        val picked = active(index)
        val shown = if (picked in group) picked else group.first()
        widths[shown] = plan.widths[index]
    }
    return widths
}

/**
 * Web `splitPair`: a width for the folded conversation's unit shared back between the conversation and the thread by
 * their saved widths, each kept at its 360 where the pair has 720.
 */
fun raftRowSplitPair(width: Float, conversation: Float, thread: Float, threadMin: Float, conversationMin: Float): RaftRowRebalance {
    val total = conversation + thread
    var next = if (total > 0f) width * thread / total else width / 2f
    if (width >= RAFT_ROW_PAIR_WIDTH_DP) next = max(threadMin, min(width - conversationMin, next))
    return RaftRowRebalance(conversation = width - next, thread = next)
}
