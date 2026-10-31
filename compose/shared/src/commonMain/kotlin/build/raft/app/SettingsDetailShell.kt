package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsDetailShell(
    title: String,
    subtitle: String,
    iconName: RaftLucideIconName? = null,
    applyTopSafeArea: Boolean = true,
    headerHeight: Dp = RaftAppSize.PageHeaderHeight,
    onBack: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null,
    // PROTOTYPE (native nav bar): [trailingContent] as bar buttons.
    nativeActions: List<RaftNativeNavBarAction> = emptyList(),
    content: @Composable () -> Unit
) {
    // The wide Settings detail pane (SettingsWideListDetail): the list beside it is the way back,
    // so no Back, and the head sits under the wide top bar (no status-bar inset).
    val wideDetail = LocalRaftSettingsWideDetail.current
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        RaftTopPageHeader(
            title = title,
            subtitle = subtitle,
            iconName = iconName,
            backgroundColor = RaftColor.SurfaceWhite,
            applyTopSafeArea = applyTopSafeArea && !wideDetail,
            headerHeight = headerHeight,
            onBack = settingsDetailHeaderBack(onBack, wideDetail),
            trailingContent = if (trailingContent != null) {
                { trailingContent() }
            } else {
                null
            },
            nativeActions = nativeActions,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

@Composable
internal fun SettingsSharedPlaceholder(
    title: String,
    detail: String,
    iconName: RaftLucideIconName,
    bottomContentPadding: Dp = RaftAppSpacing.Lg
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).padding(bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(52.dp).background(RaftColor.Brand, RoundedCornerShape(RaftAppRadius.Sticker)).border(RaftAppStroke.Control, RaftColor.StickerInk, RoundedCornerShape(RaftAppRadius.Sticker)),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            RaftLucideIcon(iconName, modifier = Modifier.size(26.dp), color = raftArgbHex(RaftColor.StickerInk))
        }
        Text(title, style = RaftType.PageTitle.copy(color = RaftColor.TextBlack), color = RaftColor.TextBlack)
        Text(detail, style = RaftType.Body.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
    }
}

/** The Back a Settings page head shows: its own on the phone, none in the wide detail pane. */
internal fun settingsDetailHeaderBack(onBack: () -> Unit, wideDetail: Boolean): (() -> Unit)? =
    onBack.takeUnless { wideDetail }
