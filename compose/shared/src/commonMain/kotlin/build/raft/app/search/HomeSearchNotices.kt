package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.formatRaftTimestampLabel
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*
import build.raft.app.settings.SettingsBillingFreeHistoryDays
import build.raft.app.settings.settingsBillingOffersUpgrade
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * Search's notices that are not results (botiverse/slock staging, packages/web/src/features/search):
 * - the plan's history window (`MessageSearchPage` `historyNotice`, `HistoryWindowBanner`
 *   surface "search"): on a Free server search reaches back only 30 days, and the page says so on
 *   every search, before anything is typed too;
 * - a search the server stopped at its scan budget (`SearchNoResultsState` / `SearchLoadMoreFooter`
 *   with `searchIncomplete`): "No matches yet" and "Continue searching" instead of "no results" and
 *   "Load more".
 */

/**
 * The plan's history window in days when search leaves older messages out, else null (web
 * `getEffectiveLimits(plan).messageHistoryDays !== -1`). Only Free has a window (shared
 * `PLAN_CONFIG.free.limits.messageHistoryDays`; its launch trial ended 2026-06-23). The plan is the
 * server's in the session's server list (web `current.plan`); a server whose plan is not known yet
 * shows no notice rather than tell a paid server it is limited.
 */
internal fun homeSearchHistoryWindowDays(plan: String?): Int? =
    if (plan?.trim()?.lowercase() == "free") SettingsBillingFreeHistoryDays else null

internal fun SharedHomeState.homeSearchHistoryWindowDays(): Int? =
    homeSearchHistoryWindowDays((content as? SharedHomeContentState.Content)?.snapshot?.server?.plan)

/**
 * "On the Free plan, search covers only the last 30 days of messages." on an info Banner, with
 * Upgrade to Pro inline (web `HistoryWindowBanner`); "View your plan" where the app offers no
 * upgrade (iOS view-only Billing). Without [onUpgrade] the sentence stands alone.
 */
@Composable
internal fun HomeSearchHistoryWindowBanner(
    days: Int,
    onUpgrade: (() -> Unit)?,
    modifier: Modifier = Modifier,
    offersUpgrade: Boolean = settingsBillingOffersUpgrade(),
) {
    val sentence = stringResource(Res.string.search_history_limited, days)
    val linkLabel =
        stringResource(if (offersUpgrade) Res.string.thread_history_paywall_upgrade else Res.string.settings_billing_view_your_plan)
    RaftBanner(
        description =
            buildAnnotatedString {
                append(sentence)
                if (onUpgrade != null) {
                    append(" ")
                    appendRaftTextLink(linkLabel, onClick = onUpgrade)
                }
            },
        status = RaftBannerStatus.Info,
        size = RaftBannerSize.Sm,
        live = false,
        // Inset by a wrapper, as the web's `px-(--column-margin)`.
        modifier = modifier.raftSafeAreaHorizontalPadding().padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/**
 * Where an incomplete search got to (web `incompleteSearchNote`): "Searched messages back to …"
 * with the oldest time the walk read, or "Only part of the messages has been searched so far."
 * when the server did not say.
 */
@Composable
internal fun homeSearchIncompleteNote(searchedThrough: String?, timeContext: RaftTimeContext?): String {
    val label = homeSearchSearchedThroughLabel(searchedThrough, currentRaftDateTimeContext(timeContext))
    return if (label != null) {
        stringResource(Res.string.search_incomplete_searched_through, label)
    } else {
        stringResource(Res.string.search_incomplete_searched_part)
    }
}

/** The local time [searchedThrough] names, as the app writes a message time ("11-06 12:10"); null when it is not a time. */
internal fun homeSearchSearchedThroughLabel(searchedThrough: String?, timeContext: RaftTimeContext?): String? {
    val value = searchedThrough?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // A context that knows today writes the time as message times are; else the device zone does.
    val context = timeContext?.takeIf { it.resolvedTodayEpochDay() != null }
    if (context != null && parseRaftTimestampEpochMillisOrNull(value, context.localOffsetMinutes) != null) {
        return formatRaftTimestampLabel(value, context).takeIf { it.isNotBlank() }
    }
    return raftDeviceZoneAbsoluteLabel(value, timeContext = timeContext)
}

/**
 * No hit yet from a search the server stopped part way (web `SearchNoResultsState`
 * `searchIncomplete`): "No matches yet", where it got to, and Continue searching.
 */
@Composable
internal fun HomeSearchIncompleteEmptyState(
    note: String,
    loadingMore: Boolean,
    onContinueSearching: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftSearchEmptyState(
        title = stringResource(Res.string.search_incomplete_no_matches_yet),
        detail = note,
        modifier = modifier.heightIn(min = 300.dp),
        actions = {
            RaftButton(
                text = stringResource(if (loadingMore) Res.string.common_loading else Res.string.search_continue_searching),
                onClick = onContinueSearching,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
                enabled = !loadingMore,
            )
        },
    )
}

/**
 * Below the message hits of a search the server stopped part way (web `SearchLoadMoreFooter`
 * `searchIncomplete`): where it got to, in hint type, over Continue searching.
 */
@Composable
internal fun HomeSearchContinueFooter(
    note: String,
    loadingMore: Boolean,
    onContinueSearching: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RaftText(
            text = note,
            style = RaftTheme.typography.caption,
            color = RaftColor.ForegroundHint,
            textAlign = TextAlign.Center,
        )
        RaftButton(
            text = stringResource(if (loadingMore) Res.string.common_loading else Res.string.search_continue_searching),
            onClick = onContinueSearching,
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Md,
            enabled = !loadingMore,
        )
    }
}
