package build.raft.app

internal const val SharedSettingsDefaultWebBaseUrl = "https://app.raft.build"

internal fun sharedSettingsResolvedWebBaseUrl(webUrl: String?): String =
    webUrl?.trim()?.trimEnd('/').orEmpty().ifBlank { SharedSettingsDefaultWebBaseUrl }

internal fun sharedSettingsWebUrl(webUrl: String?, path: String): String {
    val baseUrl = sharedSettingsResolvedWebBaseUrl(webUrl)
    val cleanPath = path.trim().trimStart('/')
    return if (cleanPath.isBlank()) baseUrl else "$baseUrl/$cleanPath"
}

internal const val SharedSettingsEmbedContract = "raft-settings-v1"
internal const val SharedSettingsEmbedHostShell = "host"

internal fun sharedSettingsHostShellWebUrl(
    webUrl: String?,
    path: String,
    extraQueryParameters: List<Pair<String, String>> = emptyList()
): String {
    val queryParameters =
        listOf(
            "embed" to SharedSettingsEmbedContract,
            "shell" to SharedSettingsEmbedHostShell
        ) + extraQueryParameters
    val query = queryParameters.joinToString(separator = "&") { (key, value) -> "$key=$value" }
    return "${sharedSettingsWebUrl(webUrl, path)}?$query"
}

/**
 * The web link a Raft Desktop deep link stands for, or null. Raft Desktop's "Open in Raft Desktop"
 * and copied links put the web path and query behind the app scheme (slock
 * `packages/web/src/utils/desktopAppLink.ts`): `raft:///s/{slug}/channel/{cid}?msg={mid}`, and
 * `raft://s/…` is read the same way (slock `desktopRouteFromDeepLink`). The path and query are put
 * on [webUrl], the Developer environment's web URL (AGENTS.md "Environment Web Link URL Rule");
 * blank or null is production. Only `/s/{slug}/…` is converted; `parseRaftMessageLink` then decides
 * whether the app opens it. `raft` and the per-build `raft-{variant}` callback schemes are accepted.
 */
internal fun raftSchemeLinkToWebLink(url: String, webUrl: String?): String? {
    val raw = url.trim()
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = raw.substring(0, schemeEnd).lowercase()
    if (scheme != "raft" && !scheme.startsWith("raft-")) return null
    val rest = raw.substring(schemeEnd + 3).substringBefore('#')
    val pathAndQuery = if (rest.startsWith("/")) rest else "/$rest"
    val path = pathAndQuery.substringBefore('?')
    val segments = path.removePrefix("/").split('/')
    if (segments.size < 2 || segments[0] != "s" || segments[1].isEmpty()) return null
    if ('\\' in pathAndQuery || '@' in path) return null
    return sharedSettingsResolvedWebBaseUrl(webUrl) + pathAndQuery
}
