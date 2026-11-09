package build.raft.app.base

/** The wrappers [BasePager.setContent] puts around every page, outermost first. */
internal enum class RaftPageLayer {
    /** Creates the shared store; shows the database recovery page for a DB newer than the app. */
    DatabaseLaunchGate,
    EnvironmentIndicator,
    SideColumnScaffold,

    /** The hovered link's destination at the page's bottom-left (RaftLinkHoverStatusHost). */
    LinkHoverStatus,
}

/**
 * The page's layers, outermost first. The database launch gate is always outside the environment
 * indicator and every product surface, so nothing in the page can reach the database first.
 *
 * It applies to the app shell, and to any non-embedded page that is the first in the process to
 * need the shared store (an Android process-death restore straight to a child page, an iOS login
 * callback opening the choose-server page). Once the store is built, later pages skip it. Embedded
 * child pages sit inside a host page that already passed it, and show their links in its status.
 */
internal fun raftPageLayers(
    ownsDatabaseLaunch: Boolean,
    embedded: Boolean,
    sharedStoreBuilt: Boolean,
): List<RaftPageLayer> =
    buildList {
        if (ownsDatabaseLaunch || (!embedded && !sharedStoreBuilt)) add(RaftPageLayer.DatabaseLaunchGate)
        add(RaftPageLayer.EnvironmentIndicator)
        if (!embedded) add(RaftPageLayer.SideColumnScaffold)
        if (!embedded) add(RaftPageLayer.LinkHoverStatus)
    }
