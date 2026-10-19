package build.raft.app.cmp

import androidx.compose.runtime.Composable
import build.raft.app.cmp.pages.CmpHelloPage
import kotlin.concurrent.Volatile

/**
 * A page that has a Compose Multiplatform implementation.
 *
 * [pageName] and the pageData JSON are the same contract the Kuikly page uses (deep links,
 * notifications, `openPage` callers and visual-testing cases keep working unchanged).
 * [rollbackFlagKey] is the per-page remote-config key a [SlockCmpRouteOverride] reads to send the
 * page back to Kuikly (or, for a page listed with [enabledByDefault] = false, to turn CMP on)
 * without a release.
 */
class SlockCmpPage internal constructor(
    val pageName: String,
    val rollbackFlagKey: String,
    val enabledByDefault: Boolean = true,
    internal val content: @Composable () -> Unit,
)

/**
 * The migrated-pages allowlist: the only place that decides whether a page name renders on CMP.
 *
 * Both hosts ask [SlockCmpRouter.rendersOnCmp] before creating a page (Android picks
 * `SlockCmpActivity` over `SlockKuiklyActivity`, iOS pushes the CMP view controller instead of
 * `KuiklyRenderViewController`); everything not listed here stays on Kuikly.
 */
object SlockCmpPages {
    /** Debug-only end-to-end check of both hosts (theme, page args, HostCapabilities, close). */
    const val CMP_HELLO: String = "compose_cmp_hello"

    /** Product pages migrated to CMP. Empty until the Settings pilot lands. */
    val migrated: List<SlockCmpPage> = emptyList()

    /** Pages routed to CMP only in debug builds (see [SlockCmpRouter.configure]). */
    val debugOnly: List<SlockCmpPage> =
        listOf(
            SlockCmpPage(
                pageName = CMP_HELLO,
                rollbackFlagKey = "mobile_cmp_page_compose_cmp_hello",
                content = { CmpHelloPage() },
            ),
        )
}

/**
 * Per-page remote override of the allowlist (rollback seam). Return `false` to send the page back
 * to Kuikly, `true` to render it on CMP, or `null` to keep [SlockCmpPage.enabledByDefault]. Only
 * pages listed in [SlockCmpPages] (and admitted in this build) can be routed to CMP at all. Called
 * on the navigation path, so it must answer synchronously (e.g. from the last persisted flag
 * evaluation); a throwing override counts as `null`.
 */
fun interface SlockCmpRouteOverride {
    fun cmpEnabled(page: SlockCmpPage): Boolean?
}

object SlockCmpRouter {
    @Volatile
    private var debugPagesEnabled: Boolean = false

    @Volatile
    private var routeOverride: SlockCmpRouteOverride? = null

    /**
     * Called once by each host at startup. [debugBuild] admits [SlockCmpPages.debugOnly];
     * [override] is the remote rollback seam (none by default: the code allowlist decides).
     */
    fun configure(debugBuild: Boolean, override: SlockCmpRouteOverride? = null) {
        debugPagesEnabled = debugBuild
        routeOverride = override
    }

    /** True when [pageName] must be hosted by the CMP host instead of the Kuikly one. */
    fun rendersOnCmp(pageName: String): Boolean {
        val page = admittedPage(pageName) ?: return false
        val override = runCatching { routeOverride?.cmpEnabled(page) }.getOrNull()
        return override ?: page.enabledByDefault
    }

    /**
     * The page a CMP host should render for [pageName], or null when it has no CMP implementation
     * in this build. Ignores the rollback override: a host that was already launched for a page
     * keeps rendering it.
     */
    fun page(pageName: String): SlockCmpPage? = admittedPage(pageName)

    private fun admittedPage(pageName: String): SlockCmpPage? =
        SlockCmpPages.migrated.firstOrNull { it.pageName == pageName }
            ?: SlockCmpPages.debugOnly.firstOrNull { it.pageName == pageName }?.takeIf { debugPagesEnabled }
}
