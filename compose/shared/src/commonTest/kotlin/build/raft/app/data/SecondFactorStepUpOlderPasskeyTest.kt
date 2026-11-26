package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Use passkey on iOS for someone with only older passkeys (under `raft.build`, which iOS can't ask
 * for: `passkey_rp_unavailable`) where the web step-up runs (#158, [withOnlyOlderPasskeys]). A
 * passkey under `app.raft.build`, and the browser option running the web step-up:
 * AppWebStepUpCoordinatorTest.
 */
class SecondFactorStepUpOlderPasskeyTest {
    /** The dialog as it opens: Use passkey first, then [codes]. */
    private fun passkeyFirst(vararg codes: SecondFactorStepUpMethod) =
        SecondFactorStepUpDialogState(
            methods = listOf(SecondFactorStepUpMethod.Passkey) + codes,
            method = SecondFactorStepUpMethod.Passkey,
            passkeyListed = true,
        )

    @Test
    fun olderPasskeysAndAnAuthenticatorGoStraightToTheBrowserAndKeepOtherMethods() {
        val dialog =
            passkeyFirst(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode)
                .withOnlyOlderPasskeys(webStepUpOffered = true)

        assertNull(dialog.method, "continues in browser directly instead of jumping to TOTP (#proj-mobile:#188)")
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), dialog.methods)
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), dialog.otherMethods)
        assertNull(dialog.banner, "no PasskeyUnsupported warning banner when browser step-up handles it")
        assertTrue(dialog.continuesInBrowser, "Continue in browser opens immediately without a second tap")
        assertEquals(SecondFactorWebStepUpPhase.Ready, dialog.webStepUp)
        assertFalse(dialog.webStepUpBesideCode)
    }

    @Test
    fun olderPasskeysOnlyGoStraightToTheBrowserAsBefore() {
        val dialog = passkeyFirst().withOnlyOlderPasskeys(webStepUpOffered = true)

        assertNull(dialog.method)
        assertEquals(emptyList(), dialog.methods)
        assertNull(dialog.banner)
        assertTrue(dialog.continuesInBrowser, "Continue in browser, which the dialog opens by itself (#5129)")
        assertFalse(dialog.webStepUpBesideCode)
    }

    @Test
    fun withoutWebStepUpOlderPasskeysStillFallBackToCodeWithBanner() {
        val dialog =
            passkeyFirst(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode)
                .withOnlyOlderPasskeys(webStepUpOffered = false)

        assertEquals(SecondFactorStepUpMethod.Totp, dialog.method)
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), dialog.methods)
        assertEquals(SecondFactorStepUpBanner.PasskeyUnsupported, dialog.banner)
        assertNull(dialog.webStepUp)
        assertFalse(dialog.continuesInBrowser)
        assertFalse(dialog.webStepUpBesideCode)
    }
}
