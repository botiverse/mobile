package build.raft.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import build.raft.app.compat.pager.RaftPaneRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The Navigation 3 scene the desktop host draws its back stack with: pages under the top one stay
 * composed (state kept, like an Activity under another) but are not shown; a popped page leaves
 * composition; pages see the host lifecycle (#3944: an unfocused window stays STARTED).
 */
@OptIn(ExperimentalTestApi::class)
class RaftDesktopStackSceneTest {
    private class Probe {
        val composed = mutableListOf<String>()
        val disposed = mutableListOf<String>()
        val instances = mutableMapOf<String, Any>()
        val lifecycles = mutableMapOf<String, Lifecycle>()
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    @Composable
    private fun Page(name: String, probe: Probe) {
        val instance = remember { Any() }
        probe.instances[name] = instance
        probe.lifecycles[name] = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(Unit) {
            probe.composed += name
            onDispose { probe.disposed += name }
        }
        Box(Modifier.fillMaxSize().testTag(name))
    }

    @Composable
    private fun Display(backStack: MutableList<String>, probe: Probe) {
        val strategy = remember { RaftDesktopStackSceneStrategy<String>() }
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            sceneStrategies = listOf(strategy),
            entryProvider = { name -> NavEntry(name, contentKey = name) { Page(name, probe) } },
        )
    }

    @Test fun `push keeps the page below composed but hidden, pop shows it with its state`() = runComposeUiTest {
        val probe = Probe()
        val backStack = mutableStateListOf("shell")
        setContent { Display(backStack, probe) }
        onNodeWithTag("shell").assertIsDisplayed()
        val shellState = probe.instances.getValue("shell")

        runOnIdle { backStack += "thread" }
        onNodeWithTag("thread").assertIsDisplayed()
        onNodeWithTag("shell").assertIsNotDisplayed()
        assertEquals(listOf("shell", "thread"), probe.composed)
        assertEquals(emptyList(), probe.disposed)

        runOnIdle { backStack.removeAt(backStack.lastIndex) }
        onNodeWithTag("shell").assertIsDisplayed()
        // The shell was never recomposed from scratch; the popped page left composition.
        assertSame(shellState, probe.instances.getValue("shell"))
        assertEquals(listOf("shell", "thread"), probe.composed)
        assertEquals(listOf("thread"), probe.disposed)
    }

    @Test fun `back order is top first`() = runComposeUiTest {
        val probe = Probe()
        val backStack = mutableStateListOf("shell", "a", "b")
        setContent { Display(backStack, probe) }
        onNodeWithTag("b").assertIsDisplayed()
        runOnIdle { backStack.removeAt(backStack.lastIndex) }
        onNodeWithTag("a").assertIsDisplayed()
        runOnIdle { backStack.removeAt(backStack.lastIndex) }
        onNodeWithTag("shell").assertIsDisplayed()
        assertEquals(listOf("b", "a"), probe.disposed)
    }

    @Test fun `a fresh stack replaces every page`() = runComposeUiTest {
        val probe = Probe()
        val backStack = mutableStateListOf("shell", "a")
        setContent { Display(backStack, probe) }
        val oldShell = probe.instances.getValue("shell")
        runOnIdle {
            backStack.clear()
            backStack += "shell2"
        }
        onNodeWithTag("shell2").assertIsDisplayed()
        assertEquals(setOf("shell", "a"), probe.disposed.toSet())
        assertNotSame(oldShell, probe.instances.getValue("shell2"))
    }

    @Test fun `pages follow the host lifecycle, unfocused STARTED included`() = runComposeUiTest {
        val probe = Probe()
        val host = TestOwner()
        host.registry.currentState = Lifecycle.State.RESUMED
        val backStack = mutableStateListOf("shell", "thread")
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) { Display(backStack, probe) }
        }
        runOnIdle {
            assertEquals(Lifecycle.State.RESUMED, probe.lifecycles.getValue("thread").currentState)
            assertEquals(Lifecycle.State.RESUMED, probe.lifecycles.getValue("shell").currentState)
            host.registry.currentState = Lifecycle.State.STARTED
        }
        runOnIdle {
            assertEquals(Lifecycle.State.STARTED, probe.lifecycles.getValue("thread").currentState)
            assertEquals(Lifecycle.State.STARTED, probe.lifecycles.getValue("shell").currentState)
            host.registry.currentState = Lifecycle.State.CREATED
        }
        runOnIdle { assertEquals(Lifecycle.State.CREATED, probe.lifecycles.getValue("thread").currentState) }
    }

    @Test fun `pane roles ride along as entry metadata`() {
        fun role(page: String) = NavEntry(page, metadata = raftDesktopPaneRoleMetadata(page)) {}.raftDesktopPaneRole()
        assertEquals(RaftPaneRole.Supporting, role("compose_channel_settings"))
        assertEquals(RaftPaneRole.Supporting, role("compose_thread_members"))
        assertEquals(RaftPaneRole.Window, role(RaftDesktopPages.APP_SHELL))
        assertEquals(RaftPaneRole.Window, role("compose_thread"))
    }
}
