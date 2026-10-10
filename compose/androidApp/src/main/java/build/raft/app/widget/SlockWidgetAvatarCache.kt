package build.raft.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import build.raft.app.network.SharedBinaryDownloader
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Task #155: remote avatars download through NetworkKMM (SharedBinaryDownloader, the app's image
 * path), so widgets get the same curl TLS trust and DNS-over-HTTPS fallback as the rest of the app.
 */
internal suspend fun Context.cacheSlockWidgetAvatarPath(wireValue: String): String? {
    val normalized = wireValue.trim().takeIf { it.isNotBlank() } ?: return null
    val directory = File(cacheDir, "slock-widget-avatars").also { it.mkdirs() }
    val file = File(directory, "${normalized.sha256()}.png")
    if (file.isFile && file.length() > 0L) return file.absolutePath
    return when {
        normalized.startsWith("http://") || normalized.startsWith("https://") ->
            cacheRemoteAvatar(normalized, file)
        normalized.startsWith("pixel:") || normalized.startsWith("random:") ->
            cachePixelAvatar(normalized, file)
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

private fun cachePixelAvatar(wireValue: String, file: File): String? =
    runCatching {
        val spec = wireValue.toWidgetPixelAvatarSpec()
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = false
            style = Paint.Style.FILL
        }
        Canvas(bitmap).also { canvas ->
            paint.color = spec.background
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
            spec.grid.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { columnIndex, key ->
                    val color = WidgetPixelPalette[key] ?: Color.TRANSPARENT
                    if (color != Color.TRANSPARENT) {
                        paint.color = color
                        canvas.drawRect(
                            widgetPixelEdge(columnIndex, bitmap.width),
                            widgetPixelEdge(rowIndex, bitmap.height),
                            widgetPixelEdge(columnIndex + 1, bitmap.width),
                            widgetPixelEdge(rowIndex + 1, bitmap.height),
                            paint
                        )
                    }
                }
            }
        }
        file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        file.absolutePath
    }.getOrNull()

private data class WidgetPixelAvatarSpec(
    val background: Int,
    val grid: List<String>
)

private fun String.toWidgetPixelAvatarSpec(): WidgetPixelAvatarSpec {
    removePrefixOrNull("pixel:")?.let { key ->
        return WidgetPixelFixedSpecs[key] ?: WidgetPixelFixedSpecs.getValue("robot")
    }
    removePrefixOrNull("random:")?.let { seed ->
        val options = WidgetPixelFixedSpecs.values.toList()
        return options[(seed.pixelHash().absoluteValueCompat()) % options.size]
    }
    return WidgetPixelFixedSpecs.getValue("robot")
}

private val WidgetPixelPalette =
    mapOf(
        '_' to Color.TRANSPARENT,
        'K' to Color.rgb(20, 17, 17),
        'W' to Color.WHITE,
        'P' to Color.rgb(254, 125, 168),
        'O' to Color.rgb(248, 161, 111),
        'Y' to Color.rgb(255, 212, 64),
        'L' to Color.rgb(169, 216, 119),
        'C' to Color.rgb(39, 204, 243),
        'V' to Color.rgb(187, 175, 230),
        'B' to Color.rgb(87, 101, 116),
        'R' to Color.rgb(249, 114, 100),
        'G' to Color.rgb(157, 202, 170)
    )

private val WidgetPixelFixedSpecs =
    mapOf(
        "robot" to WidgetPixelAvatarSpec(
            WidgetPixelPalette.getValue('C'),
            listOf("_KKKKKK_", "KCKWWKCK", "KWKWWKWK", "KKKKKKKK", "_KWKKWK_", "_KKKKKK_", "_KK__KK_", "_KK__KK_")
        ),
        "ghost" to WidgetPixelAvatarSpec(
            WidgetPixelPalette.getValue('V'),
            listOf("__KKKK__", "_KWWWWK_", "KWKWWKWK", "KWWWWWWK", "KWWWWWWK", "KWWKKWWK", "KWKWWKWK", "K_K__K_K")
        ),
        "alien" to WidgetPixelAvatarSpec(
            WidgetPixelPalette.getValue('L'),
            listOf("_KKKKKK_", "KGGGGGGK", "KGKKKKGK", "KGGGGGGK", "_KGGGGK_", "__KGGK__", "__KGGK__", "_KK__KK_")
        ),
        "cat" to WidgetPixelAvatarSpec(
            WidgetPixelPalette.getValue('Y'),
            listOf("K______K", "KK____KK", "KYKYYKYK", "KYYYYYYK", "KYKYYKYK", "_KYYYYK_", "__KYYK__", "___KK___")
        ),
        "flame" to WidgetPixelAvatarSpec(
            WidgetPixelPalette.getValue('O'),
            listOf("___K____", "__KRK___", "__KRKK__", "_KORRRK_", "_KOORRK_", "KYOOORK_", "KYYOOK__", "_KKKK___")
        )
    )

private fun widgetPixelEdge(index: Int, extent: Int): Float =
    when {
        index <= 0 -> 0f
        index >= 8 -> extent.toFloat()
        else -> (extent.toFloat() * index / 8).roundToInt().toFloat()
    }

private fun String.removePrefixOrNull(prefix: String): String? =
    takeIf { startsWith(prefix) }?.removePrefix(prefix)?.ifBlank { "robot" }

private fun String.pixelHash(): Int =
    fold(0) { hash, char -> (hash * 31) + char.code }

private fun Int.absoluteValueCompat(): Int =
    if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
