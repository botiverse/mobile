package build.raft.app.thread

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import build.raft.designsystem.components.raftClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.app.ArtifactCardExcerpt
import build.raft.app.ArtifactCardExcerptDefaultLines
import build.raft.app.withoutLeadingTitle
import build.raft.app.RaftArtifactViewerHeader
import build.raft.app.RaftArtifactViewerOpening
import build.raft.app.raftArtifactViewerCommentIdOrNull
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.artifactsHomeAuthorText
import build.raft.app.badge
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.currentRaftDateTimeContext
import build.raft.app.data.ArtifactDeletedShown
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactScreenshotReader
import build.raft.app.data.ArtifactSettled
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.ArtifactsHomeLoad
import build.raft.app.data.artifactCoverExpectedMimeType
import build.raft.app.data.artifactCoverFileKey
import build.raft.app.data.artifactFollowKey
import build.raft.app.data.artifactScreenshotFile
import build.raft.app.data.artifactWebUrl
import build.raft.app.data.canToggleFollow
import build.raft.app.fill
import build.raft.app.localized
import build.raft.app.members.localized
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.raftArgbHex
import build.raft.app.raftRelativeTimestampLabel
import build.raft.app.shell.raftWindowWide
import build.raft.app.shell.raftWindowWidth
import build.raft.app.rememberRaftArtifactOpener
import build.raft.app.shell.rememberRaftWebPageOpener
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.formatRaftTimestampEpochMillis
import build.raft.app.resources.*
import build.raft.app.traceId
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftMessageEmbed
import build.raft.designsystem.components.RaftMessageEmbedContent
import build.raft.designsystem.components.RaftFilePreviewBadge
import build.raft.designsystem.components.RaftFilePreviewBadgePlacement
import build.raft.designsystem.components.RaftMessageEmbedDefaults
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.components.raftGrayscale
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftOpacity
import build.raft.designsystem.tokens.RaftStroke
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** Initial panel/comment opening when tapping the card body (web `openArtifactCardInPlace` unseen first comment). */
internal fun ArtifactSummary.cardOpenOpening(): RaftArtifactViewerOpening? {
    val firstComment = unseen?.takeIf { it.newComments > 0 && commentCount > 0 }?.firstNewComment
    val validCommentId = raftArtifactViewerCommentIdOrNull(firstComment) ?: return null
    return RaftArtifactViewerOpening(panel = "comments", commentId = validCommentId)
}

/** Initial panel/comment opening when tapping the footer comment count (web `toComments = true`). */
internal fun ArtifactSummary.commentsOpening(): RaftArtifactViewerOpening =
    RaftArtifactViewerOpening(
        panel = "comments",
        commentId = raftArtifactViewerCommentIdOrNull(unseen?.takeIf { it.newComments > 0 && commentCount > 0 }?.firstNewComment),
    )

/** web `ArtifactMessageCard.tsx` `unseenMarks`: unseen version and comment counts shown on the New sticker. */
internal data class ArtifactCardUnseenMarks(
    val newerVersion: Boolean,
    val newComments: Int,
) {
    val isEmpty: Boolean get() = !newerVersion && newComments <= 0
}

/**
 * web `unseenMarks`: suppressed on preview cards; a pinned card never advertises a newer version
 * (its card shows that pinned version), and `newComments` is clamped to `commentCount`.
 */
internal fun artifactMessageCardUnseenMarks(summary: ArtifactSummary, preview: Boolean): ArtifactCardUnseenMarks {
    val unseen = summary.unseen?.takeUnless { preview } ?: return ArtifactCardUnseenMarks(false, 0)
    val newerVersion = unseen.newerVersion && !summary.pinned
    val newComments = if (summary.commentCount > 0) unseen.newComments.coerceIn(0, summary.commentCount) else 0
    return ArtifactCardUnseenMarks(newerVersion = newerVersion, newComments = newComments)
}

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
    val csvRows = facts.rows
    if (kind == ArtifactKind.Csv && csvRows != null) {
        parts += facts.columns?.let { ArtifactFactPart.RowsColumns(csvRows, it) } ?: ArtifactFactPart.Rows(csvRows)
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
    contextMenuEnabled: Boolean = true,
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
    val linkCopiedMessage = stringResource(Res.string.artifacts_menu_link_copied)
    val unfollowedToast = stringResource(Res.string.artifacts_follow_unfollowed)
    val undoLabel = stringResource(Res.string.artifacts_follow_undo)
    val followFailed = stringResource(Res.string.artifacts_follow_failed)
    val follows = artifacts.follows
    val followState = follows?.state?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    val toastHost = LocalRaftToastHostState.current
    val currentToastHost by rememberUpdatedState(toastHost)
    val undoToastIds = remember { mutableMapOf<String, Long>() }
    val toggleFollow: ((ArtifactSummary, Boolean) -> Unit)? =
        follows?.let { followStore ->
            { summary, nextFollowing ->
                val serverId = host.serverId
                val artifactId = summary.id
                val key = artifactFollowKey(serverId, artifactId)
                scope.launch {
                    when (val result = followStore.setFollowing(serverId, artifactId, nextFollowing)) {
                        null -> Unit
                        is ArtifactsHomeLoad.Ok -> {
                            undoToastIds.remove(key)?.let { currentToastHost?.dismiss(it) }
                            if (!result.value) {
                                val undo = {
                                    scope.launch {
                                        when (val undone = followStore.setFollowing(serverId, artifactId, true)) {
                                            null -> Unit
                                            is ArtifactsHomeLoad.Ok -> undoToastIds.remove(key)?.let { currentToastHost?.dismiss(it) }
                                            is ArtifactsHomeLoad.Failed -> host.errorToast(followFailed, undone.error.traceId)
                                            is ArtifactsHomeLoad.Missing -> host.errorToast(followFailed, undone.error.traceId)
                                            ArtifactsHomeLoad.Off -> host.errorToast(followFailed, null)
                                        }
                                    }
                                    Unit
                                }
                                val activeHost = currentToastHost
                                if (activeHost != null) {
                                    undoToastIds[key] = activeHost.show(title = unfollowedToast, actionLabel = undoLabel, onAction = undo)
                                } else {
                                    host.toastWithAction?.invoke(unfollowedToast, undoLabel, undo) ?: host.toast(unfollowedToast)
                                }
                            }
                        }
                        is ArtifactsHomeLoad.Failed -> host.errorToast(followFailed, result.error.traceId)
                        is ArtifactsHomeLoad.Missing -> host.errorToast(followFailed, result.error.traceId)
                        ArtifactsHomeLoad.Off -> host.errorToast(followFailed, null)
                    }
                }
            }
        }
    RaftDesignSystemTheme {
        CompositionLocalProvider(LocalArtifactScreenshotReader provides screenshots) {
            ThreadArtifactMessageRefsContent(
                view = view,
                onOpen = { summary ->
                    val target = summary.openTarget()
                    opener.open(target, RaftArtifactViewerHeader.of(summary, target), "message_card", summary.cardOpenOpening())
                },
                onOpenComments = { summary ->
                    val target = summary.openTarget()
                    opener.open(target, RaftArtifactViewerHeader.of(summary, target), "message_card_comments", summary.commentsOpening())
                },
                onCopyLink = { summary ->
                    host.copyText(artifactWebUrl(webBaseUrl, summary.openTarget()))
                    host.toastWithIntent(linkCopiedMessage, RaftToastIntent.Success)
                },
                modifier = modifier,
                readerId = artifacts.readerId,
                contextMenuEnabled = contextMenuEnabled,
                followingOf = { summary -> followState?.followingOf(host.serverId, summary.id) ?: summary.following },
                followPendingOf = { summary -> followState?.isPending(host.serverId, summary.id) == true },
                onToggleFollow = toggleFollow,
                onRereadLatest = { artifactId ->
                    artifacts.summaries.refresh(host.serverId, ArtifactLinkTarget(artifactId))
                },
                isPageVisible = artifacts.isPageVisible,
            )
        }
    }
}

/** The cards (web `mt-2 flex flex-col gap-2`): 8dp under the body, 8dp apart. [readerId]: the signed-in person. */
@Composable
internal fun ThreadArtifactMessageRefsContent(
    view: ThreadArtifactMessageRefsView,
    onOpen: (ArtifactSummary) -> Unit,
    modifier: Modifier = Modifier,
    skeletonAnimated: Boolean = true,
    readerId: String? = null,
    onCopyLink: (ArtifactSummary) -> Unit = {},
    onOpenComments: ((ArtifactSummary) -> Unit)? = null,
    contextMenuEnabled: Boolean = true,
    followingOf: (ArtifactSummary) -> Boolean? = { it.following },
    followPendingOf: (ArtifactSummary) -> Boolean = { false },
    onToggleFollow: ((ArtifactSummary, Boolean) -> Unit)? = null,
    onRereadLatest: ((String) -> Unit)? = null,
    isPageVisible: () -> Boolean = { true },
    maxExcerptLines: Int = ArtifactCardExcerptDefaultLines,
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
                                ArtifactMessageCard(
                                    ref.summary,
                                    onOpen = { onOpen(ref.summary) },
                                    readerId = readerId,
                                    onCopyLink = { onCopyLink(ref.summary) },
                                    onOpenComments = onOpenComments?.let { cb -> { cb(ref.summary) } },
                                    contextMenuEnabled = contextMenuEnabled,
                                    followingOverride = followingOf(ref.summary),
                                    followPending = followPendingOf(ref.summary),
                                    onToggleFollow = onToggleFollow?.let { cb -> { next -> cb(ref.summary, next) } },
                                    onRereadLatest = onRereadLatest,
                                    isPageVisible = isPageVisible,
                                    maxExcerptLines = maxExcerptLines,
                                )
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

/**
 * Whether a [windowWidth]-wide window shows a card's phone screenshot (web `PHONE_QUERY`, under its
 * `md`): a phone layout, any window under the app's wide breakpoint ([raftWindowWide]). A window
 * with the rail and two panes shows the desktop one.
 */
internal fun artifactScreenshotPhoneLayout(windowWidth: Dp): Boolean = !raftWindowWide(windowWidth)

/** Web `HELD_PICTURE_REREAD_MS` (slock #16979): re-poll the unpinned latest summary every 15s while holding a cover box. */
internal const val ARTIFACT_CARD_HELD_PICTURE_REREAD_MILLIS: Long = 15_000L

/** Web `HELD_PICTURE_MAX_MS` (slock #16979): drop the held cover box after 10 minutes without a new screenshot. */
internal const val ARTIFACT_CARD_HELD_PICTURE_MAX_MILLIS: Long = 10L * 60L * 1000L

internal const val ArtifactCardCoverTestTag = "artifact-card-cover"
internal const val ArtifactCardCoverPendingTestTag = "artifact-card-cover-pending"
internal val ArtifactCardCoverStateKey = SemanticsPropertyKey<String>("ArtifactCardCoverState")

/** web `ArtifactCardPicture` for a screenshot: none, its place kept while it is read/held, or shown. */
@Immutable
internal sealed interface ArtifactCardScreenshotView {
    /** No screenshot. */
    data object None : ArtifactCardScreenshotView

    /** Being read: the card keeps its place, so the conversation doesn't move when it comes. */
    data object Pending : ArtifactCardScreenshotView

    /**
     * A new version arrived before its publish-check screenshot landed: keep the 16:10 box until
     * the new screenshot arrives (polled every 15s) or 10 minutes elapse (web `slock#16979`).
     */
    data object Held : ArtifactCardScreenshotView

    /**
     * The card names a screenshot that couldn't be read: its place stays, an empty box (no spinner,
     * no error), as one still being read does, so the card doesn't shrink under the reader (web
     * `artifactCardPicture.ts` `unreadable`, #proj-uiux:#1311).
     */
    data object Unreadable : ArtifactCardScreenshotView

    /** Read: [bytes] of file [fileId] of card [cardId], drawn by the image loader. */
    class Shown(val cardId: String, val fileId: String, val bytes: ByteArray) : ArtifactCardScreenshotView
}

/** Web `drawsPictureBox` (slock #16979): true whenever this card is showing or holding a picture box (not an excerpt). */
internal fun ArtifactCardScreenshotView.drawsPictureBox(): Boolean =
    this is ArtifactCardScreenshotView.Shown ||
        this is ArtifactCardScreenshotView.Pending ||
        this is ArtifactCardScreenshotView.Unreadable

/** Web `useArtifactMessageCardPicture` hold condition (slock #16979). */
internal fun artifactMessageCardHoldsPictureBox(
    raw: ArtifactCardScreenshotView,
    hasExcerpt: Boolean,
    cardId: String,
    boxFor: String?,
    lapsedFor: String?,
): Boolean = raw == ArtifactCardScreenshotView.None && !hasExcerpt && boxFor == cardId && lapsedFor != cardId

private class ArtifactHeldPictureBoxTracker {
    var boxFor: String? = null
}

/**
 * Message-card picture state (web `useArtifactMessageCardPicture`, slock #16979): when an Artifact
 * message card that previously drew a 16:10 picture box receives a new version without a screenshot
 * (and without a Markdown excerpt), holds the 16:10 muted box and re-polls the unpinned latest
 * summary every 15 seconds while visible, dropping the box after 10 minutes.
 */
@Composable
internal fun rememberArtifactMessageCardScreenshot(
    summary: ArtifactSummary,
    onRereadLatest: ((String) -> Unit)? = null,
    isPageVisible: () -> Boolean = { true },
): ArtifactCardScreenshotView {
    val raw = rememberArtifactCardScreenshot(summary)
    val boxTracker = remember { ArtifactHeldPictureBoxTracker() }
    var lapsedFor by remember { mutableStateOf<String?>(null) }
    val drawsBox = raw.drawsPictureBox()
    if (drawsBox) {
        boxTracker.boxFor = summary.id
    }
    SideEffect {
        if (drawsBox && lapsedFor == summary.id) {
            lapsedFor = null
        }
    }
    val held =
        artifactMessageCardHoldsPictureBox(
            raw = raw,
            hasExcerpt = summary.excerpt != null,
            cardId = summary.id,
            boxFor = boxTracker.boxFor,
            lapsedFor = lapsedFor,
        )
    val currentLifecycleOwner by rememberUpdatedState(LocalLifecycleOwner.current)
    val currentIsPageVisible by rememberUpdatedState(isPageVisible)
    val currentRereadLatest by rememberUpdatedState(onRereadLatest)
    // Keyed only on (held, summary.id) — never version or visibility — so hiding/showing the page
    // skips poll ticks without resetting the 10-minute lapse cap (slock #16979).
    LaunchedEffect(held, summary.id) {
        if (!held) return@LaunchedEffect
        val poller =
            launch {
                while (true) {
                    delay(ARTIFACT_CARD_HELD_PICTURE_REREAD_MILLIS)
                    if (currentIsPageVisible() && currentLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        currentRereadLatest?.invoke(summary.id)
                    }
                }
            }
        delay(ARTIFACT_CARD_HELD_PICTURE_MAX_MILLIS)
        poller.cancel()
        lapsedFor = summary.id
    }
    return if (held) ArtifactCardScreenshotView.Held else raw
}

/**
 * web `useArtifactScreenshotSrc`: the card's screenshot for this window's width (the phone shot in
 * a phone-width layout when there is one), read once the card is composed (a lazy list composes
 * only the rows on screen and just past it); one kept already is shown at once.
 */
@Composable
internal fun rememberArtifactCardScreenshot(summary: ArtifactSummary): ArtifactCardScreenshotView {
    val reader = LocalArtifactScreenshotReader.current ?: return ArtifactCardScreenshotView.None
    val screenshotFileId = artifactScreenshotFile(summary.screenshot, artifactScreenshotPhoneLayout(raftWindowWidth()))
    val coverPath = if (screenshotFileId == null) summary.coverPath else null
    val fileId =
        when {
            screenshotFileId != null -> screenshotFileId
            coverPath != null -> {
                if (summary.version <= 0 || artifactCoverExpectedMimeType(coverPath) == null) return ArtifactCardScreenshotView.Unreadable
                artifactCoverFileKey(summary.version, coverPath)
            }
            else -> return ArtifactCardScreenshotView.None
        }
    val cardId = summary.id
    var bytes by remember(reader, cardId, fileId) { mutableStateOf(reader.kept(cardId, fileId)) }
    var failed by remember(reader, cardId, fileId) { mutableStateOf(false) }
    LaunchedEffect(reader, cardId, fileId) {
        if (bytes != null) return@LaunchedEffect
        val read = reader.read(cardId, fileId) { cached ->
            if (bytes == null || !bytes.contentEquals(cached)) {
                bytes = cached
            }
        }
        if (read != null) {
            if (bytes == null || !bytes.contentEquals(read)) {
                bytes = read
            }
            failed = false
        } else {
            bytes = null
            failed = true
        }
    }
    val shown = bytes
    return when {
        shown != null -> ArtifactCardScreenshotView.Shown(cardId, fileId, shown)
        failed -> ArtifactCardScreenshotView.Unreadable
        else -> ArtifactCardScreenshotView.Pending
    }
}

/** web `max-h-48`: a card's picture is at most 192dp tall. */
internal val ArtifactCardScreenshotMaxHeight: Dp = 192.dp

/** On a phone (under the app's wide breakpoint) a card's picture is at most 120dp tall (#proj-mobile:#439). */
internal val ArtifactCardScreenshotCompactMaxHeight: Dp = 120.dp

/** [ArtifactCardScreenshotCompactMaxHeight] on a phone ([compact]), else [ArtifactCardScreenshotMaxHeight]. */
internal fun artifactCardScreenshotMaxHeight(compact: Boolean): Dp =
    if (compact) ArtifactCardScreenshotCompactMaxHeight else ArtifactCardScreenshotMaxHeight

/** web `aspect-[16/10] max-h-48 w-full`: the card's width, 16:10, at most [ArtifactCardScreenshotMaxHeight] tall. */
internal fun artifactCardScreenshotHeightPx(widthPx: Int, maxHeightPx: Int): Int = minOf((widthPx * 10f / 16f).roundToInt(), maxHeightPx)

/**
 * web `ArtifactMessageCard`'s cover: edge to edge between the header and the footer, `object-cover
 * object-top` (cut from the bottom: the page's top and first screen), a `line-hairline` rule above
 * and under it; while it is read, the same box empty on `fill-muted`. At most [maxHeight] tall.
 */
@Composable
internal fun ArtifactCardScreenshot(view: ArtifactCardScreenshotView, maxHeight: Dp = ArtifactCardScreenshotMaxHeight) {
    val colors = RaftTheme.colors
    val coverState =
        when (view) {
            ArtifactCardScreenshotView.Pending -> "loading"
            ArtifactCardScreenshotView.Held -> "held"
            ArtifactCardScreenshotView.Unreadable -> "unreadable"
            ArtifactCardScreenshotView.None, is ArtifactCardScreenshotView.Shown -> null
        }
    val boxModifier =
        Modifier
            .artifactCardScreenshotSize(maxHeight)
            .background(colors.fillMuted)
            .let { base ->
                when {
                    view is ArtifactCardScreenshotView.Shown -> base.testTag(ArtifactCardCoverTestTag)
                    coverState != null ->
                        base.testTag(ArtifactCardCoverPendingTestTag).semantics {
                            this[ArtifactCardCoverStateKey] = coverState
                        }
                    else -> base
                }
            }
    RaftMessageEmbedContent {
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineHairline))
        Box(boxModifier) {
            if (view is ArtifactCardScreenshotView.Shown) {
                val context = LocalPlatformContext.current
                val request =
                    remember(view.cardId, view.fileId, view.bytes, context) {
                        ImageRequest.Builder(context)
                            .data(view.bytes)
                            .memoryCacheKey("artifact-screenshot|${view.cardId}|${view.fileId}|${view.bytes.size}|${view.bytes.contentHashCode()}")
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

private fun Modifier.artifactCardScreenshotSize(maxHeight: Dp): Modifier =
    layout { measurable, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 440.dp.roundToPx()
        val height = artifactCardScreenshotHeightPx(width, maxHeight.roundToPx())
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }

/** The Artifact icon (web `ArtifactIcon size-4`): a deleted card's title row, or a kind with no type badge. */
internal val ArtifactCardIconSize: Dp = 16.dp

/** The card's title type (raft-ui `messageCard().title()`: `subhead` bold). */
@Composable
@ReadOnlyComposable
private fun artifactCardTitleStyle(): TextStyle = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold)

/**
 * web `ArtifactMessageCard`'s title row (`flex items-start gap-2 px-3 pt-3`): the file type badge
 * ([badge]: "MD", "HTML", "CSV"; artin via Kirby, #proj-mobile:8518431c, in place of the 40dp file
 * glyph), else the Artifact icon (16dp), centred on the title's first line (`h-[1lh]`), then
 * [content] (the title and its tags).
 */
@Composable
private fun ArtifactCardTitleRow(badge: String? = null, content: @Composable RowScope.() -> Unit) {
    val lineHeight = with(LocalDensity.current) { artifactCardTitleStyle().lineHeight.toDp() }
    Row(
        Modifier.fillMaxWidth().padding(start = RaftMessageEmbedDefaults.Inset, end = RaftMessageEmbedDefaults.Inset, top = RaftMessageEmbedDefaults.Inset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.height(lineHeight), contentAlignment = Alignment.Center) {
            if (badge != null) {
                RaftFilePreviewBadge(badge, placement = RaftFilePreviewBadgePlacement.Inline)
            } else {
                RaftLucideIcon(RaftLucideIconName.Artifact, Modifier.size(ArtifactCardIconSize), color = raftArgbHex(RaftTheme.colors.foreground))
            }
        }
        content()
    }
}

/**
 * web `ArtifactMessageCard`'s meta line (raft-ui `messageCard().metaLine()`: `flex items-center
 * gap-1.5 whitespace-nowrap text-caption`, `px-3 pt-2 pb-3`): one line of details at the card's foot.
 */
@Composable
private fun ArtifactCardMetaLine(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = RaftMessageEmbedDefaults.Inset, end = RaftMessageEmbedDefaults.Inset, top = 8.dp, bottom = RaftMessageEmbedDefaults.Inset),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The version a card names (web `formatArtifactVersionBehind`, xxchan via Kirby, #proj-mobile:#439):
 * only when it points at an older version and a newer one exists, both (the version shown, the latest),
 * so the reader knows they will open an old one; the latest says nothing about its version: null.
 */
internal fun artifactCardVersionBehind(summary: ArtifactSummary): Pair<Int, Int>? =
    if (summary.pinned && summary.latestVersion > summary.version) summary.version to summary.latestVersion else null

/**
 * web `ArtifactMessageCard` `OkCard` (F12, F37, F31 A), interactive as a whole (a tap opens it in
 * place), compact (sindresorhus + Kirby, #proj-mobile:#439; web slock #17371, raft-design #1493
 * cards-in-messages "A card with a preview"): the title once at the top, a preview that doesn't
 * repeat it, one meta line.
 * - Title row ([ArtifactCardTitleRow]): the file type badge (MD, HTML, CSV…; the Artifact icon for a
 *   kind without one), the title (2 lines), then the Settled tag and the accent New badge saying what
 *   is new to the reader ("Updated · 2 new comments", [ArtifactCardNewSticker]; not on a preview).
 * - Preview: the publish check's screenshot or else the publisher's cover image
 *   ([ArtifactCardScreenshot]) edge to edge between hairlines, cut from the top, at most 192dp, 120dp
 *   on a phone; a picture is the preview, so no excerpt beside it; else a Markdown version's excerpt
 *   ([ArtifactCardExcerpt]) 12dp in without its opening level-1 heading ([withoutLeadingTitle]), at
 *   most [maxExcerptLines] lines on a phone (4; a Server flag later, 2..8); else the description (2 lines); else nothing. The kind's facts and a
 *   description beside a preview are read after opening.
 * - Meta line ([ArtifactCardMetaLine]): author · time · comments (a button opening the comments,
 *   [onOpenComments], except on a preview), then the version only when the
 *   card points at an older one and a newer exists ("v2 · latest v4", [artifactCardVersionBehind]).
 *   No channel or scope tag in any conversation (xxchan, Remy): who can see it is in the details
 *   and the share sheet.
 * A phone is a window under the app's wide breakpoint ([raftWindowWide]).
 *
 * Settled (botiverse/slock #15654, `artifact_settled_v0`): while the Artifact is settled now, the
 * Settled tag after the title ([ArtifactSettledTag]; [readerId] names the reader's own settle "You"),
 * going under the title rather than leave it under 128dp ([ArtifactCardTitleLine]). It is the only
 * status a card shows (Kirby, #proj-mobile:#439): nothing for Working, or with the flag off
 * ([ArtifactSummary.settled] null).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtifactMessageCard(
    summary: ArtifactSummary,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    readerId: String? = null,
    onCopyLink: () -> Unit = {},
    onOpenComments: (() -> Unit)? = null,
    contextMenuEnabled: Boolean = true,
    preview: Boolean = false,
    followingOverride: Boolean? = null,
    followPending: Boolean = false,
    onToggleFollow: ((Boolean) -> Unit)? = null,
    onRereadLatest: ((String) -> Unit)? = null,
    isPageVisible: () -> Boolean = { true },
    maxExcerptLines: Int = ArtifactCardExcerptDefaultLines,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val compact = !raftWindowWide()
    val time = artifactRelativeTime(summary.updatedAt)
    val description = summary.description?.takeIf { it.isNotBlank() }
    val screenshot =
        rememberArtifactMessageCardScreenshot(
            summary = summary,
            onRereadLatest = if (preview) null else onRereadLatest,
            isPageVisible = isPageVisible,
        )
    // web `useArtifactCardPicture`: a screenshot or publisher cover, else a Markdown version's excerpt
    // (one kind at a time from the server, so an excerpt never waits for or replaces a picture).
    val excerpt = summary.excerpt.takeIf { screenshot == ArtifactCardScreenshotView.None }?.withoutLeadingTitle(summary.title)
    // web `ArtifactCardMenu.tsx` (botiverse/slock #16110): right-click or Shift+F10/ContextMenu on
    // a live card opens the Artifact's own menu; preview cards and select mode leave it off.
    val effectiveMenuEnabled = contextMenuEnabled && !preview
    val childContextMenus = LocalThreadMessageChildContextMenus.current
    val childLongPress = LocalThreadMessageChildLongPress.current.takeIf { !preview }
    val regionKey = remember { Any() }
    val cardBounds = remember { ArtifactCardBoundsHolder() }
    val tooltipRegions = remember { ArtifactCardTooltipRegions() }
    val cardFocusRequester = remember { FocusRequester() }
    var menuAnchor by remember { mutableStateOf<IntOffset?>(null) }
    val density = LocalDensity.current
    val currentMenuEnabled by rememberUpdatedState(effectiveMenuEnabled)
    DisposableEffect(childContextMenus, regionKey, effectiveMenuEnabled) {
        if (effectiveMenuEnabled) {
            cardBounds.coordinates?.takeIf { it.isAttached }?.let { childContextMenus?.register(regionKey, it) }
        } else {
            childContextMenus?.unregister(regionKey)
        }
        onDispose { childContextMenus?.unregister(regionKey) }
    }
    LaunchedEffect(effectiveMenuEnabled) {
        if (!effectiveMenuEnabled && menuAnchor != null) menuAnchor = null
    }
    if (summary.settled == null) tooltipRegions.settledTag = null
    val cardModifier =
        Modifier
            .testTag(ArtifactCardMenuTags.Card)
            .focusRequester(cardFocusRequester)
            .onGloballyPositioned { coords ->
                cardBounds.coordinates = coords
                if (currentMenuEnabled) childContextMenus?.register(regionKey, coords)
            }
            .onPreviewKeyEvent { event ->
                if (
                    menuAnchor == null &&
                    artifactCardOpensContextMenuForKey(event.type, event.key, event.isShiftPressed, currentMenuEnabled)
                ) {
                    val size = cardBounds.coordinates?.size ?: IntSize.Zero
                    menuAnchor = artifactCardKeyboardMenuAnchorPx(size, with(density) { ArtifactCardKeyboardMenuInset.roundToPx() })
                    true
                } else {
                    false
                }
            }
            .artifactCardGestures(
                menuEnabled = effectiveMenuEnabled,
                tooltipRegions = tooltipRegions,
                cardCoordinates = { cardBounds.coordinates },
                childLongPress = childLongPress,
                onOpenContextMenu = { position ->
                    val size = cardBounds.coordinates?.size ?: IntSize.Zero
                    menuAnchor = artifactCardPointerMenuAnchorPx(position, size)
                },
            )
    Box(modifier) {
        RaftMessageEmbed(
            modifier = cardModifier,
            onClick = if (preview) null else onOpen,
            onClickLabel = if (preview) null else summary.title,
        ) {
            ArtifactCardTitleRow(badge = summary.kind.badge) {
                val title: @Composable (Modifier) -> Unit = { titleModifier ->
                    RaftText(
                        summary.title,
                        modifier = titleModifier,
                        style = artifactCardTitleStyle(),
                        color = colors.foreground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val settled = summary.settled
                val unseenMarks = artifactMessageCardUnseenMarks(summary, preview)
                Box(Modifier.weight(1f)) {
                    if (settled == null && unseenMarks.isEmpty) {
                        title(Modifier)
                    } else {
                        ArtifactCardTitleLine(
                            title = { title(Modifier) },
                            // web: the Settled tag, then the New badge, after the title (`gap-1 pt-px flex-wrap`),
                            // under it before the title gets under 8rem.
                            tags = {
                                FlowRow(
                                    Modifier.padding(top = 1.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (settled != null) {
                                        ArtifactSettledTag(
                                            settled,
                                            readerId,
                                            modifier = Modifier.onGloballyPositioned { tooltipRegions.settledTag = it },
                                        )
                                    }
                                    if (!unseenMarks.isEmpty) {
                                        ArtifactCardNewSticker(unseenMarks)
                                    }
                                }
                            },
                        )
                    }
                }
            }
            when {
                screenshot != ArtifactCardScreenshotView.None -> {
                    // web `mt-2 border-y`: 8dp under the title, edge to edge between hairlines.
                    Spacer(Modifier.height(8.dp))
                    ArtifactCardScreenshot(screenshot, artifactCardScreenshotMaxHeight(compact))
                }
                excerpt != null ->
                    // web `px-3 pt-2`: 12dp in, like the title, with no frame of its own.
                    Box(Modifier.fillMaxWidth().padding(start = RaftMessageEmbedDefaults.Inset, end = RaftMessageEmbedDefaults.Inset, top = 8.dp)) {
                        ArtifactCardExcerpt(excerpt, compact = compact, compactMaxLines = maxExcerptLines)
                    }
                // Without a picture or an excerpt, the description (web `line-clamp-2 text-callout`).
                description != null ->
                    RaftText(
                        description,
                        modifier = Modifier.fillMaxWidth().padding(start = RaftMessageEmbedDefaults.Inset, end = RaftMessageEmbedDefaults.Inset, top = 8.dp),
                        style = typography.callout,
                        color = colors.foregroundMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
            // One line at the foot: author · time · comments, the version only when this one is behind.
            ArtifactCardMetaLine {
                val ink = colors.foregroundMuted
                val text: @Composable (String, Modifier) -> Unit = { words, textModifier ->
                    RaftText(words, modifier = textModifier, style = typography.caption, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val dot: @Composable () -> Unit = { text("·", Modifier.clearAndSetSemantics {}) }
                val author = summary.authorName.takeIf { it.isNotBlank() }
                // The author gives way first; the rest keep their words.
                author?.let { text(it, Modifier.weight(1f, fill = false)) }
                time?.let {
                    if (author != null) dot()
                    text(it, Modifier)
                }
                if (summary.commentCount > 0) {
                    if (author != null || time != null) dot()
                    val label =
                        if (summary.commentCount == 1) {
                            stringResource(Res.string.artifacts_card_comments_one)
                        } else {
                            stringResource(Res.string.artifacts_card_comments_many, summary.commentCount)
                        }
                    val commentsClick = if (!preview) onOpenComments else null
                    val commentsModifier =
                        Modifier
                            .testTag(ArtifactCardCommentsButtonTestTag)
                            .let { base ->
                                if (commentsClick != null) {
                                    base
                                        .clip(RoundedCornerShape(build.raft.designsystem.tokens.RaftRadius.Control))
                                        .raftClickable(
                                            onClick = commentsClick,
                                            role = Role.Button,
                                            onClickLabel = label,
                                            shape = RoundedCornerShape(build.raft.designsystem.tokens.RaftRadius.Control),
                                        )
                                } else {
                                    base
                                }
                            }
                            .semantics(mergeDescendants = true) { contentDescription = label }
                    Row(
                        commentsModifier,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftLucideIcon(RaftLucideIconName.Comment, Modifier.size(14.dp), color = raftArgbHex(ink))
                        text(summary.commentCount.toString(), Modifier)
                    }
                }
                artifactCardVersionBehind(summary)?.let { (version, latest) ->
                    if (author != null || time != null || summary.commentCount > 0) dot()
                    text(stringResource(Res.string.artifacts_card_version_behind, version, latest), Modifier)
                }
            }
        }
        if (!preview) {
            val effectiveFollowing = followingOverride ?: summary.following
            ArtifactCardContextMenuAnchor(
                anchor = menuAnchor,
                returnFocusTo = cardFocusRequester,
                onDismiss = { menuAnchor = null },
                onOpen = onOpen,
                onCopyLink = onCopyLink,
                following = effectiveFollowing,
                showFollow = summary.canToggleFollow(effectiveFollowing) && onToggleFollow != null,
                followPending = followPending,
                onToggleFollow = onToggleFollow,
            )
        }
    }
}

/** web's `artifact-settled-tag`: the Settled tag's test tag. */
internal const val ArtifactSettledTagTestTag = "artifact-settled-tag"

/** web's `artifact-card-new`: the unseen updates / new comments badge's test tag. */
internal const val ArtifactCardNewStickerTestTag = "artifact-card-new"

/** web's `artifact-card-comments`: the footer comment count button's test tag. */
internal const val ArtifactCardCommentsButtonTestTag = "artifact-card-comments"

/**
 * The card's New badge (web `ArtifactMessageCard` `NewSticker`; raft-design #1493 "News for me": one
 * accent Badge after the title, beside the Settled tag, not a corner sticker): what is new to the
 * reader, unseen version and unseen comment counts (`" · "` separated: "Updated · 2 new comments"),
 * with a screen-reader label (`"; "` separated).
 */
@Composable
internal fun ArtifactCardNewSticker(marks: ArtifactCardUnseenMarks, modifier: Modifier = Modifier) {
    if (marks.isEmpty) return
    val visibleParts =
        buildList {
            if (marks.newerVersion) add(stringResource(Res.string.artifacts_dashboard_new_version))
            if (marks.newComments > 0) {
                add(
                    if (marks.newComments == 1) {
                        stringResource(Res.string.artifacts_dashboard_new_comments_one, marks.newComments)
                    } else {
                        stringResource(Res.string.artifacts_dashboard_new_comments_many, marks.newComments)
                    },
                )
            }
        }
    val spokenParts =
        buildList {
            if (marks.newerVersion) add(stringResource(Res.string.artifacts_card_new_version_label))
            if (marks.newComments > 0) {
                add(
                    if (marks.newComments == 1) {
                        stringResource(Res.string.artifacts_card_new_comments_one, marks.newComments)
                    } else {
                        stringResource(Res.string.artifacts_card_new_comments_many, marks.newComments)
                    },
                )
            }
        }
    val spoken = spokenParts.joinToString("; ")
    RaftBadge(
        text = visibleParts.joinToString(" · "),
        variant = RaftBadgeVariant.Accent,
        modifier =
            modifier.clearAndSetSemantics {
                contentDescription = spoken
                testTag = ArtifactCardNewStickerTestTag
            },
    )
}

/** web `flex-[1_1_8rem]`: the room a card's title keeps before its tags go under it (Kirby, #12873). */
private val ArtifactCardTitleMinWidth: Dp = 128.dp

/**
 * web `ArtifactMessageCard`'s title line (`flex flex-wrap gap-x-1.5 gap-y-1`): [title], then [tags]
 * right after it (6dp), or, where that would leave the title under [ArtifactCardTitleMinWidth], the
 * tags on their own line 4dp under it. Beside the title it lays out as the card's plain row does.
 */
@Composable
private fun ArtifactCardTitleLine(title: @Composable () -> Unit, tags: @Composable () -> Unit) {
    Layout(contents = listOf(title, tags)) { (titleMeasurables, tagsMeasurables), constraints ->
        val gapX = 6.dp.roundToPx()
        val gapY = 4.dp.roundToPx()
        val maxWidth = constraints.maxWidth
        val tagsPlaceable = tagsMeasurables.first().measure(Constraints(maxWidth = maxWidth, maxHeight = constraints.maxHeight))
        val beside = maxWidth == Constraints.Infinity || maxWidth - tagsPlaceable.width - gapX >= ArtifactCardTitleMinWidth.roundToPx()
        if (beside) {
            val titleRoom = if (maxWidth == Constraints.Infinity) Constraints.Infinity else maxWidth - tagsPlaceable.width - gapX
            val titlePlaceable = titleMeasurables.first().measure(Constraints(maxWidth = titleRoom, maxHeight = constraints.maxHeight))
            layout(titlePlaceable.width + gapX + tagsPlaceable.width, maxOf(titlePlaceable.height, tagsPlaceable.height)) {
                titlePlaceable.placeRelative(0, 0)
                tagsPlaceable.placeRelative(titlePlaceable.width + gapX, 0)
            }
        } else {
            val titlePlaceable = titleMeasurables.first().measure(Constraints(maxWidth = maxWidth, maxHeight = constraints.maxHeight))
            layout(maxOf(titlePlaceable.width, tagsPlaceable.width), titlePlaceable.height + gapY + tagsPlaceable.height) {
                titlePlaceable.placeRelative(0, 0)
                tagsPlaceable.placeRelative(0, titlePlaceable.height + gapY)
            }
        }
    }
}

/**
 * web `ArtifactSettledTag` (botiverse/slock #15654): Badge soft `success` with a check, "Settled". A
 * long press, or a pointer over it, shows who settled it, when and at which version
 * (`artifacts.settled.line`: "Remy settled it on 11-19 14:02 (v3)", the reader's own as "You"), as the
 * app's other tags with a detail do ([RaftTooltip], the Computers rack's Low disk chip); a screen
 * reader reads those words with the tag (`artifacts.settled.tagDetail`). Without a readable time the
 * tag says only "Settled".
 */
@Composable
internal fun ArtifactSettledTag(settled: ArtifactSettled, readerId: String?, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.artifacts_settled_tag)
    val time = artifactSettledTimeLabel(settled.at, currentRaftDateTimeContext(null))
    val line = time?.let { stringResource(Res.string.artifacts_settled_line, artifactsHomeAuthorText(settled.by, readerId).first, it, settled.version) }
    val description = line?.let { label + stringResource(Res.string.artifacts_settled_tag_detail, it) } ?: label
    val tagModifier =
        modifier.clearAndSetSemantics {
            contentDescription = description
            testTag = ArtifactSettledTagTestTag
        }
    val badge: @Composable () -> Unit = {
        RaftBadge(
            text = label,
            appearance = RaftBadgeAppearance.Soft,
            variant = RaftBadgeVariant.Success,
            icon = { RaftLucideIcon(RaftLucideIconName.Check, Modifier.fillMaxSize(), color = raftArgbHex(LocalRaftContentColor.current)) },
        )
    }
    if (line == null) Box(tagModifier) { badge() } else RaftTooltip(line, modifier = tagModifier) { badge() }
}

/**
 * When it was settled, for the Settled line (web `shortDateTime`, "Nov 19, 14:02"): the app's absolute
 * time in the reader's 12- or 24-hour setting ("11-19 14:02", "2025-11-19 14:02" for another year),
 * always with its date: a settle of today is told as of the day before, as the app's label would say
 * the time alone and "settled it on 14:02" reads as a date. Null without a time context or a readable
 * time.
 */
internal fun artifactSettledTimeLabel(at: String, context: RaftTimeContext?): String? {
    val time = context ?: return null
    val epochMillis = parseRaftTimestampEpochMillisOrNull(at, time.localOffsetMinutes) ?: return null
    val offsetMillis = time.localOffsetMinutes * MillisPerMinute
    val day = (epochMillis + offsetMillis).floorDiv(MillisPerDay)
    val today = time.todayEpochDay ?: time.nowEpochMillis?.let { (it + offsetMillis).floorDiv(MillisPerDay) } ?: return null
    return formatRaftTimestampEpochMillis(epochMillis, time.localOffsetMinutes, if (day == today) day - 1 else today, time.timeFormat)
}

/**
 * web `ArtifactDeletedCard`: the card's frame and its title row (the Artifact icon, the title as
 * plain text), gray and dimmed (DS States › Dimmed: grayscale at 60%), and who deleted it when as
 * its meta line, a step darker than a live card's (`foreground`) so it still reads at 60%. Nothing
 * in it opens.
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
    RaftMessageEmbed(modifier = modifier.testTag(ArtifactCardMenuTags.DeletedCard).raftGrayscale(RaftOpacity.Dimmed)) {
        ArtifactCardTitleRow {
            RaftText(
                deleted.title,
                modifier = Modifier.weight(1f),
                style = artifactCardTitleStyle(),
                color = colors.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ArtifactCardMetaLine {
            RaftText(meta, modifier = Modifier.weight(1f, fill = false), style = typography.caption, color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * web `ArtifactMessageCardLoading`, in the card's shape: its title row (the icon and a line) and its
 * meta line, so a card without a preview replaces it at about its own height instead of growing
 * under the reader (#proj-mobile:#201). Whether the card will have a preview is not known before
 * its summary is read, so none is reserved here. Announced as loading.
 */
@Composable
private fun ArtifactMessageCardLoading(animated: Boolean) {
    val label = stringResource(Res.string.artifacts_card_loading)
    val lineHeight = with(LocalDensity.current) { artifactCardTitleStyle().lineHeight.toDp() }
    RaftMessageEmbed(Modifier.semantics { contentDescription = label }) {
        Row(
            Modifier.fillMaxWidth().height(lineHeight + RaftMessageEmbedDefaults.Inset).padding(start = RaftMessageEmbedDefaults.Inset, end = RaftMessageEmbedDefaults.Inset, top = RaftMessageEmbedDefaults.Inset),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftSkeleton(Modifier.size(ArtifactCardIconSize), animated = animated)
            RaftSkeleton(Modifier.fillMaxWidth(0.6f), variant = RaftSkeletonVariant.Line, animated = animated)
        }
        ArtifactCardMetaLine {
            Box(Modifier.height(ArtifactCardLoadingMetaLine), contentAlignment = Alignment.Center) {
                RaftSkeleton(Modifier.fillMaxWidth(0.4f), variant = RaftSkeletonVariant.Line, animated = animated)
            }
        }
    }
}

/** The meta line's height (`text-caption`, 18dp on a phone). */
private val ArtifactCardLoadingMetaLine: Dp = 18.dp
