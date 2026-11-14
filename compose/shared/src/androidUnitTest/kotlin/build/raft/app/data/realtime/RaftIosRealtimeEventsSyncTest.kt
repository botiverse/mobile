package build.raft.app.data.realtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS realtime host must subscribe to the same server events as Android. It used to keep its
 * own hand-written list, which silently missed `agent:seen` and `joint-invites:changed`; it now
 * builds the list from the generated `GeneratedRealtimeContract.SocketEvent.serverWireEvents`.
 * This test keeps both halves honest: the generated Swift list equals Kotlin's, and the host
 * reads it (minus only the documented exclusions) instead of a literal list.
 */
class RaftIosRealtimeEventsSyncTest {
    @Test fun `generated Swift server events equal the Kotlin list`() {
        val swift = read("iosApp/iosApp/GeneratedRealtimeContract.swift")
        val constants = Regex("""static let (\w+) = "([^"]+)"""").findAll(swift)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val body = Regex("""static let serverWireEvents: \[String] = \[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(swift)?.groupValues?.get(1) ?: error("serverWireEvents not found in GeneratedRealtimeContract.swift")
        val swiftEvents = Regex("""SocketEvent\.(\w+)""").findAll(body)
            .map { constants[it.groupValues[1]] ?: error("unknown Swift constant ${it.groupValues[1]}") }
            .toList()
        assertEquals(RealtimeSocketEvents.ServerWireEvents, swiftEvents)
    }

    @Test fun `iOS host subscribes from the generated list with only documented exclusions`() {
        val host = read("iosApp/iosApp/RaftIosRealtimeHost.swift")
        assertTrue(
            "GeneratedRealtimeContract.SocketEvent.serverWireEvents.filter { !excludedServerWireEvents.contains(\$0) }" in host,
            "RaftIosRealtimeHost.serverWireEvents must be built from the generated list",
        )
        val excluded = Regex("""static let excludedServerWireEvents: Set<String> = \[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(host)?.groupValues?.get(1) ?: error("excludedServerWireEvents not found in RaftIosRealtimeHost.swift")
        val excludedNames = Regex("""SocketEvent\.(\w+)""").findAll(excluded).map { it.groupValues[1] }.toSet()
        // The web's feedback unread total is never the iOS app's; anything else needs a reason here.
        assertEquals(setOf("productFeedbackUnread"), excludedNames)
        // Events this audit found missing on iOS stay in the shared list.
        assertTrue(RealtimeSocketEvents.AgentSeen in RealtimeSocketEvents.ServerWireEvents)
        assertTrue(RealtimeSocketEvents.JointInvitesChanged in RealtimeSocketEvents.ServerWireEvents)
    }

    private fun read(relative: String): String {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val path: Path = listOf("..", ".", "compose")
            .map { workingDirectory.resolve(it).resolve(relative).normalize() }
            .firstOrNull(Files::isRegularFile) ?: error("Unable to locate $relative from $workingDirectory")
        return String(Files.readAllBytes(path))
    }
}
