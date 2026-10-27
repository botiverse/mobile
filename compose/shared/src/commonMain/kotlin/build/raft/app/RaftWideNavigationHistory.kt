package build.raft.app

/*
 * Back / forward through what the wide (split) layout showed (artin 2026-10-27, "宽屏还有一个痛点，
 * 就是前进后退"; Slack desktop's history arrows). The shell records one [RaftWideNavEntry] per
 * destination the wide layout lands on — the rail surface plus the conversation or member in its
 * detail pane — and the top bar's arrows, Cmd+[ / Cmd+] and the mouse's back / forward buttons
 * move through them like a browser.
 *
 * Entries hold identity only (Route / PageData MVI rule): a conversation keeps its detail identity
 * and the identity-only launch route the shell reopens it with, a member its
 * [MemberDetailIdentity]. Restoring an entry goes back through the normal open path; the
 * destination's stores load its content.
 */

/** Host -> app shell pager event (desktop shortcuts and mouse buttons): move through the history. */
internal const val RAFT_WIDE_HISTORY_NAVIGATE_EVENT = "slockWideHistoryNavigate"

/** [RAFT_WIDE_HISTORY_NAVIGATE_EVENT]'s data key: a [RaftWideNavDirection.wireName]. */
internal const val RAFT_WIDE_HISTORY_DIRECTION = "direction"

/** Entries kept per server; the oldest drop off first. */
internal const val RaftWideNavHistoryCap = 50

internal enum class RaftWideNavDirection(val wireName: String) {
    Back("back"),
    Forward("forward");

    companion object {
        fun fromWireName(value: String?): RaftWideNavDirection? = entries.firstOrNull { it.wireName == value?.trim() }
    }
}

/**
 * A host's request to step through the history ([RAFT_WIDE_HISTORY_NAVIGATE_EVENT]); [nonce] tells
 * two requests in the same direction apart.
 */
internal data class RaftWideNavRequest(val direction: RaftWideNavDirection, val nonce: Int)

/**
 * How long a Back / Forward waits for the shell to show the entry it reopens before it settles
 * ([RaftWideNavHistory.settle]): a member still loading, or a conversation that could not reopen.
 */
internal const val RaftWideNavRestoreSettleMillis = 2_000L

/** What the detail pane of an entry showed. */
internal sealed interface RaftWideNavDetail {
    /**
     * A conversation in the shared detail pane (channel, DM, thread, task). Two entries are the same
     * destination when their [identity] is ([RaftAdaptiveDetailIdentity.key]); [route] is only how
     * to reopen it.
     */
    class Conversation(
        val identity: RaftAdaptiveDetailIdentity,
        val route: WireThreadLaunchPayload,
    ) : RaftWideNavDetail {
        override fun equals(other: Any?): Boolean = other is Conversation && other.identity.key == identity.key

        override fun hashCode(): Int = identity.key.hashCode()

        override fun toString(): String = "Conversation(${identity.surfaceKind}:${identity.threadId})"
    }

    /** A member or agent in the Members tab's detail pane. */
    data class Member(val identity: MemberDetailIdentity) : RaftWideNavDetail
}

/**
 * One place the wide layout showed: [surface] is the rail tab key (`home`, `activity`, `dms`,
 * `tasks`, `members`, `settings`) or an inline rail destination ([AppShellRailSearchKey],
 * [AppShellRailSavedKey]); [detail] what its detail pane held, null for none.
 */
internal data class RaftWideNavEntry(
    val serverId: String,
    val surface: String,
    val detail: RaftWideNavDetail? = null,
)

/**
 * One server's history: [entries] oldest first, [index] the one showing (-1 while empty).
 *
 * [pendingRestore] is the entry a Back / Forward is reopening. Until the shell shows it, the
 * intermediate states the restore passes through (tab switched, detail not yet open) are not new
 * navigation and are not recorded; [settle] ends a restore that never arrives.
 */
internal data class RaftWideNavHistory(
    val entries: List<RaftWideNavEntry> = emptyList(),
    val index: Int = -1,
    val pendingRestore: RaftWideNavEntry? = null,
    val cap: Int = RaftWideNavHistoryCap,
) {
    init {
        require(cap > 0) { "Wide navigation history needs a positive cap" }
        require(index in -1..entries.lastIndex) { "Wide navigation index $index out of ${entries.size}" }
    }

    val current: RaftWideNavEntry?
        get() = entries.getOrNull(index)

    val canGoBack: Boolean
        get() = index > 0

    val canGoForward: Boolean
        get() = index in 0 until entries.lastIndex

    /**
     * The shell now shows [entry]:
     * - the entry a Back / Forward is restoring ends the restore; anything else meanwhile is ignored;
     * - the current entry again (a re-selection) changes nothing;
     * - the same surface replacing an empty detail (a tab opened, then a row picked in it; the Home
     *   default landing) takes that entry's place: "Members, nothing picked" is not a stop;
     * - anything else is new navigation: it drops the forward entries and is pushed, the oldest
     *   entry dropping off past [cap].
     */
    fun record(entry: RaftWideNavEntry): RaftWideNavHistory {
        if (pendingRestore != null) {
            return if (entry == pendingRestore) copy(pendingRestore = null) else this
        }
        val now = current
        if (entry == now) return this
        val kept = entries.take(index + 1)
        if (now != null && now.detail == null && now.surface == entry.surface && now.serverId == entry.serverId) {
            return copy(entries = kept.dropLast(1) + entry)
        }
        val grown = kept + entry
        val trimmed = if (grown.size > cap) grown.drop(grown.size - cap) else grown
        return copy(entries = trimmed, index = trimmed.lastIndex)
    }

    /** Moves one step in [direction]; [current] is then the entry to reopen. Unchanged at an end. */
    fun go(direction: RaftWideNavDirection): RaftWideNavHistory {
        val target =
            when (direction) {
                RaftWideNavDirection.Back -> if (canGoBack) index - 1 else return this
                RaftWideNavDirection.Forward -> if (canGoForward) index + 1 else return this
            }
        return copy(index = target, pendingRestore = entries[target])
    }

    /**
     * Ends a restore that did not arrive (the destination could not open, or is still loading):
     * [showing], what the shell shows now, is recorded as usual.
     */
    fun settle(showing: RaftWideNavEntry?): RaftWideNavHistory {
        if (pendingRestore == null) return this
        val settled = copy(pendingRestore = null)
        return if (showing == null) settled else settled.record(showing)
    }
}

/**
 * The histories of every server the wide layout showed, one stack per server: switching server
 * starts (or resumes) that server's own history, and Back never crosses into another server —
 * entries reopen through the current server's open path, and a stale other-server conversation
 * behind the arrows would switch servers by surprise. In memory only: a restart starts afresh.
 */
internal data class RaftWideNavHistories(
    val byServer: Map<String, RaftWideNavHistory> = emptyMap(),
    val cap: Int = RaftWideNavHistoryCap,
) {
    fun of(serverId: String?): RaftWideNavHistory = serverId?.let(byServer::get) ?: RaftWideNavHistory(cap = cap)

    fun record(entry: RaftWideNavEntry): RaftWideNavHistories = with(entry.serverId, of(entry.serverId).record(entry))

    fun go(serverId: String, direction: RaftWideNavDirection): RaftWideNavHistories = with(serverId, of(serverId).go(direction))

    fun settle(serverId: String, showing: RaftWideNavEntry?): RaftWideNavHistories =
        with(serverId, of(serverId).settle(showing?.takeIf { it.serverId == serverId }))

    private fun with(serverId: String, history: RaftWideNavHistory): RaftWideNavHistories =
        if (byServer[serverId] == history) this else copy(byServer = byServer + (serverId to history))
}

/**
 * What the wide layout shows now, as a history entry; null when there is nothing to record (no
 * server yet, or a Home secondary page over the list).
 *
 * The detail is what the surface actually draws: the Members tab its own member selection; the
 * shared conversation detail only on the surfaces that host it ([appShellSharedDetailSurfaceVisible];
 * on the DMs tab only a DM, [appShellDmsTabShowsDetail]). Settings and a Tasks board without its
 * task overlay have none.
 */
internal fun raftWideNavEntryOf(
    serverId: String?,
    selectedTab: String,
    railInlineSecondaryDestination: String?,
    selectedHomeDestination: String?,
    tasksDetailOverlayOpen: Boolean,
    conversation: RaftAdaptiveDetailIdentity?,
    conversationRoute: WireThreadLaunchPayload?,
    member: MemberDetailIdentity?,
): RaftWideNavEntry? {
    val server = serverId?.trim()?.takeIf(String::isNotBlank) ?: return null
    val tab = retainedAppShellSelectedTab(selectedTab)
    if (railInlineSecondaryDestination == null && tab == "home" && selectedHomeDestination != null) return null
    val surface = railInlineSecondaryDestination ?: tab
    val sharedDetailShows =
        appShellSharedDetailSurfaceVisible(
            pageVisible = true,
            selectedTab = tab,
            selectedHomeDestination = selectedHomeDestination,
            railInlineSecondaryDestination = railInlineSecondaryDestination,
            tasksDetailOverlayOpen = tasksDetailOverlayOpen,
        ) &&
            (surface != "dms" || appShellDmsTabShowsDetail(conversation?.surfaceKind, conversationRoute?.parentSurfaceKind))
    val detail =
        when {
            surface == "members" -> member?.let(RaftWideNavDetail::Member)
            sharedDetailShows && conversation != null && conversation.serverId == server && conversationRoute != null ->
                RaftWideNavDetail.Conversation(conversation, conversationRoute.toRaftWideNavRoute())
            else -> null
        }
    return RaftWideNavEntry(serverId = server, surface = surface, detail = detail)
}

/**
 * The launch route a history entry keeps: identity and intent only. The origin preview, composer
 * seed and one-shot request nonces of the open that produced it are dropped (as the in-pane
 * [build.raft.app.data.AdaptiveDetailHistory] does), so a restore neither replays stale content
 * nor re-fires a focus or composer request.
 */
internal fun WireThreadLaunchPayload.toRaftWideNavRoute(): WireThreadLaunchPayload =
    copy(
        originFallbackPreview = null,
        originFallbackSenderId = null,
        originFallbackSenderKind = null,
        composerSeedText = "",
        composerSyncNonce = 0,
        composerFocusRequestNonce = 0,
        focusedMessageRequestNonce = 0,
    )
