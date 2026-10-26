package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.ViewRootForTest
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
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkLoaded
import build.raft.app.data.AppLinkResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAppLinkPreviewApi
import build.raft.app.data.SharedAppLinkPreviewState
import build.raft.app.data.SharedAppLinkPreviewStore
import build.raft.app.data.appLinkReadScope
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Generate image" share captures are public (Remy / Tenny / AppFather 2026-10-26; web
 * `selectScreenshot.ts`): in capture mode ([LocalThreadShareCaptureActive]) a row drops the
 * quote preview of a Raft message link and its App link previews, and keeps the links the author
 * wrote, with the same spacing as a message with a plain link. Outside capture mode both show.
 * The screenshots (light and dark) set a Raft-link message beside a plain-link message and an
 * App-link message, all in capture mode; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadShareCaptureModeTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-share-capture-mode"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun captureModeDropsTheQuotePreviewKeepsTheLinkAndMatchesAPlainLinkRow() {
        val heights = mutableMapOf<String, Int>()
        val root =
            render {
                Row("normal-raft", RaftLinkMessage, capture = false, heights = heights)
                Row("capture-raft", RaftLinkMessage, capture = true, heights = heights)
                Row("capture-plain", PlainLinkMessage, capture = true, heights = heights)
                Row("never-quoted-raft", RaftLinkMessage, capture = false, heights = heights, loader = null)
            }

        // Normal mode: the quote preview is drawn under the link.
        val normal = root.textsUnder("normal-raft")
        assertTrue(normal.any { QuotedBody in it }, "normal mode shows the quote preview: $normal")
        assertTrue(normal.any { RaftPermalink in it }, "normal mode keeps the link: $normal")
        // Capture mode: no preview, the link text stays.
        val capture = root.textsUnder("capture-raft")
        assertFalse(capture.any { QuotedBody in it }, "capture mode drops the quote preview: $capture")
        assertTrue(capture.any { RaftPermalink in it }, "capture mode keeps the link text: $capture")

        // No blank gap: the preview leaves with its margin, so the row is exactly as tall as one
        // that never had a preview, and as a one-line message with a plain link.
        val captureHeight = heights.getValue("capture-raft")
        assertEquals(heights.getValue("never-quoted-raft"), captureHeight, "capture row vs never-quoted row: $heights")
        assertEquals(heights.getValue("capture-plain"), captureHeight, "capture row vs plain-link row: $heights")
        assertTrue(heights.getValue("normal-raft") > captureHeight, "the preview adds height outside capture mode: $heights")
    }

    @Test
    fun leavingCaptureModeBringsTheQuotePreviewBackInTheSameComposition() {
        val heights = mutableMapOf<String, Int>()
        val capture = mutableStateOf(true)
        val root = render { Row("toggled", RaftLinkMessage, capture = capture, heights = heights) }
        assertFalse(root.textsUnder("toggled").any { QuotedBody in it }, "capture mode: no preview")
        val captureHeight = heights.getValue("toggled")

        capture.value = false
        Snapshot.sendApplyNotifications()
        settle(root)
        assertTrue(root.textsUnder("toggled").any { QuotedBody in it }, "normal mode again: preview back")
        assertTrue(heights.getValue("toggled") > captureHeight, "the row grows back: $heights")

        // And entering capture mode on a live row (what "Generate image" does) drops it again.
        capture.value = true
        Snapshot.sendApplyNotifications()
        settle(root)
        assertFalse(root.textsUnder("toggled").any { QuotedBody in it }, "capture mode again: no preview")
        assertEquals(captureHeight, heights.getValue("toggled"), "the row shrinks back without a gap: $heights")
    }

    @Test
    fun captureModeTurnsAppLinkChipsBackIntoTheAuthorsLink() {
        val heights = mutableMapOf<String, Int>()
        val root =
            render {
                CompositionLocalProvider(LocalThreadAppLinkPreviewHost provides remember { appLinkFixtureHost() }) {
                    Row("normal-app", AppLinkMessage, capture = false, heights = heights)
                    Row("capture-app", AppLinkMessage.copy(id = CaptureAppLinkMessageId), capture = true, heights = heights)
                }
            }
        val normal = root.textsUnder("normal-app")
        assertTrue(normal.any { StampTitle in it }, "normal mode shows the App's title chip: $normal")
        val capture = root.textsUnder("capture-app")
        assertFalse(capture.any { StampTitle in it }, "capture mode shows no App data: $capture")
        assertTrue(capture.any { StampPr in it }, "capture mode shows the link the author wrote: $capture")
    }

    @Test
    fun policyStripsOnlyInCaptureMode() {
        val loaded = ThreadQuotedPermalinkUiState.Unavailable
        assertTrue(threadQuotedPermalinkPreviewVisible(loaded, shareCaptureActive = false))
        assertFalse(threadQuotedPermalinkPreviewVisible(loaded, shareCaptureActive = true))
        assertFalse(threadQuotedPermalinkPreviewVisible(null, shareCaptureActive = false))
        assertTrue(threadAppLinkPreviewsEnabled(sentMessage = true, shareCaptureActive = false))
        assertFalse(threadAppLinkPreviewsEnabled(sentMessage = true, shareCaptureActive = true))
        assertFalse(threadAppLinkPreviewsEnabled(sentMessage = false, shareCaptureActive = false))
    }

    @Test fun captureLight() = screenshot("thread.share-capture", RaftAppAppearance.Light)

    @Test fun captureDark() = screenshot("thread.share-capture.dark", RaftAppAppearance.Dark)

    private fun screenshot(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            Fixture(livePager) {
                CompositionLocalProvider(LocalThreadAppLinkPreviewHost provides remember { appLinkFixtureHost() }) {
                    val heights = remember { mutableMapOf<String, Int>() }
                    Row("capture-raft", RaftLinkMessage, capture = true, heights = heights)
                    Row("capture-plain", PlainLinkMessage, capture = true, heights = heights)
                    Row("capture-app", AppLinkMessage.copy(id = CaptureAppLinkMessageId), capture = true, heights = heights)
                }
            }
        }
    }

    private fun render(content: @Composable () -> Unit): View {
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        activity.setContent { CompositionLocalProvider(LocalRaftSpinnerStillStep provides 0) { Fixture(livePager, content) } }
        settle()
        return assertNotNull(findComposeRoot(activity.window.decorView), "no compose root")
    }

    private fun settle(root: View? = null) {
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
        // A recomposition in an already-drawn window: run its layout pass so positions report.
        (root as? RootForTest)?.measureAndLayoutForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }
}

@Composable
private fun Fixture(pager: Pager, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalActivity provides RaftPageActivity(pager),
        LocalInspectionMode provides true,
    ) {
        RaftDesignSystemTheme {
            Column(
                Modifier.fillMaxSize().background(RaftColor.Canvas).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { content() }
        }
    }
}

@Composable
private fun Row(
    tag: String,
    message: RaftThreadMessageItem,
    capture: Boolean,
    heights: MutableMap<String, Int>,
    loader: ThreadQuotedPermalinkLoader? = QuoteLoader,
) = Row(tag, message, remember { mutableStateOf(capture) }, heights, loader)

@Composable
private fun Row(
    tag: String,
    message: RaftThreadMessageItem,
    capture: MutableState<Boolean>,
    heights: MutableMap<String, Int>,
    loader: ThreadQuotedPermalinkLoader? = QuoteLoader,
) {
    CompositionLocalProvider(LocalThreadShareCaptureActive provides capture.value) {
        Column(
            Modifier
                .fillMaxWidth()
                .semantics { testTag = tag }
                .onGloballyPositioned { heights[tag] = it.size.height },
        ) {
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                timestampLabel = "9:40 AM",
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = { _, _ -> },
                onOpenMarkdownUrl = {},
                currentServerSlug = "acme",
                quotedPermalinkLoader = loader,
            )
        }
    }
}

private fun findComposeRoot(view: View): View? {
    if (view is ViewRootForTest) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
    }
    return null
}

/** Every text drawn under the node tagged [tag] (unmerged tree). */
private fun View.textsUnder(tag: String): List<String> {
    val root = (this as RootForTest).semanticsOwner.unmergedRootSemanticsNode
    val tagged = root.find { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    assertNotNull(tagged, "no row tagged $tag")
    return buildList { tagged.collectTexts(this) }
}

private fun SemanticsNode.find(predicate: (SemanticsNode) -> Boolean): SemanticsNode? =
    if (predicate(this)) this else children.firstNotNullOfOrNull { it.find(predicate) }

private fun SemanticsNode.collectTexts(into: MutableList<String>) {
    // Chips glue their words with word joiners / no-break spaces; compare plain text.
    config.getOrNull(SemanticsProperties.Text)?.forEach { into += it.text.replace("\u2060", "").replace('\u00A0', ' ') }
    children.forEach { it.collectTexts(into) }
}

private const val RaftPermalink = "https://app.raft.build/s/acme/channel/c-private?msg=msg-1"
// The same address on a host Raft doesn't own: a plain link that wraps like the permalink.
private const val PlainLink = "https://app.raft.built/s/acme/channel/c-private?msg=msg-1"
private const val QuotedBody = "Rolled back the migration"
private const val StampPr = "https://stamp.build/botiverse/mobile/pull/3654"
private const val StampTitle = "Fix the composer tray"
private const val AppLinkViewer = "user-niko:server-1"
private const val AppLinkConversation = "channel-1"
private const val AppLinkMessageId = "0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4e21"
private const val CaptureAppLinkMessageId = "0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4e22"

private fun message(id: String, body: String) =
    RaftThreadMessageItem(
        id = id,
        authorId = "user-niko",
        authorName = "Niko",
        authorKind = "human",
        avatarLabel = "N",
        timestampLabel = "2026-10-26T09:40:00Z",
        body = body,
    )

private val RaftLinkMessage = message("m-raft", "See $RaftPermalink")
private val PlainLinkMessage = message("m-plain", "See $PlainLink")
private val AppLinkMessage = message(AppLinkMessageId, "Merged: $StampPr")

private val QuoteLoader: ThreadQuotedPermalinkLoader = { _ ->
    ThreadQuotedPermalinkMessage(
        RaftThreadMessageItem(
            id = "msg-1",
            authorId = "agent-alpha",
            authorName = "Alpha",
            authorKind = "agent",
            timestampLabel = "2026-10-22T00:24:00Z",
            body = "$QuotedBody; the private error rate is flat again.",
        ),
    )
}

private fun appLinkFixtureHost(): ThreadAppLinkPreviewHost {
    val card = AppLinkResult.Card(StampPr, AppLinkApp("Stamp", null), "$StampTitle (#3654)")
    val state =
        SharedAppLinkPreviewState(
            entries =
                listOf(AppLinkMessageId, CaptureAppLinkMessageId).associate { id ->
                    appLinkReadScope(AppLinkViewer, id, AppLinkMessage.body) to
                        AppLinkLoaded(results = mapOf(StampPr to card), epochs = emptyMap())
                },
        )
    return ThreadAppLinkPreviewHost(
        store = SharedAppLinkPreviewStore(IdleAppLinkApi, CoroutineScope(Dispatchers.Unconfined), initialState = state),
        viewerKey = AppLinkViewer,
        serverId = "server-1",
        conversationId = AppLinkConversation,
        toast = {},
    )
}

private object IdleAppLinkApi : RaftAppLinkPreviewApi {
    override suspend fun readPreviews(messageId: String, serverId: String): HomeNetworkResponse = awaitCancellation()

    override suspend fun allow(appId: String, serverId: String): HomeNetworkResponse = awaitCancellation()

    override suspend fun decline(appId: String, serverId: String): HomeNetworkResponse = awaitCancellation()
}
