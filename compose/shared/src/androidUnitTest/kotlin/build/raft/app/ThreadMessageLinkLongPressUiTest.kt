package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
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
import build.raft.app.markdown.LocalRaftMarkdownLinkLongPress
import build.raft.app.markdown.RaftMarkdownBody
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin, 1.24: a finger's long press on a link in a message opened nothing (the link's framework
 * box took the press, so the row's long press never started). Now it opens the message's menu, as
 * a long press anywhere else on the row does, with the link's section on top: the address, Open,
 * Copy link, Share and, for a link that is not Raft's own, Open in browser; letting go does not
 * also open the link. A tap still opens it. Drives a production row gesture owner
 * ([threadMessageLongPressActions], [threadMessageLinkLongPressHandler]) around [RaftMarkdownBody],
 * and draws the Classic menu chrome the way the page does for the published link.
 */
@OptIn(ExperimentalTestApi::class)
// Real text measurement, so a character's box holds the pointer.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h900dp")
class ThreadMessageLinkLongPressUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    // The link section reads the page's bridge: a live, registered page.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-message-link-long-press"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    private val opened = mutableListOf<String>()
    private var menuAnchor: RaftPopoverAnchor? = null
    private val host = ThreadInPlaceTextSelectionHost(enabled = false, copyText = {})

    private fun ComposeUiTest.showMessage() {
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent {
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalThreadInPlaceTextSelectionHost provides host,
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(24.dp)) {
                        var anchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
                        val scope = rememberCoroutineScope()
                        val owner = rememberThreadMessageLongPressGestureOwner(instanceKey = "link-long-press", messageId = MESSAGE_ID)
                        val linkLongPress = remember(owner) { threadMessageLinkLongPressHandler(MESSAGE_ID, owner, host) }
                        Box(
                            Modifier.threadMessageLongPressActions(
                                messageId = MESSAGE_ID,
                                pointerSessionKey = 1,
                                gestureOwner = owner,
                                onShowActions = { shown ->
                                    anchor = shown
                                    menuAnchor = shown
                                },
                            )
                        ) {
                            CompositionLocalProvider(LocalRaftMarkdownLinkLongPress provides linkLongPress) {
                                RaftMarkdownBody(
                                    body = BODY,
                                    modifier = Modifier.width(600.dp),
                                    onOpenUrl = { opened += it },
                                )
                            }
                        }
                        anchor?.let { shown ->
                            // As ThreadMessagesListContent: the link published for this menu's anchor.
                            val link =
                                host.contextMenuLinkFor(MESSAGE_ID, shown)?.let { url ->
                                    rememberThreadMessageLinkMenuSection(
                                        url = url,
                                        webUrl = null,
                                        close = { anchor = null },
                                        openLink = { opened += it },
                                        scope = scope,
                                    )
                                }
                            ThreadMessageMenuChrome(
                                actions = emptyList(),
                                showReactionActions = false,
                                reactionAtlasImage = null,
                                onReact = {},
                                link = link,
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.textNode() = onNode(hasText("Read", substring = true), useUnmergedTree = true)

    private fun ComposeUiTest.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        textNode().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private fun ComposeUiTest.charCenter(words: String): Offset =
        textLayout().run { getBoundingBox(layoutInput.text.text.indexOf(words) + 1).center }

    @Test
    fun aLongPressOnALinkOpensTheMessageMenuWithTheLinkOnTop() =
        runRobolectricComposeUiTest {
            showMessage()
            textNode().performTouchInput { longClick(charCenter("the docs")) }
            waitForIdle()
            assertNotNull(menuAnchor, "the message menu opened")
            assertEquals(LINK, host.contextMenuLinkFor(MESSAGE_ID, menuAnchor!!), "the menu's link is the pressed one")
            onAllNodesWithText(LINK, useUnmergedTree = true).assertCountEquals(1)
            for (label in listOf("Open", "Copy link", "Share", "Open in browser")) {
                onAllNodesWithText(label, useUnmergedTree = true).assertCountEquals(1)
            }
            assertTrue(opened.isEmpty(), "letting go of the long press opens nothing: $opened")
        }

    @Test
    fun aLongPressOffTheLinksOpensTheMenuWithoutALinkSection() =
        runRobolectricComposeUiTest {
            showMessage()
            textNode().performTouchInput { longClick(charCenter("launch")) }
            waitForIdle()
            assertNotNull(menuAnchor, "the message menu opened")
            assertNull(host.contextMenuLinkFor(MESSAGE_ID, menuAnchor!!))
            onAllNodesWithText("Open in browser", useUnmergedTree = true).assertCountEquals(0)
            assertTrue(opened.isEmpty())
        }

    @Test
    fun aTapOnALinkStillOpensIt() =
        runRobolectricComposeUiTest {
            showMessage()
            textNode().performTouchInput { click(charCenter("the docs")) }
            waitForIdle()
            assertEquals(listOf(LINK), opened)
            assertNull(menuAnchor, "a tap opens no menu")
        }

    private companion object {
        const val MESSAGE_ID = "m1"
        const val LINK = "https://example.com/docs"
        const val BODY = "Read [the docs]($LINK) before the launch review tomorrow"
    }
}
