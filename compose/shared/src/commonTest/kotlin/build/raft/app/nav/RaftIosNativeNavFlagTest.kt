package build.raft.app.nav

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftIosNativeNavFlagTest {
    private val store = InMemoryRaftKeyValueStore()

    @BeforeTest @AfterTest fun reset() = RaftIosNativeNavFlag.resetForTests()

    private fun iPhone() {
        RaftIosNativeNavFlag.hostSupported = true
    }

    @Test
    fun offByDefaultOnAnIPhoneWithNothingStored() {
        iPhone()
        assertFalse(RaftIosNativeNavFlag.stored(store))
        assertFalse(RaftIosNativeNavFlag.launchDecision { store }, "a fresh install keeps today's navigation")
    }

    @Test
    fun theLastServerEvaluationOfItsOwnKeyDecides() {
        iPhone()
        RaftIosNativeNavFlag.recordServerEvaluation(store, "mobile_nav3_stack_v1", true)
        assertFalse(RaftIosNativeNavFlag.stored(store), "another key is ignored")
        RaftIosNativeNavFlag.recordServerEvaluation(store, MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY, true)
        assertTrue(RaftIosNativeNavFlag.stored(store))
        RaftIosNativeNavFlag.recordServerEvaluation(store, MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY, false)
        assertFalse(RaftIosNativeNavFlag.stored(store), "kill switch")
    }

    @Test
    fun neverOnWithoutAnIPhoneHost() {
        // iPad, Android, desktop: hostSupported stays false.
        RaftIosNativeNavFlag.recordServerEvaluation(store, MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY, true)
        RaftIosNativeNavFlag.setLocalOverride(store, true)
        assertFalse(RaftIosNativeNavFlag.stored(store))
        assertFalse(RaftIosNativeNavFlag.launchDecision(launchOverride = true) { store })
    }

    @Test
    fun aStorageFailureReadsOff() {
        iPhone()
        assertFalse(RaftIosNativeNavFlag.launchDecision { error("no store yet") })
    }

    @Test
    fun pushedAppShellServerStackPageIsMappedForNativeNavBar() {
        assertTrue(RaftIosNativeNavFlag.isMappedPage("compose_app_shell_server_page"))
        assertTrue(RaftIosNativeNavFlag.pageUsesNativeNavBar(enabled = true, pageName = "compose_app_shell_server_page"))
        assertFalse(RaftIosNativeNavFlag.pageUsesNativeNavBar(enabled = false, pageName = "compose_app_shell_server_page"))
    }
}
