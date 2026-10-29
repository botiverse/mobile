package build.raft.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.app.compat.pager.RaftPaneRole

/**
 * The desktop window's one scene: the whole back stack, the top entry drawn full-window and every
 * entry under it still composed at 0x0. That is the page model the shared pages are built for (an
 * Android Activity under another keeps its state, ViewModels and subscriptions), so pages under the
 * top one keep theirs; Navigation 3's single-pane scene would drop them from composition.
 *
 * The scene key is the bottom entry: pushing and popping stay one scene (no scene transition); the
 * app shell restarting the stack is a new one.
 */
internal class RaftDesktopStackScene<T : Any>(override val entries: List<NavEntry<T>>) : Scene<T> {
    init {
        require(entries.isNotEmpty()) { "RaftDesktopStackScene needs at least one entry" }
    }

    override val key: Any = entries.first().contentKey

    override val previousEntries: List<NavEntry<T>> = entries.dropLast(1)

    override val content: @Composable () -> Unit = {
        val topKey = entries.last().contentKey
        entries.forEach { entry ->
            key(entry.contentKey) {
                Box(if (entry.contentKey == topKey) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
                    entry.Content()
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is RaftDesktopStackScene<*> && entries == other.entries)

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "RaftDesktopStackScene(entries=${entries.map { it.contentKey }})"
}

/** [RaftDesktopStackScene] for any non-empty back stack. */
internal class RaftDesktopStackSceneStrategy<T : Any> : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? =
        if (entries.isEmpty()) null else RaftDesktopStackScene(entries)
}

/**
 * The page's [RaftPaneRole] (#3953) as Navigation 3 entry metadata. The stack scene does not read
 * it yet (on the wide layout the shell already keeps Supporting pages in its own pane); a
 * supporting-pane scene strategy placed before [RaftDesktopStackSceneStrategy] will.
 */
internal data object RaftDesktopPaneRoleKey : NavMetadataKey<RaftPaneRole>

internal fun raftDesktopPaneRoleMetadata(pageName: String): Map<String, Any> =
    metadata { put(RaftDesktopPaneRoleKey, RaftPageRegistry.paneRole(pageName)) }

internal fun NavEntry<*>.raftDesktopPaneRole(): RaftPaneRole = metadata[RaftDesktopPaneRoleKey] ?: RaftPaneRole.Window
