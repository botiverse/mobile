package build.raft.app

import androidx.compose.ui.graphics.toArgb
import build.raft.app.data.SharedPreferenceStore

/**
 * Compose-free view of a resolved pixel avatar for non-Compose hosts (the Android home-screen
 * widgets), so they render from the same data as the app — the runtime-downloaded table when
 * one is active, else the generated [PixelAvatarFixedSpecs] — instead of keeping a copy.
 *
 * [cellArgb] holds [PixelAvatarRasterCellCount] rows of [PixelAvatarRasterCellCount] ARGB
 * colors; a fully transparent cell (alpha 0) is not drawn.
 */
class RaftPixelAvatarRaster(
    val backgroundArgb: Int,
    val cellArgb: List<IntArray>
)

const val PixelAvatarRasterCellCount = 8

/**
 * Resolves `pixel:<key>` (unknown keys fall back to the active table's default key) and
 * `pixel:random:<seed>` exactly like the in-app avatar renderer. Returns null for any other
 * wire value.
 */
fun raftPixelAvatarRaster(wireValue: String): RaftPixelAvatarRaster? {
    val spec = pixelAvatarSpecForWireValue(wireValue) ?: return null
    return RaftPixelAvatarRaster(
        backgroundArgb = spec.background.toArgb(),
        cellArgb =
            spec.grid.map { row ->
                IntArray(row.length) { column -> (spec.palette[row[column]]?.toArgb()) ?: 0 }
            }
    )
}

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
