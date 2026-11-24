package build.raft.app

import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #417 (t3): pins the canonical kind contract to the legacy converters it will
 * replace. When t4-t6 migrate call sites, these assertions are the safety net
 * proving the canonical functions preserve today's semantics; the legacy
 * comparisons get deleted together with the legacy functions.
 */
class ConversationKindContractTest {
    @Test
    fun parsesEveryDirectMessageVocabularyVariant() {
        listOf("dm", "direct", "direct_message", "direct-message", "directmessage", "DM", " Direct_Message ").forEach { raw ->
            assertEquals(ConversationKind.DirectMessage, parseConversationKindOrNull(raw), "raw=$raw")
        }
    }

    @Test
    fun parsesChannelShapedVocabularyIncludingVisibilityValues() {
        // "public"/"private" are visibility, not kind — both land on Channel and
        // the visibility itself stays a separate attribute.
        listOf("channel", "public", "private", "group", "Channel", " PUBLIC ").forEach { raw ->
            assertEquals(ConversationKind.Channel, parseConversationKindOrNull(raw), "raw=$raw")
        }
    }

    @Test
    fun unknownInputParsesToNullNeverToChannel() {
        // Rule 3 of the contract: no `?: Channel`-style silent default. #415's
        // DM-thread misroute came from exactly that default.
        listOf(null, "", "   ", "foo", "channel2", "dms").forEach { raw ->
            assertNull(parseConversationKindOrNull(raw), "raw=$raw")
        }
    }

    @Test
    fun parsedThreadParentSurfaceKindMatchesLegacyPerFileVocabTables() {
        // t5/#419 finale: the per-file private parent-surface vocab tables collapsed into
        // parse-through-the-contract + the qualified enum projection. Pin exact parity with
        // the deleted tables, including "direct" -> dm (which those tables accepted) and the
        // parent-shape rejections ("thread"/"system"/unknown -> null).
        mapOf(
            "dm" to RaftThreadSurfaceKind.DirectMessage,
            "direct_message" to RaftThreadSurfaceKind.DirectMessage,
            "direct-message" to RaftThreadSurfaceKind.DirectMessage,
            "directmessage" to RaftThreadSurfaceKind.DirectMessage,
            "direct" to RaftThreadSurfaceKind.DirectMessage,
            "channel" to RaftThreadSurfaceKind.Channel,
            "public" to RaftThreadSurfaceKind.Channel,
            "private" to RaftThreadSurfaceKind.Channel,
            "joint" to RaftThreadSurfaceKind.Channel,
            "group" to RaftThreadSurfaceKind.Channel
        ).forEach { (raw, expected) ->
            assertEquals(expected, raw.parsedThreadParentSurfaceKindOrNull(), "raw=$raw")
        }
        listOf(null, "", "thread", "system", "inbox", "bogus").forEach { raw ->
            assertNull(raw.parsedThreadParentSurfaceKindOrNull(), "raw=$raw")
        }
    }

    @Test
    fun wireChannelKindRoundTripsForTheKindsItCovers() {
        listOf(ConversationKind.Channel, ConversationKind.Joint, ConversationKind.DirectMessage).forEach { kind ->
            assertEquals(kind, parseConversationKindOrNull(kind.toWireChannelKindOrNull()), "kind=$kind")
        }
    }
}
