package build.raft.app

import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.data.StoredAuthServer

/**
 * A link to the web's Settings on one of Raft's own origins: `/settings[/{tab}]` or
 * `/s/{slug}/settings[/{tab}]` (slock `components/settings/settingsNavigation.ts`, the
 * `/s/:serverSlug/settings/:tab` route). Announcements link here, e.g. "Choose a theme" to
 * `/settings/theme` and the passkey announcement to `/settings/account#two-step` (which the app
 * leaves to the web: its Account page has no two-step block).
 */
internal data class RaftSettingsLink(
    /** The `/s/{slug}` server the link names, or null when it names none (the current server). */
    val serverSlug: String?,
    /** The web tab as written (`theme`, `applications`…), or null for Settings itself. */
    val webTab: String?,
    /** The in-app Settings row key for [webTab], or null for the Settings home. */
    val destinationKey: String?,
    /** The `#…` block of the tab (web `useScrollToSettingsSection`), e.g. `two-step`. */
    val section: String?,
)

/**
 * The in-app Settings row for a web Settings tab slug (its aliases included, as the web's
 * `settingsTabIdForRouteSlug` reads them), or null when the app has no page of its own for it
 * (Labs, AI Providers, Shared links, a tab this build does not know): those open the Settings home.
 */
internal fun raftSettingsDestinationKeyForWebTab(webTab: String?): String? =
    when (webTab) {
        "account" -> SettingsDestinationAccount
        "language-region" -> SettingsNativeDetailPage.LanguageRegion.key
        "appearance" -> SettingsNativeDetailPage.Appearance.key
        "notifications", "browser" -> SettingsNativeDetailPage.Notifications.key
        "theme" -> SettingsNativeDetailPage.Theme.key
        "server-notifications" -> SettingsNativeDetailPage.ServerNotifications.key
        "server" -> SettingsNativeDetailPage.Server.key
        "billing" -> SettingsDestinationBilling
        "administration", "moderation" -> SettingsDestinationAdministration
        "im-bridges" -> SettingsDestinationImBridges
        "applications", "integrations", "mcp", "mcp-servers" -> SettingsDestinationConnectedApps
        "computers" -> SettingsDestinationComputers
        "about" -> SettingsNativeDetailPage.About.key
        "feedback" -> SettingsDestinationHandsFeedback
        else -> null
    }

/**
 * Parses a web Settings link, or null when [url] is not one: another origin (it stays a web link),
 * another path, or a `#…` block the in-app page does not have. The origins are exactly those of message links ([raftOwnWebLinkPath]):
 * production, the client config's [appHosts], and the Developer environment's [configuredWebUrl]
 * (so a staging build takes its staging links). Segments after the tab (a computer, a feedback
 * ticket) open the tab.
 */
internal fun parseRaftSettingsLink(
    url: String,
    configuredWebUrl: String? = null,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): RaftSettingsLink? {
    val own = raftOwnWebLinkPath(url, configuredWebUrl, appHosts) ?: return null
    if ('\\' in own.path || '@' in own.path) return null
    val segments = own.path.removePrefix("/").removeSuffix("/").split('/')
    val (serverSlug, rest) =
        if (segments.size >= 3 && segments[0] == "s" && segments[2] == "settings") {
            val slug = segments[1].takeIf(::isRaftLinkIdentifier) ?: return null
            slug to segments.drop(3)
        } else if (segments.isNotEmpty() && segments[0] == "settings") {
            null to segments.drop(1)
        } else {
            return null
        }
    if (rest.any(String::isEmpty)) return null
    val webTab = rest.firstOrNull()
    val destinationKey = raftSettingsDestinationKeyForWebTab(webTab)
    val section = own.fragment?.takeIf(String::isNotBlank)
    // A block the in-app page does not have would land on a page without it: the web shows it.
    if (section != null && !raftSettingsInAppPageHasSection(destinationKey, section)) return null
    return RaftSettingsLink(
        serverSlug = serverSlug,
        webTab = webTab,
        destinationKey = destinationKey,
        section = section,
    )
}

/**
 * The `#…` blocks of a web Settings tab the in-app page can show, by in-app row key. The in-app
 * Account page has none of the web's linkable ones (`#two-step`, `#passkeys`), so an Account link
 * with any block opens the web page. Tabs not listed take any block (the page opens at its top).
 */
private val RaftSettingsInAppSections: Map<String, Set<String>> =
    mapOf(SettingsDestinationAccount to emptySet())

private fun raftSettingsInAppPageHasSection(destinationKey: String?, section: String): Boolean =
    RaftSettingsInAppSections[destinationKey]?.contains(section) ?: true

/** What the app shell opens for a [RaftSettingsLink]. */
internal data class RaftSettingsLinkTarget(
    /** The account server to switch to first, or null to stay on the current one. */
    val switchToServerId: String?,
    /** The in-app Settings row to open, or null for the Settings home. */
    val destinationKey: String?,
)

/**
 * Where [link] opens for this account: on the current server when it names none or names the
 * current one; on another of the account's [servers] after switching to it. Null when it names a
 * server the account is not on: the app cannot show that server's Settings, so it stays a web link.
 */
internal fun raftSettingsLinkTarget(
    link: RaftSettingsLink,
    currentServerId: String?,
    servers: List<StoredAuthServer>,
): RaftSettingsLinkTarget? {
    val slug = link.serverSlug ?: return RaftSettingsLinkTarget(switchToServerId = null, destinationKey = link.destinationKey)
    val server = servers.firstOrNull { it.slug?.equals(slug, ignoreCase = true) == true } ?: return null
    return RaftSettingsLinkTarget(
        switchToServerId = server.id.takeIf { it != currentServerId },
        destinationKey = link.destinationKey,
    )
}
