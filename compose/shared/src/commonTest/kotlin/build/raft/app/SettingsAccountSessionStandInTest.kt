package build.raft.app

import build.raft.app.data.AccountSettingsSnapshot
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.authSessionFixture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * artin (#wg-mobile d6e6d808): every open of Settings › Account showed the placeholders
 * (you@example.com, empty username/display name, default avatar) until the account loaded.
 * The signed-in session must stand in for the profile while the account is still loading.
 */
class SettingsAccountSessionStandInTest {
    private val strings = englishErrorPresentationStringsForTest()

    @Test
    fun loadingAccountShowsTheSignedInSessionProfile() {
        val base = authSessionFixture(userId = "ada")
        val session =
            base.copy(
                user = base.user.copy(name = "ada", displayName = "Ada Lovelace", avatarUrl = "https://cdn.example/ada.png")
            )
        val loading: ScreenLoadState<AccountSettingsSnapshot> = ScreenLoadState.Loading

        val profile = loading.toAccountPayload(session, null, strings).profile

        assertEquals("ada@example.com", profile.email)
        assertEquals("ada", profile.handle, "username field shows the username, not the email")
        assertEquals("Ada Lovelace", profile.displayName)
        assertEquals("https://cdn.example/ada.png", profile.avatarUrl)
    }

    @Test
    fun loadingAccountWithoutASessionStaysEmpty() {
        val loading: ScreenLoadState<AccountSettingsSnapshot> = ScreenLoadState.Loading

        val profile = loading.toAccountPayload(null, null, strings).profile

        assertEquals("", profile.email)
        assertEquals("", profile.handle)
        assertEquals("", profile.displayName)
    }
}
