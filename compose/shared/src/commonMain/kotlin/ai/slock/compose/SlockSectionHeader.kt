package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.dp

private val SectionChevronSize = 12.dp

@Composable
internal fun SlockCollapsibleSectionHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .clickable { onExpandedChange(!expanded) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        SlockLucideIcon(
            if (expanded) "chevron-down" else "chevron-right",
            Modifier.size(SectionChevronSize)
        )
        Text(
            label,
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(start = SlockSpacing.Xs)
        )
        Text(
            count.toString(),
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            modifier = Modifier.padding(start = SlockSpacing.Xs)
        )
        Spacer(Modifier.weight(1f))
        if (trailingContent != null) {
            Box(content = trailingContent)
        }
    }
}

@Composable
internal fun SlockHeaderIconAction(
    icon: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(
            name = icon,
            modifier = Modifier.size(16.dp),
            color = "#FF000000"
        )
    }
}
