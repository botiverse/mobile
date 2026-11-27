package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.resources.Res
import build.raft.app.resources.desc
import build.raft.app.resources.thread_state_loading_older
import build.raft.app.thread.LocalThreadInlineAudioPlayer
import build.raft.app.thread.LocalThreadInlineAudioPreviewHost
import build.raft.app.thread.LocalThreadInlineImageFallbackHost
import build.raft.app.thread.LocalThreadInlineVideoPlayer
import build.raft.app.thread.LocalThreadInlineVideoPreviewHost
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.ThreadHistoryStatusRow
import build.raft.app.thread.ThreadHistoryStatusSpec
import build.raft.app.thread.ThreadImageAttachmentContent
import build.raft.app.thread.ThreadInlineAudioAttachmentCard
import build.raft.app.thread.ThreadInlineAudioPreviewHost
import build.raft.app.thread.ThreadInlineImageFallbackHost
import build.raft.app.thread.ThreadInlineImageFallbackState
import build.raft.app.thread.ThreadInlineVideoAttachmentCard
import build.raft.app.thread.ThreadInlineVideoPreviewHost
import build.raft.app.thread.ThreadNewerMessagesBoundaryRow
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import build.raft.designsystem.components.RaftMessageImageFit
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UI contract tests for #proj-mobile:#386 Batch 1:
 * - P0-2: [ThreadNewerMessagesBoundaryRow] renders at 0px height when idle (no 36dp white band),
 *   renders immediately with non-zero height when loading, and [ThreadHistoryStatusRow] never
 *   reserves invisible height while unrevealed (`loadingRevealDelayMs > 0`).
 * - P0-3: [ThreadImageAttachmentContent] renders its retryable failed placeholder when fallback
 *   URL fetching fails and uses synchronous `peekCachedUrl` on Frame 1 without re-requesting.
 * - P1-2: [ThreadInlineAudioAttachmentCard] and [ThreadInlineVideoAttachmentCard] render Ready
 *   on Frame 1 from `peekCachedUrl` with zero loading flash and zero `requestPreview` calls.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadNewerMessagesBoundaryRowUiTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-batch1-ui-contract"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun newerBoundaryRowIsZeroHeightWhenIdleAndVisibleImmediatelyWhenLoadingOrError() {
        var idleHeight = -1
        var loadingHeight = -1
        var suppressedLoadingHeight = -1
        var retryClicks = 0
        var suppressedRetryClicks = 0

        val root =
            render {
                Box(Modifier.fillMaxWidth().onGloballyPositioned { idleHeight = it.size.height }) {
                    ThreadNewerMessagesBoundaryRow(newerMessagesStatus = "idle", onRetry = {})
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "newer-loading" }
                        .onGloballyPositioned { loadingHeight = it.size.height }
                ) {
                    ThreadNewerMessagesBoundaryRow(newerMessagesStatus = "loading", onRetry = {})
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "newer-loading-suppressed" }
                        .onGloballyPositioned { suppressedLoadingHeight = it.size.height }
                ) {
                    ThreadNewerMessagesBoundaryRow(
                        newerMessagesStatus = "loading",
                        onRetry = {},
                        suppressLoadingIndicator = true,
                    )
                }
                Box(Modifier.fillMaxWidth().semantics { testTag = "newer-error" }) {
                    ThreadNewerMessagesBoundaryRow(newerMessagesStatus = "error", onRetry = { retryClicks += 1 })
                }
                Box(Modifier.fillMaxWidth().semantics { testTag = "newer-error-suppressed" }) {
                    ThreadNewerMessagesBoundaryRow(
                        newerMessagesStatus = "error",
                        onRetry = { suppressedRetryClicks += 1 },
                        suppressLoadingIndicator = true,
                    )
                }
            }

        assertEquals(0, idleHeight, "idle ThreadNewerMessagesBoundaryRow must occupy 0px height, never 36dp blank space")
        assertTrue(loadingHeight > 0, "loading ThreadNewerMessagesBoundaryRow must render visible status row immediately")
        assertTrue(root.textsUnder("newer-loading").isNotEmpty())
        assertEquals(0, suppressedLoadingHeight, "suppressed loading ThreadNewerMessagesBoundaryRow must collapse to 0px")
        assertTrue(root.textsUnder("newer-loading-suppressed").isEmpty())
        assertTrue(root.clickNodeUnder("newer-error"), "error boundary row must expose clickable retry button")
        assertEquals(1, retryClicks)
        assertTrue(
            root.clickNodeUnder("newer-error-suppressed"),
            "error retry button must never be suppressed when suppressLoadingIndicator = true",
        )
        assertEquals(1, suppressedRetryClicks)
    }

    @Test
    fun delayedHistoryStatusRowCollapsesToZeroHeightBeforeRevealInsteadOfTransparentSpacer() {
        var immediateHeight = -1
        var delayedHeight = -1

        val root =
            render(settleMs = 0L) {
                Box(Modifier.fillMaxWidth().onGloballyPositioned { immediateHeight = it.size.height }) {
                    ThreadHistoryStatusRow(
                        spec = ThreadHistoryStatusSpec(label = Res.string.thread_state_loading_older.desc(), loading = true),
                        loadingRevealDelayMs = 0L,
                        spinnerStillStep = 0,
                    )
                }
                Box(Modifier.fillMaxWidth().onGloballyPositioned { delayedHeight = it.size.height }) {
                    ThreadHistoryStatusRow(
                        spec = ThreadHistoryStatusSpec(label = Res.string.thread_state_loading_older.desc(), loading = true),
                        loadingRevealDelayMs = 600L,
                        spinnerStillStep = 0,
                    )
                }
            }

        assertTrue(immediateHeight > 0, "loadingRevealDelayMs=0L must render immediately on Frame 1")
        assertEquals(0, delayedHeight, "unrevealed delayed ThreadHistoryStatusRow must collapse to 0px, never alpha(0f) blank space")
        assertNotNull(root)
    }

    @Test
    fun inlineImageFallbackFailedPlaceholderIsRetryableAndWarmCachePeekSkipsRequestOnFrame1() {
        var failedRequests = 0
        var retriedId: String? = null
        var warmRequests = 0
        val failedAttachment =
            RaftThreadAttachmentItem(
                id = "img-failed",
                name = "broken.png",
                mimeType = "image/png",
                sizeLabel = "12 KB",
                thumbnailUrl = null,
            )
        val warmAttachment =
            RaftThreadAttachmentItem(
                id = "img-warm",
                name = "warm.png",
                mimeType = "image/png",
                sizeLabel = "24 KB",
                thumbnailUrl = null,
            )

        val root =
            render {
                CompositionLocalProvider(
                    LocalThreadInlineImageFallbackHost provides
                        ThreadInlineImageFallbackHost(
                            state = ThreadInlineImageFallbackState(
                                requestedAttachmentIds = setOf("img-failed"),
                                failedAttachmentIds = setOf("img-failed"),
                            ),
                            requestFallback = { id ->
                                if (id == "img-failed") failedRequests += 1 else warmRequests += 1
                            },
                            retryFallback = { id -> retriedId = id },
                            peekCachedUrl = { id ->
                                if (id == "img-warm") "https://r2.example/warm.png" else null
                            },
                        )
                ) {
                    Box(Modifier.size(80.dp).semantics { testTag = "img-failed-tile" }) {
                        ThreadImageAttachmentContent(attachment = failedAttachment, fit = RaftMessageImageFit.Cover)
                    }
                    Box(Modifier.size(80.dp).semantics { testTag = "img-warm-tile" }) {
                        ThreadImageAttachmentContent(attachment = warmAttachment, fit = RaftMessageImageFit.Cover)
                    }
                }
            }

        assertEquals(0, failedRequests, "failed attachment must not keep requesting remote fallback")
        assertEquals(0, warmRequests, "warm peekCachedUrl must satisfy Frame 1 without invoking requestFallback")
        assertTrue(root.clickNodeUnder("img-failed-tile"), "failed inline image placeholder must be tappable to retry")
        assertEquals("img-failed", retriedId)
    }

    @Test
    fun inlineAudioAndVideoCardsRenderReadyOnFrame1FromWarmUrlCacheWithoutLoadingFlash() {
        var audioRequests = 0
        var videoRequests = 0
        val audioAttachment =
            RaftThreadAttachmentItem(
                id = "aud-warm",
                name = "voice-note.mp3",
                mimeType = "audio/mpeg",
                sizeLabel = "500 KB",
            )
        val videoAttachment =
            RaftThreadAttachmentItem(
                id = "vid-warm",
                name = "demo.mp4",
                mimeType = "video/mp4",
                sizeLabel = "3.2 MB",
            )
        var audioPlayerSource: String? = null
        var videoPlayerSource: String? = null

        val root =
            render {
                CompositionLocalProvider(
                    LocalThreadInlineAudioPreviewHost provides
                        ThreadInlineAudioPreviewHost(
                            states = emptyMap(),
                            requestPreview = { _, _ -> audioRequests += 1 },
                            peekCachedUrl = { id -> if (id == "aud-warm") "https://r2.example/aud-warm.mp3" else null },
                        ),
                    LocalThreadInlineAudioPlayer provides { source, _, _, _, _, _ ->
                        audioPlayerSource = source
                    },
                    LocalThreadInlineVideoPreviewHost provides
                        ThreadInlineVideoPreviewHost(
                            states = emptyMap(),
                            requestPreview = { _, _ -> videoRequests += 1 },
                            peekCachedUrl = { id -> if (id == "vid-warm") "https://r2.example/vid-warm.mp4" else null },
                        ),
                    LocalThreadInlineVideoPlayer provides { source, _, _, _, _ ->
                        videoPlayerSource = source
                    },
                ) {
                    Box(Modifier.fillMaxWidth().semantics { testTag = "audio-warm-card" }) {
                        ThreadInlineAudioAttachmentCard(attachment = audioAttachment, onDownload = {})
                    }
                    Box(Modifier.fillMaxWidth().semantics { testTag = "video-warm-card" }) {
                        ThreadInlineVideoAttachmentCard(attachment = videoAttachment, onDownload = {})
                    }
                }
            }

        assertEquals(0, audioRequests, "warm audio preview must not call requestPreview")
        assertEquals("https://r2.example/aud-warm.mp3", audioPlayerSource)
        assertFalse(
            root.textsUnder("audio-warm-card").any { "Loading" in it },
            "warm audio card must not flash Loading: ${root.textsUnder("audio-warm-card")}",
        )
        assertEquals(0, videoRequests, "warm video preview must not call requestPreview")
        assertEquals("https://r2.example/vid-warm.mp4", videoPlayerSource)
    }

    private fun render(settleMs: Long = 200L, content: @Composable () -> Unit): View {
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        activity.setContent {
            CompositionLocalProvider(
                LocalRaftSpinnerStillStep provides 0,
                LocalActivity provides RaftPageActivity(livePager),
                LocalInspectionMode provides true,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        content()
                    }
                }
            }
        }
        val root = assertNotNull(findComposeRoot(activity.window.decorView), "no compose root")
        if (settleMs > 0L) {
            repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(settleMs)) }
        } else {
            shadowOf(Looper.getMainLooper()).idle()
        }
        (root as? RootForTest)?.measureAndLayoutForTest()
        shadowOf(Looper.getMainLooper()).idle()
        return root
    }
}

private fun findComposeRoot(view: View): View? {
    if (view is ViewRootForTest) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
    }
    return null
}

private fun View.textsUnder(tag: String): List<String> {
    val root = (this as RootForTest).semanticsOwner.unmergedRootSemanticsNode
    val tagged = root.find { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    assertNotNull(tagged, "no node tagged $tag")
    return buildList { tagged.collectTexts(this) }
}

private fun View.clickNodeUnder(tag: String): Boolean {
    val root = (this as RootForTest).semanticsOwner.unmergedRootSemanticsNode
    val tagged = root.find { it.config.getOrNull(SemanticsProperties.TestTag) == tag } ?: return false
    val clickable = tagged.find { it.config.getOrNull(SemanticsActions.OnClick) != null } ?: return false
    return clickable.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
}

private fun SemanticsNode.find(predicate: (SemanticsNode) -> Boolean): SemanticsNode? =
    if (predicate(this)) this else children.firstNotNullOfOrNull { it.find(predicate) }

private fun SemanticsNode.collectTexts(into: MutableList<String>) {
    config.getOrNull(SemanticsProperties.Text)?.forEach { into += it.text.replace("\u2060", "").replace('\u00A0', ' ') }
    children.forEach { it.collectTexts(into) }
}
