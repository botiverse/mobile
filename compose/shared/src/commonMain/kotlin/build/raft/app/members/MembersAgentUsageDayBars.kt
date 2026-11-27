package build.raft.app.members

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.data.AgentUsageDay
import build.raft.app.data.AgentUsageSums
import build.raft.app.data.agentUsageParseUtcEpochDay
import build.raft.app.data.agentUsageWeekdayIndex
import build.raft.app.data.formatAgentUsageTokens
import build.raft.app.localizedLabel
import build.raft.app.members.localizedLabel
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.resources.*
import build.raft.app.thread.localizedLabel
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.math.abs

/**
 * The Usage tab's token series: label and chart hue matching web `agentUsageSeries.ts`
 * (`--palette-lavender-600`, `--palette-lavender-300`, `--palette-cyan-500`, `--palette-stone-400`).
 */
internal enum class AgentUsageSeriesKey(val color: Color) {
    InputNew(RaftUiPalette.paletteLavender600),
    CacheWrite(RaftUiPalette.paletteLavender300),
    Output(RaftUiPalette.paletteCyan500),
    CacheRead(RaftUiPalette.paletteStone400),
}

@Composable
internal fun AgentUsageSeriesKey.localizedLabel(): String =
    when (this) {
        AgentUsageSeriesKey.InputNew -> stringResource(Res.string.agent_usage_kind_new_input)
        AgentUsageSeriesKey.CacheWrite -> stringResource(Res.string.agent_usage_kind_cache_write)
        AgentUsageSeriesKey.Output -> stringResource(Res.string.agent_usage_kind_output)
        AgentUsageSeriesKey.CacheRead -> stringResource(Res.string.agent_usage_kind_cache_read)
    }

internal fun AgentUsageSums.seriesValue(kind: AgentUsageSeriesKey): Long =
    when (kind) {
        AgentUsageSeriesKey.InputNew -> inputNew ?: 0L
        AgentUsageSeriesKey.CacheWrite -> cacheWrite ?: 0L
        AgentUsageSeriesKey.Output -> output ?: 0L
        AgentUsageSeriesKey.CacheRead -> cacheRead ?: 0L
    }

/** Web `UsageSwatch`: 10dp rounded 3dp swatch with 1.5dp sticker-ink inset border. */
@Composable
internal fun AgentUsageSwatch(kind: AgentUsageSeriesKey, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier =
            modifier
                .size(10.dp)
                .background(kind.color, shape)
                .border(1.5.dp, RaftUiPalette.stickerInk, shape)
                .clearAndSetSemantics {}
    )
}

private val ChartHeight = 132.dp
private val ChartTop = 18.dp
private val ChartBottom = 30.dp
private val ChartPlot = ChartHeight - ChartTop - ChartBottom
private val ChartMinSlot = 18.dp
private val ChartBarMaxWidth = 28.dp
private val ChartSegmentGap = 2.dp
private val ChartMinSegment = 3.dp
private val ChartFadePad = 28.dp
private const val ChartReadoutSeparator = " · "

/**
 * Daily stacked bar chart mirroring web `AgentUsageDayBars.tsx`:
 * one stacked bar per UTC day, each kind a sticker segment (3dp radius, 1.5dp sticker-ink edge,
 * 2dp gap), tappable columns acting as a radio group (tap again to return to the full range),
 * horizontally scrollable when narrower than 18dp/day (opening at the newest day).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MembersAgentUsageDayBars(
    days: List<AgentUsageDay>,
    withCacheReads: Boolean,
    selectedDay: String?,
    onSelectDay: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val dash = stringResource(Res.string.agent_usage_no_value)
    val todayLabel = stringResource(Res.string.agent_usage_today)
    val partialMark = stringResource(Res.string.agent_usage_partial_mark)
    val chartLabel = stringResource(Res.string.agent_usage_chart_label)

    val order =
        if (withCacheReads) {
            listOf(
                AgentUsageSeriesKey.CacheRead,
                AgentUsageSeriesKey.InputNew,
                AgentUsageSeriesKey.CacheWrite,
                AgentUsageSeriesKey.Output,
            )
        } else {
            listOf(
                AgentUsageSeriesKey.InputNew,
                AgentUsageSeriesKey.CacheWrite,
                AgentUsageSeriesKey.Output,
            )
        }
    val kinds = remember(days, withCacheReads) {
        order.filter { kind -> days.any { day -> day.sums.seriesValue(kind) > 0L } }
    }
    val kindLabels = kinds.associateWith { it.localizedLabel() }
    val dayNames = days.associate { day -> day.day to agentUsageFormattedDayName(day.day) }

    val n = days.size
    val dense = n > 7
    val sums = remember(days, kinds) {
        days.map { day -> kinds.sumOf { kind -> day.sums.seriesValue(kind) } }
    }
    val maxSum = remember(sums) { (sums.maxOrNull() ?: 0L).coerceAtLeast(1L) }
    val tallestIndex = remember(sums) {
        val peak = sums.maxOrNull() ?: 0L
        sums.indexOfFirst { it == peak }.coerceAtLeast(0)
    }
    val pickedIndex = remember(days, selectedDay) {
        selectedDay?.let { target -> days.indexOfFirst { it.day == target }.takeIf { it >= 0 } }
    }
    val lastIndex = (n - 1).coerceAtLeast(0)
    val hasPartial = remember(days) { days.any { it.partial } }

    @Composable
    fun formatKindFigures(amountOf: (AgentUsageSeriesKey) -> Long): String =
        kinds.map { kind ->
            stringResource(
                Res.string.agent_usage_readout_kind,
                kindLabels.getValue(kind),
                formatAgentUsageTokens(amountOf(kind)),
            )
        }.joinToString(ChartReadoutSeparator)

    val shownDay = pickedIndex?.let { days[it] }
    val readoutText =
        if (shownDay == null) {
            formatKindFigures { kind -> days.sumOf { day -> day.sums.seriesValue(kind) } }
        } else if (!shownDay.noData && kinds.isEmpty()) {
            dayNames.getValue(shownDay.day)
        } else {
            val figuresText =
                if (shownDay.noData) {
                    dash
                } else {
                    formatKindFigures { kind -> shownDay.sums.seriesValue(kind) }
                }
            stringResource(Res.string.agent_usage_readout_day, dayNames.getValue(shownDay.day), figuresText)
        }

    Column(
        modifier = modifier.fillMaxWidth().testTag(AgentUsageTestTags.Chart),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RaftText(
            text = readoutText,
            style = typography.monoMeta,
            color = colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 18.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(AgentUsageTestTags.Readout),
        )

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val viewportWidth = if (maxWidth > 0.dp) maxWidth else 360.dp
            val slotWidth = maxOf(ChartMinSlot, viewportWidth / n.coerceAtLeast(1))
            val contentWidth = slotWidth * n
            val scrolls = contentWidth > viewportWidth + 0.5.dp
            val barWidth = minOf(ChartBarMaxWidth, slotWidth * if (dense) 0.62f else 0.56f)
            val scrollState = rememberScrollState()
            val density = LocalDensity.current

            // Open at the newest day whenever range/width/maxValue settles and nothing is picked.
            LaunchedEffect(n, viewportWidth, scrolls, scrollState.maxValue) {
                if (scrolls && pickedIndex == null && scrollState.maxValue != Int.MAX_VALUE) {
                    scrollState.scrollTo(scrollState.maxValue)
                }
            }
            // Keep the picked day comfortably in view when scrolling horizontally.
            LaunchedEffect(pickedIndex, slotWidth, scrolls) {
                val index = pickedIndex ?: return@LaunchedEffect
                if (!scrolls) return@LaunchedEffect
                val slotPx = with(density) { slotWidth.toPx() }
                val padPx = with(density) { ChartFadePad.toPx() }
                val viewportPx = with(density) { viewportWidth.toPx() }
                val left = index * slotPx
                val right = left + slotPx
                if (left < scrollState.value + padPx) {
                    scrollState.scrollTo((left - padPx).coerceAtLeast(0f).toInt())
                } else if (right > scrollState.value + viewportPx - padPx) {
                    scrollState.scrollTo((right - viewportPx + padPx).coerceAtLeast(0f).toInt())
                }
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (scrolls) {
                                Modifier.horizontalScroll(scrollState).testTag(AgentUsageTestTags.ChartScroll)
                            } else {
                                Modifier
                            }
                        )
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(contentWidth)
                            .height(ChartHeight)
                            .semantics {
                                contentDescription = chartLabel
                                selectableGroup()
                            }
                ) {
                    // Hairline baseline spanning the entire chart width.
                    val baselineColor = colors.lineHairline
                    Canvas(Modifier.fillMaxSize()) {
                        val baselineY = (ChartTop + ChartPlot).toPx() + 0.5f
                        drawLine(
                            color = baselineColor,
                            start = Offset(0f, baselineY),
                            end = Offset(size.width, baselineY),
                            strokeWidth = 1f,
                        )
                    }

                    Row(Modifier.fillMaxSize()) {
                        days.forEachIndexed { index, day ->
                            val isSelected = pickedIndex == index
                            val dim = pickedIndex != null && !isSelected
                            val isToday = index == lastIndex
                            val showSum =
                                !day.noData && (!dense || isSelected || (pickedIndex == null && index == tallestIndex))
                            val sumLabel = if (day.noData) dash else formatAgentUsageTokens(sums[index])
                            val accessibleName =
                                if (day.partial) {
                                    stringResource(Res.string.agent_usage_bar_label_partial, dayNames.getValue(day.day), sumLabel)
                                } else {
                                    stringResource(Res.string.agent_usage_bar_label, dayNames.getValue(day.day), sumLabel)
                                }
                            val primaryXLabel =
                                if (n > 14) {
                                    val showWeekly =
                                        isSelected || ((lastIndex - index) % 7 == 0 && (pickedIndex == null || abs(pickedIndex - index) > 2))
                                    if (!showWeekly) {
                                        null
                                    } else {
                                        val base = if (isToday && !isSelected) todayLabel else agentUsageMonthDay(day.day)
                                        if (day.partial) "$base$partialMark" else base
                                    }
                                } else {
                                    val base =
                                        when {
                                            dense -> agentUsageWeekdayNarrow(day.day)
                                            isToday -> todayLabel
                                            else -> agentUsageWeekdayShort(day.day)
                                        }
                                    if (day.partial && !dense) "$base$partialMark" else base
                                }
                            val secondaryXLabel =
                                if (n > 14) {
                                    null
                                } else if (dense) {
                                    agentUsageDayOfMonth(day.day)
                                } else {
                                    agentUsageMonthDay(day.day)
                                }

                            AgentUsageDayColumn(
                                day = day,
                                index = index,
                                lastIndex = lastIndex,
                                kinds = kinds,
                                maxSum = maxSum,
                                slotWidth = slotWidth,
                                barWidth = barWidth,
                                isSelected = isSelected,
                                dim = dim,
                                showSum = showSum,
                                sumLabel = sumLabel,
                                primaryXLabel = primaryXLabel,
                                secondaryXLabel = secondaryXLabel,
                                dense = n > 14,
                                accessibleName = accessibleName,
                                onClick = { onSelectDay(if (isSelected) null else day.day) },
                            )
                        }
                    }
                }
            }
        }

        // Legend: newest-stacked-on-top first (`kinds.reversed()`), matching web `kinds.slice().reverse()`.
        if (kinds.isNotEmpty() || hasPartial) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                kinds.asReversed().forEach { kind ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AgentUsageSwatch(kind)
                        RaftText(
                            text = kindLabels.getValue(kind),
                            style = typography.caption,
                            color = colors.foreground,
                            maxLines = 1,
                        )
                    }
                }
                if (hasPartial) {
                    RaftText(
                        text = stringResource(Res.string.agent_usage_partial_legend),
                        style = typography.caption,
                        color = colors.foregroundHint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentUsageDayColumn(
    day: AgentUsageDay,
    index: Int,
    lastIndex: Int,
    kinds: List<AgentUsageSeriesKey>,
    maxSum: Long,
    slotWidth: Dp,
    barWidth: Dp,
    isSelected: Boolean,
    dim: Boolean,
    showSum: Boolean,
    sumLabel: String,
    primaryXLabel: String?,
    secondaryXLabel: String?,
    dense: Boolean,
    accessibleName: String,
    onClick: () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val edgeAlign =
        when {
            index == 0 -> Alignment.Start
            index == lastIndex -> Alignment.End
            else -> Alignment.CenterHorizontally
        }
    val edgeBottomAlign =
        when {
            index == 0 -> Alignment.BottomStart
            index == lastIndex -> Alignment.BottomEnd
            else -> Alignment.BottomCenter
        }
    val totalBarHeightDp =
        if (!day.noData && kinds.isNotEmpty() && maxSum > 0L) {
            var yDp = 0f
            val plotDp = ChartPlot.value
            val minSegDp = ChartMinSegment.value
            val maxPlotBoxDp = (ChartTop + ChartPlot).value
            for (kind in kinds) {
                val amount = day.sums.seriesValue(kind)
                if (amount <= 0L) continue
                val rawHeight = (plotDp * amount.toFloat() / maxSum.toFloat()).coerceAtLeast(minSegDp)
                yDp = (yDp + rawHeight).coerceAtMost(maxPlotBoxDp)
            }
            yDp.dp
        } else {
            0.dp
        }

    Column(
        modifier =
            Modifier
                .width(slotWidth)
                .fillMaxHeight()
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibleName
                    selected = isSelected
                }
                .raftClickable(
                    onClick = onClick,
                    role = Role.RadioButton,
                    shape = RoundedCornerShape(6.dp),
                    tier = RaftHoverTier.Subtle,
                    selected = isSelected,
                )
                .testTag("${AgentUsageTestTags.BarPrefix}${day.day}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ChartTop + ChartPlot),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (isSelected) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(start = 1.dp, end = 1.dp, top = 2.dp)
                            .background(colors.fillMuted, RoundedCornerShape(6.dp))
                )
            }
            if (!day.noData && kinds.isNotEmpty()) {
                val inkColor = RaftUiPalette.stickerInk
                Canvas(
                    modifier =
                        Modifier
                            .width(barWidth)
                            .height(ChartTop + ChartPlot)
                            .alpha(if (dim) 0.32f else 1f)
                ) {
                    val plotPx = ChartPlot.toPx()
                    val gapPx = ChartSegmentGap.toPx()
                    val minSegPx = ChartMinSegment.toPx()
                    val strokePx = 1.5.dp.toPx()
                    val radiusPx = 3.dp.toPx()
                    var y = size.height
                    for (kind in kinds) {
                        val amount = day.sums.seriesValue(kind)
                        if (amount <= 0L) continue
                        val rawHeight = (plotPx * amount.toFloat() / maxSum.toFloat()).coerceAtLeast(minSegPx)
                        val clampedTop = (y - rawHeight).coerceAtLeast(0f)
                        val segHeight = (y - clampedTop - gapPx).coerceAtLeast(1f)
                        drawRoundRect(
                            color = kind.color,
                            topLeft = Offset(0f, clampedTop),
                            size = Size(size.width, segHeight),
                            cornerRadius = CornerRadius(radiusPx, radiusPx),
                        )
                        if (size.width > strokePx * 2f && segHeight > strokePx * 2f) {
                            val half = strokePx / 2f
                            drawRoundRect(
                                color = inkColor,
                                topLeft = Offset(half, clampedTop + half),
                                size = Size(size.width - strokePx, segHeight - strokePx),
                                cornerRadius = CornerRadius((radiusPx - half).coerceAtLeast(0f)),
                                style = Stroke(width = strokePx),
                            )
                        }
                        y = clampedTop
                    }
                }
            }
            if (showSum) {
                val labelBottom = (totalBarHeightDp + 4.dp).coerceAtMost(ChartTop + ChartPlot - 14.dp)
                RaftText(
                    text = sumLabel,
                    style =
                        typography.monoMeta.copy(
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    color = if (dim) colors.foregroundHint else colors.foreground,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .align(edgeBottomAlign)
                            .padding(bottom = labelBottom)
                            .wrapContentWidth(align = edgeAlign, unbounded = true)
                            .clearAndSetSemantics {},
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().height(ChartBottom).padding(top = 2.dp).clearAndSetSemantics {},
            horizontalAlignment = edgeAlign,
            verticalArrangement = Arrangement.Top,
        ) {
            if (primaryXLabel != null) {
                RaftText(
                    text = primaryXLabel,
                    style =
                        if (dense) {
                            typography.monoMeta.copy(
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        } else {
                            typography.caption.copy(
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    color =
                        when {
                            isSelected -> colors.foreground
                            dim -> colors.foregroundHint
                            else -> colors.foregroundMuted
                        },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.wrapContentWidth(align = edgeAlign, unbounded = true),
                )
            }
            if (secondaryXLabel != null) {
                RaftText(
                    text = secondaryXLabel,
                    style = typography.monoMeta.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    color = colors.foregroundHint,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.wrapContentWidth(align = edgeAlign, unbounded = true),
                )
            }
        }
    }
}

@Composable
internal fun agentUsageWeekdayShort(day: String): String =
    when (agentUsageWeekdayIndex(day)) {
        0 -> stringResource(Res.string.agent_usage_weekday_short_sun)
        1 -> stringResource(Res.string.agent_usage_weekday_short_mon)
        2 -> stringResource(Res.string.agent_usage_weekday_short_tue)
        3 -> stringResource(Res.string.agent_usage_weekday_short_wed)
        4 -> stringResource(Res.string.agent_usage_weekday_short_thu)
        5 -> stringResource(Res.string.agent_usage_weekday_short_fri)
        6 -> stringResource(Res.string.agent_usage_weekday_short_sat)
        else -> ""
    }

@Composable
internal fun agentUsageWeekdayNarrow(day: String): String =
    when (agentUsageWeekdayIndex(day)) {
        0 -> stringResource(Res.string.agent_usage_weekday_narrow_sun)
        1 -> stringResource(Res.string.agent_usage_weekday_narrow_mon)
        2 -> stringResource(Res.string.agent_usage_weekday_narrow_tue)
        3 -> stringResource(Res.string.agent_usage_weekday_narrow_wed)
        4 -> stringResource(Res.string.agent_usage_weekday_narrow_thu)
        5 -> stringResource(Res.string.agent_usage_weekday_narrow_fri)
        6 -> stringResource(Res.string.agent_usage_weekday_narrow_sat)
        else -> ""
    }

internal fun agentUsageMonthDay(day: String): String {
    val epochDay = agentUsageParseUtcEpochDay(day) ?: return day
    val civil = civilDateFromEpochDay(epochDay)
    return "${civil.month}/${civil.day}"
}

internal fun agentUsageDayOfMonth(day: String): String {
    val epochDay = agentUsageParseUtcEpochDay(day) ?: return day
    return civilDateFromEpochDay(epochDay).day.toString()
}

@Composable
internal fun agentUsageFormattedDayName(day: String): String {
    val epochDay = agentUsageParseUtcEpochDay(day) ?: return day
    val civil = civilDateFromEpochDay(epochDay)
    return stringResource(Res.string.agent_usage_day_name, agentUsageWeekdayShort(day), civil.month, civil.day)
}
