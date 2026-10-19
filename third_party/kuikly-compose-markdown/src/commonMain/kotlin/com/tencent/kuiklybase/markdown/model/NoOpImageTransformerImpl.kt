package com.tencent.kuiklybase.markdown.model

import androidx.compose.runtime.Composable
import coil3.compose.rememberAsyncImagePainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
import com.tencent.kuiklybase.markdown.model.ImageData
import com.tencent.kuiklybase.markdown.model.ImageTransformer

class NoOpImageTransformerImpl : ImageTransformer {

    @Composable
    override fun transform(link: String, containerSize: Size?): ImageData {
        return ImageData(
            painter = rememberAsyncImagePainter(link),
        )
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size {
        return Size(100F, 100F)
    }
}