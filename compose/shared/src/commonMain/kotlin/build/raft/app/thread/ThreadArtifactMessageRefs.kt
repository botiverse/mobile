package build.raft.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ArtifactDeletedShown
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactScreenshotReader
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.artifactScreenshotFile
import build.raft.app.data.artifactWebUrl
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftAppLinkCardDefaults
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftFilePreview
import build.raft.designsystem.components.RaftFilePreviewKind
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftMessageEmbed
import build.raft.designsystem.components.RaftMessageEmbedContent
import build.raft.designsystem.components.RaftMessageEmbedFooter
import build.raft.designsystem.components.RaftMessageEmbedHeader
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.raftGrayscale
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftOpacity
import build.raft.designsystem.tokens.RaftStroke
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlin.math.roundToInt

// web `ArtifactMessageRefs.tsx` / `ArtifactMessageCard.tsx` (botiverse/slock staging 84e41d9be):
// the cards under a message for the Artifacts its text links to, mounted by `MessageItem.tsx`
// right under the body (before a quoted permalink's preview), not for a forwarded bundle or a
// read-only projection, never in a share image. The links stay in the text as their chips.

/** One drawn reference under a message: an `ok` card or a deleted one. */
internal sealed interface ThreadArtifactMessageRef {
    /** web: `<id>@<version|latest>#<index>`, so a repeat is its own entry. */
    val key: String

    data class Card(override val key: String, val target: ArtifactLinkTarget, val summary: ArtifactSummary) : ThreadArtifactMessageRef

    data class Deleted(override val key: String, val deleted: ArtifactDeletedShown) : ThreadArtifactMessageRef
}

/** What goes under a message for its Artifact links. */
@Immutable
internal sealed interface ThreadArtifactMessageRefsView {
    /** No Artifact link (flag off, none on the app hosts), or none this viewer can be shown. */
    data object None : ThreadArtifactMessageRefsView

    /** Any reference still loading: one skeleton in the card's shape. */
    data object Loading : ThreadArtifactMessageRefsView

    data class Cards(val refs: List<ThreadArtifactMessageRef>) : ThreadArtifactMessageRefsView
}

/**
 * web `ArtifactMessageRefs` over core's `artifactLinkOf`: [targets] are the message's references in
 * order (each once, at most ten), [answers] what is known of them by key. While any has no answer
 * yet, [ThreadArtifactMessageRefsView.Loading]. Then one card per `ok`, a gray card per `deleted`;
 * `unavailable` and a failed read add nothing (the link in the text is all such a viewer gets, F11).
 */
internal fun threadArtifactMessageRefsView(
    targets: List<ArtifactLinkTarget>,
    answers: Map<String, ArtifactSummaryAnswer>,
): ThreadArtifactMessageRefsView {
    if (targets.isEmpty()) return ThreadArtifactMessageRefsView.None
    if (targets.any { answers[it.key] == null }) return ThreadArtifactMessageRefsView.Loading
    val refs =
        targets.mapIndexedNotNull { index, target ->
            val key = "${target.canonicalId}@${target.version ?: "latest"}#$index"
            when (val answer = answers[target.key]) {
                is ArtifactSummaryAnswer.Ok -> ThreadArtifactMessageRef.Card(key, target, answer.summary)
                is ArtifactSummaryAnswer.Deleted -> ThreadArtifactMessageRef.Deleted(key, answer.deleted)
                else -> null
            }
        }
    return if (refs.isEmpty()) ThreadArtifactMessageRefsView.None else ThreadArtifactMessageRefsView.Cards(refs)
}

/** What a card's tap opens (web `openArtifactCardInPlace`): the card's id, at its version only when pinned. */
internal fun ArtifactSummary.openTarget(): ArtifactLinkTarget = ArtifactLinkTarget(id, if (pinned) version else null)

/** One part of a card's fact line (web `artifactFacts.ts`), localized by [localized]. */
internal sealed interface ArtifactFactPart {
    data class LatestIs(val version: Int) : ArtifactFactPart
    data class Lines(val count: Int) : ArtifactFactPart
    data class Rows(val count: Int) : ArtifactFactPart
    data class RowsColumns(val rows: Int, val columns: Int) : ArtifactFactPart
    data class ColumnNames(val names: List<String>) : ArtifactFactPart
    data class Pixels(val width: Int, val height: Int) : ArtifactFactPart
    data class Pages(val count: Int) : ArtifactFactPart
    data class Size(val bytes: Long) : ArtifactFactPart
}

/**
 * web `formatArtifactFacts`: the kind's own facts ("6 pages", "5 rows × 4 columns · a, b",
 * "1600 × 1000", "214 lines"), else the size. It never names the kind; HTML and Markdown have none.
 */
internal fun artifactKindFactParts(summary: ArtifactSummary): List<ArtifactFactPart> {
    val kind = summary.kind
    val facts = summary.facts
    if (kind == ArtifactKind.Html || kind == ArtifactKind.Markdown) return emptyList()
    val parts = mutableListOf<ArtifactFactPart>()
    if (kind == ArtifactKind.Text) facts.lines?.let { parts += ArtifactFactPart.Lines(it) }
    if (kind == ArtifactKind.Csv && facts.rows != null) {
        parts += facts.columns?.let { ArtifactFactPart.RowsColumns(facts.rows, it) } ?: ArtifactFactPart.Rows(facts.rows)
    }
    if (kind == ArtifactKind.Csv && facts.columnNames.isNotEmpty()) parts += ArtifactFactPart.ColumnNames(facts.columnNames)
    if (kind == ArtifactKind.Image && (facts.width ?: 0) != 0 && (facts.height ?: 0) != 0) {
        parts += ArtifactFactPart.Pixels(facts.width!!, facts.height!!)
    }
    if (kind == ArtifactKind.Pdf) facts.pages?.let { parts += ArtifactFactPart.Pages(it) }
    if (parts.isEmpty()) parts += ArtifactFactPart.Size(facts.sizeBytes)
    return parts
}

/**
 * web `formatArtifactCardFacts`: a pinned card that is not the latest says so first ("Latest is
 * v5"), then the kind's facts.
 */
internal fun artifactCardFactParts(summary: ArtifactSummary): List<ArtifactFactPart> {
    val latest = summary.latestVersion.takeIf { summary.pinned && it > summary.version }?.let { ArtifactFactPart.LatestIs(it) }
    return listOfNotNull(latest) + artifactKindFactParts(summary)
}

/** web `formatBytes`: bytes under 1 KB, whole KB under 1 MB, else MB to one decimal. */
internal fun artifactSizeValue(bytes: Long): Pair<String, Int> =
    when {
        bytes < 1024 -> bytes.toString() to 0
        bytes < 1024 * 1024 -> ((bytes + 512) / 1024).toString() to 1
        else -> {
            val tenths = (bytes * 10 + 512 * 1024) / (1024 * 1024)
            "${tenths / 10}.${tenths % 10}" to 2
        }
    }

@Composable
private fun ArtifactFactPart.localized(): String =
    when (this) {
        is ArtifactFactPart.LatestIs -> stringResource(Res.string.artifacts_card_latest_is, version)
        is ArtifactFactPart.Lines ->
            if (count == 1) stringResource(Res.string.artifacts_facts_lines_one) else stringResource(Res.string.artifacts_facts_lines_many, count)
        is ArtifactFactPart.Rows -> rowsText(count)
        is ArtifactFactPart.RowsColumns -> stringResource(Res.string.artifacts_facts_rows_columns, rowsText(rows), columnsText(columns))
        is ArtifactFactPart.ColumnNames -> {
            // A pair message, not a list join: the catalog says "、" / ", ".
            var list = names.first()
            for (name in names.drop(1)) list = stringResource(Res.string.artifacts_facts_column_pair, list, name)
            list
        }
        is ArtifactFactPart.Pixels -> stringResource(Res.string.artifacts_facts_pixels, width, height)
        is ArtifactFactPart.Pages ->
            if (count == 1) stringResource(Res.string.artifacts_facts_pages_one) else stringResource(Res.string.artifacts_facts_pages_many, count)
        is ArtifactFactPart.Size -> {
            val (value, unit) = artifactSizeValue(bytes)
            when (unit) {
                0 -> stringResource(Res.string.artifacts_facts_bytes, value)
                1 -> stringResource(Res.string.artifacts_facts_kilobytes, value)
                else -> stringResource(Res.string.artifacts_facts_megabytes, value)
            }
        }
    }

@Composable
private fun rowsText(count: Int): String =
    if (count == 1) stringResource(Res.string.artifacts_facts_rows_one) else stringResource(Res.string.artifacts_facts_rows_many, count)

@Composable
private fun columnsText(count: Int): String =
    if (count == 1) stringResource(Res.string.artifacts_facts_columns_one) else stringResource(Res.string.artifacts_facts_columns_many, count)

@Composable
private fun List<ArtifactFactPart>.localizedLine(): String? = takeIf { it.isNotEmpty() }?.map { it.localized() }?.joinToString(" · ")

/** A relative time ("2 hours ago"), or null for a missing or unreadable one (web `formatRelativeTime`). */
@Composable
private fun artifactRelativeTime(value: String?): String? {
    if (value.isNullOrBlank() || parseRaftTimestampEpochMillisOrNull(value) == null) return null
    return raftRelativeTimestampLabel(value, clampFuture = true).takeIf { it.isNotBlank() }
}

/**
 * The Artifact cards under one message (web `ArtifactMessageRefs`): read from the same answers as
 * the chips in its text ([rememberThreadArtifactLinkAnswers]; nothing more is asked). [enabled] is
 * false where the web draws none (a message not sent yet); a share image never has them.
 */
@Composable
internal fun ThreadArtifactMessageRefs(
    messageId: String,
    body: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val host = LocalThreadAppLinkPreviewHost.current ?: return
    val artifacts = host.artifacts ?: return
    if (!threadAppLinkPreviewsEnabled(enabled, LocalThreadShareCaptureActive.current) || !messageId.isAppLinkMessageId()) return
    val links = rememberThreadArtifactLinkAnswers(artifacts, host.serverId, body)
    val view = remember(links) { threadArtifactMessageRefsView(links.targets, links.answers) }
    if (view == ThreadArtifactMessageRefsView.None) return
    val openArtifactWebPage = rememberRaftWebPageOpener()
    val webBaseUrl = artifacts.webBaseUrl
    // A plain tap opens it in place (the in-app viewer); the browser only when no page can open.
    val opener = rememberRaftArtifactOpener { openArtifactWebPage(artifactWebUrl(webBaseUrl, it)) }
    val screenshots = remember(artifacts.screenshots, host.serverId) { artifacts.screenshots?.reader(host.serverId) }
    RaftDesignSystemTheme {
        CompositionLocalProvider(LocalArtifactScreenshotReader provides screenshots) {
            ThreadArtifactMessageRefsContent(
                view = view,
                onOpen = { summary ->
                    val target = summary.openTarget()
                    opener.open(target, RaftArtifactViewerHeader.of(summary, target), "message_card")
                },
                onOpenInNewPage = { summary -> openArtifactWebPage(artifactWebUrl(webBaseUrl, summary.openTarget())) },
                modifier = modifier,
            )
        }
    }
}

/** The cards (web `mt-2 flex flex-col gap-2`): 8dp under the body, 8dp apart. */
@Composable
internal fun ThreadArtifactMessageRefsContent(
    view: ThreadArtifactMessageRefsView,
    onOpen: (ArtifactSummary) -> Unit,
    onOpenInNewPage: (ArtifactSummary) -> Unit,
    modifier: Modifier = Modifier,
    skeletonAnimated: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (view) {
            ThreadArtifactMessageRefsView.None -> Unit
            ThreadArtifactMessageRefsView.Loading -> ArtifactMessageCardLoading(skeletonAnimated)
            is ThreadArtifactMessageRefsView.Cards ->
                view.refs.forEach { ref ->
                    key(ref.key) {
                        when (ref) {
                            is ThreadArtifactMessageRef.Card ->
                                ArtifactMessageCard(ref.summary, onOpen = { onOpen(ref.summary) }, onOpenInNewPage = { onOpenInNewPage(ref.summary) })
                            is ThreadArtifactMessageRef.Deleted -> ArtifactDeletedCard(ref.deleted)
                        }
                    }
                }
        }
    }
}

/**
 * Where a card's screenshot is read (the Server on screen's reader of
 * [build.raft.app.data.SharedArtifactScreenshotStore]); null where none is read (fixtures without
 * one): the card then shows without it.
 */
internal val LocalArtifactScreenshotReader = staticCompositionLocalOf<ArtifactScreenshotReader?> { null }

/** web `PHONE_QUERY` `(max-width: 767px)`: a phone-width layout shows a card's phone screenshot. */
internal fun artifactScreenshotPhoneLayout(windowWidth: Dp): Boolean = windowWidth < RaftAppLinkCardDefaults.PhoneBelow

/** web `ArtifactCardPicture` for a screenshot: none, its place kept while it is read, or shown. */
@Immutable
internal sealed interface ArtifactCardScreenshotView {
    /** No screenshot, or it can't be read (no spinner, no error). */
    data object None : ArtifactCardScreenshotView

    /** Being read: the card keeps its place, so the conversation doesn't move when it comes. */
    data object Pending : ArtifactCardScreenshotView

    /** Read: [bytes] of file [fileId] of card [cardId], drawn by the image loader. */
    class Shown(val cardId: String, val fileId: String, val bytes: ByteArray) : ArtifactCardScreenshotView
}

/**
 * web `useArtifactScreenshotSrc`: the card's screenshot for this window's width (the phone shot in
 * a phone-width layout when there is one), read once the card is composed (a lazy list composes
 * only the rows on screen and just past it); one kept already is shown at once.
 */
@Composable
internal fun rememberArtifactCardScreenshot(summary: ArtifactSummary): ArtifactCardScreenshotView {
    val reader = LocalArtifactScreenshotReader.current ?: return ArtifactCardScreenshotView.None
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val fileId = artifactScreenshotFile(summary.screenshot, artifactScreenshotPhoneLayout(windowWidth)) ?: return ArtifactCardScreenshotView.None
    val cardId = summary.id
    var bytes by remember(reader, cardId, fileId) { mutableStateOf(reader.kept(cardId, fileId)) }
    var failed by remember(reader, cardId, fileId) { mutableStateOf(false) }
    LaunchedEffect(reader, cardId, fileId) {
        if (bytes != null) return@LaunchedEffect
        val read = reader.read(cardId, fileId)
        if (read != null) bytes = read else failed = true
    }
    val shown = bytes
    return when {
        shown != null -> ArtifactCardScreenshotView.Shown(cardId, fileId, shown)
        failed -> ArtifactCardScreenshotView.None
        else -> ArtifactCardScreenshotView.Pending
    }
}

/** web `max-h-48`: a card's picture is at most 192dp tall. */
internal val ArtifactCardScreenshotMaxHeight: Dp = 192.dp

/** web `aspect-[16/10] max-h-48 w-full`: the card's width, 16:10, at most [ArtifactCardScreenshotMaxHeight] tall. */
internal fun artifactCardScreenshotHeightPx(widthPx: Int, maxHeightPx: Int): Int = minOf((widthPx * 10f / 16f).roundToInt(), maxHeightPx)

/**
 * web `ArtifactMessageCard`'s cover: edge to edge above the header, `object-cover object-top` (cut
 * from the bottom: the page's top and first screen), a `line-hairline` rule under it; while it is
 * read, the same box empty on `fill-muted`.
 */
@Composable
internal fun ArtifactCardScreenshot(view: ArtifactCardScreenshotView) {
    val colors = RaftTheme.colors
    RaftMessageEmbedContent {
        Box(Modifier.artifactCardScreenshotSize().background(colors.fillMuted)) {
            if (view is ArtifactCardScreenshotView.Shown) {
                val context = LocalPlatformContext.current
                val request =
                    remember(view.cardId, view.fileId, view.bytes, context) {
                        ImageRequest.Builder(context)
                            .data(view.bytes)
                            .memoryCacheKey("artifact-screenshot|${view.cardId}|${view.fileId}")
                            .build()
                    }
                Image(
                    painter = rememberAsyncImagePainter(request),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineHairline))
    }
}

private fun Modifier.artifactCardScreenshotSize(): Modifier =
    layout { measurable, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 440.dp.roundToPx()
        val height = artifactCardScreenshotHeightPx(width, ArtifactCardScreenshotMaxHeight.roundToPx())
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }

/** web `KindGlyph`: HTML's page is code, an image or video's a blank media page, any other a document. */
private fun ArtifactKind.filePreviewKind(): RaftFilePreviewKind =
    when (this) {
        ArtifactKind.Html -> RaftFilePreviewKind.Code
        ArtifactKind.Image, ArtifactKind.Video -> RaftFilePreviewKind.Media
        else -> RaftFilePreviewKind.Document
    }

/**
 * web `ArtifactMessageCard` `OkCard` (F12, F37, F31 A), interactive as a whole (a tap opens it in
 * place). Header: the kind's glyph and badge; the title (`subhead` bold, 2 lines) with its version
 * Badge; the fact line (`footnote` muted, 1 line); the description (`callout` muted, 1 line under
 * a fact line, else 2) or the start of its text (2 lines); ↗ opens its own page. Footer: where it
 * lives, who updated it when (Space Mono hint), and its comment count. Above them, the publish
 * check's screenshot when the card has one ([ArtifactCardScreenshot]), else a Markdown version's
 * excerpt ([ArtifactCardExcerpt], web #13445 / #13472), which then is not repeated as the
 * description; no publisher cover yet.
 */
@Composable
internal fun ArtifactMessageCard(
    summary: ArtifactSummary,
    onOpen: () -> Unit,
    onOpenInNewPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val facts = artifactCardFactParts(summary).localizedLine()
    val hasKindFacts = artifactKindFactParts(summary).isNotEmpty()
    val time = artifactRelativeTime(summary.updatedAt)
    val meta =
        when {
            time != null -> stringResource(Res.string.artifacts_card_updated_by, summary.authorName, time)
            else -> summary.authorName.takeIf { it.isNotBlank() }
        }
    val screenshot = rememberArtifactCardScreenshot(summary)
    // web `useArtifactCardPicture`: a screenshot, else a Markdown version's excerpt (one kind at a
    // time from the server, so an excerpt never waits for or replaces a picture).
    val excerpt = summary.excerpt.takeIf { screenshot == ArtifactCardScreenshotView.None }
    RaftMessageEmbed(modifier = modifier, onClick = onOpen, onClickLabel = summary.title) {
        if (screenshot != ArtifactCardScreenshotView.None) {
            ArtifactCardScreenshot(screenshot)
        } else if (excerpt != null) {
            RaftMessageEmbedContent { ArtifactCardExcerpt(excerpt) }
        }
        RaftMessageEmbedHeader {
            RaftFilePreview(summary.kind.filePreviewKind(), badge = summary.kind.badge)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    RaftText(
                        summary.title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = typography.subhead.copy(fontWeight = FontWeight.Bold),
                        color = colors.foreground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RaftBadge(
                        text = stringResource(Res.string.artifacts_card_version, summary.version),
                        modifier = Modifier.padding(top = 1.dp),
                        appearance = RaftBadgeAppearance.Outline,
                        variant = RaftBadgeVariant.Muted,
                    )
                }
                facts?.let {
                    RaftText(it, style = typography.footnote, color = colors.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // The card's own description only once its excerpt is drawn above (web: `card.description`).
                summary.description?.takeUnless { excerpt != null && summary.descriptionIsExcerpt }?.let {
                    RaftText(
                        it,
                        style = typography.callout,
                        color = colors.foregroundMuted,
                        maxLines = if (!summary.descriptionIsExcerpt && hasKindFacts) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // PanelAction `-mt-1 -mr-1`: its own control, above the card's tap.
            RaftIconButton(
                onClick = onOpenInNewPage,
                tooltip = stringResource(Res.string.artifacts_card_open_in_new_page),
                modifier = Modifier.offset(x = 4.dp, y = (-4).dp),
                variant = RaftButtonVariant.Ghost,
            ) {
                RaftLucideIcon(RaftLucideIconName.ArrowUpRight, Modifier.size(16.dp), color = raftArgbHex(colors.foregroundMuted))
            }
        }
        RaftMessageEmbedFooter {
            ArtifactCardScopeLabel(summary.home)
            meta?.let {
                RaftText(
                    it,
                    modifier = Modifier.weight(1f, fill = false),
                    style = typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary.commentCount > 0) {
                val label =
                    if (summary.commentCount == 1) {
                        stringResource(Res.string.artifacts_card_comments_one)
                    } else {
                        stringResource(Res.string.artifacts_card_comments_many, summary.commentCount)
                    }
                Row(
                    Modifier.semantics(mergeDescendants = true) { contentDescription = label },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftLucideIcon(RaftLucideIconName.MessageSquare, Modifier.size(12.dp), color = raftArgbHex(colors.foregroundHint))
                    RaftText(summary.commentCount.toString(), style = typography.monoMeta, color = colors.foregroundHint, maxLines = 1)
                }
            }
        }
    }
}

/**
 * web `ArtifactScopeLabel onCard`: where it lives, an outline muted Badge with its icon: a channel's
 * name bare (the icon says its kind), a DM by its peer, the whole server, or anyone with the link.
 * Display only here (the web's "who can see it" popover is not on the phone card yet).
 */
@Composable
private fun ArtifactCardScopeLabel(home: ArtifactHome) {
    val ink = RaftTheme.colors.foregroundMuted
    val text =
        when (home) {
            is ArtifactHome.Conversation -> (home.peerDisplayName ?: home.name).takeIf { it.isNotBlank() } ?: return
            ArtifactHome.Server -> stringResource(Res.string.artifacts_scope_server)
            ArtifactHome.Public -> stringResource(Res.string.artifacts_scope_public)
        }
    RaftBadge(
        text = text,
        appearance = RaftBadgeAppearance.Outline,
        variant = RaftBadgeVariant.Muted,
        icon = {
            when {
                home is ArtifactHome.Conversation && home.dm ->
                    RaftConversationKindIcon(RaftConversationKindGlyph.DirectMessage, size = 12.dp, color = ink)
                else -> {
                    val icon =
                        when {
                            home is ArtifactHome.Conversation && home.private -> RaftLucideIconName.Lock
                            home is ArtifactHome.Conversation -> RaftLucideIconName.Hash
                            home == ArtifactHome.Server -> RaftLucideIconName.Users
                            else -> RaftLucideIconName.Globe
                        }
                    RaftLucideIcon(icon, Modifier.size(12.dp), color = raftArgbHex(ink))
                }
            }
        },
    )
}

/**
 * web `ArtifactDeletedCard`: the card's frame and a document glyph, gray and dimmed (DS States ›
 * Dimmed: grayscale at 60%), the title as plain text (`foreground`) and who deleted it when
 * (`foreground-muted`). Nothing in it opens.
 */
@Composable
internal fun ArtifactDeletedCard(deleted: ArtifactDeletedShown, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val time = artifactRelativeTime(deleted.deletedAt)
    val name = deleted.deletedBy
    val meta =
        when {
            name != null && time != null -> stringResource(Res.string.artifacts_card_deleted_by, name, time)
            name != null -> stringResource(Res.string.artifacts_card_deleted_by_name, name)
            time != null -> stringResource(Res.string.artifacts_card_deleted_at, time)
            else -> stringResource(Res.string.artifacts_card_deleted)
        }
    RaftMessageEmbed(modifier = modifier.raftGrayscale(RaftOpacity.Dimmed)) {
        RaftMessageEmbedHeader {
            RaftFilePreview(RaftFilePreviewKind.Document)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftText(
                    deleted.title,
                    style = typography.subhead.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                RaftText(meta, style = typography.footnote, color = colors.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** web `ArtifactMessageCardLoading`: a skeleton in the card's shape, announced as loading. */
@Composable
private fun ArtifactMessageCardLoading(animated: Boolean) {
    val label = stringResource(Res.string.artifacts_card_loading)
    RaftMessageEmbed(Modifier.semantics { contentDescription = label }) {
        RaftMessageEmbedHeader {
            RaftSkeleton(Modifier.size(32.dp), animated = animated)
            Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftSkeleton(Modifier.fillMaxWidth(0.6f), variant = RaftSkeletonVariant.Line, animated = animated)
                RaftSkeleton(Modifier.fillMaxWidth(0.8f), variant = RaftSkeletonVariant.Line, animated = animated)
            }
        }
    }
}
