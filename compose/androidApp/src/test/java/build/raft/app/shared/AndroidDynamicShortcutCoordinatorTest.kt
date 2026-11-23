package build.raft.app.shared

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import build.raft.app.R
import build.raft.app.data.RaftDesktopShortcutContract
import build.raft.app.data.RaftDesktopShortcutItem
import build.raft.app.data.RaftShareSuggestion
import build.raft.app.data.RaftShareSuggestionContract
import build.raft.app.data.RaftShareSuggestionKind
import build.raft.app.raftPixelAvatarRaster
import build.raft.app.toAndroidExternalShareSpec
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidDynamicShortcutCoordinatorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun manifestAndShortcutsXmlDeclareShareTargetOnMainActivityWithoutFixedPackage() {
        val manifest = parseXml("src/main/AndroidManifest.xml")
        val activities = manifest.getElementsByTagName("activity")
        var mainActivity: Element? = null
        for (i in 0 until activities.length) {
            val el = activities.item(i) as Element
            if (el.getAttribute("android:name") == ".MainActivity") {
                mainActivity = el
                break
            }
        }
        assertNotNull("MainActivity missing from AndroidManifest.xml", mainActivity)
        val metaDataNodes = mainActivity!!.getElementsByTagName("meta-data")
        var hasShortcutsMeta = false
        for (i in 0 until metaDataNodes.length) {
            val meta = metaDataNodes.item(i) as Element
            if (
                meta.getAttribute("android:name") == "android.app.shortcuts" &&
                meta.getAttribute("android:resource") == "@xml/shortcuts"
            ) {
                hasShortcutsMeta = true
            }
        }
        assertTrue("MainActivity must declare android.app.shortcuts -> @xml/shortcuts", hasShortcutsMeta)

        val shortcutsXml = parseXml("src/main/res/xml/shortcuts.xml")
        val shareTargets = shortcutsXml.getElementsByTagName("share-target")
        assertEquals(1, shareTargets.length)
        val shareTarget = shareTargets.item(0) as Element
        assertEquals("build.raft.app.MainActivity", shareTarget.getAttribute("android:targetClass"))
        assertFalse(
            "share-target must omit targetPackage so alpha/play/release flavors resolve their own package",
            shareTarget.hasAttribute("android:targetPackage")
        )
        val dataNodes = shareTarget.getElementsByTagName("data")
        assertEquals(1, dataNodes.length)
        assertEquals("*/*", (dataNodes.item(0) as Element).getAttribute("android:mimeType"))
        val categories = shareTarget.getElementsByTagName("category")
        assertEquals(1, categories.length)
        assertEquals(
            AndroidDynamicShortcutCoordinatorEngine.SHARE_TARGET_CATEGORY,
            (categories.item(0) as Element).getAttribute("android:name")
        )
    }

    @Test
    fun coldStartPinnedSyncBeforeFirstPublishPreservesPersistedDirectShareTargets() {
        val persistedShareId = "raft_share:v1:srv-1:dm:dm-persisted"
        val persistedShareInfo =
            ShortcutInfoCompat.Builder(context, persistedShareId)
                .setShortLabel("Persisted Peer")
                .setIntent(Intent(Intent.ACTION_VIEW))
                .build()
        val persistedPinInfo =
            ShortcutInfoCompat.Builder(context, "shortcut_srv-1_old-pin")
                .setShortLabel("#old-pin")
                .setIntent(Intent(Intent.ACTION_VIEW))
                .build()

        // API 34: updatePinnedShortcuts(emptyList()) and non-empty pinned sync before publish() preserve share targets.
        val backend34 = FakeShortcutBackend(sdkInt = 34, maxSlots = 4).apply {
            shortcutsById[persistedShareId] = persistedShareInfo
            shortcutsById["shortcut_srv-1_old-pin"] = persistedPinInfo
        }
        val engine34 = createEngine(backend34)

        engine34.updatePinnedShortcuts(emptyList())
        assertFalse(backend34.removeAllDynamicCalled)
        assertTrue(backend34.shortcutsById.containsKey(persistedShareId))
        assertFalse(backend34.shortcutsById.containsKey("shortcut_srv-1_old-pin"))

        backend34.clearBatchLog()
        engine34.updatePinnedShortcuts(listOf(pinnedItem("srv-1", "ch-new-pin", "#new-pin")))
        assertTrue(backend34.shortcutsById.containsKey(persistedShareId))
        assertTrue(backend34.shortcutsById.containsKey("shortcut_srv-1_ch-new-pin"))
        assertFalse(backend34.removedLongLivedIds.contains(persistedShareId))

        // API 29: pinned sync before publish() preserves persisted dynamic share shortcuts in place
        // without calling setDynamicShortcuts() (which would lose persisted share icons).
        val backend29 = FakeShortcutBackend(sdkInt = 29, maxSlots = 4).apply {
            shortcutsById[persistedShareId] = persistedShareInfo
            shortcutsById["shortcut_srv-1_old-pin"] = persistedPinInfo
        }
        val engine29 = createEngine(backend29)
        engine29.updatePinnedShortcuts(listOf(pinnedItem("srv-1", "ch-pin-1", "#pin-1")))
        assertTrue(backend29.lastSetDynamic.isEmpty())
        assertTrue(backend29.shortcutsById.containsKey(persistedShareId))
        assertTrue(backend29.shortcutsById.containsKey("shortcut_srv-1_ch-pin-1"))
        assertFalse(backend29.shortcutsById.containsKey("shortcut_srv-1_old-pin"))
        assertEquals(listOf("shortcut_srv-1_ch-pin-1"), backend29.lastPushBatch.map { it.id })
    }

    @Test
    fun api30PlusCoexistsPinnedAndDirectShareTargetsAndPurgesCachedIdsOnClear() {
        val backend = FakeShortcutBackend(sdkInt = 34, maxSlots = 4)
        val engine = createEngine(backend)

        val pinned =
            listOf(
                pinnedItem("srv-1", "ch-pin-1", "#pinned-one"),
                pinnedItem("srv-1", "ch-pin-2", "#pinned-two")
            )
        val shares =
            listOf(
                shareSuggestion("srv-1", RaftShareSuggestionKind.DirectMessage, "dm-1", "AirenaAgent"),
                shareSuggestion("srv-1", RaftShareSuggestionKind.Channel, "ch-gen", "#general"),
                shareSuggestion("srv-1", RaftShareSuggestionKind.Joint, "ch-joint", "#partner")
            )

        engine.updatePinnedShortcuts(pinned)
        engine.publish("acc-1", "srv-1", shares)

        // Share targets are pushed first (reversed), then pinned shortcuts (reversed) so pinned sit on top.
        assertEquals(
            listOf(
                "raft_share:v1:srv-1:joint:ch-joint",
                "raft_share:v1:srv-1:channel:ch-gen",
                "raft_share:v1:srv-1:dm:dm-1",
                "shortcut_srv-1_ch-pin-2",
                "shortcut_srv-1_ch-pin-1"
            ),
            backend.lastPushBatch.map { it.id }
        )
        val pushedShare = backend.shortcutsById.getValue("raft_share:v1:srv-1:dm:dm-1")
        assertEquals(setOf(AndroidDynamicShortcutCoordinatorEngine.SHARE_TARGET_CATEGORY), pushedShare.categories)
        assertEquals(100, pushedShare.rank)
        assertEquals("AirenaAgent · Acme", pushedShare.longLabel.toString())

        // Clearing pinned shortcuts (user has 0 pinned channels) MUST NOT wipe Direct Share suggestions.
        backend.clearBatchLog()
        engine.updatePinnedShortcuts(emptyList())
        assertTrue(backend.removedLongLivedIds.contains("shortcut_srv-1_ch-pin-1"))
        assertTrue(backend.removedLongLivedIds.contains("shortcut_srv-1_ch-pin-2"))
        assertFalse(backend.removedLongLivedIds.contains("raft_share:v1:srv-1:dm:dm-1"))
        assertEquals(
            listOf(
                "raft_share:v1:srv-1:joint:ch-joint",
                "raft_share:v1:srv-1:channel:ch-gen",
                "raft_share:v1:srv-1:dm:dm-1"
            ),
            backend.lastPushBatch.map { it.id }
        )

        // Removing one share suggestion removes it from BOTH dynamic and long-lived caches.
        backend.clearBatchLog()
        engine.publish("acc-1", "srv-1", listOf(shares.first()))
        assertTrue(backend.removedDynamicIds.contains("raft_share:v1:srv-1:channel:ch-gen"))
        assertTrue(backend.removedLongLivedIds.contains("raft_share:v1:srv-1:channel:ch-gen"))
        assertTrue(backend.removedLongLivedIds.contains("raft_share:v1:srv-1:joint:ch-joint"))

        // Logout clear() purges BOTH dynamic and cached long-lived shortcuts.
        backend.clearBatchLog()
        engine.clear("logout")
        assertTrue(backend.removeAllDynamicCalled)
        assertTrue(backend.removedLongLivedIds.contains("raft_share:v1:srv-1:dm:dm-1"))
        assertTrue(backend.shortcutsById.isEmpty())
    }

    @Test
    fun queuedStalePublishIsDroppedAfterClearLogout() {
        val backend = FakeShortcutBackend(sdkInt = 34, maxSlots = 4)
        val queuedTasks = mutableListOf<() -> Unit>()
        val engine =
            AndroidDynamicShortcutCoordinatorEngine(
                context = context,
                backend = backend,
                executor = { _, task -> queuedTasks += task },
                launchIdClock = { 12345L },
                iconFactory = { ctx, _ -> IconCompat.createWithResource(ctx, R.mipmap.ic_launcher) },
                logDebug = {},
                logWarn = {}
            )

        engine.publish(
            "acc-1",
            "srv-1",
            listOf(shareSuggestion("srv-1", RaftShareSuggestionKind.DirectMessage, "dm-1", "AirenaAgent"))
        )
        engine.clear("logout")

        // Drain queued tasks in FIFO order: stale publish must be dropped, clear executes cleanly.
        queuedTasks.forEach { it() }
        assertTrue(backend.removeAllDynamicCalled)
        assertTrue(backend.lastPushBatch.isEmpty())
        assertTrue(backend.shortcutsById.isEmpty())
    }

    @Test
    fun logoutClearFollowedImmediatelyByEmptyPinnedSyncStillPurgesDirectShareTargets() {
        val backend = FakeShortcutBackend(sdkInt = 34, maxSlots = 4)
        val queuedTasks = mutableListOf<() -> Unit>()
        val engine =
            AndroidDynamicShortcutCoordinatorEngine(
                context = context,
                backend = backend,
                executor = { _, task -> queuedTasks += task },
                launchIdClock = { 12345L },
                iconFactory = { ctx, _ -> IconCompat.createWithResource(ctx, R.mipmap.ic_launcher) },
                logDebug = {},
                logWarn = {}
            )

        // First publish and drain so persisted Direct Share + pinned shortcuts exist on the OS.
        engine.updatePinnedShortcuts(listOf(pinnedItem("srv-1", "ch-pin-1", "#pin-1")))
        engine.publish(
            "acc-1",
            "srv-1",
            listOf(shareSuggestion("srv-1", RaftShareSuggestionKind.DirectMessage, "dm-1", "AirenaAgent"))
        )
        queuedTasks.forEach { it() }
        queuedTasks.clear()
        assertTrue(backend.shortcutsById.containsKey("raft_share:v1:srv-1:dm:dm-1"))

        // Logout triggers clear("logout"), immediately followed by SettingsHomeShellEffects
        // syncing updatePinnedShortcuts(emptyList()) on the main thread before worker drains.
        backend.clearBatchLog()
        engine.clear("logout")
        engine.updatePinnedShortcuts(emptyList())
        queuedTasks.forEach { it() }
        queuedTasks.clear()

        assertTrue(backend.removeAllDynamicCalled)
        assertTrue(backend.removedLongLivedIds.contains("raft_share:v1:srv-1:dm:dm-1"))
        assertTrue(backend.shortcutsById.isEmpty())

        // Also verify clear("logout") followed immediately by a new account's publish() purges
        // the old account's shortcuts first before pushing the new account's targets.
        backend.shortcutsById["raft_share:v1:srv-old:dm:dm-old"] =
            ShortcutInfoCompat.Builder(context, "raft_share:v1:srv-old:dm:dm-old")
                .setShortLabel("Old Peer")
                .setIntent(Intent(Intent.ACTION_VIEW))
                .build()
        backend.clearBatchLog()
        engine.clear("logout")
        engine.publish(
            "acc-2",
            "srv-2",
            listOf(shareSuggestion("srv-2", RaftShareSuggestionKind.Channel, "ch-new", "#new-room"))
        )
        queuedTasks.forEach { it() }
        assertTrue(backend.removeAllDynamicCalled)
        assertTrue(backend.removedLongLivedIds.contains("raft_share:v1:srv-old:dm:dm-old"))
        assertFalse(backend.shortcutsById.containsKey("raft_share:v1:srv-old:dm:dm-old"))
        assertTrue(backend.shortcutsById.containsKey("raft_share:v1:srv-2:channel:ch-new"))
    }

    @Test
    fun api29LegacyBudgetsDynamicSlotsBetweenPinnedAndDirectShare() {
        val backend = FakeShortcutBackend(sdkInt = 29, maxSlots = 4)
        val engine = createEngine(backend)

        val pinned = (1..4).map { pinnedItem("srv-1", "ch-$it", "#pin-$it") }
        val shares = (1..4).map { shareSuggestion("srv-1", RaftShareSuggestionKind.DirectMessage, "dm-$it", "User $it") }

        engine.updatePinnedShortcuts(pinned)
        engine.publish("acc-1", "srv-1", shares)

        assertEquals(
            listOf(
                "shortcut_srv-1_ch-1",
                "shortcut_srv-1_ch-2",
                "raft_share:v1:srv-1:dm:dm-1",
                "raft_share:v1:srv-1:dm:dm-2"
            ),
            backend.lastSetDynamic.map { it.id }
        )
    }

    @Test
    fun sendAndSendMultipleIntentsExtractCanonicalDirectShareShortcutIdAndDropNonRaftIds() {
        val validHint = "raft_share:v1:srv-1:dm:dm-agent-1"
        val validIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "hello")
                putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, validHint)
            }
        val validSpec = validIntent.toAndroidExternalShareSpec()
        assertNotNull(validSpec)
        assertEquals(validHint, validSpec!!.preselectedTargetHint)

        val multiHint = "raft_share:v1:srv-1:channel:ch-design"
        val multiIntent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    arrayListOf(Uri.parse("content://example/1"), Uri.parse("content://example/2"))
                )
                putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, multiHint)
            }
        val multiSpec = multiIntent.toAndroidExternalShareSpec()
        assertNotNull(multiSpec)
        assertEquals(multiHint, multiSpec!!.preselectedTargetHint)
        assertEquals(2, multiSpec.uris.size)

        val foreignIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "hello")
                putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "shortcut_srv-1_ch-1")
            }
        val foreignSpec = foreignIntent.toAndroidExternalShareSpec()
        assertNotNull(foreignSpec)
        assertNull(foreignSpec!!.preselectedTargetHint)
    }

    @Test
    fun shareSuggestionIconRendererRendersBundledPixelAvatarEvenWithoutMmkv() {
        val channelBitmap =
            renderAndroidShareSuggestionAvatarBitmap(
                context,
                shareSuggestion("srv-1", RaftShareSuggestionKind.Channel, "ch-1", "#general")
            )
        assertEquals(108, channelBitmap.width)
        assertEquals(108, channelBitmap.height)
        channelBitmap.recycle()

        val dmBitmap =
            renderAndroidShareSuggestionAvatarBitmap(
                context,
                shareSuggestion("srv-1", RaftShareSuggestionKind.DirectMessage, "dm-1", "Alice")
            )
        assertEquals(108, dmBitmap.width)
        assertEquals(108, dmBitmap.height)
        dmBitmap.recycle()

        val expectedRaster = requireNotNull(raftPixelAvatarRaster("pixel:cat"))
        val pixelBitmap =
            renderAndroidShareSuggestionAvatarBitmap(
                context,
                shareSuggestion(
                    "srv-1",
                    RaftShareSuggestionKind.DirectMessage,
                    "dm-agent",
                    "AirenaAgent",
                    avatarWireValue = "pixel:cat"
                )
            )
        assertEquals(108, pixelBitmap.width)
        assertEquals(108, pixelBitmap.height)
        // Outer adaptive-icon bleed corner must match the bundled pixel avatar background color.
        assertEquals(expectedRaster.backgroundArgb, pixelBitmap.getPixel(0, 0))
        pixelBitmap.recycle()
    }

    private fun createEngine(backend: FakeShortcutBackend): AndroidDynamicShortcutCoordinatorEngine =
        AndroidDynamicShortcutCoordinatorEngine(
            context = context,
            backend = backend,
            executor = { _, task -> task() },
            launchIdClock = { 12345L },
            iconFactory = { ctx, _ -> IconCompat.createWithResource(ctx, R.mipmap.ic_launcher) },
            logDebug = {},
            logWarn = {}
        )

    private fun pinnedItem(serverId: String, targetId: String, title: String) =
        RaftDesktopShortcutItem(
            id = "shortcut_${serverId}_$targetId",
            serverId = serverId,
            targetId = targetId,
            targetKind = RaftDesktopShortcutContract.TargetKindChannel,
            title = title,
            subtitle = "Acme"
        )

    private fun shareSuggestion(
        serverId: String,
        kind: RaftShareSuggestionKind,
        channelId: String,
        title: String,
        avatarWireValue: String? = null
    ) = RaftShareSuggestion(
        shortcutId = requireNotNull(RaftShareSuggestionContract.encodeTargetHint(serverId, kind, channelId)),
        serverId = serverId,
        serverName = "Acme",
        channelId = channelId,
        kind = kind,
        title = title,
        subtitle = "Acme",
        avatarWireValue = avatarWireValue
    )

    private fun parseXml(relativePath: String): Element {
        val file = locate(relativePath)
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun locate(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            File(current, "androidApp/$relativePath").takeIf { it.isFile }?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }

    private class FakeShortcutBackend(
        override val sdkInt: Int,
        private val maxSlots: Int
    ) : AndroidDynamicShortcutBackend {
        val shortcutsById = linkedMapOf<String, ShortcutInfoCompat>()
        val lastPushBatch = mutableListOf<ShortcutInfoCompat>()
        var lastSetDynamic: List<ShortcutInfoCompat> = emptyList()
        val removedDynamicIds = mutableListOf<String>()
        val removedLongLivedIds = mutableListOf<String>()
        var removeAllDynamicCalled = false

        fun clearBatchLog() {
            lastPushBatch.clear()
            removedDynamicIds.clear()
            removedLongLivedIds.clear()
            removeAllDynamicCalled = false
        }

        override fun maxShortcutCountPerActivity(): Int = maxSlots

        override fun existingDynamicAndCachedShortcutIds(): List<String> = shortcutsById.keys.toList()

        override fun setDynamicShortcuts(shortcuts: List<ShortcutInfoCompat>) {
            lastSetDynamic = shortcuts
            shortcutsById.clear()
            shortcuts.forEach { shortcutsById[it.id] = it }
        }

        override fun pushDynamicShortcut(shortcut: ShortcutInfoCompat) {
            lastPushBatch += shortcut
            shortcutsById[shortcut.id] = shortcut
        }

        override fun removeDynamicShortcuts(shortcutIds: List<String>) {
            removedDynamicIds += shortcutIds
        }

        override fun removeAllDynamicShortcuts() {
            removeAllDynamicCalled = true
        }

        override fun removeLongLivedShortcuts(shortcutIds: List<String>) {
            removedLongLivedIds += shortcutIds
            shortcutIds.forEach { shortcutsById.remove(it) }
        }
    }
}
