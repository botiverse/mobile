package com.tencent.kuiklybase.markdown.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlaceholderVerticalAlign
import com.tencent.kuikly.core.base.Size

interface ImageTransformer {
    /**
     * Will retrieve the [ImageData] from an image link/url
     */
    @Composable
    fun transform(link: String, containerSize: Size?): ImageData?

    /**
     * Returns the detected intrinsic size of the painter
     */
    @Composable
    fun intrinsicSize(painter: Painter): Size {
        return Size(0F, 0F)
    }

    /**
     * The expected placeholderSize. Note: The same size is shared for all inline images within a single MarkdownText item.
     */
    fun placeholderConfig(containerSize: Size, intrinsicImageSize: Size): PlaceholderConfig {
        return PlaceholderConfig(
            if (containerSize.isUnspecified) {
                Size(180f, 180f)
            } else if (intrinsicImageSize.isUnspecified) {
                Size(containerSize.width, 180f)
            } else {
                val width = minOf(intrinsicImageSize.width, containerSize.width)
                val height = if (intrinsicImageSize.width < containerSize.width) {
                    intrinsicImageSize.height
                } else {
                    (intrinsicImageSize.height * containerSize.width) / intrinsicImageSize.width
                }
                Size(width, height)
            }
        )
    }
}

@Immutable
data class PlaceholderConfig(
    val size: Size,
    val verticalAlign: PlaceholderVerticalAlign = PlaceholderVerticalAlign.Bottom,
    @Deprecated("This parameter is not used anymore and will be removed in the future.")
    val animate: Boolean = true,
)

@Immutable
data class ImageData(
    val painter: Painter,
    val modifier: Modifier = Modifier,
    val contentDescription: String? = "Image",
    val alignment: Alignment = Alignment.Center,
    val contentScale: ContentScale = ContentScale.Fit,
    val alpha: Float = 1F,
    val colorFilter: ColorFilter? = null,
)