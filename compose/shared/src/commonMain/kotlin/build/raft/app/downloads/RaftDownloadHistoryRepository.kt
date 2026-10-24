package build.raft.app.downloads

import build.raft.app.data.SharedPreferenceStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val RAFT_DOWNLOAD_HISTORY_STORAGE_KEY: String = "downloadCenter:history.v1"

/**
 * Durable download-centre rows over the shared [SharedPreferenceStore] (AGENTS.md "Local
 * Preferences"): one JSON document under [RAFT_DOWNLOAD_HISTORY_STORAGE_KEY], newest first,
 * capped at [maxEntries]. An unreadable document reads as empty and is overwritten on the
 * next save.
 */
class RaftDownloadHistoryRepository internal constructor(
    private val store: SharedPreferenceStore,
    private val maxEntries: Int = 100
) {
    fun load(): List<RaftDownloadEntry> {
        val raw = store.getString(RAFT_DOWNLOAD_HISTORY_STORAGE_KEY)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { HistoryJson.decodeFromString(PersistedRaftDownloadHistory.serializer(), raw).entries }
            .getOrDefault(emptyList())
            .distinctBy { it.id }
            .take(maxEntries)
    }

    fun save(entries: List<RaftDownloadEntry>) {
        val document =
            PersistedRaftDownloadHistory(
                entries = entries.take(maxEntries.coerceAtLeast(0))
            )
        store.putString(
            RAFT_DOWNLOAD_HISTORY_STORAGE_KEY,
            if (document.entries.isEmpty()) null else HistoryJson.encodeToString(PersistedRaftDownloadHistory.serializer(), document)
        )
    }
}

@Serializable
private data class PersistedRaftDownloadHistory(
    val version: Int = 1,
    val entries: List<RaftDownloadEntry> = emptyList()
)

private val HistoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
