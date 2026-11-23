@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app.data

import build.raft.app.raftApplyPersistedPixelAvatars
import build.raft.app.raftPixelAvatarRaster
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * Opaque RGBA8888 pixel-avatar bitmap payload returned to iOS Swift so Share Sheet
 * `INSendMessageIntent` suggestions can render bundled or runtime-downloaded pixel art cleanly
 * without per-pixel Objective-C crossings.
 */
class RaftIosPixelAvatarRgba(
    val size: Int,
    val backgroundArgb: Int,
    val rgbaData: NSData
)

private const val IosSharePixelAvatarMinSizePx = 48
private const val LegacyRandomAvatarPrefix = "random:"

private val iosShareColdStartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Resolves `pixel:<key>`, legacy `random:<seed>`, `pixel:random:<seed>`, and `pixel-human:`
 * avatars into a straight opaque RGBA8888 [NSData] buffer for CoreGraphics. Returns null for any
 * non-pixel wire value.
 */
fun raftIosRenderPixelAvatarRgba(wireValue: String): RaftIosPixelAvatarRgba? {
    val trimmed = wireValue.trim()
    if (
        !trimmed.startsWith("pixel:") &&
        !trimmed.startsWith(LegacyRandomAvatarPrefix) &&
        !trimmed.startsWith("pixel-human:")
    ) {
        return null
    }
    val normalized =
        if (trimmed.startsWith(LegacyRandomAvatarPrefix)) {
            "pixel:random:${trimmed.removePrefix(LegacyRandomAvatarPrefix)}"
        } else {
            trimmed
        }
    runCatching { raftApplyPersistedPixelAvatars(raftSharedPreferenceStore()) }
    val raster = raftPixelAvatarRaster(normalized) ?: return null
    val cells = raster.cellArgb.size
    if (cells <= 0) return null
    val size = maxOf(IosSharePixelAvatarMinSizePx, cells)

    val bgR = (raster.backgroundArgb ushr 16) and 0xFF
    val bgG = (raster.backgroundArgb ushr 8) and 0xFF
    val bgB = raster.backgroundArgb and 0xFF
    val bytes = ByteArray(size * size * 4)
    var fill = 0
    while (fill < bytes.size) {
        bytes[fill] = bgR.toByte()
        bytes[fill + 1] = bgG.toByte()
        bytes[fill + 2] = bgB.toByte()
        bytes[fill + 3] = 0xFF.toByte()
        fill += 4
    }

    raster.cellArgb.forEachIndexed { rowIndex, row ->
        val top = (rowIndex * size) / cells
        val bottom = ((rowIndex + 1) * size) / cells
        row.forEachIndexed { columnIndex, color ->
            val alpha = (color ushr 24) and 0xFF
            if (alpha != 0) {
                val srcR = (color ushr 16) and 0xFF
                val srcG = (color ushr 8) and 0xFF
                val srcB = color and 0xFF
                val outR: Int
                val outG: Int
                val outB: Int
                if (alpha == 0xFF) {
                    outR = srcR
                    outG = srcG
                    outB = srcB
                } else {
                    val invAlpha = 255 - alpha
                    outR = (srcR * alpha + bgR * invAlpha) / 255
                    outG = (srcG * alpha + bgG * invAlpha) / 255
                    outB = (srcB * alpha + bgB * invAlpha) / 255
                }
                val left = (columnIndex * size) / cells
                val right = ((columnIndex + 1) * size) / cells
                for (y in top until bottom) {
                    var pixelOffset = (y * size + left) * 4
                    for (x in left until right) {
                        bytes[pixelOffset] = outR.toByte()
                        bytes[pixelOffset + 1] = outG.toByte()
                        bytes[pixelOffset + 2] = outB.toByte()
                        bytes[pixelOffset + 3] = 0xFF.toByte()
                        pixelOffset += 4
                    }
                }
            }
        }
    }

    val data =
        bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
    return RaftIosPixelAvatarRgba(
        size = size,
        backgroundArgb = raster.backgroundArgb,
        rgbaData = data
    )
}

/**
 * Checks persisted auth state off the main thread on cold start and invokes [onSignedOut] ONLY
 * when the store reports an authoritative [RaftAuthSessionState.SignedOut]. Never fires on
 * [RaftAuthSessionState.SignedIn] or [RaftAuthSessionState.Corrupt].
 */
fun checkRaftIosColdStartSignedOut(onSignedOut: () -> Unit) {
    iosShareColdStartScope.launch {
        val state =
            runCatching {
                KeyValueAuthSessionStore(RaftSharedAuthSessionKeyValueStoreProvider.create().store)
                    .readSessionState()
            }.getOrNull()
        if (state is RaftAuthSessionState.SignedOut) {
            onSignedOut()
        }
    }
}
