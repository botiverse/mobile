package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.runCatchingPreservingCancellation
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Runtime refresh of the pixel-avatar table from the deployed web app (task #352).
 *
 * The generated `RaftPixelAvatarData.kt` table stays the bundled fallback; at app start the
 * shared store provider applies the last MMKV-cached document synchronously, then fires a
 * background fetch of `<web-origin>/config/pixelAvatars.json` (the same canonical JSON the
 * generator consumes). A downloaded document is accepted only when it parses, its `version`
 * is >= the bundled [PixelAvatarDataVersion], and every grid validates — otherwise rendering
 * silently keeps the previous table and we log a diagnostic.
 */

private const val PixelAvatarRuntimeLogTag = "SlockPixelAvatarRuntime"

/** Path of the canonical pixel-avatar JSON on the deployed web app (web origin, not API origin). */
internal const val PixelAvatarRuntimeConfigPath = "config/pixelAvatars.json"

private val pixelAvatarRuntimeJson = Json { ignoreUnknownKeys = true }

// Mirrors the (file-private) PixelAvatarGridCellCount in RaftHomeComponents.kt.
private const val PixelAvatarRuntimeGridCellCount = 8

/** Wire shape of pixelAvatars.json (mirrors slock packages/web/assets/avatars/pixelAvatars.json). */
@Serializable
private data class PixelAvatarWireAvatar(
    val bg: String,
    val grid: List<String>
)

@Serializable
private data class PixelAvatarWireSeries(
    val key: String,
    val avatars: List<String>
)

@Serializable
private data class PixelAvatarWireDocument(
    val version: Int,
    val palette: Map<String, String>,
    val avatars: Map<String, PixelAvatarWireAvatar>,
    val reservedKeys: List<String> = emptyList(),
    val defaultKey: String,
    val series: List<PixelAvatarWireSeries> = emptyList()
)

/** One group of the avatar picker: a heading ([key], e.g. `bots`) over its avatars, in order. */
internal data class PixelAvatarSeries(
    val key: String,
    val avatars: List<String>
)

/** A resolved pixel-avatar table — same shapes as the generated RaftPixelAvatarData symbols. */
internal class PixelAvatarData(
    val version: Int,
    val palette: Map<Char, Color>,
    val paletteArgb: Map<Char, String>,
    val specs: Map<String, PixelAvatarSpec>,
    val reservedKeys: Set<String>,
    val defaultKey: String,
    val series: List<PixelAvatarSeries> = emptyList()
)

/**
 * Known avatar-background ARGB lookup for the native avatar view path: palette colors,
 * literal (non-palette) avatar backgrounds, plus the mobile-only dark random-avatar
 * background which is not part of pixelAvatars.json.
 */
internal fun pixelAvatarKnownBackgroundArgb(
    palette: Map<Char, Color>,
    paletteArgb: Map<Char, String>,
    literalBackgroundArgb: Map<Color, String>
): Map<Color, String> =
    palette
        .filterKeys { it != '_' }
        .entries
        .associate { entry -> entry.value to paletteArgb.getValue(entry.key) } +
        literalBackgroundArgb +
        mapOf(
            // Dark random-avatar background — mobile-only, not in pixelAvatars.json.
            Color(0xFF1E1E1C) to "#FF1E1E1C" // raft-color: pixel-art avatar palette
        )

private val PixelAvatarHexColorRegex = Regex("^#[0-9a-fA-F]{6}$")

private fun parsePixelAvatarHexColor(value: String): Color? {
    if (!PixelAvatarHexColorRegex.matches(value)) return null
    return Color(0xFF000000L or value.substring(1).toLong(16)) // raft-color: pixel-art avatar palette hex parse
}

private fun pixelAvatarArgbString(hexValue: String): String = "#FF${hexValue.substring(1).uppercase()}"

/**
 * Parses and validates a pixelAvatars.json document into a [PixelAvatarData], or returns null
 * (with a diagnostic log) when the document is malformed. Entry order is preserved — it is the
 * picker/grid order. Validation mirrors the generator: 8x8 grids, grid letters in the palette,
 * bg is a non-transparent palette letter or a #hex literal, defaultKey/reservedKeys name avatars.
 */
internal fun parsePixelAvatarData(jsonText: String): PixelAvatarData? {
    fun reject(reason: String): PixelAvatarData? {
        raftLogWarn(PixelAvatarRuntimeLogTag, "pixel_avatar_runtime rejected reason=$reason")
        return null
    }
    val wire =
        try {
            pixelAvatarRuntimeJson.decodeFromString(PixelAvatarWireDocument.serializer(), jsonText)
        } catch (error: Throwable) {
            return reject("decode_failed ${error::class.simpleName}")
        }
    if (wire.version < 1) return reject("version_invalid ${wire.version}")
    if (wire.avatars.isEmpty()) return reject("avatars_empty")
    val palette = LinkedHashMap<Char, Color>()
    val paletteArgb = LinkedHashMap<Char, String>()
    for ((letterText, value) in wire.palette) {
        val letter = letterText.singleOrNull() ?: return reject("palette_letter_invalid")
        if (value == "transparent") {
            palette[letter] = Color.Transparent
            paletteArgb[letter] = "#00000000"
        } else {
            palette[letter] = parsePixelAvatarHexColor(value) ?: return reject("palette_color_invalid letter=$letter")
            paletteArgb[letter] = pixelAvatarArgbString(value)
        }
    }
    class PendingAvatar(val key: String, val background: Color, val grid: List<String>)
    val literalBackgroundArgb = LinkedHashMap<Color, String>()
    val pending = ArrayList<PendingAvatar>()
    for ((key, avatar) in wire.avatars) {
        if (avatar.grid.size != PixelAvatarRuntimeGridCellCount || avatar.grid.any { it.length != PixelAvatarRuntimeGridCellCount }) {
            return reject("grid_shape_invalid key=$key")
        }
        avatar.grid.forEach { row ->
            row.forEach { letter -> if (letter !in palette) return reject("grid_letter_unknown key=$key") }
        }
        val background: Color
        if (avatar.bg.startsWith("#")) {
            background = parsePixelAvatarHexColor(avatar.bg) ?: return reject("background_invalid key=$key")
            literalBackgroundArgb[background] = pixelAvatarArgbString(avatar.bg)
        } else {
            val letter = avatar.bg.singleOrNull()?.takeIf { it != '_' } ?: return reject("background_invalid key=$key")
            background = palette[letter] ?: return reject("background_letter_unknown key=$key")
        }
        pending += PendingAvatar(key, background, avatar.grid)
    }
    if (wire.defaultKey !in wire.avatars) return reject("default_key_unknown")
    if (wire.reservedKeys.any { it !in wire.avatars }) return reject("reserved_key_unknown")
    if (wire.series.any { series -> series.avatars.any { it !in wire.avatars } }) return reject("series_key_unknown")
    val knownBackgroundArgb = pixelAvatarKnownBackgroundArgb(palette, paletteArgb, literalBackgroundArgb)
    val specs = LinkedHashMap<String, PixelAvatarSpec>()
    pending.forEach { avatar ->
        specs[avatar.key] =
            PixelAvatarSpec(
                background = avatar.background,
                grid = avatar.grid,
                palette = palette,
                paletteArgb = paletteArgb,
                backgroundArgbLookup = knownBackgroundArgb
            )
    }
    return PixelAvatarData(
        version = wire.version,
        palette = palette,
        paletteArgb = paletteArgb,
        specs = specs,
        reservedKeys = wire.reservedKeys.toSet(),
        defaultKey = wire.defaultKey,
        series = wire.series.map { PixelAvatarSeries(it.key, it.avatars) }
    )
}

/**
 * Holder for the active pixel-avatar table. Starts from the generated bundled table and swaps
 * to a cached/downloaded document once one validates. Backed by snapshot state so composables
 * resolving specs recompose when a fetch lands after first render.
 */
internal object RaftPixelAvatarRuntime {
    val bundled: PixelAvatarData =
        PixelAvatarData(
            version = PixelAvatarDataVersion,
            palette = PixelAvatarPalette,
            paletteArgb = PixelAvatarPaletteArgb,
            specs = PixelAvatarFixedSpecs,
            reservedKeys = PixelAvatarReservedKeys,
            defaultKey = PixelAvatarDefaultKey,
            series = PixelAvatarBundledSeries
        )

    var current: PixelAvatarData by mutableStateOf(bundled)
        private set

    /**
     * Parses [jsonText] and swaps it in when valid and its version is >= [minimumVersion]
     * (the bundled version by default — a downloaded document must never roll the table back).
     * Returns the accepted data, or null when the table was left unchanged. Never throws —
     * rendering must not crash on bad cached/downloaded data.
     *
     * A document whose version equals the already-active table's is NOT swapped: the version
     * is the publisher's identity for the whole table, so an equal version is byte-identical
     * for every spec the renderer resolves. Re-swapping it would recompose every pixel avatar
     * and reopen the painter placeholder window for no change — the startup "cache then fetch"
     * path does this on every launch (Hands 9ea07421, visible as avatars flashing blank).
     */
    fun applyRuntimeJson(
        jsonText: String?,
        source: String,
        minimumVersion: Int = bundled.version
    ): PixelAvatarData? {
        if (jsonText.isNullOrBlank()) return null
        val data = parsePixelAvatarData(jsonText) ?: return null
        if (data.version < minimumVersion) {
            raftLogInfo(
                PixelAvatarRuntimeLogTag,
                "pixel_avatar_runtime stale source=$source version=${data.version} minimum=$minimumVersion"
            )
            return null
        }
        if (data.version == current.version) {
            raftLogInfo(
                PixelAvatarRuntimeLogTag,
                "pixel_avatar_runtime unchanged source=$source version=${data.version}"
            )
            return null
        }
        current = data
        raftLogInfo(
            PixelAvatarRuntimeLogTag,
            "pixel_avatar_runtime applied source=$source version=${data.version} avatars=${data.specs.size}"
        )
        return data
    }

    fun resetForTest() {
        current = bundled
    }
}

/**
 * The avatar picker's groups for [data] (web `AVATAR_SERIES`): each series with only the avatars
 * the table has and the picker may offer (not reserved), then, in one untitled group (null key),
 * any pickable avatar no series names, so a table without series (or a newer avatar) still shows.
 */
internal fun pixelAvatarPickerGroups(data: PixelAvatarData = RaftPixelAvatarRuntime.current): List<PixelAvatarPickerGroup> {
    val pickable = data.specs.keys.filterNot { it in data.reservedKeys }
    val pickableSet = pickable.toSet()
    val named = HashSet<String>()
    val groups = ArrayList<PixelAvatarPickerGroup>()
    data.series.forEach { series ->
        val avatars = series.avatars.filter { it in pickableSet && named.add(it) }
        if (avatars.isNotEmpty()) groups += PixelAvatarPickerGroup(series.key, avatars)
    }
    val rest = pickable.filterNot { it in named }
    if (rest.isNotEmpty()) groups += PixelAvatarPickerGroup(null, rest)
    return groups
}

/** One group of the avatar picker; [seriesKey] null is the untitled group. */
internal data class PixelAvatarPickerGroup(
    val seriesKey: String?,
    val avatars: List<String>
)

/** Active default avatar key — the runtime table's defaultKey (bundled `robot` until swapped). */
internal val PixelAvatarActiveDefaultKey: String
    get() = RaftPixelAvatarRuntime.current.defaultKey

/**
 * MMKV-backed cache of the last accepted pixelAvatars.json (namespace `slockLocal`, mirroring
 * KeyValueChatLayoutPreferenceStore key style).
 */
internal class KeyValuePixelAvatarStore(
    private val store: SharedPreferenceStore
) {
    fun readJson(): String? = store.getString(KEY_PIXEL_JSON)

    fun readVersion(): Int? = store.getString(KEY_PIXEL_JSON_VERSION)?.toIntOrNull()

    fun save(json: String, version: Int, fetchedAtEpochMillis: Long) {
        store.putString(KEY_PIXEL_JSON, json)
        store.putString(KEY_PIXEL_JSON_VERSION, version.toString())
        store.putString(KEY_PIXEL_JSON_FETCHED_AT, fetchedAtEpochMillis.toString())
    }

    private companion object {
        const val KEY_PIXEL_JSON = "avatars.pixelJson"
        const val KEY_PIXEL_JSON_VERSION = "avatars.pixelJsonVersion"
        const val KEY_PIXEL_JSON_FETCHED_AT = "avatars.pixelJsonFetchedAt"
    }
}

/**
 * Fetches `<web-origin>/config/pixelAvatars.json` and, when accepted by
 * [RaftPixelAvatarRuntime.applyRuntimeJson], persists it to [store]. Fire-and-forget:
 * every failure is swallowed into a diagnostic log. [webBaseUrl] is the environment's web-app
 * base URL (`SharedApiEnvironmentStore.current().webUrl`), not the API base URL — the JSON is
 * a static file served by the deployed web app.
 */
internal suspend fun refreshPixelAvatarsFromWeb(
    networkClient: RaftNetworkClient,
    store: KeyValuePixelAvatarStore,
    webBaseUrl: String?,
    nowEpochMillis: () -> Long = { defaultNowMillis() }
) {
    val url = sharedSettingsWebUrl(webBaseUrl, PixelAvatarRuntimeConfigPath)
    val response =
        runCatchingPreservingCancellation {
            networkClient.requestRawAbsoluteUrl(
                priority = DataRequestPriority.Background,
                url = url
            )
        }.getOrElse { error ->
            raftLogWarn(PixelAvatarRuntimeLogTag, "pixel_avatar_runtime fetch_failed error=${error::class.simpleName}")
            return
        }
    if (!response.isSuccessful) {
        raftLogWarn(PixelAvatarRuntimeLogTag, "pixel_avatar_runtime fetch_http_error code=${response.code}")
        return
    }
    val bodyText = response.bodyText?.takeIf { it.isNotBlank() }
    if (bodyText == null) {
        raftLogWarn(PixelAvatarRuntimeLogTag, "pixel_avatar_runtime fetch_body_empty")
        return
    }
    val applied = RaftPixelAvatarRuntime.applyRuntimeJson(bodyText, source = "fetch") ?: return
    store.save(json = bodyText, version = applied.version, fetchedAtEpochMillis = nowEpochMillis())
}
