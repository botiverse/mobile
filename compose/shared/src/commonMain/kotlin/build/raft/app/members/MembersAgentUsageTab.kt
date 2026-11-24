package build.raft.app

import androidx.compose.foundation.background
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.format.RaftFormatLocale
import build.raft.app.data.AgentUsageFigures
import build.raft.app.data.AgentUsageRange
import build.raft.app.data.AgentUsageResponse
import build.raft.app.data.AgentUsageSummary
import build.raft.app.data.AgentUsageView
import build.raft.app.data.DataResult
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.ScreenError
import build.raft.app.data.UsageUnreportedReason
import build.raft.app.data.agentUsageRequestWindow
import build.raft.app.data.agentUsageUtcDay
import build.raft.app.data.cacheShareOfInput
import build.raft.app.data.chartAgentUsageTokens
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.formatAgentUsageChange
import build.raft.app.data.formatAgentUsagePercent
import build.raft.app.data.formatAgentUsageTokens
import build.raft.app.data.relativeChange
import build.raft.app.data.summarizeAgentUsage
import build.raft.app.data.tokensPerTurn
import build.raft.app.data.totalAgentUsageTokens
import build.raft.app.model.MemberId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.getModelLabel
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftEmptyStateDefaults
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMeter
import build.raft.designsystem.components.RaftMeterSize
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSelectTrigger
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.rememberRaftSkeletonDelayPassed
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

internal object AgentUsageTestTags {
    const val Tab = "agent-usage-tab"
    const val PeriodTrigger = "agent-usage-period"
    const val PeriodMenuOptionPrefix = "agent-usage-period-"
    const val CacheReadsSwitch = "agent-usage-cache-reads"
    const val Skeleton = "agent-usage-skeleton"
    const val ErrorBanner = "agent-usage-error"
    const val RetryButton = "agent-usage-retry"
    const val OpenComputerButton = "agent-usage-open-computer"
    const val Empty = "agent-usage-empty"
    const val Headline = "agent-usage-headline"
    const val Change = "agent-usage-change"
    const val Freshness = "agent-usage-freshness"
    const val Since = "agent-usage-since"
    const val Chart = "agent-usage-chart"
    const val ChartScroll = "agent-usage-chart-scroll"
    const val Readout = "agent-usage-readout"
    const val BarPrefix = "agent-usage-bar-"
    const val TokenTypes = "agent-usage-token-types"
    const val NewInput = "agent-usage-new-input"
    const val CacheWrite = "agent-usage-cache-write"
    const val CacheRead = "agent-usage-cache-read"
    const val Output = "agent-usage-output"
    const val Reasoning = "agent-usage-reasoning"
    const val Total = "agent-usage-total"
    const val CacheShare = "agent-usage-cache-share"
    const val PerTurn = "agent-usage-per-turn"
    const val ContextWindow = "agent-usage-context-window"
    const val ContextMeter = "agent-usage-context-meter"
    const val Models = "agent-usage-models"
}

internal sealed interface AgentUsageLoadState {
    data object Loading : AgentUsageLoadState
    data object Error : AgentUsageLoadState
    data class Ready(val response: AgentUsageResponse) : AgentUsageLoadState
}

/**
 * Agent profile Usage tab (`botiverse/slock` `AgentUsageTab.tsx`, behind `agent_usage_v0`):
 * period selector, Cache reads switch (default off), daily stacked bars, headline + prior-period
 * change, token types (with reasoning inside output), context (cache share, per turn, context
 * window meter), and models breakdown.
 */
@Composable
internal fun MembersAgentUsageTab(
    agentId: MemberId,
    serverId: ServerId?,
    agentsApiClient: RaftAgentsApiClient?,
    timeContext: RaftTimeContext,
    onOpenComputer: (() -> Unit)?,
    modifier: Modifier = Modifier,
    nowMillisProvider: () -> Long = ::defaultNowMillis,
) {
    var view by remember(agentId.value, serverId?.value) { mutableStateOf(AgentUsageView()) }
    var withCacheReads by remember(agentId.value, serverId?.value) { mutableStateOf(false) }
    var attempt by remember(agentId.value, serverId?.value) { mutableIntStateOf(0) }

    val fallbackToday = remember(timeContext.nowEpochMillis, attempt) {
        agentUsageUtcDay(timeContext.nowEpochMillis ?: nowMillisProvider())
    }
    val requestWindow = remember(view.range, fallbackToday) {
        agentUsageRequestWindow(view.range.days, fallbackToday)
    }
    val requestKey = "${serverId?.value.orEmpty()}\u0000${agentId.value}\u0000${requestWindow.from}\u0000${requestWindow.to}\u0000$attempt"
    var settled by remember { mutableStateOf<Pair<String, AgentUsageLoadState>?>(null) }

    LaunchedEffect(requestKey, agentsApiClient) {
        val currentServerId = serverId?.value?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId)
        if (currentServerId == null || agentsApiClient == null) {
            settled = requestKey to AgentUsageLoadState.Error
            return@LaunchedEffect
        }
        settled = null
        val result =
            try {
                agentsApiClient.agentUsage(
                    serverId = currentServerId,
                    from = requestWindow.from,
                    to = requestWindow.to,
                    agentId = agentId,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                DataResult.Error(ScreenError("agent_usage_load_failed"))
            }
        settled =
            requestKey to
                when (result) {
                    is DataResult.Content -> AgentUsageLoadState.Ready(result.data)
                    is DataResult.Empty,
                    is DataResult.Error,
                    is DataResult.NoPermission -> AgentUsageLoadState.Error
                }
    }

    val loadState = if (settled?.first == requestKey) settled!!.second else AgentUsageLoadState.Loading

    RaftDesignSystemTheme {
        MembersAgentUsageTabBody(
            agentId = agentId.value,
            loadState = loadState,
            view = view,
            withCacheReads = withCacheReads,
            fallbackToday = fallbackToday,
            timeContext = timeContext,
            onViewChange = { view = it },
            onWithCacheReadsChange = { withCacheReads = it },
            onRetry = {
                settled = null
                attempt += 1
            },
            onOpenComputer = onOpenComputer,
            modifier = modifier,
        )
    }
}

@Composable
internal fun MembersAgentUsageTabBody(
    agentId: String,
    loadState: AgentUsageLoadState,
    view: AgentUsageView,
    withCacheReads: Boolean,
    fallbackToday: String,
    timeContext: RaftTimeContext,
    onViewChange: (AgentUsageView) -> Unit,
    onWithCacheReadsChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpenComputer: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val today = (loadState as? AgentUsageLoadState.Ready)?.response?.to ?: fallbackToday
    val summary =
        remember(agentId, loadState, view, withCacheReads) {
            (loadState as? AgentUsageLoadState.Ready)?.let { ready ->
                summarizeAgentUsage(ready.response, agentId, view, withCacheReads)
            }
        }
    val skeletonVisible = rememberRaftSkeletonDelayPassed(loading = loadState is AgentUsageLoadState.Loading)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.layerCanvas)
                .raftSafeAreaHorizontalPadding()
                .verticalScroll(rememberScrollState())
                .testTag(AgentUsageTestTags.Tab),
    ) {
        // Controls bar: Period Select + Cache reads switch (`px-5 pt-4 pb-1`).
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentUsagePeriodSelect(
                view = view,
                today = today,
                onChange = onViewChange,
            )
            Spacer(Modifier.weight(1f))
            val cacheReadsLabel = stringResource(Res.string.agent_usage_cache_reads)
            Row(
                modifier =
                    Modifier
                        .toggleable(
                            value = withCacheReads,
                            role = Role.Switch,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onValueChange = onWithCacheReadsChange,
                        )
                        .testTag(AgentUsageTestTags.CacheReadsSwitch),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftSwitch(
                    checked = withCacheReads,
                    onCheckedChange = null,
                    label = cacheReadsLabel,
                )
                RaftText(
                    text = cacheReadsLabel,
                    style = typography.caption,
                    color = colors.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            loadState is AgentUsageLoadState.Error -> {
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    RaftBanner(
                        description = stringResource(Res.string.agent_usage_load_failed),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm,
                        modifier = Modifier.testTag(AgentUsageTestTags.ErrorBanner),
                        action = {
                            RaftButton(
                                text = stringResource(Res.string.agent_usage_retry),
                                onClick = onRetry,
                                variant = RaftButtonVariant.Default,
                                size = RaftButtonSize.Md,
                                leadingIcon = { RaftContentLucideIcon(RaftLucideIconName.RefreshCw, size = 16.dp) },
                                modifier = Modifier.testTag(AgentUsageTestTags.RetryButton),
                            )
                        },
                    )
                }
            }

            summary == null -> {
                if (skeletonVisible) {
                    val animateSkeleton = !rememberRaftReducedMotion()
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                                .testTag(AgentUsageTestTags.Skeleton),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RaftSkeleton(modifier = Modifier.width(96.dp), variant = RaftSkeletonVariant.Line, animated = animateSkeleton)
                        RaftSkeleton(modifier = Modifier.fillMaxWidth().height(132.dp), animated = animateSkeleton)
                    }
                }
            }

            summary is AgentUsageSummary.Unsupported -> {
                AgentUsageUnsupportedEmptyState(
                    reason = summary.reason,
                    onOpenComputer = onOpenComputer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            summary is AgentUsageSummary.Empty -> {
                AgentUsageEmptyState(
                    title = agentUsageEmptyTitle(view.range.days),
                    action = null,
                    modifier = Modifier.fillMaxWidth().testTag(AgentUsageTestTags.Empty),
                )
            }

            summary is AgentUsageSummary.Ready -> {
                val pickedPast = view.day != null && view.day != today
                val pickedDayLabel = view.day?.let { agentUsageFormattedDayName(it) }

                @Composable
                fun sectionHeading(baseLabel: String): String =
                    if (pickedDayLabel != null) {
                        stringResource(Res.string.agent_usage_heading_on_day, pickedDayLabel, baseLabel)
                    } else {
                        baseLabel
                    }

                AgentUsageSectionBlock(
                    title = sectionHeading(stringResource(Res.string.agent_usage_tokens)),
                    separator = false,
                    aside = {
                        AgentUsageFreshness(
                            figures = summary.figures,
                            pickedPast = pickedPast,
                            today = today,
                            timeContext = timeContext,
                        )
                    },
                ) {
                    AgentUsageHeadline(
                        figures = summary.figures,
                        withCacheReads = withCacheReads,
                        picked = view.day != null,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    MembersAgentUsageDayBars(
                        days = summary.figures.days,
                        withCacheReads = withCacheReads,
                        selectedDay = view.day,
                        onSelectDay = { picked -> onViewChange(AgentUsageView(range = view.range, day = picked)) },
                    )
                }

                AgentUsageSectionBlock(
                    title = sectionHeading(stringResource(Res.string.agent_usage_token_types)),
                    separator = true,
                ) {
                    AgentUsageTokenTypesSection(figures = summary.figures)
                }

                AgentUsageSectionBlock(
                    title = sectionHeading(stringResource(Res.string.agent_usage_context)),
                    separator = true,
                ) {
                    AgentUsageContextSection(
                        summary = summary,
                        pickedPast = pickedPast,
                    )
                }

                AgentUsageSectionBlock(
                    title = sectionHeading(stringResource(Res.string.agent_usage_models)),
                    separator = true,
                ) {
                    AgentUsageModelsSection(figures = summary.figures)
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AgentUsageSectionBlock(
    title: String,
    separator: Boolean,
    aside: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (separator) RaftSeparator()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.weight(1f).padding(end = 8.dp)) {
                    RaftSectionEyebrow(text = title)
                }
                aside?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun AgentUsagePeriodSelect(
    view: AgentUsageView,
    today: String,
    onChange: (AgentUsageView) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    val periodLabel = stringResource(Res.string.agent_usage_period_label)
    val todayLabel = stringResource(Res.string.agent_usage_period_today)
    val days7Label = agentUsagePeriodDaysLabel(AgentUsageRange.Days7.days)
    val days14Label = agentUsagePeriodDaysLabel(AgentUsageRange.Days14.days)
    val days30Label = agentUsagePeriodDaysLabel(AgentUsageRange.Days30.days)

    val selectedId =
        when {
            view.day == today -> "today"
            view.day != null -> "pick"
            else -> view.range.days.toString()
        }
    val shownValue =
        when {
            view.day == today -> todayLabel
            view.day != null -> agentUsageFormattedDayName(view.day)
            view.range == AgentUsageRange.Days7 -> days7Label
            view.range == AgentUsageRange.Days14 -> days14Label
            else -> days30Label
        }

    Box {
        RaftSelectTrigger(
            value = shownValue,
            onClick = { expanded = true },
            label = periodLabel,
            expanded = expanded,
            onCollapse = { expanded = false },
            modifier =
                Modifier
                    .width(136.dp)
                    .onSizeChanged { triggerWidthPx = it.width }
                    .testTag(AgentUsageTestTags.PeriodTrigger),
        )
        RaftDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            width = maxOf(160.dp, with(density) { triggerWidthPx.toDp() }),
            offset = 4.dp,
        ) {
            listOf(
                Triple("today", todayLabel) { onChange(AgentUsageView(range = view.range, day = today)) },
                Triple("7", days7Label) { onChange(AgentUsageView(range = AgentUsageRange.Days7, day = null)) },
                Triple("14", days14Label) { onChange(AgentUsageView(range = AgentUsageRange.Days14, day = null)) },
                Triple("30", days30Label) { onChange(AgentUsageView(range = AgentUsageRange.Days30, day = null)) },
            ).forEach { (id, label, action) ->
                RaftMenuItem(
                    text = label,
                    checked = selectedId == id,
                    onClick = {
                        expanded = false
                        action()
                    },
                    modifier = Modifier.testTag("${AgentUsageTestTags.PeriodMenuOptionPrefix}$id"),
                )
            }
        }
    }
}

@Composable
private fun AgentUsageFreshness(
    figures: AgentUsageFigures,
    pickedPast: Boolean,
    today: String,
    timeContext: RaftTimeContext,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val firstDay = figures.days.firstOrNull()?.day ?: today
    val showSince = figures.since != null && firstDay < figures.since
    if (pickedPast && !showSince) return
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.testTag(AgentUsageTestTags.Freshness),
    ) {
        if (!pickedPast && figures.asOf.isNotBlank()) {
            val formattedTime = settingsCompactDateLabel(figures.asOf, timeContext)
            RaftText(
                text = stringResource(Res.string.agent_usage_as_of, formattedTime),
                style = typography.monoMeta,
                color = colors.foregroundHint,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
        if (showSince && figures.since != null) {
            RaftText(
                text = stringResource(Res.string.agent_usage_since, agentUsageFormattedDayName(figures.since)),
                style = typography.monoMeta,
                color = colors.foregroundHint,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.testTag(AgentUsageTestTags.Since),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentUsageHeadline(
    figures: AgentUsageFigures,
    withCacheReads: Boolean,
    picked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val dash = stringResource(Res.string.agent_usage_no_value)
    val headline = chartAgentUsageTokens(figures.sums, withCacheReads)
    val change = if (figures.noData) null else relativeChange(figures.compared, figures.base)

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RaftText(
            text = if (figures.noData || headline == null) dash else formatAgentUsageTokens(headline),
            style = typography.title.copy(fontFamily = typography.monoMeta.fontFamily, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = colors.foreground,
            maxLines = 1,
            modifier = Modifier.alignByBaseline().testTag(AgentUsageTestTags.Headline),
        )
        Row(
            modifier = Modifier.alignByBaseline().semantics(mergeDescendants = true) {}.testTag(AgentUsageTestTags.Change),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (change == null) {
                RaftText(
                    text = dash,
                    style = typography.caption,
                    color = colors.foregroundMuted,
                    maxLines = 1,
                )
            } else {
                val icon = if (change >= 0.0) RaftLucideIconName.ArrowUpRight else RaftLucideIconName.ArrowDownRight
                RaftLucideIcon(
                    icon,
                    modifier = Modifier.size(14.dp).clearAndSetSemantics {},
                    color = raftArgbHex(colors.foregroundMuted),
                )
                val changeText = formatAgentUsageChange(change)
                val label =
                    if (picked && figures.baseDay != null) {
                        stringResource(Res.string.agent_usage_vs_day, changeText, agentUsageFormattedDayName(figures.baseDay))
                    } else {
                        stringResource(Res.string.agent_usage_vs_prior, changeText)
                    }
                RaftText(
                    text = label,
                    style = typography.caption,
                    color = colors.foregroundMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AgentUsageFactRow(
    term: @Composable () -> Unit,
    testTag: String,
    value: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(116.dp)) { term() }
        Column(Modifier.weight(1f)) { value() }
    }
}

@Composable
private fun AgentUsageTokenTypesSection(figures: AgentUsageFigures) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val dash = stringResource(Res.string.agent_usage_no_value)
    val sums = figures.sums
    val total = totalAgentUsageTokens(sums)

    @Composable
    fun KindTerm(kind: AgentUsageSeriesKey) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentUsageSwatch(kind)
            RaftText(
                text = kind.localizedLabel(),
                style = typography.footnote.copy(fontWeight = FontWeight.Medium),
                color = colors.foregroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    fun AmountWithShare(value: Long?) {
        if (value == null || figures.noData) {
            RaftText(
                text = dash,
                style = typography.callout.copy(fontFamily = typography.monoMeta.fontFamily),
                color = colors.foreground,
                maxLines = 1,
            )
        } else {
            val share = if (total != null && total > 0L) value.toDouble() / total.toDouble() else 0.0
            val amountText = formatAgentUsageTokens(value)
            val fullText =
                stringResource(
                    Res.string.agent_usage_amount_with_share,
                    amountText,
                    formatAgentUsagePercent(share),
                )
            val styled =
                buildAnnotatedString {
                    val amountStart = fullText.indexOf(amountText)
                    if (amountStart >= 0) {
                        val amountEnd = amountStart + amountText.length
                        if (amountStart > 0) {
                            withStyle(SpanStyle(color = colors.foregroundHint)) { append(fullText.substring(0, amountStart)) }
                        }
                        append(amountText)
                        if (amountEnd < fullText.length) {
                            withStyle(SpanStyle(color = colors.foregroundHint)) { append(fullText.substring(amountEnd)) }
                        }
                    } else {
                        append(fullText)
                    }
                }
            RaftText(
                text = styled,
                style = typography.callout.copy(fontFamily = typography.monoMeta.fontFamily, fontFeatureSettings = "tnum"),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag(AgentUsageTestTags.TokenTypes),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentUsageFactRow(term = { KindTerm(AgentUsageSeriesKey.InputNew) }, testTag = AgentUsageTestTags.NewInput) {
            AmountWithShare(sums.inputNew)
        }
        if (sums.cacheWrite != null) {
            AgentUsageFactRow(term = { KindTerm(AgentUsageSeriesKey.CacheWrite) }, testTag = AgentUsageTestTags.CacheWrite) {
                AmountWithShare(sums.cacheWrite)
            }
        }
        AgentUsageFactRow(term = { KindTerm(AgentUsageSeriesKey.CacheRead) }, testTag = AgentUsageTestTags.CacheRead) {
            AmountWithShare(sums.cacheRead)
        }
        AgentUsageFactRow(term = { KindTerm(AgentUsageSeriesKey.Output) }, testTag = AgentUsageTestTags.Output) {
            AmountWithShare(sums.output)
            if (sums.reasoning != null && !figures.noData) {
                RaftText(
                    text = stringResource(Res.string.agent_usage_reasoning, formatAgentUsageTokens(sums.reasoning)),
                    style = typography.caption,
                    color = colors.foregroundHint,
                    modifier = Modifier.padding(top = 2.dp).testTag(AgentUsageTestTags.Reasoning),
                )
            }
        }
        AgentUsageFactRow(
            term = {
                RaftText(
                    text = stringResource(Res.string.agent_usage_total),
                    style = typography.footnote.copy(fontWeight = FontWeight.Medium),
                    color = colors.foregroundHint,
                    maxLines = 1,
                )
            },
            testTag = AgentUsageTestTags.Total,
        ) {
            RaftText(
                text = if (figures.noData || total == null) dash else formatAgentUsageTokens(total),
                style =
                    typography.callout.copy(
                        fontFamily = typography.monoMeta.fontFamily,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                color = colors.foreground,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AgentUsageContextSection(
    summary: AgentUsageSummary.Ready,
    pickedPast: Boolean,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val dash = stringResource(Res.string.agent_usage_no_value)
    val sums = summary.figures.sums
    val share = if (summary.figures.noData) null else cacheShareOfInput(sums)
    val perTurn = if (summary.figures.noData) null else tokensPerTurn(sums)
    val context = if (pickedPast) null else summary.context

    @Composable
    fun PlainTerm(text: String) {
        RaftText(
            text = text,
            style = typography.footnote.copy(fontWeight = FontWeight.Medium),
            color = colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentUsageFactRow(
            term = { PlainTerm(stringResource(Res.string.agent_usage_cache_reads)) },
            testTag = AgentUsageTestTags.CacheShare,
        ) {
            RaftText(
                text =
                    if (share == null) {
                        dash
                    } else {
                        stringResource(Res.string.agent_usage_cache_share, formatAgentUsagePercent(share))
                    },
                style = typography.callout,
                color = colors.foreground,
                maxLines = 1,
            )
        }
        AgentUsageFactRow(
            term = { PlainTerm(stringResource(Res.string.agent_usage_per_turn)) },
            testTag = AgentUsageTestTags.PerTurn,
        ) {
            RaftText(
                text =
                    if (perTurn == null) {
                        dash
                    } else {
                        agentUsagePerTurnValueLabel(formatAgentUsageTokens(perTurn), sums.turns)
                    },
                style = typography.callout,
                color = colors.foreground,
                maxLines = 1,
            )
        }
        if (context == null) {
            AgentUsageFactRow(
                term = { PlainTerm(stringResource(Res.string.agent_usage_context_window)) },
                testTag = AgentUsageTestTags.ContextWindow,
            ) {
                RaftText(
                    text = dash,
                    style = typography.callout.copy(fontFamily = typography.monoMeta.fontFamily),
                    color = colors.foreground,
                    maxLines = 1,
                )
            }
        }
    }
    if (context != null) {
        val percent = formatAgentUsagePercent(context.used.toDouble() / context.window.toDouble(), maxFractionDigits = 0)
        RaftMeter(
            used = context.used.toFloat(),
            max = context.window.toFloat(),
            valueText = stringResource(Res.string.agent_usage_context_used, percent),
            size = RaftMeterSize.Md,
            label = stringResource(Res.string.agent_usage_context_window),
            modifier = Modifier.padding(top = 12.dp).testTag(AgentUsageTestTags.ContextMeter),
        )
    }
}

@Composable
private fun AgentUsageModelsSection(figures: AgentUsageFigures) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val dash = stringResource(Res.string.agent_usage_no_value)
    if (figures.models.isEmpty() || figures.noData) {
        RaftText(
            text = dash,
            style = typography.callout.copy(fontFamily = typography.monoMeta.fontFamily),
            color = colors.foregroundHint,
        )
        return
    }
    val modelsTotal = figures.models.sumOf { it.tokens ?: 0L }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AgentUsageTestTags.Models),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        figures.models.forEach { model ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    RaftText(
                        text =
                            if (model.model.isNotBlank()) {
                                getModelLabel(runtimeId = model.runtime.orEmpty(), modelId = model.model)
                            } else {
                                stringResource(Res.string.agent_usage_unknown_model)
                            },
                        style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RaftText(
                        text = model.runtime?.let { observeAgentRuntimeDisplayName(it) } ?: dash,
                        style = typography.monoMeta,
                        color = colors.foregroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RaftText(
                    text = model.tokens?.let(::formatAgentUsageTokens) ?: dash,
                    style = typography.monoMeta.copy(fontFeatureSettings = "tnum"),
                    color = colors.foregroundMuted,
                    maxLines = 1,
                )
                RaftText(
                    text =
                        if (model.tokens == null) {
                            dash
                        } else {
                            formatAgentUsagePercent(if (modelsTotal > 0L) model.tokens.toDouble() / modelsTotal.toDouble() else 0.0, maxFractionDigits = 0)
                        },
                    style = typography.monoMeta.copy(fontFeatureSettings = "tnum"),
                    color = colors.foreground,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun AgentUsageUnsupportedEmptyState(
    reason: UsageUnreportedReason,
    onOpenComputer: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val title =
        when (reason) {
            UsageUnreportedReason.ComputerTooOld,
            UsageUnreportedReason.Unknown -> stringResource(Res.string.agent_usage_update_computer)
            UsageUnreportedReason.NotRun -> stringResource(Res.string.agent_usage_not_run)
            UsageUnreportedReason.NoComputer -> stringResource(Res.string.agent_usage_no_computer)
            UsageUnreportedReason.RuntimeNoUsage -> stringResource(Res.string.agent_usage_runtime_no_usage)
        }
    val showComputerButton = reason.offersComputer && onOpenComputer != null
    AgentUsageEmptyState(
        title = title,
        action =
            if (showComputerButton) {
                {
                    RaftButton(
                        text = stringResource(Res.string.agent_usage_open_computer),
                        onClick = onOpenComputer!!,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                        modifier = Modifier.testTag(AgentUsageTestTags.OpenComputerButton),
                    )
                }
            } else {
                null
            },
        modifier = modifier.testTag(reason.testTag),
    )
}

/** The usage tab's [RaftEmptyState] (web `EmptyState`). */
@Composable
internal fun AgentUsageEmptyState(
    title: String,
    action: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    RaftEmptyState(
        title = title,
        modifier = modifier,
        icon = { RaftContentLucideIcon(RaftLucideIconName.ChartColumn, size = RaftEmptyStateDefaults.IconSize) },
        actions = action,
    )
}

internal enum class AgentUsagePluralForm { One, Few, Other }

/** Russian uses Slavic one/few/other rules; all other 7 UI locales use singular/plural (`1` -> One). */
internal fun agentUsagePluralForm(
    count: Long,
    isRussian: Boolean = RaftFormatLocale.current() == RaftFormatLocale.Russian,
): AgentUsagePluralForm {
    val n = abs(count)
    if (!isRussian) return if (n == 1L) AgentUsagePluralForm.One else AgentUsagePluralForm.Other
    val mod10 = n % 10L
    val mod100 = n % 100L
    return when {
        mod10 == 1L && mod100 != 11L -> AgentUsagePluralForm.One
        mod10 in 2L..4L && mod100 !in 12L..14L -> AgentUsagePluralForm.Few
        else -> AgentUsagePluralForm.Other
    }
}

@Composable
private fun agentUsagePeriodDaysLabel(days: Int): String =
    when (agentUsagePluralForm(days.toLong())) {
        AgentUsagePluralForm.One -> stringResource(Res.string.agent_usage_period_days_one, days)
        AgentUsagePluralForm.Few -> stringResource(Res.string.agent_usage_period_days_few, days)
        AgentUsagePluralForm.Other -> stringResource(Res.string.agent_usage_period_days_other, days)
    }

@Composable
private fun agentUsagePerTurnValueLabel(tokens: String, turns: Long): String =
    when (agentUsagePluralForm(turns)) {
        AgentUsagePluralForm.One -> stringResource(Res.string.agent_usage_per_turn_value_one, tokens, turns)
        AgentUsagePluralForm.Few -> stringResource(Res.string.agent_usage_per_turn_value_few, tokens, turns)
        AgentUsagePluralForm.Other -> stringResource(Res.string.agent_usage_per_turn_value_other, tokens, turns)
    }

@Composable
private fun agentUsageEmptyTitle(days: Int): String =
    when (agentUsagePluralForm(days.toLong())) {
        AgentUsagePluralForm.One -> stringResource(Res.string.agent_usage_empty_one)
        AgentUsagePluralForm.Few -> stringResource(Res.string.agent_usage_empty_few, days)
        AgentUsagePluralForm.Other -> stringResource(Res.string.agent_usage_empty_other, days)
    }
