package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.raftAvatarInitials
import coil3.compose.AsyncImagePainter

/** Load lifecycle of a [RaftConnectedAppLogo]'s image (mirrors web `AvatarSlot` `data-avatar-image`). */
internal enum class ConnectedAppLogoImageState {
    None,
    Loading,
    Loaded,
    Failed,
}

/** Platform-neutral image painter event for [reduceConnectedAppLogoImageState]. */
internal enum class ConnectedAppLogoPainterEvent {
    Loading,
    Success,
    Error,
}

internal fun AsyncImagePainter.State.toConnectedAppLogoPainterEvent(): ConnectedAppLogoPainterEvent =
    when (this) {
        is AsyncImagePainter.State.Success -> ConnectedAppLogoPainterEvent.Success
        is AsyncImagePainter.State.Error -> ConnectedAppLogoPainterEvent.Error
        is AsyncImagePainter.State.Loading,
        AsyncImagePainter.State.Empty -> ConnectedAppLogoPainterEvent.Loading
    }

internal fun initialConnectedAppLogoImageState(logoUrl: String?): ConnectedAppLogoImageState =
    if (logoUrl.isNullOrBlank()) ConnectedAppLogoImageState.None else ConnectedAppLogoImageState.Loading

internal fun reduceConnectedAppLogoImageState(
    logoUrl: String?,
    event: ConnectedAppLogoPainterEvent,
): ConnectedAppLogoImageState =
    if (logoUrl.isNullOrBlank()) {
        ConnectedAppLogoImageState.None
    } else {
        when (event) {
            ConnectedAppLogoPainterEvent.Success -> ConnectedAppLogoImageState.Loaded
            ConnectedAppLogoPainterEvent.Error -> ConnectedAppLogoImageState.Failed
            ConnectedAppLogoPainterEvent.Loading -> ConnectedAppLogoImageState.Loading
        }
    }

internal fun reduceConnectedAppLogoImageState(
    logoUrl: String?,
    painterState: AsyncImagePainter.State,
): ConnectedAppLogoImageState =
    reduceConnectedAppLogoImageState(logoUrl, painterState.toConnectedAppLogoPainterEvent())

/**
 * Once a ConnectedAppLogo image has loaded, the sticker removes its lavender fill so logos with
 * transparent corners sit cleanly on the surface beneath (botiverse/slock #16706).
 */
internal fun connectedAppLogoUsesTransparentFill(state: ConnectedAppLogoImageState): Boolean =
    state == ConnectedAppLogoImageState.Loaded

/** Initials stay visible on lavender while loading, when missing, or on error — never behind a loaded logo. */
internal fun connectedAppLogoShowsFallbackBehindImage(state: ConnectedAppLogoImageState): Boolean =
    state == ConnectedAppLogoImageState.Loading

internal fun connectedAppLogoShowsImageLayer(
    logoUrl: String?,
    state: ConnectedAppLogoImageState,
): Boolean =
    !logoUrl.isNullOrBlank() && state != ConnectedAppLogoImageState.Failed

/**
 * An App's logo, as the web's `ConnectedAppLogo` draws it (AvatarSlot `type="app"`,
 * botiverse/slock #16706): raft-ui's Avatar sticker (1.5dp `sticker-ink` edge, the die-cut
 * `sticker-edge` ring in dark, 2dp inner corners). With no logo, while it loads, or when it fails,
 * the App's initials stay on `palette-lavender`. Once the logo has loaded, the lavender fill and
 * initials are removed so logos with transparent corners show the surface underneath instead of a
 * lavender square. People, agents, and workspaces are unchanged. [size] `Sm` (27dp) is the web's
 * `xs` step, a dense row's. Decorative: the App's name is beside it.
 */
@Composable
internal fun RaftConnectedAppLogo(
    name: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
    size: RaftAvatarSize = RaftAvatarSize.Sm,
) {
    val normalizedLogoUrl = logoUrl?.trim()?.takeIf { it.isNotEmpty() }
    var imageState by remember(normalizedLogoUrl) { mutableStateOf(initialConnectedAppLogoImageState(normalizedLogoUrl)) }
    RaftAvatar(
        type = RaftAvatarType.Human,
        size = size,
        modifier = modifier,
        initials = raftAvatarInitials(name, size).ifBlank { "A" },
        fillOverride = if (connectedAppLogoUsesTransparentFill(imageState)) Color.Transparent else null,
        showFallbackBehindImage = connectedAppLogoShowsFallbackBehindImage(imageState),
        image =
            if (!connectedAppLogoShowsImageLayer(normalizedLogoUrl, imageState)) {
                null
            } else {
                {
                    key(normalizedLogoUrl) {
                        RaftCachedImage(
                            source = normalizedLogoUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            onImageStateChange = { state ->
                                imageState = reduceConnectedAppLogoImageState(normalizedLogoUrl, state)
                            },
                        )
                    }
                }
            },
    )
}
