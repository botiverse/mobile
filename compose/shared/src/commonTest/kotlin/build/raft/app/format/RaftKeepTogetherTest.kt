package build.raft.app.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The create-agent limit banner's "Upgrade for more" never breaks inside (web `whitespace-nowrap`). */
class RaftKeepTogetherTest {
    @Test
    fun anEnglishPhraseHasNoBreakableSpaceAndReadsTheSame() {
        val kept = raftKeepTogether("Upgrade for more")
        assertFalse(' ' in kept, "a normal space would let the line break inside the phrase: $kept")
        assertEquals("Upgrade for more", kept.replace('\u00A0', ' '))
    }

    @Test
    fun cjkLettersAreJoinedSoTheLineCannotBreakBetweenThem() {
        assertEquals("升\u2060级\u2060以", raftKeepTogether("升级以"))
        // Korean breaks at its spaces and between syllables: both are held.
        assertEquals("더\u00A0만\u2060들\u2060기", raftKeepTogether("더 만들기"))
    }

    @Test
    fun anEmojiIsNeverSplitByAJoiner() {
        assertEquals("升\u2060\uD83D\uDE80", raftKeepTogether("升\uD83D\uDE80"))
    }
}
