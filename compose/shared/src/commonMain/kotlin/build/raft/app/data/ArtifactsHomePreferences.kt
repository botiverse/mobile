package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** web `ARTIFACT_DASHBOARD_VIEWS`: tiles (the default) or one row per Artifact. */
enum class ArtifactsHomeView(val wire: String) {
    Grid("grid"), List("list");

    companion object {
        val Default = Grid
    }
}

/** What the Artifacts home keeps on the device (web `artifactDashboardPrefs.ts`): the view and the sort. */
data class ArtifactsHomePrefs(
    val view: ArtifactsHomeView = ArtifactsHomeView.Default,
    val sort: ArtifactsHomeSort = ArtifactsHomeSort.Default,
)

/**
 * The web's one key (`raft:artifacts:dashboard:v1`) and shape (`view`, `listSort`), so the two read
 * alike in a diagnostics dump. Anything unreadable is the default, so a newer or older build never
 * breaks on another's value. The table's column widths are web-only (the app has no table).
 */
class ArtifactsHomePreferenceRepository(private val store: SharedPreferenceStore) {
    fun read(): ArtifactsHomePrefs = parseArtifactsHomePrefs(runCatching { store.getString(KEY) }.getOrNull())

    fun write(prefs: ArtifactsHomePrefs) {
        // Storage full or blocked: the choice holds until the page closes.
        runCatching { store.putString(KEY, encodeArtifactsHomePrefs(prefs)) }
    }

    companion object {
        const val KEY = "raft:artifacts:dashboard:v1"
    }
}

private val artifactsHomePrefsJson = Json { ignoreUnknownKeys = true }

internal fun parseArtifactsHomePrefs(raw: String?): ArtifactsHomePrefs {
    val root = raw?.let { runCatching { artifactsHomePrefsJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return ArtifactsHomePrefs()
    val view = (root["view"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val sort = (root["listSort"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return ArtifactsHomePrefs(
        view = ArtifactsHomeView.entries.firstOrNull { it.wire == view } ?: ArtifactsHomeView.Default,
        sort = ArtifactsHomeSort.entries.firstOrNull { it.wire == sort } ?: ArtifactsHomeSort.Default,
    )
}

internal fun encodeArtifactsHomePrefs(prefs: ArtifactsHomePrefs): String =
    buildJsonObject {
        put("view", prefs.view.wire)
        put("listSort", prefs.sort.wire)
    }.toString()
