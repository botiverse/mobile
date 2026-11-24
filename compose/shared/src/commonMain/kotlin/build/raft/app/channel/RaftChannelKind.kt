package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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
 * A channel's kind glyph: who can read what is written there (botiverse/slock 6b0965841, #16060,
 * channelKindIcon.tsx; raft-design channel.md, stdrc 2026-11-21). The glyph is the widest group
 * that can: [Hash] the whole server, [Lock] its members only, [External] people from outside
 * (Guests, guest servers, an open invite: Lucide's two people), [Slack] people in Slack (the Slack
 * mark), [PublicPage] anyone on the web (Lucide's globe). The corner only ever holds the small
 * lock, for a channel private in the server whose glyph is not the lock itself:
 * [ExternalPrivate], [SlackPrivate]. [Joint] (git-branch) is a joint channel known by its type
 * alone. [symbol] is the Home sidebar's name for the glyph (`#`, else the icon's name);
 * [iconName] is the icon every surface draws, the iOS native bar's asset included.
 */
enum class RaftChannelKindGlyph(val symbol: String, val iconName: RaftLucideIconName) {
    Hash("#", RaftLucideIconName.Hash),
    Lock("lock", RaftLucideIconName.Lock),
    Joint("git-branch", RaftLucideIconName.GitBranch),
    PublicPage("public-page-channel", RaftLucideIconName.PublicPageChannel),
    External("external-channel", RaftLucideIconName.ExternalChannel),
    ExternalPrivate("external-channel-private", RaftLucideIconName.ExternalChannelPrivate),
    Slack("slack-channel", RaftLucideIconName.SlackChannel),
    SlackPrivate("slack-channel-private", RaftLucideIconName.SlackChannelPrivate);

    /** The Slack mark, with or without the corner lock. */
    val isSlack: Boolean
        get() = this == Slack || this == SlackPrivate

    /**
     * Chosen from what only the channel's record knows (people from outside, Slack): a surface that
     * knows the channel's type alone cannot draw it, so it keeps the record's glyph.
     */
    val needsChannelRecord: Boolean
        get() = this == External || this == ExternalPrivate || isSlack

    companion object {
        fun fromSymbol(symbol: String?): RaftChannelKindGlyph? = entries.firstOrNull { it.symbol == symbol }
    }
}

/**
 * The one chooser of a channel's kind glyph (botiverse/slock 6b0965841, #16060,
 * packages/web/src/components/channel/channelKindIcon.tsx `ChannelKindIcon`): the widest group that
 * can read the channel wins, the globe over Slack over people from outside.
 *
 * - a public channel on the server's live public page ([onPublicPage], [channelOnPublicPage]):
 *   the globe;
 * - else, for a public, private or joint channel, [slack]: the Slack mark, then [external]: two
 *   people; each with the corner lock only when the channel is private;
 * - else private: the lock; joint: git-branch; any other channel: `#`.
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
    val isPrivate = type == "private"
    val channelKind = type == "channel" || isPrivate || type == JointChannelKindWireValue
    return when {
        onPublicPage && (type == null || type == "channel") -> RaftChannelKindGlyph.PublicPage
        slack && channelKind -> if (isPrivate) RaftChannelKindGlyph.SlackPrivate else RaftChannelKindGlyph.Slack
        external && channelKind -> if (isPrivate) RaftChannelKindGlyph.ExternalPrivate else RaftChannelKindGlyph.External
        isPrivate -> RaftChannelKindGlyph.Lock
        type == JointChannelKindWireValue -> RaftChannelKindGlyph.Joint
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

/**
 * Constant non-snapshot [State] default for [LocalRaftChannelKinds]: top-level helpers in this file
 * (such as [raftChannelKindGlyph]) are called from background data projections, so initializing a
 * top-level `mutableStateOf` in `RaftChannelKindKt.<clinit>` off the main thread would risk
 * `SnapshotKt.readError`.
 */
private object EmptyRaftChannelKindsState : State<RaftChannelKinds> {
    override val value: RaftChannelKinds = RaftChannelKinds.Empty
}

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
 * The one drawer of a channel's kind glyph, at [size] in [color] (an ARGB hex): its icon
 * ([RaftChannelKindGlyph.iconName]). A glyph with a meaning of its own carries it for screen
 * readers (web `MeaningfulGlyph`): the globe (`channel.publicPageReadable`), two people
 * (`channel.externalMembers` / `channel.externalMembersPrivate`) and the Slack mark
 * (`channel.slackConnected` / `channel.slackConnectedPrivate`).
 */
@Composable
internal fun RaftChannelKindIcon(
    glyph: RaftChannelKindGlyph,
    size: Dp,
    color: String,
    modifier: Modifier = Modifier,
) {
    val label =
        when (glyph) {
            RaftChannelKindGlyph.Hash, RaftChannelKindGlyph.Lock, RaftChannelKindGlyph.Joint -> null
            RaftChannelKindGlyph.PublicPage -> stringResource(Res.string.channel_public_page_readable)
            RaftChannelKindGlyph.External -> stringResource(Res.string.channel_external_members)
            RaftChannelKindGlyph.ExternalPrivate -> stringResource(Res.string.channel_external_members_private)
            RaftChannelKindGlyph.Slack -> stringResource(Res.string.channel_slack_connected)
            RaftChannelKindGlyph.SlackPrivate -> stringResource(Res.string.channel_slack_connected_private)
        }
    if (label == null) {
        RaftLucideIcon(glyph.iconName, modifier.size(size), color = color)
    } else {
        RaftMeaningfulChannelKindIcon(glyph, label, size, color, modifier)
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
        RaftChannelKindGlyph.External, RaftChannelKindGlyph.ExternalPrivate -> "external"
        RaftChannelKindGlyph.Slack, RaftChannelKindGlyph.SlackPrivate -> "slack"
    }
