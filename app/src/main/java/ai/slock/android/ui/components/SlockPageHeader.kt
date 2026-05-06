package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSize
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType

@Composable
fun SlockPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SlockSize.PageHeaderHeight)
            .background(SlockColor.BrandYellow)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        if (leadingContent != null) {
            leadingContent()
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = title,
                style = SlockType.PageTitle,
                color = SlockColor.TextBlack
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            }
        }
        if (trailingContent != null) {
            Box(contentAlignment = Alignment.Center) {
                trailingContent()
            }
        }
    }
}

@Composable
fun SlockTitledPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SlockSize.PageHeaderHeight)
            .background(SlockColor.BrandYellow)
            .drawBehind {
                val stroke = SlockStroke.Default.toPx()
                drawLine(
                    color = SlockColor.BorderBlack,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke
                )
            }
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Text(
            text = title,
            style = SlockType.PageTitle,
            color = SlockColor.TextBlack,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (trailingContent != null) {
            Box(contentAlignment = Alignment.Center) {
                trailingContent()
            }
        }
    }
}

@Composable
fun SlockPageDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SlockStroke.Default)
            .background(SlockColor.BorderBlack)
    )
}
