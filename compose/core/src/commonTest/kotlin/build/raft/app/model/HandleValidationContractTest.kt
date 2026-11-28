package build.raft.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for UTS #39 Highly Restrictive single-script (`#17270`), UTS #39 confusable
 * skeleton & dual-key matching, and two-tier reserved handles (`#17291`), ported from
 * `botiverse/slock` `handleScript.test.ts` and `handleSkeleton.test.ts`.
 */
class HandleValidationContractTest {

    @Test
    fun singleScriptHandlesAcceptPureScriptsAndCjkLatinCombinations() {
        val allowed = listOf(
            "owner",
            "agent-007_x",
            "中文名字",
            "日本語かなカナ",
            "ラフトー",
            "한국어",
            "한국漢字",
            "bot中文",
            "raftラフト",
            "raft日本語かな",
            "raft한국",
            "ㄅㄆ中文",
            "владелец",
            "αβγ-01",
            "ｏｗｎｅｒ",
        )
        for (handle in allowed) {
            assertTrue(isSingleScriptHandle(handle), "expected single-script handle: $handle")
        }
    }

    @Test
    fun singleScriptHandlesRejectMixedScripts() {
        val rejected = listOf(
            "оwner",      // Cyrillic о (U+043E) + Latin
            "οwner",      // Greek ο (U+03BF) + Latin
            "оο",         // Cyrillic + Greek
            "abcאב",      // Latin + Hebrew
            "한국かな",     // Hangul + Hiragana
            "raft한국かな", // Latin + Hangul + Hiragana
            "ㄅかな",      // Bopomofo + Hiragana
        )
        for (handle in rejected) {
            assertFalse(isSingleScriptHandle(handle), "expected mixed-script rejection: $handle")
        }
    }

    @Test
    fun confusableSkeletonAndDualKeysMatchSlockContract() {
        assertEquals("rn", confusableSkeleton("m"))
        assertEquals("O", confusableSkeleton("0"))
        assertEquals("l", confusableSkeleton("1"))
        assertEquals("l", confusableSkeleton("I"))
        assertEquals("o", confusableSkeleton("о")) // Cyrillic о

        // Key A pairs collide.
        val colliding = listOf(
            "Ian" to "ian",
            "ADMIN" to "admin",
            "Adrnin" to "admin",
            "ring0" to "ringo",
            "a11ce" to "allce",
            "оwner" to "owner",
            "ｏｗｎｅｒ" to "owner",
            // Key B pairs collide (capital I vs lowercase l).
            "Ian" to "lan",
            "Iris" to "lris",
            // Leading @ and surrounding spaces ignored.
            " @Ian " to "Ian",
        )
        for ((a, b) in colliding) {
            assertTrue(handlesAreConfusable(a, b), "expected confusable: $a vs $b")
        }

        val distinct = listOf(
            "IAN" to "LAN",
            "Ivy" to "Lvy",
            "alice" to "bob",
            "ian" to "jan",
            "mark" to "rnarc",
            "owner" to "owners",
        )
        for ((a, b) in distinct) {
            assertFalse(handlesAreConfusable(a, b), "expected distinct: $a vs $b")
        }
    }

    @Test
    fun reservedHandlesFollowTwoTierAndConfusableRules() {
        val everyone = listOf(
            "all", "human", "humans", "agent", "agents", "here", "idle", "busy", "system",
            "reminders", "raft", "official", "staff", "slack", "external",
        )
        for (name in everyone) {
            assertTrue(isReservedHandle(name, HandlePrincipal.Person), "everyone reserved for person: $name")
            assertTrue(isReservedHandle(name, HandlePrincipal.Agent), "everyone reserved for agent: $name")
        }

        val peopleOnly = listOf(
            "admin", "administrator", "owner", "root", "support", "security", "moderator",
        )
        for (name in peopleOnly) {
            assertTrue(isReservedHandle(name, HandlePrincipal.Person), "people reserved for person: $name")
            assertFalse(isReservedHandle(name, HandlePrincipal.Agent), "people NOT reserved for agent: $name")
        }

        // Confusable look-alikes.
        assertTrue(isReservedHandle("Adrnin", HandlePrincipal.Person))
        assertFalse(isReservedHandle("Adrnin", HandlePrincipal.Agent))
        assertTrue(isReservedHandle("r00t", HandlePrincipal.Person))
        assertTrue(isReservedHandle("RAFT", HandlePrincipal.Agent))
        assertTrue(isReservedHandle("officiaI", HandlePrincipal.Agent))
        assertTrue(isReservedHandle("SIack", HandlePrincipal.Person))
        assertTrue(isReservedHandle("s1ack", HandlePrincipal.Agent))
        assertTrue(isReservedHandle("@0fficial", HandlePrincipal.Agent))

        // Non-reserved stay open.
        assertFalse(isReservedHandle("Sl4ck", HandlePrincipal.Person))
        assertFalse(isReservedHandle("raftbot", HandlePrincipal.Person))
        assertFalse(isReservedHandle("admins", HandlePrincipal.Person))
    }
}
