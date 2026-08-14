package build.raft.app.data

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun fixtureBytesArePinnedToImmutablePhaseOneLanding() {
        val sha256 =
            MessageDigest.getInstance("SHA-256")
                .digest(fixtureBytes)
                .joinToString("") { byte -> "%02x".format(byte) }

        assertEquals(
            "22bc4d85498aa2e476fa4424956746c17fa8bdf31763b7471eaab52c43e8c0ef",
            sha256,
            "Fixture drifted from botiverse/slock@7b858a79178f78870b1c96d7377656c6e32c0136",
        )
    }

    @Test
    fun fiveStateFixturePreservesKnownZeroPositiveAndUnknown() {
        val states = fixture.states.associateBy(ActivityUnreadFixtureState::state)

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
    val states: List<ActivityUnreadFixtureState>,
)

@Serializable
private data class ActivityUnreadFixtureState(
    val state: String,
    val entry: ServerUnreadSummaryDto? = null,
    val entries: List<ServerUnreadSummaryDto> = emptyList(),
)
