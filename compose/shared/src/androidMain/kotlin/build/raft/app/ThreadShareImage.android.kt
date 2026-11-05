package build.raft.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * The share image (see [renderThreadShareImage]) for the Android export: [rows] are the selected
 * messages as captured from the conversation, one bitmap per message, at the screen's density.
 * The card's surface is the conversation's, read from the first row's corner (web: the row's
 * resolved surface) unless [background] names it. Null when there is nothing to draw.
 */
fun renderThreadShareImageBitmap(
    context: Context,
    rows: List<Bitmap>,
    background: Int? = null,
): Bitmap? {
    val first = rows.firstOrNull() ?: return null
    // The header's 12px domain is a fixed size on the web; the user's font scale is already in the rows.
    val density = Density(density = context.resources.displayMetrics.density, fontScale = 1f)
    val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr, cacheSize = 0)
    return renderThreadShareImage(
        rows = rows.map { it.asImageBitmap() },
        density = density,
        measurer = measurer,
        background = Color(background ?: first.getPixel(0, 0)),
    )?.asAndroidBitmap()
}
