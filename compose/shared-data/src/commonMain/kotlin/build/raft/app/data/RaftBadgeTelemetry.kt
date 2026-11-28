@file:OptIn(build.raft.app.RaftCoreInternalApi::class)

package build.raft.app.data

import build.raft.app.network.raftProcessIsForeground
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Badge accuracy telemetry (`#proj-mobile:#437`, server contract `botiverse/slock #17359`).
 *
 * - Emits `mobile.badge.reconciled` once per foreground (100% sampled, mobile-only) after the
 *   first foreground sync settles, comparing `icon_badge_before` (snapshotted at foreground entry
 *   before the app overwrites the badge) against the computed §1.4 `activity_total`.
 * - Tracks `last_badge_source` (`alert` | `sync` | `retract` | `app`) and timestamp across
 *   process restarts.
 * - Maintains bounded persisted lookup tables for `mobile.push.opened` enrichment:
 *   - retracted `groupKey` watermarks (`read_before_open`)
 *   - push received/sent timestamps by `delivery_id` (`push_age_ms`)
 *
 * Privacy contract: counts, durations, booleans, and closed enums only. Never emits message
 * content, conversation names, device tokens, or `delivery_id` on `mobile.badge.reconciled`.
 */
const val RAFT_BADGE_RECONCILED_EVENT = "mobile.badge.reconciled"

const val RAFT_BADGE_TELEMETRY_PREF_KEY = "raft_badge_accuracy_telemetry_v1"
const val RAFT_BADGE_TELEMETRY_PENDING_MAX = 16
const val RAFT_BADGE_TELEMETRY_PENDING_TTL_MILLIS = 48L * 60L * 60L * 1000L
const val RAFT_BADGE_TELEMETRY_RETRACT_CAP = 256
const val RAFT_BADGE_TELEMETRY_RETRACT_TTL_MILLIS = 72L * 60L * 60L * 1000L
const val RAFT_BADGE_TELEMETRY_RETRACT_LATE_GRACE_MILLIS = 60L * 1000L
const val RAFT_BADGE_TELEMETRY_PUSH_TS_CAP = 200
const val RAFT_BADGE_TELEMETRY_PUSH_TS_TTL_MILLIS = 72L * 60L * 60L * 1000L
const val RAFT_BADGE_DELTA_MIN = -1_000_000
const val RAFT_BADGE_DELTA_MAX = 1_000_000

private const val RAFT_BADGE_FUTURE_CLOCK_SKEW_TOLERANCE_MILLIS = 5L * 60L * 1000L

private val badgeTelemetryJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

/** Closed `last_badge_source` enum matching `slock` `pushDeliveryAttrs.ts`. */
enum class RaftBadgeSource(val wire: String) {
    Alert("alert"),
    Sync("sync"),
    Retract("retract"),
    App("app");

    companion object {
        fun fromWire(raw: String?): RaftBadgeSource? {
            val clean = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == clean }
        }
    }
}

/** Closed `launch` enum matching `slock` `pushDeliveryAttrs.ts`. */
enum class RaftBadgeLaunchKind(val wire: String) {
    Cold("cold"),
    Warm("warm");

    companion object {
        fun fromWire(raw: String?): RaftBadgeLaunchKind? {
            val clean = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == clean }
        }
    }
}

@Serializable
internal data class PersistedRetractedGroup(
    val groupKey: String,
    val throughSeq: Long? = null,
    val retractedAtMs: Long,
)

@Serializable
internal data class PersistedPushTimestamp(
    val deliveryId: String,
    val occurredAtMs: Long,
)

@Serializable
internal data class BufferedBadgeReconciledEvent(
    val accountId: String? = null,
    val serverId: String = "",
    val osVersion: String,
    val appState: String,
    val iconBadgeBefore: Int,
    val activityTotal: Int,
    val delta: Int,
    val lastBadgeSource: String? = null,
    val lastBadgeAgeMs: Long? = null,
    val launch: String,
    val syncOk: Boolean,
    val occurredAtMs: Long,
)

@Serializable
internal data class PersistedBadgeTelemetryState(
    val version: Int = 1,
    val accountId: String? = null,
    val lastBadgeCount: Int? = null,
    val lastBadgeSource: String? = null,
    val lastBadgeSetAtMs: Long? = null,
    val retractedGroups: List<PersistedRetractedGroup> = emptyList(),
    val pushTimestamps: List<PersistedPushTimestamp> = emptyList(),
    val pendingReconciled: List<BufferedBadgeReconciledEvent> = emptyList(),
)

internal fun BufferedBadgeReconciledEvent.toTraceAttrs(reportedAtMs: Long): JsonObject =
    JsonObject(
        buildMap {
            put("os_version", JsonPrimitive(osVersion))
            put("app_state", JsonPrimitive(appState))
            put("icon_badge_before", JsonPrimitive(iconBadgeBefore.coerceAtLeast(0)))
            put("activity_total", JsonPrimitive(activityTotal.coerceAtLeast(0)))
            put("delta", JsonPrimitive(delta.coerceIn(RAFT_BADGE_DELTA_MIN, RAFT_BADGE_DELTA_MAX)))
            lastBadgeSource
                ?.let(RaftBadgeSource::fromWire)
                ?.let { put("last_badge_source", JsonPrimitive(it.wire)) }
            lastBadgeAgeMs
                ?.takeIf { it >= 0L }
                ?.let { put("last_badge_age_ms", JsonPrimitive(it)) }
            put("launch", JsonPrimitive(launch))
            put("sync_ok", JsonPrimitive(syncOk))
            put("occurred_at_ms", JsonPrimitive(occurredAtMs.coerceAtLeast(0L)))
            put("reported_at_ms", JsonPrimitive(reportedAtMs.coerceAtLeast(0L)))
        }
    )

/**
 * Thread-safe, bounded persistent state and foreground reconciliation recorder for badge accuracy
 * telemetry (`mobile.badge.reconciled`) and push-open lookup state (`read_before_open`,
 * `push_age_ms`, `badge_at_open`).
 */
class BadgeAccuracyRecorder(
    private val preferenceStore: SharedPreferenceStore,
    private val nowEpochMillis: () -> Long,
    private val privacyMode: StateFlow<RaftPrivacyMode>,
    private val isForeground: () -> Boolean,
    private val isTransportReady: () -> Boolean,
    private val deviceContextProvider: () -> RaftPushDeliveryDeviceContext,
    private val liveIconBadgeProvider: () -> Int? = { null },
    private val emit: (serverId: String, attrs: JsonObject, occurredAtMs: Long) -> Unit,
    private val isEnabled: () -> Boolean = { true },
    scope: CoroutineScope? = null,
    private val prefKey: String = RAFT_BADGE_TELEMETRY_PREF_KEY,
) {
    private data class ForegroundBadgeSnapshot(
        val epoch: Long,
        val launch: RaftBadgeLaunchKind,
        val enteredAtMs: Long,
        var iconBadgeBefore: Int,
        var lastBadgeSource: RaftBadgeSource?,
        var lastBadgeSetAtMs: Long?,
        val persistedSetAtMsBeforeForeground: Long?,
        val liveBadgeCaptured: Boolean,
        var reconciled: Boolean = false,
    )

    private val lock = SynchronizedObject()
    private var loaded = false
    private var persisted = PersistedBadgeTelemetryState()

    private var currentlyForeground = false
    private var foregroundEpoch = 0L
    private var activeForegroundSnapshot: ForegroundBadgeSnapshot? = null
    private var pendingLiveIconBadgeBeforeForeground: Int? = null
    private var lastObservedLiveIconBadge: Int? = null

    @Volatile private var activeAccountId: String? = null
    @Volatile private var activeServerId: String? = null
    @Volatile private var knownAccountHint: String? = null
    @Volatile private var knownServerHint: String? = null

    init {
        scope?.launch {
            privacyMode.collect { mode ->
                onPrivacyModeChanged(mode)
            }
        }
    }

    /**
     * Records a badge write (`alert`, `sync`, `retract`, or `app`).
     *
     * Does NOT overwrite an active foreground's captured `icon_badge_before` / `last_badge_source`,
     * so an in-app `bridge.appBadge` call during startup never clobbers the pre-reconcile snapshot.
     */
    fun recordBadgeSet(
        count: Int,
        source: RaftBadgeSource,
        setAtMs: Long = nowEpochMillis(),
    ) {
        if (!isEnabled()) return
        val clamped = count.coerceAtLeast(0)
        synchronized(lock) {
            ensureLoadedLocked()
            lastObservedLiveIconBadge = clamped
            if (privacyMode.value == RaftPrivacyMode.On) return
            persisted =
                persisted.copy(
                    lastBadgeCount = clamped,
                    lastBadgeSource = source.wire,
                    lastBadgeSetAtMs = setAtMs.coerceAtLeast(0L),
                )
            persistLocked()
        }
    }

    /**
     * Called synchronously on iOS main thread right before `updateRaftProcessNetworkVisibility(true)`
     * so `icon_badge_before` captures the exact OS icon badge before foreground sync or `appBadge`
     * mutates it.
     */
    fun noteLiveIconBadgeBeforeForeground(count: Int) {
        if (!isEnabled()) return
        val clamped = count.coerceAtLeast(0)
        synchronized(lock) {
            pendingLiveIconBadgeBeforeForeground = clamped
            lastObservedLiveIconBadge = clamped
        }
    }

    /**
     * Enriches an un-reconciled foreground snapshot (and persisted state if newer) when iOS
     * discovers a delivered/tapped APNs alert that updated the icon badge while the app was asleep.
     */
    fun enrichForegroundSnapshotFromDeliveredAlert(
        badge: Int?,
        alertAtMs: Long,
    ) {
        if (!isEnabled() || alertAtMs <= 0L) return
        synchronized(lock) {
            ensureLoadedLocked()
            val snapshot = activeForegroundSnapshot
            if (snapshot != null && !snapshot.reconciled) {
                val previousPersistedSetAt = snapshot.persistedSetAtMsBeforeForeground ?: Long.MIN_VALUE
                if (alertAtMs > previousPersistedSetAt) {
                    snapshot.lastBadgeSource = RaftBadgeSource.Alert
                    val currentSnapshotSetAt = snapshot.lastBadgeSetAtMs ?: Long.MIN_VALUE
                    if (alertAtMs > currentSnapshotSetAt) {
                        snapshot.lastBadgeSetAtMs = alertAtMs
                    }
                    if (!snapshot.liveBadgeCaptured && badge != null && badge >= 0) {
                        snapshot.iconBadgeBefore = badge
                    }
                }
            }
            if (privacyMode.value != RaftPrivacyMode.On) {
                val persistedSetAt = persisted.lastBadgeSetAtMs ?: Long.MIN_VALUE
                if (alertAtMs > persistedSetAt) {
                    persisted =
                        persisted.copy(
                            lastBadgeCount = badge?.coerceAtLeast(0) ?: persisted.lastBadgeCount,
                            lastBadgeSource = RaftBadgeSource.Alert.wire,
                            lastBadgeSetAtMs = alertAtMs,
                        )
                    persistLocked()
                }
            }
        }
    }

    /**
     * Best-known current app-icon badge at push-tap time (`badge_at_open`).
     * Prefers live platform icon badge (iOS) -> last recorded badge set by push or app -> 0.
     */
    fun currentBadgeCountForOpen(): Int =
        synchronized(lock) {
            ensureLoadedLocked()
            runCatching { liveIconBadgeProvider() }.getOrNull()?.coerceAtLeast(0)
                ?: persisted.lastBadgeCount?.coerceAtLeast(0)
                ?: lastObservedLiveIconBadge?.coerceAtLeast(0)
                ?: 0
        }

    /** Records a retraction push (`groupKey`, optional `throughSeq`) for `read_before_open`. */
    fun recordRetract(
        groupKey: String?,
        throughSeq: Long?,
        retractedAtMs: Long = nowEpochMillis(),
    ) {
        val cleanGroupKey = groupKey?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!isEnabled()) return
        synchronized(lock) {
            ensureLoadedLocked()
            val nowMs = retractedAtMs.coerceAtLeast(0L)
            val kept =
                persisted.retractedGroups
                    .filter { isRetractLive(it, nowMs) }
                    .toMutableList()
            val existingIndex = kept.indexOfFirst { it.groupKey == cleanGroupKey }
            val mergedThrough =
                if (existingIndex >= 0) {
                    val existing = kept.removeAt(existingIndex)
                    if (throughSeq != null && existing.throughSeq != null) {
                        maxOf(existing.throughSeq, throughSeq)
                    } else {
                        throughSeq
                    }
                } else {
                    throughSeq
                }
            kept += PersistedRetractedGroup(
                groupKey = cleanGroupKey,
                throughSeq = mergedThrough,
                retractedAtMs = nowMs,
            )
            if (kept.size > RAFT_BADGE_TELEMETRY_RETRACT_CAP) {
                kept.sortBy { it.retractedAtMs }
                while (kept.size > RAFT_BADGE_TELEMETRY_RETRACT_CAP) {
                    kept.removeAt(0)
                }
            }
            persisted = persisted.copy(retractedGroups = kept)
            persistLocked()
        }
    }

    /**
     * Returns `true` when a retraction covering `(groupKey, seq)` arrived before this tap.
     *
     * - When both watermark `throughSeq` and push `seq` are known: true iff `seq <= throughSeq`.
     * - Otherwise (whole-group retract or seq unavailable): when `pushOccurredAtMs` is known,
     *   true iff `pushOccurredAtMs <= retractedAtMs + 60s` so newer pushes after an older retract
     *   are not misclassified; when `pushOccurredAtMs` is unavailable, true for any live retract.
     */
    fun isReadBeforeOpen(
        groupKey: String?,
        seq: Long?,
        pushOccurredAtMs: Long? = null,
        nowMs: Long = nowEpochMillis(),
    ): Boolean {
        val cleanGroupKey = groupKey?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return synchronized(lock) {
            ensureLoadedLocked()
            val entry =
                persisted.retractedGroups.firstOrNull { it.groupKey == cleanGroupKey && isRetractLive(it, nowMs) }
                    ?: return@synchronized false
            if (entry.throughSeq != null && seq != null) {
                return@synchronized seq <= entry.throughSeq
            }
            if (pushOccurredAtMs != null && pushOccurredAtMs > 0L) {
                return@synchronized pushOccurredAtMs <= entry.retractedAtMs + RAFT_BADGE_TELEMETRY_RETRACT_LATE_GRACE_MILLIS
            }
            true
        }
    }

    /** Records push receipt/sent timestamp by normalized `deliveryId` for `push_age_ms`. */
    fun recordPushTimestamp(
        deliveryId: String?,
        occurredAtMs: Long,
    ) {
        if (!isEnabled() || occurredAtMs <= 0L) return
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return
        synchronized(lock) {
            ensureLoadedLocked()
            if (privacyMode.value == RaftPrivacyMode.On) return
            val nowMs = nowEpochMillis()
            val kept =
                persisted.pushTimestamps
                    .filter { it.deliveryId != normalizedId && nowMs - it.occurredAtMs <= RAFT_BADGE_TELEMETRY_PUSH_TS_TTL_MILLIS }
                    .toMutableList()
            kept += PersistedPushTimestamp(deliveryId = normalizedId, occurredAtMs = occurredAtMs)
            while (kept.size > RAFT_BADGE_TELEMETRY_PUSH_TS_CAP) {
                kept.removeAt(0)
            }
            persisted = persisted.copy(pushTimestamps = kept)
            persistLocked()
        }
    }

    /** Looks up a previously recorded push timestamp for [deliveryId], or `null` if unavailable/expired. */
    fun lookupPushTimestamp(
        deliveryId: String?,
        nowMs: Long = nowEpochMillis(),
    ): Long? {
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return null
        return synchronized(lock) {
            ensureLoadedLocked()
            persisted.pushTimestamps
                .firstOrNull {
                    it.deliveryId == normalizedId &&
                        it.occurredAtMs > 0L &&
                        nowMs - it.occurredAtMs <= RAFT_BADGE_TELEMETRY_PUSH_TS_TTL_MILLIS
                }
                ?.occurredAtMs
        }
    }

    fun onProcessVisibilityChanged(foreground: Boolean) {
        if (!isEnabled()) return
        synchronized(lock) {
            if (foreground == currentlyForeground) {
                // Keep any newly supplied live badge updated only if not yet reconciled? No: duplicate
                // foreground edge is a no-op so we never re-open a new foreground epoch.
                if (foreground) {
                    pendingLiveIconBadgeBeforeForeground = null
                }
                if (!foreground) return
            } else {
                currentlyForeground = foreground
                if (foreground) {
                    ensureLoadedLocked()
                    foregroundEpoch += 1L
                    val launch = if (foregroundEpoch == 1L) RaftBadgeLaunchKind.Cold else RaftBadgeLaunchKind.Warm
                    val liveBadge =
                        pendingLiveIconBadgeBeforeForeground.also { pendingLiveIconBadgeBeforeForeground = null }
                            ?: runCatching { liveIconBadgeProvider() }.getOrNull()?.coerceAtLeast(0)
                    activeForegroundSnapshot = buildForegroundSnapshotLocked(
                        epoch = foregroundEpoch,
                        launch = launch,
                        enteredAtMs = nowEpochMillis(),
                        liveBadge = liveBadge,
                    )
                }
            }
        }
        if (foreground) {
            drainIfReady()
        }
    }

    /** Returns the current foreground's launch kind (`Cold` / `Warm`), or `null` before first foreground. */
    fun currentForegroundLaunchKind(): RaftBadgeLaunchKind? =
        synchronized(lock) { activeForegroundSnapshot?.launch }

    /** Returns true if the current foreground epoch has already emitted `mobile.badge.reconciled`. */
    fun isCurrentForegroundReconciled(): Boolean =
        synchronized(lock) { activeForegroundSnapshot?.reconciled == true }

    /**
     * Emits `mobile.badge.reconciled` once for the current foreground epoch after foreground sync
     * completes. Returns `true` if a record was enqueued for this foreground, `false` if already
     * reconciled for this foreground, disabled on this platform, or privacy mode is `On`.
     */
    fun onForegroundSyncCompleted(
        serverId: String?,
        activityTotal: Int,
        syncOk: Boolean,
        occurredAtMs: Long = nowEpochMillis(),
    ): Boolean {
        if (!isEnabled()) return false
        if (privacyMode.value == RaftPrivacyMode.On) return false
        val device = safeDeviceContext()
        val recorded =
            synchronized(lock) {
                ensureLoadedLocked()
                val snapshot =
                    activeForegroundSnapshot
                        ?: run {
                            foregroundEpoch += 1L
                            val launch =
                                if (foregroundEpoch == 1L) RaftBadgeLaunchKind.Cold else RaftBadgeLaunchKind.Warm
                            val liveBadge =
                                pendingLiveIconBadgeBeforeForeground.also { pendingLiveIconBadgeBeforeForeground = null }
                                    ?: runCatching { liveIconBadgeProvider() }.getOrNull()?.coerceAtLeast(0)
                            buildForegroundSnapshotLocked(
                                epoch = foregroundEpoch,
                                launch = launch,
                                enteredAtMs = occurredAtMs,
                                liveBadge = liveBadge,
                            ).also { activeForegroundSnapshot = it }
                        }
                if (snapshot.reconciled) return@synchronized false
                snapshot.reconciled = true
                val before = snapshot.iconBadgeBefore.coerceAtLeast(0)
                val total = activityTotal.coerceAtLeast(0)
                val delta = (before - total).coerceIn(RAFT_BADGE_DELTA_MIN, RAFT_BADGE_DELTA_MAX)
                val ageMs = snapshot.lastBadgeSetAtMs?.let { (occurredAtMs - it).coerceAtLeast(0L) }
                val appState =
                    if (snapshot.launch == RaftBadgeLaunchKind.Cold) {
                        RaftPushDeliveryAppState.ColdStart.wire
                    } else {
                        RaftPushDeliveryAppState.Foreground.wire
                    }
                val event =
                    BufferedBadgeReconciledEvent(
                        accountId = resolveAccountId(),
                        serverId = resolveServerId(serverId),
                        osVersion = normalizePushDeliveryOsVersion(device.osVersion),
                        appState = appState,
                        iconBadgeBefore = before,
                        activityTotal = total,
                        delta = delta,
                        lastBadgeSource = snapshot.lastBadgeSource?.wire,
                        lastBadgeAgeMs = ageMs,
                        launch = snapshot.launch.wire,
                        syncOk = syncOk,
                        occurredAtMs = occurredAtMs.coerceAtLeast(0L),
                    )
                val kept =
                    persisted.pendingReconciled
                        .filter { occurredAtMs - it.occurredAtMs <= RAFT_BADGE_TELEMETRY_PENDING_TTL_MILLIS }
                        .toMutableList()
                kept += event
                while (kept.size > RAFT_BADGE_TELEMETRY_PENDING_MAX) {
                    kept.removeAt(0)
                }
                persisted = persisted.copy(pendingReconciled = kept)
                persistLocked()
                true
            }
        if (recorded) {
            drainIfReady()
        }
        return recorded
    }

    fun onSignedIn(accountId: String?, serverId: String? = null) {
        val cleanAccount = accountId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanServer != null) {
            activeServerId = cleanServer
            knownServerHint = cleanServer
        }
        if (cleanAccount == null) return
        synchronized(lock) {
            ensureLoadedLocked()
            val previous = activeAccountId ?: persisted.accountId
            activeAccountId = cleanAccount
            knownAccountHint = cleanAccount
            if (previous != null && previous != cleanAccount) {
                persisted = PersistedBadgeTelemetryState(accountId = cleanAccount)
                persistLocked()
            } else if (persisted.accountId == null) {
                persisted = persisted.copy(accountId = cleanAccount)
                persistLocked()
            }
        }
        drainIfReady()
    }

    fun setKnownAccountHint(accountId: String?, serverId: String? = null) {
        val cleanAccount = accountId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanServer != null) knownServerHint = cleanServer
        if (cleanAccount != null) {
            knownAccountHint = cleanAccount
            synchronized(lock) {
                ensureLoadedLocked()
                if (persisted.accountId != null && persisted.accountId != cleanAccount) {
                    persisted = PersistedBadgeTelemetryState(accountId = cleanAccount)
                    persistLocked()
                } else if (persisted.accountId == null) {
                    persisted = persisted.copy(accountId = cleanAccount)
                    persistLocked()
                }
            }
        }
    }

    fun onSignedOut() {
        synchronized(lock) {
            activeAccountId = null
            activeServerId = null
            knownAccountHint = null
            knownServerHint = null
            loaded = true
            persisted = PersistedBadgeTelemetryState()
            runCatching { preferenceStore.putString(prefKey, null) }
        }
    }

    fun onTransportBound() {
        drainIfReady()
    }

    fun onPrivacyModeChanged(mode: RaftPrivacyMode) {
        when (mode) {
            RaftPrivacyMode.On -> {
                synchronized(lock) {
                    ensureLoadedLocked()
                    if (persisted.pendingReconciled.isNotEmpty() || persisted.pushTimestamps.isNotEmpty()) {
                        persisted =
                            persisted.copy(
                                pendingReconciled = emptyList(),
                                pushTimestamps = emptyList(),
                            )
                        persistLocked()
                    }
                }
            }
            RaftPrivacyMode.Off -> drainIfReady()
            RaftPrivacyMode.Unknown -> Unit
        }
    }

    fun drainIfReady(): Int {
        if (!isEnabled()) return 0
        if (privacyMode.value != RaftPrivacyMode.Off) return 0
        if (!isForeground()) return 0
        if (!isTransportReady()) return 0
        val account = activeAccountId ?: return 0
        val fallbackServer = activeServerId ?: knownServerHint
        val nowMs = nowEpochMillis()
        val deliverable =
            synchronized(lock) {
                ensureLoadedLocked()
                if (persisted.pendingReconciled.isEmpty()) return 0
                val ready = ArrayList<BufferedBadgeReconciledEvent>(persisted.pendingReconciled.size)
                val remaining = ArrayList<BufferedBadgeReconciledEvent>()
                for (event in persisted.pendingReconciled) {
                    if (nowMs - event.occurredAtMs > RAFT_BADGE_TELEMETRY_PENDING_TTL_MILLIS) {
                        continue
                    }
                    val eventAccount = event.accountId?.trim()?.takeIf { it.isNotEmpty() }
                    if (eventAccount != null && eventAccount != account) {
                        continue
                    }
                    val resolvedServer = event.serverId.trim().takeIf { it.isNotEmpty() } ?: fallbackServer
                    if (resolvedServer == null) {
                        remaining += event
                        continue
                    }
                    ready += if (resolvedServer == event.serverId) event else event.copy(serverId = resolvedServer)
                }
                persisted = persisted.copy(accountId = account, pendingReconciled = remaining)
                persistLocked()
                ready
            }
        if (deliverable.isEmpty()) return 0
        val reportedAtMs = nowEpochMillis()
        for (event in deliverable) {
            runCatching {
                emit(event.serverId, event.toTraceAttrs(reportedAtMs = reportedAtMs), event.occurredAtMs)
            }
        }
        return deliverable.size
    }

    @build.raft.app.RaftCoreInternalApi
    fun pendingReconciledCountForTest(): Int =
        synchronized(lock) {
            ensureLoadedLocked()
            persisted.pendingReconciled.size
        }

    private fun buildForegroundSnapshotLocked(
        epoch: Long,
        launch: RaftBadgeLaunchKind,
        enteredAtMs: Long,
        liveBadge: Int?,
    ): ForegroundBadgeSnapshot {
        val persistedCount = persisted.lastBadgeCount?.coerceAtLeast(0)
        val persistedSource = RaftBadgeSource.fromWire(persisted.lastBadgeSource)
        val persistedSetAt = persisted.lastBadgeSetAtMs
        return if (liveBadge != null) {
            val safeLive = liveBadge.coerceAtLeast(0)
            val changedExternally =
                (persistedCount != null && safeLive != persistedCount) ||
                    (persistedCount == null && safeLive > 0)
            ForegroundBadgeSnapshot(
                epoch = epoch,
                launch = launch,
                enteredAtMs = enteredAtMs,
                iconBadgeBefore = safeLive,
                lastBadgeSource = if (changedExternally) RaftBadgeSource.Alert else persistedSource,
                lastBadgeSetAtMs = if (changedExternally) null else persistedSetAt,
                persistedSetAtMsBeforeForeground = persistedSetAt,
                liveBadgeCaptured = true,
            )
        } else {
            ForegroundBadgeSnapshot(
                epoch = epoch,
                launch = launch,
                enteredAtMs = enteredAtMs,
                iconBadgeBefore = persistedCount ?: 0,
                lastBadgeSource = persistedSource,
                lastBadgeSetAtMs = persistedSetAt,
                persistedSetAtMsBeforeForeground = persistedSetAt,
                liveBadgeCaptured = false,
            )
        }
    }

    private fun isRetractLive(entry: PersistedRetractedGroup, nowMs: Long): Boolean {
        val age = nowMs - entry.retractedAtMs
        return age <= RAFT_BADGE_TELEMETRY_RETRACT_TTL_MILLIS &&
            entry.retractedAtMs <= nowMs + RAFT_BADGE_FUTURE_CLOCK_SKEW_TOLERANCE_MILLIS
    }

    private fun resolveAccountId(): String? = activeAccountId ?: knownAccountHint

    private fun resolveServerId(explicit: String?): String =
        explicit?.trim()?.takeIf { it.isNotEmpty() } ?: activeServerId ?: knownServerHint.orEmpty()

    private fun safeDeviceContext(): RaftPushDeliveryDeviceContext =
        runCatching { deviceContextProvider() }.getOrElse {
            RaftPushDeliveryDeviceContext(manufacturer = "other", osVersion = "unknown")
        }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val raw = runCatching { preferenceStore.getString(prefKey) }.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) {
            persisted = PersistedBadgeTelemetryState()
            return
        }
        persisted =
            runCatching {
                badgeTelemetryJson.decodeFromString(PersistedBadgeTelemetryState.serializer(), raw)
            }.getOrElse {
                PersistedBadgeTelemetryState()
            }
    }

    private fun persistLocked() {
        runCatching {
            if (
                persisted.accountId == null &&
                persisted.lastBadgeCount == null &&
                persisted.lastBadgeSource == null &&
                persisted.lastBadgeSetAtMs == null &&
                persisted.retractedGroups.isEmpty() &&
                persisted.pushTimestamps.isEmpty() &&
                persisted.pendingReconciled.isEmpty()
            ) {
                preferenceStore.putString(prefKey, null)
            } else {
                preferenceStore.putString(
                    prefKey,
                    badgeTelemetryJson.encodeToString(PersistedBadgeTelemetryState.serializer(), persisted),
                )
            }
        }
    }
}

/** Process-wide badge accuracy telemetry host (`#proj-mobile:#437`). */
object RaftBadgeTelemetry {
    @Volatile
    private var deviceContextOverride: (() -> RaftPushDeliveryDeviceContext)? = null

    @Volatile
    private var liveIconBadgeOverride: (() -> Int?)? = null

    private val recorder: BadgeAccuracyRecorder by lazy {
        BadgeAccuracyRecorder(
            preferenceStore = raftSharedPreferenceStore(),
            nowEpochMillis = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() },
            privacyMode = RaftTelemetryPrivacy.state,
            isForeground = ::raftProcessIsForeground,
            isTransportReady = { RaftClientTraceEvents.sender.canAcceptImmediateHandoff() },
            deviceContextProvider = {
                deviceContextOverride?.invoke() ?: defaultRaftPushDeliveryDeviceContext()
            },
            liveIconBadgeProvider = {
                liveIconBadgeOverride?.invoke()
            },
            emit = { serverId, attrs, occurredAtMs ->
                RaftClientTraceEvents.emitBadgeReconciled(serverId, attrs, occurredAtMs)
            },
            isEnabled = ::isMobilePushDeliveryPlatform,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    fun installDeviceContextProvider(provider: () -> RaftPushDeliveryDeviceContext) {
        deviceContextOverride = provider
    }

    fun installLiveIconBadgeProvider(provider: () -> Int?) {
        liveIconBadgeOverride = provider
    }

    fun recordBadgeSet(
        count: Int,
        source: RaftBadgeSource,
        setAtMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
    ) {
        runCatching { recorder.recordBadgeSet(count = count, source = source, setAtMs = setAtMs) }
    }

    fun noteLiveIconBadgeBeforeForeground(count: Int) {
        runCatching { recorder.noteLiveIconBadgeBeforeForeground(count = count) }
    }

    fun enrichForegroundSnapshotFromDeliveredAlert(
        badge: Int?,
        alertAtMs: Long,
    ) {
        runCatching {
            recorder.enrichForegroundSnapshotFromDeliveredAlert(badge = badge, alertAtMs = alertAtMs)
        }
    }

    fun currentBadgeCountForOpen(): Int =
        runCatching { recorder.currentBadgeCountForOpen() }.getOrDefault(0)

    fun recordRetract(
        groupKey: String?,
        throughSeq: Long?,
        retractedAtMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
    ) {
        runCatching {
            recorder.recordRetract(groupKey = groupKey, throughSeq = throughSeq, retractedAtMs = retractedAtMs)
        }
    }

    fun isReadBeforeOpen(
        groupKey: String?,
        seq: Long?,
        pushOccurredAtMs: Long? = null,
        nowMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
    ): Boolean =
        runCatching {
            recorder.isReadBeforeOpen(
                groupKey = groupKey,
                seq = seq,
                pushOccurredAtMs = pushOccurredAtMs,
                nowMs = nowMs,
            )
        }.getOrDefault(false)

    fun recordPushTimestamp(
        deliveryId: String?,
        occurredAtMs: Long,
    ) {
        runCatching { recorder.recordPushTimestamp(deliveryId = deliveryId, occurredAtMs = occurredAtMs) }
    }

    fun lookupPushTimestamp(
        deliveryId: String?,
        nowMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
    ): Long? =
        runCatching { recorder.lookupPushTimestamp(deliveryId = deliveryId, nowMs = nowMs) }.getOrNull()

    fun onProcessVisibility(foreground: Boolean) {
        runCatching { recorder.onProcessVisibilityChanged(foreground) }
    }

    fun currentForegroundLaunchKind(): RaftBadgeLaunchKind? =
        runCatching { recorder.currentForegroundLaunchKind() }.getOrNull()

    fun isCurrentForegroundReconciled(): Boolean =
        runCatching { recorder.isCurrentForegroundReconciled() }.getOrDefault(false)

    fun onForegroundSyncCompleted(
        serverId: String?,
        activityTotal: Int,
        syncOk: Boolean,
        occurredAtMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
    ): Boolean =
        runCatching {
            recorder.onForegroundSyncCompleted(
                serverId = serverId,
                activityTotal = activityTotal,
                syncOk = syncOk,
                occurredAtMs = occurredAtMs,
            )
        }.getOrDefault(false)

    fun onSignedIn(accountId: String?, serverId: String? = null) {
        runCatching { recorder.onSignedIn(accountId = accountId, serverId = serverId) }
    }

    fun setKnownAccountHint(accountId: String?, serverId: String? = null) {
        runCatching { recorder.setKnownAccountHint(accountId = accountId, serverId = serverId) }
    }

    fun onSignedOut() {
        runCatching { recorder.onSignedOut() }
    }

    fun onTransportBound() {
        runCatching { recorder.onTransportBound() }
    }

    fun drainIfReady(): Int =
        runCatching { recorder.drainIfReady() }.getOrDefault(0)
}
