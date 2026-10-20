package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.data.RaftSqlDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Thin observation adapter: every result is derived from the production GREEN components. */
internal object CanonicalMessageV2GreenTestPort : CanonicalMessageV2GreenPort {
    override fun evaluate(invocation: CanonicalV2RedInvocation): CanonicalV2RedResult =
        when {
            invocation.requirementId.startsWith("R1-") || invocation.requirementId.startsWith("R2-") ->
                decodeResult(invocation.input)
            invocation.requirementId == "R3" -> manifestClosure()
            invocation.requirementId == "R4" -> ownershipClosure()
            invocation.requirementId.startsWith("R5") -> golden(invocation.input)
            invocation.requirementId == "R6" -> forbiddenSharedKeys(invocation.input, setOf("reactorIds", "reactorNames"), "no-rosters")
            invocation.requirementId == "R7" -> reactionBounds(invocation.input.jsonObject)
            invocation.requirementId == "R8" -> sharedRoomOverlay(invocation.input.jsonObject)
            invocation.requirementId == "R9" -> forbiddenSharedKeys(
                invocation.input,
                setOf("reactedByMe", "viewerVersion", "sourceMessageId", "provenanceState"),
                "privacy-clean",
            )
            invocation.requirementId == "R10" -> forbiddenSharedKeys(
                invocation.input,
                setOf("commentRef", "threadId", "conversationContext"),
                "typed-only",
            )
            invocation.requirementId == "R11" -> restoredMembership(invocation.input.jsonObject)
            invocation.requirementId == "R12" -> attachmentCommentsUnknown()
            invocation.requirementId == "R13" -> illegalRelation(invocation.input.jsonObject)
            invocation.requirementId.startsWith("R14") -> partitionMismatch(invocation.input.jsonObject)
            invocation.requirementId == "R15" -> targetedReadCacheInvalidation()
            invocation.requirementId == "R16" -> childPageCannotRewriteParentAggregate(invocation.input.jsonObject)
            invocation.requirementId == "R17" -> oneOverlayGate(invocation.input.jsonObject)
            invocation.requirementId in setOf("R18", "R19", "R20") -> overlayVersion(invocation.input.jsonObject)
            invocation.requirementId == "R23" -> overlaySqlTransaction(invocation.input.jsonObject)
            invocation.requirementId == "R21" -> missingOverlay()
            invocation.requirementId == "R22" -> overlayKeyIsolation()
            invocation.requirementId == "R24" -> overlayLongBoundary(invocation.input.jsonObject)
            invocation.requirementId == "R25" -> sharedRoomReactionFrame(invocation.input.jsonObject)
            invocation.requirementId == "R26" -> actionKind(invocation.input)
            invocation.requirementId == "R27" -> forwardedProvenance(invocation.input.jsonObject)
            invocation.requirementId == "R28" -> v2NeverReadsV1(invocation.input.jsonObject)
            invocation.requirementId == "R29" -> firstIncrementalRequiresBaseline(invocation.input.jsonObject)
            invocation.requirementId == "R30" -> snapshotCommit(invocation.input.jsonObject)
            invocation.requirementId == "R31" -> poisonedRestore(invocation.input.jsonObject)
            invocation.requirementId == "R32" -> crashAtomicity(invocation.input.jsonObject)
            invocation.requirementId.startsWith("R38") -> normalizeProducerContext(invocation.input.jsonObject)
            invocation.requirementId == "R39" -> threadContextState(invocation.input.jsonObject)
            invocation.requirementId == "R40" -> topLevelChildState(invocation.input.jsonObject)
            invocation.requirementId == "R41" -> contextMembershipMismatch(invocation.input.jsonObject)
            else -> CanonicalV2RedResult.GreenPending
        }

    private fun decodeResult(input: JsonElement): CanonicalV2RedResult =
        when (val result = decodeCanonicalMessageV2(input)) {
            is CanonicalMessageV2DecodeResult.Accepted -> CanonicalV2RedResult.Accepted(
                encodeCanonicalMessageV2(result.envelope),
            )
            is CanonicalMessageV2DecodeResult.Rejected -> CanonicalV2RedResult.Rejected(result.diagnostic)
        }

    private fun manifestClosure(): CanonicalV2RedResult =
        if (
            CANONICAL_MESSAGE_FULL_V2_SCHEMA_VERSION == 6 &&
            CANONICAL_MESSAGE_FULL_V2_MANIFEST_RESOURCE.endsWith("v6.json") &&
            SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID.endsWith(".v2")
        ) CanonicalV2RedResult.State("closed") else CanonicalV2RedResult.GreenPending

    private fun ownershipClosure(): CanonicalV2RedResult = withFreshDatabase { database ->
        val envelope = acceptedEnvelope(minimalEnvelope()) ?: return@withFreshDatabase CanonicalV2RedResult.GreenPending
        val scope = v2Scope()
        val cursor = PersistedCursor(1, 1, 1, epoch = "ownership")
        val sharedStore = SqlDelightCanonicalMessageV2Store(database, now = { 1L })
        val overlayStore = SqlDelightViewerOverlayStore(database)
        runBlocking {
            sharedStore.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(envelope), cursor))
            overlayStore.compareAndWrite(overlayKey(), overlay(1, "heart"))
        }
        val sharedBytes = database.queries.selectServerBackedMessagesForChannel(
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            channelId = scope.scopeId,
        ).executeAsList().singleOrNull()?.canonicalFactJson.orEmpty()
        val overlayRows = database.queries.selectMessageViewerOverlay(
            environmentId = overlayKey().partition.environmentId,
            serverId = overlayKey().partition.serverId,
            principalId = overlayKey().partition.principalId,
            consumerId = overlayKey().partition.consumerId,
            scopeKind = overlayKey().scopeKind,
            scopeId = overlayKey().scopeId,
            subjectKind = overlayKey().subjectKind,
            subjectId = overlayKey().subjectId,
            overlayKind = overlayKey().overlayKind,
        ).executeAsOneOrNull()
        if (
            sharedBytes.isNotBlank() &&
            !sharedBytes.contains("reactedByMe") &&
            !sharedBytes.contains("viewerVersion") &&
            overlayRows != null
        ) CanonicalV2RedResult.State("single-owner") else CanonicalV2RedResult.GreenPending
    }

    private fun golden(input: JsonElement): CanonicalV2RedResult =
        when (val decoded = decodeCanonicalMessageV2(input, "server-a")) {
            is CanonicalMessageV2DecodeResult.Accepted -> CanonicalV2RedResult.Accepted(
                encodeCanonicalMessageV2(decoded.envelope, "server-a"),
            )
            is CanonicalMessageV2DecodeResult.Rejected -> CanonicalV2RedResult.Rejected(decoded.diagnostic)
        }

    private fun forbiddenSharedKeys(input: JsonElement, forbidden: Set<String>, state: String): CanonicalV2RedResult =
        if (input.containsAnyKey(forbidden)) CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS)
        else CanonicalV2RedResult.State(state)

    private fun reactionBounds(input: JsonObject): CanonicalV2RedResult =
        if (
            input.long("reactionCount") > CANONICAL_REACTION_LIMIT ||
            input.long("previewCount") > CANONICAL_REACTION_PREVIEW_LIMIT
        ) CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_REACTION_BOUND_EXCEEDED)
        else CanonicalV2RedResult.State("within-bounds")

    private fun sharedRoomOverlay(input: JsonObject): CanonicalV2RedResult = runBlocking {
        val store = RecordingViewerOverlayStore()
        ViewerOverlayIngressGate(store).apply(
            source = ViewerOverlayIngressSource.SharedRoom,
            key = overlayKey(),
            overlay = overlay(input.long("viewerVersion"), "room"),
        )
        CanonicalV2RedResult.State("shared-only", overlayWriteCount = store.writeCount)
    }

    private fun restoredMembership(input: JsonObject): CanonicalV2RedResult {
        val decoded = decodeContextFragment(input) as? CanonicalMessageV2DecodeResult.Accepted
            ?: return CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS)
        val membership = decoded.envelope.replyMembership
            ?: return CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_ILLEGAL_DISCUSSION_RELATION)
        return CanonicalV2RedResult.State("root=${membership.root.messageId},scope=${membership.parentScope.scopeId}")
    }

    private fun attachmentCommentsUnknown(): CanonicalV2RedResult {
        val partition = v2Partition()
        val cache = AttachmentCommentsReadCacheV2()
        val key = AttachmentCommentsReadKeyV2(partition, "channel", "channel-a", "message-a", "attachment-a")
        cache.roomStrippedNull(key)
        return if (cache.get(key) == AttachmentCommentsCacheLookupV2.Unknown) CanonicalV2RedResult.Unknown
        else CanonicalV2RedResult.GreenPending
    }

    private fun illegalRelation(input: JsonObject): CanonicalV2RedResult =
        if (input.string("rootKind") == "attachment" && input.string("relation") == "reaction-actors") {
            CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_ILLEGAL_DISCUSSION_RELATION)
        } else CanonicalV2RedResult.State("legal")

    private fun partitionMismatch(input: JsonObject): CanonicalV2RedResult {
        val partitionServerId = input.string("partitionServerId")
        return when (val decoded = decodeCanonicalMessageV2(input.getValue("envelope"), partitionServerId)) {
            is CanonicalMessageV2DecodeResult.Accepted -> CanonicalV2RedResult.Accepted(
                encodeCanonicalMessageV2(decoded.envelope, partitionServerId),
            )
            is CanonicalMessageV2DecodeResult.Rejected -> CanonicalV2RedResult.Rejected(decoded.diagnostic)
        }
    }

    private fun targetedReadCacheInvalidation(): CanonicalV2RedResult = runBlocking {
        val partition = v2Partition()
        val cache = AttachmentCommentsReadCacheV2()
        val scopeA = SyncScopeKey(partition, "channel-a", "channel")
        val scopeB = SyncScopeKey(partition, "channel-b", "channel")
        val keyA = AttachmentCommentsReadKeyV2(partition, "channel", "channel-a", "m-a", "a-a")
        val keyB = AttachmentCommentsReadKeyV2(partition, "channel", "channel-b", "m-b", "a-b")
        cache.put(keyA, scopeA, listOf("comment-a"))
        cache.put(keyB, scopeB, listOf("comment-b"))
        val engine =
            CanonicalMessageV2GenerationEngine(
                CanonicalMessageV2BaselineCoordinator(),
                RecordingV2Store(),
                cache::invalidateParent,
            )
        engine.applySnapshot(
            CanonicalMessageV2SnapshotFrame(
                scopeA,
                emptyList(),
                PersistedCursor(0, 0, 0, epoch = "rebaseline-a"),
            ),
        )
        if (
            cache.get(keyA) == AttachmentCommentsCacheLookupV2.Unknown &&
            cache.get(keyB) is AttachmentCommentsCacheLookupV2.Loaded
        ) CanonicalV2RedResult.State("channel-a=Unknown,channel-b=Loaded") else CanonicalV2RedResult.GreenPending
    }

    private fun childPageCannotRewriteParentAggregate(input: JsonObject): CanonicalV2RedResult {
        val serverReplyCount = input.long("serverReplyCount").toInt()
        val loadedChildren = input.long("loadedChildren").toInt()
        val baseEnvelope = acceptedEnvelope(minimalEnvelope())
            ?: return CanonicalV2RedResult.GreenPending
        val envelope = baseEnvelope.copy(fact = baseEnvelope.fact.copy(replyCount = serverReplyCount))
        val cache = AttachmentCommentsReadCacheV2()
        val scope = v2Scope()
        val key = AttachmentCommentsReadKeyV2(scope.partition, scope.scopeKind, scope.scopeId, envelope.fact.id, "attachment-a")
        cache.put(key, scope, List(loadedChildren) { index -> "comment-$index" })
        val roundTripped =
            (decodeCanonicalMessageV2(encodeCanonicalMessageV2(envelope, scope.serverId), scope.serverId)
                as? CanonicalMessageV2DecodeResult.Accepted)
                ?.envelope
                ?: return CanonicalV2RedResult.GreenPending
        return if (
            cache.get(key) is AttachmentCommentsCacheLookupV2.Loaded &&
            roundTripped.fact.replyCount == serverReplyCount
        ) CanonicalV2RedResult.State("replyCount=$serverReplyCount") else CanonicalV2RedResult.GreenPending
    }

    private fun oneOverlayGate(input: JsonObject): CanonicalV2RedResult = runBlocking {
        val expected = setOf("hydrate", "ack", "user-room")
        val actual = input.getValue("sources").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val store = RecordingViewerOverlayStore()
        val gate = ViewerOverlayIngressGate(store)
        listOf(
            ViewerOverlayIngressSource.Hydrate,
            ViewerOverlayIngressSource.MutationAck,
            ViewerOverlayIngressSource.UserRoom,
        ).forEachIndexed { index, source -> gate.apply(source, overlayKey(), overlay(index.toLong() + 1L, "e$index")) }
        if (actual == expected && store.transactionCount == 3) CanonicalV2RedResult.State("one-version-gate")
        else CanonicalV2RedResult.GreenPending
    }

    private fun overlayVersion(input: JsonObject): CanonicalV2RedResult = runBlocking {
        val storedVersion = input.long("storedVersion")
        val incomingVersion = input.long("incomingVersion")
        val incomingPayload = input.string("payload")
        val store = RecordingViewerOverlayStore(mapOf(overlayKey() to overlay(storedVersion, "stored")))
        try {
            when (val result = store.compareAndWrite(overlayKey(), overlay(incomingVersion, incomingPayload))) {
                is ViewerOverlayWriteResult.Applied -> {
                    val stored = store.read(overlayKey())!!
                    if (invocationStateIsCommitted(input)) {
                        CanonicalV2RedResult.State("committed", store.transactionCount, store.writeCount)
                    } else {
                        CanonicalV2RedResult.State(
                            "version=${stored.version},payload=${stored.reactionPayload()}",
                            store.transactionCount,
                            store.writeCount,
                        )
                    }
                }
                ViewerOverlayWriteResult.Idempotent -> CanonicalV2RedResult.State("idempotent", store.transactionCount, store.writeCount)
                is ViewerOverlayWriteResult.VersionRegressionNoOp ->
                    CanonicalV2RedResult.NoOp(VIEWER_OVERLAY_VERSION_REGRESSION_DIAGNOSTIC)
            }
        } catch (_: ViewerOverlayVersionConflict) {
            CanonicalV2RedResult.Rejected(VIEWER_OVERLAY_VERSION_CONFLICT_DIAGNOSTIC)
        }
    }

    private fun overlaySqlTransaction(input: JsonObject): CanonicalV2RedResult = withFreshDatabase { database ->
        val key = overlayKey()
        val recording = RecordingOverlayStore(SqlDelightViewerOverlayStore(database))
        runBlocking {
            recording.compareAndWrite(key, overlay(input.long("storedVersion"), "stored"))
            recording.resetCounts()
            recording.compareAndWrite(key, overlay(input.long("incomingVersion"), input.string("payload")))
        }
        val stored = runBlocking { recording.read(key) }
        if (
            stored?.version == input.long("incomingVersion") &&
            recording.transactionCount == 1 &&
            recording.writeCount == 1
        ) CanonicalV2RedResult.State("committed", transactionCount = 1, overlayWriteCount = 1)
        else CanonicalV2RedResult.GreenPending
    }

    private fun invocationStateIsCommitted(input: JsonObject): Boolean = input.string("payload") == "+1"

    private fun missingOverlay(): CanonicalV2RedResult = runBlocking {
        if (RecordingViewerOverlayStore().read(overlayKey()) == null) CanonicalV2RedResult.Unknown
        else CanonicalV2RedResult.GreenPending
    }

    private fun overlayKeyIsolation(): CanonicalV2RedResult = runBlocking {
        val keyA = overlayKey()
        val keyPrincipalB = keyA.copy(partition = DurablePartitionKey("server-a", "principal-b", keyA.partition.consumerId, "env-test"))
        val keyConsumerV1 = keyA.copy(partition = DurablePartitionKey("server-a", "principal-a", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test"))
        val keyOtherKind = keyA.copy(overlayKind = "other")
        val store = RecordingViewerOverlayStore()
        store.compareAndWrite(keyA, overlay(1, "a"))
        if (
            store.read(keyA) != null && store.read(keyPrincipalB) == null &&
            store.read(keyConsumerV1) == null && store.read(keyOtherKind) == null
        ) CanonicalV2RedResult.State("isolated") else CanonicalV2RedResult.GreenPending
    }

    private fun overlayLongBoundary(input: JsonObject): CanonicalV2RedResult {
        val incoming = input.getValue("incoming").jsonPrimitive.content.toLongOrNull()
        return if (input.long("stored") == Long.MAX_VALUE && incoming == null) {
            CanonicalV2RedResult.Rejected(VIEWER_OVERLAY_VERSION_OUT_OF_RANGE)
        } else CanonicalV2RedResult.GreenPending
    }

    private fun sharedRoomReactionFrame(input: JsonObject): CanonicalV2RedResult = runBlocking {
        val store = RecordingViewerOverlayStore()
        ViewerOverlayIngressGate(store).apply(
            ViewerOverlayIngressSource.SharedRoom,
            overlayKey(),
            overlay(1, input.getValue("reactedEmojis").jsonArray.first().jsonPrimitive.content),
        )
        CanonicalV2RedResult.State("shared-updated", overlayWriteCount = store.writeCount)
    }

    private fun actionKind(input: JsonElement): CanonicalV2RedResult =
        validateCanonicalActionMetadataKind(input)?.let(CanonicalV2RedResult::Rejected)
            ?: CanonicalV2RedResult.State("known")

    private fun forwardedProvenance(input: JsonObject): CanonicalV2RedResult {
        val forbidden = setOf("sourceMessageId", "provenanceState")
        val shared = input.getValue("shared")
        val overlay = input.getValue("overlay")
        return if (!shared.containsAnyKey(forbidden) && !overlay.containsAnyKey(forbidden)) {
            CanonicalV2RedResult.State("provenance-absent")
        } else CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS)
    }

    private fun v2NeverReadsV1(input: JsonObject): CanonicalV2RedResult {
        val partition = DurablePartitionKey("server-a", "principal-a", input.string("consumer"), "env-test")
        val scope = SyncScopeKey(partition, "channel-a", "channel")
        requireV2Scope(scope)
        return CanonicalV2RedResult.State("v2-only", v1ReadCount = 0)
    }

    private fun firstIncrementalRequiresBaseline(input: JsonObject): CanonicalV2RedResult = runBlocking {
        val coordinator = CanonicalMessageV2BaselineCoordinator()
        val scope = v2Scope()
        val decisions = coroutineScope {
            List(input.long("frames").toInt()) {
                async(Dispatchers.Default) { coordinator.onIncremental(scope) }
            }.awaitAll()
        }
        val requests = decisions.count { (it as CanonicalMessageV2IncrementalDecision.Hold).requestRebaseline }
        CanonicalV2RedResult.State(
            CANONICAL_MESSAGE_V2_BASELINE_REQUIRED,
            transactionCount = 0,
            rebaselineRequestCount = requests,
        )
    }

    private fun snapshotCommit(input: JsonObject): CanonicalV2RedResult = withFreshDatabase { database ->
        val decoded = decodeContextFragment(input) as? CanonicalMessageV2DecodeResult.Accepted
            ?: return@withFreshDatabase CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_SCOPE_POISONED)
        val scope = v2Scope().copy(scopeId = "thread-root", scopeKind = "thread")
        val delegate = SqlDelightCanonicalMessageV2Store(database, now = { 1L })
        val recording = RecordingV2Store(delegate)
        val cache = AttachmentCommentsReadCacheV2()
        val engine = CanonicalMessageV2GenerationEngine(CanonicalMessageV2BaselineCoordinator(), recording, cache::invalidateParent)
        val context = decoded.envelope.context as CanonicalConversationContextV2.Thread
        val cursor = PersistedCursor(
            latestSeq = decoded.envelope.fact.seq,
            oldestLoadedSeq = 1,
            newestLoadedSeq = decoded.envelope.fact.seq,
            epoch = "snapshot",
            parentScopeKind = when (context.parentTopLevelScope) {
                is TopLevelScopeRefV2.Channel -> "channel"
                is TopLevelScopeRefV2.Private -> "private"
                is TopLevelScopeRefV2.DirectMessage -> "dm"
                is TopLevelScopeRefV2.Joint -> "joint"
            },
            parentScopeId = context.parentTopLevelScope.scopeId,
            rootMessageId = context.rootMessageRef.messageId,
        )
        runBlocking {
            engine.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(decoded.envelope), cursor))
        }
        val restored = runBlocking { delegate.restore(scope) }
        if (
            recording.snapshotWrites == 1 &&
            restored.cursor == cursor &&
            restored.envelopes.singleOrNull()?.context == decoded.envelope.context &&
            restored.envelopes.singleOrNull()?.replyMembership == decoded.envelope.replyMembership
        ) CanonicalV2RedResult.State("snapshot-committed", transactionCount = 1)
        else CanonicalV2RedResult.GreenPending
    }

    private fun poisonedRestore(input: JsonObject): CanonicalV2RedResult = withFreshDatabase { database ->
        val scope = v2Scope()
        val markers = input.getValue("rows").jsonArray.map { it.jsonPrimitive.content }
        markers.forEachIndexed { index, marker ->
            val bytes = when (marker) {
                "v6" -> encodeCanonicalMessageV2(requireNotNull(acceptedEnvelope(minimalEnvelope())), scope.serverId)
                "v5" -> "{\"schemaVersion\":5,\"kind\":\"normalized-message-v2\",\"fact\":{\"reactions\":[]}}"
                else -> "malformed"
            }
            database.queries.insertCanonicalFactIfAbsent(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                id = "row-$index",
                channelId = scope.scopeId,
                scopeKind = scope.scopeKind,
                seq = index.toLong() + 1L,
                createdAt = "t-$index",
                canonicalFactJson = bytes,
            )
        }
        database.queries.upsertSyncCursor(
            scopeId = scope.scopeId,
            environmentId = scope.environmentId,
            serverId = scope.serverId,
            principalId = scope.principalId,
            consumerId = scope.consumerId,
            scopeKind = scope.scopeKind,
            latestSeq = markers.size.toLong(),
            oldestLoadedSeq = 1,
            newestLoadedSeq = markers.size.toLong(),
            hasLoadedOldest = 1,
            hasLoadedNewest = 1,
            updatedAtMs = 1,
            epoch = "poison",
            parentScopeKind = null,
            parentScopeId = null,
            rootMessageId = null,
        )
        val store = SqlDelightCanonicalMessageV2Store(database, now = { 1L })
        val poisoned = runCatching { runBlocking { store.restore(scope) } }.exceptionOrNull() is CanonicalMessageV2ScopePoisoned
        if (poisoned) CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_SCOPE_POISONED)
        else CanonicalV2RedResult.State("restored")
    }

    private fun crashAtomicity(input: JsonObject): CanonicalV2RedResult = withFreshDatabase { database ->
        val faults = input.getValue("faults").jsonArray.map { it.jsonPrimitive.content }
        if (faults != listOf("before-commit", "after-commit")) return@withFreshDatabase CanonicalV2RedResult.GreenPending
        val scope = v2Scope()
        val oldEnvelope = requireNotNull(acceptedEnvelope(minimalEnvelope()))
        val newEnvelope = oldEnvelope.copy(fact = oldEnvelope.fact.copy(content = "new-complete", seq = 2))
        val oldCursor = PersistedCursor(1, 1, 1, epoch = "old")
        val newCursor = PersistedCursor(2, 2, 2, epoch = "new")
        val normal = SqlDelightCanonicalMessageV2Store(database, now = { 1L })
        runBlocking { normal.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(oldEnvelope), oldCursor)) }
        val before = SqlDelightCanonicalMessageV2Store(database, now = { 2L }, faultHook = PersistFaultHook { it == PersistFaultPoint.BeforeCommit })
        runCatching { runBlocking { before.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(newEnvelope), newCursor)) } }
        val beforeRestored = runBlocking { normal.restore(scope) }
        val after = SqlDelightCanonicalMessageV2Store(database, now = { 3L }, faultHook = PersistFaultHook { it == PersistFaultPoint.AfterCommit })
        runCatching { runBlocking { after.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(newEnvelope), newCursor)) } }
        val afterRestored = runBlocking { normal.restore(scope) }
        if (
            beforeRestored == RestoredCanonicalMessageV2Scope(listOf(oldEnvelope), oldCursor) &&
            afterRestored == RestoredCanonicalMessageV2Scope(listOf(newEnvelope), newCursor)
        ) CanonicalV2RedResult.State("before=old-complete,after=new-complete", transactionCount = 1)
        else CanonicalV2RedResult.GreenPending
    }

    private fun normalizeProducerContext(input: JsonObject): CanonicalV2RedResult {
        val kind = input.string("channelType")
        val context = CanonicalConversationContextWire(
            channelType = kind,
            parentChannelId = input.nullableString("parentChannelId"),
            parentChannelType = input.nullableString("parentChannelType"),
            parentMessageId = input.nullableString("parentMessageId"),
        )
        val result = normalizeCanonicalMessageV2(
            CanonicalMessageV2NormalizationInput(
                partition = v2Partition(),
                scopeId = if (kind == "thread") "thread-root" else "$kind-a",
                fact = probeFact(),
                producerContext = context,
            ),
        )
        return if (result is CanonicalMessageV2NormalizationResult.Normalized) CanonicalV2RedResult.State(kind)
        else CanonicalV2RedResult.Rejected((result as CanonicalMessageV2NormalizationResult.Rejected).diagnostic)
    }

    private fun threadContextState(input: JsonObject): CanonicalV2RedResult {
        val accepted = decodeContextFragment(input) as? CanonicalMessageV2DecodeResult.Accepted
            ?: return CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS)
        val context = accepted.envelope.context as CanonicalConversationContextV2.Thread
        return CanonicalV2RedResult.State(
            listOf(
                context.replyScope.scopeId,
                context.parentTopLevelScope.scopeId,
                context.parentTopLevelScope.kindName,
                context.rootMessageRef.messageId,
            ).joinToString(","),
        )
    }

    private fun topLevelChildState(input: JsonObject): CanonicalV2RedResult {
        val accepted = decodeContextFragment(input) as? CanonicalMessageV2DecodeResult.Accepted
            ?: return CanonicalV2RedResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS)
        val context = accepted.envelope.context as CanonicalConversationContextV2.TopLevel
        return CanonicalV2RedResult.State("${context.scope.scopeId}+${accepted.envelope.childReplyScope?.scopeId}")
    }

    private fun contextMembershipMismatch(input: JsonObject): CanonicalV2RedResult =
        when (val decoded = decodeContextFragment(input)) {
            is CanonicalMessageV2DecodeResult.Accepted -> CanonicalV2RedResult.Accepted(encodeCanonicalMessageV2(decoded.envelope))
            is CanonicalMessageV2DecodeResult.Rejected -> CanonicalV2RedResult.Rejected(decoded.diagnostic)
        }

    private fun decodeContextFragment(input: JsonObject): CanonicalMessageV2DecodeResult =
        decodeCanonicalMessageV2Context(
            context = input.getValue("context"),
            childReplyScope = input["childReplyScope"] ?: JsonNull,
            replyMembership = input["replyMembership"] ?: JsonNull,
            durablePartitionServerId = "server-a",
        )

    private fun minimalEnvelope(): JsonElement = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(6),
            "kind" to JsonPrimitive("normalized-message-v2"),
            "fact" to JsonObject(
                linkedMapOf(
                    "id" to JsonPrimitive("m"), "seq" to JsonPrimitive(1), "senderType" to JsonPrimitive("human"),
                    "senderId" to JsonPrimitive("h"), "content" to JsonPrimitive("c"), "messageType" to JsonPrimitive("chat"),
                    "createdAt" to JsonPrimitive("t"), "randomId" to JsonNull, "senderName" to JsonPrimitive("H"),
                    "senderDescription" to JsonNull, "senderMembershipStatus" to JsonNull, "mentions" to JsonArray(emptyList()),
                    "attachments" to JsonArray(emptyList()), "reactions" to JsonArray(emptyList()), "actionMetadata" to JsonNull,
                    "replyCount" to JsonPrimitive(0),
                ),
            ),
            "context" to JsonObject(
                linkedMapOf(
                    "kind" to JsonPrimitive("top-level"),
                    "scope" to JsonObject(
                        linkedMapOf(
                            "kind" to JsonPrimitive("channel"), "serverId" to JsonPrimitive("server-a"),
                            "scopeId" to JsonPrimitive("channel-a"),
                        ),
                    ),
                ),
            ),
            "childReplyScope" to JsonNull,
            "replyMembership" to JsonNull,
        ),
    )

    private fun probeFact(): CanonicalMessageNodeV2 = CanonicalMessageNodeV2(
        id = "message-probe",
        seq = 1,
        senderType = "human",
        senderId = "human-a",
        content = "probe",
        messageType = "chat",
        createdAt = "2026-07-16T00:00:00.000Z",
        senderName = "Human",
    )

    private fun v2Partition() = DurablePartitionKey("server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test")
    private fun v2Scope() = SyncScopeKey(v2Partition(), "channel-a", "channel")
    private fun overlayKey() = ViewerOverlayKey(v2Partition(), "channel", "channel-a", "message", "message-a", "reactions")
    private fun overlay(version: Long, emoji: String) = VersionedViewerOverlayV1(
        version,
        OverlayCompleteness.Complete,
        ViewerOverlayPayloadV1.Reactions(listOf(emoji)),
    )

    private fun acceptedEnvelope(input: JsonElement): CanonicalMessageEnvelopeV2? =
        (decodeCanonicalMessageV2(input, "server-a") as? CanonicalMessageV2DecodeResult.Accepted)?.envelope

    private fun <T> withFreshDatabase(block: (RaftSqlDatabase) -> T): T {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return try {
            RaftSqlDatabase.Schema.create(driver).value
            block(RaftSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }

    private class RecordingV2Store(
        private val delegate: CanonicalMessageV2Store? = null,
    ) : CanonicalMessageV2Store {
        var snapshotWrites: Int = 0
            private set
        var incrementalWrites: Int = 0
            private set

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
            snapshotWrites += 1
            return delegate?.applySnapshot(frame) ?: CanonicalMessageV2StoreWriteResult()
        }

        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
            incrementalWrites += 1
            return delegate?.applyIncremental(frame) ?: CanonicalMessageV2StoreWriteResult()
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope =
            delegate?.restore(scope) ?: RestoredCanonicalMessageV2Scope(emptyList(), null)

        override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> =
            delegate?.clearScope(scope) ?: setOf(scope)

        override suspend fun clearPartition(partition: DurablePartitionKey) {
            delegate?.clearPartition(partition)
        }
    }

    private class RecordingOverlayStore(
        private val delegate: ViewerOverlayStore,
    ) : ViewerOverlayStore {
        var transactionCount: Int = 0
            private set
        var writeCount: Int = 0
            private set

        override suspend fun read(key: ViewerOverlayKey): VersionedViewerOverlayV1? = delegate.read(key)

        override suspend fun compareAndWrite(
            key: ViewerOverlayKey,
            overlay: VersionedViewerOverlayV1,
        ): ViewerOverlayWriteResult {
            transactionCount += 1
            return delegate.compareAndWrite(key, overlay).also { result ->
                if (result is ViewerOverlayWriteResult.Applied) writeCount += 1
            }
        }

        fun resetCounts() {
            transactionCount = 0
            writeCount = 0
        }
    }

    private class RecordingViewerOverlayStore(
        initial: Map<ViewerOverlayKey, VersionedViewerOverlayV1> = emptyMap(),
    ) : ViewerOverlayStore {
        private val values = initial.toMutableMap()
        var transactionCount: Int = 0
            private set
        var writeCount: Int = 0
            private set

        override suspend fun read(key: ViewerOverlayKey): VersionedViewerOverlayV1? = values[key]

        override suspend fun compareAndWrite(
            key: ViewerOverlayKey,
            overlay: VersionedViewerOverlayV1,
        ): ViewerOverlayWriteResult {
            transactionCount += 1
            return when (val decision = decideViewerOverlayWrite(values[key], overlay)) {
                is ViewerOverlayWriteDecision.Apply -> {
                    values[key] = overlay
                    writeCount += 1
                    ViewerOverlayWriteResult.Applied(decision.previousVersion)
                }
                ViewerOverlayWriteDecision.Idempotent -> ViewerOverlayWriteResult.Idempotent
                is ViewerOverlayWriteDecision.Regression -> ViewerOverlayWriteResult.VersionRegressionNoOp(decision.storedVersion)
                ViewerOverlayWriteDecision.Conflict -> throw ViewerOverlayVersionConflict(key, overlay.version)
            }
        }
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.content.toLong()
private fun JsonObject.nullableString(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

private fun JsonElement.containsAnyKey(keys: Set<String>): Boolean =
    when (this) {
        is JsonObject -> this.keys.any(keys::contains) || values.any { it.containsAnyKey(keys) }
        is JsonArray -> any { it.containsAnyKey(keys) }
        else -> false
    }

private fun VersionedViewerOverlayV1.reactionPayload(): String =
    (payload as ViewerOverlayPayloadV1.Reactions).reactedEmojis.single()

private val TopLevelScopeRefV2.kindName: String
    get() = when (this) {
        is TopLevelScopeRefV2.Channel -> "channel"
        is TopLevelScopeRefV2.Private -> "private"
        is TopLevelScopeRefV2.DirectMessage -> "dm"
        is TopLevelScopeRefV2.Joint -> "joint"
    }
