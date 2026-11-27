package build.raft.app

import build.raft.app.compat.pager.Pager
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.thread.RaftMessageLinkInbox
import build.raft.app.thread.isRaftLinkIdentifier
import build.raft.app.thread.key
import build.raft.app.thread.raftLinkPercentDecodeOrNull
import build.raft.app.thread.raftOwnWebLinkPath
import build.raft.app.thread.raftOwnWebLinkRawQueryOrNull
import kotlin.time.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Settings › Account › Data & privacy (botiverse/slock #15418 / #15427 / #15451 / #15495 /
 * #15506 / #15517, flag `data_export_v0`):
 * - Personal export lives on the web at `/s/<slug>/settings/account#data-export` (with optional
 *   `?data_export=<id>` from the emailed link `/?data_export=<id>`).
 * - Server export lives on the web at `/s/<slug>/settings/administration#server-data-export`
 *   (from the emailed link `/?data_export=<id>&data_export_server=<serverId>`).
 *
 * Mobile embeds the authenticated web surface via the shared Settings WebView host (matching
 * Administration / App authorizations / Account deletion) and handles `/?data_export=<id>` deep
 * links behind `data_export_v0`.
 */

/** Query parameter carrying the export UUID (`slock` `DATA_EXPORT_LINK_PARAM`). */
internal const val DATA_EXPORT_LINK_QUERY_PARAM = "data_export"

/** Query parameter carrying the server UUID for a server export (`slock` `DATA_EXPORT_SERVER_LINK_PARAM`). */
internal const val DATA_EXPORT_SERVER_LINK_QUERY_PARAM = "data_export_server"

/** Anchor on `/settings/account` (`slock` `DataExportSection.tsx` `DATA_EXPORT_SECTION_ID`). */
internal const val SETTINGS_DATA_EXPORT_ANCHOR = "data-export"

/** Anchor on `/settings/administration` (`slock` `ServerDataPrivacySection.tsx` `SERVER_DATA_EXPORT_SECTION_ID`). */
internal const val SETTINGS_SERVER_DATA_EXPORT_ANCHOR = "server-data-export"

private val DATA_EXPORT_UUID_REGEX =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

/** Lowercase export or server UUID from a link or route parameter, or null (`slock` `dataExportIdOf`). */
internal fun dataExportUuidOrNull(value: String?): String? =
    value?.trim()?.takeIf { DATA_EXPORT_UUID_REGEX.matches(it) }?.lowercase()

internal fun Pager.openSettingsDataPrivacyPage(
    storeScope: RaftShellStoreScope,
    exportId: String? = null,
    serverId: String? = null,
) {
    openSettingsWebViewPage(
        surface = SettingsWebViewSurface.DataPrivacy,
        storeScope = storeScope,
        dataExportId = dataExportUuidOrNull(exportId),
        dataExportServerId = dataExportUuidOrNull(serverId),
    )
}

internal val settingsDataPrivacyWebViewSpec: StandaloneSettingsWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.DataPrivacy,
        allowNavigation = true,
        lockToWebOrigin = true,
        buildWebUrl = { webBaseUrl, session, localeTag ->
            settingsDataPrivacyWebViewUrl(webBaseUrl, session, localeTag)
        },
    )

internal fun settingsDataPrivacyWebViewSpec(
    exportId: String? = null,
    serverId: String? = null,
): StandaloneSettingsWebViewSpec {
    val cleanExportId = dataExportUuidOrNull(exportId)
    val cleanServerId = dataExportUuidOrNull(serverId)
    if (cleanExportId == null && cleanServerId == null) return settingsDataPrivacyWebViewSpec
    return settingsDataPrivacyWebViewSpec.copy(
        buildWebUrl = { webBaseUrl, session, localeTag ->
            settingsDataPrivacyWebViewUrl(
                webBaseUrl = webBaseUrl,
                session = session,
                localeTag = localeTag,
                exportId = cleanExportId,
                serverId = cleanServerId,
            )
        },
    )
}

/**
 * Builds the embedded Settings WebView URL for Data & privacy:
 * - When [exportId] and [serverId] are valid UUIDs and [serverId] matches one of [session]'s
 *   servers with a non-blank slug, routes to `/s/<slug>/settings/administration?...&data_export=<id>#server-data-export`
 *   (matching `slock` `packages/web/src/utils/dataExportLink.ts`).
 * - Otherwise routes to `/s/<slug>/settings/account` (or `/settings/account` without a slug),
 *   appending `data_export=<id>` when [exportId] is a valid UUID, anchored at `#data-export`.
 */
internal fun settingsDataPrivacyWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?,
    localeTag: String = AppLocale.Default.languageTag,
    exportId: String? = null,
    serverId: String? = null,
): String {
    val cleanExportId = dataExportUuidOrNull(exportId)
    val cleanServerId = dataExportUuidOrNull(serverId)
    val exportedServerSlug =
        if (cleanExportId != null && cleanServerId != null) {
            session?.servers
                ?.firstOrNull { it.id.equals(cleanServerId, ignoreCase = true) }
                ?.slug
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    if (exportedServerSlug != null && cleanExportId != null) {
        val url =
            sharedSettingsHostShellWebUrl(
                webUrl = webBaseUrl,
                path = "s/$exportedServerSlug/settings/administration",
                extraQueryParameters = listOf("lang" to localeTag, DATA_EXPORT_LINK_QUERY_PARAM to cleanExportId),
            )
        return "$url#$SETTINGS_SERVER_DATA_EXPORT_ANCHOR"
    }
    val path =
        standaloneSettingsServerSlug(session)
            ?.takeIf { it.isNotBlank() }
            ?.let { "s/$it/settings/account" }
            ?: "settings/account"
    val extraQueryParameters =
        buildList {
            add("lang" to localeTag)
            if (cleanExportId != null) add(DATA_EXPORT_LINK_QUERY_PARAM to cleanExportId)
        }
    val url =
        sharedSettingsHostShellWebUrl(
            webUrl = webBaseUrl,
            path = path,
            extraQueryParameters = extraQueryParameters,
        )
    return "$url#$SETTINGS_DATA_EXPORT_ANCHOR"
}

/**
 * A parsed data-export deep link on one of Raft's own web origins (`/?data_export=<id>`, with
 * optional `&data_export_server=<serverId>`, or a direct Settings Account / Administration URL
 * carrying `?data_export=<id>`).
 */
internal data class RaftDataExportLink(
    /** Validated lowercase export UUID. */
    val exportId: String,
    /** Validated lowercase server UUID from `data_export_server`, or null for a personal export. */
    val serverId: String? = null,
    /** Optional `/s/<slug>` from a direct settings URL, or null for `/?data_export=<id>`. */
    val serverSlug: String? = null,
    /** True when the path explicitly named `/settings/administration`. */
    val isAdministrationPath: Boolean = false,
)

/**
 * Parses a Raft data-export deep link, or returns null when [url] is not one:
 * - Origin must match Raft's trusted web origins ([raftOwnWebLinkPath]).
 * - Path must be root (`""` / `"/"`) or `/[s/<slug>/]settings/(account|administration)`.
 * - Query must contain a valid UUID `data_export` parameter (invalid values fail closed to null).
 * - Optional `data_export_server` is accepted only when a valid UUID; invalid values are ignored as null
 *   (matching `slock` `dataExportLandingPath`).
 */
internal fun parseRaftDataExportLink(
    url: String,
    configuredWebUrl: String? = null,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): RaftDataExportLink? {
    val raw = url.trim()
    if (raw.isEmpty() || raw.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f } || '\\' in raw) {
        return null
    }
    val ownPath = raftOwnWebLinkPath(raw, configuredWebUrl, appHosts) ?: return null
    val rawQuery = raftOwnWebLinkRawQueryOrNull(raw, configuredWebUrl, appHosts) ?: return null
    if (rawQuery.isEmpty()) return null

    var serverSlug: String? = null
    var isAdministrationPath = false
    val path = ownPath.path
    if (path.isNotEmpty() && path != "/") {
        val segments = path.removePrefix("/").removeSuffix("/").split('/')
        if (segments.any(String::isEmpty)) return null
        val rest =
            if (segments.size >= 3 && segments[0] == "s" && segments[2] == "settings") {
                val slug = segments[1].takeIf(::isRaftLinkIdentifier) ?: return null
                serverSlug = slug
                segments.drop(3)
            } else if (segments.isNotEmpty() && segments[0] == "settings") {
                segments.drop(1)
            } else {
                return null
            }
        if (rest.size != 1) return null
        when (rest[0]) {
            "account" -> isAdministrationPath = false
            "administration" -> isAdministrationPath = true
            else -> return null
        }
    }

    var rawExportId: String? = null
    var rawServerId: String? = null
    for (part in rawQuery.split('&')) {
        if (part.isEmpty()) continue
        val key = raftLinkPercentDecodeOrNull(part.substringBefore('=')) ?: return null
        val value = raftLinkPercentDecodeOrNull(part.substringAfter('=', missingDelimiterValue = "")) ?: return null
        if (key == DATA_EXPORT_LINK_QUERY_PARAM && rawExportId == null) {
            rawExportId = value
        } else if (key == DATA_EXPORT_SERVER_LINK_QUERY_PARAM && rawServerId == null) {
            rawServerId = value
        }
    }
    val exportId = dataExportUuidOrNull(rawExportId) ?: return null
    val serverId = dataExportUuidOrNull(rawServerId)
    return RaftDataExportLink(
        exportId = exportId,
        serverId = serverId,
        serverSlug = serverSlug,
        isAdministrationPath = isAdministrationPath,
    )
}

/** A data-export deep link waiting for a ready app shell, as [RaftPendingMessageLink] waits. */
internal data class RaftPendingDataExportLink(
    val id: Long,
    val link: RaftDataExportLink,
    val receivedAtEpochMillis: Long,
    val source: String,
)

/**
 * The process-wide single slot for a data-export deep link ([parseRaftDataExportLink]), tapped in a
 * message or opened from outside the app: newest wins, taken once by a shell with a ready session,
 * dropped after [ttlMillis].
 */
internal class RaftDataExportLinkInbox(
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ttlMillis: Long = RaftMessageLinkInbox.DefaultTtlMillis,
) {
    private val lock = SynchronizedObject()
    private var nextId = 1L
    private val mutablePending = MutableStateFlow<RaftPendingDataExportLink?>(null)
    val pending: StateFlow<RaftPendingDataExportLink?> = mutablePending.asStateFlow()

    fun submit(link: RaftDataExportLink, source: String): RaftPendingDataExportLink = synchronized(lock) {
        RaftPendingDataExportLink(
            id = nextId++,
            link = link,
            receivedAtEpochMillis = nowEpochMillis(),
            source = source,
        ).also { mutablePending.value = it }
    }

    /** Takes the pending link [id]; null when already taken, replaced, or expired (then cleared). */
    fun take(id: Long): RaftPendingDataExportLink? = synchronized(lock) {
        val current = mutablePending.value?.takeIf { it.id == id } ?: return null
        mutablePending.value = null
        current.takeIf { nowEpochMillis() - it.receivedAtEpochMillis <= ttlMillis }
    }

    fun clear() = synchronized(lock) { mutablePending.value = null }

    companion object {
        /** The slot platform ingresses, message link taps, and every app shell share. */
        val Process: RaftDataExportLinkInbox = RaftDataExportLinkInbox()
    }
}

/**
 * Canonical web fallback URL for [link] when `data_export_v0` is off/unresolved or the embedded
 * surface cannot open, preserving `?data_export=<id>` (and `&data_export_server=<serverId>`).
 */
internal fun raftDataExportFallbackWebUrl(
    link: RaftDataExportLink,
    webBaseUrl: String?,
): String {
    val base = sharedSettingsResolvedWebBaseUrl(webBaseUrl)
    val slug = link.serverSlug?.takeIf(::isRaftLinkIdentifier)
    if (slug != null) {
        val tab = if (link.isAdministrationPath) "administration" else "account"
        val anchor = if (link.isAdministrationPath) SETTINGS_SERVER_DATA_EXPORT_ANCHOR else SETTINGS_DATA_EXPORT_ANCHOR
        return "$base/s/$slug/settings/$tab?$DATA_EXPORT_LINK_QUERY_PARAM=${link.exportId}#$anchor"
    }
    val query =
        buildList {
            add("$DATA_EXPORT_LINK_QUERY_PARAM=${link.exportId}")
            link.serverId?.let { add("$DATA_EXPORT_SERVER_LINK_QUERY_PARAM=$it") }
        }.joinToString("&")
    return "$base/?$query"
}

/** Routing outcome for a taken [RaftDataExportLink] inside an authenticated app shell. */
internal sealed interface RaftDataExportLinkAction {
    /** `data_export_v0` is enabled: open the authenticated Settings Data & privacy WebView. */
    data class OpenSettingsWebView(
        val exportId: String,
        val serverId: String? = null,
    ) : RaftDataExportLinkAction

    /** `data_export_v0` is off or unresolved: open cleanly in the browser preserving `?data_export=<id>`. */
    data class OpenWebFallback(
        val url: String,
    ) : RaftDataExportLinkAction
}

private fun matchedServerForDataExportLink(
    link: RaftDataExportLink,
    session: StoredAuthSession?,
): StoredAuthServer? {
    val servers = session?.servers.orEmpty()
    if (link.serverId != null) {
        return servers.firstOrNull { it.id.equals(link.serverId, ignoreCase = true) }
    }
    if (link.isAdministrationPath && link.serverSlug != null) {
        return servers.firstOrNull { it.slug?.equals(link.serverSlug, ignoreCase = true) == true }
    }
    return null
}

internal fun raftDataExportAccountServerIds(session: StoredAuthSession?): List<String> =
    buildList {
        session?.currentServerId?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
        session?.servers?.forEach { server ->
            server.id.trim().takeIf(String::isNotEmpty)?.let(::add)
        }
    }.distinct()

/** Server IDs whose feature-flag snapshots should be prefetched before routing [link]. */
internal fun raftDataExportFlagServerIdsToPrefetch(
    link: RaftDataExportLink,
    session: StoredAuthSession?,
): List<String> {
    val matched = matchedServerForDataExportLink(link, session)
    if (matched != null) return listOf(matched.id)
    return raftDataExportAccountServerIds(session)
}

/**
 * Decides where [link] opens for [session]:
 * - Server export matching one of [session]'s servers: opens the Data & privacy WebView for that
 *   server's Administration page when [isFlagEnabledForServer] is true for that server; otherwise
 *   falls back cleanly to [RaftDataExportLinkAction.OpenWebFallback] preserving `?data_export=<id>`.
 * - Personal export (or server export naming a server the account is not in): opens the Data &
 *   privacy WebView on Account settings when any joined server enables `data_export_v0`; otherwise
 *   falls back cleanly to [RaftDataExportLinkAction.OpenWebFallback] preserving `?data_export=<id>`.
 */
internal fun resolveRaftDataExportLinkAction(
    link: RaftDataExportLink,
    session: StoredAuthSession?,
    webBaseUrl: String?,
    isFlagEnabledForServer: (String) -> Boolean,
): RaftDataExportLinkAction {
    val matchedServer = matchedServerForDataExportLink(link, session)
    if (matchedServer != null) {
        return if (isFlagEnabledForServer(matchedServer.id)) {
            RaftDataExportLinkAction.OpenSettingsWebView(
                exportId = link.exportId,
                serverId = matchedServer.id,
            )
        } else {
            RaftDataExportLinkAction.OpenWebFallback(raftDataExportFallbackWebUrl(link, webBaseUrl))
        }
    }
    val accountServerIds = raftDataExportAccountServerIds(session)
    return if (accountServerIds.any(isFlagEnabledForServer)) {
        RaftDataExportLinkAction.OpenSettingsWebView(
            exportId = link.exportId,
            serverId = null,
        )
    } else {
        RaftDataExportLinkAction.OpenWebFallback(raftDataExportFallbackWebUrl(link, webBaseUrl))
    }
}
