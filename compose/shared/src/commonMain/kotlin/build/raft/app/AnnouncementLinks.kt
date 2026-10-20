package build.raft.app

import build.raft.app.data.LinkOpenMode

/** Relative query actions retain the selected server, as Web's current route does. */
internal fun resolveAnnouncementLink(href: String, webBaseUrl: String, serverSlug: String?): String? {
    if (href.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f } || '\\' in href) return null
    val base = webBaseUrl.trimEnd('/')
    val url = when {
        href.startsWith("//") -> return null
        href.startsWith("/") -> "$base$href"
        href.startsWith("?") -> {
            val slug = serverSlug?.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
                ?: return null
            "$base/s/$slug$href"
        }
        href.startsWith("https://", ignoreCase = true) -> href
        else -> return null
    }
    return resolveRaftLinkOpenDecision(url, LinkOpenMode.InAppBrowser)
        .takeIf { it.target != RaftLinkOpenTarget.Rejected }?.url
}
