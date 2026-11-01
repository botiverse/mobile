package build.raft.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

/**
 * [stack]'s `NavDisplay`: [RaftStackSceneStrategy] (every page stays composed while it is on the
 * stack, only the top one is laid out and drawn), the page's [raftPaneRoleMetadata], and a
 * ViewModel store per page, cleared when the page leaves the stack. [pageContent] draws one entry.
 *
 * The platform back event closes the top page; with only the root page left `NavDisplay`'s back
 * handler is off (no previous entry) and [RaftPageStack.back] a no-op anyway, so the root page's own
 * back handling gets it. Window input (keys, mouse buttons) stays with the host around this.
 */
@Composable
fun RaftPageStackDisplay(
    stack: RaftPageStack,
    pageContent: @Composable (RaftStackPage) -> Unit,
) {
    // The root page never leaves the stack; guard NavDisplay's non-empty requirement anyway.
    if (stack.pages.isEmpty()) return
    val sceneStrategy = remember { RaftStackSceneStrategy<RaftStackPage>() }
    NavDisplay(
        backStack = stack.pages,
        onBack = { if (stack.pages.size > 1) stack.back() },
        sceneStrategies = listOf(sceneStrategy),
        entryProvider = { page ->
            NavEntry(page, contentKey = page.contentKey, metadata = raftPaneRoleMetadata(page.args.pageName)) {
                RaftStackPageViewModels { pageContent(page) }
            }
        },
    )
}

/** The page's own ViewModel store (an Activity's / view controller's), cleared when it leaves. */
@Composable
private fun RaftStackPageViewModels(content: @Composable () -> Unit) {
    val owner = remember { RaftStackPageViewModelStoreOwner() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

private class RaftStackPageViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
