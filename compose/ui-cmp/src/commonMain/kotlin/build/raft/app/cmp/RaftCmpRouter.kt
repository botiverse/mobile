package build.raft.app.cmp

import androidx.compose.runtime.Composable
import build.raft.app.cmp.pages.CmpHelloPage
import kotlin.concurrent.Volatile

/**
 * A page implemented in this module (not through the :shared [RaftPageProvider]).
 *
 * [pageName] and the pageData JSON are the same contract every page uses (deep links,
 * notifications, `openPage` callers and visual-testing cases keep working unchanged).
 */
class RaftCmpPage internal constructor(
    val pageName: String,
    internal val content: @Composable () -> Unit,
)

/** Pages implemented in :ui-cmp itself. Everything else comes from the installed [RaftPageProvider]. */
object RaftCmpPages {
    /** Debug-only end-to-end check of both hosts (theme, page args, HostCapabilities, close). */
    const val CMP_HELLO: String = "compose_cmp_hello"

    /** Pages admitted only in debug builds (see [RaftCmpRouter.configure]). */
    val debugOnly: List<RaftCmpPage> =
        listOf(
            RaftCmpPage(
                pageName = CMP_HELLO,
                content = { CmpHelloPage() },
            ),
        )
}

/**
 * Pages implemented outside this module (CMP switch: :shared's former Kuikly pages, rendered
 * through `RaftPageRegistry`). :ui-cmp cannot depend on :shared, so :shared installs itself here.
 * Returns the page body for [pageName], or null when the provider has no such page.
 */
fun interface RaftPageProvider {
    fun content(pageName: String): (@Composable () -> Unit)?
}

/**
 * Decides what a CMP host renders for a page name. Since the CMP switch every page renders on CMP:
 * the registered set is the :ui-cmp pages admitted in this build plus everything the installed
 * [RaftPageProvider] knows. There is no per-page rollback any more (there is no Kuikly host left
 * to roll back to).
 */
object RaftCmpRouter {
    @Volatile
    private var debugPagesEnabled: Boolean = false

    @Volatile
    private var pageProvider: RaftPageProvider? = null

    /** Installs the :shared page registry (`RaftPageRegistry.install()`, called by each host at startup). */
    fun installPageProvider(provider: RaftPageProvider) {
        pageProvider = provider
    }

    /** Body of [pageName] from the installed [RaftPageProvider], if any. */
    fun providedContent(pageName: String): (@Composable () -> Unit)? =
        runCatching { pageProvider?.content(pageName) }.getOrNull()

    /** Called once by each host at startup. [debugBuild] admits [RaftCmpPages.debugOnly]. */
    fun configure(debugBuild: Boolean) {
        debugPagesEnabled = debugBuild
    }

    /**
     * The host's debug flag as passed to [configure] (Android `BuildConfig.DEBUG`, iOS `#if DEBUG`);
     * false until a host configures it, e.g. in unit tests.
     */
    val isDebugBuild: Boolean
        get() = debugPagesEnabled

    /** True when [pageName] is a registered page (a host can render it). */
    fun rendersOnCmp(pageName: String): Boolean = content(pageName) != null

    /** The :ui-cmp page for [pageName], or null when it is not one (or not admitted in this build). */
    fun page(pageName: String): RaftCmpPage? =
        RaftCmpPages.debugOnly.firstOrNull { it.pageName == pageName }?.takeIf { debugPagesEnabled }

    /** The body a host renders for [pageName]: a :ui-cmp page first, then the installed provider. */
    fun content(pageName: String): (@Composable () -> Unit)? =
        page(pageName)?.content ?: providedContent(pageName)
}
