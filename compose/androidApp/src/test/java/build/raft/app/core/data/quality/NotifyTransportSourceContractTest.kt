package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task #354 source contract: the Android platform-event path must never
 * return to the Binder-limited system broadcast, large raw realtime events
 * must reach only the canonical AppShell ingest topic, and page surfaces
 * must never subscribe to the raw-ingest topic. Reverting the in-process
 * hub, the topic split, or the module override turns this red.
 */
class NotifyTransportSourceContractTest {
    @Test
    fun platformEventPathStaysInProcessAndRawEventsStayOnTheIngestTopic() {
        val root = findRepoRoot()
        val dispatcher =
            source(root, "compose/shared/src/androidMain/kotlin/build/raft/app/data/SlockPlatformEventBus.android.kt")
        val hub =
            source(root, "compose/shared/src/androidMain/kotlin/build/raft/app/data/SlockKuiklyEventHub.kt")
        val module =
            source(root, "compose/shared/src/androidMain/kotlin/build/raft/app/data/SlockInProcessNotifyModule.kt")
        val hostContext =
            source(root, "compose/androidApp/src/main/java/build/raft/app/shared/AndroidAppScopeRealtimeHostContext.kt")
        val activity =
            source(root, "compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt")
        val appShell =
            source(root, "compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt")
        val threadPage =
            source(root, "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt")

        // The dispatcher must publish via the hub and never via broadcast.
        assertTrue(dispatcher.contains("SlockKuiklyEventHub.publish"))
        assertFalse(dispatcher.contains("sendBroadcast"))
        assertFalse(dispatcher.contains("sendKuiklyEvent"))
        assertTrue(dispatcher.contains("slock_platform_event_dispatch"))
        // Raw-ingest events publish exactly once: no enhanced-envelope
        // mirror (a second multi-MiB allocation), and no size-based drop —
        // large legitimate events are always delivered.
        assertTrue(dispatcher.contains("isRawIngest"))
        assertFalse(dispatcher.contains("FAILSAFE"))
        assertFalse(dispatcher.contains("withheld"))

        // The replacement module owns the built-in name and never broadcasts.
        assertTrue(module.contains("const val MODULE_NAME = \"KRNotifyModule\""))
        assertFalse(module.contains("sendBroadcast"))
        assertFalse(module.contains("registerKuiklyBroadcastReceiver"))
        assertTrue(module.contains("SlockKuiklyEventHub"))
        assertTrue(activity.contains("moduleExport(SlockInProcessNotifyModule.MODULE_NAME)"))

        // The hub is a plain in-process registry: no Android IPC surface.
        assertFalse(hub.contains("import android."))
        assertFalse(hub.contains("sendBroadcast"))

        // Raw realtime fanout goes to the ingest topic only; the canonical
        // AppShell subscribes to it, page surfaces do not.
        val fanOut = sourceWindow(hostContext, "override fun fanOutRawRealtimeEvent", "override suspend fun maybePostBackgroundUnreadNotification")
        assertTrue(fanOut.contains("SlockRealtimeNotifyContract.RawIngestEventName"))
        assertFalse(fanOut.contains("eventName = SlockRealtimeNotifyContract.EventName"))
        assertTrue(appShell.contains("addRawIngestListener"))
        assertFalse(threadPage.contains("RawIngestEventName"))
        assertFalse(threadPage.contains("addRawIngestListener"))
    }

    private fun source(root: File, path: String): String =
        File(root, path).readText().replace("\r\n", "\n")

    private fun sourceWindow(source: String, startNeedle: String, endNeedle: String): String {
        val start = source.indexOf(startNeedle)
        val end = source.indexOf(endNeedle, start + startNeedle.length)
        assertTrue("Missing source window start: $startNeedle", start >= 0)
        assertTrue("Missing source window end: $endNeedle", end > start)
        return source.substring(start, end)
    }

    private fun findRepoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).canonicalFile
        repeat(8) {
            if (File(current, "compose/settings.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        assertEquals("Unable to locate repository root from $userDir", true, false)
        error("unreachable")
    }
}
