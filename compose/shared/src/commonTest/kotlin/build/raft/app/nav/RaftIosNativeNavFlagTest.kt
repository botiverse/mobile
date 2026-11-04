package build.raft.app.nav

import build.raft.app.RaftNativeNavBarAction
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY
import build.raft.app.data.REGISTERED_SERVER_FEATURE_FLAG_KEYS
import build.raft.app.data.SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS
import build.raft.app.raftNativeNavBarHeadMapped
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftIosNativeNavFlagTest {
    private val store = InMemoryRaftKeyValueStore()

    @BeforeTest @AfterTest fun reset() = RaftIosNativeNavFlag.resetForTests()

    private fun iPhone() {
        RaftIosNativeNavFlag.hostSupported = true
    }

    @Test
    fun theKeyIsRegisteredAndNotDefaultOn() {
        assertEquals("mobile_ios_native_nav_v1", MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY)
        assertTrue(MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        assertFalse(MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY in SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS)
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
    fun theDeveloperSwitchWinsOverTheServerBothWays() {
        iPhone()
        assertNull(RaftIosNativeNavFlag.localOverrideSetting(store))
        RaftIosNativeNavFlag.setLocalOverride(store, true)
        assertTrue(RaftIosNativeNavFlag.stored(store))
        RaftIosNativeNavFlag.recordServerEvaluation(store, MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY, true)
        RaftIosNativeNavFlag.setLocalOverride(store, false)
        assertFalse(RaftIosNativeNavFlag.stored(store), "local off beats a server on")
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
    fun decidedOncePerProcess() {
        iPhone()
        assertFalse(RaftIosNativeNavFlag.launchDecision { store })
        RaftIosNativeNavFlag.recordServerEvaluation(store, MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY, true)
        assertFalse(RaftIosNativeNavFlag.launchDecision { store }, "the open navigation must not change")
        RaftIosNativeNavFlag.resetForTests() // next app start
        iPhone()
        assertTrue(RaftIosNativeNavFlag.launchDecision { store })
    }

    @Test
    fun aStorageFailureReadsOff() {
        iPhone()
        assertFalse(RaftIosNativeNavFlag.launchDecision { error("no store yet") })
    }

    @Test
    fun theDebugLaunchOverrideWinsAndOnlyTakesOneOrZero() {
        assertEquals(true, RaftIosNativeNavFlag.parseLaunchOverride("1"))
        assertEquals(false, RaftIosNativeNavFlag.parseLaunchOverride(" 0 "))
        assertNull(RaftIosNativeNavFlag.parseLaunchOverride(null))
        assertNull(RaftIosNativeNavFlag.parseLaunchOverride("true"))
        iPhone()
        RaftIosNativeNavFlag.setLocalOverride(store, true)
        assertFalse(RaftIosNativeNavFlag.launchDecision(launchOverride = false) { store })
        RaftIosNativeNavFlag.resetForTests()
        iPhone()
        assertTrue(RaftIosNativeNavFlag.launchDecision(launchOverride = true) { InMemoryRaftKeyValueStore() })
    }

    @Test
    fun offMeansNoPageGetsTheNativeBar() {
        // Off, the iOS host (RaftPageControllerFactory) registers no bridge for any page: every head
        // reads LocalRaftNativeNavBar null and draws its Compose head, and Back is the full-page pan.
        val pages = RaftIosNativeNavFlag.mappedPageNames + listOf("compose_file_preview", "compose_search")
        assertTrue(pages.none { RaftIosNativeNavFlag.pageUsesNativeNavBar(enabled = false, pageName = it) })
        assertTrue(RaftIosNativeNavFlag.pageUsesNativeNavBar(enabled = true, pageName = "compose_slock_app_shell"))
        assertTrue(RaftIosNativeNavFlag.pageUsesNativeNavBar(enabled = true, pageName = "compose_thread"))
        assertFalse(RaftIosNativeNavFlag.pageUsesNativeNavBar(enabled = true, pageName = "compose_file_preview"), "not mapped")
    }

    @Test
    fun aHeadWithComposeButtonsButNoBarButtonsKeepsItsComposeHead() {
        val action = RaftNativeNavBarAction(id = "a", label = "A", systemSymbol = null, onClick = {})
        assertTrue(raftNativeNavBarHeadMapped(hasComposeTrailing = false, nativeActions = emptyList()))
        assertTrue(raftNativeNavBarHeadMapped(hasComposeTrailing = true, nativeActions = listOf(action)))
        assertFalse(raftNativeNavBarHeadMapped(hasComposeTrailing = true, nativeActions = emptyList()))
    }
}
