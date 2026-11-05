package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkResult
import build.raft.app.data.CardElement
import build.raft.app.data.CardStack
import build.raft.app.data.RAFT_APP_LINK_CARD_MAX_DEPTH
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.data.appLinkDomain
import build.raft.app.markdown.raftAppLinkAsciiHost
import build.raft.designsystem.components.RaftAppLinkCard
import build.raft.designsystem.components.RaftAppLinkCardActions
import build.raft.designsystem.components.RaftAppLinkCardBody
import build.raft.designsystem.components.RaftAppLinkCardDefaults
import build.raft.designsystem.components.RaftAppLinkCardHeader
import build.raft.designsystem.components.RaftAppLinkCardHost
import build.raft.designsystem.components.RaftAppLinkCardIcon
import build.raft.designsystem.components.RaftAppLinkCardName
import build.raft.designsystem.components.RaftAppLinkCardTitle
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import coil3.compose.AsyncImagePainter

/**
 * An App link's block card (raft-design #1010 "Block cards"; RFC 076 section 14): the card of a
 * version 3 answer that lists a `card` with `placement: "block"`, drawn in the message after the
 * link's paragraph ([build.raft.app.markdown.RaftMarkdownBlockCardHost]). Raft's header names the
 * App from what Raft stores (its icon and name, the link's host), then the App's title (it opens
 * the item), the card's elements as the hover card draws them, and the buttons: the App's
 * navigate buttons in its order and Raft's Open Link, all in the More menu (the title opens the
 * item; this client declares no confirm or undo, so Raft sends no executable button). On a phone
 * it takes the message's width; a wide window caps it at 400dp, as the web does.
 */
@Composable
internal fun ThreadAppLinkBlockCard(
    card: AppLinkResult.Card,
    openUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val block = card.block ?: return
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val maxWidth: Dp = if (windowWidth >= RaftAppLinkCardDefaults.PhoneBelow) RaftAppLinkCardDefaults.MaxWidth else Dp.Unspecified
    val host = remember(card.url) { appLinkDomain(card.url)?.let(::raftAppLinkAsciiHost).orEmpty() }
    val openLink = raftAppLinkOpenLinkButton { openUri(card.url) }
    val buttons = remember(block, card.url, openLink) { block.card.blockCardButtons(card.url, openUri, openLink) }
    RaftDesignSystemTheme {
        // Between the paragraph and what follows: the gap a table or an image keeps in the text.
        Box(modifier.padding(top = 8.dp, bottom = 4.dp)) {
            RaftAppLinkCard(maxWidth = maxWidth, label = block.title) {
                // Raft's header: from what Raft stores for the App, never from the card.
                RaftAppLinkCardHeader {
                    RaftAppLinkCardIcon { ThreadAppLinkCardHeaderIcon(card.app.icon) }
                    RaftAppLinkCardName(card.app.name, Modifier.weight(1f, fill = false))
                    if (host.isNotEmpty()) RaftAppLinkCardHost(host, Modifier.weight(1f, fill = false))
                }
                RaftAppLinkCardBody {
                    RaftAppLinkCardTitle(block.title, onClick = { openUri(card.url) })
                    RaftAppLinkCardV2Body(block.card)
                }
                RaftAppLinkCardActions(buttons)
            }
        }
    }
}

/** The App's registered icon at 16dp with 2dp corners, or the generic App mark when it has none or fails. */
@Composable
private fun ThreadAppLinkCardHeaderIcon(icon: String?) {
    var broken by remember(icon) { mutableStateOf(false) }
    if (icon != null && !broken) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
            RaftCachedImage(
                source = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onImageStateChange = { state -> if (state is AsyncImagePainter.State.Error) broken = true },
            )
        }
    } else {
        RaftLucideIcon(
            name = RaftLucideIconName.AppWindow,
            modifier = Modifier.size(14.dp),
            color = raftArgbHex(RaftColor.ForegroundMuted),
            strokeWidth = 2f,
        )
    }
}

/**
 * The buttons of a block card, in order: the App's `navigate` buttons as the card lists them (an
 * `execute` or `read` button is not drawn: this client holds no handle for one), then Raft's
 * [openLink] unless an App button already goes to [url].
 */
internal fun RaftAppLinkCardV2.blockCardButtons(
    url: String,
    openUri: (String) -> Unit,
    openLink: RaftPreviewCardButton,
): List<RaftPreviewCardButton> {
    val app =
        buttonsInOrder().mapNotNull { button ->
            val action = button.action as? RaftLinkCardButtonAction.Navigate ?: return@mapNotNull null
            RaftPreviewCardButton(
                label = button.label,
                kind = RaftPreviewCardButtonKind.Link,
                onClick = { openUri(action.url) },
                frameless = button.variant == RaftLinkCardButtonVariant.Ghost,
                disabledReason = button.disabledReason,
            )
        }
    val goesToTheLink = buttonsInOrder().any { (it.action as? RaftLinkCardButtonAction.Navigate)?.url == url }
    return if (goesToTheLink) app else app + openLink
}

/** The card's Buttons in the App's order: depth first from the root, a Stack's children as listed. */
internal fun RaftAppLinkCardV2.buttonsInOrder(): List<RaftLinkCardV2Element.Button> {
    val found = ArrayList<RaftLinkCardV2Element.Button>()
    fun walk(id: String, depth: Int) {
        if (depth > RAFT_APP_LINK_CARD_MAX_DEPTH) return
        val node: CardElement = elements[id] ?: return
        when (node) {
            is RaftLinkCardV2Element.Button -> found += node
            is CardStack -> node.childIds.forEach { walk(it, depth + 1) }
            else -> Unit
        }
    }
    walk(root, 0)
    return found
}
