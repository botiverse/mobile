package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.encodeArtifactShortId
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Multi-slot per-artifact keep-alive contract tests (`#wg-mobile-feedback:#335`, Hands e6e0bbdd):
 * - Per-artifact slot keying isolates by scope, canonical Artifact ID + version, and boot-latched
 *   embed parameters (`hostConversation`, `hostHead`, `lang`).
 * - Switching A -> B -> A -> B resolves two distinct warm slots with zero splash flash and
 *   immediate Frame-1 page-bar readiness (`hostToolbar=1`).
 * - Surgical per-slot eviction preserves healthy sibling slots while account/server switch or
 *   logout clears every slot.
 * - Prewarm (`raft-app-warm`) promotes cleanly into the first opened Artifact slot without leaking
 *   or prematurely hiding `AppSplash` before the Artifact actually renders.
 */
class RaftArtifactViewerMultiSlotKeepAliveTest {
    private val web = "https://app.raft.build"
    private val env = "https://api.raft.build/api"
    private val maya = RaftArtifactViewerScope(web, env, "user-maya", "server-northwind")
    private val mayaOtherServer = maya.copy(serverId = "server-other")
    private val someoneElse = maya.copy(accountId = "user-other")
    private val channel = "c0c0c0c0-0000-4000-8000-000000000001"
    private val otherChannel = "c0c0c0c0-0000-4000-8000-000000000002"
    private val artifactA = ArtifactLinkTarget("pt_d65a0269-1111-4000-8000-000000000001")
    private val artifactB = ArtifactLinkTarget("pt_0a8e2b76-2222-4000-8000-000000000002")
    private val token = "access-token-1"

    private fun embeddedUrl(
        target: ArtifactLinkTarget,
        conversationId: String? = channel,
        hostHead: Int? = null,
        langTag: String? = "zh-CN",
        hostToolbar: Boolean = true,
    ): String =
        raftArtifactViewerEmbeddedPageUrl(web, target, conversationId, hostHead, langTag) +
            if (hostToolbar) "&hostToolbar=1" else ""

    @Test
    fun perArtifactKeepAliveKeyIsolatesByScopeArtifactVersionConversationHeadAndLang() {
        val keyA = maya.artifactKeepAliveKey(artifactA, channel, hostHead = null, langTag = "zh-CN")
        val keyB = maya.artifactKeepAliveKey(artifactB, channel, hostHead = null, langTag = "zh-CN")
        assertNotEquals(keyA, keyB)
        assertTrue(keyA.startsWith(maya.artifactKeepAlivePrefix))
        assertTrue(keyB.startsWith(maya.artifactKeepAlivePrefix))

        // Short ID and canonical `pt_<uuid>` produce the exact same keep-alive slot key.
        val shortIdA = assertNotNull(encodeArtifactShortId(artifactA.id.removePrefix("pt_")))
        assertEquals(
            keyA,
            maya.artifactKeepAliveKey(ArtifactLinkTarget(shortIdA), channel, hostHead = null, langTag = "zh-CN"),
        )

        // Different version, conversation, head height, language, server, or account never collide.
        assertNotEquals(keyA, maya.artifactKeepAliveKey(artifactA.copy(version = 2), channel, hostHead = null, langTag = "zh-CN"))
        assertNotEquals(keyA, maya.artifactKeepAliveKey(artifactA, otherChannel, hostHead = null, langTag = "zh-CN"))
        assertNotEquals(keyA, maya.artifactKeepAliveKey(artifactA, null, hostHead = null, langTag = "zh-CN"))
        assertNotEquals(keyA, maya.artifactKeepAliveKey(artifactA, channel, hostHead = 56, langTag = "zh-CN"))
        assertNotEquals(keyA, maya.artifactKeepAliveKey(artifactA, channel, hostHead = null, langTag = "en"))
        assertNotEquals(keyA, mayaOtherServer.artifactKeepAliveKey(artifactA, channel, hostHead = null, langTag = "zh-CN"))
        assertNotEquals(keyA, someoneElse.artifactKeepAliveKey(artifactA, channel, hostHead = null, langTag = "zh-CN"))

        // Legacy single-slot mode continues using the server-wide key.
        assertEquals(maya.keepAliveKey, raftArtifactViewerSlotKey(maya, artifactA, channel, null, "zh-CN", useMultiSlot = false))
        assertEquals(keyA, raftArtifactViewerSlotKey(maya, artifactA, channel, null, "zh-CN", useMultiSlot = true))
    }

    @Test
    fun switchingBetweenArtifactAAndBReusesTwoDistinctWarmSlotsWithZeroSplashFlash() {
        val pool = LinkedHashMap<String, RaftWebViewKeptDocument>()
        val cache = RaftArtifactViewerCache(onKeptSlotDropped = { pool.remove(it) }, onKeptDocumentDropped = { pool.clear() })
        val keyA = maya.artifactKeepAliveKey(artifactA, channel, hostHead = null, langTag = "zh-CN")
        val keyB = maya.artifactKeepAliveKey(artifactB, channel, hostHead = null, langTag = "zh-CN")
        val urlA = embeddedUrl(artifactA, hostToolbar = true)
        val urlB = embeddedUrl(artifactB, hostToolbar = true)

        // Simulate A and B both having rendered and parked in their own slots.
        pool[keyA] = RaftWebViewKeptDocument(src = embeddedUrl(artifactA, hostToolbar = false), currentUrl = urlA, loaded = true)
        cache.rememberKeptDocument(maya, artifactA, urlA, token, slotKey = keyA, renderedArtifact = true)
        cache.noteKeptBoot(maya, channel, null, "zh-CN", slotKey = keyA)

        pool[keyB] = RaftWebViewKeptDocument(src = embeddedUrl(artifactB, hostToolbar = false), currentUrl = urlB, loaded = true)
        cache.rememberKeptDocument(maya, artifactB, urlB, token, slotKey = keyB, renderedArtifact = true)
        cache.noteKeptBoot(maya, channel, null, "zh-CN", slotKey = keyB)

        // Re-opening A: Frame-1 page bar is ready immediately and splash indicator is None.
        assertTrue(
            raftArtifactViewerInitialWarmBarReady(
                scope = maya,
                target = artifactA,
                accessToken = token,
                conversationId = channel,
                hostHead = null,
                langTag = "zh-CN",
                useMultiSlot = true,
                cache = cache,
                readKept = pool::get,
            ),
        )
        val resA =
            resolveRaftArtifactViewerKeptSlot(
                scope = maya,
                target = artifactA,
                accessToken = token,
                conversationId = channel,
                hostHead = null,
                langTag = "zh-CN",
                useMultiSlot = true,
                cache = cache,
                readKept = pool::get,
                promoteKept = { _, _ -> false },
                dropKept = { pool.remove(it) },
            )
        assertEquals(keyA, resA.slotKey)
        assertNotNull(resA.document)
        assertTrue(resA.showsTarget)
        assertTrue(resA.alreadyNavigated)
        assertFalse(resA.promotedPrewarm)
        assertTrue(resA.renderedArtifact)
        assertEquals(
            RaftArtifactViewerLoadingIndicator.None,
            raftArtifactViewerLoadingIndicator(
                RaftArtifactViewerOpenState(
                    sessionRead = true,
                    documentShown = true,
                    reusesKeptPage = true,
                    loadError = false,
                    sinceDocumentReadyMs = 0L,
                    pageDrawsBar = true,
                ),
            ),
        )

        // Re-opening B immediately after A: B's slot was never touched and reuses cleanly with zero splash.
        assertTrue(
            raftArtifactViewerInitialWarmBarReady(
                scope = maya,
                target = artifactB,
                accessToken = token,
                conversationId = channel,
                hostHead = null,
                langTag = "zh-CN",
                useMultiSlot = true,
                cache = cache,
                readKept = pool::get,
            ),
        )
        val resB =
            resolveRaftArtifactViewerKeptSlot(
                scope = maya,
                target = artifactB,
                accessToken = token,
                conversationId = channel,
                hostHead = null,
                langTag = "zh-CN",
                useMultiSlot = true,
                cache = cache,
                readKept = pool::get,
                promoteKept = { _, _ -> false },
                dropKept = { pool.remove(it) },
            )
        assertEquals(keyB, resB.slotKey)
        assertNotNull(resB.document)
        assertTrue(resB.showsTarget)
        assertTrue(resB.alreadyNavigated)
        assertTrue(resB.renderedArtifact)
    }

    @Test
    fun versionMismatchOrInViewerVersionSwitchTriggersNavigationInsteadOfShowingStaleVersion() {
        val pool = LinkedHashMap<String, RaftWebViewKeptDocument>()
        val cache = RaftArtifactViewerCache(onKeptSlotDropped = { pool.remove(it) }, onKeptDocumentDropped = { pool.clear() })
        val keyALatest = maya.artifactKeepAliveKey(artifactA, channel, hostHead = null, langTag = "zh-CN")
        val urlASwitchedToV1 = embeddedUrl(artifactA.copy(version = 1), hostToolbar = true)

        // User opened A (latest), then picked v1 inside the viewer before closing.
        pool[keyALatest] =
            RaftWebViewKeptDocument(
                src = embeddedUrl(artifactA, hostToolbar = false),
                currentUrl = urlASwitchedToV1,
                loaded = true,
            )
        cache.rememberKeptDocument(maya, artifactA.copy(version = 1), urlASwitchedToV1, token, slotKey = keyALatest, renderedArtifact = true)
        cache.noteKeptBoot(maya, channel, null, "zh-CN", slotKey = keyALatest)

        assertFalse(raftArtifactViewerKeptUrlMatchesTarget(urlASwitchedToV1, web, artifactA))
        assertTrue(raftArtifactViewerKeptUrlMatchesTarget(urlASwitchedToV1, web, artifactA.copy(version = 1)))

        // Re-opening A (latest) must NOT falsely claim showsTarget=true for stale v1.
        assertFalse(
            raftArtifactViewerInitialWarmBarReady(
                scope = maya,
                target = artifactA,
                accessToken = token,
                conversationId = channel,
                hostHead = null,
                langTag = "zh-CN",
                useMultiSlot = true,
                cache = cache,
                readKept = pool::get,
            ),
        )
        val resLatest =
            resolveRaftArtifactViewerKeptSlot(
                scope = maya,
                target = artifactA,
                accessToken = token,
                conversationId = channel,
                hostHead = null,
                langTag = "zh-CN",
                useMultiSlot = true,
                cache = cache,
                readKept = pool::get,
                promoteKept = { _, _ -> false },
                dropKept = { pool.remove(it) },
            )
        assertNotNull(resLatest.document)
        assertFalse(resLatest.showsTarget, "Parked v1 must not satisfy latest without navigation")
        assertFalse(resLatest.alreadyNavigated)

        // Opening pinned v2 uses a separate slot key and does not collide with latest.
        val resV2 =
            resolveRaftArtifactViewerKeptSlot(
                scope = maya,
                target = artifactA.copy(version = 2),
                accessToken = token,
                conversationId = channel,
                hostHead = null,
                langTag = "zh-CN",
                useMultiSlot = true,
                cache = cache,
                readKept = pool::get,
                promoteKept = { _, _ -> false },
                dropKept = { pool.remove(it) },
            )
        assertNull(resV2.document)
        assertNotEquals(keyALatest, resV2.slotKey)
    }

    @Test
    fun surgicalEvictionPreservesSiblingSlotsWhileScopeSwitchAndClearEvictAll() {
        val droppedSlots = mutableListOf<String>()
        var fullClears = 0
        val cache =
            RaftArtifactViewerCache(
                onKeptDocumentDropped = { fullClears += 1 },
                onKeptSlotDropped = { droppedSlots += it },
            )
        val keyA = maya.artifactKeepAliveKey(artifactA, channel, langTag = "en")
        val keyB = maya.artifactKeepAliveKey(artifactB, channel, langTag = "en")
        val urlA = embeddedUrl(artifactA, langTag = "en")
        val urlB = embeddedUrl(artifactB, langTag = "en")

        assertTrue(cache.rememberKeptDocument(maya, artifactA, urlA, token, slotKey = keyA, renderedArtifact = true))
        cache.noteKeptBoot(maya, channel, null, "en", slotKey = keyA)
        assertTrue(cache.rememberKeptDocument(maya, artifactB, urlB, token, slotKey = keyB, renderedArtifact = true))
        cache.noteKeptBoot(maya, channel, null, "en", slotKey = keyB)

        // 403/404 on Artifact A evicts ONLY keyA; Artifact B stays usable.
        cache.evict(maya, artifactA.id)
        assertEquals(listOf(keyA), droppedSlots)
        assertEquals(0, fullClears)
        assertFalse(cache.keptDocumentShows(maya, artifactA, keyA))
        assertTrue(cache.keptDocumentShows(maya, artifactB, keyB))
        assertTrue(cache.keptDocumentUsable(maya, token, channel, null, "en", keyB))

        // Switching to another account or server clears EVERY slot of the prior scope.
        cache.remember(someoneElse, RaftArtifactViewerHeader(artifactA.id, "Other", build.raft.app.data.ArtifactKind.Html, 1))
        assertEquals(1, fullClears)
        assertFalse(cache.keptDocumentUsable(maya, token, channel, null, "en", keyB))

        // Switching to another server also clears the prior scope before adopting the new one.
        assertTrue(cache.rememberKeptDocument(mayaOtherServer, artifactA, urlA, token, slotKey = mayaOtherServer.artifactKeepAliveKey(artifactA)))
        assertEquals(2, fullClears)

        // Explicit logout clear wipes everything.
        cache.clear()
        assertEquals(3, fullClears)
        assertFalse(cache.keptDocumentShows(mayaOtherServer, artifactA, mayaOtherServer.artifactKeepAliveKey(artifactA)))
    }

    @Test
    fun prewarmPromotesCleanlyAndStaysCoveredUntilArtifactActuallyRenders() {
        val pool = LinkedHashMap<String, RaftWebViewKeptDocument>()
        val cache = RaftArtifactViewerCache(onKeptSlotDropped = { pool.remove(it) }, onKeptDocumentDropped = { pool.clear() })
        val prewarmKey = maya.keepAliveKey
        val keyA = maya.artifactKeepAliveKey(artifactA, channel, hostHead = null, langTag = "zh-CN")
        val warmUrl = raftArtifactViewerWarmUrl(web, channel, hostHead = null, langTag = "zh-CN")

        assertTrue(raftArtifactViewerIsWarmUrl(warmUrl, web))
        assertFalse(raftArtifactViewerIsWarmUrl(embeddedUrl(artifactA), web))

        pool[prewarmKey] = RaftWebViewKeptDocument(src = warmUrl, currentUrl = warmUrl, loaded = true)
        cache.markKeptWarm(maya, token, channel, hostHead = null, langTag = "zh-CN", slotKey = prewarmKey)

        // First open promotes prewarmKey -> keyA cleanly without leaking prewarmKey.
        val promoted =
            resolveRaftArtifactViewerKeptSlot(
                scope = maya,
                target = artifactA,
                accessToken = token,
                conversationId = channel,
                hostHead = null,
                langTag = "zh-CN",
                useMultiSlot = true,
                cache = cache,
                readKept = pool::get,
                promoteKept = { from, to ->
                    val existing = pool.remove(from) ?: return@resolveRaftArtifactViewerKeptSlot false
                    pool[to] = existing
                    true
                },
                dropKept = { pool.remove(it) },
            )
        assertTrue(promoted.promotedPrewarm)
        assertEquals(keyA, promoted.slotKey)
        assertNotNull(promoted.document)
        assertFalse(promoted.showsTarget)
        assertFalse(promoted.alreadyNavigated)
        assertFalse(promoted.renderedArtifact)
        assertNull(pool[prewarmKey], "Promoted prewarm slot must no longer occupy the prewarm key")
        assertNotNull(pool[keyA])

        // Even if tap opener pushed `/artifacts/pt_A` early (before `hostToolbar=1`), `renderedArtifact=false`
        // keeps `AppSplash` active instead of exposing an unrendered shell.
        val earlyPushUrl = embeddedUrl(artifactA, hostToolbar = false)
        pool[keyA] = RaftWebViewKeptDocument(src = warmUrl, currentUrl = earlyPushUrl, loaded = true)
        cache.rememberKeptDocument(maya, artifactA, earlyPushUrl, token, slotKey = keyA, renderedArtifact = false)
        val afterTapNav =
            resolveRaftArtifactViewerKeptSlot(
                scope = maya,
                target = artifactA,
                accessToken = token,
                conversationId = channel,
                hostHead = null,
                langTag = "zh-CN",
                useMultiSlot = true,
                cache = cache,
                readKept = pool::get,
                promoteKept = { _, _ -> false },
                dropKept = { pool.remove(it) },
            )
        assertFalse(afterTapNav.renderedArtifact)
        assertFalse(raftArtifactViewerKeptPageDrewBar(afterTapNav.document?.currentUrl))
        assertEquals(
            RaftArtifactViewerLoadingIndicator.AppSplash,
            raftArtifactViewerLoadingIndicator(
                RaftArtifactViewerOpenState(
                    sessionRead = true,
                    documentShown = true,
                    reusesKeptPage = false,
                    loadError = false,
                    sinceDocumentReadyMs = 0L,
                    pageDrawsBar = false,
                ),
            ),
        )

        // Stale-session prewarm is dropped instead of reused.
        val droppedReasons = mutableListOf<String>()
        pool.clear()
        pool[prewarmKey] = RaftWebViewKeptDocument(src = warmUrl, currentUrl = warmUrl, loaded = true)
        cache.markKeptWarm(maya, "old-token", channel, hostHead = null, langTag = "zh-CN", slotKey = prewarmKey)
        val staleRes =
            resolveRaftArtifactViewerKeptSlot(
                scope = maya,
                target = artifactB,
                accessToken = token,
                conversationId = channel,
                hostHead = null,
                langTag = "zh-CN",
                useMultiSlot = true,
                cache = cache,
                readKept = pool::get,
                promoteKept = { _, _ -> true },
                dropKept = { pool.remove(it) },
                onDroppedReason = { droppedReasons += it },
            )
        assertNull(staleRes.document)
        assertNull(pool[prewarmKey])
        assertEquals(listOf("prewarm_session_changed"), droppedReasons)
    }

    @Test
    fun poolTelemetryFormatterDistinguishesFreshCreateVsNoReloadReuseWithoutLeakingKey() {
        val secretKey = "artifact-viewer|https://app.raft.build|env|u-secret|s-secret|artifact=pt_1"
        val (createWarn, createLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.PoolEvent(
                    action = "created",
                    keepAliveKey = secretKey,
                    poolSizeAfter = 1,
                    engine = "android_webview",
                    reloaded = true,
                    surfaceId = "artifact_viewer",
                ),
            )
        assertFalse(createWarn)
        assertEquals(
            "web_container_pool action=created size=1 surface=artifact_viewer reloaded=true engine=android_webview",
            createLine,
        )
        assertFalse("u-secret" in createLine)

        val (reuseWarn, reuseLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.PoolEvent(
                    action = "reused",
                    keepAliveKey = secretKey,
                    poolSizeAfter = 1,
                    engine = "android_webview",
                    reloaded = false,
                    surfaceId = "artifact_viewer",
                ),
            )
        assertFalse(reuseWarn)
        assertEquals(
            "web_container_pool action=reused size=1 surface=artifact_viewer reloaded=false engine=android_webview",
            reuseLine,
        )
        assertFalse("s-secret" in reuseLine)
    }
}
