package build.raft.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkResult
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftSheetSurface
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * The item card of the chip tapped in one message ([url]), while the message still has it: a
 * card that is read again without its body, or a link no longer previewed, closes the sheet.
 */
@Composable
internal fun ThreadAppLinkCardSheetHost(
    results: Map<String, AppLinkResult>,
    url: String?,
    onDismiss: () -> Unit,
) {
    val card = url?.let { results[it] as? AppLinkResult.Card }?.takeIf { it.card != null }
    LaunchedEffect(url, card == null) {
        if (url != null && card == null) onDismiss()
    }
    if (card == null) return
    val uriHandler = LocalUriHandler.current
    RaftDesignSystemTheme {
        ThreadAppLinkCardSheet(
            card = card,
            onDismiss = onDismiss,
            onOpenLink = {
                onDismiss()
                uriHandler.openUri(card.url)
            },
        )
    }
}

/**
 * An App link's item card on a phone (web `AppLinkChip` hover card; raft-ui PreviewCard › Item
 * card). A finger can't hover, and raft-ui never puts the card under the message (Navigation ›
 * App links), so a tap on the chip opens this sheet: the App's name as its title, the card
 * ([RaftAppLinkCardView]: title, body, App name · domain, so the reader sees where the link goes
 * before following it), then Open Link, the ink-bordered button, never pink. v1 cards have no
 * buttons: an action is only logged. [inWindow] false draws the surface alone (fixtures).
 */
@Composable
internal fun ThreadAppLinkCardSheet(
    card: AppLinkResult.Card,
    onDismiss: () -> Unit,
    onOpenLink: () -> Unit,
    inWindow: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val actions: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        RaftButton(
            text = stringResource(MR.strings.thread_link_preview_open_link),
            onClick = onOpenLink,
            modifier = Modifier.fillMaxWidth(),
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Lg,
        )
    }
    val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        RaftAppLinkCardView(
            card = card,
            onAction = { elementId -> raftLogInfo(ThreadAppLinkCardLogTag, "app_link_card_action_ignored element=$elementId") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (inWindow) {
        RaftSheet(onDismissRequest = onDismiss, title = card.app.name, modifier = modifier, actions = actions, content = body)
    } else {
        RaftSheetSurface(title = card.app.name, modifier = modifier, actions = actions, content = body)
    }
}

private const val ThreadAppLinkCardLogTag = "RaftAppLinkCard"
