package build.raft.app

import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.MOBILE_SLACK_NAV_FEATURE_FLAG_KEY
import build.raft.app.data.REGISTERED_SERVER_FEATURE_FLAG_KEYS
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMutationResult
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Locks the #94 Slack-aligned tab model semantics (task #94, gated by mobile_slack_nav_v0):
 * fixed four-tab set with order-only configurability, sanitize/parse/serialize round-trip,
 * and gate-off legacy fallback.
 */
class SlockSlackNavTabsTest {

    @Test
    fun slackNavFlagWireKeyIsPinnedAndRegisteredForServerEvaluation() {
        // Non-mirror teeth: pin the literal wire key so a rename or a dropped registration both
        // go RED (review #269: deleting the list entry previously left the suite green).
        assertEquals("mobile_slack_nav_v0", MOBILE_SLACK_NAV_FEATURE_FLAG_KEY)
        assertTrue(
            "mobile_slack_nav_v0" in REGISTERED_SERVER_FEATURE_FLAG_KEYS,
            "mobile_slack_nav_v0 must stay registered in REGISTERED_SERVER_FEATURE_FLAG_KEYS"
        )
    }

    @Test
    fun slackNavFlagIsOverridableViaLocalFeatureOverrides() {
        // The PR claims diagnostics "Local Feature Overrides" can A/B the flag locally — this
        // proves the registry actually honors ForceOn/ForceOff/Server for the key.
        val registry =
            DiagnosticFeatureFlagOverrideRegistry(
                nowMillis = { 1_000L },
                readStateController = ReadStateV1DiagnosticOverrideController { 1_000L },
            )
        registry.configureEntitlement(true)
        val scope =
            DiagnosticFeatureFlagScope(
                environmentId = "https://api.example/",
                accountId = "account-a",
                serverId = "server-a",
            )

        assertTrue(MOBILE_SLACK_NAV_FEATURE_FLAG_KEY in registry.supportedKeys)

        val applied =
            registry.setMode(MOBILE_SLACK_NAV_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertIs<DiagnosticFeatureFlagOverrideMutationResult.Applied>(applied)
        assertTrue(
            registry.decide(MOBILE_SLACK_NAV_FEATURE_FLAG_KEY, serverEffective = false, scope = scope).finalEffective
        )

        registry.setMode(MOBILE_SLACK_NAV_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        assertFalse(
            registry.decide(MOBILE_SLACK_NAV_FEATURE_FLAG_KEY, serverEffective = true, scope = scope).finalEffective
        )

        registry.setMode(MOBILE_SLACK_NAV_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        assertTrue(
            registry.decide(MOBILE_SLACK_NAV_FEATURE_FLAG_KEY, serverEffective = true, scope = scope).finalEffective
        )
        assertFalse(
            registry.decide(MOBILE_SLACK_NAV_FEATURE_FLAG_KEY, serverEffective = false, scope = scope).finalEffective
        )
    }

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
    fun untouchedSettingFollowsTheFlagSeededDefault() {
        // No persisted setting: the flag seeds the default (artin's rule).
        assertEquals(
            listOf("home", "tasks", "members", "settings"),
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = false, persistedRawOrder = null)
        )
        assertEquals(
            SlockSlackNavTabs.DefaultOrder,
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = true, persistedRawOrder = null)
        )
    }

    @Test
    fun blankPreferenceIsAbsentAndFollowsTheFlagSeededDefault() {
        // A blank/whitespace-only stored value is not a real setting: it must NOT count as
        // "user touched the setting" — it follows the flag-seeded default like null does.
        assertEquals(
            listOf("home", "tasks", "members", "settings"),
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = false, persistedRawOrder = "")
        )
        assertEquals(
            listOf("home", "tasks", "members", "settings"),
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = false, persistedRawOrder = "   ")
        )
        assertEquals(
            SlockSlackNavTabs.DefaultOrder,
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = true, persistedRawOrder = "")
        )
    }

    @Test
    fun persistedSettingWinsOverTheFlag() {
        // A persisted order means the user touched the setting: it is the final value even
        // when the flag is off (or later turned off).
        assertEquals(
            listOf("more", "home", "dms", "activity"),
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = true, persistedRawOrder = "more,home")
        )
        assertEquals(
            listOf("more", "home", "dms", "activity"),
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = false, persistedRawOrder = "more,home")
        )
        assertEquals(
            SlockSlackNavTabs.DefaultOrder,
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = false, persistedRawOrder = "home,dms,activity,more")
        )
    }

    @Test
    fun slackNavTabOrderPreferenceRoundTripsAndClears() = runBlocking {
        val prefs = KeyValueChatLayoutPreferenceStore(InMemoryPreferenceStore())
        assertNull(prefs.readSlackNavTabOrderSync(), "untouched preference reads absent")

        prefs.saveSlackNavTabOrder("more,dms,activity,home")
        assertEquals("more,dms,activity,home", prefs.readSlackNavTabOrderSync())

        // Persisted value feeds the model directly: setting wins over any flag state.
        val persisted = prefs.readSlackNavTabOrderSync()
        assertEquals(
            listOf("more", "dms", "activity", "home"),
            SlockSlackNavTabs.resolveAppShellTabKeys(slackNavEnabled = false, persistedRawOrder = persisted)
        )

        // Blank/ cleared values read as absent again (flag-seeded default resumes).
        prefs.saveSlackNavTabOrder("")
        assertNull(prefs.readSlackNavTabOrderSync())
        prefs.saveSlackNavTabOrder(null)
        assertNull(prefs.readSlackNavTabOrderSync())
    }

    @Test
    fun slackNavGateFollowsServerUnlessLocallyOverridden() {
        val registry =
            DiagnosticFeatureFlagOverrideRegistry(
                nowMillis = { 1_000L },
                readStateController = ReadStateV1DiagnosticOverrideController { 1_000L },
            )
        registry.configureEntitlement(true)
        val scope =
            DiagnosticFeatureFlagScope(
                environmentId = "https://api.example/",
                accountId = "account-a",
                serverId = "server-a",
            )
        val resolve = { serverEffective: Boolean ->
            resolveSlackNavEnabled(
                registry = registry,
                serverEffective = serverEffective,
                environmentId = scope.environmentId,
                accountId = scope.accountId,
                serverId = scope.serverId
            )
        }

        assertFalse(resolve(false))
        assertTrue(resolve(true))
        registry.setMode(MOBILE_SLACK_NAV_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertTrue(resolve(false), "ForceOn beats a server-off evaluation")
        registry.setMode(MOBILE_SLACK_NAV_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        assertFalse(resolve(true), "ForceOff beats a server-on evaluation")

        // Effective tabs: an untouched setting follows the locally-forced default too.
        assertEquals(
            SlockSlackNavTabs.LegacyOrder,
            SlockSlackNavTabs.resolveAppShellTabKeys(
                slackNavEnabled = resolveSlackNavEnabled(registry, false, scope.environmentId, scope.accountId, scope.serverId),
                persistedRawOrder = null
            ),
            "ForceOff + untouched setting -> legacy"
        )
        registry.setMode(MOBILE_SLACK_NAV_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertEquals(
            SlockSlackNavTabs.DefaultOrder,
            SlockSlackNavTabs.resolveAppShellTabKeys(
                slackNavEnabled = resolveSlackNavEnabled(registry, false, scope.environmentId, scope.accountId, scope.serverId),
                persistedRawOrder = null
            ),
            "ForceOn + untouched setting -> Slack default order"
        )
    }

    @Test
    fun slackNavTabIconsMatchThePreviewFixture() {
        // Icons are pinned to the #94 Slack-aligned preview fixture
        // (compose_slack_nav_preview_visual): Home=house, DMs=message circle, Activity=bell,
        // More=ellipsis; legacy keys keep their existing icons.
        assertEquals(SlockLucideIconName.House, appShellSlackNavTabIcon("home"))
        assertEquals(SlockLucideIconName.MessageCircle, appShellSlackNavTabIcon("dms"))
        assertEquals(SlockLucideIconName.Bell, appShellSlackNavTabIcon("activity"))
        assertEquals(SlockLucideIconName.Ellipsis, appShellSlackNavTabIcon("more"))
        assertEquals(SlockLucideIconName.SquareCheckBig, appShellSlackNavTabIcon("tasks"))
        assertEquals(SlockLucideIconName.Users, appShellSlackNavTabIcon("members"))
        assertEquals(SlockLucideIconName.Settings, appShellSlackNavTabIcon("settings"))
        assertEquals(SlockLucideIconName.Circle, appShellSlackNavTabIcon("unknown"))
    }

    private class InMemoryPreferenceStore : SharedPreferenceStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
