package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the #94 Slack-aligned tab model semantics (task #94, gated by mobile_slack_nav_v0):
 * fixed four-tab set with order-only configurability, sanitize/parse/serialize round-trip,
 * and gate-off legacy fallback.
 */
class SlockSlackNavTabsTest {

    @Test
    fun defaultOrderIsSlackAligned() {
        assertEquals(listOf("home", "dms", "activity", "more"), SlockSlackNavTabs.DefaultOrder)
    }

    @Test
    fun parseFallsBackToDefaultOrder() {
        assertEquals(SlockSlackNavTabs.DefaultOrder, SlockSlackNavTabs.parseTabOrder(null))
        assertEquals(SlockSlackNavTabs.DefaultOrder, SlockSlackNavTabs.parseTabOrder(""))
        assertEquals(SlockSlackNavTabs.DefaultOrder, SlockSlackNavTabs.parseTabOrder(" , ,"))
        assertEquals(SlockSlackNavTabs.DefaultOrder, SlockSlackNavTabs.parseTabOrder("bogus,other"))
    }

    @Test
    fun sanitizeDropsUnknownKeysAndDedupes() {
        assertEquals(
            listOf("home", "dms", "activity", "more"),
            SlockSlackNavTabs.sanitizeTabOrder(listOf("home", "bogus", "dms", "dms"))
        )
    }

    @Test
    fun sanitizeAppendsMissingKeysInDefaultRelativeOrder() {
        assertEquals(
            listOf("more", "dms", "home", "activity"),
            SlockSlackNavTabs.sanitizeTabOrder(listOf("more", "dms"))
        )
    }

    @Test
    fun fullReorderIsRespected() {
        assertEquals(
            listOf("more", "activity", "dms", "home"),
            SlockSlackNavTabs.parseTabOrder("more,activity,dms,home")
        )
    }

    @Test
    fun serializeRoundTripsThroughParse() {
        val order = listOf("dms", "home", "more", "activity")
        val raw = SlockSlackNavTabs.serializeTabOrder(order)
        assertEquals("dms,home,more,activity", raw)
        assertEquals(order, SlockSlackNavTabs.parseTabOrder(raw))
    }

    @Test
    fun serializeSanitizesBeforeWriting() {
        assertEquals(
            "dms,home,activity,more",
            SlockSlackNavTabs.serializeTabOrder(listOf("dms", "unknown", "dms"))
        )
    }

    @Test
    fun gateOffAlwaysResolvesLegacyOrder() {
        assertEquals(
            listOf("home", "tasks", "members", "settings"),
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = false, persistedRawOrder = null)
        )
        assertEquals(
            listOf("home", "tasks", "members", "settings"),
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = false, persistedRawOrder = "more,dms,activity,home")
        )
    }

    @Test
    fun gateOnResolvesSanitizedSlackOrder() {
        assertEquals(
            SlockSlackNavTabs.DefaultOrder,
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = true, persistedRawOrder = null)
        )
        assertEquals(
            listOf("more", "home", "dms", "activity"),
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = true, persistedRawOrder = "more,home")
        )
    }
}
