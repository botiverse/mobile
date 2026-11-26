package build.raft.app.nav

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MOBILE_NAV3_STACK_FEATURE_FLAG_KEY
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Navigation 3 stack switch: default off, server value or local test switch, decided once per process. */
class RaftNav3StackFlagTest {
    private val store = InMemoryRaftKeyValueStore()

    @BeforeTest @AfterTest fun reset() = RaftNav3StackFlag.resetForTests()

    @Test fun `off by default`() {
        assertFalse(RaftNav3StackFlag.stored(store))
        assertFalse(RaftNav3StackFlag.launchDecision { store })
    }

    @Test fun `the last server evaluation of the key is kept and other keys are ignored`() {
        RaftNav3StackFlag.recordServerEvaluation(store, "server_labs_ui_v0", true)
        assertFalse(RaftNav3StackFlag.stored(store))
        RaftNav3StackFlag.recordServerEvaluation(store, MOBILE_NAV3_STACK_FEATURE_FLAG_KEY, true)
        assertTrue(RaftNav3StackFlag.stored(store))
        // Kill switch: the next evaluation turns it off again.
        RaftNav3StackFlag.recordServerEvaluation(store, MOBILE_NAV3_STACK_FEATURE_FLAG_KEY, false)
        assertFalse(RaftNav3StackFlag.stored(store))
    }

    @Test fun `the process keeps its launch decision until the next start`() {
        assertFalse(RaftNav3StackFlag.launchDecision { store })
        RaftNav3StackFlag.setLocalOverride(store, true)
        assertFalse(RaftNav3StackFlag.launchDecision { store }, "a switch must not change the open window")
        RaftNav3StackFlag.resetForTests() // next app start
        assertTrue(RaftNav3StackFlag.launchDecision { store })
    }

    @Test fun `a store failure reads off`() {
        assertFalse(RaftNav3StackFlag.launchDecision { error("no store yet") })
    }

    @Test fun `alpha builds default on and release builds default off`() {
        assertTrue(RaftNav3StackFlag.stored(store, buildDefault = true))
        assertTrue(RaftNav3StackFlag.localOverride(store, buildDefault = true), "the developer switch shows on")
        assertFalse(RaftNav3StackFlag.stored(store, buildDefault = false))
        assertFalse(RaftNav3StackFlag.localOverride(store, buildDefault = false))
    }
}
