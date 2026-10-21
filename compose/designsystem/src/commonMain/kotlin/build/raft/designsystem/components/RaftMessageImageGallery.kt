package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * raft-ui MessageImageGallery (`message-image-gallery.recipe.ts`,
 * `message-image-gallery-rows.ts`) on a phone viewport (< 640px, so the `sm:` / `md:`
 * steps never apply). CSS px map 1:1 to dp.
 */
object RaftMessageImageGalleryDefaults {
    /** Root `max-w-[min(360px,calc(100vw-112px))]`; the caller's width supplies the second term. */
    val MaxWidth = 360.dp

    /** `SINGLE_IMAGE_MAX_WIDTH` x `SINGLE_IMAGE_MAX_HEIGHT`: a lone image's reserved box cap. */
    val SingleMaxWidth = 416.dp
    val SingleMaxHeight = 288.dp

    /** A lone image without known dimensions reserves `min(11rem, 100%)` at 4 / 3. */
    val SingleFallbackWidth = 176.dp
    const val SingleFallbackAspectRatio = 4f / 3f

    /** `gap-1` between rows and cells. */
    val Gap = 4.dp

    /** `auto-rows-[8rem]` for rows of one or two; `auto-rows-[7rem]` for rows of three. */
    val RowHeight = 128.dp
    val TripleRowHeight = 112.dp

    /** `has-overlay:opacity-70`: an image still uploading. */
    const val PendingAlpha = 0.7f

    /** `hover:backdrop-brightness-95`: the image dims a step while pressed. */
    val PressedScrim = Color.Black.copy(alpha = 0.05f)
}

/** An image's intrinsic pixel size, when the server knows it. */
@Immutable
data class RaftMessageImageDimensions(val width: Int? = null, val height: Int? = null)

/** How the media fills its cell: cropped (`cover`), or fully visible on `fill-muted` (`contain`). */
enum class RaftMessageImageFit { Cover, Contain }

/** One cell: [index] into the gallery's images, how it fills, and whether it is a lone image. */
@Immutable
data class RaftMessageImageItemLayout(val index: Int, val fit: RaftMessageImageFit, val single: Boolean = false)

@Immutable
data class RaftMessageImageRow(val items: List<RaftMessageImageItemLayout>)

private enum class RaftMessageImageAspect { Wide, Tall, Normal }

private fun RaftMessageImageDimensions.validRatio(): Float? {
    val w = width ?: return null
    val h = height ?: return null
    if (w <= 0 || h <= 0) return null
    return w.toFloat() / h.toFloat()
}

private fun RaftMessageImageDimensions.aspect(): RaftMessageImageAspect {
    val ratio = validRatio() ?: return RaftMessageImageAspect.Normal
    return when {
        ratio >= 2.2f -> RaftMessageImageAspect.Wide
        ratio <= 0.55f -> RaftMessageImageAspect.Tall
        else -> RaftMessageImageAspect.Normal
    }
}

/**
 * `buildMessageImageGalleryRows`: a wide image (ratio >= 2.2) takes a row of its own; the
 * others fill rows of three, except a buffer of exactly four, which splits two and two.
 * Normal images crop, wide and tall ones (<= 0.55) stay whole. A lone image keeps its
 * proportions and never crops.
 */
fun buildRaftMessageImageGalleryRows(images: List<RaftMessageImageDimensions>): List<RaftMessageImageRow> {
    if (images.isEmpty()) return emptyList()
    if (images.size == 1) {
        return listOf(RaftMessageImageRow(listOf(RaftMessageImageItemLayout(0, RaftMessageImageFit.Contain, single = true))))
    }
    fun item(index: Int) =
        RaftMessageImageItemLayout(
            index = index,
            fit = if (images[index].aspect() == RaftMessageImageAspect.Normal) RaftMessageImageFit.Cover else RaftMessageImageFit.Contain,
        )
    val rows = mutableListOf<RaftMessageImageRow>()
    val buffer = mutableListOf<Int>()
    fun flush() {
        if (buffer.isEmpty()) return
        val chunk = if (buffer.size == 4) 2 else 3
        buffer.chunked(chunk).forEach { indices -> rows += RaftMessageImageRow(indices.map(::item)) }
        buffer.clear()
    }
    images.forEachIndexed { index, image ->
        if (image.aspect() == RaftMessageImageAspect.Wide) {
            flush()
            rows += RaftMessageImageRow(listOf(item(index)))
        } else {
            buffer += index
        }
    }
    flush()
    return rows
}

/**
 * The box a lone image reserves before it loads: its own size scaled down (never up) to
 * fit [RaftMessageImageGalleryDefaults.SingleMaxWidth] x `SingleMaxHeight`, then capped
 * to [availableWidth] at the same ratio. Unknown dimensions reserve 176dp at 4 / 3.
 */
fun raftMessageImageSingleSize(image: RaftMessageImageDimensions, availableWidth: Dp): DpSize {
    val ratio = image.validRatio()
    if (ratio == null) {
        val width = min(RaftMessageImageGalleryDefaults.SingleFallbackWidth, availableWidth)
        return DpSize(width, width / RaftMessageImageGalleryDefaults.SingleFallbackAspectRatio)
    }
    val w = image.width!!.toFloat()
    val h = image.height!!.toFloat()
    val scale = min(min(RaftMessageImageGalleryDefaults.SingleMaxWidth.value / w, RaftMessageImageGalleryDefaults.SingleMaxHeight.value / h), 1f)
    val reserved = max(1, (w * scale).roundToInt()).dp
    val width = min(reserved, availableWidth)
    return DpSize(width, width / ratio)
}

/**
 * One image cell (`message-image-gallery-item`): `fill-muted` behind the media (the
 * letterbox of a `contain` image), a `line-hairline` border, `rounded-control`, or
 * `rounded-container` for a lone image. Size it with [modifier]; [content] fills it.
 * Pressing dims the image a step; keyboard focus draws the inset outline.
 */
@Composable
fun RaftMessageImageFrame(
    modifier: Modifier = Modifier,
    single: Boolean = false,
    pending: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val radius = if (single) RaftRadius.Container else RaftRadius.Control
    val shape = RoundedCornerShape(radius)
    val source = remember { MutableInteractionSource() }
    val isPressed = raftIsPressed(source)
    val pressed = onClick != null && isPressed
    val isFocused = raftIsFocused(source)
    val focused = onClick != null && isFocused
    Box(
        modifier
            .then(if (pending) Modifier.alpha(RaftMessageImageGalleryDefaults.PendingAlpha) else Modifier)
            .clip(shape)
            .background(colors.fillMuted)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = source,
                        indication = null,
                        onClickLabel = onClickLabel,
                        role = Role.Image,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .border(RaftStroke.Hairline, colors.lineHairline, shape)
            .raftInsetFocusOutline(focused, colors.foreground, radius),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (pressed) Box(Modifier.matchParentSize().background(RaftMessageImageGalleryDefaults.PressedScrim))
    }
}

/**
 * raft-ui MessageImageGallery: the inline images of a message, laid out by
 * [buildRaftMessageImageGalleryRows] within `min(360dp, available width)`. A lone image
 * reserves its own proportions ([raftMessageImageSingleSize]); rows of one or two are
 * 128dp tall, rows of three 112dp in two columns (the phone grid), all 4dp apart.
 * [image] draws one image into its frame, honouring the layout's [RaftMessageImageFit].
 */
@Composable
fun RaftMessageImageGallery(
    images: List<RaftMessageImageDimensions>,
    modifier: Modifier = Modifier,
    pending: (index: Int) -> Boolean = { false },
    onClick: ((index: Int) -> Unit)? = null,
    onClickLabel: (index: Int) -> String? = { null },
    image: @Composable BoxScope.(RaftMessageImageItemLayout) -> Unit,
) {
    val rows = remember(images) { buildRaftMessageImageGalleryRows(images) }
    if (rows.isEmpty()) return
    BoxWithConstraints(modifier) {
        val available = min(RaftMessageImageGalleryDefaults.MaxWidth, maxWidth)
        val gap = RaftMessageImageGalleryDefaults.Gap
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            rows.forEach { row ->
                val lone = row.items.singleOrNull()?.takeIf { it.single }
                if (lone != null) {
                    RaftMessageImageFrame(
                        modifier = Modifier.size(raftMessageImageSingleSize(images[lone.index], available)),
                        single = true,
                        pending = pending(lone.index),
                        onClick = onClick?.let { { it(lone.index) } },
                        onClickLabel = onClickLabel(lone.index),
                    ) { image(lone) }
                    return@forEach
                }
                // Phone grid: one or two columns; a row of three wraps its third into a
                // second grid row of the same height (`grid-cols-2 md:grid-cols-3`).
                val columns = if (row.items.size == 3) 2 else row.items.size
                val height = if (row.items.size == 3) RaftMessageImageGalleryDefaults.TripleRowHeight else RaftMessageImageGalleryDefaults.RowHeight
                val cellWidth = (available - gap * (columns - 1)) / columns
                row.items.chunked(columns).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        line.forEach { item ->
                            RaftMessageImageFrame(
                                modifier = Modifier.size(cellWidth, height),
                                pending = pending(item.index),
                                onClick = onClick?.let { { it(item.index) } },
                                onClickLabel = onClickLabel(item.index),
                            ) { image(item) }
                        }
                    }
                }
            }
        }
    }
}
