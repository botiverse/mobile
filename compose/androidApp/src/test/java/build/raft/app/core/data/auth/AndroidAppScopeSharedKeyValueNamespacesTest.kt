package build.raft.app.core.data.auth

import build.raft.app.data.SLOCK_PUSH_REGISTRATION_MMKV_NAMESPACE
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppScopeSharedKeyValueNamespacesTest {
    @Test
    fun pushRegistrationNamespaceGetsAppScopeStorage() {
        // SlockAndroidPushRegistrationBridge persists its installation id and
        // pending registration here. Without an installed factory, the shared
        // provider throws, the bridge fails to install, and the device silently
        // never registers for push.
        assertTrue(SLOCK_PUSH_REGISTRATION_MMKV_NAMESPACE in androidAppScopeSharedKeyValueNamespaces)
    }
}
