package build.raft.app

import build.raft.app.data.LinkOpenMode
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.data.StoredAuthServer

/**
 * First path segments of the web's top-level routes (slock `AnnouncementSidebarCard.tsx`
 * `TOP_LEVEL_SEGMENTS`): any other in-app path belongs to a server.
 */
private val AnnouncementTopLevelSegments =
    setOf(
        "s", "servers", "download", "artifacts", "connections", "appsumo", "dev", "palette-audit",
        "login", "login-with-raft", "login-with-slock", "login-with-slock-human", "community", "integration-invites",
    )

private fun String.isAnnouncementServerSlug(): Boolean =
    isNotBlank() && all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }

/**
 * The web URL an announcement link stands for. An in-app path is written once for every server
 * (`/settings/theme`, `/apps`): as the web's `announcementServerPath`, it opens on the reader's
 * current server (`/s/{slug}/settings/theme`); paths already under `/s/` and the web's top-level
 * pages stay as written. Relative query actions retain the selected server, as Web's current route does.
 */
internal fun resolveAnnouncementLink(href: String, webBaseUrl: String, serverSlug: String?): String? {
    if (href.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f } || '\\' in href) return null
    val base = webBaseUrl.trimEnd('/')
    val slug = serverSlug?.takeIf { it.isAnnouncementServerSlug() }
    val url = when {
        href.startsWith("//") -> return null
        href.startsWith("/") -> {
            val firstSegment = href.drop(1).split('/', '?', '#', limit = 2).first()
            if (slug == null || firstSegment in AnnouncementTopLevelSegments) {
                "$base$href"
            } else {
                "$base/s/$slug${if (href == "/") "" else href}"
            }
        }
        href.startsWith("?") -> "$base/s/${slug ?: return null}$href"
        href.startsWith("https://", ignoreCase = true) -> href
        else -> return null
    }
    return resolveRaftLinkOpenDecision(url, LinkOpenMode.InAppBrowser)
        .takeIf { it.target != RaftLinkOpenTarget.Rejected }?.url
}

/** What an announcement's call to action or body link does. */
internal sealed interface AnnouncementLinkAction {
    /** A link to Raft's own Settings: the in-app Settings page ([RaftSettingsLink]). */
    data class OpenSettings(val link: RaftSettingsLink, val target: RaftSettingsLinkTarget) : AnnouncementLinkAction

    /**
     * Anything else: the link opens as any other web link (the link setting decides where).
     * [raftWebPage]: a page on Raft's own web origin the app has no screen for (a Settings tab of
     * another server the account is not on), opened with [RaftLinkOpenIntent.RaftWebPage] so it
     * reaches a browser instead of bouncing off Raft's own App Link / Universal Link.
     */
    data class OpenUrl(val url: String, val raftWebPage: Boolean = false) : AnnouncementLinkAction {
        val openIntent: RaftLinkOpenIntent
            get() = if (raftWebPage) RaftLinkOpenIntent.RaftWebPage else RaftLinkOpenIntent.Ordinary
    }
}

/**
 * The action for announcement link [href]: Settings links on Raft's own origins (relative, or
 * absolute on production / the environment's [webBaseUrl]) open the in-app page; null for a link
 * that cannot open at all.
 */
internal fun announcementLinkAction(
    href: String,
    webBaseUrl: String,
    currentServerId: String?,
    servers: List<StoredAuthServer>,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): AnnouncementLinkAction? {
    val currentSlug = servers.firstOrNull { it.id == currentServerId }?.slug
    val url = resolveAnnouncementLink(href, webBaseUrl, currentSlug) ?: return null
    val settingsLink = parseRaftSettingsLink(url, configuredWebUrl = webBaseUrl, appHosts = appHosts)
    val target = settingsLink?.let { raftSettingsLinkTarget(it, currentServerId, servers) }
    return if (settingsLink != null && target != null) {
        AnnouncementLinkAction.OpenSettings(settingsLink, target)
    } else {
        AnnouncementLinkAction.OpenUrl(
            url = url,
            raftWebPage = raftOwnWebLinkPath(url, configuredWebUrl = webBaseUrl, appHosts = appHosts) != null,
        )
    }
}
