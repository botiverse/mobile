package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirrors botiverse/slock #15664 (staging 5a2489ea7): the cases of
 * `packages/server/src/routes/agentRouteRefusals.test.ts` ("a new or changed description fits the
 * 300 limit…"), `profileDescriptionLimit.api.test.ts` and
 * `packages/web/tests/agentProfileEditDescriptionLimit.test.tsx`, against the shared helper.
 */
class ProfileDescriptionTest {
    private val fits = "x".repeat(PROFILE_DESCRIPTION_MAX_LENGTH)
    private val legacy = "y".repeat(412)

    @Test
    fun theLimitIs300() {
        assertEquals(300, PROFILE_DESCRIPTION_MAX_LENGTH)
        assertEquals("profile_description_invalid", PROFILE_DESCRIPTION_INVALID_CODE)
    }

    @Test
    fun withinTheLimitAlwaysFitsWhateverIsStored() {
        assertFalse(profileDescriptionTooLong(fits, null))
        assertFalse(profileDescriptionTooLong(fits, legacy))
        assertFalse(profileDescriptionTooLong("", legacy))
    }

    @Test
    fun overTheLimitAndNewOrChangedIsTooLong() {
        assertTrue(profileDescriptionTooLong("$fits!", null))
        assertTrue(profileDescriptionTooLong("$fits!", ""))
        assertTrue(profileDescriptionTooLong("${legacy}z", legacy))
        assertTrue(profileDescriptionTooLong(legacy.drop(1), legacy), "shortened, still over: refused")
        assertTrue(profileDescriptionTooLong("z".repeat(PROFILE_DESCRIPTION_MAX_LENGTH + 1), fits), "after a short one, an over-long one is a change")
    }

    @Test
    fun overTheLimitButUnchangedSavesAsItIs() {
        assertFalse(profileDescriptionTooLong(legacy, legacy))
        assertFalse(profileDescriptionTooLong(" $legacy\n", legacy), "whitespace at the ends is no change")
        assertFalse(profileDescriptionTooLong(legacy, "$legacy  "))
    }

    @Test
    fun lineBreaksCompareAsStored() {
        val stored = "Legacy intro.\n" + "x".repeat(398)
        assertFalse(profileDescriptionTooLong(stored.replace("\n", "\r\n"), stored), "CRLF is the stored LF")
        assertFalse(profileDescriptionTooLong(stored.replace("\n", "\r"), stored), "a lone CR is the stored LF")
        // 299 characters and a CRLF count as 300 once normalised: it fits.
        assertFalse(profileDescriptionTooLong("x".repeat(150) + "\r\n" + "x".repeat(149), null))
        assertEquals(PROFILE_DESCRIPTION_MAX_LENGTH, profileDescriptionLength("x".repeat(150) + "\r\n" + "x".repeat(149)))
    }

    @Test
    fun countsTheTrimmedTextLikeTheWeb() {
        assertEquals(412, profileDescriptionLength(legacy))
        assertEquals(3, profileDescriptionLength("  abc \n"))
        // JS trim also drops the no-break space and the byte-order mark at the ends.
        assertEquals(3, profileDescriptionLength(" abc﻿"))
        assertFalse(profileDescriptionTooLong("　$fits　", null))
    }
}
