package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownColors
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownDimens

@Composable
fun MarkdownDivider(
    modifier: Modifier = Modifier,
    color: Color = LocalMarkdownColors.current.dividerColor,
    thickness: Dp = LocalMarkdownDimens.current.dividerThickness,
) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color)
    )
}


@Composable
fun VerticalMarkdownDivider(
    modifier: Modifier = Modifier,
    color: Color = LocalMarkdownColors.current.dividerColor,
    thickness: Dp = LocalMarkdownDimens.current.dividerThickness,
) {

    Spacer(
        modifier
            .width(thickness)
            .fillMaxHeight()
            .background(color = color)
    )
}
