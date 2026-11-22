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
    fun olderPasskeysAndAnAuthenticatorKeepTheCodeEntryAndOfferTheBrowser() {
        val dialog =
            passkeyFirst(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode)
                .withOnlyOlderPasskeys(webStepUpOffered = true)

        assertEquals(SecondFactorStepUpMethod.Totp, dialog.method, "the 6-digit code stays")
        assertEquals(listOf(SecondFactorStepUpMethod.Totp, SecondFactorStepUpMethod.BackupCode), dialog.methods)
        assertEquals(SecondFactorStepUpBanner.PasskeyUnsupported, dialog.banner)
        assertTrue(dialog.webStepUpBesideCode, "Verify with your passkey in the browser, under the field")
        assertEquals(SecondFactorWebStepUpPhase.Ready, dialog.webStepUp)
        assertFalse(dialog.continuesInBrowser, "the browser opens only when the person picks it")
    }

    @Test
    fun olderPasskeysOnlyGoStraightToTheBrowserAsBefore() {
        val dialog = passkeyFirst().withOnlyOlderPasskeys(webStepUpOffered = true)

        assertNull(dialog.method)
        assertNull(dialog.banner)
        assertTrue(dialog.continuesInBrowser, "Continue in browser, which the dialog opens by itself (#5129)")
        assertFalse(dialog.webStepUpBesideCode)
    }
}
