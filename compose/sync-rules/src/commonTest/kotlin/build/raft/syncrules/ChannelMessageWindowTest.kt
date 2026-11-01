package build.raft.syncrules

import build.raft.syncrules.CanonicalMessageV2SegmentSource.Latest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** SPIKE copy of the toCoverage assertions in :shared MessageParserTest (JSON parse step dropped). */
class ChannelMessageWindowTest {
    @Test
    fun aWindowProvesItsRangeOnlyForItsOwnScopeSchemaAndDomain() {
        val window = ChannelMessageWindow(
            schemaVersion = 1, domain = "receiver_visible_messages_v1", serverId = "server-1", scopeId = "channel-1",
            coveredAfterSeq = 0, coveredFromSeq = 7, coveredThroughSeq = 7, remoteHighWaterSeq = 7,
            hasGap = false, hasNewer = false, completeThroughLatest = true,
        )
        assertEquals(
            CanonicalMessageV2Coverage(1, 7, reachesOldest = true, headAtVerify = true, Latest),
            window.toCoverage("server-1", "channel-1", historyLimited = false, Latest),
        )
        // An unknown schema or domain proves nothing.
        assertNull(window.copy(schemaVersion = 2).toCoverage("server-1", "channel-1", historyLimited = false, Latest))
        assertNull(window.copy(domain = "other").toCoverage("server-1", "channel-1", historyLimited = false, Latest))
        // A window naming another channel or server (or none) is not this scope's proof.
        assertNull(window.toCoverage("server-1", "channel-2", historyLimited = false, Latest))
        assertNull(window.toCoverage("server-2", "channel-1", historyLimited = false, Latest))
        assertNull(window.copy(scopeId = null).toCoverage("server-1", "channel-1", historyLimited = false, Latest))
        assertNull(window.copy(serverId = null).toCoverage("server-1", "channel-1", historyLimited = false, Latest))
        // A missing bound proves nothing.
        assertNull(window.copy(coveredAfterSeq = null).toCoverage("server-1", "channel-1", historyLimited = false, Latest))
        assertNull(window.copy(completeThroughLatest = null).toCoverage("server-1", "channel-1", historyLimited = false, Latest))
    }
}
