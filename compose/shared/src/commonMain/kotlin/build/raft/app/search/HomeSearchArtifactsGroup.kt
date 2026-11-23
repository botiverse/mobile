package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import build.raft.app.data.SearchArtifactHit
import build.raft.app.data.SearchArtifactsIntent
import build.raft.app.data.SearchArtifactsSource
import build.raft.app.data.SearchArtifactsState
import build.raft.app.data.SearchArtifactsStatus
import build.raft.app.data.SharedSearchArtifactsStore
import build.raft.app.markdown.lucideIcon
import build.raft.app.resources.*
import build.raft.app.data.ArtifactHome
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftSearchResultRow
import build.raft.designsystem.components.RaftSearchSectionHeader
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * The Artifacts group of global search (web `AppSearchGroupSection` + `ArtifactSearchHit`, slock
 * staging 93207e216): under the destinations and above the messages, a section headed "Artifacts"
 * with its count; each hit is a search row with the kind's tile, the title with the words marked,
 * and "where it lives · who made it · when it last changed" (the Artifacts home page's parts). While
 * it loads, its heading over a skeleton; when it timed out or failed, a warning with Retry and the
 * trace id; Load more on the page (the wide panel previews 5 and hands the rest to the page). A
 * group with nothing for these words has no section.
 */
internal object HomeSearchArtifactsTags {
    const val Group = "search-artifacts-group"
    const val Hit = "search-artifacts-hit"
    const val Loading = "search-artifacts-loading"
    const val Failed = "search-artifacts-failed"
    const val Retry = "search-artifacts-retry"
    const val More = "search-artifacts-more"
    const val Archived = "search-app-hit-archived"
}

/** One surface's store (the page or the wide panel): [limit] per page, null for the server's default. */
internal class HomeSearchArtifactsViewModel(source: SearchArtifactsSource, limit: Int?, logger: (String) -> Unit) : ViewModel() {
    private val storeDelegate = lazy { SharedSearchArtifactsStore(source, viewModelScope, limit = limit, logger = logger) }
    val store: SharedSearchArtifactsStore by storeDelegate
    val state: StateFlow<SearchArtifactsState> get() = store.state

    override fun onCleared() {
        if (storeDelegate.isInitialized()) store.clear()
        super.onCleared()
    }
}

/** What the rows need besides the group: the words, the server's name ("Server" home), the clock. */
internal class HomeSearchArtifactsContext(
    val query: String,
    val serverName: String?,
    val timeContext: AppShellThreadTimeContext?,
)

/** The page's (and the inline list's) section, between the destinations and the messages. */
internal fun LazyListScope.homeSearchArtifactsItems(
    state: SearchArtifactsState?,
    context: HomeSearchArtifactsContext,
    selectedKey: String?,
    onIntent: (SearchArtifactsIntent) -> Unit,
    onOpen: (SearchArtifactHit) -> Unit,
    rowModifier: @Composable (key: String) -> Modifier = { Modifier },
) {
    if (state == null || !state.shown) return
    item(key = "search-artifacts-heading") {
        HomeSearchArtifactsHeading(state, overlay = false)
    }
    when (state.status) {
        SearchArtifactsStatus.Loading -> item(key = "search-artifacts-loading") { HomeSearchArtifactsSkeleton() }
        SearchArtifactsStatus.Failed ->
            item(key = "search-artifacts-failed") { HomeSearchArtifactsFailed(state) { onIntent(SearchArtifactsIntent.Retry) } }
        else -> {
            items(state.hits.size, key = { index -> homeSearchArtifactKey(state.hits[index]) }) { index ->
                val hit = state.hits[index]
                val key = homeSearchArtifactKey(hit)
                Box(rowModifier(key)) {
                    HomeSearchArtifactHitRow(
                        hit = hit,
                        context = context,
                        selected = selectedKey == key,
                        modifier =
                            Modifier
                                .raftSafeAreaHorizontalPadding()
                                .heightIn(min = RaftSearchMetrics.entityMinHeight(RaftTheme.pointerInput)),
                        onOpen = { onOpen(hit) },
                    )
                }
            }
            if (state.nextCursor != null) {
                item(key = "search-artifacts-more") { HomeSearchArtifactsMore(state) { onIntent(SearchArtifactsIntent.LoadMore) } }
            }
        }
    }
    item(key = "search-artifacts-end") { RaftSearchListEnd() }
}

/** A hit's key among the page's rows (web `app:artifacts:<id>`). */
internal fun homeSearchArtifactKey(hit: SearchArtifactHit): String = "app:artifacts:${hit.id}"

/** The section's heading: "Artifacts", and its count once it has results (web `search-app-group-count`). */
@Composable
internal fun HomeSearchArtifactsHeading(state: SearchArtifactsState, overlay: Boolean) {
    val label = stringResource(Res.string.search_app_group_artifacts)
    val count = state.hits.size.takeIf { state.status == SearchArtifactsStatus.Ok }
    if (overlay) {
        RaftSearchSectionHeader(label, Modifier.testTag(HomeSearchArtifactsTags.Group), count = count)
    } else {
        Box(Modifier.testTag(HomeSearchArtifactsTags.Group)) { SharedHomeSearchSectionLabel(label, count = count) }
    }
}

/** Whether the group's skeleton pulses; screenshots draw it at rest (raft-ui `motion-reduce:animate-none`). */
internal val LocalHomeSearchArtifactsSkeletonAnimated = staticCompositionLocalOf { true }

/** web `AppHitSkeleton`: three rows of the entity row's tile and two lines. */
@Composable
internal fun HomeSearchArtifactsSkeleton() {
    val animated = LocalHomeSearchArtifactsSkeletonAnimated.current
    Column(Modifier.fillMaxWidth().testTag(HomeSearchArtifactsTags.Loading)) {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(horizontal = RaftSearchMetrics.Inset, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RaftSearchMetrics.EntityGap),
            ) {
                RaftSkeleton(Modifier.size(RaftSearchMetrics.EntityLeading), animated = animated)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftSkeleton(Modifier.fillMaxWidth(0.4f), variant = RaftSkeletonVariant.Line, animated = animated)
                    RaftSkeleton(Modifier.fillMaxWidth(0.6f), variant = RaftSkeletonVariant.Line, animated = animated)
                }
            }
        }
    }
}

/** web `search-app-group-failed`: what broke and the one way out, in this section only; the trace id under it. */
@Composable
internal fun HomeSearchArtifactsFailed(state: SearchArtifactsState, onRetry: () -> Unit) {
    val app = stringResource(Res.string.search_app_group_artifacts)
    Column(
        Modifier
            .fillMaxWidth()
            .raftSafeAreaHorizontalPadding()
            .padding(horizontal = RaftSearchMetrics.Inset, vertical = 4.dp)
            .testTag(HomeSearchArtifactsTags.Failed),
    ) {
        RaftBanner(
            description =
                if (state.timedOut) {
                    stringResource(Res.string.search_app_group_timed_out, app)
                } else {
                    stringResource(Res.string.search_app_group_failed, app)
                },
            status = RaftBannerStatus.Warning,
            action = {
                RaftButton(
                    text = stringResource(Res.string.common_retry),
                    onClick = onRetry,
                    modifier = Modifier.testTag(HomeSearchArtifactsTags.Retry),
                    variant = RaftButtonVariant.Default,
                )
            },
        )
        RaftTraceIdNote(state.error?.traceId, Modifier.padding(top = 4.dp))
    }
}

/** web `search-app-group-more`: a ghost Load more, loading while the next page reads. */
@Composable
private fun HomeSearchArtifactsMore(state: SearchArtifactsState, onLoadMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(horizontal = RaftSearchMetrics.Inset, vertical = 4.dp)) {
        RaftButton(
            text = stringResource(Res.string.artifacts_dashboard_show_more),
            onClick = onLoadMore,
            modifier = Modifier.testTag(HomeSearchArtifactsTags.More),
            variant = RaftButtonVariant.Ghost,
            loading = state.paging,
        )
    }
}

/**
 * web `ArtifactSearchHit`: the kind's tile, the title with the words marked (an old `.md` / `.html`
 * dropped, as the home page shows it), then "#place · author · 3d".
 */
@Composable
internal fun HomeSearchArtifactHitRow(
    hit: SearchArtifactHit,
    context: HomeSearchArtifactsContext,
    selected: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val row = hit.row
    val line =
        listOf(
            artifactsHomePlaceWords(row, context.serverName),
            artifactsHomeActorName(row.author, stringResource(Res.string.artifacts_dashboard_author_unknown)),
            raftRelativeTimestampLabel(row.summary.updatedAt, context.timeContext),
        ).filter { it.isNotBlank() }.joinToString(" · ")
    // web ArtifactSearchHit: its home conversation is archived (`home.conversation.archived`).
    val homeArchived = (row.summary.home as? ArtifactHome.Conversation)?.archived == true
    RaftSearchResultRow(
        onClick = onOpen,
        modifier = modifier.testTag(HomeSearchArtifactsTags.Hit),
        selected = selected,
        badge =
            if (homeArchived) {
                {
                    RaftBadge(
                        text = stringResource(Res.string.channel_settings_status_archived),
                        modifier = Modifier.testTag(HomeSearchArtifactsTags.Archived),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Muted,
                    )
                }
            } else {
                null
            },
        leading = {
            RaftIconTile(size = RaftIconTileSize.Sm) {
                RaftLucideIcon(row.summary.kind.lucideIcon(), Modifier.size(RaftIconTileSize.Sm.icon), color = raftArgbHex(colors.foregroundMuted))
            }
        },
        description = {
            RaftText(line, style = RaftTheme.typography.callout, color = colors.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    ) {
        RaftSearchHitText(
            text = artifactsHomeShownTitle(row.summary.title),
            query = context.query,
            style = RaftTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
