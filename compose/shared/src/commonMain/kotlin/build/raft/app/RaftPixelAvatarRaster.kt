package build.raft.app

import androidx.compose.ui.graphics.toArgb
import build.raft.app.data.SharedPreferenceStore

/**
 * Compose-free view of a resolved pixel avatar for non-Compose hosts (the Android home-screen
 * widgets), so they render from the same data as the app — the runtime-downloaded table when
 * one is active, else the generated [PixelAvatarFixedSpecs] — instead of keeping a copy.
 *
 * [cellArgb] holds square rows of ARGB colors ([PixelAvatarRasterCellCount] for an agent's
 * `pixel:` avatar, the art's 112 for a person's built `pixel-human:` one); a fully transparent
 * cell (alpha 0) is not drawn.
 */
class RaftPixelAvatarRaster(
    val backgroundArgb: Int,
    val cellArgb: List<IntArray>
)

const val PixelAvatarRasterCellCount = 8

/**
 * Resolves `pixel:<key>` (unknown keys fall back to the active table's default key),
 * `pixel:random:<seed>` and a person's built `pixel-human:` avatar exactly like the in-app
 * avatar renderer. Returns null for any other wire value.
 */
fun raftPixelAvatarRaster(wireValue: String): RaftPixelAvatarRaster? {
    raftHumanPixelAvatarRaster(wireValue)?.let { human ->
        return RaftPixelAvatarRaster(
            backgroundArgb = human.backgroundArgb,
            cellArgb = List(human.size) { row -> human.argb.copyOfRange(row * human.size, (row + 1) * human.size) }
        )
    }
    val spec = pixelAvatarSpecForWireValue(wireValue) ?: return null
    return RaftPixelAvatarRaster(
        backgroundArgb = spec.background.toArgb(),
        cellArgb =
            spec.grid.map { row ->
                IntArray(row.length) { column -> (spec.palette[row[column]]?.toArgb()) ?: 0 }
            }
    )
}

/** Art revision of the people's built pixel avatars [raftPixelAvatarRaster] draws. */
val raftHumanPixelAvatarArtRevision: String
    get() = RaftHumanPixelAvatarArtData.artVersion

/** Version of the pixel-avatar table currently used by [raftPixelAvatarRaster]. */
val raftPixelAvatarActiveDataVersion: Int
    get() = RaftPixelAvatarRuntime.current.version

/**
 * Applies the persisted runtime pixel-avatar document from [store] (the `slockLocal` store the
 * shared store provider caches it in) when it is newer than the active table. For processes
 * that may render avatars before the shared store provider has started (widget broadcasts).
 * Never throws; invalid or stale documents leave the active table unchanged.
 */
fun raftApplyPersistedPixelAvatars(store: SharedPreferenceStore) {
    val persisted = KeyValuePixelAvatarStore(store)
    val version = persisted.readVersion() ?: return
    if (version <= RaftPixelAvatarRuntime.current.version) return
    RaftPixelAvatarRuntime.applyRuntimeJson(persisted.readJson(), source = "persisted")
}
