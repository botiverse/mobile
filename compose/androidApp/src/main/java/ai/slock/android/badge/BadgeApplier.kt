package ai.slock.android.badge

/**
 * Host-side consumer of the shared `bridge.appBadge(count, source, isStale)` contract
 * (see #81). The applier is responsible for rendering the unread count on whatever
 * platform surface the host owns — for Android v1 this is the launcher icon number
 * badge, derived from a headless `NotificationCompat` post on a dedicated badge
 * channel (per #100).
 *
 * **Boundary rule (per #81, 2026-06-15 12:59):** implementations MUST NOT branch on
 * [source] to decide which UI to show. The host platform only displays [count].
 * [source] and [isStale] are diagnostics-only and are surfaced via logging.
 *
 * This interface is split out from `SlockKuiklyBridgeModule` so the bridge parser
 * can be unit-tested without a real `NotificationManager` (no Robolectric in the
 * project — see `BadgeApplierTest` for the parser-side test).
 */
internal interface BadgeApplier {
    /**
     * Apply the latest unread count to the host surface.
     *
     * @param count Unread count to display. `0` means clear the badge.
     * @param source Provenance string from the shared contract (one of
     *   `SERVER_UNREAD_SUMMARY` / `CHANNEL_DM_FALLBACK` / `DISABLED` / `UNKNOWN`).
     *   Diagnostics-only; must not branch the host behavior.
     * @param isStale True when the last refresh attempt failed. The host keeps the
     *   previous [count] visible but may surface a stale indicator in diagnostics.
     *   Diagnostics-only; must not change which [count] is shown.
     */
    fun apply(count: Int, source: String, isStale: Boolean)

    /**
     * Clear the host badge surface entirely (used on sign-out and on `count == 0`
     * when the contract says "no badge"). Idempotent.
     */
    fun clear()
}
