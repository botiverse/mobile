package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SettingsAccountMobileOAuthRefreshTest {
    @Test
    fun onlySuccessfulAccountLinkCompletionReloadsCanonicalSettingsState() {
        assertTrue(
            shouldReloadSettingsAccountAfterMobileOAuth(
                buildJsonObject { put("status", "settings_account") }
            )
        )
        assertFalse(
            shouldReloadSettingsAccountAfterMobileOAuth(
                buildJsonObject { put("status", "login_error") }
            )
        )
        assertFalse(shouldReloadSettingsAccountAfterMobileOAuth(buildJsonObject {}))
        assertFalse(shouldReloadSettingsAccountAfterMobileOAuth(null))
    }
}
