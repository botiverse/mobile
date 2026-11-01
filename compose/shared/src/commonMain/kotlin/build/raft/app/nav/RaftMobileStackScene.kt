package build.raft.app.nav

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * The phone window's scene of [RaftPageStack]: the top entry full window, every entry under it
 * still composed at 0x0 (kept alive, like an Activity under another one; [RaftMobilePageStackDisplay]
 * pauses them), and one scene per top entry so pushes and pops animate in `NavDisplay`.
 *
 * Motion lives on the top entry ([Modifier.animateEnterExit]), the scene transition itself is none:
 * a pushed page slides in from the end edge over the page it was opened from, which this scene
 * draws full window under it until the push settles (the scene being left draws nothing: Navigation
 * 3 renders a shared entry only in the newer scene). On a pop, and while a predictive back gesture
 * drags (Android 14+), the leaving page slides out over the previous scene, whose top page is the
 * only other one drawn. So at most two pages are drawn at any time.
 */
class RaftMobileStackScene<T : Any>(
    override val entries: List<NavEntry<T>>,
    private val motion: RaftMobileStackMotion,
) : Scene<T> {
    init {
        require(entries.isNotEmpty()) { "RaftMobileStackScene needs at least one entry" }
    }

    override val key: Any = entries.last().contentKey

    override val previousEntries: List<NavEntry<T>> = entries.dropLast(1)

    override val content: @Composable () -> Unit = {
        val top = entries.last()
        val below = entries.getOrNull(entries.lastIndex - 1)
        val scope = LocalNavAnimatedContentScope.current
        val pushed = motion.isPushed(top.contentKey)
        val entering =
            scope.transition.targetState == EnterExitState.Visible &&
                scope.transition.currentState != EnterExitState.Visible
        entries.forEach { entry ->
            key(entry.contentKey) {
                val modifier =
                    when {
                        entry === top ->
                            with(scope) {
                                Modifier.fillMaxSize().animateEnterExit(
                                    enter = if (pushed) RaftMobileStackPushEnter else EnterTransition.None,
                                    exit = RaftMobileStackPopExit,
                                )
                            }
                        entry === below && pushed && entering -> Modifier.fillMaxSize()
                        else -> Modifier.size(0.dp)
                    }
                Box(modifier) { entry.Content() }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is RaftMobileStackScene<*> && entries == other.entries)

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "RaftMobileStackScene(entries=${entries.map { it.contentKey }})"
}

/** [RaftMobileStackScene] for any non-empty back stack. */
class RaftMobileStackSceneStrategy<T : Any>(private val motion: RaftMobileStackMotion) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? =
        if (entries.isEmpty()) null else RaftMobileStackScene(entries, motion)
}

/**
 * Which pages the last stack change brought in: a scene whose top page was not on the stack before
 * that change is a push (its page slides in); any other scene entering is a pop or the previous
 * scene of a back gesture (its page is already in place). Plain fields, updated in composition
 * before the scenes read them.
 */
class RaftMobileStackMotion {
    private var current: List<Any> = emptyList()
    private var beforeLastChange: Set<Any> = emptySet()

    fun update(contentKeys: List<Any>) {
        if (contentKeys == current) return
        beforeLastChange = current.toSet()
        current = contentKeys
    }

    fun isPushed(contentKey: Any): Boolean = contentKey !in beforeLastChange
}

private const val RaftMobileStackMotionMillis = 300

private val RaftMobileStackPushEnter: EnterTransition =
    slideInHorizontally(tween(RaftMobileStackMotionMillis, easing = FastOutSlowInEasing)) { width -> width }

private val RaftMobileStackPopExit: ExitTransition =
    slideOutHorizontally(tween(RaftMobileStackMotionMillis, easing = FastOutSlowInEasing)) { width -> width }

/** The scene-level transform: none, the top entry animates itself (see [RaftMobileStackScene]). */
internal val RaftMobileStackSceneTransform = EnterTransition.None togetherWith ExitTransition.None
