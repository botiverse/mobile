package build.raft.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.ThreadFindIntent
import build.raft.app.data.ThreadFindMessage
import build.raft.app.data.ThreadFindWindow
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.thread.ThreadFindBarHost
import build.raft.app.thread.ThreadFindController
import build.raft.app.thread.ThreadFindTestTags
import build.raft.app.thread.ThreadTransientBackHandler
import build.raft.app.thread.ThreadTransientBackTarget
import build.raft.app.thread.dispatch
import build.raft.app.thread.rememberThreadFindController
import build.raft.app.thread.threadTransientBackTarget
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Search in this thread on a real composition (web `ThreadSearchBar`): the page's controller
 * ([rememberThreadFindController]) and bar ([ThreadFindBarHost]) with the page's Back
 * ([ThreadTransientBackHandler] over [threadTransientBackTarget]), as ThreadPage composes them.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadFindBarUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private companion object {
        // web message.threadPanel.nextMatch / previousMatch / closeThreadSearch (en).
        const val NEXT = "Next match"
        const val PREVIOUS = "Previous match"
        const val CLOSE = "Close thread search"
    }

    private val window =
        ThreadFindWindow(
            threadChannelId = "thread-1",
            origin = ThreadFindMessage("origin", null, "Artin", "The launch plan"),
            messages =
                listOf(
                    ThreadFindMessage("r1", 1, "Lagoon", "lunch first"),
                    ThreadFindMessage("r2", 2, "Lagoon", "Launch notes are in"),
                ),
        )

    @Composable
    private fun Page(onController: (ThreadFindController) -> Unit, onDispatcher: (OnBackPressedDispatcher?) -> Unit) {
        onDispatcher(LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher)
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = RaftAppearance.Light) {
                val controller = rememberThreadFindController("thread-1", taskSurface = false)
                onController(controller)
                val state by controller.store.state.collectAsState()
                ThreadTransientBackHandler(threadTransientBackTarget(activeModal = null, shareSelectionActive = false, inlineSearchVisible = state.open)) { target ->
                    if (target == ThreadTransientBackTarget.InlineSearch) controller.store.dispatch(ThreadFindIntent.Close)
                }
                Box(Modifier.fillMaxSize()) { ThreadFindBarHost(controller) }
            }
        }
    }

    @Test
    fun opensCountsStepsBothWaysAndClosesWithCloseAndBack() = runRobolectricComposeUiTest {
        var controller: ThreadFindController? = null
        var dispatcher: OnBackPressedDispatcher? = null
        setContent { Page(onController = { controller = it }, onDispatcher = { dispatcher = it }) }
        val store = assertNotNull(controller).store
        runOnIdle { store.dispatch(ThreadFindIntent.Window(window)) }

        // Closed until Search in thread opens it; no words, no count.
        onNodeWithTag(ThreadFindTestTags.Bar).assertDoesNotExist()
        runOnIdle { store.dispatch(ThreadFindIntent.Open) }
        onNodeWithTag(ThreadFindTestTags.Bar).assertExists()
        onNodeWithTag(ThreadFindTestTags.Count).assertTextEquals("")
        onNodeWithContentDescription(NEXT).assertIsNotEnabled()

        runOnIdle { store.dispatch(ThreadFindIntent.Query("LAUNCH")) }
        onNodeWithTag(ThreadFindTestTags.Count).assertTextEquals("1/2")
        assertEquals("origin", store.state.value.activeMessageId)

        onNodeWithContentDescription(NEXT).assertIsEnabled().performClick()
        onNodeWithTag(ThreadFindTestTags.Count).assertTextEquals("2/2")
        assertEquals("r2", store.state.value.activeMessageId)
        onNodeWithContentDescription(NEXT).performClick()
        onNodeWithTag(ThreadFindTestTags.Count).assertTextEquals("1/2")
        onNodeWithContentDescription(PREVIOUS).performClick()
        onNodeWithTag(ThreadFindTestTags.Count).assertTextEquals("2/2")

        // Close: the bar goes, its words stay.
        onNodeWithContentDescription(CLOSE).performClick()
        onNodeWithTag(ThreadFindTestTags.Bar).assertDoesNotExist()
        assertFalse(store.state.value.open)
        assertEquals("LAUNCH", store.state.value.query)

        // Reopened, it is where it was; Back closes it.
        runOnIdle { store.dispatch(ThreadFindIntent.Open) }
        onNodeWithTag(ThreadFindTestTags.Count).assertTextEquals("2/2")
        runOnIdle { assertNotNull(dispatcher, "the activity's Back dispatcher").onBackPressed() }
        onNodeWithTag(ThreadFindTestTags.Bar).assertDoesNotExist()
        assertFalse(store.state.value.open)
    }
}
