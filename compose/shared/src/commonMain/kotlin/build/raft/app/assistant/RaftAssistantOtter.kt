package build.raft.app.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.resources.*
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.math.ceil
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// The personal assistant's otter on a composer (web packages/web/src/assistant/AssistantOtter.tsx,
// Kirby's prototype v13, RFC 075 §4.3): an otter lying on the composer frame's top edge, its lower
// body behind the frame and two paws on the edge. Its face says what the assistant is doing; a
// press opens the assistant. This file is the view only: which composer carries it, the mood's
// inputs and the panel it opens are the host's.
//
// Motion (web, Raft DS › Motion): one pop-in when it appears, and the slide into the composer
// while the person types or the panel is open. Status never loops. With reduced motion nothing
// moves and it appears as it is.

/**
 * What the otter shows (web assistantOtterState.ts `OtterMood`):
 * - [Away]: paused by an admin, or its agent is offline;
 * - [Confirm]: an action card from it in the owner's DM waits for the owner;
 * - [New]: unread messages in that DM;
 * - [Working]: its agent is thinking or working;
 * - [Idle]: none of these.
 */
enum class RaftOtterMood { Idle, Working, New, Confirm, Away }

/**
 * The mood from its inputs, highest first (web `otterMood`): a pause outranks everything, a card
 * to confirm outranks a plain unread, an offline agent is away only when nothing else applies.
 * [working]: the agent's activity is thinking or working; [offline]: it is offline.
 */
fun raftOtterMood(paused: Boolean, confirm: Boolean, unread: Boolean, working: Boolean, offline: Boolean): RaftOtterMood =
    when {
        paused -> RaftOtterMood.Away
        confirm -> RaftOtterMood.Confirm
        unread -> RaftOtterMood.New
        working -> RaftOtterMood.Working
        offline -> RaftOtterMood.Away
        else -> RaftOtterMood.Idle
    }

/** How far the otter has gone into the composer (web `translate-y` on the otter's button). */
enum class RaftOtterTuck(
    /** Downward, as a share of the pose's own height. */
    internal val fraction: Float,
) {
    /** Lying on the edge. */
    None(0f),

    /** The composer holds text or an attachment (web `data-composing`: `translate-y-[58%]`). */
    Composing(0.58f),

    /** The assistant's panel is open (web `data-open`: `translate-y-[105%]`), all of it gone. */
    PanelOpen(1.05f),

    /**
     * A card sits on the composer's top edge, where the otter lies (a mention's Add / Notify /
     * Ignore): all of it gone like [PanelOpen], so it never covers the card's buttons (artin 11-02).
     */
    Covered(1.05f),
}

/**
 * One pose, cut where the frame's top edge crosses the otter: its size and the x of its head's
 * middle, in dp at scale 1 (web `POSES`, CSS px; the webp files are 2x).
 */
@Immutable
class RaftOtterPose internal constructor(
    val name: String,
    internal val image: DrawableResource,
    val width: Dp,
    val height: Dp,
    val headX: Dp,
) {
    override fun toString(): String = "RaftOtterPose($name)"
}

internal object RaftOtterPoses {
    val Curious = RaftOtterPose("curious", Res.drawable.assistant_otter_curious, 89.dp, 60.dp, 47.dp)
    val Wave = RaftOtterPose("wave", Res.drawable.assistant_otter_wave, 96.dp, 52.dp, 47.dp)
    val Thinking = RaftOtterPose("thinking", Res.drawable.assistant_otter_thinking, 87.dp, 62.dp, 44.dp)
    val Confused = RaftOtterPose("confused", Res.drawable.assistant_otter_confused, 93.dp, 66.dp, 50.dp)
    val Sleepy = RaftOtterPose("sleepy", Res.drawable.assistant_otter_sleepy, 135.dp, 71.dp, 65.dp)

    /** Only while it pops in (web: the running pose shows for `OTTER_ENTRY_MS`). */
    val Running = RaftOtterPose("running", Res.drawable.assistant_otter_running, 109.dp, 60.dp, 63.dp)
}

/** The pose for [mood] (web `MOOD_POSE`), or the running one while it pops in. */
fun raftOtterPose(mood: RaftOtterMood, entering: Boolean = false): RaftOtterPose =
    if (entering) {
        RaftOtterPoses.Running
    } else {
        when (mood) {
            RaftOtterMood.Idle -> RaftOtterPoses.Curious
            RaftOtterMood.Working -> RaftOtterPoses.Thinking
            RaftOtterMood.New -> RaftOtterPoses.Wave
            RaftOtterMood.Confirm -> RaftOtterPoses.Confused
            RaftOtterMood.Away -> RaftOtterPoses.Sleepy
        }
    }

/** Web `BOTH_PAWS`: each paw's left x from the head's middle. */
private val BothPaws = listOf((-37).dp, 15.dp)

/**
 * The paws on the edge, by their left x from the head's middle (web `MOOD_PAWS`): the waving one
 * has only its right paw down, and so does the thinking one, whose left paw is on its chin (artin
 * 10-30: three hands otherwise); asleep, none. Both while it pops in.
 */
fun raftOtterPaws(mood: RaftOtterMood, entering: Boolean = false): List<Dp> =
    if (entering) {
        BothPaws
    } else {
        when (mood) {
            RaftOtterMood.New, RaftOtterMood.Working -> listOf(15.dp)
            RaftOtterMood.Away -> emptyList()
            else -> BothPaws
        }
    }

/** Whether the state bubble shows beside its head (web: not while entering, only `new` / `confirm`). */
fun raftOtterShowsBubble(mood: RaftOtterMood, entering: Boolean = false): Boolean =
    !entering && (mood == RaftOtterMood.New || mood == RaftOtterMood.Confirm)

/**
 * The list-end band a host's message list keeps free while the otter lies on its composer: as tall
 * as the pose at [scale], rounded up (web `Math.ceil(pose.h * scale)`). It follows the settled pose
 * for [mood]; the web also follows the running pose for its 620ms pop-in, which here would move the
 * list end twice for a few dp.
 */
fun raftAssistantOtterBandHeight(mood: RaftOtterMood, scale: Float = RaftAssistantOtterDefaults.PhoneScale): Dp =
    // Less a hair of float error: 0.8f is a little over 0.8, and 60 x 0.8 must stay 48.
    ceil(raftOtterPose(mood).height.value.toDouble() * scale - 1e-4).dp

object RaftAssistantOtterDefaults {
    /** Phones and thread panels: 80% (web `SMALL_SCALE`, `max-md:scale-80`). */
    const val PhoneScale: Float = 0.8f

    /** A conversation's composer on a wide window (web desktop channel composer). */
    const val WideScale: Float = 1f

    /** The head's middle, from the frame's right end, on a phone (web `max-md:right-13`, 52px). */
    val PhoneEndInset: Dp = 52.dp

    /** The head's middle, from the frame's right end, wider (web `right-19`, 76px: over the send button's side). */
    val WideEndInset: Dp = 76.dp

    /**
     * How far the otter reaches below the frame's top edge at [scale]: the paws, 9dp at scale 1
     * (web paw `bottom-[-9px]`). The otter's own body stops 1dp under the edge, on the frame's line.
     */
    fun belowEdge(scale: Float = PhoneScale): Dp = PawBelowEdge * scale

    /** How far the otter rises above the frame's top edge for [mood] at [scale] (the pose, less the 2dp under the edge). */
    fun aboveEdge(mood: RaftOtterMood, scale: Float = PhoneScale): Dp = (raftOtterPose(mood).height - BodyBelowEdge) * scale
}

// ---- Geometry (scale 1; the whole otter scales about the anchor, as the web's `scale-80`) ------

/** The window around the head that clips the otter at the frame's edge (web `CLIP_HALF_WIDTH`). */
private val ClipHalfWidth = 160.dp

/** The image's bottom under the edge: web button `bottom-[-1px]` inside a clip at `bottom-[-1px]`. */
private val BodyBelowEdge = 2.dp

/** The clip's bottom under the edge (web clip `bottom-[-1px]`): the otter covers the frame's outer line. */
private val ClipBelowEdge = 1.dp

/** Room above the pose for the pop-in's overshoot (web keyframe 60%: -8px, plus the curve's overshoot). */
private val PopHeadroom = 24.dp

/** Web paw.svg: 22 x 15, `bottom-[-9px]`. */
private val PawWidth = 22.dp
private val PawHeight = 15.dp
private val PawBelowEdge = 9.dp

/** Web bubble: `bottom-3` (12px above the edge), `right: pose.cx + 6` (6px off the otter's left). */
private val BubbleAboveEdge = 12.dp
private val BubbleGap = 6.dp

// ---- Motion -----------------------------------------------------------------------------------

/** Web index.css `assistant-otter-pop`: 620ms (`OTTER_ENTRY_MS`), the running pose shows this long. */
internal const val RaftOtterEntryMillis = 620

/** The pop's keyframe at 60% (372ms): 8px above its rest, then down to rest. */
private const val PopPeakMillis = RaftOtterEntryMillis * 60 / 100
private val PopPeak = (-8).dp

/** The pop's start: `translateY(105%)` of the running pose, all of it under the edge. */
private val PopStart = RaftOtterPoses.Running.height * 1.05f

/** Web `cubic-bezier(0.3, 1.5, 0.5, 1)`, one small overshoot; CSS applies it to each keyframe segment. */
private val PopEasing: Easing = CubicBezierEasing(0.3f, 1.5f, 0.5f, 1f)

/** The slide into the composer: web `transition-[translate] duration-200 ease-slide` (raft-ui `--ease-slide`). */
private const val TuckMillis = 200

/**
 * Paws and bubble fade while tucked: web `transition-opacity duration-150`, Tailwind's default
 * curve `cubic-bezier(0.4, 0, 0.2, 1)` ([RaftMotion.EaseStandard]).
 */
private const val EdgeFadeMillis = 150

/**
 * The personal assistant's otter, lying on a composer frame's top edge (web `AssistantOtter`).
 *
 * Put it in the composer frame's own box, after the frame, as wide as the frame:
 * ```
 * Box {
 *     Composer(…)                                    // the frame starts at this box's top
 *     RaftAssistantOtter(mood, tuck, onClick = openPanel, modifier = Modifier.fillMaxWidth())
 * }
 * ```
 * It takes no height: it draws upward from its top (the frame's edge), its head's middle
 * [endInset] from its right end, its body clipped 1dp under the edge (the lower body behind the
 * frame) and its paws 9dp × [scale] over the frame. Pair it with [RaftAssistantOtterListEndBand]
 * at the end of that conversation's message list so it never covers the last message.
 *
 * - [mood]: its face ([raftOtterPose]); `new` / `confirm` also show their state beside its head.
 * - [tuck]: [RaftOtterTuck.Composing] while the composer holds text or an attachment,
 *   [RaftOtterTuck.PanelOpen] while the assistant's panel is open, [RaftOtterTuck.Covered] while a
 *   card sits above the composer: it slides into the frame
 *   (200ms `ease-slide`), and its paws and bubble fade out.
 * - [onClick]: opens the assistant. No press or hover state (the web has none).
 * - [animateEntrance]: on its first composition it runs in (running pose) and pops up over 620ms.
 *   Pass false when it comes back without being new (the web pops it in on every mount).
 * - [scale]: [RaftAssistantOtterDefaults.PhoneScale] on phones and thread panels.
 *
 * With reduced motion ([rememberRaftReducedMotion]) there is no pop-in and the tuck snaps.
 * Screen readers get one button, "Open Assistant" or "Open Assistant: <state>".
 */
@Composable
fun RaftAssistantOtter(
    mood: RaftOtterMood,
    tuck: RaftOtterTuck,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = RaftAssistantOtterDefaults.PhoneScale,
    endInset: Dp = RaftAssistantOtterDefaults.PhoneEndInset,
    animateEntrance: Boolean = true,
) {
    val reducedMotion = rememberRaftReducedMotion()
    // First appearance on this composer: it runs in and pops up, then settles.
    var entering by remember { mutableStateOf(animateEntrance && !reducedMotion) }
    // The pop's translation in dp, from 105% of the running pose's height under its rest.
    val pop = remember { Animatable(if (entering) PopStart.value else 0f) }
    val pose = raftOtterPose(mood, entering)
    LaunchedEffect(entering) {
        if (!entering) return@LaunchedEffect
        // translateY(105%) -> -8px at 60% -> 0, the curve on each segment as CSS keyframes do.
        pop.animateTo(PopPeak.value, tween(PopPeakMillis, easing = PopEasing))
        pop.animateTo(0f, tween(RaftOtterEntryMillis - PopPeakMillis, easing = PopEasing))
        entering = false
    }
    val label =
        when (val state = raftOtterStateLabel(mood)) {
            null -> stringResource(Res.string.assistant_otter_open)
            else -> stringResource(Res.string.assistant_otter_open_with_state, state)
        }
    RaftAssistantOtterLayout(
        pose = pose,
        paws = raftOtterPaws(mood, entering),
        bubble = if (raftOtterShowsBubble(mood, entering)) raftOtterStateLabel(mood) else null,
        bubbleDot = mood == RaftOtterMood.New,
        tuck = tuck,
        label = label,
        onClick = onClick,
        scale = scale,
        endInset = endInset,
        reducedMotion = reducedMotion,
        popOffset = { pop.value },
        modifier = modifier,
    )
}

/** The state's words (web `MOOD_STATE_ID`), none when idle. */
@Composable
private fun raftOtterStateLabel(mood: RaftOtterMood): String? =
    when (mood) {
        RaftOtterMood.Idle -> null
        RaftOtterMood.Working -> stringResource(Res.string.assistant_otter_state_working)
        RaftOtterMood.New -> stringResource(Res.string.assistant_otter_state_new)
        RaftOtterMood.Confirm -> stringResource(Res.string.assistant_otter_state_confirm)
        RaftOtterMood.Away -> stringResource(Res.string.assistant_otter_state_away)
    }

/**
 * The otter at one [pose], without its own entry state (tests draw the running pose with it).
 * [popOffset]: the pop-in's downward translation in dp, read at draw time.
 */
@Composable
internal fun RaftAssistantOtterLayout(
    pose: RaftOtterPose,
    paws: List<Dp>,
    bubble: String?,
    bubbleDot: Boolean,
    tuck: RaftOtterTuck,
    label: String,
    onClick: () -> Unit,
    scale: Float,
    endInset: Dp,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    popOffset: () -> Float = { 0f },
) {
    val tuckFraction by animateFloatAsState(
        targetValue = tuck.fraction,
        animationSpec = if (reducedMotion) snap() else tween(TuckMillis, easing = RaftMotion.EaseSlide),
        label = "otterTuck",
    )
    val edgeAlpha by animateFloatAsState(
        targetValue = if (tuck == RaftOtterTuck.None) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(EdgeFadeMillis, easing = RaftMotion.EaseStandard),
        label = "otterEdgeAlpha",
    )
    val pawPainter = rememberVectorPainter(RaftOtterPaw)
    val colors = RaftTheme.colors
    val edgeY = PopHeadroom + pose.height - BodyBelowEdge
    val bodyWidth = ClipHalfWidth * 2
    val bodyHeight = edgeY + PawBelowEdge
    Layout(
        modifier = modifier,
        content = {
            // The otter's own box at scale 1, scaled about the anchor (the head's middle on the edge).
            Layout(
                modifier =
                    Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, edgeY / bodyHeight)
                    },
                content = {
                    // The clip: nothing of the otter shows below the frame's top edge (+1dp, its line).
                    Box(Modifier.clipToBounds()) {
                        Image(
                            painter = painterResource(pose.image),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier =
                                Modifier
                                    .padding(start = ClipHalfWidth - pose.headX, top = PopHeadroom)
                                    .size(pose.width, pose.height)
                                    .graphicsLayer {
                                        translationY = tuckFraction * pose.height.toPx() + popOffset().dp.toPx()
                                    }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onClick,
                                    )
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = label
                                        role = Role.Button
                                    },
                        )
                    }
                    paws.forEach { _ ->
                        Image(
                            painter = pawPainter,
                            contentDescription = null,
                            modifier = Modifier.size(PawWidth, PawHeight).graphicsLayer { alpha = edgeAlpha },
                        )
                    }
                    if (bubble != null) {
                        // Beside its head, inside the band the list keeps free. Decoration: the
                        // button's label already says it.
                        Row(
                            modifier =
                                Modifier
                                    .clearAndSetSemantics {}
                                    .graphicsLayer { alpha = edgeAlpha }
                                    .background(colors.layerPopover, RoundedCornerShape(RaftRadius.Control))
                                    .border(RaftStroke.Hairline, colors.lineStrong, RoundedCornerShape(RaftRadius.Control))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (bubbleDot) {
                                Box(Modifier.size(6.dp).background(RaftUiPalette.attention, RoundedCornerShape(2.dp)))
                            }
                            RaftText(bubble, style = RaftTheme.typography.footnote, color = colors.foreground, maxLines = 1, softWrap = false)
                        }
                    }
                },
            ) { measurables, _ ->
                val anchorX = ClipHalfWidth.roundToPx()
                val edge = edgeY.roundToPx()
                val clip =
                    measurables[0].measure(
                        Constraints.fixed(bodyWidth.roundToPx(), edge + ClipBelowEdge.roundToPx()),
                    )
                val pawPlaceables = measurables.subList(1, 1 + paws.size).map { it.measure(Constraints()) }
                val bubblePlaceable = measurables.getOrNull(1 + paws.size)?.measure(Constraints())
                layout(bodyWidth.roundToPx(), bodyHeight.roundToPx()) {
                    clip.place(0, 0)
                    pawPlaceables.forEachIndexed { index, paw ->
                        paw.place(anchorX + paws[index].roundToPx(), edge + PawBelowEdge.roundToPx() - paw.height)
                    }
                    bubblePlaceable?.place(
                        x = anchorX - (pose.headX + BubbleGap).roundToPx() - bubblePlaceable.width,
                        y = edge - BubbleAboveEdge.roundToPx() - bubblePlaceable.height,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        // No height of its own: the anchor is a point on the frame's top edge, endInset from the end.
        val body = measurables.single().measure(Constraints())
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else body.width
        layout(width, constraints.minHeight) {
            body.place(
                x = width - endInset.roundToPx() - ClipHalfWidth.roundToPx(),
                y = -edgeY.roundToPx(),
            )
        }
    }
}

/**
 * Goes at the end of the host's message list (web `AssistantOtterListEnd`): while the otter lies
 * on this conversation's composer, the list ends a full-width band as tall as the otter higher,
 * so a reader at the bottom sees the last message whole above it and no content gets narrower.
 * Don't compose it while that composer has no otter.
 */
@Composable
fun RaftAssistantOtterListEndBand(
    mood: RaftOtterMood,
    modifier: Modifier = Modifier,
    scale: Float = RaftAssistantOtterDefaults.PhoneScale,
) {
    Spacer(modifier.fillMaxWidth().height(raftAssistantOtterBandHeight(mood, scale)).clearAndSetSemantics {})
}

/** Web otter/paw.svg (22 x 15): fixed otter art, the same in light and dark. */
private val RaftOtterPaw: ImageVector by lazy {
    ImageVector.Builder(
        name = "RaftOtterPaw",
        defaultWidth = PawWidth,
        defaultHeight = PawHeight,
        viewportWidth = 22f,
        viewportHeight = 15f,
    ).apply {
        addPath(
            pathData = addPathNodes("M9 1H13A8 8 0 0 1 21 9A5 5 0 0 1 16 14H6A5 5 0 0 1 1 9A8 8 0 0 1 9 1Z"),
            fill = SolidColor(Color(0xFF795033)), // raft-color: otter art (paw.svg fill)
            stroke = SolidColor(Color(0xFF1E1A16)), // raft-color: otter art (paw.svg outline)
            strokeLineWidth = 2f,
        )
        addPath(
            pathData = addPathNodes("M7.75 5.5V9.5M14.25 5.5V9.5"),
            stroke = SolidColor(Color(0xFF55341C)), // raft-color: otter art (paw.svg toes)
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
        )
    }.build()
}
