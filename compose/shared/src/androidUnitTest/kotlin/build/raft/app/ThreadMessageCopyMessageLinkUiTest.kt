package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * artin, 1.25: a long press on a link puts the link's section (with its own "Copy link") on top of
 * the Compact menu, so the message's Copy Link reads "Copy Message Link"; a plain long press on
 * the message keeps "Copy Link". The real Compact chrome, drawn with and without a link section.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadMessageCopyMessageLinkUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    // The reaction row logs through the page's bridge: a live, registered page.
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-message-copy-message-link"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    @Test
    fun aPlainLongPressKeepsCopyLink() =
        runRobolectricComposeUiTest {
            showCompactMenu(link = null)
            onAllNodesWithText("Copy Link", useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText("Copy Message Link", useUnmergedTree = true).assertCountEquals(0)
        }

    @Test
    fun aLongPressOnALinkSaysCopyMessageLink() =
        runRobolectricComposeUiTest {
            showCompactMenu(
                link =
                    ThreadMessageLinkMenuSection(
                        url = "https://docs.example.com/guides/release-checklist",
                        items = threadMessageLinkMenuActions(isRaftLink = false).map { action -> ThreadMessageLinkMenuItem(action) {} }
                    )
            )
            // The link section's own Copy link, then the message's Copy Message Link.
            onAllNodesWithText("Copy link", useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText("Copy Message Link", useUnmergedTree = true).assertCountEquals(1)
            onAllNodesWithText("Copy Link", useUnmergedTree = true).assertCountEquals(0)
        }

    private fun ComposeUiTest.showCompactMenu(link: ThreadMessageLinkMenuSection?) {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        setContent {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    ThreadNewMessageMenuChrome(
                        availability = ThreadNewMessageMenuActionAvailability(addReaction = true, copyLink = true),
                        onReact = {},
                        onCopy = {},
                        onReply = {},
                        onForward = {},
                        onSave = {},
                        onTranslate = {},
                        onSelect = {},
                        onMore = {},
                        link = link,
                    )
                }
            }
        }
        waitForIdle()
    }
}
