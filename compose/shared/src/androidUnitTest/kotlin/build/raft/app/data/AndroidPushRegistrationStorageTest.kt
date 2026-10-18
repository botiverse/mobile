package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidPushRegistrationStorageTest {
    @Test
    fun pushRegistrationNamespaceRequiresAppScopeStorageOnAndroid() {
        // The Android push bridge persists its installation id and pending
        // registration in this namespace. If Android stops requiring (and the
        // host stops installing) app-scope storage for it, the bridge fails to
        // install and the device silently never registers for push.
        assertTrue(slockRequiresAppScopeKeyValueStore(SLOCK_PUSH_REGISTRATION_MMKV_NAMESPACE))
    }
}
