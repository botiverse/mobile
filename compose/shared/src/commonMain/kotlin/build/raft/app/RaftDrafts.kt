package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeConversationRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.RaftDraftChannelPlace
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedTasksDataStore
import build.raft.app.data.SharedTasksScope
import build.raft.app.data.formatTaskConversationRef
import build.raft.app.data.parseRaftDraftChannelPlace
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.TaskSummary
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftPointerHover
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.components.raftStateOverlay
import build.raft.designsystem.components.rememberRaftStateOverlay
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.time.Instant
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/*
 * Drafts (botiverse/slock #12159, server flag `drafts_section_v0`): every conversation where the person
 * has an unsent composer draft, in one list under Saved, newest first. Tapping a row opens where the
 * draft was typed; that composer reads the same draft key, so the text is still there.
 *
 * A draft key (SharedThreadComposerDraftRecord) already says where it was typed: `send` (the
 * conversation the composer posts into), and for a reply `parent` (the thread's root message) and
 * `mention` (the thread's parent channel). Most drafts are therefore placed from what this device has:
 * the Home channel and DM rows, and the task store for a task conversation. Only a conversation those
 * don't know (a task conversation whose task isn't loaded, a channel not in the sidebar, another
 * server's conversation) is asked for with the existing `GET /channels/:id`, three at a time; a 404
 * or 403 ("not here") is remembered, any other failure is asked again later. Drafts that can't be
 * placed on this server stay in storage and are simply not listed.
 *
 * Only the thread composer writes this store, so there are no dialog notes (forward, artifact share,
 * attachment comments) to leave out: they never reach it on mobile.
 */

/** Where a draft was typed, on the current server. */
internal sealed interface RaftDraftPlace {
    /** A channel or a DM: opens there. */
    data class Conversation(val channelId: String, val directMessage: Boolean) : RaftDraftPlace

    /** A reply thread: opens on its parent channel's thread ([threadChannelId] null before the thread exists). */
    data class Thread(
        val parentChannelId: String,
        val parentMessageId: String,
        val threadChannelId: String?,
    ) : RaftDraftPlace

    /** A task's own conversation (task v2). [parentChannelId] / [taskId] are null while unknown. */
    data class Task(
        val conversationChannelId: String,
        val parentChannelId: String?,
        val taskId: String?,
    ) : RaftDraftPlace
}

/** What this device knows about the current server's conversations (Home rows and loaded tasks). */
@Immutable
internal data class RaftDraftDirectory(
    val serverId: String,
    val channels: Map<String, HomeChannelRow> = emptyMap(),
    val directMessages: Map<String, HomeDirectMessageRow> = emptyMap(),
    /** The Home sidebar's glyph per channel id ([activityChannelSymbolsByChannelId]). */
    val channelSymbols: Map<String, String> = emptyMap(),
    val tasks: List<TaskSummary> = emptyList(),
) {
    fun conversationRow(channelId: String?): HomeConversationRow? =
        channelId?.let { id -> directMessages[id] ?: channels[id] }

    fun taskForConversation(conversationChannelId: String): TaskSummary? =
        tasks.firstOrNull { it.conversationChannelId?.value == conversationChannelId }

    fun task(taskId: String?, conversationChannelId: String): TaskSummary? =
        taskId?.let { id -> tasks.firstOrNull { it.id.value == id } } ?: taskForConversation(conversationChannelId)

    companion object {
        fun from(serverId: String, snapshot: HomeSnapshot?, tasks: List<TaskSummary>): RaftDraftDirectory {
            val home = snapshot?.takeIf { it.server.id.value == serverId }
            return RaftDraftDirectory(
                serverId = serverId,
                channels = home?.channels.orEmpty().associateBy { it.channelId.value },
                directMessages = home?.directMessages.orEmpty().associateBy { it.channelId.value },
                channelSymbols = home?.activityChannelSymbolsByChannelId().orEmpty(),
                tasks = tasks,
            )
        }
    }
}

internal sealed interface RaftDraftPlaceResolution {
    data class Placed(val place: RaftDraftPlace) : RaftDraftPlaceResolution

    /** Not a conversation of this server (or no longer readable): left out of the list. */
    data object NotHere : RaftDraftPlaceResolution

    /** Only the server can say: ask `GET /channels/[channelId]`. */
    data class NeedsLookup(val channelId: String) : RaftDraftPlaceResolution
}

private val RaftDraftTopLevelChannelTypes = setOf("channel", "private", "joint")

/**
 * Places [this] draft on the directory's server. [lookups] holds the answers of `GET /channels/:id`
 * for this server: a value is the channel, a null value is "not here" (404/403); no entry is unasked.
 */
internal fun SharedThreadComposerDraftRecord.resolveRaftDraftPlace(
    directory: RaftDraftDirectory,
    lookups: Map<String, RaftDraftChannelPlace?>,
): RaftDraftPlaceResolution {
    val parentMessage = parentMessageId?.takeIf { it.isNotBlank() }
    if (parentMessage != null) {
        // A reply: `mention` is the thread's parent channel, `send` its stream (the parent itself
        // before the thread has a reply).
        val parentChannel = mentionChannelId?.takeIf { it.isNotBlank() } ?: return RaftDraftPlaceResolution.NotHere
        val thread =
            RaftDraftPlace.Thread(
                parentChannelId = parentChannel,
                parentMessageId = parentMessage,
                threadChannelId = sendChannelId.takeIf { it.isNotBlank() && it != parentChannel },
            )
        if (directory.conversationRow(parentChannel) != null) return RaftDraftPlaceResolution.Placed(thread)
        if (parentChannel !in lookups) return RaftDraftPlaceResolution.NeedsLookup(parentChannel)
        val answer = lookups[parentChannel] ?: return RaftDraftPlaceResolution.NotHere
        return if (answer.type in RaftDraftTopLevelChannelTypes || answer.type == "dm") {
            RaftDraftPlaceResolution.Placed(thread)
        } else {
            RaftDraftPlaceResolution.NotHere
        }
    }
    val conversation = sendChannelId
    directory.directMessages[conversation]?.let {
        return RaftDraftPlaceResolution.Placed(RaftDraftPlace.Conversation(conversation, directMessage = true))
    }
    directory.channels[conversation]?.let {
        return RaftDraftPlaceResolution.Placed(RaftDraftPlace.Conversation(conversation, directMessage = false))
    }
    directory.taskForConversation(conversation)?.let { task ->
        return RaftDraftPlaceResolution.Placed(
            RaftDraftPlace.Task(conversation, parentChannelId = task.channelId?.value, taskId = task.id.value)
        )
    }
    if (conversation !in lookups) return RaftDraftPlaceResolution.NeedsLookup(conversation)
    val answer = lookups[conversation] ?: return RaftDraftPlaceResolution.NotHere
    return when {
        answer.type == RaftTaskConversationChannelKind ->
            RaftDraftPlaceResolution.Placed(RaftDraftPlace.Task(conversation, answer.parentChannelId, answer.taskId))
        answer.type == "dm" ->
            RaftDraftPlaceResolution.Placed(RaftDraftPlace.Conversation(conversation, directMessage = true))
        answer.type in RaftDraftTopLevelChannelTypes ->
            RaftDraftPlaceResolution.Placed(RaftDraftPlace.Conversation(conversation, directMessage = false))
        answer.type == "thread" && answer.parentChannelId != null && answer.parentMessageId != null ->
            RaftDraftPlaceResolution.Placed(RaftDraftPlace.Thread(answer.parentChannelId, answer.parentMessageId, conversation))
        else -> RaftDraftPlaceResolution.NotHere
    }
}

/** One listed draft. */
@Immutable
internal data class RaftDraftEntry(
    val key: String,
    val text: String,
    val editedAtEpochMillis: Long?,
    val place: RaftDraftPlace,
)

/** The Drafts list and the conversations still to ask the server about. */
@Immutable
internal data class RaftDraftListing(
    val entries: List<RaftDraftEntry> = emptyList(),
    val lookupChannelIds: Set<String> = emptySet(),
) {
    val count: Int
        get() = entries.size

    companion object {
        val Empty = RaftDraftListing()
    }
}

/**
 * The listed drafts: a draft whose text is only whitespace (an attachment or an "as task" toggle
 * alone keeps a key) is not one; nor is one this server can't place. Newest edit first; drafts saved
 * before edit times were kept come last, in key order.
 */
internal fun raftDraftListing(
    drafts: List<SharedThreadComposerDraftListing>,
    directory: RaftDraftDirectory,
    lookups: Map<String, RaftDraftChannelPlace?>,
): RaftDraftListing {
    val entries = mutableListOf<RaftDraftEntry>()
    val lookupIds = linkedSetOf<String>()
    drafts.forEach { draft ->
        if (draft.text.isBlank()) return@forEach
        when (val resolution = draft.record.resolveRaftDraftPlace(directory, lookups)) {
            is RaftDraftPlaceResolution.Placed ->
                entries += RaftDraftEntry(draft.record.key, draft.text, draft.editedAtEpochMillis, resolution.place)
            is RaftDraftPlaceResolution.NeedsLookup -> lookupIds += resolution.channelId
            RaftDraftPlaceResolution.NotHere -> Unit
        }
    }
    return RaftDraftListing(entries = entries.sortedWith(RaftDraftEntryOrder), lookupChannelIds = lookupIds)
}

private val RaftDraftEntryOrder =
    Comparator<RaftDraftEntry> { a, b ->
        val at = a.editedAtEpochMillis
        val bt = b.editedAtEpochMillis
        when {
            at == null && bt == null -> a.key.compareTo(b.key)
            at == null -> 1
            bt == null -> -1
            at != bt -> bt.compareTo(at)
            else -> a.key.compareTo(b.key)
        }
    }

/** A `GET /channels/:id` answer for a draft lookup. */
internal sealed interface RaftDraftLookupResult {
    data class Found(val place: RaftDraftChannelPlace) : RaftDraftLookupResult

    /** 404 / 403: not this server's, gone, or not readable. Remembered. */
    data object NotHere : RaftDraftLookupResult

    /** Offline, 5xx, 429, a body that isn't a channel: asked again later. */
    data object Failed : RaftDraftLookupResult
}

internal suspend fun RaftNetworkClient.fetchRaftDraftChannelPlace(serverId: String, channelId: String): RaftDraftLookupResult {
    val response =
        try {
            request(
                priority = DataRequestPriority.Background,
                path = "channels/$channelId",
                serverIdOverride = ServerId(serverId),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return RaftDraftLookupResult.Failed
        }
    return when {
        response.isSuccessful ->
            parseRaftDraftChannelPlace(response.bodyText)?.let(RaftDraftLookupResult::Found) ?: RaftDraftLookupResult.Failed
        response.code == 404 || response.code == 403 -> RaftDraftLookupResult.NotHere
        else -> RaftDraftLookupResult.Failed
    }
}

/**
 * The server's answers about draft conversations, per signed-in account and server (a draft's
 * conversation doesn't move, but what one account may read another may not). Answers live only while
 * their account is bound: [bindAccount] with another account, or none (sign-out), drops them all, as
 * the account's drafts are dropped (SharedThreadComposerDraftAccounts). At most [maxConcurrent]
 * lookups run at once across callers.
 */
internal class RaftDraftPlaceLookups(maxConcurrent: Int = 3) {
    /** Whose answers: the draft account's storage token (environment + user) and the server. */
    data class Scope(val account: String, val serverId: String)

    private val lock = SynchronizedObject()
    private val inFlight = mutableSetOf<Pair<Scope, String>>()
    private val semaphore = Semaphore(maxConcurrent)
    private var boundAccount: String? = null
    private val answers = MutableStateFlow<Map<Scope, Map<String, RaftDraftChannelPlace?>>>(emptyMap())

    /** (account, server) -> channelId -> answer (null: not here). Only the bound account's are kept. */
    val state: StateFlow<Map<Scope, Map<String, RaftDraftChannelPlace?>>> = answers.asStateFlow()

    /** The bound account's scope on [serverId]; null while no account is bound (nothing is asked). */
    fun scope(serverId: String): Scope? = synchronized(lock) { boundAccount }?.let { Scope(it, serverId) }

    /** The signed-in account is now [account] (null: signed out). Another account's answers go. */
    fun bindAccount(account: String?) {
        synchronized(lock) {
            if (account == boundAccount) return
            boundAccount = account
            inFlight.clear()
            answers.value = emptyMap()
        }
    }

    /**
     * Asks for each of [channelIds] not answered or in flight yet for the bound account on [serverId].
     * Returns true when every asked id was answered (found or not here); false when one failed and
     * should be asked again later. An answer that arrives after the account changed is dropped.
     */
    suspend fun ensure(
        serverId: String,
        channelIds: Collection<String>,
        fetch: suspend (serverId: String, channelId: String) -> RaftDraftLookupResult,
    ): Boolean {
        val scope = scope(serverId) ?: return true
        val toAsk =
            synchronized(lock) {
                val known = answers.value[scope].orEmpty()
                channelIds
                    .filter { id -> id !in known && (scope to id) !in inFlight }
                    .distinct()
                    .also { ids -> ids.forEach { id -> inFlight += scope to id } }
            }
        if (toAsk.isEmpty()) return true
        val results =
            try {
                coroutineScope {
                    toAsk.map { id ->
                        async {
                            val result = semaphore.withPermit { fetch(serverId, id) }
                            val answer =
                                when (result) {
                                    is RaftDraftLookupResult.Found -> result.place
                                    RaftDraftLookupResult.NotHere -> null
                                    RaftDraftLookupResult.Failed -> return@async false
                                }
                            synchronized(lock) {
                                if (boundAccount == scope.account) {
                                    answers.update { all -> all + (scope to (all[scope].orEmpty() + (id to answer))) }
                                }
                            }
                            true
                        }
                    }.awaitAll()
                }
            } finally {
                synchronized(lock) { toAsk.forEach { id -> inFlight -= scope to id } }
            }
        return results.all { it }
    }
}

/** What the Drafts row and page read beyond the draft store; present only while `drafts_section_v0` is on. */
internal class RaftDraftsSource(
    val networkClient: RaftNetworkClient?,
    val tasksDataStore: SharedTasksDataStore?,
)

/**
 * The current server's [RaftDraftDirectory] from its Home [snapshot] and loaded tasks; null while
 * Drafts is off ([source] null) or Home has not loaded this server yet.
 */
@Composable
internal fun rememberRaftDraftDirectory(source: RaftDraftsSource?, snapshot: HomeSnapshot?): RaftDraftDirectory? {
    val serverId = snapshot?.server?.id?.value?.takeIf { it.isNotBlank() }
    if (source == null || serverId == null) return null
    val tasksFlow =
        remember(source.tasksDataStore, serverId) {
            source.tasksDataStore?.projectionFlow(serverId, SharedTasksScope.Server)?.map { it.tasks } ?: flowOf(emptyList())
        }
    val tasks by tasksFlow.collectAsState(emptyList())
    return remember(serverId, snapshot, tasks) { RaftDraftDirectory.from(serverId, snapshot, tasks) }
}

/** The app's one lookup cache (Home's count and the Drafts list read the same answers). */
internal val SharedRaftDraftPlaceLookups = RaftDraftPlaceLookups()

internal const val RaftDraftLookupRetryMillis = 30_000L

/**
 * The current server's draft listing, kept up to date with the draft store, Home and the task store,
 * asking the server (three at a time) for what only it can place, once Home has loaded ([directory]
 * built from a Home snapshot: before that every channel draft would look unknown and cost a request).
 */
@Composable
internal fun rememberRaftDraftListing(
    directory: RaftDraftDirectory?,
    draftPersistSlice: SharedThreadComposerDraftPersistSlice?,
    networkClient: RaftNetworkClient?,
    /** Re-read the stored texts (they change without the set of keys changing). */
    refreshTick: Int = 0,
    lookups: RaftDraftPlaceLookups = SharedRaftDraftPlaceLookups,
): RaftDraftListing {
    if (directory == null || draftPersistSlice == null) return RaftDraftListing.Empty
    val records by draftPersistSlice.activeDraftRecordsState.collectAsState()
    val drafts = remember(draftPersistSlice, records, refreshTick) { draftPersistSlice.draftListings() }
    val allAnswers by lookups.state.collectAsState()
    val serverAnswers = lookups.scope(directory.serverId)?.let { allAnswers[it] }.orEmpty()
    val listing = remember(drafts, directory, serverAnswers) { raftDraftListing(drafts, directory, serverAnswers) }
    if (networkClient != null) {
        LaunchedEffect(directory.serverId, listing.lookupChannelIds, networkClient) {
            val ids = listing.lookupChannelIds
            if (ids.isEmpty()) return@LaunchedEffect
            while (!lookups.ensure(directory.serverId, ids) { serverId, id -> networkClient.fetchRaftDraftChannelPlace(serverId, id) }) {
                delay(RaftDraftLookupRetryMillis)
            }
        }
    }
    return listing
}

// ---------------------------------------------------------------------------------------------
// Opening
// ---------------------------------------------------------------------------------------------

/**
 * Where tapping a draft goes: a channel / DM opens there, a thread on its parent channel's thread, a
 * task its own conversation. Channels and threads go through the shared resolver (the directory may
 * know the conversation better); null while a parent can't be resolved yet.
 */
internal fun RaftDraftEntry.toHomeMessageOpenRequest(
    serverId: String,
    directory: RaftDraftDirectory,
    conversationDirectory: SharedConversationDirectoryState,
): HomeMessageOpenRequest? =
    when (val place = place) {
        is RaftDraftPlace.Task ->
            HomeMessageOpenRequest(
                conversationIdentity = taskConversationLaunchIdentity(ChannelId(place.conversationChannelId)),
                anchorMessageId = null,
            )
        is RaftDraftPlace.Conversation ->
            directory.conversationRow(place.channelId)
                ?.let { row -> HomeMessageOpenRequest(row.toThreadLaunchConversationIdentity(), anchorMessageId = null) }
                ?: resolveRaftDraftOpenRequest(
                    serverId,
                    ConversationOpenSourceMessageIdentity(
                        source = ConversationOpenSource.Draft,
                        messageId = null,
                        channelId = place.channelId,
                        conversationKind = if (place.directMessage) ConversationKind.DirectMessage else ConversationKind.Channel,
                    ),
                    conversationDirectory,
                )
        is RaftDraftPlace.Thread ->
            resolveRaftDraftOpenRequest(
                serverId,
                ConversationOpenSourceMessageIdentity(
                    source = ConversationOpenSource.Draft,
                    messageId = null,
                    channelId = null,
                    threadChannelId = place.threadChannelId,
                    parentChannelId = place.parentChannelId,
                    parentMessageId = place.parentMessageId,
                    conversationKind = ConversationKind.Thread,
                    parentConversationKind = directory.conversationRow(place.parentChannelId)?.kind,
                ),
                conversationDirectory,
            )
    }

private fun resolveRaftDraftOpenRequest(
    serverId: String,
    identity: ConversationOpenSourceMessageIdentity,
    conversationDirectory: SharedConversationDirectoryState,
): HomeMessageOpenRequest? =
    (resolveOpenRequest(serverId, identity, conversationDirectory) as? ConversationOpenResolution.Resolved)?.request

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

/** What a draft row shows. Strings are resolved by the caller ([RaftDraftRowLabels]). */
@Immutable
internal data class RaftDraftRowModel(
    val key: String,
    val leading: ActivityConversationRowModel,
    val leadingLabel: String,
    val title: String,
    /** A task's `#channel:#7 ·` before the text. */
    val prefix: String?,
    /** The draft's first non-blank line. */
    val preview: String,
    /** ISO-8601, for the relative time; null for a draft without an edit time. */
    val editedAt: String?,
)

/** The localized words a draft row needs. */
@Immutable
internal data class RaftDraftRowLabels(
    val threadIn: (String) -> String,
    val taskUntitled: String,
    val unknownConversation: String,
    val prefix: (String) -> String,
)

private fun HomeConversationRow.raftDraftDmPeer(): ActivityConversationDmPeer =
    ActivityConversationDmPeer(
        peerId = presenceMemberId?.value?.takeIf { it.isNotBlank() },
        peerIsAgent = directMessagePeerType.equals("agent", ignoreCase = true),
        avatarWireValue = directMessagePeerAvatarWireValue,
        displayName = name.takeIf { it.isNotBlank() },
        activityMuted = false,
    )

/** `#name` for a channel, the person for a DM (web DraftsPanel `channelRef`). */
private fun HomeConversationRow?.raftDraftChannelRef(fallback: String): String =
    when {
        this == null -> fallback
        kind == ConversationKind.DirectMessage -> name
        else -> "#${name.trimStart('#')}"
    }

internal fun RaftDraftEntry.toRaftDraftRowModel(directory: RaftDraftDirectory, labels: RaftDraftRowLabels): RaftDraftRowModel {
    val preview = text.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    val editedAt = editedAtEpochMillis?.let { Instant.fromEpochMilliseconds(it).toString() }
    fun leading(
        kind: ActivityConversationLeadingKind,
        dmPeer: ActivityConversationDmPeer?,
        channelSymbol: String,
        threadInDirectMessage: Boolean = false,
        taskConversation: Boolean = false,
    ) = ActivityConversationRowModel(
        leadingKind = kind,
        dmPeer = dmPeer,
        title = null,
        threadChannelLabel = null,
        threadInDirectMessage = threadInDirectMessage,
        channelSymbol = channelSymbol,
        sender = null,
        preview = null,
        muted = false,
        strong = false,
        showMentionMarker = false,
        unreadCount = 0,
        showUnreadCount = false,
        hasDraft = true,
        taskNumber = null,
        taskStatus = null,
        taskConversation = taskConversation,
    )
    return when (val place = place) {
        is RaftDraftPlace.Conversation -> {
            val row = directory.conversationRow(place.channelId)
            val name = row?.name?.trimStart('#')?.takeIf { it.isNotBlank() } ?: labels.unknownConversation
            RaftDraftRowModel(
                key = key,
                leading =
                    if (place.directMessage) {
                        leading(ActivityConversationLeadingKind.DirectMessage, row?.raftDraftDmPeer(), "#")
                    } else {
                        leading(ActivityConversationLeadingKind.Channel, null, directory.channelSymbols[place.channelId] ?: "#")
                    },
                leadingLabel = name,
                title = name,
                prefix = null,
                preview = preview,
                editedAt = editedAt,
            )
        }
        is RaftDraftPlace.Thread -> {
            val parent = directory.conversationRow(place.parentChannelId)
            val inDm = parent?.kind == ConversationKind.DirectMessage
            RaftDraftRowModel(
                key = key,
                leading =
                    leading(
                        ActivityConversationLeadingKind.Thread,
                        parent?.takeIf { inDm }?.raftDraftDmPeer(),
                        directory.channelSymbols[place.parentChannelId] ?: "#",
                        threadInDirectMessage = inDm,
                    ),
                leadingLabel = parent?.name.orEmpty(),
                title = labels.threadIn(parent.raftDraftChannelRef(labels.unknownConversation)),
                prefix = null,
                preview = preview,
                editedAt = editedAt,
            )
        }
        is RaftDraftPlace.Task -> {
            val task = directory.task(place.taskId, place.conversationChannelId)
            val parentId = place.parentChannelId ?: task?.channelId?.value
            val parent = directory.conversationRow(parentId)
            val inDm = parent?.kind == ConversationKind.DirectMessage
            val taskNumber = task?.taskNumber
            val ref =
                if (taskNumber != null) {
                    formatTaskConversationRef(taskNumber, parent?.name ?: task.channelName, parent?.kind)
                } else {
                    parent?.let { it.raftDraftChannelRef(labels.unknownConversation) }
                }
            RaftDraftRowModel(
                key = key,
                leading =
                    leading(
                        ActivityConversationLeadingKind.Thread,
                        parent?.takeIf { inDm }?.raftDraftDmPeer(),
                        parentId?.let(directory.channelSymbols::get) ?: "#",
                        threadInDirectMessage = inDm,
                        taskConversation = true,
                    ),
                leadingLabel = parent?.name.orEmpty(),
                title = task?.title?.takeIf { it.isNotBlank() } ?: labels.taskUntitled,
                prefix = ref?.let(labels.prefix),
                preview = preview,
                editedAt = editedAt,
            )
        }
    }
}

@Composable
internal fun rememberRaftDraftRowLabels(): RaftDraftRowLabels {
    val threadIn = stringResource(Res.string.drafts_row_thread_in, RaftDraftLabelSlot)
    val taskUntitled = stringResource(Res.string.drafts_row_task_untitled)
    val unknown = stringResource(Res.string.drafts_row_unknown_conversation)
    val prefix = stringResource(Res.string.activity_list_thread_channel, RaftDraftLabelSlot)
    return remember(threadIn, taskUntitled, unknown, prefix) {
        RaftDraftRowLabels(
            threadIn = { name -> threadIn.replace(RaftDraftLabelSlot, name) },
            taskUntitled = taskUntitled,
            unknownConversation = unknown,
            prefix = { ref -> prefix.replace(RaftDraftLabelSlot, ref) },
        )
    }
}

/** Stands in for a format argument so a label can be filled outside composition. */
private const val RaftDraftLabelSlot = "\uE000"

private const val RaftDraftMarkInlineId = "draft-row-mark"

/** web DraftsPanel's pencil: 14px in the hint colour, before the text's first line. */
private val RaftDraftMarkSize = 14.dp

/** `before:left-16`: the hairline starts where the text column starts, as on Activity rows. */
private val RaftDraftDividerStart = 64.dp

/**
 * A draft drawn as an Activity row (web DraftsPanel `DraftItem`, raft-ui InboxItem compact): the same
 * conversation tile, the conversation as the title with the edit time at its end, and under it a
 * pencil, a task's `#channel:#7 ·`, then the draft's first line. A draft has nothing to mark done,
 * read or unfollow, so the row has no swipe or menu.
 */
@Composable
internal fun RaftDraftRow(
    model: RaftDraftRowModel,
    divider: Boolean,
    selected: Boolean,
    timeContext: RaftTimeContext?,
    agentStatusResolver: RaftAgentStatusResolver,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val hairline = colors.lineHairline
    val density = LocalDensity.current
    val titleLine = with(density) { typography.subhead.lineHeight.toDp() }
    val bodyLine = with(density) { typography.callout.lineHeight.toDp() }
    val draftLabel = stringResource(Res.string.drafts_row_draft_label)
    val openLabel = stringResource(Res.string.common_open)
    var pressed by remember(model.key) { mutableStateOf(false) }
    val hoverSource = remember { MutableInteractionSource() }
    val hover = rememberRaftStateOverlay(hoverSource, RaftHoverTier.Cell, selected = selected || pressed)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    when {
                        selected -> Modifier.background(colors.infoSoft)
                        pressed -> Modifier.background(colors.fillMedium)
                        else -> Modifier
                    }
                )
                .raftStateOverlay(hover, RectangleShape)
                .raftPointerHover(hoverSource)
                .then(
                    if (divider) {
                        Modifier.drawBehind {
                            val start = RaftDraftDividerStart.toPx()
                            drawLine(hairline, Offset(start, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                        }
                    } else {
                        Modifier
                    }
                )
                .raftSafeAreaHorizontalPadding(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .activityRowGestures(
                        gestureKey = "draft-open:${model.key}",
                        captureMenuAnchor = false,
                        onClick = onOpen,
                        onOpenMenu = {},
                        onPressedChange = { pressed = it },
                    )
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        onClick(label = openLabel) {
                            onOpen()
                            true
                        }
                    }
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActivityConversationLeading(model.leading, model.leadingLabel, agentStatusResolver)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.height(titleLine),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftText(
                        text = model.title,
                        modifier = Modifier.weight(1f),
                        style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foregroundMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model.editedAt != null) {
                        RaftRelativeTimestampText(
                            value = model.editedAt,
                            timeContext = timeContext,
                            style = typography.monoMeta.copy(fontFeatureSettings = "tnum"),
                            color = colors.foregroundHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            clampFuture = true,
                            toggleEnabled = false,
                            modifier = Modifier.widthIn(max = 96.dp),
                        )
                    }
                }
                val markWidth = with(density) { (RaftDraftMarkSize + 4.dp).toSp() }
                val markHeight = with(density) { RaftDraftMarkSize.toSp() }
                val hint = colors.foregroundHint
                val inlineContent =
                    remember(markWidth, markHeight, hint) {
                        mapOf(
                            RaftDraftMarkInlineId to
                                InlineTextContent(Placeholder(markWidth, markHeight, PlaceholderVerticalAlign.TextCenter)) {
                                    RaftLucideIcon(
                                        RaftLucideIconName.Pencil,
                                        Modifier.padding(end = 4.dp).size(RaftDraftMarkSize),
                                        color = raftArgbHex(hint),
                                    )
                                }
                        )
                    }
                val body =
                    remember(model.prefix, model.preview) {
                        buildAnnotatedString {
                            appendInlineContent(RaftDraftMarkInlineId, alternateText = " ")
                            model.prefix?.let { append("$it ") }
                            append(model.preview)
                        }
                    }
                ActivityExcerptBesideMarks(
                    text = body,
                    // Screen readers hear "Draft:" where the pencil is (web `aria-label`).
                    accessibilityText = listOfNotNull(draftLabel, model.prefix, model.preview).joinToString(" "),
                    style = typography.callout,
                    color = colors.foregroundMuted,
                    inlineContent = inlineContent,
                    lineHeight = bodyLine,
                    marks = {},
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Panel
// ---------------------------------------------------------------------------------------------

/**
 * The Drafts page (web DraftsPanel): Saved's header ("Drafts", "N drafts") and the rows, or an empty
 * state. Drafts are this device's, so there is no loading, paging or pull to refresh; a row goes away
 * once its draft is sent or emptied.
 */
@Composable
internal fun RaftDraftsPanel(
    rows: List<RaftDraftRowModel>,
    onClose: (() -> Unit)?,
    onOpen: (key: String) -> Unit,
    bottomContentPadding: Dp,
    timeContext: RaftTimeContext?,
    agentStatusResolver: RaftAgentStatusResolver,
    topSafeAreaPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    onTitleClick: (() -> Unit)? = null,
    inlineListSelection: RaftInlineListSelection? = null,
) {
    var selectedKey by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        RaftPanelBackHeader(
            title = stringResource(Res.string.drafts_title),
            subtitle = stringResource(if (rows.size == 1) Res.string.drafts_count_one else Res.string.drafts_count, rows.size),
            onBack = onClose,
            topSafeArea = topSafeAreaPadding,
            onTitleClick = onTitleClick,
        )
        RaftAppShellBodyTopBanner()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = 4.dp),
        ) {
            if (rows.isEmpty()) {
                item(key = "drafts-empty") {
                    RaftEmptyState(
                        title = stringResource(Res.string.drafts_empty_title),
                        detail = stringResource(Res.string.drafts_empty_detail),
                        iconName = RaftLucideIconName.Pencil,
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                    )
                }
            }
            items(rows.size, key = { index -> "draft-${rows[index].key}" }) { index ->
                val row = rows[index]
                RaftDraftRow(
                    model = row,
                    divider = index > 0,
                    selected = (inlineListSelection?.selectedRowKey ?: selectedKey) == row.key,
                    timeContext = timeContext,
                    agentStatusResolver = agentStatusResolver,
                    onOpen = {
                        if (inlineListSelection == null) selectedKey = row.key
                        inlineListSelection.opening(row.key) { onOpen(row.key) }
                    },
                )
            }
            item(key = "drafts-bottom") { Spacer(Modifier.height(bottomContentPadding)) }
        }
    }
}
