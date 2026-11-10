package build.raft.app.assistant

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.image.rememberRaftAppInForeground
import build.raft.app.resources.*
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// The personal assistant's otter on a composer (web packages/web/src/assistant/AssistantOtter.tsx,
// Kirby's prototype v13, RFC 075 §4.3): an otter lying on the composer frame's top edge, its lower
// body behind the frame and two paws on the edge. Its face says what the assistant is doing; a
// press opens the assistant. This file is the view only: which composer carries it, the mood's
// inputs and the panel it opens are the host's.
//
// Motion (web, Raft DS › Motion; Artea #proj-assistant:#24): one pop-in when it appears, and the
// slide into the composer while the person types or the panel is open. Asleep (nothing new) it
// breathes and z's float up from its head; something new pops it out from behind the edge with
// two small bobs, never a sideways sway (RaftAssistantOtterMotion.kt). Its loops hold while the app
// is in the background. With reduced motion nothing moves and it appears as it is.

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

/** The pose for [mood] (web `MOOD_POSE`): nothing new, it sleeps (Artea, #24); the running one while it pops in. */
fun raftOtterPose(mood: RaftOtterMood, entering: Boolean = false): RaftOtterPose =
    if (entering) {
        RaftOtterPoses.Running
    } else {
        when (mood) {
            RaftOtterMood.Idle -> RaftOtterPoses.Sleepy
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
 * 10-30: three hands otherwise); asleep (idle or away), none. Both while it pops in.
 */
fun raftOtterPaws(mood: RaftOtterMood, entering: Boolean = false): List<Dp> =
    if (entering) {
        BothPaws
    } else {
        when (mood) {
            RaftOtterMood.New, RaftOtterMood.Working -> listOf(15.dp)
            RaftOtterMood.Idle, RaftOtterMood.Away -> emptyList()
            else -> BothPaws
        }
    }

/** Whether it sleeps (web: idle or away): it breathes and z's float up, once it has popped in. */
fun raftOtterAsleep(mood: RaftOtterMood, entering: Boolean = false): Boolean =
    !entering && (mood == RaftOtterMood.Idle || mood == RaftOtterMood.Away)

/**
 * Whether its sleep loop (breath and z's) ticks every frame: asleep, the app in the foreground,
 * no reduced motion, not held still for a screenshot, and not hidden in the frame behind an open
 * assistant panel ([RaftOtterTuck.PanelOpen]; tucked for composing its head still shows).
 */
internal fun raftOtterLoopRuns(
    asleep: Boolean,
    foreground: Boolean,
    reducedMotion: Boolean,
    heldStill: Boolean,
    tuck: RaftOtterTuck,
): Boolean = asleep && foreground && !reducedMotion && !heldStill && tuck != RaftOtterTuck.PanelOpen

/** Whether it is awake for something (web `awake`: `new` / `confirm`, once it has popped in): it wakes once per waking. */
fun raftOtterAwake(mood: RaftOtterMood, entering: Boolean = false): Boolean =
    !entering && (mood == RaftOtterMood.New || mood == RaftOtterMood.Confirm)

/** Whether the state bubble shows beside its head (web: not while entering, only `new` / `confirm`). */
fun raftOtterShowsBubble(mood: RaftOtterMood, entering: Boolean = false): Boolean = raftOtterAwake(mood, entering)

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

/**
 * Web `OtterZzz`: its bottom-left `pose.w - pose.cx - 18px` right of the head's middle and
 * `pose.h - 14px` above the edge; "z", "z", "Z" at 10 / 12 / 15px, mono bold, muted.
 */
private val ZzzInset = 18.dp
private val ZzzDrop = 14.dp
private val ZzzGlyphs = listOf("z" to 10, "z" to 12, "Z" to 15)

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
 * frame) and its paws 9dp × [scale] over the frame. It lies over the message list's end and
 * takes no space there: the list neither moves nor scrolls when it comes or goes (artin 11-03),
 * a reader scrolls if it covers something.
 *
 * - [mood]: its face ([raftOtterPose]); `new` / `confirm` also show their state beside its head.
 * - [tuck]: [RaftOtterTuck.Composing] while the composer holds text or an attachment,
 *   [RaftOtterTuck.PanelOpen] while the assistant's panel is open: it slides into the frame
 *   (200ms `ease-slide`), and its paws, bubble and z's fade out. It comes back out once the
 *   message is sent or the composer cleared.
 * - [onClick]: opens the assistant. No press or hover state (the web has none).
 * - [animateEntrance]: on its first composition it runs in (running pose) and pops up over 620ms.
 *   Pass false when it comes back without being new (the web pops it in on every mount).
 * - [scale]: [RaftAssistantOtterDefaults.PhoneScale] on phones and thread panels.
 * - [lift]: how far above the frame's edge it lies: over the notices stacked on the frame (a
 *   mention's Add / Notify / Ignore card), so it covers none of their buttons (web
 *   `useNoticesLift`). It takes no room there either.
 *
 * Asleep ([raftOtterAsleep]) it breathes and z's float up from its head while the app is in the
 * foreground; woken ([raftOtterAwake]) it pops out from behind the edge and bobs twice.
 * With reduced motion ([rememberRaftReducedMotion]) there is no pop-in, the tuck snaps, it sleeps
 * still with one z and wakes in place.
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
    lift: Dp = 0.dp,
    /** Where the otter rests (its pose's box, in window coordinates, before any tuck or pop). */
    onRestBounds: ((Rect) -> Unit)? = null,
) {
    val stillAt = LocalRaftAssistantOtterStillAt.current
    // Held still (a screenshot, a preview) unless the test opts in to a moment of its motion.
    val reducedMotion = rememberRaftReducedMotion() || (stillAt == null && raftOtterHeldStill())
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
    val asleep = raftOtterAsleep(mood, entering)
    // Asleep it breathes and z's float up: only while the app is in the foreground (web: paused
    // while the page is hidden) and the otter can be seen, and never with reduced motion.
    val foreground = rememberRaftAppInForeground()
    val loopClock =
        rememberRaftOtterLoopClock(
            running =
                raftOtterLoopRuns(
                    asleep = asleep,
                    foreground = foreground,
                    reducedMotion = reducedMotion,
                    heldStill = stillAt != null,
                    tuck = tuck,
                ),
        )
    val looping by remember { derivedStateOf { loopClock.value != null } }
    val loopMillis: (() -> Long)? =
        when {
            reducedMotion -> null
            stillAt != null -> ({ stillAt })
            looping -> ({ loopClock.value ?: 0L })
            else -> null
        }
    // Something new: out from behind the edge, then two bobs, once per waking (web `assistant-otter-wake`).
    val awake = raftOtterAwake(mood, entering)
    val wake = remember { Animatable(RaftOtterWakeMillis.toFloat()) }
    LaunchedEffect(awake) {
        if (!awake || reducedMotion || stillAt != null) return@LaunchedEffect
        wake.snapTo(0f)
        wake.animateTo(RaftOtterWakeMillis.toFloat(), tween(RaftOtterWakeMillis, easing = LinearEasing))
    }
    val wakeMillis: () -> Float =
        if (stillAt != null && awake) ({ stillAt.toFloat() }) else ({ wake.value })
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
        onRestBounds = onRestBounds,
        asleep = asleep,
        loopMillis = loopMillis,
        wakeMillis = wakeMillis,
        lift = lift,
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
 * [asleep]: it breathes and shows its z's, moving by [loopMillis] (null: still, one z).
 * [wakeMillis]: how far into its waking it is ([RaftOtterWakeMillis] and on: at rest).
 * [lift]: how far above the frame's edge it lies.
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
    onRestBounds: ((Rect) -> Unit)? = null,
    asleep: Boolean = false,
    loopMillis: (() -> Long)? = null,
    wakeMillis: () -> Float = { RaftOtterWakeMillis.toFloat() },
    lift: Dp = 0.dp,
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
                                    // Before the tuck and the pop: where it rests (web `restRef`).
                                    .then(
                                        if (onRestBounds != null) {
                                            // Corner to corner through the scale, not clipped to the
                                            // frame (boundsInWindow would cut it at an ancestor's clip).
                                            Modifier.onGloballyPositioned {
                                                val size = it.size
                                                onRestBounds(
                                                    Rect(
                                                        it.localToWindow(Offset.Zero),
                                                        it.localToWindow(Offset(size.width.toFloat(), size.height.toFloat())),
                                                    ),
                                                )
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .graphicsLayer {
                                        // The waking scales about the pose's middle (web: the button's own origin).
                                        val wake = raftOtterWake(wakeMillis())
                                        translationY =
                                            tuckFraction * pose.height.toPx() + popOffset().dp.toPx() + wake.dy.dp.toPx()
                                        scaleX = wake.scale
                                        scaleY = wake.scale
                                    }
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onClick,
                                    )
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = label
                                        role = Role.Button
                                    }
                                    .then(
                                        if (asleep && loopMillis != null) {
                                            // Its breath, its belly on the edge (web `transform-origin: 50% 100%`).
                                            Modifier.graphicsLayer {
                                                val breath = raftOtterBreath(loopMillis())
                                                scaleX = breath.scaleX
                                                scaleY = breath.scaleY
                                                transformOrigin = TransformOrigin(0.5f, 1f)
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ),
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
                    if (asleep) {
                        RaftOtterZzz(loopMillis = loopMillis, edgeAlpha = { edgeAlpha })
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
                val bubblePlaceable = if (bubble != null) measurables[1 + paws.size].measure(Constraints()) else null
                val zzzPlaceable = if (asleep) measurables.last().measure(Constraints()) else null
                layout(bodyWidth.roundToPx(), bodyHeight.roundToPx()) {
                    clip.place(0, 0)
                    pawPlaceables.forEachIndexed { index, paw ->
                        paw.place(anchorX + paws[index].roundToPx(), edge + PawBelowEdge.roundToPx() - paw.height)
                    }
                    bubblePlaceable?.place(
                        x = anchorX - (pose.headX + BubbleGap).roundToPx() - bubblePlaceable.width,
                        y = edge - BubbleAboveEdge.roundToPx() - bubblePlaceable.height,
                    )
                    // Its bottom-left beside the head; the z's rise from there, over the list.
                    zzzPlaceable?.place(
                        x = anchorX + (pose.width - pose.headX - ZzzInset).roundToPx(),
                        y = edge - (pose.height - ZzzDrop).roundToPx() - zzzPlaceable.height,
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
                y = -(edgeY + lift).roundToPx(),
            )
        }
    }
}

/**
 * Asleep: three z's float up from beside its head, one after another (web `OtterZzz`), each from
 * its bottom-left corner here. Still ([loopMillis] null): the first one only, at 70%. They fade
 * with the paws while it is tucked in. Decoration only.
 */
@Composable
private fun RaftOtterZzz(loopMillis: (() -> Long)?, edgeAlpha: () -> Float) {
    val density = LocalDensity.current
    val color = RaftTheme.colors.foregroundMuted
    val mono = RaftTheme.typography.monoMeta
    Box(Modifier.clearAndSetSemantics {}) {
        val glyphs = if (loopMillis == null) ZzzGlyphs.take(1) else ZzzGlyphs
        glyphs.forEachIndexed { index, (glyph, px) ->
            // Art-sized, like the otter itself: web px, not the reader's text size.
            val size = with(density) { px.dp.toSp() }
            RaftText(
                glyph,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .graphicsLayer {
                            val z = if (loopMillis == null) RaftOtterStillZ else raftOtterZ(index, loopMillis())
                            alpha = z.alpha * edgeAlpha()
                            translationX = z.dx.dp.toPx()
                            translationY = z.dy.dp.toPx()
                            scaleX = z.scale
                            scaleY = z.scale
                        },
                style = mono.copy(fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size),
                color = color,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
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
