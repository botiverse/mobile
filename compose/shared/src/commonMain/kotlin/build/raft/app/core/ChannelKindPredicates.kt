package build.raft.app

import build.raft.app.data.HomeChannelRow

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
