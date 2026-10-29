package build.raft.app.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject

/**
 * Back on the real desktop NavDisplay ([RaftDesktopNavDisplay]). artin pressed Esc in a chat on the
 * app shell and the app quit; Tenny: with only the root page left, Back does nothing.
 *
 * The window sends Esc first to the focused content (the host's key handler), then, unconsumed, as
 * the window's back event ([BackInput] here) to the navigation-event handlers: the page's own (the
 * shell's root handler: pop its main route, else closePage) before NavDisplay's.
 */
@OptIn(ExperimentalTestApi::class)
class RaftDesktopNavDisplayBackTest {
    /** The window's back event (Compose Desktop's Esc input). */
    private class BackInput : NavigationEventInput() {
        fun back() = dispatchOnBackCompleted()
    }

    private class Window : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher = NavigationEventDispatcher()
        val input = BackInput().also { navigationEventDispatcher.addInput(it) }
    }

    /** Like the app shell: a root back handler that pops its own route, else calls closePage. */
    @Composable
    private fun FakePage(navigator: RaftDesktopNavigator, page: RaftDesktopPage, shellBacks: MutableList<String>) {
        val focus = FocusRequester()
        if (page.args.pageName == RaftDesktopPages.APP_SHELL) {
            NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None)) {
                shellBacks += "shell"
                navigator.navigatorFor(page).closePage()
            }
        }
        Box(Modifier.fillMaxSize().testTag(page.args.pageName).focusRequester(focus).focusable())
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    private fun content(navigator: RaftDesktopNavigator, window: Window, shellBacks: MutableList<String>): @Composable () -> Unit =
        {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides window) {
                RaftDesktopNavDisplay(navigator) { page -> FakePage(navigator, page, shellBacks) }
            }
        }

    @Test fun `Esc with only the root page left does nothing and does not quit`() = runComposeUiTest {
        val navigator = RaftDesktopNavigator()
        val window = Window()
        val shellBacks = mutableListOf<String>()
        setContent(content(navigator, window, shellBacks))
        val root = navigator.pages.single()

        // Esc in the focused shell: the host's key handler leaves it unconsumed...
        onNodeWithTag(RaftDesktopPages.APP_SHELL).performKeyInput { pressKey(Key.Escape) }
        // ...so the window sends it as Back: the shell's own handler runs first, its closePage is a no-op.
        runOnIdle { window.input.back() }
        runOnIdle {
            assertEquals(listOf("shell"), shellBacks)
            assertEquals(listOf(root), navigator.pages.toList())
        }
        onNodeWithTag(RaftDesktopPages.APP_SHELL).assertIsDisplayed()
        assertEquals(false, navigator.back())
        assertEquals(listOf(root), navigator.pages.toList())
    }

    @Test fun `Esc closes the page pushed over the shell, then stops at the root`() = runComposeUiTest {
        val navigator = RaftDesktopNavigator()
        val window = Window()
        val shellBacks = mutableListOf<String>()
        setContent(content(navigator, window, shellBacks))
        runOnIdle { navigator.open("thread", JsonObject(emptyMap()), from = navigator.pages.single()) }
        onNodeWithTag("thread").assertIsDisplayed()

        onNodeWithTag("thread").performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(listOf(RaftDesktopPages.APP_SHELL), navigator.pages.map { it.args.pageName }) }
        onNodeWithTag(RaftDesktopPages.APP_SHELL).assertIsDisplayed()
        assertEquals(emptyList(), shellBacks)

        onNodeWithTag(RaftDesktopPages.APP_SHELL).performKeyInput { pressKey(Key.Escape) }
        runOnIdle { window.input.back() }
        runOnIdle { assertEquals(listOf(RaftDesktopPages.APP_SHELL), navigator.pages.map { it.args.pageName }) }
    }
}
