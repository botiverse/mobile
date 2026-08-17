package build.raft.app.data

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerActivityUnreadFixtureTest {
    private val fixtureBytes: ByteArray by lazy {
        checkNotNull(javaClass.getResourceAsStream("/sync/unread-summary-activity.v1.json")) {
            "unread-summary-activity.v1.json missing from androidUnitTest resources"
        }.readBytes()
    }

    private val fixture: ActivityUnreadFixture by lazy {
        Json { ignoreUnknownKeys = true }
            .decodeFromString(ActivityUnreadFixture.serializer(), fixtureBytes.decodeToString())
    }

    @Test
    fun fixtureBytesMatchPinnedServerContract() {
        val sha256 =
            MessageDigest.getInstance("SHA-256")
                .digest(fixtureBytes)
                .joinToString("") { byte -> "%02x".format(byte) }

        assertEquals(
            "abfaacbe2c548a16a72060d00fb204ec45c39d1d4c1406c73a8cdbe7a6fa7e01",
            sha256,
            "Fixture drifted from botiverse/slock@803e8c8e89398c014e3664fd3e3c140bd982aba3",
        )
    }

    @Test
    fun fiveStateFixturePreservesKnownZeroPositiveAndUnknown() {
        val states = fixture.states.associateBy(ActivityUnreadFixtureState::state)

        assertEquals("task #235 Phase 1 v2.4.6", fixture.contract)
        assertTrue(fixture.renderRule.contains("absent/unknown ⇒ render the legacy broader unread NUMBER"))
        assertTrue(fixture.renderRule.contains("this field is never populated from the broader count"))
        assertEquals(0L, states.getValue("zero").entry?.knownActivityUnreadCount)
        assertEquals(3L, states.getValue("positive").entry?.knownActivityUnreadCount)
        assertEquals(2, states.getValue("positive").entry?.unreadCount)
        assertEquals(
            listOf(null, null),
            states.getValue("batch-failure-all-unknown").entries.map { it.knownActivityUnreadCount },
        )
        assertNull(states.getValue("unauthorized").entry)
        assertEquals(
            listOf(3L, null),
            states.getValue("mixed-known-unknown").entries.map { it.knownActivityUnreadCount },
        )
    }
}

@Serializable
private data class ActivityUnreadFixture(
    val contract: String,
    val states: List<ActivityUnreadFixtureState>,
    val renderRule: String,
)

@Serializable
private data class ActivityUnreadFixtureState(
    val state: String,
    val entry: ServerUnreadSummaryDto? = null,
    val entries: List<ServerUnreadSummaryDto> = emptyList(),
)
