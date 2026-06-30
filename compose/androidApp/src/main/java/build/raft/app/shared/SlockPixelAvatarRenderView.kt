package build.raft.app.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import com.tencent.kuikly.core.render.android.expand.component.KRView
import kotlin.math.roundToInt

internal class SlockPixelAvatarRenderView(context: Context) : KRView(context) {
    private var backgroundColor: Int = Color.TRANSPARENT
    private var spans: List<PixelAvatarSpan> = emptyList()
    private var cacheKey: String = ""

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }

    init {
        setWillNotDraw(false)
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        val changed =
                when (propKey) {
                    PROP_BACKGROUND -> updateBackground(propValue.toString())
                    PROP_SPANS -> updateSpans(propValue.toString())
                    PROP_CACHE_KEY -> updateCacheKey(propValue.toString())
                    else -> return super.setProp(propKey, propValue)
                }
        if (changed) {
            invalidate()
        }
        return true
    }

    override fun resetProp(propKey: String): Boolean {
        val changed =
                when (propKey) {
                    PROP_BACKGROUND -> updateBackground("#00000000")
                    PROP_SPANS -> updateSpans("")
                    PROP_CACHE_KEY -> updateCacheKey("")
                    else -> return super.resetProp(propKey)
                }
        if (changed) {
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawWidth = width
        val drawHeight = height
        if (drawWidth <= 0 || drawHeight <= 0) return
        val bitmap = cachedBitmap(drawWidth, drawHeight)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    private fun cachedBitmap(drawWidth: Int, drawHeight: Int): Bitmap {
        val key = "$cacheKey|$drawWidth:$drawHeight"
        bitmapCache.get(key)?.let { return it }
        val bitmap = Bitmap.createBitmap(drawWidth, drawHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { canvas ->
            paint.color = backgroundColor
            canvas.drawRect(0f, 0f, drawWidth.toFloat(), drawHeight.toFloat(), paint)
            spans.forEach { span ->
                paint.color = span.color
                val left = pixelGridEdge(span.startColumnIndex, drawWidth)
                val right = pixelGridEdge(span.startColumnIndex + span.columnCount, drawWidth)
                val top = pixelGridEdge(span.rowIndex, drawHeight)
                val bottom = pixelGridEdge(span.rowIndex + 1, drawHeight)
                canvas.drawRect(left, top, right, bottom, paint)
            }
        }
        bitmapCache.put(key, bitmap)
        return bitmap
    }

    private fun updateBackground(value: String): Boolean {
        val next = parseColor(value)
        if (backgroundColor == next) return false
        backgroundColor = next
        return true
    }

    private fun updateSpans(value: String): Boolean {
        val next = parseSpans(value)
        if (spans == next) return false
        spans = next
        return true
    }

    private fun updateCacheKey(value: String): Boolean {
        if (cacheKey == value) return false
        cacheKey = value
        return true
    }

    private fun parseSpans(value: String): List<PixelAvatarSpan> {
        if (value.isBlank()) return emptyList()
        return value
                .split(";")
                .mapNotNull { raw ->
                    val parts = raw.split(",")
                    if (parts.size != 4) return@mapNotNull null
                    val rowIndex = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val startColumnIndex = parts[1].toIntOrNull() ?: return@mapNotNull null
                    val columnCount = parts[2].toIntOrNull() ?: return@mapNotNull null
                    val color = parseColor(parts[3])
                    PixelAvatarSpan(
                            rowIndex = rowIndex,
                            startColumnIndex = startColumnIndex,
                            columnCount = columnCount,
                            color = color
                    )
                }
    }

    private fun parseColor(value: String): Int =
            runCatching { Color.parseColor(value) }.getOrDefault(Color.TRANSPARENT)

    private fun pixelGridEdge(index: Int, extent: Int): Float =
            when {
                index <= 0 -> 0f
                index >= GRID_CELL_COUNT -> extent.toFloat()
                else -> (extent.toFloat() * index / GRID_CELL_COUNT).roundToInt().toFloat()
            }

    private data class PixelAvatarSpan(
            val rowIndex: Int,
            val startColumnIndex: Int,
            val columnCount: Int,
            val color: Int,
    )

    companion object {
        const val VIEW_NAME = "SlockPixelAvatarView"
        private const val PROP_BACKGROUND = "background"
        private const val PROP_SPANS = "spans"
        private const val PROP_CACHE_KEY = "cacheKey"
        private const val GRID_CELL_COUNT = 8
        private val bitmapCache = LruCache<String, Bitmap>(128)
    }
}
