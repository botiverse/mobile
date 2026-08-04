package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsAccountMobileOAuthRefreshTest {
    @Test
    fun onlySuccessfulAccountLinkCompletionReloadsCanonicalSettingsState() {
        assertTrue(
            shouldReloadSettingsAccountAfterMobileOAuth(
                JSONObject().put("status", "settings_account")
            )
        )
        assertFalse(
            shouldReloadSettingsAccountAfterMobileOAuth(
                JSONObject().put("status", "login_error")
            )
        )
        assertFalse(shouldReloadSettingsAccountAfterMobileOAuth(JSONObject()))
        assertFalse(shouldReloadSettingsAccountAfterMobileOAuth(null))
    }
}
