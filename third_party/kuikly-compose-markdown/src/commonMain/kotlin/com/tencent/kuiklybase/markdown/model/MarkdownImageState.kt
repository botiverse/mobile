package com.tencent.kuiklybase.markdown.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.core.base.Size
import com.tencent.kuikly.compose.ui.unit.IntSize

val UnspecifiedSize = Size(Float.NaN, Float.NaN)

val Size.isUnspecified: Boolean get() = width.isNaN() || height.isNaN()

fun Size.toIntSize() = IntSize(width.toInt(), height.toInt())

fun IntSize.toSize() = Size(width.toFloat(), height.toFloat())

interface MarkdownImageState {
    val containerSize: Size
    val intrinsicImageSize: Size

    @Deprecated("Use updateContainerSize instead", ReplaceWith("updateContainerSize(size)"))
    fun setContainerSize(intSize: IntSize)

    @Deprecated("Use updateImageSize instead", ReplaceWith("updateImageSize(size)"))
    fun setImageSize(size: Size)

    @Suppress("DEPRECATION")
    fun updateContainerSize(size: Size) = setContainerSize(size.toIntSize())

    @Suppress("DEPRECATION")
    fun updateImageSize(size: Size) = setImageSize(size)
}

internal class MarkdownImageStateImpl : MarkdownImageState {

    override var containerSize by mutableStateOf(UnspecifiedSize)

    override var intrinsicImageSize by mutableStateOf(UnspecifiedSize)

    @Deprecated("Use updateContainerSize instead", replaceWith = ReplaceWith("updateContainerSize(size)"))
    override fun setContainerSize(intSize: IntSize) = updateContainerSize(intSize.toSize())

    @Deprecated("Use updateImageSize instead", replaceWith = ReplaceWith("updateImageSize(size)"))
    override fun setImageSize(size: Size) = updateImageSize(size)

    override fun updateContainerSize(size: Size) {
        containerSize = size
    }

    override fun updateImageSize(size: Size) {
        intrinsicImageSize = size
    }
}

@Composable
fun rememberMarkdownImageState(): MarkdownImageState {
//    val density = LocalDensity.current
    return remember { MarkdownImageStateImpl() }
}