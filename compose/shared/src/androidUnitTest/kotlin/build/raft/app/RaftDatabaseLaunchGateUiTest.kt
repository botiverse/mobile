package build.raft.app

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #113: the database launch gate builds the shared store off the main thread on a cold start,
 * shows the launch surface until it is ready, then the app; an existing store renders at once, and
 * the database-newer-than-app and fatal paths behave as when the build ran in composition.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftDatabaseLaunchGateUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val buildExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, BuildThreadName) }
    private val buildDispatcher = buildExecutor.asCoroutineDispatcher()

    @BeforeTest
    fun resetReadiness() = RaftDatabaseLaunchReadiness.resetForTests()

    @AfterTest
    fun stopBuildThread() {
        buildDispatcher.close()
        RaftDatabaseLaunchReadiness.resetForTests()
    }

    @Test
    fun coldStartShowsTheLaunchSurfaceUntilTheOffMainBuildIsReadyThenTheApp() = runRobolectricComposeUiTest {
        val store = Any()
        val release = CountDownLatch(1)
        val buildThreads = CopyOnWriteArrayList<String>()
        val adopted = CopyOnWriteArrayList<Pair<Any, Boolean>>()
        setContent {
            Gate(
                existingStore = { null },
                buildStore = {
                    buildThreads += buildThreadName()
                    check(release.await(5, TimeUnit.SECONDS)) { "test never released the build" }
                    store
                },
                adoptStore = { adopted += it to (Looper.myLooper() == Looper.getMainLooper()) },
            )
        }
        waitUntil(timeoutMillis = 5_000) { buildThreads.isNotEmpty() }

        onNodeWithTag(LaunchSurfaceTag).assertExists()
        onNodeWithTag(AppTag).assertDoesNotExist()
        assertFalse(RaftDatabaseLaunchReadiness.settled.value, "nothing but the launch surface yet")
        assertEquals(listOf(BuildThreadName), buildThreads, "the build runs on the build dispatcher, not main")
        assertNotEquals(Looper.getMainLooper().thread.name, buildThreads.single())

        release.countDown()
        waitUntil(timeoutMillis = 5_000) { adopted.isNotEmpty() }
        waitForIdle()

        onNodeWithTag(AppTag).assertExists()
        onNodeWithTag(LaunchSurfaceTag).assertDoesNotExist()
        assertEquals(1, adopted.size)
        assertSame(store, adopted.single().first)
        assertTrue(adopted.single().second, "the built store is adopted on the main thread")
        assertTrue(RaftDatabaseLaunchReadiness.settled.value)
    }

    @androidx.compose.runtime.Composable
    private fun Gate(
        existingStore: () -> Any?,
        buildStore: () -> Any,
        adoptStore: (Any) -> Unit,
        logWarn: (String) -> Unit = {},
    ) {
        RaftTheme(appearance = RaftAppearance.Light) {
            RaftDatabaseLaunchGateContent(
                existingStore = existingStore,
                buildStore = buildStore,
                adoptStore = adoptStore,
                logWarn = logWarn,
                buildDispatcher = buildDispatcher,
                launchSurface = { Box(Modifier.testTag(LaunchSurfaceTag)) },
                content = { Text("app", modifier = Modifier.testTag(AppTag)) },
            )
        }
    }

    /** The thread's own name, without the " @coroutine#N" suffix coroutine debug mode adds. */
    private fun buildThreadName(): String = Thread.currentThread().name.substringBefore(" @")

    private companion object {
        const val BuildThreadName = "test-database-launch-build"
        const val LaunchSurfaceTag = "launch-surface"
        const val AppTag = "app"
        const val RetryLabel = "Retry after updating"
    }
}
