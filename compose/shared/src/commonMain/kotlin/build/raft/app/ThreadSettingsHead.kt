package build.raft.app

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance

/** The channel's visibility as the settings drawer's wide head names it (web ChannelDrawerTitle). */
internal enum class ThreadSettingsVisibility {
    Public,
    Private,
    Joint,
}

/**
 * Web ChannelDrawerTitle's tag from the channel `type` (the conversation's channel kind):
 * `private` is Private, `joint` is Joint Channel, `channel` / `public` is Public. Null where the
 * kind is unknown or is not a channel (a DM, a thread): the head shows no tag.
 */
internal fun threadSettingsVisibility(channelKind: String?): ThreadSettingsVisibility? =
    when (channelKind?.trim()?.lowercase()) {
        "private" -> ThreadSettingsVisibility.Private
        "joint" -> ThreadSettingsVisibility.Joint
        "channel", "public" -> ThreadSettingsVisibility.Public
        else -> null
    }

/**
 * The wide head's trailing tag (slock #12627 ChannelDrawerTitle, from md up): "#name" then the
 * channel's visibility as a neutral outline badge, centred on the line, 8dp after the name. On a
 * phone the head has room for one secondary item, the "· #name" ref, so it shows no tag.
 */
internal fun threadSettingsVisibilityTag(channelKind: String?): (@Composable RowScope.() -> Unit)? {
    val visibility = threadSettingsVisibility(channelKind) ?: return null
    return {
        RaftBadge(
            text =
                when (visibility) {
                    ThreadSettingsVisibility.Public -> stringResource(Res.string.channel_settings_status_public)
                    ThreadSettingsVisibility.Private -> stringResource(Res.string.channel_settings_status_private)
                    ThreadSettingsVisibility.Joint -> stringResource(Res.string.channel_settings_status_joint)
                },
            // The head row spaces its items 6dp; web `md:gap-x-2` is 8.
            modifier = Modifier.align(Alignment.CenterVertically).padding(start = 2.dp),
            appearance = RaftBadgeAppearance.Outline,
        )
    }
}
