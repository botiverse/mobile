package build.raft.app

import build.raft.app.data.MOBILE_SLACK_NAV_FEATURE_FLAG_KEY
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagScope

/**
 * #94 Slack-aligned bottom navigation tab model, gated by
 * [build.raft.app.data.MOBILE_SLACK_NAV_FEATURE_FLAG_KEY].
 *
 * The Slack-aligned nav uses a fixed SET of four tabs — home (channels only), dms, activity,
 * more — whose ORDER is the only user-configurable dimension: entries cannot be added or
 * removed (artin-locked spec). This object is the single source of truth for the ordered key
 * list: a persisted raw order string is sanitized back onto the fixed set, and the gate-aware
 * resolver keeps the legacy tab keys whenever the flag is off.
 *
 * Currently model-only (unwired); the live app-shell tab sites in SettingsHomePage.kt adopt it
 * in the wiring increment, together with the persisted preference store.
 */
internal object SlockSlackNavTabs {
    const val Home: String = "home"
    const val Dms: String = "dms"
    const val Activity: String = "activity"
    const val More: String = "more"

    /** Fixed Slack-aligned tab set in factory order. */
    val DefaultOrder: List<String> = listOf(Home, Dms, Activity, More)

    /** Legacy app-shell tab keys (mirrors SettingsHomePage.kt), used while the gate is off. */
    val LegacyOrder: List<String> = listOf("home", "tasks", "members", "settings")

    /**
     * Clamps a persisted order onto the fixed set: unknown keys are dropped, duplicates
     * removed, and missing keys appended in their default relative order. The result always
     * contains exactly the four tabs, so entries can be reordered but never removed.
     */
    fun sanitizeTabOrder(persisted: List<String>): List<String> {
        val known = persisted.filter { it in DefaultOrder }.distinct()
        return known + DefaultOrder.filter { it !in known }
    }

    /** Canonical storage form of a tab order (fixed set, sanitized before writing). */
    fun serializeTabOrder(order: List<String>): String =
        sanitizeTabOrder(order).joinToString(",")

    /** Parses a stored order string; null/blank/garbage all fall back to [DefaultOrder]. */
    fun parseTabOrder(raw: String?): List<String> =
        sanitizeTabOrder(raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList())

    /**
     * Gate-aware resolution of the ordered app-shell tab keys, per artin's locked rule
     * (2026-09-01, #proj-mobile:5a279be4): the flag only seeds the DEFAULT; the final value
     * always comes from the setting. A persisted order means the user has touched the setting —
     * it wins regardless of the flag (including the flag later being turned off). With no
     * persisted setting, the flag picks the factory default: on -> Slack order, off -> legacy.
     *
     * A blank/whitespace-only stored value is NOT a setting (nothing was meaningfully chosen);
     * it is treated as absent and follows the flag-seeded default.
     */
    fun resolveAppShellTabKeys(slackNavEnabled: Boolean, persistedRawOrder: String?): List<String> {
        val persisted = persistedRawOrder?.takeIf { it.isNotBlank() }
        return when {
            persisted != null -> parseTabOrder(persisted)
            slackNavEnabled -> DefaultOrder
            else -> LegacyOrder
        }
    }
}

/**
 * Per-key bottom-tab icon for both legacy and Slack-aligned tab sets. Slack-aligned icons
 * mirror the #94 preview fixture: Home=house, DMs=message circle, Activity=bell, More=ellipsis.
 */
internal fun appShellSlackNavTabIcon(key: String): SlockLucideIconName =
    when (key) {
        SlockSlackNavTabs.Home -> SlockLucideIconName.House
        SlockSlackNavTabs.Dms -> SlockLucideIconName.MessageCircle
        SlockSlackNavTabs.Activity -> SlockLucideIconName.Bell
        SlockSlackNavTabs.More -> SlockLucideIconName.Ellipsis
        "tasks" -> SlockLucideIconName.SquareCheckBig
        "members" -> SlockLucideIconName.Users
        "settings" -> SlockLucideIconName.Settings
        else -> SlockLucideIconName.Circle
    }

/**
 * Effective on-device value of the Slack-nav gate: the server evaluation plus any diagnostics
 * "Local Feature Overrides" latch (mirrors resolveActivityStableOrderEnabled).
 */
internal fun resolveSlackNavEnabled(
    registry: DiagnosticFeatureFlagOverrideRegistry,
    serverEffective: Boolean,
    environmentId: String,
    accountId: String,
    serverId: String
): Boolean =
    registry.decide(
        key = MOBILE_SLACK_NAV_FEATURE_FLAG_KEY,
        serverEffective = serverEffective,
        scope =
            DiagnosticFeatureFlagScope(
                environmentId = environmentId.trim().trimEnd('/'),
                accountId = accountId,
                serverId = serverId
            )
    ).finalEffective
