package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.compat.pager.RaftPaneRole
import build.raft.app.data.RAFT_SIDE_THREAD_DIVIDER_DP
import kotlinx.serialization.json.JsonObject

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

/** One page open in a shell pane; [id] keys its composition. */
internal data class RaftPaneEntry(val id: Long, val pageName: String, val pageData: JsonObject)

/** The supporting pane's own page stack: the last entry shows, Back pops it. */
internal data class RaftPaneStack(val entries: List<RaftPaneEntry> = emptyList(), private val nextId: Long = 0) {
    val isOpen: Boolean get() = entries.isNotEmpty()

    /** A page opened from inside the pane: pushed on it. */
    fun push(pageName: String, pageData: JsonObject): RaftPaneStack =
        RaftPaneStack(entries + RaftPaneEntry(nextId, pageName, pageData), nextId + 1)

    /** A page opened from the conversation: the pane starts over with it (web: one drawer). */
    fun replace(pageName: String, pageData: JsonObject): RaftPaneStack =
        RaftPaneStack(listOf(RaftPaneEntry(nextId, pageName, pageData)), nextId + 1)

    fun pop(): RaftPaneStack = copy(entries = entries.dropLast(1))

    /** Entry [id] closed itself (its `closePage`): it goes, with anything opened over it. */
    fun close(id: Long): RaftPaneStack = copy(entries = entries.takeWhile { it.id != id })

    fun cleared(): RaftPaneStack = copy(entries = emptyList())
}

/** What a wide Back (system Back, Esc, Cmd+[, the top bar's Back) does first. */
internal enum class RaftWideBackStep { PopSupportingPane, Existing }

/**
 * [shown] is whether the pane is on screen (the same condition that draws it). Pages can sit in the
 * stack while it isn't, e.g. under the task overlay; Back must not spend a press closing it unseen.
 */
internal fun raftWideBackStep(supporting: RaftPaneStack, shown: Boolean): RaftWideBackStep =
    if (shown && supporting.isOpen) RaftWideBackStep.PopSupportingPane else RaftWideBackStep.Existing

/**
 * The shell's wide Back: an open supporting pane is the topmost thing, so Back closes it before
 * stepping the history; the task overlay sits above it, so while [overlayOpen] Back is the
 * overlay's (Tenny, #3953). [paneShown] is what [RaftSupportingPaneShownReport] last reported.
 */
internal fun raftWideBackStep(supporting: RaftPaneStack, paneShown: Boolean, overlayOpen: Boolean): RaftWideBackStep =
    raftWideBackStep(supporting, shown = paneShown && !overlayOpen)

/**
 * Reports through [report] whether the supporting pane is [shown] in this detail pane. Only the
 * pane that hosts it ([host]: the main detail, not the task overlay's copy) reports, so the
 * overlay's copy leaving the composition when the overlay closes does not reset it (Tenny, #3953).
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
 * Gives [content]'s pages a [RaftPaneNavigator] over the current host's navigator; the rest of
 * the host (bridge, events, capabilities) is shared. [close] null: `closePage` stays the platform's.
 */
@Composable
internal fun RaftPaneHost(
    wide: Boolean,
    openInPane: (RaftPaneRole, String, JsonObject) -> Unit,
    close: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val parent = LocalRaftHost.current
    val currentWide by rememberUpdatedState(wide)
    val currentOpenInPane by rememberUpdatedState(openInPane)
    val currentClose by rememberUpdatedState(close)
    val host =
        remember(parent) {
            RaftCmpHost(
                capabilities = parent.capabilities,
                navigator =
                    RaftPaneNavigator(
                        platform = parent.navigator,
                        wide = { currentWide },
                        openInPane = { role, name, data -> currentOpenInPane(role, name, data) },
                        close = { currentClose?.invoke() ?: parent.navigator.closePage() },
                    ),
                bridgeTransport = parent.bridgeTransport,
                events = parent.events,
                childPageLoads = parent.childPageLoads,
            )
        }
    CompositionLocalProvider(LocalRaftHost provides host, content = content)
}

/**
 * True on the first page of the supporting pane, where the page's Back closes the pane: its head
 * shows the leading Close (×, web drawer) instead of the Back arrow (RaftTopPageHeader).
 */
internal val LocalRaftPaneBackCloses = staticCompositionLocalOf { false }

/** Web ChannelSettingsSheet: the drawer is at most 420 wide. */
internal const val RAFT_SUPPORTING_PANE_MAX_DP = 420f
internal const val RAFT_SUPPORTING_PANE_MIN_DP = 320f

/** The conversation beside the pane keeps the wide detail's minimum (RaftListPaneWidths). */
internal const val RAFT_SUPPORTING_PANE_MIN_DETAIL_DP = 360f

/** The supporting pane's width in a detail slot [availableDp] wide, and whether it sits [beside] the conversation or over its right edge. */
internal data class RaftSupportingPaneSize(val widthDp: Float, val beside: Boolean)

internal fun raftSupportingPaneSize(availableDp: Float): RaftSupportingPaneSize {
    val besideWidth =
        (availableDp - RAFT_SUPPORTING_PANE_MIN_DETAIL_DP).coerceAtMost(RAFT_SUPPORTING_PANE_MAX_DP)
    return if (besideWidth >= RAFT_SUPPORTING_PANE_MIN_DP) {
        RaftSupportingPaneSize(besideWidth, beside = true)
    } else {
        RaftSupportingPaneSize(availableDp.coerceAtMost(RAFT_SUPPORTING_PANE_MAX_DP), beside = false)
    }
}

/**
 * The wide detail slot with a supporting pane on its right: [detail] narrows to make room when
 * [raftSupportingPaneSize] fits it beside, else the pane lies over its right edge. [detail] keeps
 * its place in the composition whether the pane is open or not, so the conversation never remounts.
 */
@Composable
internal fun RaftSupportingPaneLayout(
    modifier: Modifier,
    supporting: (@Composable () -> Unit)?,
    detail: @Composable (Modifier) -> Unit,
) {
    Layout(
        contents =
            listOf(
                { detail(Modifier.fillMaxSize()) },
                {
                    if (supporting != null) {
                        Row(Modifier.fillMaxSize()) {
                            // raft-ui: one line-seam between regions, as the side thread's
                            Spacer(Modifier.width(RAFT_SIDE_THREAD_DIVIDER_DP.dp).fillMaxHeight().background(RaftColor.BorderSeam))
                            Box(Modifier.weight(1f).fillMaxHeight()) { supporting() }
                        }
                    }
                },
            ),
        modifier = modifier,
    ) { (detailMeasurables, supportingMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val size = raftSupportingPaneSize(width.toDp().value).takeIf { supportingMeasurables.isNotEmpty() }
        val paneWidth = size?.let { it.widthDp.dp.roundToPx().coerceAtMost(width) } ?: 0
        val detailWidth = if (size?.beside == true) width - paneWidth else width
        val detailPlaceables = detailMeasurables.map { it.measure(Constraints.fixed(detailWidth, height)) }
        val panePlaceables = supportingMeasurables.map { it.measure(Constraints.fixed(paneWidth, height)) }
        layout(width, height) {
            detailPlaceables.forEach { it.place(0, 0) }
            panePlaceables.forEach { it.place(width - paneWidth, 0) }
        }
    }
}

/**
 * The supporting pane's pages: each a child page whose `openPage` pushes onto [stack] (a page
 * with a role) and whose `closePage` closes it. Pages under the top stay composed, unplaced and
 * inactive, so going back finds them as they were. Back pops the top first ([raftWideBackStep]);
 * handlers inside the page (a dialog) are composed after it and win.
 */
@Composable
internal fun RaftSupportingPaneStack(
    stack: RaftPaneStack,
    active: Boolean,
    onStackChange: (RaftPaneStack) -> Unit,
) {
    val currentStack by rememberUpdatedState(stack)
    RaftBackHandler(enabled = active && stack.isOpen, label = "wide_supporting_pane", blocksBackSwipe = false) {
        onStackChange(currentStack.pop())
    }
    val top = stack.entries.lastOrNull()
    stack.entries.forEach { entry ->
        key(entry.id) {
            RaftPaneHost(
                wide = true,
                openInPane = { _, name, data -> onStackChange(currentStack.push(name, data)) },
                close = { onStackChange(currentStack.close(entry.id)) },
            ) {
                val pageData = remember(entry) { entry.pageData.toKuiklyJson() }
                CompositionLocalProvider(LocalRaftPaneBackCloses provides (entry == stack.entries.first())) {
                    RaftChildPage(
                        pageName = entry.pageName,
                        pageData = pageData,
                        modifier = Modifier.fillMaxSize(),
                        active = active && entry == top,
                    )
                }
            }
        }
    }
}
