package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import build.raft.app.base.BridgeModule
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.compat.pager.RaftPaneRole
import build.raft.app.resources.*
import build.raft.designsystem.components.LocalRaftPaneGridColumnShown
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Wide-layout pane roles (plan option C, artin 2026-10-29): on the wide layout a page whose
 * registry role ([RaftPageRegistry.paneRole]) is not Window opens in a pane of the shell instead of
 * on the platform stack, which covers the whole window, rail included. Every page reaches its
 * host navigator through RouterModule.openPage, and a child page shares its parent's host, so the
 * shell wraps that navigator ([RaftPaneHost]) around the pages it hosts. Off the wide layout the
 * wrapper hands every call to the platform navigator without looking at roles.
 */

/** Where an `openPage` goes: the platform stack ([RaftPaneRole.Window]) or a shell pane. Roles are read only when [wide]. */
internal fun raftPaneRoute(
    wide: Boolean,
    pageName: String,
    roleOf: (String) -> RaftPaneRole = RaftPageRegistry::paneRole,
): RaftPaneRole = if (wide) roleOf(pageName) else RaftPaneRole.Window

/**
 * Where a page opened from inside the profile column goes: a conversation becomes the main detail
 * (web: the profile's Message closes the panel and navigates to the DM); the rest by [roleOf].
 */
internal fun raftProfileColumnRole(
    pageName: String,
    roleOf: (String) -> RaftPaneRole = RaftPageRegistry::paneRole,
): RaftPaneRole = if (pageName == THREAD_PAGE) RaftPaneRole.Detail else roleOf(pageName)

/** One page open in a shell pane; [id] keys its composition. */
internal data class RaftPaneEntry(val id: Long, val pageName: String, val pageData: JsonObject)

/** A profile column cell's own page stack ([RaftProfileColumnPanes]): the last entry shows, Back pops it. */
internal data class RaftPaneStack(val entries: List<RaftPaneEntry> = emptyList(), private val nextId: Long = 0) {
    val isOpen: Boolean get() = entries.isNotEmpty()

    /** A page opened from inside the cell: pushed on it. */
    fun push(pageName: String, pageData: JsonObject): RaftPaneStack =
        RaftPaneStack(entries + RaftPaneEntry(nextId, pageName, pageData), nextId + 1)

    /** A page opened from the conversation: the cell starts over with it (web: one section, one page). */
    fun replace(pageName: String, pageData: JsonObject): RaftPaneStack =
        RaftPaneStack(listOf(RaftPaneEntry(nextId, pageName, pageData)), nextId + 1)

    fun pop(): RaftPaneStack = copy(entries = entries.dropLast(1))

    /** Entry [id] closed itself (its `closePage`): it goes, with anything opened over it. */
    fun close(id: Long): RaftPaneStack = copy(entries = entries.takeWhile { it.id != id })

    fun cleared(): RaftPaneStack = copy(entries = emptyList())
}

/** What a wide Back (system Back, Esc, Cmd+[, the top bar's Back) does first. */
internal enum class RaftWideBackStep { PopProfileColumn, Existing }

/**
 * [shown] is whether the profile column is on screen (the same condition that declares it). Pages can sit in it
 * while it isn't, e.g. while the Tasks page's task column shows; Back must not spend a press closing it unseen.
 */
internal fun raftWideBackStep(column: RaftProfileColumnPanes, shown: Boolean): RaftWideBackStep =
    if (shown && column.isOpen) RaftWideBackStep.PopProfileColumn else RaftWideBackStep.Existing

/**
 * The shell's wide Back: an open profile column is the topmost thing, so Back closes its last page before
 * stepping the history; on the Tasks page the task column shows the detail without the column, so
 * while [overlayOpen] (that column open) Back is the column's (Tenny, #3953). [paneShown] is what [RaftSupportingPaneShownReport] last reported.
 */
internal fun raftWideBackStep(column: RaftProfileColumnPanes, paneShown: Boolean, overlayOpen: Boolean): RaftWideBackStep =
    raftWideBackStep(column, shown = paneShown && !overlayOpen)

/**
 * Reports through [report] whether the profile column (the pages with the Supporting role) is [shown] beside this
 * detail pane. Only the pane that hosts it ([host]: the main detail, not the Tasks page column's copy) reports, so the
 * column's copy leaving the composition when the column closes does not reset it (Tenny, #3953).
 */
@Composable
internal fun RaftSupportingPaneShownReport(host: Boolean, shown: Boolean, report: (Boolean) -> Unit) {
    if (host) {
        DisposableEffect(shown) {
            report(shown)
            onDispose { report(false) }
        }
    }
}

/**
 * The host navigator for pages the shell hosts: [openInPane] takes the pages [raftPaneRoute] sends
 * to a pane, [platform] the rest with the same arguments; [close] is this page's `closePage`.
 */
internal class RaftPaneNavigator(
    private val platform: RaftCmpNavigator,
    private val wide: () -> Boolean,
    private val openInPane: (RaftPaneRole, String, JsonObject) -> Unit,
    private val close: () -> Unit = platform::closePage,
    private val roleOf: (String) -> RaftPaneRole = RaftPageRegistry::paneRole,
) : RaftCmpNavigator {
    override fun openPage(pageName: String, pageData: JsonObject) {
        when (val role = raftPaneRoute(wide(), pageName, roleOf)) {
            RaftPaneRole.Window -> platform.openPage(pageName, pageData)
            else -> openInPane(role, pageName, pageData)
        }
    }

    override fun closePage() = close()
}

/**
 * The bridge wire for a page in a pane: the bridge's own `closePage` (BridgeModule.closePage, which
 * the member profile's Back uses) closes the pane page through [close], not the window under it.
 * Every other call goes to [platform] unchanged.
 */
internal class RaftPaneBridgeTransport(
    private val platform: HostBridgeTransport,
    private val close: () -> Unit,
) : HostBridgeTransport {
    override fun call(method: String, paramsJson: String, callback: ((String?) -> Unit)?) {
        if (method == BridgeModule.CLOSE_PAGE) close() else platform.call(method, paramsJson, callback)
    }

    override fun callSync(method: String, paramsJson: String): String? = platform.callSync(method, paramsJson)
}

/**
 * Gives [content]'s pages a [RaftPaneNavigator] over the current host's navigator; the rest of
 * the host (events, capabilities) is shared. [close] null: `closePage` stays the platform's;
 * otherwise both the navigator's and the bridge's `closePage` call it.
 */
@Composable
internal fun RaftPaneHost(
    wide: Boolean,
    openInPane: (RaftPaneRole, String, JsonObject) -> Unit,
    close: (() -> Unit)? = null,
    roleOf: (String) -> RaftPaneRole = RaftPageRegistry::paneRole,
    content: @Composable () -> Unit,
) {
    val parent = LocalRaftHost.current
    val currentWide by rememberUpdatedState(wide)
    val currentOpenInPane by rememberUpdatedState(openInPane)
    val currentClose by rememberUpdatedState(close)
    val ownClose = close != null
    val host =
        remember(parent, ownClose) {
            val closePage = { currentClose?.invoke() ?: parent.navigator.closePage() }
            RaftCmpHost(
                capabilities = parent.capabilities,
                navigator =
                    RaftPaneNavigator(
                        platform = parent.navigator,
                        wide = { currentWide },
                        openInPane = { role, name, data -> currentOpenInPane(role, name, data) },
                        close = closePage,
                        roleOf = roleOf,
                    ),
                bridgeTransport =
                    parent.bridgeTransport?.let { transport ->
                        if (ownClose) RaftPaneBridgeTransport(transport, closePage) else transport
                    },
                events = parent.events,
                childPageLoads = parent.childPageLoads,
            )
        }
    CompositionLocalProvider(LocalRaftHost provides host, content = content)
}

/**
 * True on the first page of a profile column cell, where the page's Back closes the cell: its head
 * shows the leading Close (×, web ChannelSideCard) instead of the Back arrow (RaftTopPageHeader).
 */
internal val LocalRaftPaneBackCloses = staticCompositionLocalOf { false }

/*
 * The profile column (#189 step 3; web channelSideLayout's `[profile, context, details]` column, 400 wide, at least
 * 320): on the wide layout the pages with the Supporting role (a member's profile, the channel details and the
 * details' member list) open in a column of the pane grid's row, right of every other column, as the web's default
 * layout has it. The row plan gives it room as it gives every column: short of room all columns give way, then clip,
 * then the rightmost become tabs. The thread stays where the plan puts it (it no longer steps aside).
 *
 * The column stacks two cells, each a page stack of its own: the profile on top, the details under it (web: the
 * details follow the profile). A member picked in the details' member list shows their profile above the list.
 */

/** The profile column's cells, top to bottom; [id] keys the cell's fold and the stack's split. */
internal enum class RaftProfileColumnCell(val id: String) {
    Profile("profile"),
    Details("details"),
}

/** The cell a page with the Supporting role opens in: a member's profile in the profile, the rest (the channel details, their member list) in the details. */
internal fun raftProfileColumnCellOf(pageName: String): RaftProfileColumnCell =
    if (pageName == MEMBER_DETAIL_PAGE) RaftProfileColumnCell.Profile else RaftProfileColumnCell.Details

/**
 * The profile column's pages: each cell's own stack, and the cell opened into [last] (Back closes a page there
 * first). Values only, compared by value.
 */
@Immutable
internal data class RaftProfileColumnPanes(
    val profile: RaftPaneStack = RaftPaneStack(),
    val details: RaftPaneStack = RaftPaneStack(),
    val last: RaftProfileColumnCell? = null,
) {
    val isOpen: Boolean get() = profile.isOpen || details.isOpen

    /** The cells with a page, top to bottom: the column's cells. None: no column. */
    val cells: List<RaftProfileColumnCell> get() = RaftProfileColumnCell.entries.filter { stack(it).isOpen }

    /**
     * Every page in it, in the order they reopen in on the platform stack when the window narrows: the cell opened
     * into last on top, so Back goes through them as it did in the column.
     */
    val pages: List<RaftPaneEntry>
        get() = if (backCell == RaftProfileColumnCell.Details) profile.entries + details.entries else details.entries + profile.entries

    /** Whether this has a page [before] hadn't (one opened, not one closed): the column must show (it leaves Focus). */
    fun openedSince(before: RaftProfileColumnPanes): Boolean =
        RaftProfileColumnCell.entries.any { cell ->
            val had = before.stack(cell).entries.map { it.id }.toSet()
            stack(cell).entries.any { it.id !in had }
        }

    /** The cell Back closes a page of: the one opened into last, else the other one open; null when none is. */
    val backCell: RaftProfileColumnCell? get() = last?.takeIf { stack(it).isOpen } ?: cells.lastOrNull()

    fun stack(cell: RaftProfileColumnCell): RaftPaneStack =
        when (cell) {
            RaftProfileColumnCell.Profile -> profile
            RaftProfileColumnCell.Details -> details
        }

    private fun withStack(cell: RaftProfileColumnCell, stack: RaftPaneStack): RaftProfileColumnPanes =
        when (cell) {
            RaftProfileColumnCell.Profile -> copy(profile = stack)
            RaftProfileColumnCell.Details -> copy(details = stack)
        }

    /** [last] kept while its cell is open, else the other open cell's. */
    private fun settled(): RaftProfileColumnPanes = copy(last = backCell)

    /** A page opened from the conversation (or anything else outside the column): its cell starts over with it; the other cell stays. */
    fun open(pageName: String, pageData: JsonObject): RaftProfileColumnPanes {
        val cell = raftProfileColumnCellOf(pageName)
        return withStack(cell, stack(cell).replace(pageName, pageData)).copy(last = cell)
    }

    /**
     * A page opened from a page in cell [from]: pushed there when it belongs there (the details' member list), else
     * its own cell starts over with it (a member picked in the member list: their profile, above the list).
     */
    fun openFrom(from: RaftProfileColumnCell, pageName: String, pageData: JsonObject): RaftProfileColumnPanes {
        val cell = raftProfileColumnCellOf(pageName)
        val next = if (cell == from) stack(cell).push(pageName, pageData) else stack(cell).replace(pageName, pageData)
        return withStack(cell, next).copy(last = cell)
    }

    /** Entry [id] of [cell] closed itself (its Close or `closePage`): it goes, with anything opened over it. */
    fun close(cell: RaftProfileColumnCell, id: Long): RaftProfileColumnPanes = withStack(cell, stack(cell).close(id)).settled()

    /** [cell] closes as a whole (the conversation's Settings toggle for the details). */
    fun closeCell(cell: RaftProfileColumnCell): RaftProfileColumnPanes = withStack(cell, stack(cell).cleared()).settled()

    /** Back: the top page of [backCell] goes. */
    fun back(): RaftProfileColumnPanes {
        val cell = backCell ?: return this
        return withStack(cell, stack(cell).pop()).settled()
    }

    fun cleared(): RaftProfileColumnPanes = RaftProfileColumnPanes(profile.cleared(), details.cleared(), last = null)
}

/**
 * What the profile column shows, for the conversation beside it: [details] is the details cell's first page while
 * the column is shown (null otherwise), [closeDetails] closes that cell. The conversation's Settings toggle (and its
 * name) read it: pressed while its details are open; pressed again, it closes them, as Tasks and Files do.
 */
internal class RaftProfileColumnState(val details: RaftPaneEntry?, val closeDetails: () -> Unit)

/** The profile column beside this conversation; none outside the wide shell. */
internal val LocalRaftProfileColumn = compositionLocalOf { RaftProfileColumnState(details = null, closeDetails = {}) }

/** Whether the column shows the details of channel [channelId] (pages pushed over them included). */
internal fun RaftProfileColumnState.showsChannelDetails(channelId: String?): Boolean {
    val entry = details ?: return false
    if (channelId.isNullOrBlank() || entry.pageName != CHANNEL_SETTINGS_PAGE) return false
    return (entry.pageData["channelId"] as? JsonPrimitive)?.contentOrNull == channelId
}

/**
 * The profile column of the pane grid for [cells] (top to bottom, [RaftProfileColumnPanes.cells]), each drawn by
 * [content]; null with no cell. It stands last in the row ([RaftPaneGridColumn.trailing]); its tab and seam are
 * named by its cells (web RowTabs: "Profile · Details"). [content] is composed where the grid draws the column; the
 * grid draws the latest one handed up, so it may capture what it shows.
 */
@Composable
internal fun raftProfileColumn(
    cells: List<RaftProfileColumnCell>,
    content: @Composable (RaftProfileColumnCell) -> Unit,
): RaftPaneGridColumn? {
    val profileLabel = stringResource(Res.string.member_label_profile)
    val detailsLabel = stringResource(Res.string.channel_details_title)
    val tabLabel =
        cells.joinToString(" · ") { cell ->
            when (cell) {
                RaftProfileColumnCell.Profile -> profileLabel
                RaftProfileColumnCell.Details -> detailsLabel
            }
        }
    val resizeLabel = stringResource(Res.string.thread_side_card_resize_column, tabLabel)
    if (cells.isEmpty()) return null
    return RaftPaneGridColumn(
        bounds = RaftPaneGridColumns.Profile,
        resizeLabel = resizeLabel,
        cells = cells.map { cell -> RaftPaneGridCell(id = cell.id) { content(cell) } },
        tabLabel = tabLabel,
        trailing = true,
    )
}

/**
 * One cell of the profile column: [cell]'s pages in [panes], each a child page whose `openPage` opens in the column
 * ([RaftProfileColumnPanes.openFrom], a page with a role) and whose `closePage` closes it. A conversation opened here
 * goes to [openDetail] (the main detail; false: it could not, and the page opens on the platform stack); other pages
 * without a role open on the platform stack. Pages under the top stay composed and inactive, so going back finds
 * them as they were. Back closes the last page opened in the column first ([RaftProfileColumnPanes.backCell]: only
 * that cell's handler is on), and only while the column is on screen ([LocalRaftPaneGridColumnShown]: not while it is
 * a row tab not shown or out of sight in Focus mode, where its pages are inactive too); handlers inside the page (a
 * dialog) are composed after it and win. [panes] is read when it is needed, so two Backs in a row each act on the
 * pages as they are, not as they were drawn. [page] draws an entry: the page itself ([RaftChildPage]); a test's
 * fixture.
 */
@Composable
internal fun RaftProfileColumnCellStack(
    cell: RaftProfileColumnCell,
    panes: () -> RaftProfileColumnPanes,
    active: Boolean,
    onChange: (RaftProfileColumnPanes) -> Unit,
    openDetail: (String, JsonObject) -> Boolean = { _, _ -> false },
    page: @Composable (entry: RaftPaneEntry, active: Boolean) -> Unit = { entry, pageActive ->
        val pageData = remember(entry) { entry.pageData.toKuiklyJson() }
        RaftChildPage(pageName = entry.pageName, pageData = pageData, modifier = Modifier.fillMaxSize(), active = pageActive)
    },
) {
    val currentPanes by rememberUpdatedState(panes)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOpenDetail by rememberUpdatedState(openDetail)
    val platform = LocalRaftHost.current.navigator
    val shown = LocalRaftPaneGridColumnShown.current
    val now = panes()
    RaftBackHandler(enabled = active && shown && now.backCell == cell, label = "wide_profile_column", blocksBackSwipe = false) {
        currentOnChange(currentPanes().back())
    }
    val stack = now.stack(cell)
    val first = stack.entries.firstOrNull()
    val top = stack.entries.lastOrNull()
    Box(Modifier.fillMaxSize()) {
        stack.entries.forEach { entry ->
            key(entry.id) {
                RaftPaneHost(
                    wide = true,
                    openInPane = { role, name, data ->
                        when (role) {
                            RaftPaneRole.Detail -> if (!currentOpenDetail(name, data)) platform.openPage(name, data)
                            else -> currentOnChange(currentPanes().openFrom(cell, name, data))
                        }
                    },
                    close = { currentOnChange(currentPanes().close(cell, entry.id)) },
                    roleOf = ::raftProfileColumnRole,
                ) {
                    CompositionLocalProvider(LocalRaftPaneBackCloses provides (entry == first)) {
                        page(entry, active && shown && entry == top)
                    }
                }
            }
        }
    }
}

/**
 * The wide workspace detail's row (SettingsHomePage `adaptiveDetailPane`; composed as is by RaftProfileColumnUiTest):
 * the conversation ([main]) as the grid's main pane, the side thread's column ([sideThreadColumn]) and the profile
 * column, under the conversation's host, which opens the pages with the Supporting role in the column where [wide]
 * (else on the platform stack as before), with the column's state for the conversation's Settings toggle
 * ([LocalRaftProfileColumn]). [columnOpen]: the column may show here and has pages (the shell's `profileColumnOpen`);
 * only the selected tab's pane ([tabSelected]) declares it, as the side thread. [reportHost] / [onShown]: see
 * [RaftSupportingPaneShownReport].
 *
 * Focus mode ([threadFocused]: the side thread fills the row) and the column (PR #4664 review):
 * - a page opened in the column while the thread fills the row leaves Focus ([onLeaveFocus]) so that it shows (web
 *   rowLayout `useRowPlan`: a region opening into an expanded row calls `restoreRowExpand`);
 * - Focus entered with the column open puts it out of sight: its pages stay, inert and inactive, the shell's Back
 *   does not count it as shown and its own Back is off, so Back leaves Focus first and the column comes back.
 *
 * [panes] is read where it is needed (the shell's state), so two Backs in a row each act on the pages as they are.
 */
@Composable
internal fun RaftWorkspaceDetailGrid(
    modifier: Modifier,
    wide: Boolean,
    panes: () -> RaftProfileColumnPanes,
    onPanesChange: (RaftProfileColumnPanes) -> Unit,
    columnOpen: Boolean,
    tabSelected: Boolean,
    active: Boolean,
    sideThreadColumn: RaftPaneGridColumn?,
    threadFocused: Boolean,
    onLeaveFocus: () -> Unit,
    reportHost: Boolean,
    onShown: (Boolean) -> Unit,
    openDetail: (String, JsonObject) -> Boolean = { _, _ -> false },
    page: @Composable (entry: RaftPaneEntry, active: Boolean) -> Unit = { entry, pageActive ->
        val pageData = remember(entry) { entry.pageData.toKuiklyJson() }
        RaftChildPage(pageName = entry.pageName, pageData = pageData, modifier = Modifier.fillMaxSize(), active = pageActive)
    },
    main: @Composable () -> Unit,
) {
    val filling = threadFocused && sideThreadColumn != null
    // Only the main detail pane reports whether the column is shown; out of sight in Focus it isn't.
    RaftSupportingPaneShownReport(host = reportHost, shown = columnOpen && !filling, report = onShown)
    val currentPanes by rememberUpdatedState(panes)
    val currentOnPanesChange by rememberUpdatedState(onPanesChange)
    val currentFilling by rememberUpdatedState(filling)
    val currentOnLeaveFocus by rememberUpdatedState(onLeaveFocus)
    // Every change to the column's pages goes through here: one that opens a page while the thread fills the row
    // leaves Focus first.
    val change: (RaftProfileColumnPanes) -> Unit =
        remember {
            { next: RaftProfileColumnPanes ->
                if (currentFilling && next.openedSince(currentPanes())) currentOnLeaveFocus()
                currentOnPanesChange(next)
            }
        }
    // The column's pages are the shell's, not the conversation's: they get this host, not the pane wrapper below.
    val shellHost = LocalRaftHost.current
    val now = panes()
    // A new column object each composition: the grid draws its latest cells (each grid's `fresh` state).
    val profileColumn =
        if (columnOpen && tabSelected) {
            raftProfileColumn(now.cells) { cell ->
                CompositionLocalProvider(LocalRaftHost provides shellHost) {
                    RaftProfileColumnCellStack(cell = cell, panes = panes, active = active, onChange = change, openDetail = openDetail, page = page)
                }
            }
        } else {
            null
        }
    // For the conversation's Settings toggle; kept while the details' first page is, so the conversation only
    // recomposes when it changes.
    val details = now.details.entries.firstOrNull()?.takeIf { columnOpen }
    val state =
        remember(details) {
            RaftProfileColumnState(details = details, closeDetails = { change(currentPanes().closeCell(RaftProfileColumnCell.Details)) })
        }
    RaftPaneHost(wide = wide, openInPane = { _, pageName, pageData -> change(currentPanes().open(pageName, pageData)) }) {
        CompositionLocalProvider(LocalRaftProfileColumn provides state) {
            RaftPaneGrid(
                columns = listOfNotNull(sideThreadColumn, profileColumn),
                modifier = modifier,
                fillColumn = RaftPaneGridColumns.Thread.id.takeIf { filling },
                main = main,
            )
        }
    }
}
