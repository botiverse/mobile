package build.raft.app.settings

import build.raft.app.resources.*
import build.raft.app.settings.iconName
import build.raft.app.settings.toHomeSearchEntityResult
import build.raft.app.settingsComputerRouteIdentity
import build.raft.app.thread.icon
import build.raft.app.thread.memberDetailIdentity
import build.raft.app.thread.toThreadLaunchConversationIdentity
import build.raft.app.toHomeSearchEntityResult
import build.raft.app.toThreadLaunchConversationIdentity
import build.raft.app.markdown.markdownToPreviewText
import build.raft.app.memberDetailIdentity
import build.raft.app.members.iconName
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import build.raft.app.HomeSearchEntityResultLimit
import build.raft.app.JointChannelKindWireValue
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftChannelKindGlyph
import build.raft.app.RaftChannelKinds
import build.raft.app.RaftFilterOption
import build.raft.app.RaftLucideIconName
import build.raft.app.SearchEntityStatus
import build.raft.app.SettingsComputerRouteIdentity
import build.raft.app.ThreadComposerSuggestionSearchEntry
import build.raft.app.ThreadComposerSuggestionSearchField
import build.raft.app.ThreadLaunchConversationIdentity
import build.raft.app.buildSearchSnippet
import build.raft.app.data.SharedComputersState
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.selectComputerServerSlice
import build.raft.app.get
import build.raft.app.homeSearchExternalChannel
import build.raft.app.iconName
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.HomeConversationRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SearchScope
import build.raft.app.data.SearchSort
import build.raft.app.data.SearchSenderFilterItem
import build.raft.app.data.SearchFrequentEntityLimit
import build.raft.app.data.SearchFrequentEntityRank
import build.raft.app.data.SearchPersonalizationEntityKey
import build.raft.app.data.SearchPersonalizationEntityKind
import build.raft.app.data.SearchTimeRange
import build.raft.app.data.SharedHomeSearchPanelState
import build.raft.app.data.ScreenError
import build.raft.app.data.InboxItem
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.Message
import build.raft.app.raftChannelKindGlyph
import build.raft.app.rankThreadComposerSuggestionEntries
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import build.raft.app.data.map

internal data class SearchChannelFilterItem(
    val id: String,
    val label: String,
    val icon: RaftLucideIconName
)

internal val SearchChannelFilterItem.iconName: RaftLucideIconName
    get() = icon

internal fun homeSearchChannelFilters(state: SharedHomeState): List<SearchChannelFilterItem> {
    val snapshot = (state.content as? SharedHomeContentState.Content)?.snapshot ?: return emptyList()
    val channelFilters =
        snapshot.channels
            .mapNotNull { conversation ->
                val channelId = conversation.channelId?.value ?: return@mapNotNull null
                SearchChannelFilterItem(
                    id = channelId,
                    label = conversation.name.removePrefix("#"),
                    icon = RaftLucideIconName.Hash
                )
            }
    val directMessageFilters =
        snapshot.directMessages
            .map { conversation ->
                SearchChannelFilterItem(
                    id = conversation.channelId?.value ?: conversation.threadId.value,
                    label = "@${conversation.name.removePrefix("@")}",
                    icon = if (conversation.isAgentDirectMessage()) RaftLucideIconName.Bot else RaftLucideIconName.CircleUserRound
                )
            }
    return (channelFilters + directMessageFilters)
        .distinctBy { it.id }
}

internal enum class SearchEntityType {
    Channel,
    DirectMessage,
    Human,
    Agent,
    Computer
}

internal data class HomeSearchComputerDirectory(
    val serverId: String? = null,
    val machines: List<ComputerSummary> = emptyList(),
    val status: SharedComputersLoadStatus = SharedComputersLoadStatus.Idle,
    val hasSnapshot: Boolean = false,
    val refreshing: Boolean = false,
    val initialLoadError: ScreenError? = null,
) {
    val initialLoadInFlight: Boolean
        get() = !hasSnapshot && status == SharedComputersLoadStatus.InFlight
}

internal fun SharedComputersState.toHomeSearchComputerDirectory(serverId: String?): HomeSearchComputerDirectory {
    val normalizedServerId = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return HomeSearchComputerDirectory()
    val slice = selectComputerServerSlice(normalizedServerId)
    return HomeSearchComputerDirectory(
        serverId = normalizedServerId,
        machines = slice.machines,
        status = slice.status,
        hasSnapshot = slice.hasSnapshot,
        refreshing = slice.refreshing,
        initialLoadError = slice.error.takeIf { !slice.hasSnapshot && slice.status == SharedComputersLoadStatus.Error },
    )
}

/** The shared snippet of a channel [description] when it holds a hit of [query], else the description as is. */
internal fun channelDescriptionSnippet(description: String, query: String): String =
    if (homeSearchHighlightRanges(description, query).isEmpty()) {
        description
    } else {
        buildSearchSnippet(description, homeSearchHighlightTokens(query))
    }

internal data class SearchEntityResult(
    val key: String,
    val type: SearchEntityType,
    val title: String,
    val subtitle: String,
    val threadId: String?,
    val memberIdentity: MemberDetailIdentity?,
    val conversationIdentity: ThreadLaunchConversationIdentity?,
    val computerIdentity: SettingsComputerRouteIdentity? = null,
    val avatarWireValue: String? = null,
    val showPresence: Boolean = false,
    val archived: Boolean = false,
    /** The part of [subtitle] that is real data (a handle, a description); the rest is a fixed label that is never marked. Null = all of it. */
    val subtitleDataRange: IntRange? = null,
    /**
     * The server's status of a row of core's search (`GET /api/search` channels / people groups);
     * null on the local first paint. Drawn as it is (docs/contracts/web-search.md: clients only draw it).
     */
    val status: SearchEntityStatus? = null,
    /** A channel's description (a not-joined channel says what it is about under "Not joined"). */
    val description: String? = null,
) {
    val memberId: String?
        get() = memberIdentity?.memberId?.value
}

/**
 * The agent whose App Bot identity a search entity row shows (web `EntityResultRow` /
 * `SearchHomeEntityRow` `useAppBotAgent(result.type === "agentDm" ? result.agentId : null)`):
 * an agent result, or a DM whose peer is an agent. Null for channels, computers and humans.
 */
/**
 * An App Bot's search subtitle without the "Agent" kind word the directory mapping puts first
 * ("Agent · @stamp" -> "@stamp", "Agent · @stamp · Members" -> "@stamp · Members", "Agent" -> "").
 * Web `searchEntities` gives an agent its `@handle` as the subtitle; a Bot is not an Agent.
 * The marked `@handle` range moves with the text. Other results are returned as they are.
 */
internal fun SearchEntityResult.withoutAgentKindLabel(agentLabel: String): SearchEntityResult {
    if (type != SearchEntityType.Agent || agentLabel.isEmpty() || !subtitle.startsWith(agentLabel)) return this
    val rest = subtitle.removePrefix(agentLabel)
    val cut =
        when {
            rest.isEmpty() -> agentLabel.length
            rest.startsWith(SearchEntitySubtitleSeparator) -> agentLabel.length + SearchEntitySubtitleSeparator.length
            else -> return this
        }
    val range = subtitleDataRange
    return copy(
        subtitle = subtitle.substring(cut),
        subtitleDataRange =
            when {
                range == null -> null
                range.isEmpty() -> IntRange.EMPTY
                range.first < cut -> IntRange.EMPTY
                else -> (range.first - cut)..(range.last - cut)
            },
    )
}

private const val SearchEntitySubtitleSeparator = " · "

internal fun SearchEntityResult.appBotLookupMemberId(): String? {
    if (type != SearchEntityType.Agent && type != SearchEntityType.DirectMessage) return null
    if (memberIdentity?.kind != MemberKind.Agent) return null
    return memberId?.trim()?.takeIf { it.isNotBlank() }
}

/**
 * A channel result's glyph (web EntityResultRow `ChannelIcon`): the Home row's ([RaftChannelKinds])
 * when the channel is loaded, else from its type alone (web `ChannelIcon` fallback).
 */
internal fun SearchEntityResult.homeSearchChannelGlyph(kinds: RaftChannelKinds = RaftChannelKinds.Empty): RaftChannelKindGlyph {
    val identity = conversationIdentity ?: return kinds.glyph(null, null)
    return kinds.glyph(identity.channelId?.value, homeSearchChannelWireType())
}

/**
 * The glyph a channel result draws (botiverse/slock 6b0965841 packages/web/src/features/search/
 * EntityResultRow.tsx `SearchChannelIcon`): a row of core's search carries its status, so people
 * from outside (`external || guest`) come from it even for a channel the app hasn't loaded; the
 * public page (`guestVisible`) and Slack (`bridge`) from the Home row ([homeSearchChannelGlyph]).
 * The one chooser then picks the widest group: the globe, then the Slack mark, then two people.
 */
internal fun SearchEntityResult.homeSearchChannelIconGlyph(kinds: RaftChannelKinds = RaftChannelKinds.Empty): RaftChannelKindGlyph {
    val homeGlyph = homeSearchChannelGlyph(kinds)
    if (!homeSearchExternalChannel) return homeGlyph
    return raftChannelKindGlyph(
        homeSearchChannelWireType(),
        onPublicPage = homeGlyph == RaftChannelKindGlyph.PublicPage,
        external = true,
        slack = homeGlyph.isSlack,
    )
}

/** A channel result's wire type (`channel` / `private` / `joint`), from its launch identity. */
internal fun SearchEntityResult.homeSearchChannelWireType(): String? {
    val identity = conversationIdentity ?: return null
    return if (identity.kind == ConversationKind.Joint) JointChannelKindWireValue else identity.channelType ?: "channel"
}

internal fun SearchEntityResult.homeSearchEntityLeadingIconName(kinds: RaftChannelKinds = RaftChannelKinds.Empty): RaftLucideIconName? =
    when (type) {
        SearchEntityType.Channel -> homeSearchChannelGlyph(kinds).iconName
        SearchEntityType.Computer -> RaftLucideIconName.Monitor
        SearchEntityType.DirectMessage,
        SearchEntityType.Human,
        SearchEntityType.Agent -> null
    }

internal sealed interface HomeSearchEntityDestination {
    data class Conversation(val identity: ThreadLaunchConversationIdentity) : HomeSearchEntityDestination
    data class Member(val identity: MemberDetailIdentity) : HomeSearchEntityDestination
    data class Computer(val identity: SettingsComputerRouteIdentity) : HomeSearchEntityDestination
}

internal fun SearchEntityResult.destinationOrNull(): HomeSearchEntityDestination? {
    computerIdentity?.let { identity ->
        settingsComputerRouteIdentity(identity.serverId, identity.machineId)?.let {
            return HomeSearchEntityDestination.Computer(it)
        }
    }
    conversationIdentity?.let { return HomeSearchEntityDestination.Conversation(it) }
    val identity = memberIdentity ?: return null
    if (identity.memberId.value.isBlank() || identity.sourceServerId?.value.isNullOrBlank()) return null
    if (identity.kind != MemberKind.Human && identity.kind != MemberKind.Agent) return null
    return HomeSearchEntityDestination.Member(identity)
}

/**
 * The conversation a search entity opens in the wide shell's detail pane beside the inline
 * Search, or null when it has no inline home there (a computer, a person or agent): those open
 * their page, as on the phone.
 */
internal fun SearchEntityResult.homeSearchInlineConversationOrNull(): ThreadLaunchConversationIdentity? =
    (destinationOrNull() as? HomeSearchEntityDestination.Conversation)?.identity

/**
 * Opens a search entity: inline first when [openInline] takes it, else its page. An inline host
 * that declines (returns false) must not swallow the click: the wide Search returned false for
 * computers and people, and `false ?: page` never reached the page (artin 11-02, Compose 版).
 */
internal fun openHomeSearchEntityResult(
    entity: SearchEntityResult,
    openInline: ((SearchEntityResult) -> Boolean)?,
    openPage: () -> Boolean,
): Boolean = openInline?.invoke(entity) == true || openPage()

internal sealed interface HomeSearchResultGroup {
    data class Message(val item: InboxItem) : HomeSearchResultGroup
    data class Thread(
        val key: String,
        val title: String,
        val items: List<InboxItem>
    ) : HomeSearchResultGroup
}

/** web searchGrouping `truncateThreadTitle`: a thread result's title longer than this is cut. */
private const val HomeSearchThreadTitleMaxLength = 80

/**
 * A thread result's title (web searchGrouping `truncateThreadTitle`): its parent message as one
 * line, and past 80 characters its first 77 and "...". Null when the parent message has no text
 * (the result says "Thread discussion").
 */
internal fun homeSearchThreadTitle(parentMessagePreview: String?): String? {
    val line = parentMessagePreview?.let(::markdownToPreviewText)?.takeIf { it.isNotBlank() } ?: return null
    if (line.length <= HomeSearchThreadTitleMaxLength) return line
    // The web's `slice(0, 77)`, without splitting a character made of two UTF-16 units.
    val keep = if (line[76].isHighSurrogate()) 76 else 77
    return line.take(keep) + "..."
}

@Composable
internal fun SharedHomeSearchPanelState.senderFilterOptions(): List<RaftFilterOption> {
    val meLabel = stringResource(Res.string.search_sender_me)
    return senderFilters
        .map { sender ->
            RaftFilterOption(
                id = sender.id,
                label = if (sender.isSelf) meLabel else sender.displayLabel,
                icon = if (sender.kind == MemberKind.Agent) RaftLucideIconName.Bot else RaftLucideIconName.CircleUserRound
            )
        }
        .distinctBy { it.id }
}

@Composable
internal fun SharedHomeSearchPanelState.localizedSenderFilterLabel(): String {
    val selectedId = selectedSenderId?.trim()?.takeIf { it.isNotBlank() }
        ?: return stringResource(Res.string.search_filter_from)
    val meLabel = stringResource(Res.string.search_sender_me)
    val selected = senderFilters.firstOrNull { it.id == selectedId }
    return stringResource(
        Res.string.search_filter_from_value,
        selected?.let { if (it.isSelf) meLabel else it.displayLabel } ?: selectedId
    )
}

private val SearchSenderFilterItem.displayLabel: String
    get() = label.ifBlank { handle.ifBlank { id } }

internal fun homeSearchEntityResults(
    query: String,
    homeState: SharedHomeState,
    senderFilters: List<SearchSenderFilterItem>,
    channelLabel: String,
    dmLabel: String,
    agentLabel: String,
    personLabel: String,
    membersLabel: String,
    computerLabel: String = "Computer",
    computerDirectory: HomeSearchComputerDirectory = HomeSearchComputerDirectory(),
): List<SearchEntityResult> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) return emptyList()
    val candidates =
        homeSearchEntityCandidates(
            homeState = homeState,
            senderFilters = senderFilters,
            channelLabel = channelLabel,
            dmLabel = dmLabel,
            agentLabel = agentLabel,
            personLabel = personLabel,
            membersLabel = membersLabel,
            computerLabel = computerLabel,
            computerDirectory = computerDirectory,
        )
    val entries =
        candidates
            .let { unfiltered ->
                when (trimmedQuery.firstOrNull()) {
                    '#' -> unfiltered.filter { it.result.type == SearchEntityType.Channel }
                    '@' ->
                        unfiltered.filter {
                            it.result.type == SearchEntityType.Human ||
                                it.result.type == SearchEntityType.Agent
                        }
                    else -> unfiltered
                }
            }
            .mapIndexed { index, candidate ->
                ThreadComposerSuggestionSearchEntry(
                    index = index,
                    suggestion = candidate.result,
                    fields =
                        candidate.searchFields.map { field ->
                            ThreadComposerSuggestionSearchField(field.value, priority = field.priority)
                        },
                )
            }
    // The local first paint (web `buildSearchEntityEntries`): the composer ranker's matching and
    // order, no status rule (archived, joined, Guest) decides anything; core's groups replace
    // these rows once they answer ([composeHomeSearchEntityGroups]).
    // web MAX_ENTITY_RESULTS: every reasonable match up to 50; the list shows 5, then "Show all N".
    return rankThreadComposerSuggestionEntries(query = trimmedQuery, entries = entries, limit = HomeSearchEntityResultLimit)
        .distinctBy { it.key }
        .take(HomeSearchEntityResultLimit)
}

internal data class HomeSearchEntityCandidate(
    val result: SearchEntityResult,
    val searchFields: List<HomeSearchEntitySearchField>,
)

internal data class HomeSearchEntitySearchField(
    val value: String,
    val priority: Int,
)

internal fun homeSearchEntityCandidates(
    homeState: SharedHomeState,
    senderFilters: List<SearchSenderFilterItem>,
    channelLabel: String,
    dmLabel: String,
    agentLabel: String,
    personLabel: String,
    membersLabel: String,
    computerLabel: String = "Computer",
    computerDirectory: HomeSearchComputerDirectory = HomeSearchComputerDirectory(),
): List<HomeSearchEntityCandidate> {
    val snapshot = (homeState.content as? SharedHomeContentState.Content)?.snapshot ?: return emptyList()
    val conversations = snapshot.channels + snapshot.directMessages
    val homeServerId = snapshot.server.id.value
    // task #238: this map is an identity index, so it needs an owner scope and must reject blank
    // ids. Keyed by a bare memberId, a joint-channel member carried in from another server could
    // collide with a local DM peer; and a blank wire peerId (`MemberId("")`) survived the null
    // check and sat in the map as a key that can only ever join unrelated rows. A missing scope
    // dimension does not cost an extra row - it merges two identities into one.
    val byPresenceMemberId =
        snapshot.directMessages
            .mapNotNull { conversation ->
                val scope =
                    homeSearchMemberScopeKey(homeServerId, conversation.presenceMemberId?.value.orEmpty())
                        ?: return@mapNotNull null
                scope to conversation.toThreadLaunchConversationIdentity()
            }
            .toMap()
    val conversationEntries =
        conversations.map { conversation ->
            val type =
                if (conversation.kind == ConversationKind.DirectMessage) {
                    SearchEntityType.DirectMessage
                } else {
                    SearchEntityType.Channel
                }
            val conversationIdentity = conversation.toThreadLaunchConversationIdentity()
            HomeSearchEntityCandidate(
                result =
                    SearchEntityResult(
                        key = "conversation:${conversation.threadId.value}",
                        type = type,
                        title = conversation.name,
                        subtitle =
                            conversation.subtitle?.takeIf { it.isNotBlank() }
                                ?: if (type == SearchEntityType.Channel) channelLabel else dmLabel,
                        subtitleDataRange = if (conversation.subtitle.isNullOrBlank()) IntRange.EMPTY else null,
                        threadId = conversation.threadId.value,
                        memberIdentity = conversationIdentity.dmPeerIdentity,
                        conversationIdentity = conversationIdentity,
                        avatarWireValue = null,
                        showPresence = conversation.isAgentDirectMessage(),
                        archived = conversation.archived,
                        description = conversation.subtitle?.takeIf { type == SearchEntityType.Channel && it.isNotBlank() },
                    ),
                searchFields =
                    listOf(
                        HomeSearchEntitySearchField(conversation.name, priority = 0),
                        HomeSearchEntitySearchField(conversation.subtitle.orEmpty(), priority = 3),
                        HomeSearchEntitySearchField(conversation.channelId?.value.orEmpty(), priority = 5),
                        HomeSearchEntitySearchField(conversation.threadId.value, priority = 5),
                    ),
            )
        }
    val dmCandidatesByThreadId =
        conversationEntries
            .asSequence()
            .filter { candidate -> candidate.result.type == SearchEntityType.DirectMessage }
            .mapNotNull { candidate -> candidate.result.threadId?.let { threadId -> threadId to candidate } }
            .toMap()
    // task #238: a DM row and its member row are the same entity reached through two disjoint key
    // namespaces ("conversation:<threadId>" vs "sender:<server>:<kind>:<memberId>"), so the
    // trailing distinctBy() was structurally unable to collapse them. Merge from the member side:
    // it is the richer row, and its `byPresenceMemberId` hit only needs presenceMemberId, whereas
    // the conversation side's dmPeerIdentity additionally requires a parseable peer kind - i.e.
    // strictly weaker coverage. The member row already carries the DM launch affordance via
    // conversationIdentity, so it also absorbs the DM row's search fields before that row is
    // dropped, keeping DM name/subtitle/threadId queryable.
    val absorbedDmThreadIds = mutableSetOf<String>()
    val memberEntries =
        senderFilters.mapNotNull { sender ->
            val conversation =
                homeSearchMemberScopeKey(sender.sourceServerId.value, sender.id)
                    ?.let { scope -> byPresenceMemberId[scope] }
            val suggestion =
                sender.toHomeSearchEntityResult(
                    conversationIdentity = conversation,
                    agentLabel = agentLabel,
                    personLabel = personLabel,
                    membersLabel = membersLabel,
                ) ?: return@mapNotNull null
            val absorbedDm =
                conversation?.threadId?.value?.let { threadId -> dmCandidatesByThreadId[threadId] }
            absorbedDm?.result?.threadId?.let { threadId -> absorbedDmThreadIds += threadId }
            HomeSearchEntityCandidate(
                result = suggestion,
                searchFields =
                    listOf(
                        HomeSearchEntitySearchField(sender.displayLabel, priority = 0),
                        HomeSearchEntitySearchField(sender.handle, priority = 1),
                        HomeSearchEntitySearchField(sender.id, priority = 5),
                    ) + absorbedDm?.searchFields.orEmpty()
            )
        }
    val computerEntries =
        computerDirectory.machines
            .takeIf { computerDirectory.serverId == snapshot.server.id.value }
            .orEmpty()
            .asSequence()
            .filter { it.isComputer }
            .distinctBy { it.id }
            .mapNotNull { machine ->
                val computerIdentity =
                    settingsComputerRouteIdentity(
                        serverId = computerDirectory.serverId,
                        machineId = machine.id,
                    ) ?: return@mapNotNull null
                val title = machine.name.trim().ifBlank { machine.hostname?.trim().orEmpty().ifBlank { machine.id } }
                HomeSearchEntityCandidate(
                    result =
                        SearchEntityResult(
                            key = "computer:${computerIdentity.serverId}:${computerIdentity.machineId}",
                            type = SearchEntityType.Computer,
                            title = title,
                            subtitle = machine.hostname?.trim().orEmpty().ifBlank { computerLabel },
                            subtitleDataRange = if (machine.hostname?.trim().isNullOrEmpty()) IntRange.EMPTY else null,
                            threadId = null,
                            memberIdentity = null,
                            conversationIdentity = null,
                            computerIdentity = computerIdentity,
                        ),
                    searchFields =
                        listOf(
                            HomeSearchEntitySearchField(machine.name, priority = 0),
                            HomeSearchEntitySearchField(machine.hostname.orEmpty(), priority = 1),
                            HomeSearchEntitySearchField(machine.description.orEmpty(), priority = 3),
                            HomeSearchEntitySearchField(machine.os.orEmpty(), priority = 4),
                        ),
                )
            }
            .toList()
    val visibleConversationEntries =
        conversationEntries.filterNot { candidate ->
            candidate.result.type == SearchEntityType.DirectMessage &&
                candidate.result.threadId?.let { threadId -> threadId in absorbedDmThreadIds } == true
        }
    return (visibleConversationEntries + memberEntries + computerEntries).distinctBy { it.result.key }
}

/**
 * Owner scope for the DM-peer identity index used by [homeSearchEntityCandidates].
 *
 * A member id is only unique within its own server, so an identity key that omits the server can
 * merge two distinct people. Callers pass the server the row was sourced from: DM rows come from
 * the current Home snapshot, member rows carry their own `sourceServerId` (joint channels surface
 * members from other servers).
 *
 * Deliberately a typed pair rather than a "$serverId:$memberId" string: a concatenated key is
 * delimiter-ambiguous, so ("a:b", "c") and ("a", "b:c") would collide into one entry - the same
 * wrong-merge failure this scope exists to prevent. Blank components yield no key at all, so a
 * blank wire peer id can never become a merge key that joins unrelated rows.
 */
private data class HomeSearchMemberScope(val serverId: String, val memberId: String)

private fun homeSearchMemberScopeKey(serverId: String, memberId: String): HomeSearchMemberScope? {
    val scopedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    val scopedMemberId = memberId.trim().takeIf { it.isNotBlank() } ?: return null
    return HomeSearchMemberScope(serverId = scopedServerId, memberId = scopedMemberId)
}

internal fun homeSearchFrequentEntityResults(
    homeState: SharedHomeState,
    senderFilters: List<SearchSenderFilterItem>,
    ranks: Map<SearchPersonalizationEntityKey, SearchFrequentEntityRank>,
    channelLabel: String,
    dmLabel: String,
    agentLabel: String,
    personLabel: String,
    membersLabel: String,
): List<SearchEntityResult> {
    if (ranks.isEmpty()) return emptyList()
    val snapshot = (homeState.content as? SharedHomeContentState.Content)?.snapshot ?: return emptyList()
    val currentMemberId = snapshot.currentMember.id.value
    val hiddenDmIds = snapshot.sidebarOrder?.hiddenDmIds.orEmpty().toSet()
    val eligibleConversationKeys =
        (snapshot.channels + snapshot.directMessages)
            .asSequence()
            .filter { conversation ->
                conversation.joined &&
                    conversation.permissions.canRead &&
                    !conversation.archived &&
                    (
                        conversation.kind != ConversationKind.DirectMessage ||
                            (
                                conversation.threadId.value !in hiddenDmIds &&
                                    conversation.channelId?.value !in hiddenDmIds
                            )
                    )
            }
            .mapNotNull { conversation ->
                val kind =
                    if (conversation.kind == ConversationKind.DirectMessage) {
                        SearchPersonalizationEntityKind.DirectMessage
                    } else {
                        SearchPersonalizationEntityKind.Channel
                    }
                SearchPersonalizationEntityKey.create(
                    kind,
                    conversation.channelId?.value ?: conversation.threadId.value,
                )
            }
            .toSet()
    val candidates =
        homeSearchEntityCandidates(
            homeState = homeState,
            senderFilters = senderFilters,
            channelLabel = channelLabel,
            dmLabel = dmLabel,
            agentLabel = agentLabel,
            personLabel = personLabel,
            membersLabel = membersLabel,
        )
    return candidates
        .mapNotNull { candidate ->
            val result = candidate.result
            val key = result.searchPersonalizationEntityKeyOrNull() ?: return@mapNotNull null
            val rank = ranks[key] ?: return@mapNotNull null
            if (result.archived || result.destinationOrNull() == null) return@mapNotNull null
            if (
                (key.kind == SearchPersonalizationEntityKind.Channel ||
                    key.kind == SearchPersonalizationEntityKind.DirectMessage) &&
                key !in eligibleConversationKeys
            ) {
                return@mapNotNull null
            }
            if (
                (result.type == SearchEntityType.Human || result.type == SearchEntityType.Agent) &&
                result.memberId == currentMemberId
            ) {
                return@mapNotNull null
            }
            Triple(result, key, rank)
        }
        .sortedWith(
            compareByDescending<Triple<SearchEntityResult, SearchPersonalizationEntityKey, SearchFrequentEntityRank>> {
                it.third.score
            }.thenByDescending { it.third.lastOpenedAtMillis }
                .thenBy { it.first.title.lowercase() }
                .thenBy { it.second.storageValue }
        )
        .take(SearchFrequentEntityLimit)
        .map { it.first }
}

internal fun SearchEntityResult.searchPersonalizationEntityKeyOrNull(): SearchPersonalizationEntityKey? =
    when (type) {
        SearchEntityType.Channel ->
            SearchPersonalizationEntityKey.create(
                SearchPersonalizationEntityKind.Channel,
                conversationIdentity?.channelId?.value ?: conversationIdentity?.threadId?.value,
            )
        SearchEntityType.DirectMessage ->
            SearchPersonalizationEntityKey.create(
                SearchPersonalizationEntityKind.DirectMessage,
                conversationIdentity?.channelId?.value ?: conversationIdentity?.threadId?.value,
            )
        SearchEntityType.Human ->
            SearchPersonalizationEntityKey.create(SearchPersonalizationEntityKind.Human, memberId)
        SearchEntityType.Agent ->
            SearchPersonalizationEntityKey.create(SearchPersonalizationEntityKind.Agent, memberId)
        SearchEntityType.Computer -> null
    }

internal fun SearchSenderFilterItem.toHomeSearchEntityResult(
    conversationIdentity: ThreadLaunchConversationIdentity?,
    agentLabel: String,
    personLabel: String,
    membersLabel: String,
): SearchEntityResult? {
    if (kind != MemberKind.Agent && kind != MemberKind.Human) return null
    val identity = memberDetailIdentity(id, kind, sourceServerId.value) ?: return null
    return SearchEntityResult(
        key = "sender:${sourceServerId.value}:$kind:${identity.memberId.value}",
        type = if (kind == MemberKind.Agent) SearchEntityType.Agent else SearchEntityType.Human,
        title = displayLabel,
        // web searchEntities: an agent's subtitle is its "@handle", a person's their handle; with
        // none, the fixed kind label, which is never marked.
        subtitle =
            when {
                handle.isBlank() -> if (kind == MemberKind.Agent) agentLabel else personLabel
                kind == MemberKind.Agent -> "@$handle"
                else -> handle
            },
        subtitleDataRange = if (handle.isBlank()) IntRange.EMPTY else null,
        threadId = conversationIdentity?.threadId?.value,
        memberIdentity = identity,
        conversationIdentity = conversationIdentity,
        avatarWireValue = avatarWireValue,
        showPresence = kind == MemberKind.Agent,
    )
}

private fun HomeDirectMessageRow.isAgentDirectMessage(): Boolean =
    kind == ConversationKind.DirectMessage &&
        directMessagePeerType.equals("agent", ignoreCase = true)

private fun HomeConversationRow.isAgentDirectMessage(): Boolean =
    kind == ConversationKind.DirectMessage &&
        directMessagePeerType.equals("agent", ignoreCase = true)

@Composable
internal fun SearchScope.localizedSearchLabel(): String =
    stringResource(
        when (this) {
            SearchScope.Mentioned -> Res.string.search_scope_mentioned
            SearchScope.Humans -> Res.string.search_scope_humans
            SearchScope.Agents -> Res.string.search_scope_agents
        }
    )

@Composable
internal fun SearchTimeRange.localizedSearchLabel(): String =
    stringResource(
        when (this) {
            SearchTimeRange.Any -> Res.string.search_time_any
            SearchTimeRange.Today -> Res.string.search_time_today
            SearchTimeRange.Last7Days -> Res.string.search_time_last_7_days
            SearchTimeRange.Last30Days -> Res.string.search_time_last_30_days
        }
    )

@Composable
internal fun SearchSort.localizedSearchLabel(): String =
    stringResource(
        when (this) {
            SearchSort.Relevance -> Res.string.search_sort_relevant
            SearchSort.Recent -> Res.string.search_sort_recent
        }
    )
