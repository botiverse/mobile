package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.SharedActivitySenderProfile
import build.raft.app.data.SharedActivityState
import build.raft.app.data.SharedHomeSavedPanelState
import build.raft.app.data.parseSharedInboxSnapshot
import build.raft.app.data.parseSharedSavedItems
import build.raft.app.model.InboxItemKind
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.TaskStatus
import build.raft.app.settings.AppShellThreadTimeContext
import build.raft.app.settings.SharedHomeSavedPanel
import build.raft.app.settings.appShellPhoneBottomTabs
import build.raft.app.settings.homeSearchVisualServer
import build.raft.app.shell.RaftBottomTabBar
import build.raft.app.thread.key
import kotlinx.coroutines.flow.flowOf

/*
 * Saved and Activity on the rows the web side of the same cases loads: the response bodies in
 * VisualFixtureWire (slock packages/visual-testing/shared/{savedMessages,activityResults}Fixture.json),
 * read by the parsers the app reads the real responses with. Nothing here restates a row: to change
 * one, change the JSON and regenerate. Both render at the fixture instant (homeVisualFixtureTimeContext),
 * and, like the web (its agent and member stores), take a sender's face and name from the fixture's
 * members by sender id.
 */

internal val homeVisualFixtureTimeContext =
    AppShellThreadTimeContext(
        localOffsetMinutes = VisualFixtureData.Locale.UTC_OFFSET_MINUTES,
        todayEpochDay = VisualFixtureData.Locale.TODAY_EPOCH_DAY.toLong(),
        nowEpochMillis = VisualFixtureData.Locale.NOW_EPOCH_MILLIS,
        timeFormat = RaftTimeFormat24Hour,
    )

private class VisualFixtureMember(val displayName: String, val avatar: String?)

/** The senders of the shared rows, by id (fixtureData.json agents and humans). */
private val visualFixtureMembersById: Map<String, VisualFixtureMember> =
    mapOf(
        VisualFixtureData.Humans.Owner.ID to
            VisualFixtureMember(VisualFixtureData.Humans.Owner.DISPLAY_NAME, avatar = null),
        VisualFixtureData.Agents.Cindy.ID to
            VisualFixtureMember(VisualFixtureData.Agents.Cindy.DISPLAY_NAME, VisualFixtureData.Agents.Cindy.AVATAR),
        VisualFixtureData.Agents.ProductUx.ID to
            VisualFixtureMember(VisualFixtureData.Agents.ProductUx.DISPLAY_NAME, VisualFixtureData.Agents.ProductUx.AVATAR),
    )

internal val visualFixtureSenderProfiles: Map<String, SharedActivitySenderProfile> =
    visualFixtureMembersById.mapValues { (_, member) -> SharedActivitySenderProfile(member.displayName) }

internal val visualFixtureAvatarResolver =
    RaftMemberAvatarResolver(
        currentAvatar = { memberId -> visualFixtureMembersById[memberId]?.avatar },
        avatarFlow = { memberId -> flowOf(visualFixtureMembersById[memberId]?.avatar) },
    )

internal val visualFixtureAvatarSource = ActivityAvatarSource(visualFixtureAvatarResolver, cacheServerId = null)

private val homeSavedVisualState: SharedHomeSavedPanelState =
    checkNotNull(parseSharedSavedItems(VisualFixtureWire.SAVED_MESSAGES)) { "savedMessagesFixture.json does not parse" }
        .let { saved ->
            SharedHomeSavedPanelState(
                items = saved.items,
                totalCount = saved.totalCount,
                loaded = true,
                hasMore = saved.hasMore,
                nextOffset = saved.rawResultCount,
            )
        }

// Also the rows of the wide layout's screens.wide.activity-task (WideShellVisualFixtures), as on the web.
internal val homeActivityVisualSnapshot: InboxSnapshot =
    checkNotNull(parseSharedInboxSnapshot(VisualFixtureWire.ACTIVITY_RESULTS)) { "activityResultsFixture.json does not parse" }
        .let { inbox ->
            InboxSnapshot(
                server = homeSearchVisualServer,
                items = inbox.items,
                totalCount = inbox.totalCount,
                totalUnreadCount = inbox.totalUnreadCount,
                hasMore = inbox.hasMore,
            )
        }

// Activity-tab (Android-only) screens keep the richer thread row: a claimed task and a draft.
private val homeActivityTabVisualSnapshot =
    homeActivityVisualSnapshot.copy(
        items =
            homeActivityVisualSnapshot.items.map { item ->
                if (item.kind == InboxItemKind.Thread) {
                    item.copy(
                        taskNumber = 607,
                        taskStatus = TaskStatus.InProgress,
                        taskClaimedByName = "Mahua",
                        hasDraft = true
                    )
                } else {
                    item
                }
            }
    )

private val homeActivityVisualDraftProjection =
    setOf(
        SharedThreadComposerDraftRecord(
            key = "reply:msg-agent-reply",
            sendChannelId = "thread-msg-agent-reply",
            parentMessageId = "msg-agent-reply"
        )
    ).toSharedThreadComposerDraftProjection()

@Composable
internal fun HomeSavedVisualContent(
    // PROTOTYPE (native nav): a pushed full-screen page with a working Back.
    protoOnBack: (() -> Unit)? = null,
) {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    Box(modifier = if (protoOnBack != null) Modifier.fillMaxSize() else Modifier.width(342.dp).height(620.dp)) {
        SharedHomeSavedPanel(
            state = homeSavedVisualState.copy(refreshing = params.optBoolean("task797Refreshing", false)),
            memberAvatarResolver = visualFixtureAvatarResolver,
            onIntent = {},
            onClose = protoOnBack ?: {},
            onOpenItem = {},
            bottomContentPadding = 12.dp,
            includeTopSafeArea = protoOnBack != null || params.optBoolean("task797IncludeTopSafeArea", false),
            timeContext = homeVisualFixtureTimeContext
        )
    }
}

@Composable
internal fun HomeActivityVisualContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    Box(modifier = Modifier.width(342.dp).height(620.dp)) {
        CompositionLocalProvider(LocalActivityAvatarSource provides visualFixtureAvatarSource) {
            SharedHomeActivityScreen(
                state =
                    SharedActivityState(
                        snapshot = homeActivityVisualSnapshot,
                        loaded = true,
                        pullRefreshing = params.optBoolean("task797Refreshing", false),
                        senderProfiles = visualFixtureSenderProfiles,
                    ),
                onIntent = {},
                onOpenItem = {},
                onClose = {},
                includeTopSafeArea = params.optBoolean("task797IncludeTopSafeArea", false),
                timeContext = homeVisualFixtureTimeContext,
                foregroundEpoch = 0L,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Activity as the second app-shell bottom tab (artin 2026-10-20): the main-tab head, the four
 * segments (全部 · 未读 · 提及 · 已置顶) and the 5-tab bar with the Activity badge. pageData
 * `segment` picks all / unread / mentions / pinned.
 */
@Composable
internal fun RaftActivityTabVisualContent(
    // PROTOTYPE (native nav): the whole screen with its safe area, the host draws the tab bar.
    protoFullScreen: Boolean = false,
) {
    val pager = LocalActivity.current.getPager() as Pager
    val segment =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.optString("segment").ifBlank { "all" }
        }
    val filter =
        InboxFilter.entries.firstOrNull { (it.scopeWireValue ?: it.wireValue) == segment }
            ?: InboxFilter.All
    Column(
        modifier =
            (if (protoFullScreen) Modifier.fillMaxSize() else Modifier.width(390.dp).height(844.dp))
                .background(RaftColor.SurfaceWhite)
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalActivityAvatarSource provides visualFixtureAvatarSource) {
                SharedHomeActivityScreen(
                    state =
                        SharedActivityState(
                            selectedFilter = filter,
                            snapshot = homeActivityTabVisualSnapshot,
                            loaded = true,
                            senderProfiles = visualFixtureSenderProfiles,
                        ),
                    onIntent = {},
                    onOpenItem = {},
                    onClose = null,
                    includeTopSafeArea = protoFullScreen,
                    timeContext = homeVisualFixtureTimeContext,
                    draftProjection = homeActivityVisualDraftProjection,
                    foregroundEpoch = 0L,
                    mainTab = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (!protoFullScreen) RaftBottomTabBar(
            items =
                appShellPhoneBottomTabs(
                    selectedTab = "activity",
                    homeLabel = "Home",
                    activityLabel = "Activity",
                    searchLabel = "Search",
                    tasksLabel = "Tasks",
                    moreLabel = "More",
                    activityUnreadCount = homeActivityTabVisualSnapshot.totalUnreadCount
                ),
            onSelect = {},
            modifier = Modifier.fillMaxWidth()
        )
    }
}
