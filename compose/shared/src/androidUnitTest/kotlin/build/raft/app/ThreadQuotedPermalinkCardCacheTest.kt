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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
import build.raft.app.data.MessageContextHiddenTarget
import build.raft.app.data.RaftQuotedPermalinkViewer
import build.raft.app.thread.LocalThreadQuotedPermalinkLoader
import build.raft.app.thread.LocalThreadQuotedPermalinkLookup
import build.raft.app.thread.LocalThreadQuotedPermalinkOpenUpgrade
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.ThreadMessageCard
import build.raft.app.thread.ThreadQuotedPermalinkCache
import build.raft.app.thread.ThreadQuotedPermalinkCacheLookup
import build.raft.app.thread.ThreadQuotedPermalinkLoadResult
import build.raft.app.thread.ThreadQuotedPermalinkLoader
import build.raft.app.thread.ThreadQuotedPermalinkLookup
import build.raft.app.thread.ThreadQuotedPermalinkMessage
import build.raft.app.thread.raftMessageBodyTextStyle
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import kotlinx.coroutines.CompletableDeferred
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
 * #proj-mobile:#383: verifies [ThreadMessageCard] uses [LocalThreadQuotedPermalinkLookup] to
 * render already-cached quoted permalinks immediately on Frame 1 with zero "Loading message…"
 * flash and zero duplicate loader invocations when revisiting a conversation or scrolling back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadQuotedPermalinkCardCacheTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-quoted-permalink-cache"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun cachedQuotedPermalinkRendersImmediatelyWithoutLoadingFlashOrLoaderCall() {
        val cache = ThreadQuotedPermalinkCache()
        val viewer = RaftQuotedPermalinkViewer(userId = "user-a", serverId = "server-1", plan = "pro")
        cache.put(viewer.userId, viewer.serverId, QuotedChannelId, QuotedMessageId, LoadedQuote)

        var loaderCalls = 0
        val neverFinishes = CompletableDeferred<ThreadQuotedPermalinkLoadResult>()
        val loader: ThreadQuotedPermalinkLoader = {
            loaderCalls += 1
            neverFinishes.await()
        }
        val lookup: ThreadQuotedPermalinkLookup = { target ->
            cache.peekLookup(viewer.userId, viewer.serverId, target.channelId.value, target.messageId.value)
        }

        val root =
            render {
                CompositionLocalProvider(
                    LocalThreadQuotedPermalinkLoader provides loader,
                    LocalThreadQuotedPermalinkLookup provides lookup,
                ) {
                    MessageRow("cached-row", RaftLinkMessage)
                }
            }

        val texts = root.textsUnder("cached-row")
        assertTrue(texts.any { QuotedBody in it }, "cached quote must render immediately: $texts")
        assertFalse(texts.any { "Loading message" in it }, "cached quote must never flash Loading: $texts")
        assertEquals(0, loaderCalls, "cached hit must invoke loader 0 times")
    }

    @Test
    fun coldLoadPopulatesCacheSoRemountingRowSkipsDuplicateFetch() {
        val cache = ThreadQuotedPermalinkCache()
        val viewer = RaftQuotedPermalinkViewer(userId = "user-a", serverId = "server-1", plan = "pro")
        var networkFetches = 0

        val loader: ThreadQuotedPermalinkLoader = { target ->
            cache.getOrFetch(
                viewerProvider = { viewer },
                channelId = target.channelId.value,
                messageId = target.messageId.value,
            ) {
                networkFetches += 1
                LoadedQuote
            }?.let { ThreadQuotedPermalinkLoadResult.Loaded(it) } ?: ThreadQuotedPermalinkLoadResult.Unavailable
        }
        val lookup: ThreadQuotedPermalinkLookup = { target ->
            cache.peekLookup(viewer.userId, viewer.serverId, target.channelId.value, target.messageId.value)
        }
        val mountEpoch = mutableStateOf(0)

        val root =
            render {
                CompositionLocalProvider(
                    LocalThreadQuotedPermalinkLoader provides loader,
                    LocalThreadQuotedPermalinkLookup provides lookup,
                ) {
                    key(mountEpoch.value) {
                        MessageRow("remount-row", RaftLinkMessage)
                    }
                }
            }

        assertEquals(1, networkFetches)
        assertTrue(root.textsUnder("remount-row").any { QuotedBody in it })

        // Simulate row scrolling off-screen and back on-screen (fresh composition).
        mountEpoch.value = 1
        settle(root)

        val afterRemount = root.textsUnder("remount-row")
        assertTrue(afterRemount.any { QuotedBody in it }, "remounted row renders cached quote: $afterRemount")
        assertFalse(afterRemount.any { "Loading message" in it }, "remounted row must not show Loading: $afterRemount")
        assertEquals(1, networkFetches, "remounted row must not issue a second fetch")
    }

    @Test
    fun cachedRetractedQuotedPermalinkRendersRetractedImmediatelyWithoutLoadingOrFetch() {
        val cache = ThreadQuotedPermalinkCache()
        val viewer = RaftQuotedPermalinkViewer(userId = "user-a", serverId = "server-1", plan = "pro")
        val retractedQuote =
            LoadedQuote.copy(
                item = LoadedQuote.item.copy(deletedAt = "2026-10-22T00:25:00Z"),
            )
        cache.put(viewer.userId, viewer.serverId, QuotedChannelId, QuotedMessageId, retractedQuote)

        var loaderCalls = 0
        val neverFinishes = CompletableDeferred<ThreadQuotedPermalinkLoadResult>()
        val loader: ThreadQuotedPermalinkLoader = {
            loaderCalls += 1
            neverFinishes.await()
        }
        val lookup: ThreadQuotedPermalinkLookup = { target ->
            cache.peekLookup(viewer.userId, viewer.serverId, target.channelId.value, target.messageId.value)
        }

        val root =
            render {
                CompositionLocalProvider(
                    LocalThreadQuotedPermalinkLoader provides loader,
                    LocalThreadQuotedPermalinkLookup provides lookup,
                ) {
                    MessageRow("retracted-row", RaftLinkMessage)
                }
            }

        val texts = root.textsUnder("retracted-row")
        assertFalse(texts.any { QuotedBody in it }, "retracted quote must not show original body: $texts")
        assertFalse(texts.any { "Loading message" in it }, "cached retracted quote must never flash Loading: $texts")
        assertEquals(0, loaderCalls, "cached retracted hit must invoke loader 0 times")
    }

    @Test
    fun knownUnavailableNegativeCacheRendersUnavailableImmediatelyWithoutLoadingOrLoaderCall() {
        val cache = ThreadQuotedPermalinkCache()
        val viewer = RaftQuotedPermalinkViewer(userId = "user-a", serverId = "server-1", plan = "free")
        cache.putUnavailable(viewer.userId, viewer.serverId, QuotedChannelId, QuotedMessageId)

        var loaderCalls = 0
        val neverFinishes = CompletableDeferred<ThreadQuotedPermalinkLoadResult>()
        val loader: ThreadQuotedPermalinkLoader = {
            loaderCalls += 1
            neverFinishes.await()
        }
        val lookup: ThreadQuotedPermalinkLookup = { target ->
            cache.peekLookup(viewer.userId, viewer.serverId, target.channelId.value, target.messageId.value)
        }

        val root =
            render {
                CompositionLocalProvider(
                    LocalThreadQuotedPermalinkLoader provides loader,
                    LocalThreadQuotedPermalinkLookup provides lookup,
                ) {
                    MessageRow("neg-cached-row", RaftLinkMessage)
                }
            }

        val texts = root.textsUnder("neg-cached-row")
        assertFalse(texts.any { "Loading message" in it }, "negative-cached unavailable quote must never flash Loading: $texts")
        assertEquals(0, loaderCalls, "negative cache hit must invoke loader 0 times")
    }

    @Test
    fun throwingQuotedPermalinkLoaderSettlesToUnavailableInsteadOfStuckLoading() {
        var loaderCalls = 0
        val loader: ThreadQuotedPermalinkLoader = {
            loaderCalls += 1
            throw IllegalStateException("boom")
        }
        val lookup: ThreadQuotedPermalinkLookup = { ThreadQuotedPermalinkCacheLookup.Miss }

        val root =
            render {
                CompositionLocalProvider(
                    LocalThreadQuotedPermalinkLoader provides loader,
                    LocalThreadQuotedPermalinkLookup provides lookup,
                ) {
                    MessageRow("throwing-row", RaftLinkMessage)
                }
            }

        val texts = root.textsUnder("throwing-row")
        assertEquals(1, loaderCalls)
        assertFalse(texts.any { "Loading message" in it }, "throwing loader must settle to Unavailable, not stay Loading: $texts")
    }

    @Test
    fun olderQuotedPermalinkRendersOlderNoticeWithoutMarkingLinkUnavailable() {
        val hiddenTarget =
            MessageContextHiddenTarget(
                messageId = QuotedMessageId,
                senderType = "user",
                senderId = "user-juno",
                senderName = "Juno",
                preview = "Older message",
                createdAt = "2020-01-02T00:00:00Z",
            )
        var loaderCalls = 0
        var upgradeOpened = 0
        val loader: ThreadQuotedPermalinkLoader = {
            loaderCalls += 1
            ThreadQuotedPermalinkLoadResult.Older(hiddenTarget)
        }
        val lookup: ThreadQuotedPermalinkLookup = { ThreadQuotedPermalinkCacheLookup.Older(hiddenTarget) }

        val root =
            render {
                CompositionLocalProvider(
                    LocalThreadQuotedPermalinkLoader provides loader,
                    LocalThreadQuotedPermalinkLookup provides lookup,
                    LocalThreadQuotedPermalinkOpenUpgrade provides { upgradeOpened += 1 },
                ) {
                    MessageRow("older-row", RaftLinkMessage)
                }
            }

        assertEquals(0, loaderCalls, "memoized Older lookup must invoke loader 0 times")
        val olderNotice = root.textsUnder("quoted-message-older")
        assertTrue(olderNotice.any { "30" in it }, "older notice must mention 30-day window: $olderNotice")
        val upgradeTexts = root.textsUnder("quoted-message-older-upgrade")
        assertTrue(upgradeTexts.isNotEmpty(), "older notice must render upgrade link: $olderNotice")
        val upgradeNode =
            (root as RootForTest).semanticsOwner.unmergedRootSemanticsNode.find {
                it.config.getOrNull(SemanticsProperties.TestTag) == "quoted-message-older-upgrade"
            }
        assertNotNull(upgradeNode, "upgrade link node must exist")
        val clickAction = upgradeNode.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertNotNull(clickAction, "upgrade link must have click action")
        clickAction.action?.invoke()
        assertEquals(1, upgradeOpened, "tapping upgrade link must invoke LocalThreadQuotedPermalinkOpenUpgrade")
        val rowTexts = root.textsUnder("older-row")
        assertTrue(rowTexts.any { "Juno" in it }, "older card header must show sender name: $rowTexts")
        assertFalse(rowTexts.any { "Unavailable" in it }, "inline permalink must stay normal, not Unavailable: $rowTexts")
    }

    private fun render(content: @Composable () -> Unit): View {
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        activity.setContent {
            CompositionLocalProvider(
                LocalRaftSpinnerStillStep provides 0,
                LocalActivity provides RaftPageActivity(livePager),
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
        settle()
        return assertNotNull(findComposeRoot(activity.window.decorView), "no compose root")
    }

    private fun settle(root: View? = null) {
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
        (root as? RootForTest)?.measureAndLayoutForTest()
        shadowOf(Looper.getMainLooper()).idle()
    }
}

@Composable
private fun MessageRow(
    tag: String,
    message: RaftThreadMessageItem,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { testTag = tag },
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
        )
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
    assertNotNull(tagged, "no row tagged $tag")
    return buildList { tagged.collectTexts(this) }
}

private fun SemanticsNode.find(predicate: (SemanticsNode) -> Boolean): SemanticsNode? =
    if (predicate(this)) this else children.firstNotNullOfOrNull { it.find(predicate) }

private fun SemanticsNode.collectTexts(into: MutableList<String>) {
    config.getOrNull(SemanticsProperties.Text)?.forEach { into += it.text.replace("\u2060", "").replace('\u00A0', ' ') }
    children.forEach { it.collectTexts(into) }
}

private const val QuotedChannelId = "47d614ba-c55e-412a-874f-620e6d2fb849"
private const val QuotedMessageId = "1949b70d-3814-4e40-8364-8ffad2fff299"
private const val RaftPermalink = "https://app.raft.build/s/acme/channel/$QuotedChannelId?msg=$QuotedMessageId"
private const val QuotedBody = "Instant cached quote body"

private val RaftLinkMessage =
    RaftThreadMessageItem(
        id = "m-raft-cache",
        authorId = "user-niko",
        authorName = "Niko",
        authorKind = "human",
        avatarLabel = "N",
        timestampLabel = "2026-10-26T09:40:00Z",
        body = "See $RaftPermalink",
    )

private val LoadedQuote =
    ThreadQuotedPermalinkMessage(
        item =
            RaftThreadMessageItem(
                id = QuotedMessageId,
                authorId = "agent-alpha",
                authorName = "Alpha",
                authorKind = "agent",
                timestampLabel = "2026-10-22T00:24:00Z",
                body = QuotedBody,
            ),
    )
