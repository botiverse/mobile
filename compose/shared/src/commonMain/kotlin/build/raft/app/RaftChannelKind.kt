package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import build.raft.app.components.RaftHashGlobeIcon
import build.raft.app.components.channelOnPublicPage
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.EXTERNAL_CHANNELS_FEATURE_FLAG_KEY
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedServerFeatureFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import build.raft.app.resources.*

/**
 * A channel's kind glyph. [symbol] is the Home sidebar's glyph name (`#`, `lock`, the lucide
 * names, [HomeChannelSymbolHashGlobe]); [iconName] is the single icon a surface without the
 * combined hash-globe draws (the iOS native bar: globe).
 */
enum class RaftChannelKindGlyph(val symbol: String, val iconName: RaftLucideIconName) {
    Hash("#", RaftLucideIconName.Hash),
    Lock("lock", RaftLucideIconName.Lock),
    Joint("git-branch", RaftLucideIconName.GitBranch),
    PublicPage(HomeChannelSymbolHashGlobe, RaftLucideIconName.Globe),
    External("external-channel", RaftLucideIconName.ExternalChannel);

    companion object {
        fun fromSymbol(symbol: String?): RaftChannelKindGlyph? = entries.firstOrNull { it.symbol == symbol }
    }
}

/**
 * The channel is in the web sidebar's External section (botiverse/slock
 * packages/web/src/components/layout/sidebarChannelVisibility.ts `isSidebarExternalSectionChannel`):
 * a joint channel (guest servers), or a public or private one with a Guest or an open external
 * invite (`hasExternalRelation`). The web also keeps a joint channel whose conversion job is still
 * running out of it; the app does not read conversion jobs, so a joint channel always counts.
 */
internal fun isRaftExternalSectionChannel(channelType: String?, hasExternalRelation: Boolean): Boolean =
    when (channelType?.trim()?.lowercase()) {
        JointChannelKindWireValue -> true
        "channel", "private" -> hasExternalRelation
        else -> false
    }

/**
 * The one chooser of a channel's kind glyph (web components/channel/channelKind.ts
 * `channelKindIconProps` + channelKindIcon.tsx `ChannelKindIcon`), so the sidebar, the header,
 * Activity, search, pickers and composer suggestions draw the same channel the same way:
 *
 * - External (lock + arrow out) when the server's `external_channels_v1` is on and the channel is
 *   external ([isRaftExternalSectionChannel]); with the flag off nothing changes.
 * - else private: lock; a public channel on the server's live public page ([onPublicPage],
 *   [channelOnPublicPage]): the hash-globe; joint: git-branch; any other channel: `#`.
 *
 * [channelType] is the wire type (`channel` / `private` / `joint`); null or unknown draws `#`.
 */
internal fun raftChannelKindGlyph(
    channelType: String?,
    onPublicPage: Boolean = false,
    hasExternalRelation: Boolean = false,
    externalChannelsEnabled: Boolean = false,
): RaftChannelKindGlyph {
    val type = channelType?.trim()?.lowercase()
    return when {
        externalChannelsEnabled && isRaftExternalSectionChannel(type, hasExternalRelation) -> RaftChannelKindGlyph.External
        type == "private" -> RaftChannelKindGlyph.Lock
        type == JointChannelKindWireValue -> RaftChannelKindGlyph.Joint
        onPublicPage && (type == null || type == "channel") -> RaftChannelKindGlyph.PublicPage
        else -> RaftChannelKindGlyph.Hash
    }
}

/** [raftChannelKindGlyph] for a Home channel row (the canonical channel record). */
internal fun HomeChannelRow.raftChannelKindGlyph(
    serverPublicPageLive: Boolean,
    externalChannelsEnabled: Boolean,
): RaftChannelKindGlyph {
    val type = if (kind == build.raft.app.model.ConversationKind.Joint) JointChannelKindWireValue else channelType
    return raftChannelKindGlyph(
        channelType = type,
        onPublicPage = channelOnPublicPage(serverPublicPageLive, type, guestVisible, archived),
        hasExternalRelation = hasExternalRelation,
        externalChannelsEnabled = externalChannelsEnabled,
    )
}

/**
 * The glyph per channel id of one server's Home channels, for surfaces that know a channel only by
 * its id and type (search, forward targets, composer suggestions): web `ChannelIcon` looks the
 * channel up in the store and falls back to its type when it is not loaded.
 */
@Immutable
internal data class RaftChannelKinds(
    val externalChannelsEnabled: Boolean = false,
    val glyphsByChannelId: Map<String, RaftChannelKindGlyph> = emptyMap(),
) {
    fun glyph(channelId: String?, fallbackType: String?): RaftChannelKindGlyph =
        channelId?.let { glyphsByChannelId[it] }
            ?: raftChannelKindGlyph(fallbackType, externalChannelsEnabled = externalChannelsEnabled)

    companion object {
        val Empty = RaftChannelKinds()

        fun from(snapshot: HomeSnapshot?, externalChannelsEnabled: Boolean): RaftChannelKinds =
            RaftChannelKinds(
                externalChannelsEnabled = externalChannelsEnabled,
                glyphsByChannelId =
                    snapshot?.channels.orEmpty().associate { channel ->
                        channel.channelId.value to
                            channel.raftChannelKindGlyph(snapshot?.server?.publicPageLive == true, externalChannelsEnabled)
                    },
            )
    }
}

/**
 * The current server's [RaftChannelKinds], provided by the app shell and the conversation page as a
 * [State]: only the surfaces that read it ([currentRaftChannelKinds]) recompose when Home or the
 * flag changes, never the provider's whole page.
 */
internal val LocalRaftChannelKinds = staticCompositionLocalOf<State<RaftChannelKinds>> { EmptyRaftChannelKindsState }

private val EmptyRaftChannelKindsState: State<RaftChannelKinds> = mutableStateOf(RaftChannelKinds.Empty)

@Composable
internal fun currentRaftChannelKinds(): RaftChannelKinds = LocalRaftChannelKinds.current.value

/**
 * [RaftChannelKinds] of the server [homeState]'s Home shows: its `external_channels_v1` and its
 * channels' glyphs, following both.
 */
@Composable
internal fun rememberRaftChannelKindsState(
    flags: SharedServerFeatureFlags?,
    homeState: StateFlow<SharedHomeState>,
): State<RaftChannelKinds> {
    val kinds =
        remember(flags, homeState) {
            val revision = flags?.snapshotRevision ?: MutableStateFlow(0L)
            combine(homeState, revision) { state, _ -> raftChannelKindsOf(flags, state) }.distinctUntilChanged()
        }
    return kinds.collectAsState(remember(flags, homeState) { raftChannelKindsOf(flags, homeState.value) })
}

/** [rememberRaftChannelKindsState] for a caller that already holds the Home state (the app shell). */
@Composable
internal fun rememberRaftChannelKindsState(
    flags: SharedServerFeatureFlags?,
    homeState: SharedHomeState,
): State<RaftChannelKinds> {
    val revision = flags?.snapshotRevision?.collectAsState()?.value
    val kinds = remember(flags, homeState.content, revision) { raftChannelKindsOf(flags, homeState) }
    return rememberUpdatedState(kinds)
}

private fun raftChannelKindsOf(flags: SharedServerFeatureFlags?, state: SharedHomeState): RaftChannelKinds {
    val snapshot = (state.content as? SharedHomeContentState.Content)?.snapshot ?: return RaftChannelKinds.Empty
    val enabled = flags?.isEnabled(snapshot.server.id, EXTERNAL_CHANNELS_FEATURE_FLAG_KEY) == true
    return RaftChannelKinds.from(snapshot, enabled)
}

/**
 * A channel's kind glyph at [size] in [color] (an ARGB hex): the lucide icon, the public page's
 * hash-globe, or the external glyph, which carries its meaning for screen readers like the
 * hash-globe (web `ExternalChannelGlyph`, `channel.externalMembers`).
 */
@Composable
internal fun RaftChannelKindIcon(
    glyph: RaftChannelKindGlyph,
    size: Dp,
    color: String,
    modifier: Modifier = Modifier,
) {
    when (glyph) {
        RaftChannelKindGlyph.PublicPage -> RaftHashGlobeIcon(size = size, color = color, modifier = modifier)
        RaftChannelKindGlyph.External -> {
            val label = stringResource(Res.string.channel_external_members)
            Box(modifier.size(size).semantics { contentDescription = label }) {
                RaftLucideIcon(RaftLucideIconName.ExternalChannel, Modifier.size(size), color = color)
            }
        }
        else -> RaftLucideIcon(glyph.iconName, modifier.size(size), color = color)
    }
}

/** The kind name a host is told along with the glyph (`channel`, `private`, `joint`, `external`). */
internal fun RaftChannelKindGlyph.wireChannelKind(): String =
    when (this) {
        RaftChannelKindGlyph.Hash, RaftChannelKindGlyph.PublicPage -> "channel"
        RaftChannelKindGlyph.Lock -> "private"
        RaftChannelKindGlyph.Joint -> JointChannelKindWireValue
        RaftChannelKindGlyph.External -> "external"
    }
