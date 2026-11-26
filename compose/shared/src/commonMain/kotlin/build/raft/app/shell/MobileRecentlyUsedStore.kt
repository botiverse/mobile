package build.raft.app

import build.raft.app.data.RaftRailAppPlacePrefix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Maximum number of recently opened Artifact / Raft App entries kept per account × server. */
const val MOBILE_RECENTLY_USED_MAX_ENTRIES: Int = 4

/** Account × server key for [MobileRecentlyUsedStore]. */
data class MobileRecentlyUsedScope(
    val accountId: String,
    val serverId: String,
)

/**
 * One recently opened Artifact or installed Raft App recorded by [MobileRecentlyUsedStore] for the
 * mobile Server Tab Popover (`#proj-mobile:#329`).
 */
sealed interface MobileRecentlyUsedEntry {
    /** Canonical dedupe & tab-bar exclusion key (`artifact:<id>` or `app:<lowercase-clientId>`). */
    val placeKey: String

    /** Human-readable title displayed in the Recently used section. */
    val title: String

    data class Artifact(
        val id: String,
        override val title: String,
    ) : MobileRecentlyUsedEntry {
        override val placeKey: String get() = "artifact:$id"
    }

    data class App(
        val clientId: String,
        val name: String,
        val iconUrl: String? = null,
        val homepageUrl: String,
    ) : MobileRecentlyUsedEntry {
        override val placeKey: String get() = "$RaftRailAppPlacePrefix${clientId.lowercase()}"
        override val title: String get() = name
    }
}

/**
 * Pure-Kotlin per-account × per-server recently-used store for mobile navigation (`#proj-mobile:#329`):
 * records up to [MOBILE_RECENTLY_USED_MAX_ENTRIES] (4) most-recently-opened Artifact and Raft App
 * entries, deduplicating by kind + id, moving re-opened items to the front, and filtering out any
 * place key currently on the tab bar.
 */
class MobileRecentlyUsedStore(
    private val maxEntries: Int = MOBILE_RECENTLY_USED_MAX_ENTRIES,
) {
    private val mutableState = MutableStateFlow<Map<MobileRecentlyUsedScope, List<MobileRecentlyUsedEntry>>>(emptyMap())
    val state: StateFlow<Map<MobileRecentlyUsedScope, List<MobileRecentlyUsedEntry>>> = mutableState.asStateFlow()

    fun recordArtifact(
        accountId: String?,
        serverId: String?,
        artifactId: String,
        title: String,
    ) {
        val scope = scopeOrNull(accountId, serverId) ?: return
        val normalizedId = artifactId.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedTitle = title.trim().takeIf { it.isNotEmpty() } ?: return
        record(scope, MobileRecentlyUsedEntry.Artifact(id = normalizedId, title = normalizedTitle))
    }

    fun recordApp(
        accountId: String?,
        serverId: String?,
        clientId: String,
        name: String,
        iconUrl: String? = null,
        homepageUrl: String,
    ) {
        val scope = scopeOrNull(accountId, serverId) ?: return
        val normalizedClientId = clientId.trim().lowercase().takeIf { it.isNotEmpty() } ?: return
        val normalizedName = name.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedHomepageUrl = homepageUrl.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedIconUrl = iconUrl?.trim()?.takeIf { it.isNotEmpty() }
        record(
            scope,
            MobileRecentlyUsedEntry.App(
                clientId = normalizedClientId,
                name = normalizedName,
                iconUrl = normalizedIconUrl,
                homepageUrl = normalizedHomepageUrl,
            ),
        )
    }

    /**
     * Returns recently opened entries for ([accountId], [serverId]) in most-recent-first order,
     * excluding any entry whose canonical [MobileRecentlyUsedEntry.placeKey] is in [excludedPlaceKeys].
     */
    fun recentItems(
        accountId: String?,
        serverId: String?,
        excludedPlaceKeys: Set<String> = emptySet(),
    ): List<MobileRecentlyUsedEntry> {
        val scope = scopeOrNull(accountId, serverId) ?: return emptyList()
        val entries = mutableState.value[scope].orEmpty()
        if (entries.isEmpty() || excludedPlaceKeys.isEmpty()) return entries
        val normalizedExcluded = excludedPlaceKeys.mapTo(hashSetOf(), ::normalizeExcludedPlaceKey)
        return entries.filter { entry -> normalizeExcludedPlaceKey(entry.placeKey) !in normalizedExcluded }
    }

    fun clearServer(accountId: String?, serverId: String?) {
        val scope = scopeOrNull(accountId, serverId) ?: return
        mutableState.update { current -> if (scope in current) current - scope else current }
    }

    fun clearAccount(accountId: String?) {
        val normalizedAccount = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        mutableState.update { current ->
            current.filterKeys { it.accountId != normalizedAccount }
        }
    }

    fun clear() {
        mutableState.value = emptyMap()
    }

    private fun record(scope: MobileRecentlyUsedScope, entry: MobileRecentlyUsedEntry) {
        val limit = maxEntries.coerceAtLeast(1)
        mutableState.update { current ->
            val existing = current[scope].orEmpty()
            val updated = (listOf(entry) + existing.filterNot { sameEntry(it, entry) }).take(limit)
            current + (scope to updated)
        }
    }

    private fun sameEntry(a: MobileRecentlyUsedEntry, b: MobileRecentlyUsedEntry): Boolean =
        when {
            a is MobileRecentlyUsedEntry.Artifact && b is MobileRecentlyUsedEntry.Artifact -> a.id == b.id
            a is MobileRecentlyUsedEntry.App && b is MobileRecentlyUsedEntry.App ->
                a.clientId.equals(b.clientId, ignoreCase = true)
            else -> false
        }

    private fun scopeOrNull(accountId: String?, serverId: String?): MobileRecentlyUsedScope? {
        val normalizedAccount = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedServer = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return MobileRecentlyUsedScope(accountId = normalizedAccount, serverId = normalizedServer)
    }

    private fun normalizeExcludedPlaceKey(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith(RaftRailAppPlacePrefix, ignoreCase = true)) {
            "$RaftRailAppPlacePrefix${trimmed.substring(RaftRailAppPlacePrefix.length).lowercase()}"
        } else {
            trimmed
        }
    }
}
