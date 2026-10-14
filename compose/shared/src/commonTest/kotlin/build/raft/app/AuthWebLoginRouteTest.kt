package build.raft.app

import build.raft.app.data.SharedAuthRuntimeCapabilities
import build.raft.app.data.SharedAuthRuntimeCapabilityStore
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthWebLoginRouteTest {
    @Test fun webLoginOnlyHostIgnoresTheGate() {
        val ohos = SharedAuthRuntimeCapabilities(webLoginOnly = true)
        assertTrue(shouldUseWebLogin(ohos, webLoginGateEnabled = false))
    }

    @Test fun supportingHostFollowsTheGate() {
        val mobile = SharedAuthRuntimeCapabilities(webLoginSupported = true)
        assertTrue(shouldUseWebLogin(mobile, webLoginGateEnabled = true))
        assertFalse(shouldUseWebLogin(mobile, webLoginGateEnabled = false))
    }

    @Test fun hostWithoutWebLoginKeepsTheNativePage() {
        assertFalse(shouldUseWebLogin(SharedAuthRuntimeCapabilities(), webLoginGateEnabled = true))
    }

    @Test fun pageDataSeedsSupportAndLaterPagesWithoutTheKeyKeepIt() {
        val store = SharedAuthRuntimeCapabilityStore()
        store.seedFromAuthPageData(JSONObject().put("pageName", LOGIN_PAGE).put(AUTH_WEB_LOGIN_SUPPORTED_KEY, true))
        assertTrue(store.seedFromAuthPageData(JSONObject().put("pageName", LOGIN_PAGE)).webLoginSupported)
    }

    @Test fun freshAnswerSwitchesThePageOnlyBeforeInputAndWithinTheWindow() {
        assertTrue(shouldApplyFreshLoginPageChoice(elapsedMs = 500, interacted = false))
        assertFalse(shouldApplyFreshLoginPageChoice(elapsedMs = 500, interacted = true))
        assertFalse(shouldApplyFreshLoginPageChoice(elapsedMs = 2_500, interacted = false))
    }
}
