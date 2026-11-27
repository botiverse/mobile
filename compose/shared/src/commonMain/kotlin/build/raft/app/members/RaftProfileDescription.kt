package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import build.raft.app.base.BridgeModule
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.ProfileDescriptionSegment
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedArtifactSummaryStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY
import build.raft.app.data.answersFor
import build.raft.app.data.artifactWebUrl
import build.raft.app.data.profileDescriptionArtifactTargets
import build.raft.app.data.profileDescriptionPieces
import build.raft.app.data.profileDescriptionPlainWords
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.data.resolveProfileDescriptionSegments
import build.raft.app.markdown.RAFT_RICHTEXT_INLINE_CHIP_FONT_SIZE_EM
import build.raft.app.markdown.RaftInlineBoxStyle
import build.raft.app.markdown.RaftInlineWordJoiner
import build.raft.app.markdown.RaftRichTextInlineChipMarginDp
import build.raft.app.markdown.lucideIcon
import build.raft.app.markdown.raftAppLinkCap
import build.raft.app.markdown.raftAppLinkUnbreakable
import build.raft.app.markdown.raftChipLinkStyles
import build.raft.app.markdown.raftInlineChipExtents
import build.raft.app.markdown.raftInlineDecorations
import build.raft.app.markdown.raftInlineGapInlineContent
import build.raft.app.markdown.raftMarkdownInlineContentIds
import build.raft.app.markdown.raftTextWithoutInlineGaps
import build.raft.app.markdown.withRaftInlineBox
import build.raft.app.markdown.withRaftInlineChipGapLinks
import build.raft.app.markdown.withRaftInlineChipGaps
import build.raft.app.markdown.withRaftMarkdownBodyClick
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.app.thread.LocalThreadAppLinkPreviewHost
import build.raft.app.thread.LocalThreadShareCaptureActive
import build.raft.app.thread.ThreadAppLinkPreviewHost
import build.raft.app.thread.icon
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftPreviewCardBody
import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardDescription
import build.raft.designsystem.components.RaftPreviewCardPopup
import build.raft.designsystem.components.RaftPreviewCardSeparator
import build.raft.designsystem.components.RaftPreviewCardState
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.rememberRaftPreviewCardState
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.StateFlow

/*
 * Profile description Artifact chips (botiverse/slock #14969 `ProfileDescription.tsx` and #15230
 * unreadable Artifact fallback): renders Workspace Artifact links inside an agent's or member's
 * plain-text description (message author subtitle + popover, profile preview hover card, and
 * agent/human profile detail descriptions) as inline title chips backed by
 * [SharedArtifactSummaryStore].
 *
 * - Available (`Ok`): cyan chip (`InfoSoft`) with the Artifact kind icon + title, opens in place.
 * - Loading: muted gray `"Artifact"` chip (never the raw URL), still clickable to open in place.
 * - Unavailable / Failed: muted gray `"Artifact"` chip (never the raw URL or hidden title), still
 *   clickable to open in place, with an unavailable hover card on pointer hover saying only that
 *   the viewer can't see it and opening it says why (#15230).
 * - Deleted: muted dimmed chip with `FileX`, deleted title and `"deleted"` suffix, not clickable.
 * - Non-Artifact URLs stay plain text untouched.
 * - Share image capture ([LocalThreadShareCaptureActive]) keeps the author's raw link text.
 */

/**
 * Ambient host supplying batched Artifact summary lookup, Raft app hosts, server feature-flag
 * gating and web origin to profile descriptions on screen. Falls back to
 * [LocalThreadAppLinkPreviewHost] inside conversations when not explicitly provided.
 */
@Immutable
internal data class RaftProfileDescriptionArtifactHost(
    val serverId: String,
    val summaries: SharedArtifactSummaryStore,
    val appHosts: StateFlow<List<String>>,
    val featureFlags: SharedServerFeatureFlags?,
    val webBaseUrl: String,
    /**
     * Optional opener override (used in tests/fixtures). When null, chips open via
     * [rememberRaftArtifactOpener] with a browser fallback to [artifactWebUrl].
     */
    val onOpenArtifact: ((ArtifactLinkTarget, ArtifactSummary?) -> Unit)? = null,
)

internal val LocalRaftProfileDescriptionArtifacts =
    staticCompositionLocalOf<RaftProfileDescriptionArtifactHost?> { null }

/** Tag on clickable available Artifact chips in profile descriptions. */
internal const val RaftProfileDescriptionArtifactTag = "raft.build.profileDescription.artifact"

/** Tag on clickable pending (loading) Artifact chips in profile descriptions. */
internal const val RaftProfileDescriptionArtifactPendingTag = "raft.build.profileDescription.artifactPending"

/** Tag on clickable unavailable/failed Artifact chips in profile descriptions. */
internal const val RaftProfileDescriptionArtifactUnavailableTag = "raft.build.profileDescription.artifactUnavailable"

/** String annotation over unavailable Artifact chips (drives the #15230 pointer hover card). */
internal const val RaftProfileDescriptionUnavailableHoverTag = "raft.build.profileDescription.unavailableHover"

/** Test tag on the #15230 unavailable Artifact hover card body. */
internal const val RaftProfileDescriptionUnavailableCardTestTag = "profile-description-artifact-unavailable-card"

/** About 16em (two-thirds of message body's 24em `RaftAppLinkChipMaxChars`): web `max-w-[16em]`. */
internal const val RaftProfileDescriptionChipMaxChars = 26

private const val ProfileDescriptionDeletedAlpha = 0.65f

/** Derives a [RaftProfileDescriptionArtifactHost] from a conversation's [ThreadAppLinkPreviewHost]. */
@Composable
internal fun rememberThreadProfileDescriptionArtifactHost(
    threadHost: ThreadAppLinkPreviewHost?,
): RaftProfileDescriptionArtifactHost? {
    val artifacts = threadHost?.artifacts ?: return null
    val serverId = threadHost.serverId
    return remember(serverId, artifacts) {
        RaftProfileDescriptionArtifactHost(
            serverId = serverId,
            summaries = artifacts.summaries,
            appHosts = artifacts.appHosts,
            featureFlags = artifacts.featureFlags,
            webBaseUrl = artifacts.webBaseUrl,
        )
    }
}

/**
 * Resolves the active [RaftProfileDescriptionArtifactHost]: explicit
 * [LocalRaftProfileDescriptionArtifacts] first, else derived from [LocalThreadAppLinkPreviewHost].
 */
@Composable
internal fun rememberCurrentProfileDescriptionArtifactHost(): RaftProfileDescriptionArtifactHost? {
    LocalRaftProfileDescriptionArtifacts.current?.let { return it }
    return rememberThreadProfileDescriptionArtifactHost(LocalThreadAppLinkPreviewHost.current)
}

/** Builds a [RaftProfileDescriptionArtifactHost] from shared stores for [serverId], or null when blank. */
@Composable
internal fun rememberShellProfileDescriptionArtifactHost(
    sharedStores: RaftSharedStoreProvider,
    serverId: String?,
): RaftProfileDescriptionArtifactHost? {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val webBaseUrl = sharedStores.apiEnvironmentStore.current().webUrl
    return remember(sharedStores, cleanServerId, webBaseUrl) {
        RaftProfileDescriptionArtifactHost(
            serverId = cleanServerId,
            summaries = sharedStores.artifactSummaryStore,
            appHosts = sharedStores.clientConfigStore.appHosts,
            featureFlags = sharedStores.serverFeatureFlags,
            webBaseUrl = webBaseUrl,
        )
    }
}

/** Builds a [RaftProfileDescriptionArtifactHost] for [MemberDetailPageContent] without bloating its method body. */
@Composable
internal fun rememberMemberDetailProfileDescriptionArtifactHost(
    bridge: BridgeModule,
    serverId: String?,
): RaftProfileDescriptionArtifactHost? {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    return rememberShellProfileDescriptionArtifactHost(sharedStores, cleanServerId)
}

@Immutable
internal data class RaftProfileDescriptionResolved(
    val rawText: String,
    val segments: List<ProfileDescriptionSegment>,
    val hasArtifacts: Boolean,
    val plainWords: String,
    val host: RaftProfileDescriptionArtifactHost?,
)

/**
 * Cuts [text] around http(s) addresses, batches summary lookups for any Artifact targets through
 * [SharedArtifactSummaryStore], and returns resolved segments + plain-text tooltip words.
 * Requests on mount (matching web `ProfileDescription.tsx`), gated on `workspace_artifacts_v0`
 * and suppressed during share-image capture ([LocalThreadShareCaptureActive]).
 */
@Composable
internal fun rememberProfileDescriptionResolved(
    text: String,
    enabled: Boolean = true,
): RaftProfileDescriptionResolved {
    val shareCaptureActive = LocalThreadShareCaptureActive.current
    val host = if (enabled && !shareCaptureActive) rememberCurrentProfileDescriptionArtifactHost() else null
    if (host == null || text.isEmpty()) {
        return remember(text) {
            RaftProfileDescriptionResolved(
                rawText = text,
                segments = emptyList(),
                hasArtifacts = false,
                plainWords = text,
                host = null,
            )
        }
    }
    val flagEnabled = rememberServerFeatureLatched(host.featureFlags, ServerId(host.serverId), WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY)
    val appHosts by host.appHosts.collectAsState()
    val pieces = remember(text) { profileDescriptionPieces(text) }
    val targets =
        remember(flagEnabled, pieces, appHosts) {
            if (flagEnabled) profileDescriptionArtifactTargets(pieces, appHosts) else emptyList()
        }
    if (targets.isEmpty()) {
        return remember(text) {
            RaftProfileDescriptionResolved(
                rawText = text,
                segments = emptyList(),
                hasArtifacts = false,
                plainWords = text,
                host = null,
            )
        }
    }
    val answersFlow = remember(host.summaries, host.serverId, targets) { host.summaries.answersFlow(host.serverId, targets) }
    val initialAnswers =
        remember(host.summaries, host.serverId, targets) {
            host.summaries.state.value.answersFor(host.serverId, targets)
        }
    val answers by answersFlow.collectAsState(initial = initialAnswers)
    LaunchedEffect(host.summaries, host.serverId, targets) {
        host.summaries.request(host.serverId, targets)
    }
    val segments =
        remember(pieces, appHosts, answers) {
            resolveProfileDescriptionSegments(pieces, appHosts, answers, enabled = true)
        }
    val plainWords = remember(segments) { profileDescriptionPlainWords(segments) }
    val hasArtifacts = remember(segments) { segments.any { it is ProfileDescriptionSegment.Artifact } }
    return remember(text, segments, hasArtifacts, plainWords, host) {
        RaftProfileDescriptionResolved(
            rawText = text,
            segments = segments,
            hasArtifacts = hasArtifacts,
            plainWords = plainWords,
            host = host,
        )
    }
}

internal data class RaftProfileDescriptionUnavailableSpan(
    val start: Int,
    val end: Int,
    val url: String,
)

internal data class RaftProfileDescriptionAnnotatedContent(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
    val interactiveChipRanges: List<IntRange>,
    val unavailableSpans: List<RaftProfileDescriptionUnavailableSpan>,
)

internal fun raftProfileDescriptionChipBoxStyle(
    info: Boolean,
    compact: Boolean,
    dimmed: Boolean = false,
): RaftInlineBoxStyle {
    val fill =
        when {
            info -> RaftColor.InfoSoft
            dimmed -> RaftColor.InactiveSoft.copy(alpha = ProfileDescriptionDeletedAlpha)
            else -> RaftColor.InactiveSoft
        }
    val verticalPadding = if (compact) 1.dp else 1.5.dp
    return RaftInlineBoxStyle(
        backgroundColor = fill,
        paddingStart = 4.5.dp,
        paddingEnd = 4.5.dp,
        paddingTop = verticalPadding,
        paddingBottom = verticalPadding,
        marginStart = RaftRichTextInlineChipMarginDp.dp,
        marginEnd = RaftRichTextInlineChipMarginDp.dp,
        cornerRadius = RaftAppRadius.Sticker,
    )
}

private fun profileDescriptionChipLinkStyles(ink: Color): TextLinkStyles {
    val base =
        raftSpanStyleForWeight(FontWeight.SemiBold).copy(
            color = ink,
            textDecoration = null,
            background = Color.Unspecified,
        )
    return raftChipLinkStyles(base, pressed = base.copy(background = RaftAppThemeState.colors.fillMedium))
}

/**
 * Pure builder turning resolved [segments] into an [AnnotatedString] + [InlineTextContent] map
 * with inline Artifact chips, painted padding gap links, optional non-chip [onTextClick] fallback,
 * and unavailable chip ranges for desktop pointer hover cards.
 */
internal fun buildRaftProfileDescriptionAnnotatedContent(
    segments: List<ProfileDescriptionSegment>,
    placeholder: String,
    deletedLabel: String,
    compact: Boolean,
    interactive: Boolean,
    onOpenArtifact: (ArtifactLinkTarget, ArtifactSummary?) -> Unit,
    onTextClick: (() -> Unit)? = null,
): RaftProfileDescriptionAnnotatedContent {
    val inlineContent = linkedMapOf<String, InlineTextContent>()
    val iconSizeSp = if (compact) 10.sp else 12.sp
    val iconSizeDp = if (compact) 10.dp else 12.dp
    val suffixFontSizeSp = if (compact) 9.5.sp else 10.5.sp

    val rawAnnotated =
        buildAnnotatedString {
            segments.forEachIndexed { index, segment ->
                when (segment) {
                    is ProfileDescriptionSegment.Text -> append(segment.text)
                    is ProfileDescriptionSegment.Artifact.Available -> {
                        val box = raftProfileDescriptionChipBoxStyle(info = true, compact = compact)
                        val iconId = "raft-profile-artifact-$index-icon"
                        val iconGapId = "raft-profile-artifact-$index-gap"
                        val label = raftAppLinkCap(segment.summary.title.ifBlank { segment.target.id }, RaftProfileDescriptionChipMaxChars)
                        val annotation =
                            if (interactive) {
                                LinkAnnotation.Clickable(
                                    tag = RaftProfileDescriptionArtifactTag,
                                    styles = profileDescriptionChipLinkStyles(RaftColor.TextBlack),
                                    linkInteractionListener = LinkInteractionListener { onOpenArtifact(segment.target, segment.summary) },
                                )
                            } else {
                                null
                            }
                        withRaftInlineChipGaps(box) {
                            withRaftInlineBox(box) {
                                val chipContent: AnnotatedString.Builder.() -> Unit = {
                                    withStyle(SpanStyle(fontSize = RAFT_RICHTEXT_INLINE_CHIP_FONT_SIZE_EM.em)) {
                                        appendInlineContent(iconId, "\uFFFC")
                                        append(RaftInlineWordJoiner)
                                        appendInlineContent(iconGapId, "\uFFFC")
                                        append(RaftInlineWordJoiner)
                                        withStyle(raftSpanStyleForWeight(FontWeight.SemiBold).copy(color = RaftColor.TextBlack)) {
                                            append(raftAppLinkUnbreakable(label))
                                        }
                                    }
                                }
                                if (annotation != null) withLink(annotation) { chipContent() } else chipContent()
                            }
                        }
                        inlineContent[iconId] =
                            profileDescriptionChipIconInlineContent(
                                icon = segment.summary.kind.lucideIcon(),
                                color = RaftColor.ForegroundMuted,
                                sizeSp = iconSizeSp,
                                sizeDp = iconSizeDp,
                            )
                        inlineContent[iconGapId] = profileDescriptionSpacerInlineContent(4.sp)
                    }
                    is ProfileDescriptionSegment.Artifact.Loading -> {
                        val box = raftProfileDescriptionChipBoxStyle(info = false, compact = compact)
                        val iconId = "raft-profile-artifact-$index-icon"
                        val iconGapId = "raft-profile-artifact-$index-gap"
                        val annotation =
                            if (interactive) {
                                LinkAnnotation.Clickable(
                                    tag = RaftProfileDescriptionArtifactPendingTag,
                                    styles = profileDescriptionChipLinkStyles(RaftColor.ForegroundMuted),
                                    linkInteractionListener = LinkInteractionListener { onOpenArtifact(segment.target, null) },
                                )
                            } else {
                                null
                            }
                        withRaftInlineChipGaps(box) {
                            withRaftInlineBox(box) {
                                val chipContent: AnnotatedString.Builder.() -> Unit = {
                                    withStyle(SpanStyle(fontSize = RAFT_RICHTEXT_INLINE_CHIP_FONT_SIZE_EM.em)) {
                                        appendInlineContent(iconId, "\uFFFC")
                                        append(RaftInlineWordJoiner)
                                        appendInlineContent(iconGapId, "\uFFFC")
                                        append(RaftInlineWordJoiner)
                                        withStyle(raftSpanStyleForWeight(FontWeight.SemiBold).copy(color = RaftColor.ForegroundMuted)) {
                                            append(raftAppLinkUnbreakable(placeholder))
                                        }
                                    }
                                }
                                if (annotation != null) withLink(annotation) { chipContent() } else chipContent()
                            }
                        }
                        inlineContent[iconId] =
                            profileDescriptionChipIconInlineContent(
                                icon = RaftLucideIconName.FileText,
                                color = RaftColor.ForegroundMuted,
                                sizeSp = iconSizeSp,
                                sizeDp = iconSizeDp,
                            )
                        inlineContent[iconGapId] = profileDescriptionSpacerInlineContent(4.sp)
                    }
                    is ProfileDescriptionSegment.Artifact.Unavailable -> {
                        val box = raftProfileDescriptionChipBoxStyle(info = false, compact = compact)
                        val iconId = "raft-profile-artifact-$index-icon"
                        val iconGapId = "raft-profile-artifact-$index-gap"
                        val annotation =
                            if (interactive) {
                                LinkAnnotation.Clickable(
                                    tag = RaftProfileDescriptionArtifactUnavailableTag,
                                    styles = profileDescriptionChipLinkStyles(RaftColor.ForegroundMuted),
                                    linkInteractionListener = LinkInteractionListener { onOpenArtifact(segment.target, null) },
                                )
                            } else {
                                null
                            }
                        withRaftInlineChipGaps(box) {
                            withRaftInlineBox(box) {
                                pushStringAnnotation(RaftProfileDescriptionUnavailableHoverTag, segment.url)
                                val chipContent: AnnotatedString.Builder.() -> Unit = {
                                    withStyle(SpanStyle(fontSize = RAFT_RICHTEXT_INLINE_CHIP_FONT_SIZE_EM.em)) {
                                        appendInlineContent(iconId, "\uFFFC")
                                        append(RaftInlineWordJoiner)
                                        appendInlineContent(iconGapId, "\uFFFC")
                                        append(RaftInlineWordJoiner)
                                        withStyle(raftSpanStyleForWeight(FontWeight.SemiBold).copy(color = RaftColor.ForegroundMuted)) {
                                            append(raftAppLinkUnbreakable(placeholder))
                                        }
                                    }
                                }
                                if (annotation != null) withLink(annotation) { chipContent() } else chipContent()
                                pop()
                            }
                        }
                        inlineContent[iconId] =
                            profileDescriptionChipIconInlineContent(
                                icon = RaftLucideIconName.FileText,
                                color = RaftColor.ForegroundMuted,
                                sizeSp = iconSizeSp,
                                sizeDp = iconSizeDp,
                            )
                        inlineContent[iconGapId] = profileDescriptionSpacerInlineContent(4.sp)
                    }
                    is ProfileDescriptionSegment.Artifact.Deleted -> {
                        val box = raftProfileDescriptionChipBoxStyle(info = false, compact = compact, dimmed = true)
                        val iconId = "raft-profile-artifact-$index-icon"
                        val iconGapId = "raft-profile-artifact-$index-gap"
                        val suffixGapId = "raft-profile-artifact-$index-suffix-gap"
                        val ink = RaftColor.ForegroundMuted.copy(alpha = ProfileDescriptionDeletedAlpha)
                        val label = raftAppLinkCap(segment.deleted.title.ifBlank { segment.target.id }, RaftProfileDescriptionChipMaxChars)
                        withRaftInlineChipGaps(box) {
                            withRaftInlineBox(box) {
                                withStyle(SpanStyle(fontSize = RAFT_RICHTEXT_INLINE_CHIP_FONT_SIZE_EM.em)) {
                                    appendInlineContent(iconId, "\uFFFC")
                                    append(RaftInlineWordJoiner)
                                    appendInlineContent(iconGapId, "\uFFFC")
                                    append(RaftInlineWordJoiner)
                                    withStyle(raftSpanStyleForWeight(FontWeight.SemiBold).copy(color = ink)) {
                                        append(raftAppLinkUnbreakable(label))
                                    }
                                    append(RaftInlineWordJoiner)
                                    appendInlineContent(suffixGapId, " ")
                                    append(RaftInlineWordJoiner)
                                    withStyle(
                                        SpanStyle(
                                            color = ink,
                                            fontSize = suffixFontSizeSp,
                                            fontWeight = FontWeight.Normal,
                                            fontFamily = raftMonoFamilyForWeight(FontWeight.Normal),
                                        ),
                                    ) {
                                        append(raftAppLinkUnbreakable(deletedLabel))
                                    }
                                }
                            }
                        }
                        inlineContent[iconId] =
                            profileDescriptionChipIconInlineContent(
                                icon = RaftLucideIconName.FileX,
                                color = ink,
                                sizeSp = iconSizeSp,
                                sizeDp = iconSizeDp,
                            )
                        inlineContent[iconGapId] = profileDescriptionSpacerInlineContent(4.sp)
                        inlineContent[suffixGapId] = profileDescriptionSpacerInlineContent(4.sp)
                    }
                }
            }
        }

    // Painted chip padding taps as the chip, and any surrounding plain text (or deleted chip) still
    // dispatches [onTextClick] when supplied (e.g. author subtitle opening its full popover).
    val withGapLinks = rawAnnotated.withRaftInlineChipGapLinks()
    val resolvedText =
        if (interactive && onTextClick != null) {
            withGapLinks.withRaftMarkdownBodyClick(onTextClick)
        } else {
            withGapLinks
        }
    val allInlineContent = inlineContent + raftInlineGapInlineContent(resolvedText.raftMarkdownInlineContentIds())
    // Interactive chips exclude deleted chips (deleted chips have no link and no hover card, matching
    // web `CHIP_SELECTOR = '[data-app-link-chip]:not([data-app-link-chip="deleted"])'`).
    val chipExtents = withGapLinks.raftInlineChipExtents()
    val interactiveLinkRanges =
        withGapLinks.getLinkAnnotations(0, withGapLinks.length).map { it.start until it.end }
    val interactiveChipRanges =
        chipExtents.filter { extent ->
            interactiveLinkRanges.any { link -> link.first <= extent.last && extent.first <= link.last }
        }
    val unavailableSpans =
        resolvedText
            .getStringAnnotations(RaftProfileDescriptionUnavailableHoverTag, 0, resolvedText.length)
            .filter { it.end > it.start }
            .map { range ->
                val extent = chipExtents.firstOrNull { range.start in it || (range.end - 1) in it }
                if (extent != null) {
                    RaftProfileDescriptionUnavailableSpan(extent.first, extent.last + 1, range.item)
                } else {
                    RaftProfileDescriptionUnavailableSpan(range.start, range.end, range.item)
                }
            }
            .sortedBy { it.start }
    return RaftProfileDescriptionAnnotatedContent(
        text = resolvedText,
        inlineContent = allInlineContent,
        interactiveChipRanges = interactiveChipRanges,
        unavailableSpans = unavailableSpans,
    )
}

private fun profileDescriptionChipIconInlineContent(
    icon: RaftLucideIconName,
    color: Color,
    sizeSp: TextUnit,
    sizeDp: androidx.compose.ui.unit.Dp,
): InlineTextContent =
    InlineTextContent(
        placeholder = Placeholder(width = sizeSp, height = sizeSp, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
    ) {
        Box(Modifier.size(sizeDp), contentAlignment = Alignment.Center) {
            RaftLucideIcon(
                name = icon,
                modifier = Modifier.size(sizeDp),
                color = raftArgbHex(color),
                strokeWidth = 2f,
            )
        }
    }

private fun profileDescriptionSpacerInlineContent(width: TextUnit): InlineTextContent =
    InlineTextContent(
        placeholder = Placeholder(width = width, height = 1.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
    ) {}

/**
 * Renders a plain-text profile description (or author subtitle) with inline Artifact title chips.
 *
 * - When no Artifact links are present (or Workspace Artifacts is off / share capture is active),
 *   renders a plain [RaftText] with zero chip overhead.
 * - When [enableHoverCards] is false (e.g. inside `ThreadMessageHeaderRow` with
 *   `Modifier.alignByBaseline()`), emits a single [RaftText] with no wrapper `Box`.
 * - When [enableHoverCards] is true, tracks mouse hover over unavailable chips to open the #15230
 *   unavailable hint card (`ArtifactUnavailableLinkPreview`) and reports [onPointerOverInteractiveChipChange]
 *   so an enclosing description tooltip stays closed over interactive chips.
 */
@Composable
internal fun RaftProfileDescriptionText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    compact: Boolean = false,
    enabled: Boolean = true,
    interactive: Boolean = true,
    source: String = "profile_description",
    onTextClick: (() -> Unit)? = null,
    onBeforeOpenArtifact: (() -> Unit)? = null,
    enableHoverCards: Boolean = false,
    onPointerOverInteractiveChipChange: ((Boolean) -> Unit)? = null,
    resolvedOverride: RaftProfileDescriptionResolved? = null,
) {
    val resolved = resolvedOverride ?: rememberProfileDescriptionResolved(text = text, enabled = enabled)
    val host = resolved.host
    if (!resolved.hasArtifacts || host == null) {
        val plainModifier =
            if (interactive && onTextClick != null) {
                modifier.raftClickable(onClick = onTextClick, shape = RoundedCornerShape(RaftAppRadius.Sm), tier = RaftHoverTier.Subtle)
            } else {
                modifier
            }
        RaftText(
            text = text,
            modifier = plainModifier,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
        )
        return
    }

    val placeholder = stringResource(Res.string.artifacts_chip_placeholder)
    val deletedLabel = stringResource(Res.string.artifacts_chip_deleted)
    val overrideOpen = host.onOpenArtifact
    val openArtifact: (ArtifactLinkTarget, ArtifactSummary?) -> Unit =
        if (overrideOpen != null) {
            remember(overrideOpen, onBeforeOpenArtifact) {
                { target, summary ->
                    onBeforeOpenArtifact?.invoke()
                    overrideOpen(target, summary)
                }
            }
        } else {
            val openWebPage = rememberRaftWebPageOpener()
            val opener = rememberRaftArtifactOpener { target -> openWebPage(artifactWebUrl(host.webBaseUrl, target)) }
            remember(opener, source, onBeforeOpenArtifact) {
                { target, summary ->
                    onBeforeOpenArtifact?.invoke()
                    opener.open(target, summary?.let { RaftArtifactViewerHeader.of(it, target) }, source)
                }
            }
        }
    val themeColors = RaftTheme.colors
    val content =
        remember(resolved.segments, placeholder, deletedLabel, compact, interactive, openArtifact, onTextClick, themeColors) {
            buildRaftProfileDescriptionAnnotatedContent(
                segments = resolved.segments,
                placeholder = placeholder,
                deletedLabel = deletedLabel,
                compact = compact,
                interactive = interactive,
                onOpenArtifact = openArtifact,
                onTextClick = onTextClick,
            )
        }
    val decorations = remember(content.text, style.fontSize) { content.text.raftInlineDecorations(style.fontSize) }
    val readableText = remember(content.text) { content.text.raftTextWithoutInlineGaps().replace('\u00A0', ' ') }
    var textLayout by remember(content.text) { mutableStateOf<TextLayoutResult?>(null) }

    // Prewarm the in-app Artifact viewer ahead of a tap on live pages (skip when tests supply onOpenArtifact).
    if (overrideOpen == null) {
        val hasClickableArtifact =
            remember(resolved.segments) {
                resolved.segments.any { it is ProfileDescriptionSegment.Artifact && it !is ProfileDescriptionSegment.Artifact.Deleted }
            }
        RaftArtifactViewerPrewarm(enabled = interactive && hasClickableArtifact)
    }

    if (!enableHoverCards) {
        RaftText(
            text = content.text,
            modifier =
                modifier
                    .raftProfileDescriptionReadableSemantics(readableText)
                    .raftInlineDecorations(decorations) { textLayout },
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            inlineContent = content.inlineContent,
            onTextLayout = { result -> textLayout = result },
        )
        return
    }

    val hoverCards =
        rememberRaftProfileDescriptionHoverCards(
            unavailableSpans = content.unavailableSpans,
            interactiveChipRanges = content.interactiveChipRanges,
            onPointerOverInteractiveChipChange = onPointerOverInteractiveChipChange,
        )
    Box(modifier, propagateMinConstraints = true) {
        RaftText(
            text = content.text,
            modifier =
                Modifier
                    .raftProfileDescriptionReadableSemantics(readableText)
                    .raftProfileDescriptionHover(hoverCards)
                    .raftInlineDecorations(decorations) { textLayout },
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            inlineContent = content.inlineContent,
            onTextLayout = { result ->
                textLayout = result
                hoverCards.layout = result
            },
        )
        hoverCards.Popups()
    }
}

private fun Modifier.raftProfileDescriptionReadableSemantics(readable: String): Modifier =
    semantics { this.text = AnnotatedString(readable) }

/**
 * Profile preview card's description block (web `ProfilePreviewCardContent` + `ProfileDescription`):
 * clamped to 2 lines with inline Artifact chips, tooltip showing resolved titles over plain text,
 * and suppressing the description tooltip while the mouse pointer rests on an interactive chip.
 */
@Composable
internal fun RaftProfilePreviewDescription(
    description: String,
    modifier: Modifier = Modifier,
) {
    val resolved = rememberProfileDescriptionResolved(text = description, enabled = true)
    if (!resolved.hasArtifacts) {
        RaftTooltip(text = description, modifier = modifier) {
            RaftPreviewCardDescription(description, maxLines = 2)
        }
        return
    }
    var overInteractiveChip by remember(description) { mutableStateOf(false) }
    val body =
        remember(description) {
            movableContentOf<RaftProfileDescriptionResolved> { currentResolved ->
                RaftProfileDescriptionText(
                    text = description,
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    source = "profile_preview_card",
                    enableHoverCards = true,
                    onPointerOverInteractiveChipChange = { overInteractiveChip = it },
                    resolvedOverride = currentResolved,
                )
            }
        }
    if (!overInteractiveChip) {
        RaftTooltip(text = resolved.plainWords, modifier = modifier) { body(resolved) }
    } else {
        Box(modifier, propagateMinConstraints = true) { body(resolved) }
    }
}

@Stable
private class RaftProfileDescriptionHoverCards(
    val unavailableSpans: List<RaftProfileDescriptionUnavailableSpan>,
    val interactiveChipRanges: List<IntRange>,
    val states: List<RaftPreviewCardState>,
    private val onPointerOverInteractiveChipChange: ((Boolean) -> Unit)?,
) {
    var layout: TextLayoutResult? = null
    private val anchors = arrayOfNulls<Rect>(unavailableSpans.size)
    private var lastOverInteractive = false

    fun hover(position: Offset?) {
        val currentLayout = layout
        val textLength = currentLayout?.layoutInput?.text?.length ?: 0
        val visibleEnd =
            if (currentLayout != null && currentLayout.lineCount > 0) {
                currentLayout.getLineEnd(currentLayout.lineCount - 1, visibleEnd = true).coerceIn(0, textLength)
            } else {
                0
            }
        val hitOffset =
            if (position == null || currentLayout == null || visibleEnd <= 0) {
                null
            } else {
                val caret = currentLayout.getOffsetForPosition(position)
                intArrayOf(caret, caret - 1)
                    .firstOrNull { offset ->
                        offset in 0 until visibleEnd && currentLayout.getBoundingBox(offset).contains(position)
                    }
            }
        val overInteractive = hitOffset != null && interactiveChipRanges.any { hitOffset in it }
        if (overInteractive != lastOverInteractive) {
            lastOverInteractive = overInteractive
            onPointerOverInteractiveChipChange?.invoke(overInteractive)
        }
        val hitUnavailable =
            if (hitOffset == null) {
                null
            } else {
                unavailableSpans.indexOfFirst { hitOffset >= it.start && hitOffset < it.end }.takeIf { it >= 0 }
            }
        states.forEachIndexed { index, state ->
            val over = index == hitUnavailable
            if (over && !state.isTriggerHovered && currentLayout != null && visibleEnd > 0) {
                val span = unavailableSpans[index]
                val from = span.start.coerceIn(0, visibleEnd - 1)
                val to = span.end.coerceIn(from + 1, visibleEnd)
                anchors[index] = currentLayout.getPathForRange(from, to).getBounds()
            }
            if (state.isTriggerHovered != over) state.setTriggerHovered(over)
        }
    }

    fun press() {
        states.forEach { if (it.isTriggerHovered) it.dismiss() }
    }

    @Composable
    fun Popups() {
        unavailableSpans.forEachIndexed { index, span ->
            val state = states[index]
            if (!state.isTriggerHovered && !state.isOpen) return@forEachIndexed
            key(span) {
                RaftPreviewCardPopup(
                    state = state,
                    anchor = anchors[index],
                    width = RaftPreviewCardDefaults.ItemWidth,
                ) {
                    RaftProfileDescriptionUnavailableCard()
                }
            }
        }
    }
}

@Composable
private fun rememberRaftProfileDescriptionHoverCards(
    unavailableSpans: List<RaftProfileDescriptionUnavailableSpan>,
    interactiveChipRanges: List<IntRange>,
    onPointerOverInteractiveChipChange: ((Boolean) -> Unit)?,
): RaftProfileDescriptionHoverCards {
    val states = unavailableSpans.map { span -> key(span) { rememberRaftPreviewCardState() } }
    return remember(unavailableSpans, interactiveChipRanges, states, onPointerOverInteractiveChipChange) {
        RaftProfileDescriptionHoverCards(
            unavailableSpans = unavailableSpans,
            interactiveChipRanges = interactiveChipRanges,
            states = states,
            onPointerOverInteractiveChipChange = onPointerOverInteractiveChipChange,
        )
    }
}

private fun Modifier.raftProfileDescriptionHover(cards: RaftProfileDescriptionHoverCards): Modifier =
    pointerInput(cards) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move -> cards.hover(change.position)
                    PointerEventType.Exit -> cards.hover(null)
                    PointerEventType.Press, PointerEventType.Scroll -> cards.press()
                }
            }
        }
    }

/**
 * Hover card body + footer for an Artifact link in a profile description the viewer cannot read
 * (web `ArtifactUnavailableLinkPreview` + `LinkCardFooter`, botiverse/slock #15230): says only
 * that the viewer can't see it and that opening it says why — never the title or raw address.
 */
@Composable
private fun RaftProfileDescriptionUnavailableCard() {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    RaftPreviewCardBody(modifier = Modifier.testTag(RaftProfileDescriptionUnavailableCardTestTag)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RaftLucideIcon(
                name = RaftLucideIconName.FileText,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
                color = raftArgbHex(colors.foregroundMuted),
            )
            RaftText(
                text = stringResource(Res.string.artifacts_chip_description_unavailable_hint),
                style = typography.footnote,
                color = colors.foreground,
                modifier = Modifier.weight(1f),
            )
        }
    }
    RaftPreviewCardSeparator()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(
            text = stringResource(Res.string.artifacts_app_name),
            style = typography.caption,
            color = colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
