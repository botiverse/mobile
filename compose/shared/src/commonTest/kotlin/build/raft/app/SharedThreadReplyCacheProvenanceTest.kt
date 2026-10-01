package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ConversationRepository
import build.raft.app.data.SharedMessagesPayload
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.data.SlockThreadApi
import build.raft.app.data.ProcessPresenceAuthContext
import build.raft.app.data.ScreenError
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.SlockShellScopedStoreCache
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentityAuthority
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadReplyCacheIdentityTransitionAnchor
import build.raft.app.data.awaitThreadReplyCacheIdentityTransition
import build.raft.app.data.reconcileOrClearActiveShellServerSelections
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.toHomeChannelRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedThreadReplyCacheProvenanceTest {
    @Test
    fun staleVisibleParentFromAccountADoesNotSeedAccountBOnSameServer() = runBlocking {
        val authStore = InMemorySlockAuthSessionStore(session("account-a"))
        val messageStore = SharedThreadMessageStore()
        val dataSource = dataSource(authStore, messageStore)
        val accountA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        authStore.saveSession(session("account-b"))
        val accountB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val accepted =
            dataSource.rememberReplyParentCandidate(
                provenance = accountA,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1"
            )

        assertFalse(accepted)
        assertNotEquals(accountA, accountB)
        assertNull(
            messageStore.getScoped(
                requireNotNull(accountB.messageStoreScopeId),
                ChannelId("channel-1"),
                MessageId(ParentMessageId)
            )
        )
        assertNull(
            messageStore.getReplyThreadStream(
                requireNotNull(accountB.namespaceId),
                "channel-1",
                ParentMessageId
            )
        )
        assertNull(
            messageStore.getReplyThreadStream(
                requireNotNull(accountA.namespaceId),
                "channel-1",
                ParentMessageId
            )
        )
    }

    @Test
    fun markdownStartedWithAccountAFailsClosedAfterAccountBPublication() = runBlocking {
        val authStore = InMemorySlockAuthSessionStore(session("account-a"))
        val messageStore = SharedThreadMessageStore()
        val dataSource = dataSource(authStore, messageStore)
        val accountA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        var accountB: ReplyCacheProvenance? = null
        dataSource.onMarkdownReplyCacheScopeCapturedForTest = {
            authStore.saveSession(session("account-b"))
            accountB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        }

        val result =
            dataSource.markdownThreadRefLaunchPayload(
                provenance = accountA,
                currentPayload = channelPayload(),
                channelLabel = "#general",
                threadShortId = ParentMessageId.take(8)
            )

        val error = assertIs<DataResult.Error>(result)
        assertEquals("thread_reply_cache_provenance_stale", error.error.code)
        assertNull(
            messageStore.getReplyThreadStream(
                requireNotNull(requireNotNull(accountB).namespaceId),
                "channel-1",
                ParentMessageId
            )
        )
        assertNull(
            messageStore.getReplyThreadStream(
                requireNotNull(accountA.namespaceId),
                "channel-1",
                ParentMessageId
            )
        )
    }

    @Test
    fun identitySourcePublicationInterleaveProducesCompleteAccountBToken() = runBlocking {
        val authStore = InMemorySlockAuthSessionStore(session("account-a"))
        val messageStore = SharedThreadMessageStore()
        var environment = " https://prod.example.test/ "
        val dataSource =
            dataSource(
                authStore = authStore,
                messageStore = messageStore,
                environment = { environment },
            )
        val accountA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        var switched = false
        dataSource.onReplyCacheIdentitySourceCapturedForTest = {
            if (!switched) {
                switched = true
                environment = "https://staging.example.test/"
                authStore.saveSession(session("account-b"))
            }
        }

        val accountB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        assertTrue(switched)
        assertEquals("https://staging.example.test", accountB.environmentId)
        assertEquals("account-b", accountB.principalId)
        assertEquals("server-1", accountB.serverId)
        assertTrue(accountB.generation > accountA.generation)
        assertEquals(
            "https://staging.example.test\u001faccount-b\u001fserver-1",
            accountB.namespaceId
        )
    }

    @Test
    fun olderAuthorityCaptureCompletingLateCannotOverwriteNewerPublication() = runBlocking {
        val authStore = InMemorySlockAuthSessionStore(session("account-a"))
        val messageStore = SharedThreadMessageStore()
        val firstHookEntered = CompletableDeferred<Unit>()
        val releaseFirstHook = CompletableDeferred<Unit>()
        var authorityCalls = 0
        var hookCalls = 0
        val dataSource =
            dataSource(
                authStore = authStore,
                messageStore = messageStore,
                authority = {
                    authorityCalls += 1
                    val accountId = if (authorityCalls == 1 || authorityCalls == 4) "account-a" else "account-b"
                    val captured = session(accountId)
                    ThreadReplyCacheIdentitySnapshot(
                        session = captured,
                        environmentId = "https://prod.example.test",
                        principalId = accountId,
                        serverId = "server-1",
                        authGeneration = if (accountId == "account-a") 1L else 2L,
                        selectionGeneration = if (accountId == "account-a") 1L else 2L,
                    )
                },
            )
        dataSource.onReplyCacheIdentitySourceCapturedForTest = {
            hookCalls += 1
            if (hookCalls == 1) {
                firstHookEntered.complete(Unit)
                releaseFirstHook.await()
            }
        }

        val older = async { requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance }
        firstHookEntered.await()
        val newer = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        releaseFirstHook.complete(Unit)
        val lateOlder = older.await()
        val stillCurrent = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        assertEquals("account-a", lateOlder.principalId)
        assertEquals("account-b", newer.principalId)
        assertEquals(newer, stillCurrent)
        assertFalse(
            dataSource.rememberReplyParentCandidate(
                provenance = lateOlder,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1",
            )
        )
    }

    @Test
    fun sameIdentityReusesTokenAndAcceptsVisibleParentSeed() = runBlocking {
        val authStore = InMemorySlockAuthSessionStore(session("account-a"))
        val messageStore = SharedThreadMessageStore()
        val dataSource = dataSource(authStore, messageStore)
        val first = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val second = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        val accepted =
            dataSource.rememberReplyParentCandidate(
                provenance = first,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1"
            )

        assertEquals(first, second)
        assertTrue(accepted)
        assertEquals(
            "parent body",
            messageStore.getScoped(
                requireNotNull(first.messageStoreScopeId),
                ChannelId("channel-1"),
                MessageId(ParentMessageId)
            )?.body
        )
        assertEquals(
            "thread-parent-1",
            messageStore.getReplyThreadStream(
                requireNotNull(first.namespaceId),
                "channel-1",
                ParentMessageId
            )
        )
    }

    @Test
    fun localSendAcceptsOnlyAForwardTokenRotationWithinTheSameBindingAndSelection() {
        val captured =
            ReplyCacheProvenance(
                environmentId = "https://prod.example.test",
                principalId = "account-a",
                serverId = "server-1",
                generation = 41L,
                authGeneration = 7L,
                selectionGeneration = 11L,
                bindingGeneration = 3L,
            )
        val rotated = captured.copy(generation = 99L, authGeneration = 8L)

        assertTrue(rotated.isCurrentOrSuccessorForLocalSend(captured))
        assertTrue(captured.isCurrentOrSuccessorForLocalSend(captured))
        assertFalse(captured.copy(authGeneration = 6L).isCurrentOrSuccessorForLocalSend(captured))
        assertFalse(rotated.copy(principalId = "account-b").isCurrentOrSuccessorForLocalSend(captured))
        assertFalse(rotated.copy(environmentId = "https://staging.example.test").isCurrentOrSuccessorForLocalSend(captured))
        assertFalse(rotated.copy(serverId = "server-2").isCurrentOrSuccessorForLocalSend(captured))
        assertFalse(rotated.copy(selectionGeneration = 12L).isCurrentOrSuccessorForLocalSend(captured))
        assertFalse(rotated.copy(bindingGeneration = 4L).isCurrentOrSuccessorForLocalSend(captured))
    }

    @Test
    fun sameAccountAndServerWithNewAuthorityRevisionMintsFreshToken() = runBlocking {
        val authStore = InMemorySlockAuthSessionStore(session("account-a"))
        val messageStore = SharedThreadMessageStore()
        var authGeneration = 7L
        var selectionGeneration = 11L
        val dataSource =
            dataSource(
                authStore = authStore,
                messageStore = messageStore,
                authority = {
                    ThreadReplyCacheIdentitySnapshot(
                        session = requireNotNull(authStore.readSession()),
                        environmentId = "https://prod.example.test",
                        principalId = "account-a",
                        serverId = "server-1",
                        authGeneration = authGeneration,
                        selectionGeneration = selectionGeneration,
                    )
                },
            )
        val first = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        authGeneration += 1L
        selectionGeneration += 1L
        val second = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        assertNotEquals(first, second)
        assertEquals(authGeneration, second.authGeneration)
        assertEquals(selectionGeneration, second.selectionGeneration)
        assertTrue(second.generation > first.generation)
        assertFalse(
            dataSource.rememberReplyParentCandidate(
                provenance = first,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1",
            )
        )
    }

    @Test
    fun visiblePageProvenanceAdvancesAfterSameRouteAuthorityRevisionWithoutReentry() = runBlocking {
        val authStore = InMemorySlockAuthSessionStore(session("account-a"))
        val messageStore = SharedThreadMessageStore()
        var accountId = "account-a"
        var authGeneration = 7L
        var selectionGeneration = 11L
        val dataSource =
            dataSource(
                authStore = authStore,
                messageStore = messageStore,
                authority = {
                    ThreadReplyCacheIdentitySnapshot(
                        session = requireNotNull(authStore.readSession()),
                        environmentId = "https://prod.example.test",
                        principalId = accountId,
                        serverId = "server-1",
                        authGeneration = authGeneration,
                        selectionGeneration = selectionGeneration,
                    )
                },
            )
        val publishedProvenance = dataSource.replyCacheProvenanceState
        val initial = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        assertEquals(initial, publishedProvenance.value)

        authGeneration += 1L
        selectionGeneration += 1L
        // An older-page producer can be the operation that observes the new shell authority.
        val refreshedByPageLoad =
            requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        assertNotEquals(initial, refreshedByPageLoad)
        assertEquals(refreshedByPageLoad, publishedProvenance.value)
        val currentPageProvenance =
            requireNotNull(
                publishedProvenance.value.currentRevisionForReplyCacheOwner(initial)
            )
        assertFalse(
            dataSource.rememberReplyParentCandidate(
                provenance = initial,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1",
            )
        )
        assertTrue(
            dataSource.rememberReplyParentCandidate(
                provenance = currentPageProvenance,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1",
            )
        )
        assertEquals(
            "thread-parent-1",
            messageStore.getReplyThreadStream(
                requireNotNull(refreshedByPageLoad.namespaceId),
                "channel-1",
                ParentMessageId,
            )
        )

        accountId = "account-b"
        authStore.saveSession(session(accountId))
        authGeneration += 1L
        selectionGeneration += 1L
        val accountB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        assertEquals(accountB, publishedProvenance.value)
        assertNull(publishedProvenance.value.currentRevisionForReplyCacheOwner(initial))
    }

    @Test
    fun canonicalConversationDetailPublishesWithCurrentReplyCacheProvenance() = runBlocking {
        val detail = directMessageDetail("Cindy", "pixel:cindy")
        val repository = canonicalConversationRepository(detail)
        val published = mutableListOf<Pair<ReplyCacheProvenance, ConversationSummary>>()
        val dataSource =
            dataSource(
                authStore = InMemorySlockAuthSessionStore(session("account-a")),
                messageStore = SharedThreadMessageStore(),
                conversationRepository = repository,
                canonicalConversationIdentityPublisher = { provenance, canonical ->
                    published += provenance to canonical
                    true
                },
            )

        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(
            dataSource.fetchThreadPayload(directMessagePayload(), persistResult = false)
        )

        assertEquals(1, published.size)
        assertEquals("https://prod.example.test", published.single().first.environmentId)
        assertEquals("account-a", published.single().first.principalId)
        assertEquals("server-1", published.single().first.serverId)
        assertEquals("Cindy", published.single().second.name)
        assertEquals("pixel:cindy", published.single().second.directMessagePeerAvatarWireValue)
    }

    @Test
    fun lateCanonicalConversationDetailFromRetiredReplyCacheScopeFailsClosedAndIsNotPublished() = runBlocking {
        val authStore = InMemorySlockAuthSessionStore(session("account-a"))
        val release = CompletableDeferred<Unit>()
        val repository =
            canonicalConversationRepository(directMessageDetail("Late Cindy", "pixel:late"))
                .also { it.releaseThread = release }
        val published = mutableListOf<Pair<ReplyCacheProvenance, ConversationSummary>>()
        val dataSource =
            dataSource(
                authStore = authStore,
                messageStore = SharedThreadMessageStore(),
                conversationRepository = repository,
                canonicalConversationIdentityPublisher = { provenance, canonical ->
                    published += provenance to canonical
                    true
                },
            )
        val request =
            async {
                dataSource.fetchThreadPayload(directMessagePayload(), persistResult = false)
            }
        repository.threadStarted.await()
        authStore.saveSession(session("account-b"))
        assertEquals("account-b", dataSource.captureReplyCacheProducerContext()?.provenance?.principalId)
        release.complete(Unit)

        val result = assertIs<DataResult.Error>(request.await())
        assertEquals("thread_reply_cache_provenance_stale", result.error.code)
        assertEquals(false, result.error.retryable)
        assertEquals(emptyList(), published)
    }

    @Test
    fun stableEnvironmentBSessionAHybridMintsNoTokenAndWritesNothing() = runBlocking {
        val authStore = InMemorySlockAuthSessionStore(session("account-a"))
        val selectedServerStore =
            SelectedServerStore(SlockShellStoreScope.create("environment-hybrid", "server-1"))
        val authContext =
            MutableStateFlow(
                ProcessPresenceAuthContext(
                    environmentId = "https://prod.example.test",
                    accountId = "account-a",
                    authorityGeneration = 7L,
                    writeGeneration = 3L,
                )
            )
        var environmentId = "https://prod.example.test"
        val authority =
            ThreadReplyCacheIdentityAuthority(
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                authContextProvider = { authContext.value },
                environmentIdProvider = { environmentId },
            )
        val messageStore = SharedThreadMessageStore()
        val dataSource =
            dataSource(
                authStore = authStore,
                messageStore = messageStore,
                authority = authority::capture,
                transitionWaiter = { transitionAnchor ->
                    awaitThreadReplyCacheIdentityTransition(
                        authContext = authContext,
                        sessionStore = authStore,
                        selectedServerStore = selectedServerStore,
                        transitionAnchor = transitionAnchor,
                        environmentIdProvider = { environmentId },
                    )
                },
            )
        val valid = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        // Environment publication leads session clearing. Holding the production gap stable across
        // both samples must not bind account A to environment B.
        environmentId = "https://staging.example.test"
        assertNull(dataSource.captureReplyCacheProducerContext())
        assertFalse(
            dataSource.rememberReplyParentCandidate(
                provenance = valid,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1",
            )
        )
        assertNull(
            messageStore.getScoped(
                requireNotNull(valid.messageStoreScopeId),
                ChannelId("channel-1"),
                MessageId(ParentMessageId),
            )
        )
        assertNull(
            messageStore.getReplyThreadStream(
                requireNotNull(valid.namespaceId),
                "channel-1",
                ParentMessageId,
            )
        )
    }

    @Test
    fun stablePrincipalASelectedServerOwnerBHybridMintsNoTokenAndWritesNothing() = runBlocking {
        val accountASession = session("account-a")
        val authStore = InMemorySlockAuthSessionStore(accountASession)
        val selectedServerStore =
            SelectedServerStore(SlockShellStoreScope.create("selection-hybrid", "server-1"))
        val authContext =
            MutableStateFlow(
                ProcessPresenceAuthContext(
                    environmentId = "https://prod.example.test",
                    accountId = "account-a",
                    authorityGeneration = 11L,
                    writeGeneration = 5L,
                )
            )
        val authority =
            ThreadReplyCacheIdentityAuthority(
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                authContextProvider = { authContext.value },
                environmentIdProvider = { "https://prod.example.test" },
            )
        val messageStore = SharedThreadMessageStore()
        val dataSource =
            dataSource(
                authStore = authStore,
                messageStore = messageStore,
                authority = authority::capture,
                transitionWaiter = { transitionAnchor ->
                    awaitThreadReplyCacheIdentityTransition(
                        authContext = authContext,
                        sessionStore = authStore,
                        selectedServerStore = selectedServerStore,
                        transitionAnchor = transitionAnchor,
                        environmentIdProvider = { "https://prod.example.test" },
                    )
                },
            )
        val valid = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        selectedServerStore.reconcile(session("account-b"))
        assertNull(dataSource.captureReplyCacheProducerContext())
        assertFalse(
            dataSource.rememberReplyParentCandidate(
                provenance = valid,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1",
            )
        )
        assertNull(
            messageStore.getScoped(
                requireNotNull(valid.messageStoreScopeId),
                ChannelId("channel-1"),
                MessageId(ParentMessageId),
            )
        )
        assertNull(
            messageStore.getReplyThreadStream(
                requireNotNull(valid.namespaceId),
                "channel-1",
                ParentMessageId,
            )
        )
    }

    @Test
    fun missingSessionOrContextCannotUseRetiredAnchorToRepairAuthority() = runBlocking {
        val accountA = session("account-a")
        val authStore = InMemorySlockAuthSessionStore(accountA)
        val selectedServerStore =
            SelectedServerStore(SlockShellStoreScope.create("missing-authority", "server-1"))
        val authContext =
            MutableStateFlow<ProcessPresenceAuthContext?>(
                ProcessPresenceAuthContext(
                    environmentId = "https://prod.example.test",
                    accountId = "account-a",
                    authorityGeneration = 15L,
                    writeGeneration = 7L,
                )
            )
        val authority =
            ThreadReplyCacheIdentityAuthority(
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                authContextProvider = { authContext.value },
                environmentIdProvider = { "https://prod.example.test" },
            )
        val dataSource =
            dataSource(
                authStore = authStore,
                messageStore = SharedThreadMessageStore(),
                authority = authority::capture,
                transitionWaiter = { transitionAnchor ->
                    awaitThreadReplyCacheIdentityTransition(
                        authContext = authContext,
                        sessionStore = authStore,
                        selectedServerStore = selectedServerStore,
                        transitionAnchor = transitionAnchor,
                        environmentIdProvider = { "https://prod.example.test" },
                    )
                },
            )
        val beforeMissingSession = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        authStore.clearSession(reason = "test", source = "test")
        assertNull(dataSource.captureReplyCacheProducerContext())
        assertFalse(
            dataSource.rememberReplyParentCandidate(
                provenance = beforeMissingSession,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1",
            )
        )

        authStore.saveSession(accountA)
        val beforeMissingContext = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        authContext.value = null
        assertNull(dataSource.captureReplyCacheProducerContext())
        assertFalse(
            dataSource.rememberReplyParentCandidate(
                provenance = beforeMissingContext,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1",
            )
        )
    }

    @Test
    fun globalAccountBWaitsForContextAndRouteOwnerBeforeResumingSameInitialFetch() = runBlocking {
        val authStore = InMemorySlockAuthSessionStore(session("account-a"))
        val selectedServerStore =
            SelectedServerStore(SlockShellStoreScope.create("auth-revision-lag", "server-1"))
        val authContext =
            MutableStateFlow(
                ProcessPresenceAuthContext(
                    environmentId = "https://prod.example.test",
                    accountId = "account-a",
                    authorityGeneration = 17L,
                    writeGeneration = 9L,
                )
            )
        val authority =
            ThreadReplyCacheIdentityAuthority(
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                authContextProvider = { authContext.value },
                environmentIdProvider = { "https://prod.example.test" },
            )
        val waiterEntered = CompletableDeferred<Unit>()
        val routeOwnerGapEntered = CompletableDeferred<Unit>()
        val releaseRouteOwnerGap = CompletableDeferred<Unit>()
        val messageStore = SharedThreadMessageStore()
        val dataSource =
            dataSource(
                authStore = authStore,
                messageStore = messageStore,
                authority = authority::capture,
                transitionWaiter = { transitionAnchor ->
                    waiterEntered.complete(Unit)
                    awaitThreadReplyCacheIdentityTransition(
                        authContext = authContext,
                        sessionStore = authStore,
                        selectedServerStore = selectedServerStore,
                        transitionAnchor = transitionAnchor,
                        environmentIdProvider = { "https://prod.example.test" },
                        beforeSelectedServerReconcile = {
                            routeOwnerGapEntered.complete(Unit)
                            releaseRouteOwnerGap.await()
                        },
                    )
                },
            )
        val accountA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance

        // The process-global OAuth path commits B without passing through the scoped auth-store
        // ordering. Context and selected-server owner are both still A when the mounted fetch starts.
        authStore.saveSession(session("account-b"))
        val initialFetch = async { dataSource.fetchThreadPayload(channelPayload()) }
        waiterEntered.await()

        assertFalse(initialFetch.isCompleted)
        assertFalse(
            dataSource.rememberReplyParentCandidate(
                provenance = accountA,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1",
            )
        )
        assertNull(
            messageStore.getScoped(
                requireNotNull(accountA.messageStoreScopeId),
                ChannelId("channel-1"),
                MessageId(ParentMessageId),
            )
        )
        assertNull(
            messageStore.getReplyThreadStream(
                requireNotNull(accountA.namespaceId),
                "channel-1",
                ParentMessageId,
            )
        )
        assertNull(
            messageStore.getReplyThreadStream(
                "https://prod.example.test\u001faccount-b\u001fserver-1",
                "channel-1",
                ParentMessageId,
            )
        )

        authContext.value =
            ProcessPresenceAuthContext(
                environmentId = "https://prod.example.test",
                accountId = "account-b",
                authorityGeneration = 18L,
                writeGeneration = 10L,
            )
        routeOwnerGapEntered.await()

        // Context/session now agree on B while the selected-server authority is still owned by A.
        // Capture and every old/new cache namespace remain zero until the provider transition owns B.
        assertFalse(initialFetch.isCompleted)
        assertNull(authority.capture())
        assertNull(
            messageStore.getScoped(
                requireNotNull(accountA.messageStoreScopeId),
                ChannelId("channel-1"),
                MessageId(ParentMessageId),
            )
        )
        assertNull(
            messageStore.getReplyThreadStream(
                "https://prod.example.test\u001faccount-b\u001fserver-1",
                "channel-1",
                ParentMessageId,
            )
        )

        releaseRouteOwnerGap.complete(Unit)
        val resumedFetchError = assertIs<DataResult.Error>(initialFetch.await())
        assertNotEquals("auth_session_required", resumedFetchError.error.code)
        val accountB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        assertEquals("account-b", accountB.principalId)
        assertNotEquals(accountA, accountB)
        assertTrue(
            dataSource.rememberReplyParentCandidate(
                provenance = accountB,
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessage = parentMessage(),
                threadChannelId = "thread-parent-1",
            )
        )
        assertIs<DataResult.Content<*>>(
            dataSource.markdownThreadRefLaunchPayload(
                provenance = accountB,
                currentPayload = channelPayload(),
                channelLabel = "#general",
                threadShortId = ParentMessageId.take(8),
            )
        )
        Unit
    }

    @Test
    fun queuedClearedAfterGlobalLoginReconcilesNonNullSessionInsteadOfLeavingOwnerA() = runBlocking {
        val accountA = session("account-a")
        val accountB = session("account-b")
        val authStore = InMemorySlockAuthSessionStore(accountA)
        val scope = SlockShellStoreScope.create("queued-cleared-after-login", "server-1")
        val selectedServerStores = SlockShellScopedStoreCache<SelectedServerStore>()
        val selectedServerStore =
            selectedServerStores.getOrPut(scope) { SelectedServerStore(scope) }
        val authContext =
            MutableStateFlow(
                ProcessPresenceAuthContext(
                    environmentId = "https://prod.example.test",
                    accountId = "account-a",
                    authorityGeneration = 21L,
                    writeGeneration = 13L,
                )
            )
        val authority =
            ThreadReplyCacheIdentityAuthority(
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                authContextProvider = { authContext.value },
                environmentIdProvider = { "https://prod.example.test" },
            )
        val snapshotA = requireNotNull(authority.capture())

        // A queued Cleared delivery reaches the helper only after global login B has committed.
        // Its first session read is non-null B, so it must reconcile every live shell instead of
        // returning with the old A owner or clearing B's selection.
        authStore.saveSession(accountB)
        authContext.value =
            ProcessPresenceAuthContext(
                environmentId = "https://prod.example.test",
                accountId = "account-b",
                authorityGeneration = 22L,
                writeGeneration = 14L,
            )
        reconcileOrClearActiveShellServerSelections(
            authStore = authStore,
            selectedServerStores = selectedServerStores,
        )

        val snapshotB = requireNotNull(authority.capture())
        assertEquals("account-b", snapshotB.principalId)
        assertEquals("server-1", snapshotB.serverId)
        assertTrue(snapshotB.selectionGeneration > snapshotA.selectionGeneration)
    }

    private fun dataSource(
        authStore: InMemorySlockAuthSessionStore,
        messageStore: SharedThreadMessageStore,
        environment: () -> String = { "https://prod.example.test" },
        authority: (suspend () -> ThreadReplyCacheIdentitySnapshot?)? = null,
        transitionWaiter:
            (suspend (ThreadReplyCacheIdentityTransitionAnchor?) -> Boolean)? = null,
        conversationRepository: ConversationRepository? = null,
        canonicalConversationIdentityPublisher:
            suspend (ReplyCacheProvenance, ConversationSummary) -> Boolean = { _, _ -> false },
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            api = UnusedThreadApi,
            sessionStore = authStore,
            messageStore = messageStore,
            conversationRepository = conversationRepository,
            cacheEnvironmentIdProvider = environment,
            replyCacheIdentityAuthority = authority ?: {
                authStore.readSession()?.let { session ->
                    ThreadReplyCacheIdentitySnapshot(
                        session = session,
                        environmentId = environment().trim().trimEnd('/'),
                        principalId = session.user.id.trim(),
                        serverId = session.currentServerId.orEmpty(),
                        // A live session always carries a registry generation >= 1; 0 means "no session".
                        authGeneration = 1L,
                        selectionGeneration = 0L,
                    )
                }
            },
            replyCacheIdentityTransitionWaiter = transitionWaiter,
            canonicalConversationIdentityPublisher = canonicalConversationIdentityPublisher,
            homeSnapshotProvider = { homeSnapshot() }
        )

    private fun canonicalConversationRepository(
        detail: ConversationSummary,
    ): ReplyThreadAccessConversationRepositoryRecorder =
        ReplyThreadAccessConversationRepositoryRecorder(
            replyResult = DataResult.Error(ScreenError("unused", retryable = false)),
            threadResult =
                DataResult.Content(
                    ThreadSnapshot(
                        conversation = detail,
                        canonicalConversationDetail = detail,
                        messages = emptyList(),
                    )
                ),
        )

    private fun directMessageDetail(name: String, avatar: String): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            kind = ConversationKind.DirectMessage,
            name = name,
            directMessagePeerType = "human",
            directMessagePeerAvatarWireValue = avatar,
            presenceMemberId = MemberId("peer-1"),
        )

    private fun directMessagePayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            status = "loading",
            surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
            channelKind = KuiklyThreadSurfaceKind.DirectMessage,
            sourceThreadId = "dm-1",
            streamChannelId = "dm-1",
        )

    private fun channelPayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            status = "content",
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
            channelKind = KuiklyThreadSurfaceKind.Channel,
            sourceThreadId = "channel-1",
            streamChannelId = "channel-1",
            messages = listOf(parentMessage())
        )

    private fun parentMessage(): KuiklyThreadMessageItem =
        KuiklyThreadMessageItem(
            id = ParentMessageId,
            authorId = "member-1",
            authorName = "Member",
            authorKind = "human",
            seq = 1,
            body = "parent body",
            threadChannelId = "thread-parent-1",
            replyCount = 1
        )

    private fun homeSnapshot(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Server"),
            currentMember =
                MemberProfile(
                    id = MemberId("member-1"),
                    displayName = "Member",
                    handle = "member",
                    kind = MemberKind.Human
                ),
            channels =
                listOf(
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )

    private fun session(accountId: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = accountId, email = "$accountId@example.test", displayName = accountId),
            accessToken = "access-$accountId",
            refreshToken = "refresh-$accountId",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Server", slug = "server"))
        )

    private companion object {
        const val ParentMessageId = "5fbf9608-1111-2222-3333-444444444444"

        val UnusedThreadApi =
            object : SlockThreadApi {
                private fun <T> unused(): ThreadApiResult<T> = ThreadApiResult.Failure(500)

                override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?, afterSeq: Long?, requestTrace: String?): ThreadApiResult<ThreadMessagesPage> = unused()
                override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int, requestTrace: String?): ThreadApiResult<ThreadMessagesPage> = unused()
                override suspend fun sendMessage(channelId: String, body: String, asTask: Boolean, attachmentIds: List<String>, randomId: String?): ThreadApiResult<ThreadMessagesPage> = unused()
                override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): ThreadApiResult<List<PendingMentionActionExecuteResult>> = unused()
                override suspend fun attachmentUrl(attachmentId: String, disposition: String?): ThreadApiResult<ThreadAttachmentOpenTarget> = unused()
                override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget> = unused()
                override suspend fun attachmentComments(attachmentId: String): ThreadApiResult<ThreadAttachmentCommentsPage> = unused()
                override suspend fun sendAttachmentComment(attachmentId: String, body: String): ThreadApiResult<Unit> = unused()
                override suspend fun markChannelReadToSeq(channelId: String, seq: Long): ThreadApiResult<Unit> = unused()
                override suspend fun markChannelReadAll(channelId: String): ThreadApiResult<Unit> = unused()
                override suspend fun saveMessage(messageId: String): ThreadApiResult<Unit> = unused()
                override suspend fun unsaveMessage(messageId: String): ThreadApiResult<Unit> = unused()
                override suspend fun addReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary> = unused()
                override suspend fun removeReaction(messageId: String, emoji: String): ThreadApiResult<MessageMutationSummary> = unused()
                override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): ThreadApiResult<ThreadActionResult> = unused()
                override suspend fun messageContext(messageId: String, channelId: String?): ThreadApiResult<SharedMessagesPayload> = unused()
            }
    }
}
