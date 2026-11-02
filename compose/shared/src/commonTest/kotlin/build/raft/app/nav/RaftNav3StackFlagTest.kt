package build.raft.app.nav

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.MOBILE_NAV3_STACK_FEATURE_FLAG_KEY
import build.raft.app.data.REGISTERED_SERVER_FEATURE_FLAG_KEYS
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Navigation 3 stack switch: default off, server value or local test switch, decided once per process. */
class RaftNav3StackFlagTest {
    private val store = InMemoryRaftKeyValueStore()

    @BeforeTest @AfterTest fun reset() = RaftNav3StackFlag.resetForTests()

    @Test fun `the key is evaluated with the other mobile flags`() {
        assertEquals("mobile_nav3_stack_v1", MOBILE_NAV3_STACK_FEATURE_FLAG_KEY)
        assertTrue(MOBILE_NAV3_STACK_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
    }

    @Test fun `off by default`() {
        assertFalse(RaftNav3StackFlag.stored(store))
        assertFalse(RaftNav3StackFlag.launchDecision { store })
    }

    @Test fun `the last server evaluation of the key is kept and other keys are ignored`() {
        RaftNav3StackFlag.recordServerEvaluation(store, "drafts_section_v0", true)
        assertFalse(RaftNav3StackFlag.stored(store))
        RaftNav3StackFlag.recordServerEvaluation(store, MOBILE_NAV3_STACK_FEATURE_FLAG_KEY, true)
        assertTrue(RaftNav3StackFlag.stored(store))
        // Kill switch: the next evaluation turns it off again.
        RaftNav3StackFlag.recordServerEvaluation(store, MOBILE_NAV3_STACK_FEATURE_FLAG_KEY, false)
        assertFalse(RaftNav3StackFlag.stored(store))
    }

    @Test fun `the local test switch turns it on whatever the server says`() {
        RaftNav3StackFlag.recordServerEvaluation(store, MOBILE_NAV3_STACK_FEATURE_FLAG_KEY, false)
        RaftNav3StackFlag.setLocalOverride(store, true)
        assertTrue(RaftNav3StackFlag.localOverride(store))
        assertTrue(RaftNav3StackFlag.stored(store))
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

    @Test fun `alpha builds default on, release builds default off`() {
        assertTrue(RaftNav3StackFlag.stored(store, buildDefault = true))
        assertTrue(RaftNav3StackFlag.localOverride(store, buildDefault = true), "the developer switch shows on")
        assertFalse(RaftNav3StackFlag.stored(store, buildDefault = false))
        assertFalse(RaftNav3StackFlag.localOverride(store, buildDefault = false))
    }

    @Test fun `an explicit local off wins over the alpha default`() {
        RaftNav3StackFlag.setLocalOverride(store, false)
        assertEquals(false, RaftNav3StackFlag.localOverrideSetting(store))
        assertFalse(RaftNav3StackFlag.stored(store, buildDefault = true))
        assertFalse(RaftNav3StackFlag.localOverride(store, buildDefault = true))
        RaftNav3StackFlag.setLocalOverride(store, true)
        assertTrue(RaftNav3StackFlag.stored(store, buildDefault = true))
    }

    @Test fun `the server still turns it on for release builds`() {
        RaftNav3StackFlag.recordServerEvaluation(store, MOBILE_NAV3_STACK_FEATURE_FLAG_KEY, true)
        assertTrue(RaftNav3StackFlag.stored(store, buildDefault = false))
        RaftNav3StackFlag.recordServerEvaluation(store, MOBILE_NAV3_STACK_FEATURE_FLAG_KEY, false)
        assertFalse(RaftNav3StackFlag.stored(store, buildDefault = false))
        assertTrue(RaftNav3StackFlag.stored(store, buildDefault = true), "a server false does not undo the alpha default")
    }

    @Test fun `the launch decision follows the host's build default`() {
        RaftNav3StackFlag.buildDefaultOn = true
        assertTrue(RaftNav3StackFlag.launchDecision { store })
        RaftNav3StackFlag.resetForTests()
        assertFalse(RaftNav3StackFlag.launchDecision { store }, "release default")
    }
}
