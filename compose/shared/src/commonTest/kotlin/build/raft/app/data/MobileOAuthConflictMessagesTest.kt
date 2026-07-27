package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MobileOAuthConflictMessagesTest {
    private fun message(error: String?, provider: String = "Google"): String? =
        mobileOAuthConflictMessageOrNull(
            providerName = provider,
            error = error,
            accountConflictMessage = { name -> "password_then_settings:$name" },
            providerConflictMessage = { name -> "other_user:$name" },
        )

    @Test
    fun theTwoConflictCodesNeverCollapseIntoTheSameCopy() {
        // The whole point: `account_conflict` (your own account, just not linked
        // yet) must never read like `provider_conflict` (someone else owns it).
        assertEquals("password_then_settings:Google", message("account_conflict"))
        assertEquals("other_user:Google", message("provider_conflict"))
    }

    @Test
    fun everyOtherCodeYieldsNullSoCallersKeepTheirOwnFallback() {
        assertNull(message("access_denied"))
        assertNull(message("expired"))
        assertNull(message("registration_disabled"))
        assertNull(message(""))
        assertNull(message(null))
        // Not a prefix/substring match — a code that merely contains a conflict
        // token must not be mapped.
        assertNull(message("account_conflict_v2"))
        assertNull(message("pre_account_conflict"))
    }

    @Test
    fun codeMatchingToleratesTransportCasingAndPadding() {
        assertEquals("password_then_settings:Google", message(" account_conflict "))
        assertEquals("password_then_settings:Google", message("ACCOUNT_CONFLICT"))
        assertEquals("other_user:Google", message("Provider_Conflict"))
    }

    @Test
    fun providerNameIsCarriedThroughUnchanged() {
        assertEquals("password_then_settings:GitHub", message("account_conflict", "GitHub"))
        assertEquals("other_user:GitHub", message("provider_conflict", "GitHub"))
    }
}
