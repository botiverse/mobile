@file:OptIn(build.raft.app.RaftCoreInternalApi::class)

package build.raft.app.data

import build.raft.app.model.ConversationKind
import build.raft.app.network.raftProcessIsForeground
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * End-to-end mobile push delivery observability (`#proj-mobile:#405`).
 *
 * Server decides sampling via `trace_sample.mobile.push_delivery` and attaches an opaque random
 * RFC 4122 UUID `deliveryId` only when sampled. The client never re-samples: any missing, blank,
 * or non-UUID `deliveryId` is a strict no-op.
 *
 * Events flow into the existing client-trace pipeline (`POST /api/web-traces` -> `otel.logs`,
 * `service = 'slock-web'`) through [RaftClientTraceEvents]. Records captured while backgrounded,
 * cold-starting, or before privacy mode resolves are persisted in a bounded local buffer (max 100
 * records, 48h TTL) and drained strictly on foreground once signed in, transport-bound, and
 * `RAFT_PRIVACY_MODE == Off` — zero extra background network wakeups.
 *
 * Privacy contract: never stores or emits message content, channel id/name, sender id/name, or
 * device token.
 */
const val RAFT_PUSH_DELIVERY_EVENT_RECEIVED = "mobile.push.received"
const val RAFT_PUSH_DELIVERY_EVENT_PRESENTED = "mobile.push.presented"
const val RAFT_PUSH_DELIVERY_EVENT_OPENED = "mobile.push.opened"

const val RAFT_PUSH_DELIVERY_BUFFER_MAX_EVENTS = 100
const val RAFT_PUSH_DELIVERY_BUFFER_TTL_MILLIS = 48L * 60L * 60L * 1000L
const val RAFT_PUSH_DELIVERY_REPORTED_PRESENTED_CAP = 200
const val RAFT_PUSH_DELIVERY_BUFFER_PREF_KEY = "raft_push_delivery_trace_buffer_v1"
const val RAFT_PUSH_OPENED_LANDING_TIMEOUT_MILLIS = 8_000L

private const val RAFT_PUSH_DELIVERY_OPENED_DEDUP_CAP = 64

private val PushDeliveryUuidPattern =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private val pushDeliveryTraceJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

/** Validates RFC 4122 36-char UUID (case-insensitive) and returns lowercased string, or null. */
fun normalizePushDeliveryId(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.length != 36 || !PushDeliveryUuidPattern.matches(trimmed)) return null
    return trimmed.lowercase()
}

/** Closed transport enum for `mobile.push.*` events. */
enum class RaftPushDeliveryTransport(val wire: String) {
    Fcm("fcm"),
    Apns("apns"),
    MobileSocket("mobile_socket");

    companion object {
        fun fromWire(raw: String?): RaftPushDeliveryTransport? {
            val clean = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == clean }
        }
    }
}

/** Closed app-state enum for `mobile.push.*` events. */
enum class RaftPushDeliveryAppState(val wire: String) {
    Foreground("foreground"),
    Background("background"),
    ColdStart("cold_start");

    companion object {
        fun fromWire(raw: String?): RaftPushDeliveryAppState? {
            val clean = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == clean }
        }
    }
}

/** Closed `push_category` enum for `mobile.push.received`. */
enum class RaftPushDeliveryCategory(val wire: String) {
    Dm("dm"),
    Mention("mention"),
    Channel("channel"),
    Thread("thread"),
    Task("task"),
    Reminder("reminder"),
    SystemNotice("system_notice"),
    Test("test"),
    Other("other"),
}

/** Closed `suppress_reason` enum for `mobile.push.presented` when `presented == false`. */
enum class RaftPushDeliverySuppressReason(val wire: String) {
    PermissionOff("permission_off"),
    AlertsDisabled("alerts_disabled"),
    ChannelMuted("channel_muted"),
    Dnd("dnd"),
    ForegroundSuppressed("foreground_suppressed"),
    VisibleConversation("visible_conversation"),
    Dedup("dedup"),
    Retracted("retracted"),
    RemotePushActive("remote_push_active"),
    PhoneAlertFalse("phone_alert_false"),
    PostFailed("post_failed"),
    Unknown("unknown");

    companion object {
        fun fromWire(raw: String?): RaftPushDeliverySuppressReason {
            val clean = raw?.trim()?.lowercase() ?: return Unknown
            return entries.firstOrNull { it.wire == clean } ?: Unknown
        }
    }
}

/** Closed `open_surface` enum for `mobile.push.opened`. */
enum class RaftPushDeliveryOpenSurface(val wire: String) {
    SystemTray("system_tray"),
    InAppBanner("in_app_banner"),
}

/** Closed 18-value manufacturer allowlist. */
val RAFT_PUSH_DELIVERY_MANUFACTURERS: Set<String> =
    setOf(
        "apple",
        "huawei",
        "honor",
        "xiaomi",
        "redmi",
        "oppo",
        "oneplus",
        "realme",
        "vivo",
        "iqoo",
        "samsung",
        "google",
        "meizu",
        "zte",
        "nubia",
        "motorola",
        "sony",
        "other",
    )

private val SubBrandKeys = listOf("redmi", "honor", "iqoo", "realme", "oneplus", "nubia")

private val ManufacturerKeys =
    listOf(
        "apple",
        "huawei",
        "honor",
        "xiaomi",
        "redmi",
        "oppo",
        "oneplus",
        "realme",
        "vivo",
        "iqoo",
        "samsung",
        "google",
        "meizu",
        "zte",
        "nubia",
        "motorola",
        "sony",
    )

private fun matchesOemToken(normalized: String, token: String): Boolean {
    if (normalized.isEmpty()) return false
    if (normalized == token) return true
    if (normalized.startsWith("$token ") || normalized.startsWith("$token-") || normalized.startsWith("${token}_")) {
        return true
    }
    if (token == "oneplus" && (normalized == "one plus" || normalized.startsWith("one plus "))) return true
    if (token == "motorola" && (normalized == "moto" || normalized.startsWith("moto "))) return true
    return false
}

/**
 * Normalizes OEM manufacturer & brand strings to the closed 18-value allowlist.
 *
 * Sub-brands (`redmi`, `honor`, `iqoo`, `realme`, `oneplus`, `nubia`) are checked on [brand] first
 * so Android devices whose `Build.MANUFACTURER` reports the parent OEM (`Xiaomi`, `HUAWEI`,
 * `vivo`, `OPPO`, `ZTE`) still classify accurately under their sub-brand.
 */
fun normalizePushDeliveryManufacturer(manufacturer: String?, brand: String? = null): String {
    val cleanBrand = brand?.trim()?.lowercase().orEmpty()
    val cleanManufacturer = manufacturer?.trim()?.lowercase().orEmpty()
    if (cleanBrand.isNotEmpty()) {
        for (subBrand in SubBrandKeys) {
            if (matchesOemToken(cleanBrand, subBrand)) return subBrand
        }
    }
    if (cleanManufacturer.isNotEmpty()) {
        for (key in ManufacturerKeys) {
            if (matchesOemToken(cleanManufacturer, key)) return key
        }
    }
    if (cleanBrand.isNotEmpty()) {
        for (key in ManufacturerKeys) {
            if (matchesOemToken(cleanBrand, key)) return key
        }
    }
    return "other"
}

/** Normalizes server notification `category` / `kind` to the closed [RaftPushDeliveryCategory] enum. */
fun normalizePushDeliveryCategory(
    category: String?,
    kind: String? = null,
    mentioned: Boolean = false,
): RaftPushDeliveryCategory {
    val cleanCategory = category?.trim()?.lowercase().orEmpty()
    if (cleanCategory.isNotEmpty()) {
        return when (cleanCategory) {
            "dm", "direct_message" -> RaftPushDeliveryCategory.Dm
            "mention" -> RaftPushDeliveryCategory.Mention
            "channel", "channel_unread", "general_unread" -> RaftPushDeliveryCategory.Channel
            "thread" -> RaftPushDeliveryCategory.Thread
            "task", "task_assigned", "task_status", "task_reply" -> RaftPushDeliveryCategory.Task
            "reminder" -> RaftPushDeliveryCategory.Reminder
            "system_notice", "system", "notice", "account_notice" -> RaftPushDeliveryCategory.SystemNotice
            "test" -> RaftPushDeliveryCategory.Test
            else -> RaftPushDeliveryCategory.Other
        }
    }
    if (mentioned) return RaftPushDeliveryCategory.Mention
    return when (kind?.trim()?.lowercase().orEmpty()) {
        "dm", "direct_message" -> RaftPushDeliveryCategory.Dm
        "thread" -> RaftPushDeliveryCategory.Thread
        "channel", "joint" -> RaftPushDeliveryCategory.Channel
        else -> RaftPushDeliveryCategory.Other
    }
}

/** Maps [ConversationKind] to wire kind hint for [normalizePushDeliveryCategory]. */
fun ConversationKind.pushDeliveryCategoryKindHint(): String =
    when (this) {
        ConversationKind.DirectMessage -> "dm"
        ConversationKind.Thread -> "thread"
        ConversationKind.Channel,
        ConversationKind.Joint -> "channel"
        else -> "other"
    }

/** Formats iOS version as low-cardinality `"major.minor"`. */
fun formatIosPushDeliveryOsVersion(major: Int, minor: Int): String =
    "${major.coerceAtLeast(0)}.${minor.coerceAtLeast(0)}"

private val OsVersionPattern = Regex("^[0-9]{1,3}(\\.[0-9]{1,3})?$")

/** Ensures `os_version` stays low-cardinality (`"35"`, `"18.4"`, or `"unknown"`). */
fun normalizePushDeliveryOsVersion(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return "unknown"
    if (OsVersionPattern.matches(trimmed)) return trimmed
    val parts = trimmed.split('.').mapNotNull { it.trim().toIntOrNull() }
    return when {
        parts.size >= 2 -> "${parts[0]}.${parts[1]}"
        parts.size == 1 -> parts[0].toString()
        else -> "unknown"
    }
}

/** Platform device attributes attached to push delivery events. */
data class RaftPushDeliveryDeviceContext(
    val manufacturer: String,
    val osVersion: String,
    val gmsAvailable: Boolean? = null,
    val dndActive: Boolean? = null,
)

/** Platform default device context (Android / iOS / Desktop). */
expect fun defaultRaftPushDeliveryDeviceContext(): RaftPushDeliveryDeviceContext

/**
 * In-memory landing destination matching criteria for a deferred `mobile.push.opened` event
 * (`#proj-mobile:#437`). Never persisted or serialized into trace payloads.
 */
data class RaftPushOpenLandingTarget(
    val serverId: String = "",
    val conversationIds: Set<String> = emptySet(),
    val parentMessageId: String? = null,
    val isActivityTarget: Boolean = false,
)

/** One persisted privacy-safe push delivery event awaiting foreground handoff. */
@Serializable
internal data class BufferedPushDeliveryEvent(
    val eventName: String,
    val accountId: String? = null,
    val serverId: String = "",
    val deliveryId: String,
    val transport: String,
    val manufacturer: String,
    val osVersion: String,
    val appState: String,
    val gmsAvailable: Boolean? = null,
    val occurredAtMs: Long,
    val pushCategory: String? = null,
    val presented: Boolean? = null,
    val suppressReason: String? = null,
    val dndActive: Boolean? = null,
    val inferredFromCenter: Boolean? = null,
    val openSurface: String? = null,
    val landingUnread: Int? = null,
    val landingLoadOk: Boolean? = null,
    val pushAgeMs: Long? = null,
    val readBeforeOpen: Boolean? = null,
    val badgeAtOpen: Int? = null,
)

@Serializable
internal data class PersistedPushDeliveryTraceState(
    val version: Int = 1,
    val accountId: String? = null,
    val droppedCount: Int = 0,
    val events: List<BufferedPushDeliveryEvent> = emptyList(),
    val reportedPresentedIds: List<String> = emptyList(),
)

internal data class DrainedPushDeliveryBatch(
    val events: List<BufferedPushDeliveryEvent>,
    val droppedCount: Int,
)

/**
 * Thread-safe, bounded persistent buffer for `mobile.push.*` events.
 *
 * - Max [maxEvents] (100) records; overflow evicts oldest and increments persisted `droppedCount`.
 * - TTL [ttlMillis] (48 hours); expired records are pruned and added to `droppedCount`.
 * - Also persists a bounded FIFO set of [maxReportedPresentedIds] (200) delivery IDs already
 *   reported as `presented`, preventing iOS Notification Center sweeps from double-reporting.
 */
internal class PushDeliveryTraceBuffer(
    private val store: SharedPreferenceStore,
    private val prefKey: String = RAFT_PUSH_DELIVERY_BUFFER_PREF_KEY,
    private val maxEvents: Int = RAFT_PUSH_DELIVERY_BUFFER_MAX_EVENTS,
    private val ttlMillis: Long = RAFT_PUSH_DELIVERY_BUFFER_TTL_MILLIS,
    private val maxReportedPresentedIds: Int = RAFT_PUSH_DELIVERY_REPORTED_PRESENTED_CAP,
) {
    private val lock = SynchronizedObject()
    private var loaded = false
    private var state = PersistedPushDeliveryTraceState()

    fun append(event: BufferedPushDeliveryEvent, nowMs: Long) {
        synchronized(lock) {
            ensureLoadedLocked()
            val normalizedEventAccount = event.accountId?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedEventAccount != null && state.accountId != null && state.accountId != normalizedEventAccount) {
                state = PersistedPushDeliveryTraceState(accountId = normalizedEventAccount)
            } else if (normalizedEventAccount != null && state.accountId == null) {
                state = state.copy(accountId = normalizedEventAccount)
            }
            val effectiveEvent =
                if (normalizedEventAccount == null && state.accountId != null) {
                    event.copy(accountId = state.accountId)
                } else {
                    event.copy(accountId = normalizedEventAccount)
                }
            var dropped = state.droppedCount
            val kept = ArrayList<BufferedPushDeliveryEvent>(state.events.size + 1)
            for (existing in state.events) {
                if (isExpired(existing.occurredAtMs, nowMs)) {
                    dropped += 1
                } else {
                    kept += existing
                }
            }
            if (isExpired(effectiveEvent.occurredAtMs, nowMs)) {
                dropped += 1
            } else {
                kept += effectiveEvent
                while (kept.size > maxEvents) {
                    kept.removeAt(0)
                    dropped += 1
                }
            }
            state = state.copy(droppedCount = dropped, events = kept)
            persistLocked()
        }
    }

    fun markPresentedReported(deliveryId: String) {
        synchronized(lock) {
            ensureLoadedLocked()
            if (deliveryId in state.reportedPresentedIds) return
            val next = ArrayList<String>(state.reportedPresentedIds.size + 1)
            next.addAll(state.reportedPresentedIds)
            next.add(deliveryId)
            while (next.size > maxReportedPresentedIds) {
                next.removeAt(0)
            }
            state = state.copy(reportedPresentedIds = next)
            persistLocked()
        }
    }

    fun isPresentedReported(deliveryId: String): Boolean =
        synchronized(lock) {
            ensureLoadedLocked()
            deliveryId in state.reportedPresentedIds
        }

    /** Atomically marks [deliveryId] as reported if not already present; returns true if new. */
    fun markPresentedIfNew(deliveryId: String): Boolean =
        synchronized(lock) {
            ensureLoadedLocked()
            if (deliveryId in state.reportedPresentedIds) return false
            val next = ArrayList<String>(state.reportedPresentedIds.size + 1)
            next.addAll(state.reportedPresentedIds)
            next.add(deliveryId)
            while (next.size > maxReportedPresentedIds) {
                next.removeAt(0)
            }
            state = state.copy(reportedPresentedIds = next)
            persistLocked()
            true
        }

    /**
     * Drains events deliverable under [activeAccountId].
     *
     * Expired events (>48h) increment `droppedCount`. Events belonging to a different non-null
     * account are discarded without emitting. `droppedCount` is returned and reset to 0 only when
     * at least one event is handed off (so a non-zero drop count is never silently lost before an
     * event carries it).
     */
    fun drainForAccount(
        activeAccountId: String,
        fallbackServerId: String?,
        nowMs: Long,
    ): DrainedPushDeliveryBatch =
        synchronized(lock) {
            ensureLoadedLocked()
            val cleanAccount = activeAccountId.trim().takeIf { it.isNotEmpty() }
                ?: return DrainedPushDeliveryBatch(emptyList(), 0)
            val cleanFallbackServer = fallbackServerId?.trim()?.takeIf { it.isNotEmpty() }
            if (state.events.isEmpty()) {
                return DrainedPushDeliveryBatch(emptyList(), 0)
            }
            var dropped = state.droppedCount
            val deliverable = ArrayList<BufferedPushDeliveryEvent>(state.events.size)
            val remainingWithoutServer = ArrayList<BufferedPushDeliveryEvent>()
            for (event in state.events) {
                if (isExpired(event.occurredAtMs, nowMs)) {
                    dropped += 1
                    continue
                }
                val eventAccount = event.accountId?.trim()?.takeIf { it.isNotEmpty() }
                if (eventAccount != null && eventAccount != cleanAccount) {
                    continue
                }
                val resolvedServer = event.serverId.trim().takeIf { it.isNotEmpty() } ?: cleanFallbackServer
                if (resolvedServer == null) {
                    remainingWithoutServer += event
                    continue
                }
                deliverable += if (resolvedServer == event.serverId) event else event.copy(serverId = resolvedServer)
            }
            if (deliverable.isEmpty()) {
                state =
                    state.copy(
                        accountId = cleanAccount,
                        droppedCount = dropped,
                        events = remainingWithoutServer,
                    )
                persistLocked()
                return DrainedPushDeliveryBatch(emptyList(), 0)
            }
            state =
                state.copy(
                    accountId = cleanAccount,
                    droppedCount = 0,
                    events = remainingWithoutServer,
                )
            persistLocked()
            DrainedPushDeliveryBatch(events = deliverable, droppedCount = dropped)
        }

    fun onAccountIdentified(accountId: String) {
        val clean = accountId.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            ensureLoadedLocked()
            if (state.accountId != null && state.accountId != clean) {
                state = PersistedPushDeliveryTraceState(accountId = clean)
                persistLocked()
            } else if (state.accountId == null) {
                state = state.copy(accountId = clean)
                persistLocked()
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            loaded = true
            state = PersistedPushDeliveryTraceState()
            runCatching { store.putString(prefKey, null) }
        }
    }

    fun snapshotSize(): Int =
        synchronized(lock) {
            ensureLoadedLocked()
            state.events.size
        }

    fun snapshotDroppedCount(): Int =
        synchronized(lock) {
            ensureLoadedLocked()
            state.droppedCount
        }

    private fun isExpired(occurredAtMs: Long, nowMs: Long): Boolean =
        nowMs - occurredAtMs > ttlMillis

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val raw = runCatching { store.getString(prefKey) }.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) {
            state = PersistedPushDeliveryTraceState()
            return
        }
        state =
            runCatching {
                pushDeliveryTraceJson.decodeFromString(PersistedPushDeliveryTraceState.serializer(), raw)
            }.getOrElse {
                PersistedPushDeliveryTraceState()
            }
    }

    private fun persistLocked() {
        runCatching {
            if (
                state.accountId == null &&
                state.droppedCount == 0 &&
                state.events.isEmpty() &&
                state.reportedPresentedIds.isEmpty()
            ) {
                store.putString(prefKey, null)
            } else {
                store.putString(
                    prefKey,
                    pushDeliveryTraceJson.encodeToString(PersistedPushDeliveryTraceState.serializer(), state),
                )
            }
        }
    }
}

internal fun BufferedPushDeliveryEvent.toTraceAttrs(
    reportedAtMs: Long,
    bufferDroppedCount: Int = 0,
): JsonObject =
    JsonObject(
        buildMap {
            put("delivery_id", JsonPrimitive(deliveryId))
            put("transport", JsonPrimitive(transport))
            put("manufacturer", JsonPrimitive(manufacturer))
            put("os_version", JsonPrimitive(osVersion))
            put("app_state", JsonPrimitive(appState))
            gmsAvailable?.let { put("gms_available", JsonPrimitive(it)) }
            put("occurred_at_ms", JsonPrimitive(occurredAtMs))
            put("reported_at_ms", JsonPrimitive(reportedAtMs))
            if (bufferDroppedCount > 0) {
                put("buffer_dropped_count", JsonPrimitive(bufferDroppedCount))
            }
            when (eventName) {
                RAFT_PUSH_DELIVERY_EVENT_RECEIVED -> {
                    put("push_category", JsonPrimitive(pushCategory ?: RaftPushDeliveryCategory.Other.wire))
                }
                RAFT_PUSH_DELIVERY_EVENT_PRESENTED -> {
                    val isPresented = presented == true
                    put("presented", JsonPrimitive(isPresented))
                    if (!isPresented) {
                        put(
                            "suppress_reason",
                            JsonPrimitive(suppressReason ?: RaftPushDeliverySuppressReason.Unknown.wire),
                        )
                    }
                    dndActive?.let { put("dnd_active", JsonPrimitive(it)) }
                    if (inferredFromCenter == true) {
                        put("inferred_from_center", JsonPrimitive(true))
                    }
                }
                RAFT_PUSH_DELIVERY_EVENT_OPENED -> {
                    put("open_surface", JsonPrimitive(openSurface ?: RaftPushDeliveryOpenSurface.SystemTray.wire))
                    landingLoadOk?.let { ok ->
                        put("landing_load_ok", JsonPrimitive(ok))
                        if (ok) {
                            landingUnread?.takeIf { it >= 0 }?.let { put("landing_unread", JsonPrimitive(it)) }
                        }
                    }
                    pushAgeMs?.takeIf { it >= 0L }?.let { put("push_age_ms", JsonPrimitive(it)) }
                    readBeforeOpen?.let { put("read_before_open", JsonPrimitive(it)) }
                    badgeAtOpen?.takeIf { it >= 0 }?.let { put("badge_at_open", JsonPrimitive(it)) }
                }
            }
        }
    )

/**
 * Coordinates validation, bounded local buffering, privacy/account gating, and foreground draining
 * of `mobile.push.*` events into [RaftClientTraceEvents].
 */
class PushDeliveryTraceRecorder(
    preferenceStore: SharedPreferenceStore,
    private val nowEpochMillis: () -> Long,
    private val privacyMode: StateFlow<RaftPrivacyMode>,
    private val isForeground: () -> Boolean,
    private val isTransportReady: () -> Boolean,
    private val deviceContextProvider: () -> RaftPushDeliveryDeviceContext,
    private val emit: (serverId: String, name: String, attrs: JsonObject, occurredAtMs: Long) -> Unit,
    private val isEnabled: () -> Boolean = { true },
    private val scope: CoroutineScope? = null,
    prefKey: String = RAFT_PUSH_DELIVERY_BUFFER_PREF_KEY,
    maxEvents: Int = RAFT_PUSH_DELIVERY_BUFFER_MAX_EVENTS,
    ttlMillis: Long = RAFT_PUSH_DELIVERY_BUFFER_TTL_MILLIS,
    maxReportedPresentedIds: Int = RAFT_PUSH_DELIVERY_REPORTED_PRESENTED_CAP,
    private val landingTimeoutMillis: Long = RAFT_PUSH_OPENED_LANDING_TIMEOUT_MILLIS,
    private val pushTimestampRecorder: (deliveryId: String, occurredAtMs: Long) -> Unit = { _, _ -> },
    private val pushTimestampLookup: (deliveryId: String, nowMs: Long) -> Long? = { _, _ -> null },
    private val readBeforeOpenLookup: (groupKey: String?, seq: Long?, pushOccurredAtMs: Long?, nowMs: Long) -> Boolean = { _, _, _, _ -> false },
    private val currentBadgeCountProvider: () -> Int = { 0 },
) {
    private data class PendingDeferredPushOpen(
        val token: Long,
        val deliveryId: String,
        val accountId: String?,
        val serverId: String,
        val transport: RaftPushDeliveryTransport,
        val appState: RaftPushDeliveryAppState,
        val openSurface: RaftPushDeliveryOpenSurface,
        val occurredAtMs: Long,
        val pushAgeMs: Long?,
        val readBeforeOpen: Boolean,
        val badgeAtOpen: Int,
        val device: RaftPushDeliveryDeviceContext,
        val target: RaftPushOpenLandingTarget,
    )

    private val buffer =
        PushDeliveryTraceBuffer(
            store = preferenceStore,
            prefKey = prefKey,
            maxEvents = maxEvents,
            ttlMillis = ttlMillis,
            maxReportedPresentedIds = maxReportedPresentedIds,
        )
    private val stateLock = SynchronizedObject()
    private val recentlyOpenedIds = ArrayList<String>()
    private var pendingOpen: PendingDeferredPushOpen? = null
    private var pendingOpenTimeoutJob: Job? = null
    private var pendingOpenSeq = 0L

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

    fun recordReceived(
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        category: RaftPushDeliveryCategory,
        occurredAtMs: Long = nowEpochMillis(),
        accountId: String? = null,
        deviceContext: RaftPushDeliveryDeviceContext? = null,
    ): Boolean {
        if (!isEnabled()) return false
        if (privacyMode.value == RaftPrivacyMode.On) return false
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return false
        runCatching { pushTimestampRecorder(normalizedId, occurredAtMs) }
        val device = safeDeviceContext(deviceContext)
        val event =
            BufferedPushDeliveryEvent(
                eventName = RAFT_PUSH_DELIVERY_EVENT_RECEIVED,
                accountId = resolveAccountId(accountId),
                serverId = resolveServerId(serverId),
                deliveryId = normalizedId,
                transport = transport.wire,
                manufacturer = normalizePushDeliveryManufacturer(device.manufacturer),
                osVersion = normalizePushDeliveryOsVersion(device.osVersion),
                appState = appState.wire,
                gmsAvailable = device.gmsAvailable,
                occurredAtMs = occurredAtMs,
                pushCategory = category.wire,
            )
        buffer.append(event, nowEpochMillis())
        drainIfReady()
        return true
    }

    fun recordPresented(
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        presented: Boolean,
        suppressReason: RaftPushDeliverySuppressReason? = null,
        inferredFromCenter: Boolean = false,
        occurredAtMs: Long = nowEpochMillis(),
        accountId: String? = null,
        deviceContext: RaftPushDeliveryDeviceContext? = null,
    ): Boolean {
        if (!isEnabled()) return false
        if (privacyMode.value == RaftPrivacyMode.On) return false
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return false
        buffer.markPresentedReported(normalizedId)
        val device = safeDeviceContext(deviceContext)
        val event =
            BufferedPushDeliveryEvent(
                eventName = RAFT_PUSH_DELIVERY_EVENT_PRESENTED,
                accountId = resolveAccountId(accountId),
                serverId = resolveServerId(serverId),
                deliveryId = normalizedId,
                transport = transport.wire,
                manufacturer = normalizePushDeliveryManufacturer(device.manufacturer),
                osVersion = normalizePushDeliveryOsVersion(device.osVersion),
                appState = appState.wire,
                gmsAvailable = device.gmsAvailable,
                occurredAtMs = occurredAtMs,
                presented = presented,
                suppressReason = if (presented) null else (suppressReason ?: RaftPushDeliverySuppressReason.Unknown).wire,
                dndActive = device.dndActive,
                inferredFromCenter = if (inferredFromCenter) true else null,
            )
        buffer.append(event, nowEpochMillis())
        drainIfReady()
        return true
    }

    /**
     * Used by iOS foreground `UNUserNotificationCenter.getDeliveredNotifications()` backfill:
     * skips any `deliveryId` already reported as `presented` (e.g. via `willPresent` or an
     * earlier foreground sweep).
     */
    fun recordPresentedIfNotAlreadyReported(
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        inferredFromCenter: Boolean = true,
        occurredAtMs: Long = nowEpochMillis(),
        accountId: String? = null,
        deviceContext: RaftPushDeliveryDeviceContext? = null,
    ): Boolean {
        if (!isEnabled()) return false
        if (privacyMode.value == RaftPrivacyMode.On) return false
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return false
        if (!buffer.markPresentedIfNew(normalizedId)) return false
        val device = safeDeviceContext(deviceContext)
        val event =
            BufferedPushDeliveryEvent(
                eventName = RAFT_PUSH_DELIVERY_EVENT_PRESENTED,
                accountId = resolveAccountId(accountId),
                serverId = resolveServerId(serverId),
                deliveryId = normalizedId,
                transport = transport.wire,
                manufacturer = normalizePushDeliveryManufacturer(device.manufacturer),
                osVersion = normalizePushDeliveryOsVersion(device.osVersion),
                appState = appState.wire,
                gmsAvailable = device.gmsAvailable,
                occurredAtMs = occurredAtMs,
                presented = true,
                suppressReason = null,
                dndActive = device.dndActive,
                inferredFromCenter = if (inferredFromCenter) true else null,
            )
        buffer.append(event, nowEpochMillis())
        drainIfReady()
        return true
    }

    fun isPresentedAlreadyReported(deliveryId: String?): Boolean {
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return false
        return buffer.isPresentedReported(normalizedId)
    }

    fun recordOpened(
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        openSurface: RaftPushDeliveryOpenSurface,
        occurredAtMs: Long = nowEpochMillis(),
        accountId: String? = null,
        deviceContext: RaftPushDeliveryDeviceContext? = null,
        landingUnread: Int? = null,
        landingLoadOk: Boolean? = null,
        pushAgeMs: Long? = null,
        readBeforeOpen: Boolean? = null,
        badgeAtOpen: Int? = null,
    ): Boolean {
        if (!isEnabled()) return false
        if (privacyMode.value == RaftPrivacyMode.On) return false
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return false
        val isNewOpen = reserveOpenedId(normalizedId)
        if (!isNewOpen) return false
        val device = safeDeviceContext(deviceContext)
        val event =
            BufferedPushDeliveryEvent(
                eventName = RAFT_PUSH_DELIVERY_EVENT_OPENED,
                accountId = resolveAccountId(accountId),
                serverId = resolveServerId(serverId),
                deliveryId = normalizedId,
                transport = transport.wire,
                manufacturer = normalizePushDeliveryManufacturer(device.manufacturer),
                osVersion = normalizePushDeliveryOsVersion(device.osVersion),
                appState = appState.wire,
                gmsAvailable = device.gmsAvailable,
                occurredAtMs = occurredAtMs,
                openSurface = openSurface.wire,
                landingUnread = if (landingLoadOk == true) landingUnread?.coerceAtLeast(0) else null,
                landingLoadOk = landingLoadOk,
                pushAgeMs = pushAgeMs?.coerceAtLeast(0L),
                readBeforeOpen = readBeforeOpen,
                badgeAtOpen = badgeAtOpen?.coerceAtLeast(0),
            )
        buffer.append(event, nowEpochMillis())
        drainIfReady()
        return true
    }

    /**
     * #proj-mobile:#437: captures tap-time attributes immediately (`occurred_at_ms` = tap time,
     * `push_age_ms`, `read_before_open`, `badge_at_open`) and defers emitting `mobile.push.opened`
     * until the destination conversation/Activity first load settles (or [landingTimeoutMillis] = 8s
     * expires, the app backgrounds, or another push open supersedes this one).
     */
    fun beginDeferredOpen(
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        openSurface: RaftPushDeliveryOpenSurface,
        target: RaftPushOpenLandingTarget = RaftPushOpenLandingTarget(),
        occurredAtMs: Long = nowEpochMillis(),
        pushOccurredAtMs: Long? = null,
        groupKey: String? = null,
        seq: Long? = null,
        readBeforeOpenOverride: Boolean? = null,
        badgeAtOpenOverride: Int? = null,
        accountId: String? = null,
        deviceContext: RaftPushDeliveryDeviceContext? = null,
    ): Boolean {
        if (!isEnabled()) return false
        if (privacyMode.value == RaftPrivacyMode.On) return false
        val normalizedId = normalizePushDeliveryId(deliveryId) ?: return false
        val resolvedServer = resolveServerId(serverId)
        val pushTs =
            pushOccurredAtMs?.takeIf { it > 0L }
                ?: runCatching { pushTimestampLookup(normalizedId, occurredAtMs) }.getOrNull()?.takeIf { it > 0L }
        val pushAgeMs = pushTs?.let { (occurredAtMs - it).coerceAtLeast(0L) }
        val readBeforeOpen =
            readBeforeOpenOverride
                ?: runCatching { readBeforeOpenLookup(groupKey, seq, pushTs, occurredAtMs) }.getOrDefault(false)
        val badgeAtOpen =
            (badgeAtOpenOverride ?: runCatching { currentBadgeCountProvider() }.getOrDefault(0))
                .coerceAtLeast(0)
        val device = safeDeviceContext(deviceContext)
        val normalizedTarget =
            RaftPushOpenLandingTarget(
                serverId = target.serverId.trim().ifEmpty { resolvedServer },
                conversationIds =
                    target.conversationIds
                        .mapNotNull { it.trim().takeIf { id -> id.isNotEmpty() } }
                        .toSet(),
                parentMessageId = target.parentMessageId?.trim()?.takeIf { it.isNotEmpty() },
                isActivityTarget = target.isActivityTarget,
            )
        var superseded: PendingDeferredPushOpen? = null
        var supersededJob: Job? = null
        var token = 0L
        val accepted =
            synchronized(stateLock) {
                if (normalizedId in recentlyOpenedIds) {
                    false
                } else {
                    recentlyOpenedIds += normalizedId
                    while (recentlyOpenedIds.size > RAFT_PUSH_DELIVERY_OPENED_DEDUP_CAP) {
                        recentlyOpenedIds.removeAt(0)
                    }
                    superseded = pendingOpen
                    supersededJob = pendingOpenTimeoutJob
                    pendingOpenTimeoutJob = null
                    pendingOpenSeq += 1L
                    token = pendingOpenSeq
                    pendingOpen =
                        PendingDeferredPushOpen(
                            token = token,
                            deliveryId = normalizedId,
                            accountId = resolveAccountId(accountId),
                            serverId = resolvedServer,
                            transport = transport,
                            appState = appState,
                            openSurface = openSurface,
                            occurredAtMs = occurredAtMs,
                            pushAgeMs = pushAgeMs,
                            readBeforeOpen = readBeforeOpen,
                            badgeAtOpen = badgeAtOpen,
                            device = device,
                            target = normalizedTarget,
                        )
                    true
                }
            }
        if (!accepted) return false
        supersededJob?.cancel()
        superseded?.let { previous ->
            emitSettledDeferredOpen(previous, landingLoadOk = false, landingUnread = null)
        }
        val timeoutJob =
            scope?.launch {
                delay(landingTimeoutMillis)
                settlePendingOpenByToken(token = token, landingLoadOk = false, landingUnread = null)
            }
        if (timeoutJob != null) {
            val attached =
                synchronized(stateLock) {
                    if (pendingOpen?.token == token) {
                        pendingOpenTimeoutJob = timeoutJob
                        true
                    } else {
                        false
                    }
                }
            if (!attached) {
                timeoutJob.cancel()
            }
        }
        return true
    }

    /**
     * Completes a pending deferred `mobile.push.opened` if its landing target matches the
     * conversation that just finished its first canonical load (called BEFORE `MarkVisibleRead`).
     */
    fun completeDeferredOpenForConversation(
        serverId: String?,
        candidateConversationIds: Set<String>,
        parentMessageId: String? = null,
        landingLoadOk: Boolean,
        landingUnread: Int? = null,
    ): Boolean {
        val cleanServer = serverId?.trim().orEmpty()
        val cleanIds = candidateConversationIds.mapNotNull { it.trim().takeIf { id -> id.isNotEmpty() } }.toSet()
        val cleanParent = parentMessageId?.trim()?.takeIf { it.isNotEmpty() }
        var matched: PendingDeferredPushOpen? = null
        var jobToCancel: Job? = null
        synchronized(stateLock) {
            val current = pendingOpen ?: return false
            if (current.target.isActivityTarget) return false
            if (current.target.serverId.isNotEmpty() && cleanServer.isNotEmpty() && current.target.serverId != cleanServer) {
                return false
            }
            val idMatch =
                if (current.target.conversationIds.isEmpty() && current.target.parentMessageId == null) {
                    true
                } else {
                    current.target.conversationIds.any { it in cleanIds } ||
                        (current.target.parentMessageId != null && current.target.parentMessageId == cleanParent)
                }
            if (!idMatch) return false
            matched = current
            pendingOpen = null
            jobToCancel = pendingOpenTimeoutJob
            pendingOpenTimeoutJob = null
        }
        jobToCancel?.cancel()
        matched?.let { pending ->
            emitSettledDeferredOpen(
                pending = pending,
                landingLoadOk = landingLoadOk,
                landingUnread = if (landingLoadOk) landingUnread?.coerceAtLeast(0) else null,
            )
            return true
        }
        return false
    }

    /** Completes a pending Activity-target deferred `mobile.push.opened` (reminders/notices/tests). */
    fun completeDeferredOpenForActivity(
        serverId: String?,
        landingLoadOk: Boolean,
        landingUnread: Int? = null,
    ): Boolean {
        val cleanServer = serverId?.trim().orEmpty()
        var matched: PendingDeferredPushOpen? = null
        var jobToCancel: Job? = null
        synchronized(stateLock) {
            val current = pendingOpen ?: return false
            if (!current.target.isActivityTarget) return false
            if (current.target.serverId.isNotEmpty() && cleanServer.isNotEmpty() && current.target.serverId != cleanServer) {
                return false
            }
            matched = current
            pendingOpen = null
            jobToCancel = pendingOpenTimeoutJob
            pendingOpenTimeoutJob = null
        }
        jobToCancel?.cancel()
        matched?.let { pending ->
            emitSettledDeferredOpen(
                pending = pending,
                landingLoadOk = landingLoadOk,
                landingUnread = if (landingLoadOk) landingUnread?.coerceAtLeast(0) else null,
            )
            return true
        }
        return false
    }

    /** Flushes any pending deferred push open immediately with `landing_load_ok = false`. */
    fun flushPendingOpenAsFailed(): Boolean {
        var pending: PendingDeferredPushOpen? = null
        var jobToCancel: Job? = null
        synchronized(stateLock) {
            pending = pendingOpen
            pendingOpen = null
            jobToCancel = pendingOpenTimeoutJob
            pendingOpenTimeoutJob = null
        }
        jobToCancel?.cancel()
        val flushed = pending ?: return false
        emitSettledDeferredOpen(flushed, landingLoadOk = false, landingUnread = null)
        return true
    }

    @build.raft.app.RaftCoreInternalApi
    fun hasPendingDeferredOpenForTest(): Boolean = synchronized(stateLock) { pendingOpen != null }

    private fun reserveOpenedId(normalizedId: String): Boolean =
        synchronized(stateLock) {
            if (normalizedId in recentlyOpenedIds) {
                false
            } else {
                recentlyOpenedIds += normalizedId
                while (recentlyOpenedIds.size > RAFT_PUSH_DELIVERY_OPENED_DEDUP_CAP) {
                    recentlyOpenedIds.removeAt(0)
                }
                true
            }
        }

    private fun settlePendingOpenByToken(
        token: Long,
        landingLoadOk: Boolean,
        landingUnread: Int?,
    ) {
        var matched: PendingDeferredPushOpen? = null
        synchronized(stateLock) {
            val current = pendingOpen ?: return
            if (current.token != token) return
            matched = current
            pendingOpen = null
            pendingOpenTimeoutJob = null
        }
        matched?.let { pending ->
            emitSettledDeferredOpen(pending, landingLoadOk = landingLoadOk, landingUnread = landingUnread)
        }
    }

    private fun discardPendingOpen() {
        val jobToCancel =
            synchronized(stateLock) {
                pendingOpen = null
                pendingOpenTimeoutJob.also { pendingOpenTimeoutJob = null }
            }
        jobToCancel?.cancel()
    }

    private fun emitSettledDeferredOpen(
        pending: PendingDeferredPushOpen,
        landingLoadOk: Boolean,
        landingUnread: Int?,
    ) {
        if (!isEnabled()) return
        if (privacyMode.value == RaftPrivacyMode.On) return
        val event =
            BufferedPushDeliveryEvent(
                eventName = RAFT_PUSH_DELIVERY_EVENT_OPENED,
                accountId = pending.accountId ?: resolveAccountId(null),
                serverId = pending.serverId.ifEmpty { resolveServerId(null) },
                deliveryId = pending.deliveryId,
                transport = pending.transport.wire,
                manufacturer = normalizePushDeliveryManufacturer(pending.device.manufacturer),
                osVersion = normalizePushDeliveryOsVersion(pending.device.osVersion),
                appState = pending.appState.wire,
                gmsAvailable = pending.device.gmsAvailable,
                occurredAtMs = pending.occurredAtMs,
                openSurface = pending.openSurface.wire,
                landingUnread = if (landingLoadOk) landingUnread?.coerceAtLeast(0) else null,
                landingLoadOk = landingLoadOk,
                pushAgeMs = pending.pushAgeMs?.coerceAtLeast(0L),
                readBeforeOpen = pending.readBeforeOpen,
                badgeAtOpen = pending.badgeAtOpen.coerceAtLeast(0),
            )
        buffer.append(event, nowEpochMillis())
        drainIfReady()
    }

    fun onSignedIn(accountId: String?, serverId: String? = null) {
        val cleanAccount = accountId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanServer != null) {
            activeServerId = cleanServer
            knownServerHint = cleanServer
        }
        if (cleanAccount == null) return
        val accountChanged =
            synchronized(stateLock) {
                val previous = activeAccountId
                activeAccountId = cleanAccount
                knownAccountHint = cleanAccount
                if (previous != null && previous != cleanAccount) {
                    recentlyOpenedIds.clear()
                    true
                } else {
                    false
                }
            }
        if (accountChanged) {
            discardPendingOpen()
            buffer.clearAll()
        }
        buffer.onAccountIdentified(cleanAccount)
        drainIfReady()
    }

    fun setKnownAccountHint(accountId: String?, serverId: String? = null) {
        val cleanAccount = accountId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanServer != null) knownServerHint = cleanServer
        if (cleanAccount != null) {
            knownAccountHint = cleanAccount
            buffer.onAccountIdentified(cleanAccount)
        }
    }

    fun onSignedOut() {
        var jobToCancel: Job? = null
        synchronized(stateLock) {
            activeAccountId = null
            activeServerId = null
            knownAccountHint = null
            knownServerHint = null
            recentlyOpenedIds.clear()
            pendingOpen = null
            jobToCancel = pendingOpenTimeoutJob
            pendingOpenTimeoutJob = null
        }
        jobToCancel?.cancel()
        buffer.clearAll()
    }

    fun onTransportBound() {
        drainIfReady()
    }

    fun onProcessVisibilityChanged(foreground: Boolean) {
        if (foreground) {
            drainIfReady()
        } else {
            flushPendingOpenAsFailed()
        }
    }

    fun onPrivacyModeChanged(mode: RaftPrivacyMode) {
        when (mode) {
            RaftPrivacyMode.On -> {
                var jobToCancel: Job? = null
                synchronized(stateLock) {
                    recentlyOpenedIds.clear()
                    pendingOpen = null
                    jobToCancel = pendingOpenTimeoutJob
                    pendingOpenTimeoutJob = null
                }
                jobToCancel?.cancel()
                buffer.clearAll()
            }
            RaftPrivacyMode.Off -> drainIfReady()
            RaftPrivacyMode.Unknown -> Unit
        }
    }

    /**
     * Hands buffered events into [RaftClientTraceEvents] strictly when foregrounded, signed in,
     * transport-bound, and `RAFT_PRIVACY_MODE == Off`. Never starts background network work.
     */
    fun drainIfReady(): Int {
        if (!isEnabled()) return 0
        if (privacyMode.value != RaftPrivacyMode.Off) return 0
        if (!isForeground()) return 0
        if (!isTransportReady()) return 0
        val account = activeAccountId ?: return 0
        val nowMs = nowEpochMillis()
        val drained =
            buffer.drainForAccount(
                activeAccountId = account,
                fallbackServerId = activeServerId ?: knownServerHint,
                nowMs = nowMs,
            )
        if (drained.events.isEmpty()) return 0
        val reportedAtMs = nowEpochMillis()
        drained.events.forEachIndexed { index, event ->
            val attrs =
                event.toTraceAttrs(
                    reportedAtMs = reportedAtMs,
                    bufferDroppedCount = if (index == 0) drained.droppedCount else 0,
                )
            runCatching {
                emit(event.serverId, event.eventName, attrs, event.occurredAtMs)
            }
        }
        return drained.events.size
    }

    @build.raft.app.RaftCoreInternalApi fun bufferedCountForTest(): Int = buffer.snapshotSize()

    @build.raft.app.RaftCoreInternalApi fun droppedCountForTest(): Int = buffer.snapshotDroppedCount()

    private fun resolveAccountId(explicit: String?): String? =
        explicit?.trim()?.takeIf { it.isNotEmpty() } ?: activeAccountId ?: knownAccountHint

    private fun resolveServerId(explicit: String?): String =
        explicit?.trim()?.takeIf { it.isNotEmpty() } ?: activeServerId ?: knownServerHint.orEmpty()

    private fun safeDeviceContext(override: RaftPushDeliveryDeviceContext?): RaftPushDeliveryDeviceContext =
        override ?: runCatching { deviceContextProvider() }.getOrElse {
            RaftPushDeliveryDeviceContext(manufacturer = "other", osVersion = "unknown")
        }
}

internal fun isMobilePushDeliveryPlatform(): Boolean {
    val os = runCatching { featureFlagClientOs() }.getOrNull()
    return os == FEATURE_FLAG_CLIENT_OS_ANDROID || os == FEATURE_FLAG_CLIENT_OS_IOS
}

/** Process-wide push delivery observability host. */
object RaftPushDeliveryTrace {
    @Volatile
    private var deviceContextOverride: (() -> RaftPushDeliveryDeviceContext)? = null

    private val recorder: PushDeliveryTraceRecorder by lazy {
        PushDeliveryTraceRecorder(
            preferenceStore = raftSharedPreferenceStore(),
            nowEpochMillis = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() },
            privacyMode = RaftTelemetryPrivacy.state,
            isForeground = ::raftProcessIsForeground,
            isTransportReady = { RaftClientTraceEvents.sender.canAcceptImmediateHandoff() },
            deviceContextProvider = {
                deviceContextOverride?.invoke() ?: defaultRaftPushDeliveryDeviceContext()
            },
            emit = { serverId, name, attrs, occurredAtMs ->
                RaftClientTraceEvents.emitPushDeliveryEvent(serverId, name, attrs, occurredAtMs)
            },
            isEnabled = ::isMobilePushDeliveryPlatform,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            pushTimestampRecorder = RaftBadgeTelemetry::recordPushTimestamp,
            pushTimestampLookup = RaftBadgeTelemetry::lookupPushTimestamp,
            readBeforeOpenLookup = RaftBadgeTelemetry::isReadBeforeOpen,
            currentBadgeCountProvider = RaftBadgeTelemetry::currentBadgeCountForOpen,
        )
    }

    /** Installs a platform device-context provider (e.g. Android Context-backed GMS & DND checks). */
    fun installDeviceContextProvider(provider: () -> RaftPushDeliveryDeviceContext) {
        deviceContextOverride = provider
        RaftBadgeTelemetry.installDeviceContextProvider(provider)
    }

    fun recordReceived(
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        category: RaftPushDeliveryCategory,
        occurredAtMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
        accountId: String? = null,
        deviceContext: RaftPushDeliveryDeviceContext? = null,
    ): Boolean =
        runCatching {
            recorder.recordReceived(
                deliveryId = deliveryId,
                serverId = serverId,
                transport = transport,
                appState = appState,
                category = category,
                occurredAtMs = occurredAtMs,
                accountId = accountId,
                deviceContext = deviceContext,
            )
        }.getOrDefault(false)

    fun recordPresented(
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        presented: Boolean,
        suppressReason: RaftPushDeliverySuppressReason? = null,
        inferredFromCenter: Boolean = false,
        occurredAtMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
        accountId: String? = null,
        deviceContext: RaftPushDeliveryDeviceContext? = null,
    ): Boolean =
        runCatching {
            recorder.recordPresented(
                deliveryId = deliveryId,
                serverId = serverId,
                transport = transport,
                appState = appState,
                presented = presented,
                suppressReason = suppressReason,
                inferredFromCenter = inferredFromCenter,
                occurredAtMs = occurredAtMs,
                accountId = accountId,
                deviceContext = deviceContext,
            )
        }.getOrDefault(false)

    fun recordPresentedIfNotAlreadyReported(
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        inferredFromCenter: Boolean = true,
        occurredAtMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
        accountId: String? = null,
        deviceContext: RaftPushDeliveryDeviceContext? = null,
    ): Boolean =
        runCatching {
            recorder.recordPresentedIfNotAlreadyReported(
                deliveryId = deliveryId,
                serverId = serverId,
                transport = transport,
                appState = appState,
                inferredFromCenter = inferredFromCenter,
                occurredAtMs = occurredAtMs,
                accountId = accountId,
                deviceContext = deviceContext,
            )
        }.getOrDefault(false)

    fun isPresentedAlreadyReported(deliveryId: String?): Boolean =
        runCatching { recorder.isPresentedAlreadyReported(deliveryId) }.getOrDefault(false)

    fun recordOpened(
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        openSurface: RaftPushDeliveryOpenSurface,
        occurredAtMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
        accountId: String? = null,
        deviceContext: RaftPushDeliveryDeviceContext? = null,
        landingUnread: Int? = null,
        landingLoadOk: Boolean? = null,
        pushAgeMs: Long? = null,
        readBeforeOpen: Boolean? = null,
        badgeAtOpen: Int? = null,
    ): Boolean =
        runCatching {
            recorder.recordOpened(
                deliveryId = deliveryId,
                serverId = serverId,
                transport = transport,
                appState = appState,
                openSurface = openSurface,
                occurredAtMs = occurredAtMs,
                accountId = accountId,
                deviceContext = deviceContext,
                landingUnread = landingUnread,
                landingLoadOk = landingLoadOk,
                pushAgeMs = pushAgeMs,
                readBeforeOpen = readBeforeOpen,
                badgeAtOpen = badgeAtOpen,
            )
        }.getOrDefault(false)

    fun beginDeferredOpen(
        deliveryId: String?,
        serverId: String?,
        transport: RaftPushDeliveryTransport,
        appState: RaftPushDeliveryAppState,
        openSurface: RaftPushDeliveryOpenSurface,
        target: RaftPushOpenLandingTarget = RaftPushOpenLandingTarget(),
        occurredAtMs: Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
        pushOccurredAtMs: Long? = null,
        groupKey: String? = null,
        seq: Long? = null,
        readBeforeOpenOverride: Boolean? = null,
        badgeAtOpenOverride: Int? = null,
        accountId: String? = null,
        deviceContext: RaftPushDeliveryDeviceContext? = null,
    ): Boolean =
        runCatching {
            recorder.beginDeferredOpen(
                deliveryId = deliveryId,
                serverId = serverId,
                transport = transport,
                appState = appState,
                openSurface = openSurface,
                target = target,
                occurredAtMs = occurredAtMs,
                pushOccurredAtMs = pushOccurredAtMs,
                groupKey = groupKey,
                seq = seq,
                readBeforeOpenOverride = readBeforeOpenOverride,
                badgeAtOpenOverride = badgeAtOpenOverride,
                accountId = accountId,
                deviceContext = deviceContext,
            )
        }.getOrDefault(false)

    fun completeDeferredOpenForConversation(
        serverId: String?,
        candidateConversationIds: Set<String>,
        parentMessageId: String? = null,
        landingLoadOk: Boolean,
        landingUnread: Int? = null,
    ): Boolean =
        runCatching {
            recorder.completeDeferredOpenForConversation(
                serverId = serverId,
                candidateConversationIds = candidateConversationIds,
                parentMessageId = parentMessageId,
                landingLoadOk = landingLoadOk,
                landingUnread = landingUnread,
            )
        }.getOrDefault(false)

    fun completeDeferredOpenForActivity(
        serverId: String?,
        landingLoadOk: Boolean,
        landingUnread: Int? = null,
    ): Boolean =
        runCatching {
            recorder.completeDeferredOpenForActivity(
                serverId = serverId,
                landingLoadOk = landingLoadOk,
                landingUnread = landingUnread,
            )
        }.getOrDefault(false)

    fun flushPendingOpenAsFailed(): Boolean =
        runCatching { recorder.flushPendingOpenAsFailed() }.getOrDefault(false)

    fun onSignedIn(accountId: String?, serverId: String? = null) {
        runCatching { recorder.onSignedIn(accountId, serverId) }
        RaftBadgeTelemetry.onSignedIn(accountId, serverId)
    }

    fun setKnownAccountHint(accountId: String?, serverId: String? = null) {
        runCatching { recorder.setKnownAccountHint(accountId, serverId) }
        RaftBadgeTelemetry.setKnownAccountHint(accountId, serverId)
    }

    fun onSignedOut() {
        runCatching { recorder.onSignedOut() }
        RaftBadgeTelemetry.onSignedOut()
    }

    fun onTransportBound() {
        runCatching { recorder.onTransportBound() }
        RaftBadgeTelemetry.onTransportBound()
    }

    fun onProcessVisibility(foreground: Boolean) {
        runCatching { recorder.onProcessVisibilityChanged(foreground) }
    }

    fun drainIfReady(): Int =
        runCatching { recorder.drainIfReady() }.getOrDefault(0) + RaftBadgeTelemetry.drainIfReady()
}

/**
 * Swift-friendly entry point exported in the shared iOS framework (primitive parameters only so
 * Swift call sites avoid boxed `KotlinBoolean?` / `KotlinLong?` bridging).
 */
object RaftIosPushDeliveryTraceBridge {
    fun recordApnsReceived(
        deliveryId: String?,
        serverId: String?,
        category: String?,
        kind: String?,
        isForeground: Boolean,
        occurredAtMs: Long,
    ) {
        RaftPushDeliveryTrace.recordReceived(
            deliveryId = deliveryId,
            serverId = serverId,
            transport = RaftPushDeliveryTransport.Apns,
            appState = if (isForeground) RaftPushDeliveryAppState.Foreground else RaftPushDeliveryAppState.Background,
            category = normalizePushDeliveryCategory(category = category, kind = kind),
            occurredAtMs = occurredAtMs,
        )
    }

    fun recordApnsPresented(
        deliveryId: String?,
        serverId: String?,
        isForeground: Boolean,
        occurredAtMs: Long,
    ) {
        RaftPushDeliveryTrace.recordPresented(
            deliveryId = deliveryId,
            serverId = serverId,
            transport = RaftPushDeliveryTransport.Apns,
            appState = if (isForeground) RaftPushDeliveryAppState.Foreground else RaftPushDeliveryAppState.Background,
            presented = true,
            suppressReason = null,
            inferredFromCenter = false,
            occurredAtMs = occurredAtMs,
        )
    }

    fun recordApnsSuppressed(
        deliveryId: String?,
        serverId: String?,
        suppressReason: String,
        isForeground: Boolean,
        occurredAtMs: Long,
    ) {
        RaftPushDeliveryTrace.recordPresented(
            deliveryId = deliveryId,
            serverId = serverId,
            transport = RaftPushDeliveryTransport.Apns,
            appState = if (isForeground) RaftPushDeliveryAppState.Foreground else RaftPushDeliveryAppState.Background,
            presented = false,
            suppressReason = RaftPushDeliverySuppressReason.fromWire(suppressReason),
            inferredFromCenter = false,
            occurredAtMs = occurredAtMs,
        )
    }

    fun recordApnsCenterBackfillPresented(
        deliveryId: String?,
        serverId: String?,
        occurredAtMs: Long,
    ): Boolean {
        if (occurredAtMs > 0L) {
            RaftBadgeTelemetry.recordPushTimestamp(deliveryId = deliveryId, occurredAtMs = occurredAtMs)
        }
        return RaftPushDeliveryTrace.recordPresentedIfNotAlreadyReported(
            deliveryId = deliveryId,
            serverId = serverId,
            transport = RaftPushDeliveryTransport.Apns,
            appState = RaftPushDeliveryAppState.Background,
            inferredFromCenter = true,
            occurredAtMs = occurredAtMs,
        )
    }

    fun recordApnsOpened(
        deliveryId: String?,
        serverId: String?,
        appState: String,
        occurredAtMs: Long,
    ) {
        val state = RaftPushDeliveryAppState.fromWire(appState) ?: RaftPushDeliveryAppState.Background
        RaftPushDeliveryTrace.recordOpened(
            deliveryId = deliveryId,
            serverId = serverId,
            transport = RaftPushDeliveryTransport.Apns,
            appState = state,
            openSurface = RaftPushDeliveryOpenSurface.SystemTray,
            occurredAtMs = occurredAtMs,
        )
    }

    /**
     * #proj-mobile:#437: Swift-friendly deferred push open entry point (primitives only).
     * Pass `-1L` for absent [pushOccurredAtMs], `-1` for absent [badgeAtOpen], and
     * [hasSeq] = false when seq is unavailable.
     */
    fun beginApnsOpened(
        deliveryId: String?,
        serverId: String?,
        appState: String,
        occurredAtMs: Long,
        pushOccurredAtMs: Long,
        badgeAtOpen: Int,
        groupKey: String?,
        seq: Long,
        hasSeq: Boolean,
        readBeforeOpenHint: Boolean,
        conversationId: String?,
        channelId: String?,
        threadId: String?,
        parentMessageId: String?,
        taskConversationChannelId: String?,
        isActivityTarget: Boolean,
    ) {
        val state = RaftPushDeliveryAppState.fromWire(appState) ?: RaftPushDeliveryAppState.Background
        val cleanPushTs = pushOccurredAtMs.takeIf { it > 0L }
        val cleanSeq = if (hasSeq) seq else null
        val candidateIds =
            listOfNotNull(conversationId, channelId, threadId, taskConversationChannelId)
                .mapNotNull { it.trim().takeIf { id -> id.isNotEmpty() } }
                .toSet()
        val readBeforeOpen =
            readBeforeOpenHint ||
                RaftBadgeTelemetry.isReadBeforeOpen(
                    groupKey = groupKey,
                    seq = cleanSeq,
                    pushOccurredAtMs = cleanPushTs,
                    nowMs = occurredAtMs,
                )
        RaftPushDeliveryTrace.beginDeferredOpen(
            deliveryId = deliveryId,
            serverId = serverId,
            transport = RaftPushDeliveryTransport.Apns,
            appState = state,
            openSurface = RaftPushDeliveryOpenSurface.SystemTray,
            target =
                RaftPushOpenLandingTarget(
                    serverId = serverId?.trim().orEmpty(),
                    conversationIds = candidateIds,
                    parentMessageId = parentMessageId?.trim()?.takeIf { it.isNotEmpty() },
                    isActivityTarget = isActivityTarget,
                ),
            occurredAtMs = occurredAtMs,
            pushOccurredAtMs = cleanPushTs,
            groupKey = groupKey,
            seq = cleanSeq,
            readBeforeOpenOverride = readBeforeOpen,
            badgeAtOpenOverride = badgeAtOpen.takeIf { it >= 0 },
        )
    }

    fun noteIosIconBadgeBeforeForeground(count: Int) {
        RaftBadgeTelemetry.noteLiveIconBadgeBeforeForeground(count)
    }

    fun recordBadgeSet(
        count: Int,
        source: String,
        setAtMs: Long,
    ) {
        val badgeSource = RaftBadgeSource.fromWire(source) ?: return
        RaftBadgeTelemetry.recordBadgeSet(count = count, source = badgeSource, setAtMs = setAtMs)
    }

    fun enrichForegroundAlertBadge(
        badge: Int,
        alertAtMs: Long,
    ) {
        RaftBadgeTelemetry.enrichForegroundSnapshotFromDeliveredAlert(
            badge = badge.takeIf { it >= 0 },
            alertAtMs = alertAtMs,
        )
    }

    fun recordRetract(
        groupKey: String?,
        throughSeq: Long,
        hasThroughSeq: Boolean,
        retractedAtMs: Long,
    ) {
        RaftBadgeTelemetry.recordRetract(
            groupKey = groupKey,
            throughSeq = if (hasThroughSeq) throughSeq else null,
            retractedAtMs = retractedAtMs,
        )
    }

    fun isPresentedAlreadyReported(deliveryId: String?): Boolean =
        RaftPushDeliveryTrace.isPresentedAlreadyReported(deliveryId)

    fun drainIfReady() {
        RaftPushDeliveryTrace.drainIfReady()
    }
}
