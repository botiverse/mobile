package build.raft.app.shared

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import build.raft.app.MainActivity
import build.raft.app.R
import build.raft.app.RaftPixelAvatarRaster
import build.raft.app.core.data.auth.androidMMKVLocalPreferenceKeyValueStore
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.RaftDesktopShortcutContract
import build.raft.app.data.RaftDesktopShortcutItem
import build.raft.app.data.RaftShareSuggestion
import build.raft.app.data.RaftShareSuggestionContract
import build.raft.app.data.RaftShareSuggestionKind
import build.raft.app.data.RaftShareSuggestionsHost
import build.raft.app.data.RaftShareSuggestionsHostProvider
import build.raft.app.image.raftReadCachedAvatarImageBytesOrNull
import build.raft.app.raftApplyPersistedPixelAvatars
import build.raft.app.raftPixelAvatarRaster
import build.raft.app.widget.toSharedPixelAvatarWireValue
import build.raft.app.widget.widgetPixelArgb
import build.raft.app.widget.widgetPixelSize
import org.json.JSONObject

/**
 * Backend seam over [ShortcutManagerCompat] so unit tests can verify coexistence between pinned
 * launcher shortcuts and Direct Share targets as well as full dynamic + cached cleanup on logout.
 */
internal interface AndroidDynamicShortcutBackend {
    val sdkInt: Int
    fun maxShortcutCountPerActivity(): Int
    fun existingDynamicAndCachedShortcutIds(): List<String>
    fun setDynamicShortcuts(shortcuts: List<ShortcutInfoCompat>)
    fun pushDynamicShortcut(shortcut: ShortcutInfoCompat)
    fun removeDynamicShortcuts(shortcutIds: List<String>)
    fun removeAllDynamicShortcuts()
    fun removeLongLivedShortcuts(shortcutIds: List<String>)
}

private class SystemAndroidDynamicShortcutBackend(
    private val context: Context
) : AndroidDynamicShortcutBackend {
    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun maxShortcutCountPerActivity(): Int =
        ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(1)

    override fun existingDynamicAndCachedShortcutIds(): List<String> {
        val flags =
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED
        return ShortcutManagerCompat.getShortcuts(context, flags).map { it.id }
    }

    override fun setDynamicShortcuts(shortcuts: List<ShortcutInfoCompat>) {
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    override fun pushDynamicShortcut(shortcut: ShortcutInfoCompat) {
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    override fun removeDynamicShortcuts(shortcutIds: List<String>) {
        if (shortcutIds.isNotEmpty()) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, shortcutIds)
        }
    }

    override fun removeAllDynamicShortcuts() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    override fun removeLongLivedShortcuts(shortcutIds: List<String>) {
        if (shortcutIds.isNotEmpty()) {
            ShortcutManagerCompat.removeLongLivedShortcuts(context, shortcutIds)
        }
    }
}

/**
 * Single owner of Android dynamic and long-lived shortcuts so pinned launcher icon shortcuts
 * ([updatePinnedShortcuts]) and Direct Share suggestions ([publish]) coexist without wiping one
 * another, and sign-out / account changes purge both dynamic and API 30+ cached long-lived targets.
 *
 * Uses separate [stateLock] (fast, in-memory only) and [applyLock] (background worker only) so
 * callers on the main thread never block behind Binder IPC, MMKV reads, or bitmap rendering.
 */
internal class AndroidDynamicShortcutCoordinatorEngine(
    private val context: Context,
    private val backend: AndroidDynamicShortcutBackend,
    private val executor: (String, () -> Unit) -> Unit = { name, task ->
        RaftThreadAdapter.execute(name, task)
    },
    private val launchIdClock: () -> Long = System::currentTimeMillis,
    private val iconFactory: (Context, RaftShareSuggestion) -> IconCompat = ::buildAndroidShareSuggestionIcon,
    private val logDebug: (String) -> Unit = { message -> RaftAppLog.d(TAG, message) },
    private val logWarn: (String) -> Unit = { message -> RaftAppLog.w(TAG, message) }
) : RaftShareSuggestionsHost {
    private val stateLock = Any()
    private val applyLock = Any()
    private var pinnedItems: List<RaftDesktopShortcutItem> = emptyList()
    private var shareAccountId: String? = null
    private var shareServerId: String? = null
    private var shareSuggestions: List<RaftShareSuggestion> = emptyList()
    private var shareSuggestionsPublished: Boolean = false
    private var requestedPurgeGeneration: Long = 0L
    private var requestedPurgeReason: String? = null
    private var pendingGeneration: Long = 0L

    // Guarded by [applyLock].
    private var appliedPurgeGeneration: Long = 0L
    private var lastAppliedSignature: String? = null

    fun updatePinnedShortcuts(items: List<RaftDesktopShortcutItem>) {
        val normalized = items.take(MAX_PINNED_SHORTCUTS)
        val snapshot =
            synchronized(stateLock) {
                pinnedItems = normalized
                pendingGeneration += 1L
                Snapshot(
                    generation = pendingGeneration,
                    pinned = pinnedItems,
                    shareAccountId = shareAccountId,
                    shareServerId = shareServerId,
                    share = shareSuggestions,
                    shareSuggestionsPublished = shareSuggestionsPublished,
                    requestedPurgeGeneration = requestedPurgeGeneration,
                    requestedPurgeReason = requestedPurgeReason,
                    clearAll = false,
                    clearReason = null
                )
            }
        schedule(snapshot)
    }

    override fun publish(
        accountId: String,
        serverId: String,
        suggestions: List<RaftShareSuggestion>
    ) {
        val cleanAccountId = accountId.trim()
        val cleanServerId = serverId.trim()
        if (cleanAccountId.isEmpty() || cleanServerId.isEmpty()) return
        val normalized =
            suggestions
                .filter { isConsistentShareSuggestion(it) }
                .distinctBy { it.shortcutId }
                .take(RaftShareSuggestionContract.MaxSuggestionCount)
        val snapshot =
            synchronized(stateLock) {
                shareAccountId = cleanAccountId
                shareServerId = cleanServerId
                shareSuggestions = normalized
                shareSuggestionsPublished = true
                pendingGeneration += 1L
                Snapshot(
                    generation = pendingGeneration,
                    pinned = pinnedItems,
                    shareAccountId = shareAccountId,
                    shareServerId = shareServerId,
                    share = shareSuggestions,
                    shareSuggestionsPublished = true,
                    requestedPurgeGeneration = requestedPurgeGeneration,
                    requestedPurgeReason = requestedPurgeReason,
                    clearAll = false,
                    clearReason = null
                )
            }
        schedule(snapshot)
    }

    override fun clear(reason: String) {
        val snapshot =
            synchronized(stateLock) {
                pinnedItems = emptyList()
                shareAccountId = null
                shareServerId = null
                shareSuggestions = emptyList()
                // After an explicit clear (logout / signed-out), empty share state is authoritative
                // so a follow-up updatePinnedShortcuts(emptyList()) never preserves prior-session targets.
                shareSuggestionsPublished = true
                requestedPurgeGeneration += 1L
                requestedPurgeReason = reason
                pendingGeneration += 1L
                Snapshot(
                    generation = pendingGeneration,
                    pinned = emptyList(),
                    shareAccountId = null,
                    shareServerId = null,
                    share = emptyList(),
                    shareSuggestionsPublished = true,
                    requestedPurgeGeneration = requestedPurgeGeneration,
                    requestedPurgeReason = requestedPurgeReason,
                    clearAll = true,
                    clearReason = reason
                )
            }
        schedule(snapshot)
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(stateLock) { generation == pendingGeneration }

    private fun schedule(snapshot: Snapshot) {
        executor("sync-dynamic-shortcuts") {
            if (isCurrentGeneration(snapshot.generation)) {
                synchronized(applyLock) {
                    if (isCurrentGeneration(snapshot.generation)) {
                        try {
                            applySnapshotLocked(snapshot)
                        } catch (e: Throwable) {
                            logWarn("dynamic_shortcuts_sync_failed error=${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun applySnapshotLocked(snapshot: Snapshot) {
        var justPurged = false
        if (appliedPurgeGeneration < snapshot.requestedPurgeGeneration) {
            purgeAllShortcutsLocked(snapshot.requestedPurgeReason ?: snapshot.clearReason ?: "clear")
            appliedPurgeGeneration = snapshot.requestedPurgeGeneration
            lastAppliedSignature = null
            justPurged = true
            if (snapshot.clearAll) return
        } else if (snapshot.clearAll) {
            purgeAllShortcutsLocked(snapshot.clearReason ?: "clear")
            lastAppliedSignature = null
            return
        }

        if (snapshot.pinned.isEmpty() && snapshot.share.isEmpty()) {
            if (snapshot.shareSuggestionsPublished) {
                if (!justPurged) {
                    purgeAllShortcutsLocked("empty")
                }
                lastAppliedSignature = null
            } else {
                // Before the first authoritative publish() or clear() in this process (e.g. cold
                // start or offline launch), clearing launcher pinned items must NOT wipe Direct
                // Share shortcuts persisted from the previous session.
                val staleNonShareIds =
                    runCatching { backend.existingDynamicAndCachedShortcutIds() }
                        .getOrDefault(emptyList())
                        .filterNot(::isRaftShareShortcutId)
                if (staleNonShareIds.isNotEmpty()) {
                    backend.removeDynamicShortcuts(staleNonShareIds)
                    backend.removeLongLivedShortcuts(staleNonShareIds)
                }
                lastAppliedSignature = buildSignature(snapshot)
                logDebug("dynamic_shortcuts_pinned_cleared_preserving_share removedNonShare=${staleNonShareIds.size}")
            }
            return
        }

        val signature = buildSignature(snapshot)
        if (signature == lastAppliedSignature) return

        val pinnedInfos = snapshot.pinned.mapIndexedNotNull { index, item ->
            buildPinnedShortcutInfo(item, rank = index)
        }
        val shareInfos = snapshot.share.mapIndexedNotNull { index, suggestion ->
            buildShareShortcutInfo(suggestion, rank = SHARE_RANK_BASE + index)
        }

        // Re-check generation after icon rendering so a concurrent clear("logout") or newer update
        // is never overwritten by a stale worker batch.
        if (!isCurrentGeneration(snapshot.generation)) return

        if (pinnedInfos.isEmpty() && shareInfos.isEmpty()) {
            if (snapshot.shareSuggestionsPublished) {
                if (!justPurged) {
                    purgeAllShortcutsLocked("no_valid_items")
                }
                lastAppliedSignature = null
            }
            return
        }

        val existingIds =
            runCatching { backend.existingDynamicAndCachedShortcutIds() }
                .getOrDefault(emptyList())

        if (backend.sdkInt >= Build.VERSION_CODES.R) {
            val desiredIds = (pinnedInfos.map { it.id } + shareInfos.map { it.id }).toSet()
            val staleIds =
                existingIds.filter { id ->
                    id !in desiredIds &&
                        (snapshot.shareSuggestionsPublished || !isRaftShareShortcutId(id))
                }
            if (staleIds.isNotEmpty()) {
                backend.removeDynamicShortcuts(staleIds)
                backend.removeLongLivedShortcuts(staleIds)
            }
            // Android 11+ (API 30+): push long-lived share targets first so they stay cached for
            // Direct Share even after launcher-visible pinned shortcuts take the top dynamic slots.
            shareInfos.asReversed().forEach { info ->
                if (!isCurrentGeneration(snapshot.generation)) return
                backend.pushDynamicShortcut(info)
            }
            pinnedInfos.asReversed().forEach { info ->
                if (!isCurrentGeneration(snapshot.generation)) return
                backend.pushDynamicShortcut(info)
            }
            lastAppliedSignature = signature
            logDebug(
                "dynamic_shortcuts_synced pinned=${pinnedInfos.size} share=${shareInfos.size} " +
                    "sharePublished=${snapshot.shareSuggestionsPublished} staleRemoved=${staleIds.size} sdk=${backend.sdkInt}"
            )
        } else {
            // Android 10 (API 29): no FLAG_MATCH_CACHED pool; share dynamic slots cleanly between
            // pinned launcher shortcuts and Direct Share targets within the activity's max count.
            val maxSlots = backend.maxShortcutCountPerActivity()
            if (snapshot.shareSuggestionsPublished) {
                val combined = combineLegacyDynamicShortcuts(pinnedInfos, shareInfos, maxSlots)
                val desiredIds = combined.map { it.id }.toSet()
                val staleIds = existingIds.filter { it !in desiredIds }
                if (staleIds.isNotEmpty()) {
                    backend.removeDynamicShortcuts(staleIds)
                    backend.removeLongLivedShortcuts(staleIds)
                }
                if (!isCurrentGeneration(snapshot.generation)) return
                backend.setDynamicShortcuts(combined)
                lastAppliedSignature = signature
                logDebug(
                    "dynamic_shortcuts_synced pinned=${pinnedInfos.size} share=${shareInfos.size} " +
                        "sharePublished=true staleRemoved=${staleIds.size} sdk=${backend.sdkInt}"
                )
            } else {
                val existingShareCount = existingIds.count(::isRaftShareShortcutId)
                if (existingShareCount == 0) {
                    val appliedPinned = pinnedInfos.take(maxSlots)
                    val desiredIds = appliedPinned.map { it.id }.toSet()
                    val staleNonShareIds =
                        existingIds.filter { id -> id !in desiredIds && !isRaftShareShortcutId(id) }
                    if (staleNonShareIds.isNotEmpty()) {
                        backend.removeDynamicShortcuts(staleNonShareIds)
                        backend.removeLongLivedShortcuts(staleNonShareIds)
                    }
                    if (!isCurrentGeneration(snapshot.generation)) return
                    backend.setDynamicShortcuts(appliedPinned)
                    lastAppliedSignature = signature
                    logDebug(
                        "dynamic_shortcuts_synced pinned=${appliedPinned.size} share=0 " +
                            "sharePublished=false staleRemoved=${staleNonShareIds.size} sdk=${backend.sdkInt}"
                    )
                } else {
                    // On API 29, reconstructing persisted ShortcutInfoCompat via getShortcuts()
                    // drops icons. Preserve persisted share shortcuts in place by removing stale
                    // non-share IDs and pushing only the budgeted pinned shortcuts.
                    val minShareSlots = if (maxSlots >= 4) minOf(2, existingShareCount) else 1
                    val pinnedBudget = (maxSlots - minShareSlots).coerceAtLeast(0).coerceAtMost(pinnedInfos.size)
                    val appliedPinned = pinnedInfos.take(pinnedBudget)
                    val desiredPinnedIds = appliedPinned.map { it.id }.toSet()
                    val staleNonShareIds =
                        existingIds.filter { id -> id !in desiredPinnedIds && !isRaftShareShortcutId(id) }
                    if (staleNonShareIds.isNotEmpty()) {
                        backend.removeDynamicShortcuts(staleNonShareIds)
                        backend.removeLongLivedShortcuts(staleNonShareIds)
                    }
                    appliedPinned.asReversed().forEach { info ->
                        if (!isCurrentGeneration(snapshot.generation)) return
                        backend.pushDynamicShortcut(info)
                    }
                    lastAppliedSignature = signature
                    logDebug(
                        "dynamic_shortcuts_synced pinned=${appliedPinned.size} preservedShare=$existingShareCount " +
                            "sharePublished=false staleRemoved=${staleNonShareIds.size} sdk=${backend.sdkInt}"
                    )
                }
            }
        }
    }

    private fun purgeAllShortcutsLocked(reason: String) {
        val existingIds =
            runCatching { backend.existingDynamicAndCachedShortcutIds() }
                .getOrDefault(emptyList())
        backend.removeAllDynamicShortcuts()
        if (existingIds.isNotEmpty()) {
            backend.removeLongLivedShortcuts(existingIds)
        }
        logDebug("dynamic_shortcuts_cleared reason=$reason removedIds=${existingIds.size}")
    }

    private fun buildPinnedShortcutInfo(
        item: RaftDesktopShortcutItem,
        rank: Int
    ): ShortcutInfoCompat? {
        val targetId = item.targetId.trim().takeIf { it.isNotBlank() } ?: return null
        val serverId = item.serverId.trim().takeIf { it.isNotBlank() } ?: return null
        val title = item.title.trim().takeIf { it.isNotBlank() } ?: return null
        val id = item.id.trim().takeIf { it.isNotBlank() } ?: return null
        val launchIntent = buildOpenConversationIntent(serverId, targetId, item.targetKind)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(title)
            .setLongLabel(title)
            .setRank(rank)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(launchIntent)
            .build()
    }

    private fun buildShareShortcutInfo(
        suggestion: RaftShareSuggestion,
        rank: Int
    ): ShortcutInfoCompat? {
        val decoded = RaftShareSuggestionContract.decodeTargetHint(suggestion.shortcutId) ?: return null
        val cleanServerId = suggestion.serverId.trim()
        val cleanChannelId = suggestion.channelId.trim()
        if (
            decoded.serverId != cleanServerId ||
            decoded.channelId != cleanChannelId ||
            decoded.kind != suggestion.kind
        ) {
            return null
        }
        val id =
            RaftShareSuggestionContract.encodeTargetHint(decoded.serverId, decoded.kind, decoded.channelId)
                ?: return null
        val title = suggestion.title.trim().takeIf { it.isNotBlank() } ?: return null
        val longLabel =
            suggestion.subtitle?.trim()?.takeIf { it.isNotBlank() }?.let { serverName ->
                "$title · $serverName"
            } ?: title
        val targetKind =
            if (suggestion.kind == RaftShareSuggestionKind.DirectMessage) {
                RaftDesktopShortcutContract.TargetKindDm
            } else {
                RaftDesktopShortcutContract.TargetKindChannel
            }
        val icon = iconFactory(context, suggestion)
        val person =
            Person.Builder()
                .setName(title)
                .setKey(id)
                .setIcon(icon)
                .build()
        val launchIntent = buildOpenConversationIntent(cleanServerId, cleanChannelId, targetKind)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(title)
            .setLongLabel(longLabel)
            .setRank(rank)
            .setLongLived(true)
            .setCategories(setOf(SHARE_TARGET_CATEGORY))
            .setPerson(person)
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()
    }

    private fun buildOpenConversationIntent(
        serverId: String,
        targetId: String,
        targetKind: String
    ): Intent {
        val launchId = "${serverId}_${targetId}_${launchIdClock()}"
        val pageData =
            JSONObject().apply {
                put(RaftDesktopShortcutContract.KeyServerId, serverId)
                put(RaftDesktopShortcutContract.KeyTargetId, targetId)
                put(RaftDesktopShortcutContract.KeyTargetKind, targetKind)
                put(RaftDesktopShortcutContract.KeyLaunchId, launchId)
            }
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(RaftCmpActivity.KEY_PAGE_NAME, RaftCmpActivity.PAGE_APP_SHELL)
            putExtra(RaftCmpActivity.KEY_PAGE_DATA, pageData.toString())
            putExtra(RaftDesktopShortcutContract.KeyServerId, serverId)
            putExtra(RaftDesktopShortcutContract.KeyTargetId, targetId)
            putExtra(RaftDesktopShortcutContract.KeyTargetKind, targetKind)
            putExtra(RaftDesktopShortcutContract.KeyLaunchId, launchId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    private fun buildSignature(snapshot: Snapshot): String =
        buildString {
            append("p:")
            snapshot.pinned.forEach { item ->
                append(item.id).append('|').append(item.title).append('|').append(item.targetKind).append(';')
            }
            append("#pub:").append(snapshot.shareSuggestionsPublished)
            append("#iv:").append(ICON_STYLE_VERSION)
            append("#s:")
            append(snapshot.shareAccountId.orEmpty()).append('|').append(snapshot.shareServerId.orEmpty()).append(':')
            snapshot.share.forEach { item ->
                append(item.shortcutId)
                    .append('|').append(item.serverName)
                    .append('|').append(item.title)
                    .append('|').append(item.subtitle.orEmpty())
                    .append('|').append(item.avatarWireValue.orEmpty())
                    .append('|').append(item.serverAvatarUrl.orEmpty())
                    .append('|').append(if (item.isPrivate) '1' else '0')
                    .append(';')
            }
        }

    private fun isConsistentShareSuggestion(suggestion: RaftShareSuggestion): Boolean {
        val decoded = RaftShareSuggestionContract.decodeTargetHint(suggestion.shortcutId) ?: return false
        return decoded.serverId == suggestion.serverId.trim() &&
            decoded.channelId == suggestion.channelId.trim() &&
            decoded.kind == suggestion.kind
    }

    private fun isRaftShareShortcutId(id: String): Boolean =
        RaftShareSuggestionContract.decodeTargetHint(id) != null

    private data class Snapshot(
        val generation: Long,
        val pinned: List<RaftDesktopShortcutItem>,
        val shareAccountId: String?,
        val shareServerId: String?,
        val share: List<RaftShareSuggestion>,
        val shareSuggestionsPublished: Boolean,
        val requestedPurgeGeneration: Long,
        val requestedPurgeReason: String?,
        val clearAll: Boolean,
        val clearReason: String?
    )

    companion object {
        internal const val TAG = "SlockDynamicShortcut"
        internal const val MAX_PINNED_SHORTCUTS = 4
        internal const val SHARE_RANK_BASE = 100
        internal const val SHARE_TARGET_CATEGORY = "build.raft.app.category.SHARE_TARGET"
        internal const val ICON_STYLE_VERSION = "iconV2"

        internal fun combineLegacyDynamicShortcuts(
            pinned: List<ShortcutInfoCompat>,
            share: List<ShortcutInfoCompat>,
            maxSlots: Int
        ): List<ShortcutInfoCompat> {
            val safeMax = maxSlots.coerceAtLeast(1)
            if (share.isEmpty()) return pinned.take(safeMax)
            if (pinned.isEmpty()) return share.take(safeMax)
            val minShareSlots = if (safeMax >= 4) minOf(2, share.size) else 1
            val pinnedBudget = (safeMax - minShareSlots).coerceAtLeast(0).coerceAtMost(pinned.size)
            val shareBudget = (safeMax - pinnedBudget).coerceAtLeast(0)
            return pinned.take(pinnedBudget) + share.take(shareBudget)
        }
    }
}

object AndroidDynamicShortcutCoordinator {
    @Volatile
    private var engine: AndroidDynamicShortcutCoordinatorEngine? = null

    fun install(context: Context) {
        val appContext = context.applicationContext
        val resolved =
            engine ?: synchronized(this) {
                engine ?: AndroidDynamicShortcutCoordinatorEngine(
                    context = appContext,
                    backend = SystemAndroidDynamicShortcutBackend(appContext)
                ).also { engine = it }
            }
        RaftShareSuggestionsHostProvider.installAppScopeHost(resolved)
    }

    internal fun updatePinnedShortcuts(context: Context, items: List<RaftDesktopShortcutItem>) {
        val resolved =
            engine ?: run {
                install(context)
                engine
            }
        resolved?.updatePinnedShortcuts(items)
    }

    internal fun clear(reason: String) {
        engine?.clear(reason)
    }

    internal fun resetForTests() {
        synchronized(this) {
            engine = null
        }
        RaftShareSuggestionsHostProvider.clearForTests()
    }
}

internal enum class AndroidShareChannelBadgeKind {
    PublicHash,
    PrivateLock,
    JointUsers
}

internal fun resolveAndroidShareChannelBadgeKind(
    suggestion: RaftShareSuggestion
): AndroidShareChannelBadgeKind =
    when {
        suggestion.isPrivate -> AndroidShareChannelBadgeKind.PrivateLock
        suggestion.kind == RaftShareSuggestionKind.Joint -> AndroidShareChannelBadgeKind.JointUsers
        else -> AndroidShareChannelBadgeKind.PublicHash
    }

internal fun resolveAndroidShareServerTileColor(serverId: String): Int {
    val hash = deterministicFnv1aHash(serverId.trim())
    val index = (hash % SERVER_TILE_PALETTE.size.toULong()).toInt()
    return SERVER_TILE_PALETTE[index]
}

internal fun buildAndroidShareSuggestionIcon(
    context: Context,
    suggestion: RaftShareSuggestion
): IconCompat {
    val bitmap = renderAndroidShareSuggestionAvatarBitmap(context, suggestion)
    return IconCompat.createWithAdaptiveBitmap(bitmap)
}

internal fun resolveAndroidShareSuggestionPixelRaster(
    context: Context,
    suggestion: RaftShareSuggestion
): RaftPixelAvatarRaster? {
    if (suggestion.kind != RaftShareSuggestionKind.DirectMessage) return null
    return resolveAndroidPixelAvatarRasterByWire(context, suggestion.avatarWireValue)
}

private fun resolveAndroidPixelAvatarRasterByWire(
    context: Context,
    wireValue: String?
): RaftPixelAvatarRaster? {
    val wire = wireValue?.trim().orEmpty()
    if (!wire.startsWith("pixel:") && !wire.startsWith("random:") && !wire.startsWith("pixel-human:")) {
        return null
    }
    // Isolate persisted runtime-table loading so bundled pixel art still renders if MMKV
    // is unavailable (e.g. JVM Robolectric or storage error).
    runCatching { raftApplyPersistedPixelAvatars(androidMMKVLocalPreferenceKeyValueStore(context)) }
    return runCatching { raftPixelAvatarRaster(wire.toSharedPixelAvatarWireValue()) }.getOrNull()
}

internal fun renderAdaptivePixelAvatarArgb(
    raster: RaftPixelAvatarRaster,
    sizePx: Int = 108
): IntArray {
    val pixels = IntArray(sizePx * sizePx) { raster.backgroundArgb }
    // Adaptive icons mask the outer 18% on each side; keep pixel art inside the 66% safe zone.
    val inset = (sizePx * 0.17f).toInt()
    val innerSize = (sizePx - inset * 2).coerceAtLeast(1)
    val rawSize = raster.widgetPixelSize()
    val rawPixels = raster.widgetPixelArgb(rawSize)
    for (y in 0 until innerSize) {
        val srcY = ((y * rawSize) / innerSize).coerceIn(0, rawSize - 1)
        val dstRow = (inset + y) * sizePx + inset
        val srcRow = srcY * rawSize
        for (x in 0 until innerSize) {
            val srcX = ((x * rawSize) / innerSize).coerceIn(0, rawSize - 1)
            pixels[dstRow + x] = rawPixels[srcRow + srcX]
        }
    }
    return pixels
}

internal fun renderAndroidShareSuggestionAvatarBitmap(
    context: Context,
    suggestion: RaftShareSuggestion,
    sizePx: Int = 108,
    cachedServerAvatarBytesLoader: (String) -> ByteArray? = ::raftReadCachedAvatarImageBytesOrNull
): Bitmap {
    if (suggestion.kind == RaftShareSuggestionKind.DirectMessage) {
        resolveAndroidShareSuggestionPixelRaster(context, suggestion)?.let { raster ->
            return renderAdaptivePixelAvatarBitmap(raster, sizePx)
        }
        val glyph = suggestion.avatarLabel.trim().take(2).ifBlank { "?" }
        val paletteIndex = (suggestion.shortcutId.hashCode() and Int.MAX_VALUE) % DM_TILE_PALETTE.size
        return renderAdaptiveGlyphBitmap(
            glyph = glyph,
            backgroundColor = DM_TILE_PALETTE[paletteIndex],
            textColor = Color.WHITE,
            sizePx = sizePx
        )
    }

    return renderAdaptiveChannelServerBadgeBitmap(
        context = context,
        suggestion = suggestion,
        sizePx = sizePx,
        cachedServerAvatarBytesLoader = cachedServerAvatarBytesLoader
    )
}

private fun renderAdaptiveChannelServerBadgeBitmap(
    context: Context,
    suggestion: RaftShareSuggestion,
    sizePx: Int,
    cachedServerAvatarBytesLoader: (String) -> ByteArray?
): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val size = sizePx.toFloat()
    val serverColor = resolveAndroidShareServerTileColor(suggestion.serverId)
    canvas.drawColor(serverColor)

    var drewServerVisual = false
    val rawServerAvatar = suggestion.serverAvatarUrl?.trim()?.takeIf { it.isNotEmpty() }
    if (rawServerAvatar != null) {
        val pixelRaster = resolveAndroidPixelAvatarRasterByWire(context, rawServerAvatar)
        if (pixelRaster != null) {
            val pixelBitmap = renderAdaptivePixelAvatarBitmap(pixelRaster, sizePx)
            try {
                canvas.drawBitmap(pixelBitmap, 0f, 0f, null)
                drewServerVisual = true
            } finally {
                pixelBitmap.recycle()
            }
        } else {
            val cachedBytes = runCatching { cachedServerAvatarBytesLoader(rawServerAvatar) }.getOrNull()
            val decoded = cachedBytes?.let { decodeBoundedAndroidServerAvatarBitmap(it, targetSizePx = sizePx) }
            if (decoded != null) {
                try {
                    val cropSize = minOf(decoded.width, decoded.height)
                    val left = (decoded.width - cropSize) / 2
                    val top = (decoded.height - cropSize) / 2
                    val srcRect = Rect(left, top, left + cropSize, top + cropSize)
                    val dstRect = Rect(0, 0, sizePx, sizePx)
                    val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(decoded, srcRect, dstRect, bitmapPaint)
                    drewServerVisual = true
                } finally {
                    decoded.recycle()
                }
            }
        }
    }

    if (!drewServerVisual) {
        val monogram = suggestion.serverAvatarMonogram()
        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = size * (if (monogram.length <= 1) 0.30f else 0.23f)
            }
        val metrics = textPaint.fontMetrics
        val centerX = size * 0.515f
        val baseline = (size * 0.515f) - ((metrics.ascent + metrics.descent) / 2f)
        canvas.drawText(monogram, centerX, baseline, textPaint)
    }

    // Android adaptive bitmaps mask the outer ~18% on each edge (visible circle radius = 0.333 * size).
    // Centering the badge at (0.35 * size, 0.35 * size) with radius 0.10 * size (halo 0.112 * size)
    // keeps its outermost rim at sqrt(0.15^2 + 0.15^2) + 0.112 = 0.324 * size < 0.333 * size
    // (never clipped) while matching iOS's 30% badge-to-visible-circle proportion.
    drawAndroidChannelSemanticBadge(
        canvas = canvas,
        kind = resolveAndroidShareChannelBadgeKind(suggestion),
        centerX = size * 0.35f,
        centerY = size * 0.35f,
        badgeRadius = size * 0.10f,
        haloRadius = size * 0.112f
    )

    return bitmap
}

private fun decodeBoundedAndroidServerAvatarBitmap(
    bytes: ByteArray,
    targetSizePx: Int
): Bitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val outWidth = bounds.outWidth
        val outHeight = bounds.outHeight
        if (outWidth <= 0 || outHeight <= 0) return null
        val target = targetSizePx.coerceAtLeast(64)
        var sampleSize = 1
        val halfWidth = outWidth / 2
        val halfHeight = outHeight / 2
        while (halfWidth / sampleSize >= target && halfHeight / sampleSize >= target) {
            sampleSize *= 2
        }
        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)?.takeIf {
            it.width > 0 && it.height > 0
        }
    }.getOrNull()
}

private fun drawAndroidChannelSemanticBadge(
    canvas: Canvas,
    kind: AndroidShareChannelBadgeKind,
    centerX: Float,
    centerY: Float,
    badgeRadius: Float,
    haloRadius: Float
) {
    val haloPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = CHANNEL_BADGE_HALO_COLOR
        }
    val pillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = CHANNEL_BADGE_PILL_FILL
        }
    val inkFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = CHANNEL_BADGE_ICON_INK
        }

    canvas.drawCircle(centerX, centerY, haloRadius, haloPaint)
    canvas.drawCircle(centerX, centerY, badgeRadius, pillPaint)

    canvas.save()
    canvas.translate(centerX, centerY)
    val r = badgeRadius
    when (kind) {
        AndroidShareChannelBadgeKind.PublicHash -> {
            val slant = 0.08f * r
            val strokePaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = CHANNEL_BADGE_ICON_INK
                    strokeWidth = maxOf(1.2f, 0.135f * r)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
            canvas.drawLine(-0.18f * r + slant, -0.44f * r, -0.18f * r - slant, 0.44f * r, strokePaint)
            canvas.drawLine(0.18f * r + slant, -0.44f * r, 0.18f * r - slant, 0.44f * r, strokePaint)
            canvas.drawLine(-0.42f * r, -0.17f * r, 0.46f * r, -0.17f * r, strokePaint)
            canvas.drawLine(-0.46f * r, 0.17f * r, 0.42f * r, 0.17f * r, strokePaint)
        }

        AndroidShareChannelBadgeKind.PrivateLock -> {
            val shackleR = 0.23f * r
            val shackleArchY = -0.24f * r
            val kappa = 0.55228475f * shackleR
            val shacklePath =
                Path().apply {
                    moveTo(-shackleR, -0.02f * r)
                    lineTo(-shackleR, shackleArchY)
                    cubicTo(
                        -shackleR,
                        shackleArchY - kappa,
                        -kappa,
                        shackleArchY - shackleR,
                        0f,
                        shackleArchY - shackleR
                    )
                    cubicTo(
                        kappa,
                        shackleArchY - shackleR,
                        shackleR,
                        shackleArchY - kappa,
                        shackleR,
                        shackleArchY
                    )
                    lineTo(shackleR, -0.02f * r)
                }
            val shacklePaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = CHANNEL_BADGE_ICON_INK
                    strokeWidth = maxOf(1.2f, 0.15f * r)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
            canvas.drawPath(shacklePath, shacklePaint)

            val bodyRect = RectF(-0.37f * r, -0.06f * r, 0.37f * r, 0.46f * r)
            val corner = 0.12f * r
            canvas.drawRoundRect(bodyRect, corner, corner, inkFillPaint)
            canvas.drawCircle(0f, 0.20f * r, 0.075f * r, pillPaint)
        }

        AndroidShareChannelBadgeKind.JointUsers -> {
            // Secondary right-back person silhouette.
            canvas.drawCircle(0.25f * r, -0.18f * r, 0.135f * r, inkFillPaint)
            val secondaryTorso = buildAndroidTorsoArchPath(0.06f * r, 0.50f * r, 0.06f * r, 0.36f * r)
            canvas.drawPath(secondaryTorso, inkFillPaint)

            // Subtle pill-colored cutout around primary person so both silhouettes separate crisply.
            val primaryTorso = buildAndroidTorsoArchPath(-0.49f * r, 0.19f * r, 0.04f * r, 0.40f * r)
            canvas.drawCircle(-0.15f * r, -0.22f * r, (0.175f + 0.055f) * r, pillPaint)
            val cutoutStrokePaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = CHANNEL_BADGE_PILL_FILL
                    strokeWidth = maxOf(1.0f, 0.10f * r)
                    strokeJoin = Paint.Join.ROUND
                }
            canvas.drawPath(primaryTorso, cutoutStrokePaint)

            // Primary left-front person silhouette.
            canvas.drawCircle(-0.15f * r, -0.22f * r, 0.175f * r, inkFillPaint)
            canvas.drawPath(primaryTorso, inkFillPaint)
        }
    }
    canvas.restore()
}

private fun buildAndroidTorsoArchPath(
    left: Float,
    right: Float,
    top: Float,
    bottom: Float
): Path {
    val midX = (left + right) * 0.5f
    val halfW = (right - left) * 0.5f
    return Path().apply {
        moveTo(left, bottom)
        cubicTo(left, top, midX - halfW * 0.45f, top, midX, top)
        cubicTo(midX + halfW * 0.45f, top, right, top, right, bottom)
        close()
    }
}

private fun renderAdaptivePixelAvatarBitmap(
    raster: RaftPixelAvatarRaster,
    sizePx: Int
): Bitmap = Bitmap.createBitmap(renderAdaptivePixelAvatarArgb(raster, sizePx), sizePx, sizePx, Bitmap.Config.ARGB_8888)

private fun renderAdaptiveGlyphBitmap(
    glyph: String,
    backgroundColor: Int,
    textColor: Int,
    sizePx: Int
): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(backgroundColor)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * (if (glyph.length <= 1) 0.40f else 0.30f)
        }
    val metrics = paint.fontMetrics
    val baseline = (sizePx / 2f) - ((metrics.ascent + metrics.descent) / 2f)
    canvas.drawText(glyph, sizePx / 2f, baseline, paint)
    return bitmap
}

private fun deterministicFnv1aHash(value: String): ULong {
    var hash = 0xcbf29ce484222325uL
    for (byte in value.encodeToByteArray()) {
        hash = hash xor (byte.toUInt() and 0xFFu).toULong()
        hash *= 0x100000001b3uL
    }
    return hash
}

internal val SERVER_TILE_PALETTE =
    intArrayOf(
        Color.rgb(0x3A, 0x3A, 0x3E), // charcoal
        Color.rgb(0x3D, 0x4C, 0x63), // deep slate-blue
        Color.rgb(0x36, 0x4D, 0x45), // deep forest
        Color.rgb(0x4C, 0x3F, 0x59), // deep plum
        Color.rgb(0x52, 0x45, 0x3A) // deep bronze
    )
internal val CHANNEL_BADGE_PILL_FILL = Color.rgb(0xF3, 0xF1, 0xEB)
internal val CHANNEL_BADGE_ICON_INK = Color.rgb(0x26, 0x24, 0x20)
private val CHANNEL_BADGE_HALO_COLOR = Color.argb(0xCC, 0x18, 0x18, 0x1B)
private val DM_TILE_PALETTE =
    intArrayOf(
        Color.rgb(0xE8, 0x4C, 0x88),
        Color.rgb(0x3B, 0x82, 0xF6),
        Color.rgb(0x10, 0xB9, 0x81),
        Color.rgb(0x8B, 0x5C, 0xF6),
        Color.rgb(0xF5, 0x9E, 0x0B),
        Color.rgb(0x06, 0xB6, 0xD4)
    )
