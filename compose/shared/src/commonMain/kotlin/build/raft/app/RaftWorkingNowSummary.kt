package build.raft.app

import build.raft.app.resources.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentActivitySubscriptions
import build.raft.app.data.RaftDisplayPreferenceRuntime
import build.raft.app.data.RaftLiveAgentActivityItem
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberKind
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftPanelAction
import build.raft.designsystem.components.RaftPopoverSurface
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * What "who is working" reads for the current server, provided by the app shell: the live list
 * ([build.raft.app.data.SharedLiveAgentActivityStore]), the status source the rows' lights and
 * text read, the subscriptions for their detail while Working now is open, and a way to an
 * agent's page. Absent (a fixture, a page outside the shell): no summary.
 */
internal class RaftWorkingNowSource(
    val serverId: String,
    val items: StateFlow<List<RaftLiveAgentActivityItem>>,
    val agentStatusResolver: RaftAgentStatusResolver,
    val subscriptions: AgentActivitySubscriptions?,
    val onOpenAgent: (agentId: String) -> Unit,
)

internal val LocalRaftWorkingNow = staticCompositionLocalOf<RaftWorkingNowSource?> { null }

/** DS AgentActivity: at most 3 faces in the summary; the rest only in the count. */
internal const val RaftWorkingNowMaxFaces = 3

/** The faces a summary shows: the first [RaftWorkingNowMaxFaces] agents, in the order they started. */
internal fun List<RaftLiveAgentActivityItem>.workingNowFaces(): List<RaftLiveAgentActivityItem> = take(RaftWorkingNowMaxFaces)

/**
 * Who is working right now (Web `WorkingNowSummary` `count`, raft-design people-and-agents/
 * working-agents.md "The summary and Working now"): up to 3 faces and the count, at the right of
 * the wide top bar and of the phone's Home bar. It opens Working now. Nothing while nobody is
 * working, or while Settings › Appearance › Live agent activity is off.
 *
 * [onFrame]: on the frame (both places today), with the frame's ink.
 */
@Composable
internal fun RaftWorkingNowSummary(
    modifier: Modifier = Modifier,
    onFrame: Boolean = true,
    height: Dp = RaftWorkingNowSummaryHeight,
) {
    val source = LocalRaftWorkingNow.current ?: return
    if (!RaftDisplayPreferenceRuntime.showLiveAgentActivity) return
    val items by source.items.collectAsState()
    if (items.isEmpty()) return
    RaftWorkingNowSummaryContent(
        items = items,
        source = source,
        onFrame = onFrame,
        height = height,
        modifier = modifier,
    )
}

/** The summary drawn from [items] (non-empty); the screenshot cases draw it from fixtures. */
@Composable
internal fun RaftWorkingNowSummaryContent(
    items: List<RaftLiveAgentActivityItem>,
    source: RaftWorkingNowSource?,
    onFrame: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val anchorRef = remember { RaftLiveRootAnchorRef(identity = "working-now-summary") }
    var anchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }
    val summary = raftWorkingNowSummaryText(items.size)
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        Box(modifier.raftCaptureLiveRootAnchor(anchorRef)) {
            RaftPanelAction(
                onClick = {
                    anchorResolver = anchorRef.resolver(RaftPopoverLocalPoint.BottomEnd)
                    open = !open
                },
                open = open,
                // Web: the trigger's name is the sentence ("2 agents working"); the faces are hidden.
                modifier = Modifier.height(height).semantics { contentDescription = summary },
            ) {
                Row(
                    // Web `min-h-8 gap-1.5 rounded-control px-1.5`.
                    modifier = Modifier.padding(horizontal = 6.dp).clearAndSetSemantics {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RaftWorkingFaces(items.workingNowFaces())
                    // Web `font-mono text-mono-meta`: the count alone.
                    RaftText(
                        text = items.size.toString(),
                        style = RaftTheme.typography.monoMeta,
                        color = if (onFrame) colors.foregroundOnFrame else colors.foreground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    if (open) {
        val anchor = anchorResolver?.resolveCurrentPlacement()
        if (anchor == null) {
            // The summary left the screen under the popover: close it.
            LaunchedEffect(Unit) { open = false }
        } else {
            RaftWorkingNowPopover(
                anchor = anchor,
                items = items,
                source = source,
                onDismiss = { open = false },
            )
        }
    }
}

/**
 * The stack of agents at work (DS AgentActivity `rf-stack`): up to three 19dp (`xs`) faces, each
 * laid 5dp over the one before, no ring. Decorative: the count or sentence beside it says who.
 * Explicit offsets: negative Row spacing does not overlap native Kuikly children.
 */
@Composable
internal fun RaftWorkingFaces(items: List<RaftLiveAgentActivityItem>, modifier: Modifier = Modifier) {
    val face = RaftAvatarSize.Xs.edge
    val step = face - RaftWorkingFacesOverlap
    Box(modifier.width(face + step * (items.size - 1).coerceAtLeast(0)).height(face)) {
        items.forEachIndexed { index, item ->
            // Keyed by agent: a face already shown keeps its place (and does not come in again).
            key(item.agentId) {
                Box(Modifier.offset(x = step * index).zIndex(index.toFloat()).raftWorkingFaceEntrance()) {
                    RaftMemberAvatar(
                    identity = RaftMemberAvatarIdentity(kind = MemberKind.Agent, memberId = item.agentId, serverId = null),
                    label = item.name,
                    avatarSize = face,
                    carriedAvatarWireValue = item.avatarWireValue,
                        resolver = EmptyRaftMemberAvatarResolver,
                    )
                }
            }
        }
    }
}

/**
 * A face that joins the stack slides in from [Offset] toward the start and fades in, over
 * [DurationMillis] on the DS slide curve (Web `WorkingFaces`: `motion-safe:transition-[opacity,translate]
 * duration-200 ease-slide starting:-translate-x-1 starting:opacity-0`; DS: 180-220ms). It leaves
 * at once, as on the Web; with reduced motion it simply appears.
 */
internal object RaftWorkingFaceEntrance {
    const val DurationMillis: Int = 200

    /** Web `-translate-x-1` (0.25rem): a physical left shift, in RTL too. */
    val Offset: Dp = 4.dp

    val Spec: TweenSpec<Float> = tween(durationMillis = DurationMillis, easing = RaftMotion.EaseSlide)

    /** Alpha and horizontal shift (dp) at [progress] 0 (start) to 1 (in place). */
    fun frame(progress: Float): Pair<Float, Float> = progress to (progress - 1f) * Offset.value
}

@Composable
private fun Modifier.raftWorkingFaceEntrance(): Modifier {
    val reducedMotion = rememberRaftReducedMotion()
    val progress = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, RaftWorkingFaceEntrance.Spec) }
    return graphicsLayer {
        val (alpha, shift) = RaftWorkingFaceEntrance.frame(progress.value)
        this.alpha = alpha
        translationX = shift.dp.toPx()
    }
}

/** "1 agent working" / "2 agents working" (Web `layout.workingNow.summary`). */
@Composable
internal fun raftWorkingNowSummaryText(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.layout_working_now_summary_one, count)
    } else {
        stringResource(Res.string.layout_working_now_summary_other, count)
    }

@Composable
private fun RaftWorkingNowPopover(
    anchor: RaftPopoverAnchor,
    items: List<RaftLiveAgentActivityItem>,
    source: RaftWorkingNowSource?,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    var measured by remember { mutableStateOf(Size.Zero) }
    val estimatedHeight = 44.dp + 12.dp + RaftWorkingNowRowEstimate * items.size.coerceAtMost(7)
    val size = if (measured != Size.Zero) measured else with(density) { Size(RaftWorkingNowWidth.toPx(), estimatedHeight.toPx()) }
    // What each of them is doing: their detail while the list is open (Web `AgentActivitySubscription`
    // inside the popover). The same subscribe the DM, profile and member list send for the agents they show.
    if (source?.subscriptions != null) {
        RaftAgentActivitySubscriptionEffect(
            subscriptions = source.subscriptions,
            serverId = source.serverId,
            agentIds = remember(items) { items.map { it.agentId } },
        )
    }
    // DS: a Popover 300 wide, aligned to the end of the summary, on a phone too.
    RaftModalAnchoredPopover(
        anchor = anchor,
        contentSize = size,
        onDismiss = onDismiss,
        horizontalOffset = -size.width,
        verticalOffset = with(density) { 4.dp.toPx() },
        viewportGap = with(density) { 8.dp.toPx() },
        verticalPlacement = RaftFloatingVerticalPlacement.Below,
        dismissActivationDelayMillis = 0L,
    ) {
        Box(Modifier.onSizeChanged { measured = Size(it.width.toFloat(), it.height.toFloat()) }) {
            RaftWorkingNowPanel(
                items = items,
                agentStatusResolver = source?.agentStatusResolver ?: EmptyRaftAgentStatusResolver,
                onOpenAgent = { agentId ->
                    onDismiss()
                    source?.onOpenAgent?.invoke(agentId)
                },
            )
        }
    }
}

/**
 * Working now (Web `WorkingNowList`): the title, then one row per working agent in the order they
 * started: its 27dp (`sm`) avatar with its light, its name in bold and what it is doing on up to
 * 2 lines. A row opens the agent's page.
 */
@Composable
internal fun RaftWorkingNowPanel(
    items: List<RaftLiveAgentActivityItem>,
    agentStatusResolver: RaftAgentStatusResolver,
    onOpenAgent: (String) -> Unit,
) {
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        RaftPopoverSurface(
            modifier = Modifier.clickable { },
            width = RaftWorkingNowWidth,
            contentPadding = PaddingValues(0.dp),
            header = {
                // `flex min-h-11 items-center border-b border-line-seam px-4 text-headline`
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    RaftText(stringResource(Res.string.layout_working_now_title), style = typography.headline, color = colors.foreground)
                }
                Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(colors.lineSeam))
            },
        ) {
            // `max-h-[min(60vh,420px)] overflow-y-auto py-1.5`
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)
            ) {
                items.forEach { item ->
                    RaftWorkingNowRow(item, agentStatusResolver, onOpenAgent)
                }
            }
        }
    }
}

@Composable
private fun RaftWorkingNowRow(
    item: RaftLiveAgentActivityItem,
    agentStatusResolver: RaftAgentStatusResolver,
    onOpenAgent: (String) -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val indicator = rememberRaftAgentStatusIndicatorState(agentStatusResolver, item.agentId)
    // What it is doing, as the Home DM row says it (Web `formatActivityText`): the detail when the
    // runtime gave one, else Thinking… / Working….
    val text =
        raftHomeDmAgentLiveActivity(indicator)?.localizedText()
            ?: stringResource(
                if (item.status == AgentStatus.Thinking) Res.string.home_dm_agent_activity_thinking
                else Res.string.home_dm_agent_activity_working
            )
    // Web `presence={activity === "error" ? "error" : "busy"}`: the still yellow light while it works.
    val light = if (indicator?.status == AgentStatus.Error) RaftColor.PresenceError else RaftColor.PresenceBusy
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onOpenAgent(item.agentId) }
                // `flex items-start gap-3 px-4 py-2`
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RaftAvatarWithStatus(
            statusColor = light,
            avatarSize = RaftAvatarSize.Sm.edge,
            cutoutColor = colors.layerPopover,
        ) {
            RaftMemberAvatar(
                identity = RaftMemberAvatarIdentity(kind = MemberKind.Agent, memberId = item.agentId, serverId = null),
                label = item.name,
                avatarSize = RaftAvatarSize.Sm.edge,
                carriedAvatarWireValue = item.avatarWireValue,
                resolver = EmptyRaftMemberAvatarResolver,
            )
        }
        Column(Modifier.weight(1f)) {
            RaftText(
                item.name,
                style = typography.callout.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RaftText(
                text,
                style = typography.footnote,
                color = colors.foregroundMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Web `min-h-8`: the summary's height on the Home bar; the wide top bar's items are 36dp (`h-9`). */
internal val RaftWorkingNowSummaryHeight = 32.dp
internal val RaftWorkingNowTopBarHeight = 36.dp
private val RaftWorkingFacesOverlap = 5.dp
private val RaftWorkingNowWidth = 300.dp
private val RaftWorkingNowRowEstimate = 58.dp
