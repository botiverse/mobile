package build.raft.app.widget

import android.content.Context
import android.graphics.Bitmap
import build.raft.app.PixelAvatarRasterCellCount
import build.raft.app.RaftPixelAvatarRaster
import build.raft.app.core.data.auth.androidMMKVLocalPreferenceKeyValueStore
import build.raft.app.network.SharedBinaryDownloader
import build.raft.app.raftApplyPersistedPixelAvatars
import build.raft.app.raftPixelAvatarActiveDataVersion
import build.raft.app.raftPixelAvatarRaster
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Task #155: remote avatars download through NetworkKMM (SharedBinaryDownloader, the app's image
 * path), so widgets get the same curl TLS trust and DNS-over-HTTPS fallback as the rest of the app.
 */
internal suspend fun Context.cacheRaftWidgetAvatarPath(wireValue: String): String? {
    val normalized = wireValue.trim().takeIf { it.isNotBlank() } ?: return null
    val directory = File(cacheDir, "slock-widget-avatars").also { it.mkdirs() }
    return when {
        normalized.startsWith("http://") || normalized.startsWith("https://") -> {
            val file = File(directory, "${normalized.sha256()}.png")
            if (file.isFile && file.length() > 0L) file.absolutePath else cacheRemoteAvatar(normalized, file)
        }
        normalized.startsWith("pixel:") || normalized.startsWith("random:") ->
            cachePixelAvatar(normalized, directory)
        else -> null
    }
}

private suspend fun cacheRemoteAvatar(url: String, file: File): String? =
    try {
        // fetchBytes throws on a non-2xx status or a transport failure: no avatar, the widget
        // keeps its letter placeholder.
        val bytes = SharedBinaryDownloader.fetchBytes(url).bytes
        if (bytes.isEmpty()) {
            null
        } else {
            file.writeBytes(bytes)
            file.absolutePath
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

private fun Context.cachePixelAvatar(wireValue: String, directory: File): String? =
    runCatching {
        raftApplyPersistedPixelAvatars(androidMMKVLocalPreferenceKeyValueStore(this))
        val raster = raftPixelAvatarRaster(wireValue.toSharedPixelAvatarWireValue()) ?: return@runCatching null
        // Key the render by the active table version so a runtime table update re-renders.
        val file = File(directory, "${wireValue.sha256()}-pixel-v$raftPixelAvatarActiveDataVersion.png")
        if (file.isFile && file.length() > 0L) return@runCatching file.absolutePath
        val bitmap = renderRaftWidgetPixelAvatar(raster)
        file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        file.absolutePath
    }.getOrNull()

/**
 * Legacy bare `random:<seed>` values render like the canonical `pixel:random:<seed>`; every
 * other value is passed through to the shared resolver unchanged.
 */
internal fun String.toSharedPixelAvatarWireValue(): String =
    if (startsWith(LegacyRandomPrefix)) "pixel:random:${removePrefix(LegacyRandomPrefix)}" else this

private const val LegacyRandomPrefix = "random:"

/** Pixel raster for a resolved avatar; split out (no Bitmap) so unit tests can compare it. */
internal fun RaftPixelAvatarRaster.widgetPixelArgb(size: Int = WidgetPixelAvatarSizePx): IntArray {
    val pixels = IntArray(size * size) { backgroundArgb }
    cellArgb.forEachIndexed { rowIndex, row ->
        row.forEachIndexed { columnIndex, color ->
            if ((color ushr 24) != 0) {
                val left = widgetPixelEdge(columnIndex, size)
                val top = widgetPixelEdge(rowIndex, size)
                val right = widgetPixelEdge(columnIndex + 1, size)
                val bottom = widgetPixelEdge(rowIndex + 1, size)
                for (y in top until bottom) {
                    for (x in left until right) pixels[y * size + x] = color
                }
            }
        }
    }
    return pixels
}

private fun renderRaftWidgetPixelAvatar(raster: RaftPixelAvatarRaster): Bitmap {
    val size = WidgetPixelAvatarSizePx
    return Bitmap.createBitmap(raster.widgetPixelArgb(size), size, size, Bitmap.Config.ARGB_8888)
}

private const val WidgetPixelAvatarSizePx = 48

private fun widgetPixelEdge(index: Int, extent: Int): Int =
    when {
        index <= 0 -> 0
        index >= PixelAvatarRasterCellCount -> extent
        else -> (extent.toFloat() * index / PixelAvatarRasterCellCount).roundToInt()
    }

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
