package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.compat.json.JSONObject
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class RaftFilePreviewLaunchContractTest {
    @Test
    fun filePreviewWireCarriesOnlyTypedIdentityAndIntent() {
        val descriptor = WireRaftFilePreviewLaunchPayload.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map(descriptor::getElementName)

        assertEquals(
            listOf(
                "version",
                "sessionId",
                "routeKind",
                "attachmentId",
                "previewKind",
                "attachmentCommentsEnabled",
                "commentsInitiallyOpen",
                "galleryAttachmentIds",
                "galleryIndex",
                "agentId",
                "workspacePath"
            ),
            names
        )
    }

    @Test
    fun explicitSourceAuthorityAllowsLaunchWhenPagerPageDataHasNoScope() {
        val sourceStoreScope = RaftShellStoreScope.create("settings-shell", "server-a")

        assertEquals(
            sourceStoreScope,
            resolveRaftFilePreviewSourceStoreScope(
                sourcePageData = JSONObject(),
                sourceStoreScope = sourceStoreScope
            )
        )
        assertNull(
            resolveRaftFilePreviewSourceStoreScope(
                sourcePageData = JSONObject(),
                sourceStoreScope = null
            )
        )
    }

    @Test
    fun liveAuthenticatedOwnerOverridesStaleSourceServer() {
        val sourcePageData = JSONObject().apply { put("serverId", "server-a") }
        val storeScope = RaftShellStoreScope.create("shell-a", "server-a")
        val liveOwner =
            AuthenticatedRouteOwner(
                environmentId = "https://api.raft.build/api",
                accountId = "account-a",
                serverId = "server-b"
            )

        assertEquals(
            "server-b",
            resolveRaftFilePreviewTargetServerId(
                sourcePageData = sourcePageData,
                storeScope = storeScope,
                liveAuthorityAvailable = true,
                liveOwner = liveOwner
            )
        )
        assertNull(
            resolveRaftFilePreviewTargetServerId(
                sourcePageData = sourcePageData,
                storeScope = storeScope,
                liveAuthorityAvailable = true,
                liveOwner = null
            )
        )
        assertEquals(
            "server-a",
            resolveRaftFilePreviewTargetServerId(
                sourcePageData = sourcePageData,
                storeScope = storeScope,
                liveAuthorityAvailable = false,
                liveOwner = null
            )
        )
    }

    @Test
    fun ephemeralSameProcessCapabilityResolvesExactLocalPayload() {
        val candidate =
            RaftFilePreviewPayload(
                kind = "image",
                imageUrl = "file:///cache/local-editor.png",
                composerImageThreadKey = "thread-1",
                composerImageActionId = "action-1",
                composerImageFileRef = "file:///cache/local-editor.png",
                composerImageCanComplete = true
            )
        val sessionId = RaftFilePreviewLaunchPayloadStore.put(candidate)
        val launch = raftFilePreviewLaunchDescriptor(sessionId, candidate)

        val trace =
            raftFilePreviewResolveLaunchPayload(
                source = Json.encodeToString(WireRaftFilePreviewLaunchPayload.serializer(), launch),
                loadPreviewPayload = RaftFilePreviewLaunchPayloadStore::get
            )

        assertEquals("success", trace.result)
        assertEquals(candidate, trace.payload)
        assertFalse(trace.requiresAuthenticatedRouteAuthority())
        assertTrue(RaftFilePreviewLaunchPayloadStore.retire(sessionId))
    }

    @Test
    fun threadAttachmentStoreMissRestoresIdentityOnlyLoadingPayload() {
        val launch =
            raftFilePreviewLaunchDescriptor(
                sessionId = "missing-thread-session",
                payload =
                    RaftFilePreviewPayload(
                        kind = "loading",
                        requestedKind = "image",
                        title = "Must not restore",
                        imageUrl = "https://signed.example/must-not-restore",
                        source = RaftFilePreviewSourceThreadAttachment,
                        attachmentId = "attachment-2",
                        attachmentCommentsEnabled = true,
                        commentsInitiallyOpen = true,
                        imageGallery =
                            listOf(
                                RaftFilePreviewGalleryImage(attachmentId = "attachment-1", name = "one.png"),
                                RaftFilePreviewGalleryImage(attachmentId = "attachment-2", name = "two.png")
                            ),
                        imageGalleryIndex = 1
                    )
            )

        val trace =
            raftFilePreviewResolveLaunchPayload(
                source = Json.encodeToString(WireRaftFilePreviewLaunchPayload.serializer(), launch),
                loadPreviewPayload = { null }
            )

        assertEquals("restored_thread_attachment", trace.result)
        assertEquals("loading", trace.payload.kind)
        assertEquals("image", trace.payload.requestedKind)
        assertEquals(RaftFilePreviewSourceThreadAttachment, trace.payload.source)
        assertEquals("attachment-2", trace.payload.attachmentId)
        assertEquals(listOf("attachment-1", "attachment-2"), trace.payload.imageGallery.map { it.attachmentId })
        assertEquals(1, trace.payload.imageGalleryIndex)
        assertTrue(trace.payload.attachmentCommentsEnabled)
        assertTrue(trace.payload.commentsInitiallyOpen)
        assertEquals("", trace.payload.title)
        assertNull(trace.payload.imageUrl)
        assertEquals("", trace.payload.composerImageActionId)
    }

    @Test
    fun localEditorStoreMissFailsClosedRetiresAndCarriesZeroCallbackMaterial() {
        val launch =
            raftFilePreviewLaunchDescriptor(
                sessionId = "local-editor-session",
                payload =
                    RaftFilePreviewPayload(
                        kind = "image",
                        imageUrl = "file:///tmp/picked.png",
                        composerImageThreadKey = "thread-1",
                        composerImageActionId = "action-1",
                        composerImageFileRef = "file:///tmp/picked.png",
                        composerImageCanComplete = true,
                        composerImageCanEdit = true
                    )
            )
        val retired = mutableListOf<String>()

        val trace =
            raftFilePreviewResolveLaunchPayload(
                source = Json.encodeToString(WireRaftFilePreviewLaunchPayload.serializer(), launch),
                loadPreviewPayload = { null },
                retireEphemeralSession = { sessionId ->
                    retired += sessionId
                    setOf("file:///cache/orphan.png")
                }
            )

        assertEquals("ephemeral_expired", trace.result)
        assertEquals("ephemeral_preview_expired", trace.payload.error)
        assertEquals(listOf("local-editor-session"), retired)
        assertEquals(setOf("file:///cache/orphan.png"), trace.temporaryFileRefsToCleanup)
        assertEquals("", trace.payload.composerImageThreadKey)
        assertEquals("", trace.payload.composerImageActionId)
        assertEquals("", trace.payload.composerImageFileRef)
        assertFalse(trace.payload.composerImageCanComplete)
        assertFalse(trace.payload.composerImageCanEdit)
        assertFalse(trace.requiresAuthenticatedRouteAuthority())
    }

    @Test
    fun mixedOrMalformedRemoteIdentityFailsClosedWithoutCandidateLookup() {
        var candidateLookupCount = 0
        val malformed =
            WireRaftFilePreviewLaunchPayload(
                sessionId = "mixed-session",
                routeKind = RaftFilePreviewRouteThreadAttachment,
                attachmentId = "attachment-1",
                previewKind = "image",
                agentId = "agent-must-not-mix",
                workspacePath = "notes.md"
            )

        val trace =
            raftFilePreviewResolveLaunchPayload(
                source = Json.encodeToString(WireRaftFilePreviewLaunchPayload.serializer(), malformed),
                loadPreviewPayload = {
                    candidateLookupCount += 1
                    null
                }
            )

        assertEquals("invalid_launch", trace.result)
        assertEquals("invalid_file_preview_launch", trace.payload.error)
        assertEquals(0, candidateLookupCount)
    }

    @Test
    fun ephemeralRegistryClaimsExactBatchOnceAndKeepsCompletionTombstone() {
        val registry = RaftFilePreviewEphemeralActionRegistry()
        registry.register(
            sessionId = "session-1",
            threadKey = "thread-1",
            actionIds = setOf("action-1", "action-2"),
            ownedTemporaryFileRefs = setOf("file:///cache/original.png")
        )
        assertTrue(
            registry.trackOwnedTemporaryFileRef(
                sessionId = "session-1",
                threadKey = "thread-1",
                fileRef = "file:///cache/edited-1.png"
            )
        )
        assertTrue(
            registry.trackOwnedTemporaryFileRef(
                sessionId = "session-1",
                threadKey = "thread-1",
                fileRef = "file:///cache/edited-2.png"
            )
        )

        val prepared =
            registry.prepareDeliveryBatch(
                sessionId = "session-1",
                threadKey = "thread-1",
                fileRefByActionId =
                    mapOf(
                        "action-1" to "file:///cache/edited-1.png",
                        "action-2" to "file:///cache/edited-2.png"
                    )
            )

        assertEquals(setOf("file:///cache/original.png"), requireNotNull(prepared).cleanupFileRefs)
        assertEquals(
            setOf("file:///cache/edited-1.png", "file:///cache/edited-2.png"),
            prepared.pendingFileRefs
        )
        assertIs<RaftFilePreviewEphemeralActionClaim.Rejected>(
            registry.claim("session-1", "wrong-thread", "action-1", "file:///cache/edited-1.png")
        )
        assertEquals(
            RaftFilePreviewEphemeralActionClaim.Accepted(sessionComplete = false),
            registry.claim("session-1", "thread-1", "action-1", "file:///cache/edited-1.png")
        )
        assertTrue(
            registry.markTransferred(
                "session-1",
                "thread-1",
                "action-1",
                "file:///cache/edited-1.png"
            )
        )
        assertIs<RaftFilePreviewEphemeralActionClaim.Rejected>(
            registry.claim("session-1", "thread-1", "action-1", "file:///cache/edited-1.png")
        )
        assertEquals(
            RaftFilePreviewEphemeralActionClaim.Accepted(sessionComplete = true),
            registry.claim("session-1", "thread-1", "action-2", "file:///cache/edited-2.png")
        )
        assertTrue(registry.markAllClaimedTransferred("session-1"))
        assertTrue(registry.hasSession("session-1"))
        assertTrue(registry.completePending("session-1"))
        assertFalse(registry.hasSession("session-1"))
    }

    @Test
    fun pendingCleanupExcludesDurableRefsAndAlreadyTransferredTemporaryRefs() {
        val registry = RaftFilePreviewEphemeralActionRegistry()
        val ledger = RaftFilePreviewEphemeralCleanupLedger(InMemoryRaftKeyValueStore())
        registry.register(
            sessionId = "session-owned",
            threadKey = "thread-1",
            actionIds = setOf("action-1", "action-2", "action-durable"),
            ownedTemporaryFileRefs =
                setOf(
                    "file:///cache/owned-1.png",
                    "file:///cache/owned-2.png"
                )
        )
        val prepared =
            requireNotNull(
                registry.prepareDeliveryBatch(
                    sessionId = "session-owned",
                    threadKey = "thread-1",
                    fileRefByActionId =
                        mapOf(
                            "action-1" to "file:///cache/owned-1.png",
                            "action-2" to "file:///cache/owned-2.png",
                            "action-durable" to "file:///feedback-draft/durable.png"
                        )
                )
            )
        ledger.replace("session-owned", prepared.pendingFileRefs)

        assertEquals(
            setOf("file:///cache/owned-1.png", "file:///cache/owned-2.png"),
            prepared.pendingFileRefs
        )
        val firstAdmission =
            assertIs<RaftFilePreviewComposerImageEventAdmission.Accepted>(
                raftFilePreviewClaimComposerImageEvent(
                    eventData =
                        JSONObject().apply {
                            put("previewSessionId", "session-owned")
                            put("threadKey", "thread-1")
                            put("actionId", "action-1")
                            put("fileRef", "file:///cache/owned-1.png")
                        },
                    expectedThreadKey = "thread-1",
                    registry = registry,
                    cleanupLedger = ledger
                )
            )
        assertTrue(
            raftFilePreviewMarkComposerImageEventTransferred(
                admission = firstAdmission,
                registry = registry,
                cleanupLedger = ledger
            )
        )

        assertEquals(
            setOf("file:///cache/owned-2.png"),
            raftFilePreviewFinishComposerImageEventSession(
                sessionId = "session-owned",
                transferred = false,
                registry = registry,
                cleanupLedger = ledger
            )
        )
    }

    @Test
    fun processDeathRegistryRejectsOldCallbackAndDrainsItsLedger() {
        val store = InMemoryRaftKeyValueStore()
        val ledger = RaftFilePreviewEphemeralCleanupLedger(store)
        ledger.register("old-session", setOf("file:///cache/old.png"))
        val event =
            JSONObject().apply {
                put("previewSessionId", "old-session")
                put("threadKey", "thread-1")
                put("actionId", "action-1")
                put("fileRef", "file:///cache/old.png")
            }

        val admission =
            raftFilePreviewClaimComposerImageEvent(
                eventData = event,
                expectedThreadKey = "thread-1",
                registry = RaftFilePreviewEphemeralActionRegistry(),
                cleanupLedger = ledger
            )

        val rejected = assertIs<RaftFilePreviewComposerImageEventAdmission.Rejected>(admission)
        assertEquals(setOf("file:///cache/old.png"), rejected.orphanedTemporaryFileRefs)
        assertTrue(ledger.take("old-session").isEmpty())
    }

    @Test
    fun galleryActionIdentityUsesTheFullAttachmentId() {
        val first =
            raftFilePreviewComposerImageGalleryActionId(
                baseActionId = "base",
                attachmentId = "same-prefix-1-full-id",
                index = 0
            )
        val second =
            raftFilePreviewComposerImageGalleryActionId(
                baseActionId = "base",
                attachmentId = "same-prefix-2-full-id",
                index = 0
            )

        assertFalse(first == second)
        assertTrue(first.endsWith("same-prefix-1-full-id"))
        assertTrue(second.endsWith("same-prefix-2-full-id"))
    }
}
