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
