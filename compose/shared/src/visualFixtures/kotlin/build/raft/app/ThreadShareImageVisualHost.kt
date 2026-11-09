package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import build.raft.app.compat.ui.raftSoftwareImageBitmap
import kotlinx.coroutines.delay

/**
 * `components.thread.message-share.preview[-desktop]`: the share image itself, the PNG Share
 * preview's Copy / Download hands out, for one message. [row] is drawn on the conversation
 * surface at the share card's row width ([cardWidth] less the card's padding), recorded into a
 * layer the way the export captures the live row, and handed to [renderThreadShareImage]; the
 * result is drawn at the top left one bitmap pixel per screen pixel, so the capture is the
 * exported PNG. The React twin exports the same message with the web exporter at 3x.
 *
 * [onImageBottom] reports the image's bottom edge (root px) for the component-bounds crop.
 */
@Composable
internal fun ThreadShareImageVisualCase(
    cardWidth: Dp,
    onImageBottom: (Int) -> Unit,
    row: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    // As the Android export: the header's 12px domain does not follow the font scale.
    val exportDensity = remember(density.density) { Density(density = density.density, fontScale = 1f) }
    val measurer =
        remember(fontFamilyResolver, exportDensity) {
            TextMeasurer(fontFamilyResolver, exportDensity, LayoutDirection.Ltr, cacheSize = 0)
        }
    val layer = rememberGraphicsLayer()
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val reportBottom by rememberUpdatedState(onImageBottom)
    LaunchedEffect(layer) {
        // Let the row settle (avatar, status dot, markdown layout) before the snapshot.
        repeat(6) { withFrameNanos {} }
        delay(ThreadShareImageVisualSettleMs)
        val rowImage = layer.toImageBitmap().raftSoftwareImageBitmap()
        image = renderThreadShareImage(listOf(rowImage), exportDensity, measurer, RaftColor.SurfaceWhite)
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                .background(RaftColor.SurfaceWhite),
    ) {
        Box(
            modifier =
                Modifier
                    .requiredWidth(cardWidth - ThreadShareImageRowInset * 2)
                    .background(RaftColor.SurfaceWhite)
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
        ) {
            row()
        }
        image?.let { bitmap ->
            Box(
                modifier =
                    Modifier
                        .layout { measurable, _ ->
                            val placeable = measurable.measure(Constraints.fixed(bitmap.width, bitmap.height))
                            layout(bitmap.width, bitmap.height) { placeable.place(0, 0) }
                        }
                        .drawBehind { drawImage(bitmap) }
                        .onGloballyPositioned { coordinates ->
                            reportBottom((coordinates.positionInRoot().y + coordinates.size.height).toInt())
                        },
            )
        }
    }
}

private const val ThreadShareImageVisualSettleMs = 800L
