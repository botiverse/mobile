package build.raft.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
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
import build.raft.app.resources.RaftBundledFiles
import build.raft.app.resources.Res
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The quick reaction bar a double tap on a message shows (#3916), light and dark: three messages,
 * the second double tapped on its body text, the bar open just below where the second tap landed
 * ([ThreadMessageQuickReactionBarPopover], drawn in the page; in the app it is its own window).
 * The reaction sprite atlas is decoded synchronously, as in [ThreadMessageFooterScreenshotTest].
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h520dp-xxhdpi")
class ThreadMessageQuickReactionBarScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    // The reaction glyphs log through the live page's bridge: give them one.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-quick-reaction-bar-screenshot"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test fun quickReactionBarLight() = capture("thread.message.quick-reaction-bar", RaftAppAppearance.Light)

    @Test fun quickReactionBarDark() = capture("thread.message.quick-reaction-bar.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val atlas =
                remember {
                    val bytes = runBlocking { Res.readBytes(RaftBundledFiles.reactionAtlasV1.path) }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalThreadReactionAtlasImage provides atlas) {
                        QuickReactionBarOverMessages()
                    }
                }
            }
        }
}

private const val TappedMessage = "m-2"

@Composable
private fun QuickReactionBarOverMessages() {
    val density = LocalDensity.current
    var anchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    val rows =
        listOf(
            RaftThreadMessageItem(
                id = "m-1",
                authorId = "agent-alpha",
                authorName = "Alpha",
                authorKind = "agent",
                avatarLabel = "A",
                timestampLabel = "2026-10-29T09:30:00Z",
                body = "Staging is on v0.30.1: the new DM notification logic and the agent restart fix.",
            ),
            RaftThreadMessageItem(
                id = TappedMessage,
                authorId = "user-carol",
                authorName = "Carol",
                authorKind = "human",
                avatarLabel = "C",
                timestampLabel = "2026-10-29T09:34:00Z",
                body = "Checked the dashboards: error rate is flat and the restarts are gone.",
            ),
            RaftThreadMessageItem(
                id = "m-3",
                authorId = "user-niko",
                authorName = "Niko",
                authorKind = "human",
                avatarLabel = "N",
                timestampLabel = "2026-10-29T09:41:00Z",
                body = "Nice. Promoting it to production after lunch.",
            ),
        )
    Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        Column(Modifier.fillMaxSize().padding(top = RaftAppSpacing.Sm)) {
            rows.forEach { message ->
                val tapped = message.id == TappedMessage
                Box(
                    if (tapped) {
                        // The second tap, on the body's first line about a third of the way across.
                        Modifier.onGloballyPositioned { coordinates ->
                            if (anchor == null) {
                                val tap = with(density) { Offset(150.dp.toPx(), 44.dp.toPx()) }
                                anchor = RaftPopoverAnchor.forRootOffset(coordinates.positionInRoot() + tap)
                            }
                        }
                    } else {
                        Modifier
                    }
                ) {
                    ThreadMessageCard(
                        message = message,
                        messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                        timestampLabel = "9:${message.timestampLabel.substring(14, 16)} AM",
                        onShowActions = {},
                        onOpenMember = {},
                        onOpenThread = {},
                        onOpenTask = {},
                        onOpenTaskStatusMenu = {},
                        onToggleReaction = {},
                        onOpenAttachment = {},
                        onOpenMarkdownTag = { _, _ -> },
                        onOpenMarkdownUrl = {},
                    )
                }
            }
        }
        anchor?.let { tap ->
            ThreadMessageQuickReactionBarPopover(messageId = TappedMessage, anchor = tap, onDismiss = {}, onReact = {})
        }
    }
}
