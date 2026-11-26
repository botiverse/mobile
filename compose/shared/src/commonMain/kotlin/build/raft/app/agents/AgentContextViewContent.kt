package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.AgentContextView
import build.raft.app.data.AgentContextViewCompaction
import build.raft.app.data.AgentContextViewDisplayRow
import build.raft.app.data.AgentContextViewMessage
import build.raft.app.data.AgentContextViewMessageFilter
import build.raft.app.data.AgentContextViewMessageRole
import build.raft.app.data.AgentContextViewOutcome
import build.raft.app.data.AgentContextViewPart
import build.raft.app.data.AgentContextViewRecord
import build.raft.app.data.AgentContextViewRedactionKind
import build.raft.app.data.AgentContextViewStatus
import build.raft.app.data.AgentContextViewTool
import build.raft.app.data.AgentContextViewToolPairs
import build.raft.app.data.agentContextViewFilterMessageCount
import build.raft.app.data.agentContextViewPartKey
import build.raft.app.data.agentContextViewSectionCharCounts
import build.raft.app.data.buildAgentContextViewRowsByFilter
import build.raft.app.data.buildAgentContextViewToolPairs
import build.raft.app.data.findAgentContextViewMatchingRows
import build.raft.app.data.isAgentContextViewRaftNoticePart
import build.raft.app.format.RaftFormat
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCodeBlock
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftDescriptionValue
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldClearAction
import build.raft.designsystem.components.RaftTextFieldSize
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

internal const val AGENT_CONTEXT_SECTION_SYSTEM_TAG = "agent-context-section-system"
internal const val AGENT_CONTEXT_SECTION_TOOLS_TAG = "agent-context-section-tools"
internal const val AGENT_CONTEXT_SECTION_MESSAGES_TAG = "agent-context-section-messages"
internal const val AGENT_CONTEXT_SEARCH_FIELD_TAG = "agent-context-search-field"
internal const val AGENT_CONTEXT_VIEWED_BY_TAG = "agent-context-viewed-by"
internal const val AGENT_CONTEXT_TRIGGER_ROW_TAG = "agent-context-trigger-row"

private const val CONTEXT_TEXT_FOLD_THRESHOLD_LINES = 14
private const val CONTEXT_TEXT_FOLD_PREVIEW_LINES = 10

internal fun formatAgentContextCount(value: Long): String {
    val negative = value < 0L
    val digits = (if (negative) -value else value).toString()
    if (digits.length <= 3) return if (negative) "-$digits" else digits
    val out = StringBuilder(digits.length + digits.length / 3 + 1)
    if (negative) out.append('-')
    val leading = digits.length % 3
    if (leading > 0) {
        out.append(digits, 0, leading)
        if (leading < digits.length) out.append(',')
    }
    var i = leading
    while (i < digits.length) {
        out.append(digits, i, i + 3)
        i += 3
        if (i < digits.length) out.append(',')
    }
    return out.toString()
}

@Composable
private fun agentContextRedactionKindLabel(kind: AgentContextViewRedactionKind): String =
    when (kind) {
        AgentContextViewRedactionKind.PrivateKey -> stringResource(Res.string.agent_context_view_redaction_private_key)
        AgentContextViewRedactionKind.RaftKey -> stringResource(Res.string.agent_context_view_redaction_raft_key)
        AgentContextViewRedactionKind.Bearer -> stringResource(Res.string.agent_context_view_redaction_bearer)
        AgentContextViewRedactionKind.Authorization -> stringResource(Res.string.agent_context_view_redaction_authorization)
        AgentContextViewRedactionKind.VendorKey -> stringResource(Res.string.agent_context_view_redaction_vendor_key)
        AgentContextViewRedactionKind.GithubToken -> stringResource(Res.string.agent_context_view_redaction_github_token)
        AgentContextViewRedactionKind.AwsKey -> stringResource(Res.string.agent_context_view_redaction_aws_key)
        AgentContextViewRedactionKind.GoogleApiKey -> stringResource(Res.string.agent_context_view_redaction_google_api_key)
        AgentContextViewRedactionKind.XaiKey -> stringResource(Res.string.agent_context_view_redaction_xai_key)
        AgentContextViewRedactionKind.Jwt -> stringResource(Res.string.agent_context_view_redaction_jwt)
        AgentContextViewRedactionKind.SecretAssignment -> stringResource(Res.string.agent_context_view_redaction_secret_assignment)
        AgentContextViewRedactionKind.HexBlob -> stringResource(Res.string.agent_context_view_redaction_hex_blob)
        AgentContextViewRedactionKind.UrlQuery -> stringResource(Res.string.agent_context_view_redaction_url_query)
        AgentContextViewRedactionKind.UrlCredentials -> stringResource(Res.string.agent_context_view_redaction_url_credentials)
    }

@Composable
private fun agentContextCountString(count: Int, oneRes: org.jetbrains.compose.resources.StringResource, otherRes: org.jetbrains.compose.resources.StringResource): String =
    stringResource(if (count == 1) oneRes else otherRes, count)

/**
 * Facts summary (`RaftDescriptionList`), collapsible System prompt & Tools sections, and the
 * filterable/searchable Messages section of a loaded [AgentContextView].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentContextViewBodySections(
    context: AgentContextView,
    status: AgentContextViewStatus?,
    agentName: String,
    timeContext: RaftTimeContext?,
    onCopyText: (String) -> Unit,
    onCopyCallId: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (systemChars, toolsChars, messageChars) = remember(context) { agentContextViewSectionCharCounts(context) }
    val toolPairs = remember(context) { buildAgentContextViewToolPairs(context) }
    val rowsByFilter = remember(context, toolPairs) { buildAgentContextViewRowsByFilter(context, toolPairs) }

    var systemExpanded by remember(context.messageId) { mutableStateOf(false) }
    var toolsExpanded by remember(context.messageId) { mutableStateOf(false) }
    var messagesExpanded by remember(context.messageId) { mutableStateOf(true) }
    var activeFilter by remember(context.messageId) { mutableStateOf(AgentContextViewMessageFilter.All) }
    var searchQuery by remember(context.messageId) { mutableStateOf("") }
    var activeMatchCursor by remember(context.messageId, activeFilter, searchQuery) { mutableStateOf(0) }
    var highlightedMessageIndex by remember(context.messageId) { mutableStateOf<Int?>(context.trigger?.messageIndex) }

    val visibleRows = rowsByFilter[activeFilter].orEmpty()
    val matchingRowIndices =
        remember(context, visibleRows, searchQuery) {
            findAgentContextViewMatchingRows(context, visibleRows, searchQuery)
        }
    val safeMatchCursor =
        if (matchingRowIndices.isEmpty()) 0 else activeMatchCursor.coerceIn(0, matchingRowIndices.lastIndex)
    val activeMatchRowIndex = matchingRowIndices.getOrNull(safeMatchCursor)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentContextFactsCard(
            context = context,
            status = status,
            timeContext = timeContext,
        )

        if (context.system.isNotEmpty()) {
            val blockMeta =
                agentContextCountString(
                    context.system.size,
                    Res.string.agent_context_view_blocks_one,
                    Res.string.agent_context_view_blocks_other,
                )
            val charsMeta =
                agentContextCountString(
                    systemChars,
                    Res.string.agent_context_view_chars_one,
                    Res.string.agent_context_view_chars_other,
                )
            AgentContextCollapsibleSection(
                title = stringResource(Res.string.agent_context_view_section_system),
                meta = stringResource(Res.string.agent_context_view_section_meta, blockMeta, charsMeta),
                expanded = systemExpanded,
                onToggle = { systemExpanded = !systemExpanded },
                modifier = Modifier.testTag(AGENT_CONTEXT_SECTION_SYSTEM_TAG),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    context.system.forEachIndexed { index, block ->
                        AgentContextSystemBlockCard(
                            index = index,
                            text = block.text,
                            onCopy = { onCopyText(block.text) },
                        )
                    }
                }
            }
        }

        if (context.tools.isNotEmpty()) {
            val toolsMeta =
                agentContextCountString(
                    context.tools.size,
                    Res.string.agent_context_view_contents_tools_one,
                    Res.string.agent_context_view_contents_tools_other,
                )
            val charsMeta =
                agentContextCountString(
                    toolsChars,
                    Res.string.agent_context_view_chars_one,
                    Res.string.agent_context_view_chars_other,
                )
            AgentContextCollapsibleSection(
                title = stringResource(Res.string.agent_context_view_section_tools),
                meta = stringResource(Res.string.agent_context_view_section_meta, toolsMeta, charsMeta),
                expanded = toolsExpanded,
                onToggle = { toolsExpanded = !toolsExpanded },
                modifier = Modifier.testTag(AGENT_CONTEXT_SECTION_TOOLS_TAG),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    context.tools.forEach { tool ->
                        AgentContextToolRow(tool = tool, onCopyText = onCopyText)
                    }
                }
            }
        }

        val messagesCountMeta =
            agentContextCountString(
                context.messages.size,
                Res.string.agent_context_view_contents_messages_one,
                Res.string.agent_context_view_contents_messages_other,
            )
        val messagesCharsMeta =
            agentContextCountString(
                messageChars,
                Res.string.agent_context_view_chars_one,
                Res.string.agent_context_view_chars_other,
            )
        AgentContextCollapsibleSection(
            title = stringResource(Res.string.agent_context_view_section_messages),
            meta = stringResource(Res.string.agent_context_view_section_meta, messagesCountMeta, messagesCharsMeta),
            expanded = messagesExpanded,
            onToggle = { messagesExpanded = !messagesExpanded },
            modifier = Modifier.testTag(AGENT_CONTEXT_SECTION_MESSAGES_TAG),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentContextMessagesToolbar(
                    activeFilter = activeFilter,
                    rowsByFilter = rowsByFilter,
                    onFilterChange = { activeFilter = it },
                    searchQuery = searchQuery,
                    onSearchQueryChange = {
                        searchQuery = it
                        activeMatchCursor = 0
                    },
                    matchCount = matchingRowIndices.size,
                    activeMatchIndex = safeMatchCursor,
                    onPreviousMatch = {
                        if (matchingRowIndices.isNotEmpty()) {
                            val next = if (safeMatchCursor == 0) matchingRowIndices.lastIndex else safeMatchCursor - 1
                            activeMatchCursor = next
                            highlightedMessageIndex = visibleRows.getOrNull(matchingRowIndices[next])?.messageIndex
                        }
                    },
                    onNextMatch = {
                        if (matchingRowIndices.isNotEmpty()) {
                            val next = (safeMatchCursor + 1) % matchingRowIndices.size
                            activeMatchCursor = next
                            highlightedMessageIndex = visibleRows.getOrNull(matchingRowIndices[next])?.messageIndex
                        }
                    },
                    hasTrigger = context.trigger != null,
                    onJumpToTrigger = {
                        val triggerMsg = context.trigger?.messageIndex ?: return@AgentContextMessagesToolbar
                        if (activeFilter != AgentContextViewMessageFilter.All) {
                            activeFilter = AgentContextViewMessageFilter.All
                        }
                        highlightedMessageIndex = triggerMsg
                    },
                )
                if (visibleRows.isEmpty()) {
                    RaftText(
                        text = stringResource(Res.string.agent_context_view_filter_empty),
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foregroundHint,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        visibleRows.forEachIndexed { rowIndex, row ->
                            val isActiveMatch = activeMatchRowIndex == rowIndex
                            val isMatch = searchQuery.isNotBlank() && rowIndex in matchingRowIndices
                            AgentContextMessageRowCard(
                                context = context,
                                row = row,
                                toolPairs = toolPairs,
                                agentName = agentName,
                                isActiveMatch = isActiveMatch || highlightedMessageIndex == row.messageIndex,
                                isSearchMatch = isMatch,
                                onCopyText = onCopyText,
                                onCopyCallId = onCopyCallId,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentContextFactsCard(
    context: AgentContextView,
    status: AgentContextViewStatus?,
    timeContext: RaftTimeContext?,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    val runtimeName = observeAgentRuntimeDisplayName(context.runtime)
    val unknownLabel = stringResource(Res.string.agent_context_view_unknown)
    val tokensText =
        context.tokens.total?.let { total ->
            val totalStr = formatAgentContextCount(total)
            val cache = context.tokens.cacheRead?.takeIf { it > 0L }
            if (cache != null) {
                stringResource(Res.string.agent_context_view_tokens_value, totalStr, formatAgentContextCount(cache))
            } else {
                totalStr
            }
        }
    val sourceText =
        if (!context.source.version.isNullOrBlank()) {
            stringResource(Res.string.agent_context_view_source_version, context.source.format, context.source.version)
        } else {
            context.source.format
        }
    val computerName = status?.computer?.name?.takeIf { it.isNotBlank() }
    val readFromText =
        if (computerName != null) {
            stringResource(Res.string.agent_context_view_read_from_value, computerName, sourceText)
        } else {
            sourceText
        }
    val systemPart =
        when (context.system.size) {
            0 -> stringResource(Res.string.agent_context_view_contents_system_none)
            1 -> stringResource(Res.string.agent_context_view_contents_system_one)
            else -> stringResource(Res.string.agent_context_view_contents_system_other, context.system.size)
        }
    val toolsPart =
        agentContextCountString(
            context.tools.size,
            Res.string.agent_context_view_contents_tools_one,
            Res.string.agent_context_view_contents_tools_other,
        )
    val messagesPart =
        agentContextCountString(
            context.messages.size,
            Res.string.agent_context_view_contents_messages_one,
            Res.string.agent_context_view_contents_messages_other,
        )
    val contentsSummary = stringResource(Res.string.agent_context_view_contents_join, systemPart, toolsPart, messagesPart)
    val totalRedacted = context.redactions.values.sum()
    val redactionSummary =
        if (totalRedacted > 0) {
            val details =
                context.redactions.entries
                    .filter { it.value > 0 }
                    .map { (kind, count) ->
                        stringResource(
                            Res.string.agent_context_view_redaction_item,
                            count,
                            agentContextRedactionKindLabel(kind),
                        )
                    }
                    .joinToString(", ")
            stringResource(Res.string.agent_context_view_redacted, totalRedacted, details)
        } else {
            null
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .padding(14.dp),
    ) {
        RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
            Item(
                term = stringResource(Res.string.agent_context_view_fact_runtime),
                value = runtimeName,
            )
            Item(
                term = stringResource(Res.string.agent_context_view_fact_model),
                value = context.model?.takeIf { it.isNotBlank() } ?: unknownLabel,
                mono = !context.model.isNullOrBlank(),
            )
            if (tokensText != null) {
                Item(
                    term = stringResource(Res.string.agent_context_view_fact_tokens),
                    value = tokensText,
                )
            }
            context.requestAt?.takeIf { it.isNotBlank() }?.let { requestAt ->
                Item(
                    term = stringResource(Res.string.agent_context_view_fact_request_at),
                    value = raftLocalizedMediumDateTime(requestAt, timeContext),
                )
            }
            Item(
                term = stringResource(Res.string.agent_context_view_fact_read_from),
                value = readFromText,
            )
            status?.retainedSince?.takeIf { it.isNotBlank() }?.let { keptSince ->
                Item(
                    term = stringResource(Res.string.agent_context_view_fact_kept_since),
                    value = settingsCompactDateLabel(keptSince, timeContext),
                )
            }
            Item(term = stringResource(Res.string.agent_context_view_fact_contents)) {
                RaftDescriptionValue(contentsSummary, copyable = false)
                if (context.compaction is AgentContextViewCompaction.Happened) {
                    RaftBadge(
                        text = stringResource(Res.string.agent_context_view_compacted),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Information,
                    )
                }
                if (redactionSummary != null) {
                    RaftBadge(
                        text = redactionSummary,
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Warning,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentContextCollapsibleSection(
    title: String,
    meta: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .raftClickable(
                        onClick = onToggle,
                        role = Role.Button,
                        tier = RaftHoverTier.Subtle,
                    )
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftLucideIcon(
                name = if (expanded) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
                modifier = Modifier.size(16.dp),
                color = raftArgbHex(colors.foregroundMuted),
            )
            RaftText(
                text = title,
                style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RaftText(
                text = meta,
                style = RaftTheme.typography.monoMeta,
                color = colors.foregroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(RaftStroke.Hairline)
                    .background(colors.lineHairline)
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun AgentContextSystemBlockCard(
    index: Int,
    text: String,
    onCopy: () -> Unit,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.fillSubtle, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftText(
                text = stringResource(Res.string.agent_context_view_system_block, index),
                style = RaftTheme.typography.monoMeta,
                color = colors.foregroundHint,
            )
            RaftIconButton(
                onClick = onCopy,
                tooltip = stringResource(Res.string.common_copy),
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Sm,
            ) {
                RaftSlotIcon(RaftLucideIconName.Copy)
            }
        }
        AgentContextFoldableText(text = text, mono = true)
    }
}

@Composable
private fun AgentContextToolRow(
    tool: AgentContextViewTool,
    onCopyText: (String) -> Unit,
) {
    var expanded by remember(tool.name) { mutableStateOf(false) }
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.fillSubtle, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .raftClickable(
                        onClick = { expanded = !expanded },
                        tier = RaftHoverTier.Subtle,
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftLucideIcon(
                name = if (expanded) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
                modifier = Modifier.size(14.dp),
                color = raftArgbHex(colors.foregroundMuted),
            )
            RaftText(
                text = tool.name,
                style = RaftTheme.typography.monoCode.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tool.description.isNotBlank()) {
                    RaftText(
                        text = tool.description,
                        style = RaftTheme.typography.callout,
                        color = colors.foregroundMuted,
                    )
                }
                RaftText(
                    text = stringResource(Res.string.agent_context_view_tools_input_schema),
                    style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foregroundHint,
                )
                if (!tool.inputSchemaJson.isNullOrBlank()) {
                    RaftCodeBlock(
                        text = tool.inputSchemaJson,
                        action = {
                            RaftIconButton(
                                onClick = { onCopyText(tool.inputSchemaJson) },
                                tooltip = stringResource(Res.string.common_copy),
                                variant = RaftButtonVariant.Ghost,
                                size = RaftIconButtonSize.Sm,
                            ) {
                                RaftSlotIcon(RaftLucideIconName.Copy)
                            }
                        },
                    )
                } else {
                    RaftText(
                        text = stringResource(Res.string.agent_context_view_tools_no_schema),
                        style = RaftTheme.typography.footnote,
                        color = colors.foregroundHint,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentContextMessagesToolbar(
    activeFilter: AgentContextViewMessageFilter,
    rowsByFilter: Map<AgentContextViewMessageFilter, List<AgentContextViewDisplayRow>>,
    onFilterChange: (AgentContextViewMessageFilter) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    matchCount: Int,
    activeMatchIndex: Int,
    onPreviousMatch: () -> Unit,
    onNextMatch: () -> Unit,
    hasTrigger: Boolean,
    onJumpToTrigger: () -> Unit,
) {
    val colors = RaftTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftSegmentedControl {
                val filterItems =
                    listOf(
                        AgentContextViewMessageFilter.All to Res.string.agent_context_view_filter_all,
                        AgentContextViewMessageFilter.Conversation to Res.string.agent_context_view_filter_conversation,
                        AgentContextViewMessageFilter.Notices to Res.string.agent_context_view_filter_notices,
                        AgentContextViewMessageFilter.Tools to Res.string.agent_context_view_filter_tools,
                    )
                filterItems.forEach { (filter, labelRes) ->
                    val count = agentContextViewFilterMessageCount(rowsByFilter[filter].orEmpty())
                    RaftSegment(
                        text = stringResource(labelRes),
                        selected = activeFilter == filter,
                        onClick = { onFilterChange(filter) },
                        count = count.takeIf { it > 0 }?.toString(),
                    )
                }
            }
            if (hasTrigger) {
                RaftIconButton(
                    onClick = onJumpToTrigger,
                    tooltip = stringResource(Res.string.agent_context_view_jump_trigger),
                    variant = RaftButtonVariant.Default,
                    size = RaftIconButtonSize.Md,
                ) {
                    RaftSlotIcon(RaftLucideIconName.Send)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = stringResource(Res.string.agent_context_view_search_label),
                leading = { RaftSlotIcon(RaftLucideIconName.Search) },
                trailingAction =
                    if (searchQuery.isNotEmpty()) {
                        {
                            RaftTextFieldClearAction(
                                onClear = { onSearchQueryChange("") },
                                label = stringResource(Res.string.agent_context_view_search_clear),
                            )
                        }
                    } else {
                        null
                    },
                size = RaftTextFieldSize.Md,
                modifier = Modifier.weight(1f).testTag(AGENT_CONTEXT_SEARCH_FIELD_TAG),
            )
            if (searchQuery.isNotBlank()) {
                val countLabel =
                    if (matchCount > 0) {
                        stringResource(Res.string.agent_context_view_search_count, activeMatchIndex + 1, matchCount)
                    } else {
                        stringResource(Res.string.agent_context_view_search_no_matches)
                    }
                RaftText(
                    text = countLabel,
                    style = RaftTheme.typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                )
                RaftIconButton(
                    onClick = onPreviousMatch,
                    tooltip = stringResource(Res.string.agent_context_view_search_previous),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Sm,
                    enabled = matchCount > 0,
                ) {
                    RaftSlotIcon(RaftLucideIconName.ChevronUp)
                }
                RaftIconButton(
                    onClick = onNextMatch,
                    tooltip = stringResource(Res.string.agent_context_view_search_next),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Sm,
                    enabled = matchCount > 0,
                ) {
                    RaftSlotIcon(RaftLucideIconName.ChevronDown)
                }
            }
        }
    }
}

@Composable
private fun AgentContextMessageRowCard(
    context: AgentContextView,
    row: AgentContextViewDisplayRow,
    toolPairs: AgentContextViewToolPairs,
    agentName: String,
    isActiveMatch: Boolean,
    isSearchMatch: Boolean,
    onCopyText: (String) -> Unit,
    onCopyCallId: (String) -> Unit,
) {
    val colors = RaftTheme.colors
    val message = context.messages[row.messageIndex]
    val trigger = context.trigger
    val containsTrigger =
        trigger != null &&
            trigger.messageIndex == row.messageIndex &&
            trigger.partIndex in row.partIndices
    val isRaftNoticeRow =
        message.role == AgentContextViewMessageRole.User &&
            row.partIndices.any { idx -> isAgentContextViewRaftNoticePart(message, message.parts[idx]) }
    val speakerLabel =
        when (message.role) {
            AgentContextViewMessageRole.System -> stringResource(Res.string.agent_context_view_role_system)
            AgentContextViewMessageRole.User -> stringResource(Res.string.agent_context_view_role_user)
            AgentContextViewMessageRole.Assistant -> agentName
            AgentContextViewMessageRole.Tool -> stringResource(Res.string.agent_context_view_role_tool)
        }
    val indexLabel =
        if (row.lastMessageIndex > row.messageIndex) {
            stringResource(Res.string.agent_context_view_turn_range, row.messageIndex + 1, row.lastMessageIndex + 1)
        } else {
            stringResource(Res.string.agent_context_view_message_index, row.messageIndex + 1)
        }
    val shape = RoundedCornerShape(RaftRadius.Control)
    val bgColor =
        when {
            containsTrigger -> colors.infoSoft
            isActiveMatch -> colors.warningSoft
            isSearchMatch -> colors.fillSubtle
            else -> colors.layerCanvas
        }
    val borderColor =
        when {
            containsTrigger -> colors.infoStrong
            isActiveMatch -> colors.warningStrong
            else -> colors.lineHairline
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (containsTrigger) Modifier.testTag(AGENT_CONTEXT_TRIGGER_ROW_TAG) else Modifier)
                .background(bgColor, shape)
                .border(RaftStroke.Hairline, borderColor, shape)
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftText(
                text = speakerLabel,
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isRaftNoticeRow) {
                RaftBadge(
                    text = stringResource(Res.string.agent_context_view_turn_from_raft),
                    appearance = RaftBadgeAppearance.Outline,
                    variant = RaftBadgeVariant.Information,
                )
            }
            Spacer(Modifier.weight(1f))
            RaftText(
                text = indexLabel,
                style = RaftTheme.typography.monoMeta,
                color = colors.foregroundHint,
            )
        }

        val toolUseIndices =
            row.partIndices.filter { idx -> message.parts[idx] is AgentContextViewPart.ToolUse }
        row.partIndices.forEach { partIndex ->
            val part = message.parts[partIndex]
            val isTriggerPart =
                trigger != null && trigger.messageIndex == row.messageIndex && trigger.partIndex == partIndex
            val stepNumber = toolUseIndices.indexOf(partIndex).takeIf { it >= 0 }?.plus(1)
            val pairedResultPlace = toolPairs.resultOf[agentContextViewPartKey(row.messageIndex, partIndex)]
            val pairedResult =
                pairedResultPlace?.let { place ->
                    context.messages.getOrNull(place.messageIndex)?.parts?.getOrNull(place.partIndex)
                        as? AgentContextViewPart.ToolResult
                }
            AgentContextMessagePartView(
                part = part,
                isTrigger = isTriggerPart,
                stepNumber = stepNumber,
                pairedResult = pairedResult,
                onCopyText = onCopyText,
                onCopyCallId = onCopyCallId,
            )
        }

        // Any unpaired tool results absorbed in member messages that were not consumed by a ToolUse above.
        row.members.forEach { member ->
            val memberMsg = context.messages[member.messageIndex]
            member.partIndices.forEach { partIndex ->
                val partKey = agentContextViewPartKey(member.messageIndex, partIndex)
                if (partKey !in toolPairs.callOf) {
                    AgentContextMessagePartView(
                        part = memberMsg.parts[partIndex],
                        isTrigger = false,
                        stepNumber = null,
                        pairedResult = null,
                        onCopyText = onCopyText,
                        onCopyCallId = onCopyCallId,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentContextMessagePartView(
    part: AgentContextViewPart,
    isTrigger: Boolean,
    stepNumber: Int?,
    pairedResult: AgentContextViewPart.ToolResult?,
    onCopyText: (String) -> Unit,
    onCopyCallId: (String) -> Unit,
) {
    val colors = RaftTheme.colors
    when (part) {
        is AgentContextViewPart.Text -> {
            AgentContextFoldableText(text = part.text, mono = false)
        }
        is AgentContextViewPart.Thinking -> {
            if (part.text.isNullOrBlank()) {
                RaftBadge(
                    text = stringResource(Res.string.agent_context_view_part_thinking_hidden_badge),
                    appearance = RaftBadgeAppearance.Outline,
                    variant = RaftBadgeVariant.Muted,
                )
            } else {
                var expanded by remember(part.text) { mutableStateOf(false) }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(RaftRadius.Control))
                            .background(colors.fillSubtle)
                            .border(RaftStroke.Hairline, colors.lineHairline, RoundedCornerShape(RaftRadius.Control)),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .raftClickable(onClick = { expanded = !expanded }, tier = RaftHoverTier.Subtle)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftLucideIcon(
                            name = if (expanded) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
                            modifier = Modifier.size(14.dp),
                            color = raftArgbHex(colors.foregroundMuted),
                        )
                        RaftText(
                            text = stringResource(Res.string.agent_context_view_part_thinking),
                            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                            color = colors.foregroundMuted,
                        )
                    }
                    if (expanded) {
                        Box(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                            AgentContextFoldableText(text = part.text, mono = false)
                        }
                    }
                }
            }
        }
        is AgentContextViewPart.ToolUse -> {
            if (isTrigger) {
                AgentContextTriggerToolUseCard(
                    part = part,
                    stepNumber = stepNumber,
                    onCopyText = onCopyText,
                )
            } else {
                AgentContextToolStepCard(
                    part = part,
                    pairedResult = pairedResult,
                    onCopyText = onCopyText,
                    onCopyCallId = onCopyCallId,
                )
            }
        }
        is AgentContextViewPart.ToolResult -> {
            val heading =
                when {
                    part.isError && !part.toolName.isNullOrBlank() ->
                        stringResource(Res.string.agent_context_view_part_error_of, part.toolName)
                    part.isError -> stringResource(Res.string.agent_context_view_part_error)
                    !part.toolName.isNullOrBlank() ->
                        stringResource(Res.string.agent_context_view_part_result_of, part.toolName)
                    else -> stringResource(Res.string.agent_context_view_part_result)
                }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(if (part.isError) colors.dangerSoft else colors.fillSubtle, RoundedCornerShape(RaftRadius.Control))
                        .border(RaftStroke.Hairline, colors.lineHairline, RoundedCornerShape(RaftRadius.Control))
                        .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RaftText(
                    text = heading,
                    style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = if (part.isError) colors.dangerStrong else colors.foregroundMuted,
                )
                if (part.output.isEmpty()) {
                    RaftText(
                        text = stringResource(Res.string.agent_context_view_step_empty_output),
                        style = RaftTheme.typography.footnote,
                        color = colors.foregroundHint,
                    )
                } else {
                    AgentContextFoldableText(text = part.output, mono = true)
                }
            }
        }
        is AgentContextViewPart.Image -> {
            val unknownLabel = stringResource(Res.string.agent_context_view_unknown)
            val mediaLabel = part.mediaType?.takeIf { it.isNotBlank() } ?: unknownLabel
            val sizeLabel = part.sizeBytes?.let { RaftFormat.bytes(it) } ?: unknownLabel
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(colors.fillSubtle, RoundedCornerShape(RaftRadius.Control))
                        .border(RaftStroke.Hairline, colors.lineHairline, RoundedCornerShape(RaftRadius.Control))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftLucideIcon(
                    name = RaftLucideIconName.Image,
                    modifier = Modifier.size(16.dp),
                    color = raftArgbHex(colors.foregroundMuted),
                )
                RaftText(
                    text = stringResource(Res.string.agent_context_view_part_image, mediaLabel, sizeLabel),
                    style = RaftTheme.typography.footnote,
                    color = colors.foregroundMuted,
                )
            }
        }
        is AgentContextViewPart.Compaction -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(colors.fillSubtle, RoundedCornerShape(RaftRadius.Control))
                        .border(RaftStroke.Hairline, colors.lineHairline, RoundedCornerShape(RaftRadius.Control))
                        .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RaftText(
                    text = stringResource(Res.string.agent_context_view_part_compacted),
                    style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                )
                if (part.text.isNullOrBlank()) {
                    RaftText(
                        text = stringResource(Res.string.agent_context_view_part_compaction_encrypted),
                        style = RaftTheme.typography.footnote,
                        color = colors.foregroundHint,
                    )
                } else {
                    AgentContextFoldableText(text = part.text, mono = false)
                }
            }
        }
        is AgentContextViewPart.Unknown -> {
            RaftText(
                text = stringResource(Res.string.agent_context_view_part_unknown, part.originalKind),
                style = RaftTheme.typography.footnote,
                color = colors.foregroundHint,
            )
        }
    }
}

@Composable
private fun AgentContextTriggerToolUseCard(
    part: AgentContextViewPart.ToolUse,
    stepNumber: Int?,
    onCopyText: (String) -> Unit,
) {
    var showCommand by remember(part.toolUseId, part.inputJson) { mutableStateOf(false) }
    val colors = RaftTheme.colors
    val heading =
        if (stepNumber != null) {
            stringResource(Res.string.agent_context_view_step_sent, stepNumber)
        } else {
            stringResource(Res.string.agent_context_view_step_sent_no_step)
        }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftBadge(
                text = heading,
                appearance = RaftBadgeAppearance.Soft,
                variant = RaftBadgeVariant.Information,
                icon = { RaftSlotIcon(RaftLucideIconName.Send) },
            )
            Spacer(Modifier.weight(1f))
            RaftButton(
                text =
                    stringResource(
                        if (showCommand) {
                            Res.string.agent_context_view_step_hide_command
                        } else {
                            Res.string.agent_context_view_step_show_command
                        }
                    ),
                onClick = { showCommand = !showCommand },
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Md,
            )
        }
        if (showCommand) {
            RaftCodeBlock(
                text = part.inputJson,
                action = {
                    RaftIconButton(
                        onClick = { onCopyText(part.inputJson) },
                        tooltip = stringResource(Res.string.common_copy),
                        variant = RaftButtonVariant.Ghost,
                        size = RaftIconButtonSize.Sm,
                    ) {
                        RaftSlotIcon(RaftLucideIconName.Copy)
                    }
                },
            )
        }
    }
}

@Composable
private fun AgentContextToolStepCard(
    part: AgentContextViewPart.ToolUse,
    pairedResult: AgentContextViewPart.ToolResult?,
    onCopyText: (String) -> Unit,
    onCopyCallId: (String) -> Unit,
) {
    var expanded by remember(part.toolUseId, part.toolName) { mutableStateOf(false) }
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    val failed = pairedResult?.isError == true
    val statusBadgeText =
        when {
            pairedResult == null -> stringResource(Res.string.agent_context_view_step_no_result)
            failed -> stringResource(Res.string.agent_context_view_step_failed)
            else -> stringResource(Res.string.agent_context_view_step_succeeded)
        }
    val statusVariant =
        when {
            pairedResult == null -> RaftBadgeVariant.Muted
            failed -> RaftBadgeVariant.Danger
            else -> RaftBadgeVariant.Success
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .raftClickable(onClick = { expanded = !expanded }, tier = RaftHoverTier.Subtle)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftLucideIcon(
                name = if (expanded) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
                modifier = Modifier.size(14.dp),
                color = raftArgbHex(colors.foregroundMuted),
            )
            RaftText(
                text = part.toolName,
                style = RaftTheme.typography.monoCode.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!part.summary.isNullOrBlank()) {
                RaftText(
                    text = part.summary,
                    style = RaftTheme.typography.footnote,
                    color = colors.foregroundHint,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            RaftBadge(
                text = statusBadgeText,
                appearance = RaftBadgeAppearance.Soft,
                variant = statusVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftText(
                        text = stringResource(Res.string.agent_context_view_step_input),
                        style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foregroundHint,
                    )
                    part.toolUseId?.takeIf { it.isNotBlank() }?.let { callId ->
                        RaftButton(
                            text = stringResource(Res.string.agent_context_view_part_copy_call_id, callId.take(12)),
                            onClick = { onCopyCallId(callId) },
                            variant = RaftButtonVariant.Ghost,
                            size = RaftButtonSize.Md,
                        )
                    }
                }
                if (!part.flatArguments.isNullOrEmpty()) {
                    RaftDescriptionList(direction = RaftDescriptionListDirection.Vertical) {
                        part.flatArguments.forEach { (key, value) ->
                            Item(term = key, value = value, mono = true)
                        }
                    }
                } else {
                    RaftCodeBlock(
                        text = part.inputJson,
                        action = {
                            RaftIconButton(
                                onClick = { onCopyText(part.inputJson) },
                                tooltip = stringResource(Res.string.common_copy),
                                variant = RaftButtonVariant.Ghost,
                                size = RaftIconButtonSize.Sm,
                            ) {
                                RaftSlotIcon(RaftLucideIconName.Copy)
                            }
                        },
                    )
                }

                if (pairedResult != null) {
                    RaftText(
                        text = stringResource(Res.string.agent_context_view_step_output),
                        style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = if (pairedResult.isError) colors.dangerStrong else colors.foregroundHint,
                    )
                    if (pairedResult.output.isEmpty()) {
                        RaftText(
                            text = stringResource(Res.string.agent_context_view_step_empty_output),
                            style = RaftTheme.typography.footnote,
                            color = colors.foregroundHint,
                        )
                    } else {
                        AgentContextFoldableText(text = pairedResult.output, mono = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentContextFoldableText(
    text: String,
    mono: Boolean,
) {
    val lines = remember(text) { text.split('\n') }
    val foldable = lines.size > CONTEXT_TEXT_FOLD_THRESHOLD_LINES
    var expanded by remember(text) { mutableStateOf(false) }
    val displayedText =
        if (foldable && !expanded) {
            lines.take(CONTEXT_TEXT_FOLD_PREVIEW_LINES).joinToString("\n")
        } else {
            text
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftText(
            text = displayedText,
            style = if (mono) RaftTheme.typography.monoCode else RaftTheme.typography.callout,
            color = RaftTheme.colors.foreground,
        )
        if (foldable) {
            RaftButton(
                text =
                    if (expanded) {
                        stringResource(Res.string.agent_context_view_show_less)
                    } else {
                        stringResource(Res.string.agent_context_view_show_all_lines, lines.size)
                    },
                onClick = { expanded = !expanded },
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Md,
            )
        }
    }
}

@Composable
internal fun agentContextViewOutcomeLabel(outcome: AgentContextViewOutcome?): String? =
    when (outcome) {
        null, AgentContextViewOutcome.Ok -> null
        AgentContextViewOutcome.Cancelled -> stringResource(Res.string.agent_context_view_outcome_cancelled)
        AgentContextViewOutcome.Refused -> stringResource(Res.string.agent_context_view_outcome_refused)
        AgentContextViewOutcome.NoComputer -> stringResource(Res.string.agent_context_view_outcome_no_computer)
        AgentContextViewOutcome.Offline -> stringResource(Res.string.agent_context_view_outcome_offline)
        AgentContextViewOutcome.OldDaemon -> stringResource(Res.string.agent_context_view_outcome_old_daemon)
        AgentContextViewOutcome.AgentMoved -> stringResource(Res.string.agent_context_view_outcome_agent_moved)
        AgentContextViewOutcome.NotFound -> stringResource(Res.string.agent_context_view_outcome_not_found)
        AgentContextViewOutcome.Expired -> stringResource(Res.string.agent_context_view_outcome_expired)
        AgentContextViewOutcome.UnsupportedRuntime -> stringResource(Res.string.agent_context_view_outcome_unsupported_runtime)
        AgentContextViewOutcome.TooLarge -> stringResource(Res.string.agent_context_view_outcome_too_large)
        AgentContextViewOutcome.ReadFailed -> stringResource(Res.string.agent_context_view_outcome_read_failed)
        AgentContextViewOutcome.ContextMismatch,
        AgentContextViewOutcome.MediaRemoved,
        AgentContextViewOutcome.NewerSessionFormat,
        AgentContextViewOutcome.NotRecorded -> stringResource(Res.string.agent_context_view_outcome_read_failed)
    }

/**
 * Audit trail list (`Viewed by`) shown below the body in every loaded/terminal state when non-empty.
 */
@Composable
internal fun AgentContextViewedBySection(
    records: List<AgentContextViewRecord>,
    viewerUserId: String?,
    serverId: String?,
    timeContext: RaftTimeContext?,
    modifier: Modifier = Modifier,
) {
    if (records.isEmpty()) return
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    val formerMemberLabel = stringResource(Res.string.agent_context_view_former_member)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(AGENT_CONTEXT_VIEWED_BY_TAG)
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RaftText(
            text = stringResource(Res.string.agent_context_view_viewed_by),
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foregroundMuted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            records.forEach { record ->
                val rawName = record.viewer?.name?.takeIf { it.isNotBlank() } ?: formerMemberLabel
                val isYou = !record.viewerIsAgent && !viewerUserId.isNullOrBlank() && record.viewer?.id == viewerUserId
                val displayName =
                    if (isYou) {
                        stringResource(Res.string.agent_context_view_viewer_row_you, rawName)
                    } else {
                        stringResource(Res.string.agent_context_view_viewer_row_name, rawName)
                    }
                val whenLabel = raftLocalizedShortDateTime(record.at, timeContext)
                val outcomeLabel = agentContextViewOutcomeLabel(record.outcome)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftMemberAvatar(
                        identity =
                            RaftMemberAvatarIdentity(
                                kind = if (record.viewerIsAgent) MemberKind.Agent else MemberKind.Human,
                                memberId = record.viewer?.id,
                                serverId = serverId,
                            ),
                        label = rawName,
                        avatarSize = 24.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        RaftText(
                            text = displayName,
                            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Medium),
                            color = colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (outcomeLabel != null) {
                            RaftText(
                                text = outcomeLabel,
                                style = RaftTheme.typography.caption,
                                color = colors.foregroundHint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    RaftText(
                        text = whenLabel,
                        style = RaftTheme.typography.monoMeta,
                        color = colors.foregroundHint,
                        maxLines = 1,
                    )
                }
            }
        }
        RaftText(
            text = stringResource(Res.string.agent_context_view_viewed_by_note),
            style = RaftTheme.typography.caption,
            color = colors.foregroundHint,
        )
    }
}
