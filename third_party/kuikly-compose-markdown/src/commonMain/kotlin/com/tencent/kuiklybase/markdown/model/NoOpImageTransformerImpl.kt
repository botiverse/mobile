package com.tencent.kuiklybase.markdown.model

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.ui.graphics.painter.Painter
import com.tencent.kuikly.core.base.Size
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