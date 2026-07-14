package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadRealtimeIngressSourceContractTest {
    @Test
    fun threadHostsForwardRawRealtimeIntoSharedOwner() {
        val source =
            File(
                findRepoRoot(),
                "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt"
            ).readText()

        assertTrue(
            "A directly hosted Thread must decode ordinary realtime payloads without depending on AppShell.",
            source.contains("decodeSharedShellRealtimeEvent(") &&
                source.contains("resolveSharedShellRealtimeServerId(rawEventData, event, session)")
        )
        assertTrue(
            "Thread ingress must enter the shared owner so shell and route listeners share one dedup/fanout decision.",
            source.contains("realtimeDomainEventOwner.accept(") &&
                source.contains("source = \"thread_route\"")
        )
        assertFalse(
            "The raw listener must not bypass owner dedup by mutating the Thread store directly.",
            source.substringAfter("realtimeEvents.addListener { eventData ->")
                .substringBefore("onDispose { realtimeEvents.removeListener(callbackRef) }")
                .contains("latestThreadRealtimeEventHandler(event)")
        )
    }

    private fun findRepoRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        while (current.parentFile != null) {
            if (File(current, "AGENTS.md").isFile && File(current, "compose/settings.gradle.kts").isFile) {
                return current
            }
            current = requireNotNull(current.parentFile)
        }
        error("Could not find repo root from $userDir")
    }
}
