package build.raft.app.shared

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
            append("#s:")
            append(snapshot.shareAccountId.orEmpty()).append('|').append(snapshot.shareServerId.orEmpty()).append(':')
            snapshot.share.forEach { item ->
                append(item.shortcutId)
                    .append('|').append(item.title)
                    .append('|').append(item.subtitle.orEmpty())
                    .append('|').append(item.avatarWireValue.orEmpty())
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

internal fun buildAndroidShareSuggestionIcon(
    context: Context,
    suggestion: RaftShareSuggestion
): IconCompat {
    val bitmap = renderAndroidShareSuggestionAvatarBitmap(context, suggestion)
    return IconCompat.createWithAdaptiveBitmap(bitmap)
}

internal fun renderAndroidShareSuggestionAvatarBitmap(
    context: Context,
    suggestion: RaftShareSuggestion,
    sizePx: Int = 108
): Bitmap {
    if (suggestion.kind == RaftShareSuggestionKind.DirectMessage) {
        val wire = suggestion.avatarWireValue?.trim().orEmpty()
        if (wire.startsWith("pixel:") || wire.startsWith("random:") || wire.startsWith("pixel-human:")) {
            // Isolate persisted runtime-table loading so bundled pixel art still renders if MMKV
            // is unavailable (e.g. JVM Robolectric or storage error).
            runCatching { raftApplyPersistedPixelAvatars(androidMMKVLocalPreferenceKeyValueStore(context)) }
            val pixelBitmap =
                runCatching {
                    val raster = raftPixelAvatarRaster(wire.toSharedPixelAvatarWireValue())
                    raster?.let { renderAdaptivePixelAvatarBitmap(it, sizePx) }
                }.getOrNull()
            if (pixelBitmap != null) return pixelBitmap
        }
    }
    val glyph =
        if (suggestion.kind != RaftShareSuggestionKind.DirectMessage) {
            "#"
        } else {
            suggestion.avatarLabel.trim().take(2).ifBlank { "?" }
        }
    val backgroundColor =
        if (suggestion.kind != RaftShareSuggestionKind.DirectMessage) {
            CHANNEL_TILE_BACKGROUND
        } else {
            val paletteIndex = (suggestion.shortcutId.hashCode() and Int.MAX_VALUE) % DM_TILE_PALETTE.size
            DM_TILE_PALETTE[paletteIndex]
        }
    val textColor =
        if (suggestion.kind != RaftShareSuggestionKind.DirectMessage) {
            CHANNEL_TILE_FOREGROUND
        } else {
            Color.WHITE
        }
    return renderAdaptiveGlyphBitmap(
        glyph = glyph,
        backgroundColor = backgroundColor,
        textColor = textColor,
        sizePx = sizePx
    )
}

private fun renderAdaptivePixelAvatarBitmap(
    raster: RaftPixelAvatarRaster,
    sizePx: Int
): Bitmap {
    val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(raster.backgroundArgb)
    val rawSize = raster.widgetPixelSize()
    val rawBitmap = Bitmap.createBitmap(raster.widgetPixelArgb(rawSize), rawSize, rawSize, Bitmap.Config.ARGB_8888)
    try {
        // Adaptive icons mask the outer 18% on each side; keep pixel art inside the 66% safe zone.
        val inset = (sizePx * 0.17f).toInt()
        val dest = Rect(inset, inset, sizePx - inset, sizePx - inset)
        val paint = Paint().apply { isFilterBitmap = false }
        canvas.drawBitmap(rawBitmap, null, dest, paint)
    } finally {
        rawBitmap.recycle()
    }
    return output
}

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

private val CHANNEL_TILE_BACKGROUND = Color.rgb(0xFF, 0xDF, 0x3F)
private val CHANNEL_TILE_FOREGROUND = Color.rgb(0x18, 0x18, 0x1B)
private val DM_TILE_PALETTE =
    intArrayOf(
        Color.rgb(0xE8, 0x4C, 0x88),
        Color.rgb(0x3B, 0x82, 0xF6),
        Color.rgb(0x10, 0xB9, 0x81),
        Color.rgb(0x8B, 0x5C, 0xF6),
        Color.rgb(0xF5, 0x9E, 0x0B),
        Color.rgb(0x06, 0xB6, 0xD4)
    )
