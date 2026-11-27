package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import build.raft.app.data.SearchChannelHit
import build.raft.app.data.SearchCoreGroup
import build.raft.app.data.SearchCoreGroupState
import build.raft.app.data.SearchCoreGroupStatus
import build.raft.app.data.SearchCoreGroupsIntent
import build.raft.app.data.SearchCoreGroupsSource
import build.raft.app.data.SearchCoreGroupsState
import build.raft.app.data.SearchPersonHit
import build.raft.app.data.SearchRequiredAction
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedSearchCoreGroupsStore
import build.raft.app.label
import build.raft.app.memberDetailIdentity
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.topLevelThreadId
import build.raft.app.resources.*
import build.raft.app.settings.SearchEntityResult
import build.raft.app.settings.SearchEntityType
import build.raft.app.settings.toHomeSearchEntityResult
import build.raft.app.thread.key
import build.raft.app.thread.label
import build.raft.app.thread.memberDetailIdentity
import build.raft.app.toHomeSearchEntityResult
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.StateFlow

/*
 * Global search's destinations in groups, from the server (web #15387 `composeSearchEntityGroups`,
 * `SearchEntityResultsSection`, `EntityResultRow`; docs/contracts/web-search.md "Web client"):
 *
 * - Typing paints the local rows at once (loaded channels, people, agents, computers, matched by
 *   the composer ranker, no status rule).
 * - Core's `channels` and `people` groups answer for the words in the field and then REPLACE that
 *   group's local rows: the server's items, order and status. Computers stay local.
 * - Until a group answers, its section shows the local rows without a count or Show all. A group
 *   that can't be asked (offline, an error, a group error) keeps its local rows, and a line under
 *   the summary says only loaded channels and people are shown (offline), or offers Retry.
 * - A row draws one mark at most: a channel Archived, else External (`external || guest`); a person
 *   Agent, else Guest. No kind tag: the heading says it. A public channel the reader hasn't joined
 *   says "Not joined" before its description; it opens on its preview, whose footer has Join.
 * - "Show all" / "Show fewer" sits at the heading's end; expanded, Load more pages the group with
 *   its cursor, and a failed page shows a warning with Retry in Load more's place (no toast).
 */

/** A destinations section (web `SearchEntityGroupId`), in drawing order. */
internal enum class HomeSearchEntityGroupId(val testTag: String) {
    Channels("channels"),
    People("people"),
    Computers("computers"),
}

/** The server's status of a channel or person row (web `SearchEntityStatus`). */
internal data class SearchEntityStatus(
    val archived: Boolean,
    val joined: Boolean,
    /** A channel shared with another server (a joint). */
    val external: Boolean,
    /** A channel Guests take part in, or a person who is a Guest (told to owners and admins only). */
    val guest: Boolean,
    val canPost: Boolean,
    val requiredAction: SearchRequiredAction?,
)

/** The section a destination is drawn in (web `searchEntityGroupOf`). */
internal fun SearchEntityResult.homeSearchEntityGroupId(): HomeSearchEntityGroupId =
    when (type) {
        SearchEntityType.Channel -> HomeSearchEntityGroupId.Channels
        SearchEntityType.Computer -> HomeSearchEntityGroupId.Computers
        SearchEntityType.DirectMessage,
        SearchEntityType.Human,
        SearchEntityType.Agent -> HomeSearchEntityGroupId.People
    }

/**
 * A channel of the server's `channels` group as a destination row (web `channelHitToEntity`), with
 * the local row's key so selection and the opened-row mark carry over. It opens the loaded
 * channel's own identity when the app holds it ([local]), else the channel by id and type: a
 * channel the reader hasn't joined opens on its preview.
 */
internal fun SearchChannelHit.toHomeSearchEntityResult(
    local: Map<String, SearchEntityResult>,
    channelLabel: String,
): SearchEntityResult {
    val channelId = ChannelId(id)
    val threadId = topLevelThreadId(channelId).value
    val key = "conversation:$threadId"
    val identity =
        local[key]?.conversationIdentity
            ?: buildThreadLaunchConversationIdentity(
                kind = if (type == JointChannelKindWireValue) ConversationKind.Joint else ConversationKind.Channel,
                channelId = channelId,
                channelType = type,
            )
    return SearchEntityResult(
        key = key,
        type = SearchEntityType.Channel,
        title = name,
        subtitle = description ?: channelLabel,
        subtitleDataRange = if (description == null) IntRange.EMPTY else null,
        threadId = threadId,
        memberIdentity = null,
        conversationIdentity = identity,
        archived = archived,
        status =
            SearchEntityStatus(
                archived = archived,
                joined = joined,
                external = external,
                guest = guest,
                canPost = canPost,
                requiredAction = requiredAction,
            ),
        description = description,
    )
}

/**
 * A person or agent of the server's `people` group as a destination row (web `personHitToEntity`),
 * with the local row's key. Their DM opens when the app holds it ([local]); otherwise the open asks
 * for it ([openHomeSearchEntityWithDirectMessage]), as for any person row. Null without a server.
 */
internal fun SearchPersonHit.toHomeSearchEntityResult(
    local: Map<String, SearchEntityResult>,
    serverId: String,
    agentLabel: String,
    personLabel: String,
): SearchEntityResult? {
    val kind = if (agent) MemberKind.Agent else MemberKind.Human
    val identity = memberDetailIdentity(id, kind, serverId) ?: return null
    val key = "sender:$serverId:$kind:${identity.memberId.value}"
    val localRow = local[key]
    val handle = name.trim()
    return SearchEntityResult(
        key = key,
        type = if (agent) SearchEntityType.Agent else SearchEntityType.Human,
        title = displayName ?: handle,
        subtitle =
            when {
                handle.isBlank() -> if (agent) agentLabel else personLabel
                agent -> "@$handle"
                else -> handle
            },
        subtitleDataRange = if (handle.isBlank()) IntRange.EMPTY else null,
        threadId = localRow?.conversationIdentity?.threadId?.value,
        memberIdentity = identity,
        conversationIdentity = localRow?.conversationIdentity,
        avatarWireValue = avatarUrl ?: localRow?.avatarWireValue,
        showPresence = agent,
        status =
            SearchEntityStatus(
                archived = false,
                joined = dmChannelId != null,
                external = false,
                guest = guest,
                canPost = canPost,
                requiredAction = requiredAction,
            ),
    )
}

/** One destinations section: the server's rows (and how many it has in all), or the local first paint. */
internal data class HomeSearchEntityGroupView(
    val id: HomeSearchEntityGroupId,
    val results: List<SearchEntityResult>,
    /** Every match: the server's `total`, else the local rows' count. */
    val total: Int,
    /** The group's answer for these words replaced the local rows. */
    val fromServer: Boolean,
    /** The server has more rows past [results] (Load more pages on). */
    val hasMore: Boolean = false,
    /** A next page is loading. */
    val paging: Boolean = false,
    /** The last next page failed: Retry in Load more's place. */
    val pageFailed: Boolean = false,
    /** The failed page's error (its trace id). */
    val pageError: ScreenError? = null,
    /** The group's server answer for these words hasn't arrived: local rows, but no count or Show all. */
    val pending: Boolean = false,
) {
    /** The heading's count: the group's total, unknown while it is [pending]. */
    val count: Int? get() = total.takeUnless { pending }

    /** The rows drawn: the first [HomeSearchEntityPreviewCount] unless [expanded]. */
    fun shown(expanded: Boolean): List<SearchEntityResult> = results.homeSearchShownEntities(expanded)

    /** Show all / Show fewer at the heading's end (web: expanded, or a known count over the preview). */
    fun showsToggle(expanded: Boolean): Boolean = expanded || (count?.let(::homeSearchEntitiesCollapsible) == true)

    /** Load more (or its failure with Retry) at the section's end: expanded, with rows past these. */
    fun showsMore(expanded: Boolean): Boolean = expanded && hasMore
}

/** The destinations sections for the words in the field (web `composeSearchEntityGroups`'s answer). */
internal data class HomeSearchEntityGroups(
    val groups: List<HomeSearchEntityGroupView> = emptyList(),
    /** Some group shows local rows because its request failed: what is shown may not be everything. */
    val incomplete: Boolean = false,
    /** A failed group is being asked again (the incomplete line says Searching… in Retry's place). */
    val retrying: Boolean = false,
    /** Some group is still being asked for these words. */
    val pending: Boolean = false,
    /** The first failure, its trace id shown under the incomplete line. */
    val error: ScreenError? = null,
) {
    val results: List<SearchEntityResult> get() = groups.flatMap { it.results }

    val isEmpty: Boolean get() = groups.isEmpty()

    /** Every destination, shown or not (the summary's count). */
    val total: Int get() = groups.sumOf { it.total }

    fun group(id: HomeSearchEntityGroupId): HomeSearchEntityGroupView? = groups.firstOrNull { it.id == id }
}

/**
 * The destinations sections for [query] (web `composeSearchEntityGroups`): each core group's
 * answer for these exact words replaces the local rows of that group ([local], ranked, every type),
 * in the server's order and with its status; until it arrives, and when it can't (offline, a
 * failed request), the local rows stay. Computers have no server group: always local. A server
 * group with nothing for these words has no section. [core] null: core's groups are off (a
 * conversation's own search), every section is local.
 */
internal fun composeHomeSearchEntityGroups(
    query: String,
    local: List<SearchEntityResult>,
    core: SearchCoreGroupsState?,
    toChannel: (SearchChannelHit) -> SearchEntityResult,
    toPerson: (SearchPersonHit) -> SearchEntityResult?,
): HomeSearchEntityGroups {
    val words = query.trim()
    var incomplete = false
    var retrying = false
    var anyPending = false
    var error: ScreenError? = null
    val groups =
        HomeSearchEntityGroupId.entries.mapNotNull { id ->
            val localRows = local.filter { it.homeSearchEntityGroupId() == id }
            val answer: SearchCoreGroupState<*>? =
                when (id) {
                    HomeSearchEntityGroupId.Channels -> core?.channels
                    HomeSearchEntityGroupId.People -> core?.people
                    HomeSearchEntityGroupId.Computers -> null
                }
            if (answer != null && answer.status == SearchCoreGroupStatus.Ok && answer.query == words) {
                val rows =
                    when (id) {
                        HomeSearchEntityGroupId.Channels -> core!!.channels.items.map(toChannel)
                        else -> core!!.people.items.mapNotNull(toPerson)
                    }.distinctBy { it.key }
                return@mapNotNull rows.takeIf { it.isNotEmpty() }?.let {
                    HomeSearchEntityGroupView(
                        id = id,
                        results = it,
                        total = maxOf(answer.total, it.size),
                        fromServer = true,
                        hasMore = answer.nextCursor != null,
                        paging = answer.paging,
                        pageFailed = answer.pageFailed,
                        pageError = answer.error.takeIf { answer.pageFailed },
                    )
                }
            }
            if (answer != null && answer.status == SearchCoreGroupStatus.Failed && answer.query == words) {
                incomplete = true
                if (error == null) error = answer.error
            }
            // Asked and not answered for these words yet (an answer for other words is about to go).
            val pending =
                words.isNotEmpty() && answer != null &&
                    (
                        answer.status == SearchCoreGroupStatus.Loading ||
                            (answer.status != SearchCoreGroupStatus.Idle && answer.query != words)
                    )
            if (pending) anyPending = true
            if (pending && answer?.status == SearchCoreGroupStatus.Loading && answer.retrying) retrying = true
            localRows.takeIf { it.isNotEmpty() }?.let {
                HomeSearchEntityGroupView(id = id, results = it, total = it.size, fromServer = false, pending = pending)
            }
        }
    return HomeSearchEntityGroups(groups = groups, incomplete = incomplete, retrying = retrying, pending = anyPending, error = error)
}

/** The one mark a destination row draws (web `EntityResultRow`), in priority order. */
internal enum class HomeSearchEntityBadge { Archived, External, Agent, Guest }

/** Archived, by the server's status when it has one, else by the loaded channel. */
internal val SearchEntityResult.archivedNow: Boolean
    get() = status?.archived ?: archived

/**
 * The row's one mark at most (web `EntityResultRow`; raft-design badge.md): a channel Archived,
 * else External (a channel with Guests is External too: raft-design channel.md); a person or DM
 * Agent when it is an agent, else Guest. Computers, and an App Bot (its App badge is drawn
 * instead), have none.
 */
internal fun SearchEntityResult.homeSearchEntityBadge(): HomeSearchEntityBadge? =
    when (type) {
        SearchEntityType.Channel ->
            when {
                archivedNow -> HomeSearchEntityBadge.Archived
                status?.external == true || status?.guest == true -> HomeSearchEntityBadge.External
                else -> null
            }
        SearchEntityType.Agent -> HomeSearchEntityBadge.Agent
        SearchEntityType.DirectMessage -> HomeSearchEntityBadge.Agent.takeIf { showPresence }
        SearchEntityType.Human -> HomeSearchEntityBadge.Guest.takeIf { status?.guest == true }
        SearchEntityType.Computer -> null
    }

/** A public channel the reader hasn't joined (`requiredAction: "join_channel"`): "Not joined" in its description. */
internal val SearchEntityResult.homeSearchNotJoined: Boolean
    get() = type == SearchEntityType.Channel && status?.requiredAction == SearchRequiredAction.JoinChannel

/** The channel draws the external icon: the server says it is external or has Guests. */
internal val SearchEntityResult.homeSearchExternalChannel: Boolean
    get() = type == SearchEntityType.Channel && (status?.external == true || status?.guest == true)

/** The core groups' line under the summary (web `SearchEntityResultsIncompleteNote`). */
internal enum class HomeSearchCoreGroupsNote { Offline, Failed, Retrying }

internal fun HomeSearchEntityGroups.note(offline: Boolean): HomeSearchCoreGroupsNote? =
    when {
        !incomplete && !retrying -> null
        offline -> HomeSearchCoreGroupsNote.Offline
        retrying -> HomeSearchCoreGroupsNote.Retrying
        else -> HomeSearchCoreGroupsNote.Failed
    }

/**
 * The host says it has no network (the app shell's offline banner), for search's "only loaded
 * channels and people are shown" (web `navigator.onLine === false`).
 */
internal val LocalRaftAppShellNetworkOffline = compositionLocalOf { false }

internal object HomeSearchEntityGroupTags {
    const val Group = "search-entity-group"
    const val ShowAll = "search-entity-show-all"
    const val LoadMore = "search-entity-load-more"
    const val LoadMoreFailed = "search-entity-load-more-failed"
    const val LoadMoreRetry = "search-entity-load-more-retry"
    const val Incomplete = "search-entity-incomplete"
    const val IncompleteRetry = "search-entity-incomplete-retry"
    const val NotJoined = "search-entity-not-joined"
    const val Badge = "search-entity-badge"
}

@Composable
internal fun HomeSearchEntityGroupId.label(): String =
    stringResource(
        when (this) {
            HomeSearchEntityGroupId.Channels -> Res.string.search_group_channels
            HomeSearchEntityGroupId.People -> Res.string.search_group_people
            HomeSearchEntityGroupId.Computers -> Res.string.search_group_computers
        },
    )

/** The heading's end: "Show all" / "Show fewer", a muted text link (raft-design text-link.md: See all beside a section title). */
@Composable
internal fun HomeSearchEntityShowAllToggle(expanded: Boolean, onToggle: () -> Unit) {
    RaftTextLink(
        text = stringResource(if (expanded) Res.string.search_show_fewer_entities else Res.string.search_show_all_entities),
        onClick = onToggle,
        modifier = Modifier.testTag(HomeSearchEntityGroupTags.ShowAll),
        variant = RaftTextLinkVariant.Muted,
        style = RaftTheme.typography.footnote,
        maxLines = 1,
    )
}

/**
 * The end of an expanded group with more rows: a ghost Load more (loading while the page reads), or,
 * when the last page failed, a warning in its place with Retry and the trace id (no toast).
 */
@Composable
internal fun HomeSearchEntityGroupMore(view: HomeSearchEntityGroupView, onLoadMore: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .raftSafeAreaHorizontalPadding()
            .padding(horizontal = RaftSearchMetrics.Inset, vertical = 4.dp),
    ) {
        if (view.pageFailed && !view.paging) {
            Box(Modifier.testTag(HomeSearchEntityGroupTags.LoadMoreFailed)) {
                RaftBanner(
                    description = stringResource(Res.string.search_load_more_failed),
                    status = RaftBannerStatus.Warning,
                    action = {
                        RaftButton(
                            text = stringResource(Res.string.common_retry),
                            onClick = onLoadMore,
                            modifier = Modifier.testTag(HomeSearchEntityGroupTags.LoadMoreRetry),
                            variant = RaftButtonVariant.Default,
                        )
                    },
                )
            }
            RaftTraceIdNote(view.pageError?.traceId, Modifier.padding(top = 4.dp))
        } else {
            RaftButton(
                text = stringResource(Res.string.search_load_more),
                onClick = onLoadMore,
                modifier = Modifier.testTag(HomeSearchEntityGroupTags.LoadMore),
                variant = RaftButtonVariant.Ghost,
                loading = view.paging,
            )
        }
    }
}

/**
 * The summary's second line (web `SearchEntityResultsIncompleteNote`): offline says so; a failed
 * request says only loaded channels and people are shown, with Retry (Searching… while it asks
 * again) and the trace id.
 */
@Composable
internal fun HomeSearchCoreGroupsNoteLine(
    note: HomeSearchCoreGroupsNote,
    error: ScreenError?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(
        modifier
            .fillMaxWidth()
            .raftSafeAreaHorizontalPadding()
            .padding(start = RaftSearchMetrics.Inset, top = 2.dp, end = RaftSearchMetrics.Inset)
            .testTag(HomeSearchEntityGroupTags.Incomplete),
    ) {
        when (note) {
            HomeSearchCoreGroupsNote.Offline ->
                RaftText(stringResource(Res.string.search_core_groups_offline), style = typography.footnote, color = colors.foregroundHint)
            HomeSearchCoreGroupsNote.Retrying ->
                RaftText(
                    "${stringResource(Res.string.search_core_groups_failed)} ${stringResource(Res.string.search_searching)}",
                    style = typography.footnote,
                    color = colors.foregroundHint,
                )
            HomeSearchCoreGroupsNote.Failed -> {
                RaftText(stringResource(Res.string.search_core_groups_failed), style = typography.footnote, color = colors.foregroundHint)
                RaftTextLink(
                    text = stringResource(Res.string.common_retry),
                    onClick = onRetry,
                    modifier = Modifier.testTag(HomeSearchEntityGroupTags.IncompleteRetry),
                    variant = RaftTextLinkVariant.Strong,
                    style = typography.footnote,
                    maxLines = 1,
                )
                RaftTraceIdNote(error?.traceId, Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** One surface's core groups store (the page or the wide panel). */
internal class HomeSearchCoreGroupsViewModel(source: SearchCoreGroupsSource, logger: (String) -> Unit) : ViewModel() {
    private val storeDelegate = lazy { SharedSearchCoreGroupsStore(source, viewModelScope, logger = logger) }
    val store: SharedSearchCoreGroupsStore by storeDelegate
    val state: StateFlow<SearchCoreGroupsState> get() = store.state

    override fun onCleared() {
        if (storeDelegate.isInitialized()) store.clear()
        super.onCleared()
    }
}

/** The group's store id of a section (computers have none). */
internal val HomeSearchEntityGroupId.coreGroup: SearchCoreGroup?
    get() =
        when (this) {
            HomeSearchEntityGroupId.Channels -> SearchCoreGroup.Channels
            HomeSearchEntityGroupId.People -> SearchCoreGroup.People
            HomeSearchEntityGroupId.Computers -> null
        }

/** Load more for [group] as the store's intent, or null for a local group. */
internal fun HomeSearchEntityGroupId.loadMoreIntent(): SearchCoreGroupsIntent? = coreGroup?.let(SearchCoreGroupsIntent::LoadMore)
