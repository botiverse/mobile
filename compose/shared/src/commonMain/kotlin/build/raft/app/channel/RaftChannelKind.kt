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
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import build.raft.app.resources.*

/**
 * A channel's kind glyph. [symbol] is the Home sidebar's glyph name (`#`, `lock`, the lucide
 * names, [HomeChannelSymbolHashGlobe]); [iconName] is the single icon a surface without the
 * combined hash-globe draws (the iOS native bar: globe).
 *
 * The Slack glyphs (botiverse/slock #16042 `SlackChannelGlyph`): the Slack mark, [Slack]; with the
 * corner lock, [SlackLocked] (a joint channel, or one with people from outside) and [SlackPrivate]
 * (a private channel, which also says so to screen readers).
 */
enum class RaftChannelKindGlyph(val symbol: String, val iconName: RaftLucideIconName) {
    Hash("#", RaftLucideIconName.Hash),
    Lock("lock", RaftLucideIconName.Lock),
    Joint("git-branch", RaftLucideIconName.GitBranch),
    PublicPage(HomeChannelSymbolHashGlobe, RaftLucideIconName.Globe),
    External("external-channel", RaftLucideIconName.ExternalChannel),
    Slack("slack-channel", RaftLucideIconName.SlackChannel),
    SlackLocked("slack-channel-locked", RaftLucideIconName.SlackChannelLocked),
    SlackPrivate("slack-channel-private", RaftLucideIconName.SlackChannelLocked);

    /** One of the Slack mark glyphs. */
    val isSlack: Boolean
        get() = this == Slack || this == SlackLocked || this == SlackPrivate

    companion object {
        fun fromSymbol(symbol: String?): RaftChannelKindGlyph? = entries.firstOrNull { it.symbol == symbol }
    }
}

/** The wire value of `channel.bridge.provider` for a channel connected to Slack. */
internal const val RaftBridgeProviderSlack = "slack"

/**
 * People from outside the Server are in the channel (botiverse/slock e60211734
 * packages/web/src/components/layout/sidebarChannelVisibility.ts `isExternalMembersChannel`): a
 * joint channel (guest servers), or a public or private one with a Guest or an open external
 * invite (`hasExternalRelation`). Its icon is External and its details tag it External. The web
 * also keeps a joint channel whose conversion job is still running out of it; the app does not
 * read conversion jobs, so a joint channel always counts.
 */
internal fun isRaftExternalMembersChannel(channelType: String?, hasExternalRelation: Boolean): Boolean =
    when (channelType?.trim()?.lowercase()) {
        JointChannelKindWireValue -> true
        "channel", "private" -> hasExternalRelation
        else -> false
    }

/**
 * Connected to a Slack channel, its messages also reaching people in Slack (web
 * sidebarChannelVisibility.ts `isSlackChannel`: `bridge.provider === "slack"`). [bridgeProvider] is the
 * row's `bridge.provider`.
 */
internal fun isRaftSlackChannel(bridgeProvider: String?): Boolean =
    bridgeProvider?.trim()?.lowercase() == RaftBridgeProviderSlack

/**
 * The channel sits in the sidebar's External section (web sidebarChannelVisibility.ts
 * `isSidebarExternalSectionChannel` / `sidebarChannelHomeSectionKey`, botiverse/slock #16042): every
 * channel people outside the Server read, so one with people from outside
 * ([isRaftExternalMembersChannel]) and one connected to Slack ([isRaftSlackChannel]) (stdrc,
 * #proj-raft-ui:5609bab0; raft-design channel.md).
 */
internal fun isRaftExternalSectionChannel(channelType: String?, hasExternalRelation: Boolean, bridgeProvider: String?): Boolean =
    isRaftExternalMembersChannel(channelType, hasExternalRelation) || isRaftSlackChannel(bridgeProvider)

/** [isRaftExternalSectionChannel] for a Home channel row: where it sits in the sidebar and the server drawer. */
internal fun HomeChannelRow.isHomeExternalSectionChannel(): Boolean =
    isRaftExternalSectionChannel(
        channelType = if (kind == build.raft.app.model.ConversationKind.Joint) JointChannelKindWireValue else channelType,
        hasExternalRelation = hasExternalRelation,
        bridgeProvider = bridgeProvider,
    )

/**
 * A channel's kind glyph from what is known of it (botiverse/slock e60211734
 * packages/web/src/components/channel/channelKindIcon.tsx `ChannelKindIcon`), for a public, private
 * or joint channel:
 *
 * - [slack]: the Slack mark, which wins over the arrow and the globe (Slack is what the channel is);
 *   with the corner lock when the channel is not public or [external] ([RaftChannelKindGlyph.SlackLocked]),
 *   [RaftChannelKindGlyph.SlackPrivate] for a private one;
 * - else External (lock + arrow out) when [external];
 * - else private: lock; a public channel on the server's live public page ([onPublicPage],
 *   [channelOnPublicPage]): the hash-globe; joint: git-branch; any other channel: `#`.
 *
 * Called with the type alone it is the web's fallback for a channel that is not loaded
 * (`<ChannelKindIcon type={type} />`): a joint channel keeps git-branch. A surface that holds the
 * channel record takes [raftChannelRecordKindGlyph], which decides [external] and [slack].
 *
 * [channelType] is the wire type (`channel` / `private` / `joint`); null or unknown draws `#`.
 */
internal fun raftChannelKindGlyph(
    channelType: String?,
    onPublicPage: Boolean = false,
    external: Boolean = false,
    slack: Boolean = false,
): RaftChannelKindGlyph {
    val type = channelType?.trim()?.lowercase()
    val channelKind = type == "channel" || type == "private" || type == JointChannelKindWireValue
    return when {
        slack && channelKind ->
            when {
                type == "private" -> RaftChannelKindGlyph.SlackPrivate
                type != "channel" || external -> RaftChannelKindGlyph.SlackLocked
                else -> RaftChannelKindGlyph.Slack
            }
        external && channelKind -> RaftChannelKindGlyph.External
        type == "private" -> RaftChannelKindGlyph.Lock
        type == JointChannelKindWireValue -> RaftChannelKindGlyph.Joint
        onPublicPage && (type == null || type == "channel") -> RaftChannelKindGlyph.PublicPage
        else -> RaftChannelKindGlyph.Hash
    }
}

/**
 * The one chooser for a channel whose record is known (web components/channel/channelKind.ts
 * `channelKindIconProps`), so the sidebar, the header, Activity, search, pickers and composer
 * suggestions draw the same channel the same way: External when people from outside are in it
 * ([isRaftExternalMembersChannel]: a joint channel, or one with `hasExternalRelation`), Slack when it
 * is connected to Slack ([isRaftSlackChannel], [bridgeProvider] the row's `bridge.provider`).
 * Otherwise [raftChannelKindGlyph].
 */
internal fun raftChannelRecordKindGlyph(
    channelType: String?,
    onPublicPage: Boolean = false,
    hasExternalRelation: Boolean = false,
    bridgeProvider: String? = null,
): RaftChannelKindGlyph =
    raftChannelKindGlyph(
        channelType,
        onPublicPage,
        external = isRaftExternalMembersChannel(channelType, hasExternalRelation),
        slack = isRaftSlackChannel(bridgeProvider),
    )

/** [raftChannelRecordKindGlyph] for a Home channel row (the canonical channel record). */
internal fun HomeChannelRow.raftChannelKindGlyph(serverPublicPageLive: Boolean): RaftChannelKindGlyph {
    val type = if (kind == build.raft.app.model.ConversationKind.Joint) JointChannelKindWireValue else channelType
    return raftChannelRecordKindGlyph(
        channelType = type,
        onPublicPage = channelOnPublicPage(serverPublicPageLive, type, guestVisible, archived),
        hasExternalRelation = hasExternalRelation,
        bridgeProvider = bridgeProvider,
    )
}

/**
 * The glyph per channel id of one server's Home channels, for surfaces that know a channel only by
 * its id and type (search, forward targets, composer suggestions): web `ChannelIcon` looks the
 * channel up in the store and falls back to its type when it is not loaded. The fallback draws the
 * type alone (web `<ChannelKindIcon type={type} />`, no `external`): a joint channel that is not
 * loaded gets git-branch, never External.
 */
@Immutable
internal data class RaftChannelKinds(
    val glyphsByChannelId: Map<String, RaftChannelKindGlyph> = emptyMap(),
) {
    fun glyph(channelId: String?, fallbackType: String?): RaftChannelKindGlyph =
        channelId?.let { glyphsByChannelId[it] } ?: raftChannelKindGlyph(fallbackType)

    companion object {
        val Empty = RaftChannelKinds()

        fun from(snapshot: HomeSnapshot?): RaftChannelKinds =
            RaftChannelKinds(
                glyphsByChannelId =
                    snapshot?.channels.orEmpty().associate { channel ->
                        channel.channelId.value to channel.raftChannelKindGlyph(snapshot?.server?.publicPageLive == true)
                    },
            )
    }
}

/**
 * The current server's [RaftChannelKinds], provided by the app shell and the conversation page as a
 * [State]: only the surfaces that read it ([currentRaftChannelKinds]) recompose when Home
 * changes, never the provider's whole page.
 */
internal val LocalRaftChannelKinds = staticCompositionLocalOf<State<RaftChannelKinds>> { EmptyRaftChannelKindsState }

private val EmptyRaftChannelKindsState: State<RaftChannelKinds> = mutableStateOf(RaftChannelKinds.Empty)

@Composable
internal fun currentRaftChannelKinds(): RaftChannelKinds = LocalRaftChannelKinds.current.value

/** [RaftChannelKinds] of the server [homeState]'s Home shows: its channels' glyphs, following them. */
@Composable
internal fun rememberRaftChannelKindsState(homeState: StateFlow<SharedHomeState>): State<RaftChannelKinds> {
    val kinds = remember(homeState) { homeState.map(::raftChannelKindsOf).distinctUntilChanged() }
    return kinds.collectAsState(remember(homeState) { raftChannelKindsOf(homeState.value) })
}

/** [rememberRaftChannelKindsState] for a caller that already holds the Home state (the app shell). */
@Composable
internal fun rememberRaftChannelKindsState(homeState: SharedHomeState): State<RaftChannelKinds> {
    val kinds = remember(homeState.content) { raftChannelKindsOf(homeState) }
    return rememberUpdatedState(kinds)
}

private fun raftChannelKindsOf(state: SharedHomeState): RaftChannelKinds {
    val snapshot = (state.content as? SharedHomeContentState.Content)?.snapshot ?: return RaftChannelKinds.Empty
    return RaftChannelKinds.from(snapshot)
}

/**
 * A channel's kind glyph at [size] in [color] (an ARGB hex): the lucide icon, the public page's
 * hash-globe, or a glyph that carries its meaning for screen readers like the hash-globe (web
 * `MeaningfulGlyph`): the external glyph (`ExternalChannelGlyph`, `channel.externalMembers`) and the
 * Slack mark (`SlackChannelGlyph`, `channel.slackConnected` / `channel.slackConnectedPrivate`).
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
        RaftChannelKindGlyph.External ->
            RaftMeaningfulChannelKindIcon(glyph, stringResource(Res.string.channel_external_members), size, color, modifier)
        RaftChannelKindGlyph.Slack, RaftChannelKindGlyph.SlackLocked ->
            RaftMeaningfulChannelKindIcon(glyph, stringResource(Res.string.channel_slack_connected), size, color, modifier)
        RaftChannelKindGlyph.SlackPrivate ->
            RaftMeaningfulChannelKindIcon(glyph, stringResource(Res.string.channel_slack_connected_private), size, color, modifier)
        else -> RaftLucideIcon(glyph.iconName, modifier.size(size), color = color)
    }
}

@Composable
private fun RaftMeaningfulChannelKindIcon(
    glyph: RaftChannelKindGlyph,
    label: String,
    size: Dp,
    color: String,
    modifier: Modifier,
) {
    Box(modifier.size(size).semantics { contentDescription = label }) {
        RaftLucideIcon(glyph.iconName, Modifier.size(size), color = color)
    }
}

/** The kind name a host is told along with the glyph (`channel`, `private`, `joint`, `external`, `slack`). */
internal fun RaftChannelKindGlyph.wireChannelKind(): String =
    when (this) {
        RaftChannelKindGlyph.Hash, RaftChannelKindGlyph.PublicPage -> "channel"
        RaftChannelKindGlyph.Lock -> "private"
        RaftChannelKindGlyph.Joint -> JointChannelKindWireValue
        RaftChannelKindGlyph.External -> "external"
        RaftChannelKindGlyph.Slack, RaftChannelKindGlyph.SlackLocked, RaftChannelKindGlyph.SlackPrivate -> "slack"
    }
