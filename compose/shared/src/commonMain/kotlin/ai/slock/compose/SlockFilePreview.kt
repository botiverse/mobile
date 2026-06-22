package ai.slock.compose

import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.markdown.SlockMarkdownBody
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import androidx.compose.runtime.Composable

@Composable
internal fun SlockFilePreviewPageScaffold(
    title: String,
    subtitle: String,
    iconName: SlockLucideIconName,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = title,
            subtitle = subtitle,
            iconName = iconName,
            backgroundColor = SlockColor.SurfaceWhite,
            leadingContent = {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = onBack,
                    size = 30.dp,
                    shadowOffset = 2.dp
                )
            },
            trailingContent = trailingContent
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            content()
        }
    }
}

@Composable
internal fun SlockFilePreviewGenericCard(
    iconName: SlockLucideIconName,
    name: String,
    meta: String,
    message: String,
    statusLabel: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(24.dp), color = "#FF000000")
        Text(
            text = name.ifBlank { "File" },
            style = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = SlockType.Body,
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = message,
            style = SlockType.Body,
            color = SlockColor.TextBlack,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        statusLabel?.let {
            Text(
                text = it,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun SlockFilePreviewMarkdownPanel(
    name: String,
    meta: String,
    statusLabel: String?,
    status: String,
    body: String?,
    error: String?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    performanceKey: String? = null,
    textStyle: TextStyle? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        SlockFilePreviewPanelHeader(
            name = name.ifBlank { "Markdown" },
            meta = meta,
            statusLabel = statusLabel
        )
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        when (status) {
            "content" ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(SlockSpacing.Md),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                ) {
                    item {
                        SlockMarkdownBody(
                            body = body.orEmpty(),
                            textStyle = textStyle ?: SlockType.Body,
                            onOpenUrl = onOpenUrl,
                            modifier = Modifier.fillMaxWidth(),
                            performanceKey = performanceKey
                        )
                    }
                }
            "error" ->
                SlockFilePreviewMessage(
                    iconName = SlockLucideIconName.FileText,
                    title = "Markdown preview unavailable",
                    detail = error.orEmpty().ifBlank { "Open with system viewer to inspect this file." },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            else ->
                SlockFilePreviewMessage(
                    iconName = SlockLucideIconName.Loader,
                    title = "Loading markdown preview...",
                    detail = name.ifBlank { "Markdown file" },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
        }
    }
}

@Composable
internal fun SlockFilePreviewTextPanel(
    name: String,
    meta: String,
    content: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        SlockFilePreviewPanelHeader(name = name.ifBlank { "Text file" }, meta = meta, statusLabel = null)
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 560.dp)
                .background(SlockColor.SurfaceCream)
                .padding(SlockSpacing.Sm)
        ) {
            Text(
                text = content,
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack,
                maxLines = 28,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun SlockFilePreviewImagePanel(
    source: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderLabel: String = "IMG"
) {
    SlockAspectFitImagePreviewSurface(
        source = source,
        contentDescription = contentDescription,
        placeholderLabel = placeholderLabel,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 420.dp),
        backgroundColor = SlockColor.SurfaceWhite,
        borderWidth = SlockStroke.Default
    )
}

@Composable
internal fun SlockFilePreviewMessage(
    iconName: SlockLucideIconName,
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(SlockSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(34.dp), color = "#FF000000")
        Text(
            text = title,
            style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SlockFilePreviewPanelHeader(
    name: String,
    meta: String,
    statusLabel: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceCream)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = name,
            style = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        statusLabel?.let {
            Text(
                text = it,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
