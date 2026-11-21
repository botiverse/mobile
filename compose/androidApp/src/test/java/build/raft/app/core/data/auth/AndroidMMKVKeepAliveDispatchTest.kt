package build.raft.app.core.data.auth

import android.app.Application
import build.raft.app.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import build.raft.app.data.NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.shared.AndroidRealtimeKeepAliveController
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidMMKVKeepAliveDispatchTest {
    private val queuedTasks = mutableListOf<() -> Unit>()

    @Before
    fun setUp() {
        queuedTasks.clear()
        AndroidAppScopeMMKV.resetKeepAliveRunnerForTest()
        AndroidAppScopeMMKV.keepAliveRunner = { task -> queuedTasks += task }
    }

    @After
    fun tearDown() {
        AndroidAppScopeMMKV.resetKeepAliveRunnerForTest()
    }

    @Test
    fun ignoresUnrelatedNamespacesAndKeys() {
        val app = RuntimeEnvironment.getApplication()
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = "otherNamespace",
            key = NOTIFICATION_ENABLED_PREFERENCE_KEY,
            value = "true",
        )
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = "unrelated_pref_key",
            value = "true",
        )
        assertEquals(0, queuedTasks.size)
    }

    @Test
    fun dispatchesNotificationAndKeepAlivePreferenceUpdatesOffCallerThread() {
        val app = RuntimeEnvironment.getApplication()
        val observedActions = mutableListOf<String>()
        AndroidAppScopeMMKV.keepAliveActionExecutor = { _, action -> observedActions += action }

        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = NOTIFICATION_ENABLED_PREFERENCE_KEY,
            value = "false",
        )
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = AndroidRealtimeKeepAliveController.localPreferenceKey(NOTIFICATION_ENABLED_PREFERENCE_KEY),
            value = "true",
        )
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY,
            value = "true",
        )
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = AndroidRealtimeKeepAliveController.localPreferenceKey(
                NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
            ),
            value = "false",
        )
        // Nothing executed inline on the caller thread.
        assertEquals(emptyList<String>(), observedActions)
        assertEquals(4, queuedTasks.size)

        queuedTasks.forEach { it() }
        assertEquals(listOf("refresh", "refresh", "start", "stop"), observedActions)
    }

    @Test
    fun defaultRunnerExecutesRapidTogglesInSerialFifoOrderOffCallerThread() {
        val app = RuntimeEnvironment.getApplication()
        val callerThread = Thread.currentThread()
        val observedActions = Collections.synchronizedList(mutableListOf<String>())
        val workerThreads = Collections.synchronizedList(mutableListOf<Thread>())
        val done = CountDownLatch(3)

        AndroidAppScopeMMKV.resetKeepAliveRunnerForTest()
        AndroidAppScopeMMKV.keepAliveActionExecutor = { _, action ->
            workerThreads += Thread.currentThread()
            observedActions += action
            done.countDown()
        }

        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY,
            value = "true",
        )
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY,
            value = "false",
        )
        AndroidAppScopeMMKV.maybeUpdateForegroundKeepAlive(
            context = app,
            mmapID = RaftLocalPreferenceStore.KuiklyNamespace,
            key = NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY,
            value = "true",
        )

        assertTrue("timed out waiting for serial keep-alive runner", done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("start", "stop", "start"), observedActions.toList())
        workerThreads.forEach { thread -> assertNotEquals(callerThread, thread) }
    }
}
