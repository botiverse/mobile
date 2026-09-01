package build.raft.app

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
     */
    fun resolveAppShellTabKeys(slackNavEnabled: Boolean, persistedRawOrder: String?): List<String> =
        when {
            persistedRawOrder != null -> parseTabOrder(persistedRawOrder)
            slackNavEnabled -> DefaultOrder
            else -> LegacyOrder
        }
}
