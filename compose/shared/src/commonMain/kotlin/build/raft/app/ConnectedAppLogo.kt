package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.raftAvatarInitials
import coil3.compose.AsyncImagePainter

/**
 * An App's logo, as the web's `ConnectedAppLogo` draws it (AvatarSlot `type="app"`): raft-ui's
 * Avatar sticker (the `human` square: `palette-lavender` fill, 1.5dp `sticker-ink` edge, the
 * die-cut `sticker-edge` ring in dark), the logo filling the inside of the edge with 2dp
 * corners (a transparent logo sits on the fill), the App's initials when it fails or when there
 * is none; the bare fill while it loads. [size] `Sm` (27dp) is the web's `xs` step, a dense
 * row's. Decorative: the App's name is beside it.
 */
@Composable
internal fun RaftConnectedAppLogo(
    name: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
    size: RaftAvatarSize = RaftAvatarSize.Sm,
) {
    var broken by remember(logoUrl) { mutableStateOf(false) }
    RaftAvatar(
        type = RaftAvatarType.Human,
        size = size,
        modifier = modifier,
        initials = raftAvatarInitials(name, size).ifBlank { "A" },
        image =
            if (logoUrl == null || broken) {
                null
            } else {
                {
                    RaftCachedImage(
                        source = logoUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onImageStateChange = { if (it is AsyncImagePainter.State.Error) broken = true },
                    )
                }
            },
    )
}
