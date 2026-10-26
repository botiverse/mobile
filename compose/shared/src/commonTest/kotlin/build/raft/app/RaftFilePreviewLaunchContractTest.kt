package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.RaftEventBus
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
    fun remotePageDataExcludesDisplayContentUrlsFileRefsAndTokens() {
        val payload =
            RaftFilePreviewPayload(
                kind = "loading",
                requestedKind = "image",
                title = "SECRET_TITLE",
                subtitle = "SECRET_SUBTITLE",
                name = "SECRET_NAME.png",
                path = "SECRET_PATH.png",
                meta = "SECRET_META",
                content = "SECRET_CONTENT",
                imageUrl = "https://signed.example/SECRET_IMAGE_TOKEN",
                externalUrl = "https://signed.example/SECRET_EXTERNAL_TOKEN",
                externalFileName = "SECRET_EXTERNAL_NAME.png",
                externalMimeType = "image/png",
                source = RaftFilePreviewSourceThreadAttachment,
                attachmentId = "attachment-current",
                imageGallery =
                    listOf(
                        RaftFilePreviewGalleryImage(
                            attachmentId = "attachment-current",
                            name = "SECRET_GALLERY_NAME",
                            thumbnailUrl = "https://signed.example/SECRET_THUMB_TOKEN",
                            composerImageFileRef = "file:///SECRET_REF",
                            composerImageOwnedTemporary = true
                        )
                    ),
                composerImageOwnedTemporaryFileRefs = listOf("file:///SECRET_OWNED_REF")
            )
        val launch = raftFilePreviewLaunchDescriptor("preview-session", payload)
        val pageData =
            raftFilePreviewLaunchPageData(
                launch = launch,
                storeScope = RaftShellStoreScope.create("shell-a", "server-a"),
                targetServerId = "server-a"
            )
        val encodedPayload = pageData.optString("payload")

        assertEquals(RAFT_FILE_PREVIEW_PAGE, pageData.optString("pageName"))
        assertEquals("shell-a", pageData.optString(RAFT_SHELL_INSTANCE_ID))
        assertEquals("server-a", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals("server-a", pageData.optString("serverId"))
        assertTrue(encodedPayload.contains("attachment-current"))
        listOf(
            "SECRET_TITLE",
            "SECRET_SUBTITLE",
            "SECRET_NAME",
            "SECRET_PATH",
            "SECRET_META",
            "SECRET_CONTENT",
            "SECRET_IMAGE_TOKEN",
            "SECRET_EXTERNAL_TOKEN",
            "SECRET_EXTERNAL_NAME",
            "SECRET_GALLERY_NAME",
            "SECRET_THUMB_TOKEN",
            "SECRET_REF",
            "SECRET_OWNED_REF",
            "fileRef",
            "accessToken",
            "refreshToken"
        ).forEach { forbidden ->
            assertFalse(encodedPayload.contains(forbidden), "$forbidden must stay outside compose_file_preview payload")
        }
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
    fun remoteSameProcessCandidateCannotBypassCanonicalHydration() {
        val candidate =
            RaftFilePreviewPayload(
                kind = "loading",
                requestedKind = "image",
                title = "Candidate title",
                imageUrl = "https://candidate.example/image",
                source = RaftFilePreviewSourceThreadAttachment,
                attachmentId = "attachment-1"
            )
        val sessionId = RaftFilePreviewLaunchPayloadStore.put(candidate)
        val pageData =
            raftFilePreviewLaunchPageData(
                launch = raftFilePreviewLaunchDescriptor(sessionId, candidate),
                storeScope = RaftShellStoreScope.create("shell-a", "server-a")
            )

        var candidateLookupCount = 0
        val trace =
            raftFilePreviewResolveLaunchPayload(
                source = pageData.optString("payload"),
                loadPreviewPayload = {
                    candidateLookupCount += 1
                    RaftFilePreviewLaunchPayloadStore.get(it)
                }
            )

        assertEquals("restored_thread_attachment", trace.result)
        assertEquals(0, candidateLookupCount)
        assertEquals("loading", trace.payload.kind)
        assertEquals("", trace.payload.title)
        assertNull(trace.payload.imageUrl)
        assertEquals("attachment-1", trace.payload.attachmentId)
        assertTrue(trace.requiresAuthenticatedRouteAuthority())
        assertTrue(RaftFilePreviewLaunchPayloadStore.retire(sessionId))
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
    fun agentWorkspaceStoreMissRestoresAgentAndPath() {
        val launch =
            raftFilePreviewLaunchDescriptor(
                sessionId = "missing-workspace-session",
                payload = agentWorkspaceLazyPreviewPayload("agent-1", "notes/plan.md")
            )

        val trace =
            raftFilePreviewResolveLaunchPayload(
                source = Json.encodeToString(WireRaftFilePreviewLaunchPayload.serializer(), launch),
                loadPreviewPayload = { null }
            )

        assertEquals("restored_agent_workspace", trace.result)
        assertEquals(RaftFilePreviewSourceAgentWorkspace, trace.payload.source)
        assertEquals("agent-1", trace.payload.agentId)
        assertEquals("notes/plan.md", trace.payload.path)
        assertEquals("loading", trace.payload.kind)
        assertTrue(trace.requiresAuthenticatedRouteAuthority())
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
    fun localEditorWireContainsOnlyOpaqueSessionIdentity() {
        val launch =
            raftFilePreviewLaunchDescriptor(
                sessionId = "opaque-session",
                payload =
                    RaftFilePreviewPayload(
                        kind = "image",
                        imageUrl = "file:///tmp/private.png",
                        composerImageThreadKey = "private-thread",
                        composerImageActionId = "private-action",
                        composerImageFileRef = "file:///tmp/private.png",
                        composerImageCanComplete = true
                    )
            )
        val encoded = Json.encodeToString(WireRaftFilePreviewLaunchPayload.serializer(), launch)

        assertEquals(RaftFilePreviewRouteEphemeral, launch.routeKind)
        assertTrue(encoded.contains("opaque-session"))
        listOf("private.png", "private-thread", "private-action", "file://").forEach { forbidden ->
            assertFalse(encoded.contains(forbidden))
        }
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
    fun storeRetirementIsExactlyOnce() {
        val store = RaftFilePreviewStore()
        val sessionId = store.put(RaftFilePreviewPayload(kind = "image"))

        assertTrue(store.retire(sessionId))
        assertFalse(store.retire(sessionId))
        assertNull(store.get(sessionId))
    }

    @Test
    fun remoteRouteIntentWinsWithoutReadingProcessCandidate() {
        val launchPayload =
            RaftFilePreviewPayload(
                kind = "loading",
                requestedKind = "image",
                source = RaftFilePreviewSourceThreadAttachment,
                attachmentId = "attachment-1",
                attachmentCommentsEnabled = true,
                commentsInitiallyOpen = true
            )
        val launch = raftFilePreviewLaunchDescriptor("candidate-session", launchPayload)
        val mismatchedCandidates =
            listOf(
                launchPayload.copy(requestedKind = "text"),
                launchPayload.copy(attachmentCommentsEnabled = false, commentsInitiallyOpen = false),
                launchPayload.copy(commentsInitiallyOpen = false)
            )

        mismatchedCandidates.forEach { candidate ->
            var candidateLookupCount = 0
            val trace =
                raftFilePreviewResolveLaunchPayload(
                    source = Json.encodeToString(WireRaftFilePreviewLaunchPayload.serializer(), launch),
                    loadPreviewPayload = {
                        candidateLookupCount += 1
                        candidate
                    }
                )

            assertEquals("restored_thread_attachment", trace.result)
            assertEquals(0, candidateLookupCount)
            assertEquals("image", trace.payload.requestedKind)
            assertTrue(trace.payload.attachmentCommentsEnabled)
            assertTrue(trace.payload.commentsInitiallyOpen)
        }
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
    fun postFailureRetiresOnlyAWhollyUnclaimedPendingBatch() {
        val registry = RaftFilePreviewEphemeralActionRegistry()
        registry.register(
            sessionId = "session-unclaimed",
            threadKey = "thread-1",
            actionIds = setOf("action-1"),
            ownedTemporaryFileRefs = setOf("file:///cache/unclaimed.png")
        )
        registry.prepareDeliveryBatch(
            sessionId = "session-unclaimed",
            threadKey = "thread-1",
            fileRefByActionId = mapOf("action-1" to "file:///cache/unclaimed.png")
        )

        assertEquals(
            RaftFilePreviewEphemeralSessionRetirement(
                cleanupFileRefs = setOf("file:///cache/unclaimed.png"),
                retiredSession = true
            ),
            registry.retirePendingIfUnclaimed("session-unclaimed")
        )
        assertFalse(registry.hasSession("session-unclaimed"))

        registry.register(
            sessionId = "session-claimed",
            threadKey = "thread-1",
            actionIds = setOf("action-1"),
            ownedTemporaryFileRefs = setOf("file:///cache/claimed.png")
        )
        registry.prepareDeliveryBatch(
            sessionId = "session-claimed",
            threadKey = "thread-1",
            fileRefByActionId = mapOf("action-1" to "file:///cache/claimed.png")
        )
        assertEquals(
            RaftFilePreviewEphemeralActionClaim.Accepted(sessionComplete = true),
            registry.claim("session-claimed", "thread-1", "action-1", "file:///cache/claimed.png")
        )

        assertEquals(
            RaftFilePreviewEphemeralSessionRetirement(),
            registry.retirePendingIfUnclaimed("session-claimed")
        )
        assertTrue(registry.hasSession("session-claimed"))
        assertTrue(
            registry.markTransferred(
                "session-claimed",
                "thread-1",
                "action-1",
                "file:///cache/claimed.png"
            )
        )
        assertTrue(registry.completePending("session-claimed"))
    }

    @Test
    fun composerEditorEventsStayProcessLocalAndAttemptTheWholeBatchAfterFailure() {
        val deliveries = mutableListOf<Pair<String, Boolean>>()
        val eventBus =
            object : RaftEventBus {
                override fun observe(
                    eventName: String,
                    crossProcess: Boolean,
                    listener: (kotlinx.serialization.json.JsonObject?) -> Unit
                ): RealtimeSubscription = RealtimeSubscription {}

                override fun post(
                    eventName: String,
                    eventData: kotlinx.serialization.json.JsonObject,
                    crossProcess: Boolean,
                    source: String
                ) {
                    deliveries += eventData.optString("actionId") to crossProcess
                    if (deliveries.size == 1) error("injected listener failure")
                }
            }

        assertFalse(
            raftFilePreviewPostComposerImageEvents(
                events =
                    listOf(
                        JSONObject().put("actionId", "action-1"),
                        JSONObject().put("actionId", "action-2")
                    ),
                eventBus = eventBus
            )
        )
        assertEquals(
            listOf("action-1" to false, "action-2" to false),
            deliveries
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
    fun cleanupLedgerIsBoundedAndReplaceCreatesAnIndexedRecord() {
        val ledger =
            RaftFilePreviewEphemeralCleanupLedger(
                store = InMemoryRaftKeyValueStore(),
                maxEntries = 2
            )

        ledger.register("session-1", setOf("file:///cache/one.png"))
        ledger.replace("session-2", setOf("file:///cache/two.png"))
        val evicted = ledger.register("session-3", setOf("file:///cache/three.png"))

        assertEquals(setOf("file:///cache/one.png"), evicted)
        assertEquals(
            setOf("file:///cache/two.png"),
            ledger.takeOrphans(activeSessionIds = setOf("session-3"))
        )
        assertEquals(setOf("file:///cache/three.png"), ledger.take("session-3"))
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

    @Test
    fun legacyComposerCallbackWithoutPreviewSessionIsRejected() {
        val admission =
            raftFilePreviewClaimComposerImageEvent(
                eventData =
                    JSONObject().apply {
                        put("threadKey", "thread-1")
                        put("actionId", "action-1")
                        put("fileRef", "file:///cache/edited.png")
                    },
                expectedThreadKey = "thread-1",
                registry = RaftFilePreviewEphemeralActionRegistry(),
                cleanupLedger = RaftFilePreviewEphemeralCleanupLedger(InMemoryRaftKeyValueStore())
            )

        assertIs<RaftFilePreviewComposerImageEventAdmission.Rejected>(admission)
    }

    @Test
    fun invalidJsonFailsClosed() {
        val trace =
            raftFilePreviewResolveLaunchPayload(
                source = "{not-json",
                loadPreviewPayload = { null }
            )

        assertEquals("failure", trace.result)
        assertEquals(Res.string.file_preview_unavailable.desc(), trace.payload.titleDesc)
        assertEquals("payload_parse_failed", trace.payload.error)
    }
}
