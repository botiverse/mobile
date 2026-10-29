package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

// A host that draws a RaftSideActionColumn's buttons itself: iOS 27.1 can put an app's bar
// button items in iPhone Duo's vertical strip (`UIBarButtonItem.axisBehavior =
// .verticalPreferred`), drawn and placed by the system. The column then only holds its place.

/**
 * Draws a [RaftSideActionColumn]'s buttons in place of the column (iOS: system bar button items in
 * the Duo's vertical strip). Provided through [LocalRaftSideActionHost]; one column per host.
 *
 * The column hands its buttons over only when every primary and action has a
 * [RaftSideAction.systemSymbol]; otherwise it draws them itself, as without a host, and the host
 * is not asked (or is [clear]ed if it was drawing).
 */
interface RaftSideActionHost {
    /**
     * Draw [column], replacing what was drawn. Called when the column's content changes (never
     * twice in a row for equal data), after composition, on the main thread. [onAction] runs the
     * button or ⋯ row of that id with its latest `onClick` (a no-op for an unknown or disabled id);
     * it is the same function for the column's whole life.
     *
     * True when the host draws it. False when it cannot: the column then draws its own buttons
     * until its content changes and asks again.
     */
    fun present(column: RaftSideActionHostColumn, onAction: (id: String) -> Unit): Boolean

    /** Stop drawing: the column left composition, or draws its buttons itself again. */
    fun clear()
}

/**
 * What a [RaftSideActionHost] draws, top to bottom: [primary] (Back) then the page actions.
 *
 * [actions] are all of them in their listed order, and the column's own rules
 * ([RaftSideAction.keepVisible], [RaftSideAction.priority], the
 * [RaftSideActionColumnDefaults.MaxActions] cap) split them into [visibleActionIds] (buttons, in
 * listed order) and [overflowActionIds] (rows of the ⋯ menu, named [overflowLabel], in listed
 * order; empty = no ⋯). The split ignores the column's height and keep-out bands: the system
 * strip places its own buttons.
 */
@Immutable
data class RaftSideActionHostColumn(
    val primary: List<Item>,
    val actions: List<Item>,
    val visibleActionIds: List<String>,
    val overflowActionIds: List<String>,
    val overflowLabel: String,
) {
    /**
     * One button: [RaftSideAction] without its drawing. [label] is the accessibility label (and the
     * ⋯ row's text); [attention] is the bell's "new" pixel, read as "[label], [attentionDescription]".
     */
    @Immutable
    data class Item(
        val id: String,
        val label: String,
        val systemSymbol: String,
        val enabled: Boolean,
        val attention: Boolean,
        val attentionDescription: String?,
    )
}

/** The host that draws side action columns in this window, if any (null: the column draws itself). */
val LocalRaftSideActionHost = staticCompositionLocalOf<RaftSideActionHost?> { null }

/**
 * [primary] and [actions] as a host draws them, or null when one of them has no
 * [RaftSideAction.systemSymbol] (the column then draws them all itself). An empty column hands
 * off too, so a page that briefly has no buttons does not flip between the host and the column.
 */
internal fun raftSideActionHostColumn(
    primary: List<RaftSideAction>,
    actions: List<RaftSideAction>,
    overflowLabel: String,
    maxActions: Int = RaftSideActionColumnDefaults.MaxActions,
): RaftSideActionHostColumn? {
    fun RaftSideAction.item(): RaftSideActionHostColumn.Item? {
        val symbol = systemSymbol?.takeIf { it.isNotBlank() } ?: return null
        return RaftSideActionHostColumn.Item(id, label, symbol, enabled, attention, attentionDescription)
    }
    val primaryItems = primary.map { it.item() ?: return null }
    val actionItems = actions.map { it.item() ?: return null }
    // Only the order matters: a column exactly tall enough for what the cap lets it show, so
    // the split is the cap's and the ranks' alone.
    val metrics = RaftSideColumnMetrics(item = 1, gap = 0, inset = 0, sectionGap = 0)
    val plan =
        raftSideColumnPlan(
            height = raftSideColumnNaturalHeight(metrics, primary.size, actions.size, maxActions),
            metrics = metrics,
            primaryCount = primary.size,
            ranks = actions.map { it.raftSideRank() },
            maxActions = maxActions,
        )
    return RaftSideActionHostColumn(
        primary = primaryItems,
        actions = actionItems,
        visibleActionIds = plan.shown.map { actions[it].id },
        overflowActionIds = plan.overflowed.map { actions[it].id },
        overflowLabel = overflowLabel,
    )
}

/** An action's rank in [raftSideColumnPlan]: kept-visible ones before any priority. */
internal fun RaftSideAction.raftSideRank(): Int = if (keepVisible) Int.MAX_VALUE else priority

/** The `onClick` a host's tap on [id] runs: the first action of that id, if enabled. */
internal fun List<RaftSideAction>.raftSideActionClick(id: String): (() -> Unit)? =
    firstOrNull { it.id == id }?.takeIf { it.enabled }?.onClick

/**
 * One column's link to its [host]: presents only content that changed, clears when the column
 * stops handing off or leaves composition, and remembers what the host declined.
 */
internal class RaftSideActionHostPresenter(
    private val host: RaftSideActionHost,
    private val onAction: (id: String) -> Unit,
) : RememberObserver {
    private var presented: RaftSideActionHostColumn? = null

    /** The content the host last declined to draw (the column draws it itself); null = none. */
    var declined by mutableStateOf<RaftSideActionHostColumn?>(null)
        private set

    /** Hands [column] to the host, or takes the buttons back with null. */
    fun update(column: RaftSideActionHostColumn?) {
        if (column == presented) return
        if (column == null) {
            release()
            return
        }
        presented = column
        declined = if (host.present(column, onAction)) null else column
    }

    /** Whether the column hands [column] to the host (it has not declined that content). */
    fun handsOff(column: RaftSideActionHostColumn?): Boolean = column != null && column != declined

    fun release() {
        declined = null
        if (presented == null) return
        presented = null
        host.clear()
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = release()

    override fun onAbandoned() = release()
}

/**
 * True when the [LocalRaftSideActionHost] draws this column's buttons, the column then only
 * holding its place. Presents changed content after composition and clears the host when the
 * column stops handing off or leaves composition.
 */
@Composable
internal fun raftSideActionHostHandoff(
    primary: List<RaftSideAction>,
    actions: List<RaftSideAction>,
    overflowLabel: String,
): Boolean {
    val host = LocalRaftSideActionHost.current
    // Taps come back later, by id: run the action of the latest composition, not a stale lambda.
    val latest = rememberUpdatedState(primary + actions)
    val presenter =
        remember(host) {
            host?.let { RaftSideActionHostPresenter(it) { id -> latest.value.raftSideActionClick(id)?.invoke() } }
        } ?: return false
    val column = raftSideActionHostColumn(primary, actions, overflowLabel)
    SideEffect { presenter.update(column) }
    return presenter.handsOff(column)
}
