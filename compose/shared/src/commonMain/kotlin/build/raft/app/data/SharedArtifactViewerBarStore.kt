package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The list the viewer's page has open, as its address says (`#panel=`, slock staging a2654a29f9
 * `artifactPageOpening.ts` `artifactPanelFromHash` / `artifactHashForPanel`).
 */
enum class ArtifactViewerPanel(val wire: String) {
    None("none"),
    Comments("comments"),
    Versions("versions"),
}

/**
 * The open list in [url]'s fragment: `#panel=comments` / `#panel=versions`; no fragment, an empty
 * one or `#panel=none` is none open. Any other fragment is not the page's list (null: unchanged).
 */
fun artifactViewerPanelOf(url: String): ArtifactViewerPanel? {
    val hash = url.indexOf('#').let { if (it < 0) "" else url.substring(it) }
    if (hash.isEmpty() || hash == "#") return ArtifactViewerPanel.None
    val match = Regex("^#panel=(comments|versions|none)$").find(hash) ?: return null
    return ArtifactViewerPanel.entries.first { it.wire == match.groupValues[1] }
}

/** The viewer bar's state. Nothing here blocks the page: with nothing read, the bar hides its counts. */
data class ArtifactViewerBarState(
    /** The viewer route's answer, kept until a new one lands; null before the first or when it failed. */
    val details: ArtifactViewerDetails? = null,
    /** The presence route's answer; null before it or when it failed. */
    val presence: ArtifactPresence? = null,
    /** Following, as shown (a toggle is shown at once and taken back if the write fails). */
    val following: Boolean? = null,
    val followInFlight: Boolean = false,
    /** The page's open list, from its address. */
    val panel: ArtifactViewerPanel = ArtifactViewerPanel.None,
    /** The last read of the viewer route failed and nothing is kept. */
    val detailsFailed: Boolean = false,
) {
    /**
     * "N read" as the web's summary line counts it ([artifactPresenceEntries]); null (hidden) when
     * presence isn't read or no one else is there. The viewer is "You" only with the viewer answer.
     */
    val readCount: Int?
        get() = presence?.let { artifactPresenceReadCount(artifactPresenceEntries(it, details?.viewerKey, details?.onCurrentVersion ?: false)) }
}

sealed interface ArtifactViewerBarIntent {
    /** Read the bar's counts (the viewer opens; the latest is read again). [version]: the pinned version shown. */
    data class Load(val version: Int?) : ArtifactViewerBarIntent

    /** The page's address shows another version (no read: the counts don't depend on it). */
    data class VersionShown(val version: Int?) : ArtifactViewerBarIntent

    /** The page's address says which list is open; comments closing reads the counts again. */
    data class PanelObserved(val panel: ArtifactViewerPanel) : ArtifactViewerBarIntent

    data object ToggleFollow : ArtifactViewerBarIntent
}

/**
 * The Artifact viewer's native bar for one Artifact in one Server. Reads are single-flight: a
 * [ArtifactViewerBarIntent.Load] while one is out runs once more after it, with the latest
 * version. A failed read keeps what was shown. Following is optimistic and rolled back on a failed
 * write; a second toggle while one is out is ignored. No polling: the counts are read on open and
 * when the comments list closes.
 */
class SharedArtifactViewerBarStore(
    private val repository: ArtifactViewerRepository,
    private val scope: CoroutineScope,
    private val serverId: String,
    private val artifactId: String,
    private val logger: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ArtifactViewerBarState())
    val state: StateFlow<ArtifactViewerBarState> = mutableState.asStateFlow()

    private var version: Int? = null
    private var loading = false
    private var loadAgain = false

    fun dispatch(intent: ArtifactViewerBarIntent) {
        when (intent) {
            is ArtifactViewerBarIntent.Load -> {
                version = intent.version
                load()
            }
            is ArtifactViewerBarIntent.VersionShown -> version = intent.version
            is ArtifactViewerBarIntent.PanelObserved -> {
                val previous = mutableState.value.panel
                if (previous == intent.panel) return
                mutableState.update { it.copy(panel = intent.panel) }
                if (previous == ArtifactViewerPanel.Comments) load()
            }
            ArtifactViewerBarIntent.ToggleFollow -> toggleFollow()
        }
    }

    private fun load() {
        if (loading) {
            loadAgain = true
            return
        }
        loading = true
        scope.launch {
            try {
                do {
                    loadAgain = false
                    val details = async { repository.details(serverId, artifactId, version) }
                    val presence = async { repository.presence(serverId, artifactId) }
                    apply(details.await(), presence.await())
                } while (loadAgain)
            } finally {
                loading = false
            }
        }
    }

    private fun apply(details: DataResult<ArtifactViewerDetails>, presence: DataResult<ArtifactPresence>) {
        mutableState.update { state ->
            var next = state
            next =
                when (details) {
                    is DataResult.Content ->
                        next.copy(
                            details = details.data,
                            // A toggle on its way wins over a read that may predate it.
                            following = if (next.followInFlight) next.following else details.data.following,
                            detailsFailed = false,
                        )
                    else -> next.copy(detailsFailed = next.details == null)
                }
            if (presence is DataResult.Content) next = next.copy(presence = presence.data)
            next
        }
        if (details !is DataResult.Content) logger("artifact_viewer_bar_details_failed")
        if (presence !is DataResult.Content) logger("artifact_viewer_bar_presence_failed")
    }

    private fun toggleFollow() {
        val before = mutableState.value
        val current = before.following ?: return
        if (before.followInFlight) return
        val wanted = !current
        mutableState.update { it.copy(following = wanted, followInFlight = true) }
        scope.launch {
            val answer = repository.setFollowing(serverId, artifactId, wanted)
            mutableState.update {
                when (answer) {
                    is DataResult.Content -> it.copy(following = answer.data, followInFlight = false)
                    else -> it.copy(following = current, followInFlight = false)
                }
            }
            if (answer !is DataResult.Content) logger("artifact_viewer_bar_follow_failed wanted=$wanted")
        }
    }
}
