package build.raft.app.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthSessionStoreTest {
    @Test
    fun authInstallationIdentityPersistsAcrossStoreInstancesAndAccounts() = runBlocking {
        val keyValue = InMemoryRaftKeyValueStore()
        val first = KeyValueAuthSessionStore(keyValue)
        val firstId = first.readOrCreateAuthInstallationId("ari_00000000000000000000000000000001")
        first.replaceAuthenticatedSession(sampleSession())
        first.clearSession(reason = "switch", source = "test")
        val restarted = KeyValueAuthSessionStore(keyValue)

        val readBack = restarted.readOrCreateAuthInstallationId("ari_ffffffffffffffffffffffffffffffff")

        assertEquals(firstId, readBack)
        assertEquals("ari_00000000000000000000000000000001", readBack)
    }

    @Test
    fun durableAttemptAndSuccessorCommitShareOneSessionRecord() = runBlocking {
        val keyValue = InMemoryRaftKeyValueStore()
        val store = KeyValueAuthSessionStore(keyValue)
        val original = sampleSession()
        store.replaceAuthenticatedSession(original)
        val attempt =
            StoredAuthRefreshAttempt(
                attemptId = "arf_0000000000000010",
                installationId = "ari_00000000000000000000000000000011",
                accountId = original.user.id,
                predecessorAccessToken = original.accessToken,
                predecessorRefreshToken = original.refreshToken,
                createdAtMillis = 1L,
            )

        assertEquals(
            attempt,
            store.prepareRefreshAttemptIfMatch(
                expectedAccountId = original.user.id,
                expectedAccessToken = original.accessToken,
                expectedRefreshToken = original.refreshToken,
                candidate = attempt,
            ),
        )
        val processRestart = KeyValueAuthSessionStore(keyValue)
        assertEquals(attempt, processRestart.readSession()?.refreshAttempt)

        val successor =
            processRestart.rotateTokensAndClearRefreshAttemptIfMatch(
                expectedAccountId = original.user.id,
                expectedAccessToken = original.accessToken,
                expectedRefreshToken = original.refreshToken,
                expectedAttemptId = attempt.attemptId,
                newAccessToken = "successor-access",
                newRefreshToken = "successor-refresh",
            )

        assertEquals("successor-access", successor?.accessToken)
        assertNull(successor?.refreshAttempt)
        val secondRestart = KeyValueAuthSessionStore(keyValue)
        assertEquals("successor-refresh", secondRestart.readSession()?.refreshToken)
        assertNull(secondRestart.readSession()?.refreshAttempt)
    }
    @Test
    fun serverPermissionProjectionMatchesMachineManagementCapability() {
        fun permissions(role: String?) =
            StoredAuthServer(id = "server-1", name = "Server One", role = role)
                .toServerSummary()
                .permissions

        assertTrue(permissions("owner").canManageMachines)
        assertTrue(permissions("owner").canManageSettings)
        assertTrue(permissions("admin").canManageMachines)
        assertTrue(permissions("admin").canManageSettings)
        assertFalse(permissions("member").canManageMachines)
        assertFalse(permissions("member").canManageSettings)
        assertFalse(permissions("ADMIN").canManageMachines)
        assertFalse(permissions("ADMIN").canManageSettings)
        assertFalse(permissions(" owner ").canManageMachines)
        assertFalse(permissions(" owner ").canManageSettings)
        assertFalse(permissions(null).canManageMachines)
        assertFalse(permissions("unknown").canManageMachines)
    }

    @Test
    fun serverSummaryCarriesHumanDirectoryPolicy() {
        val summary =
            StoredAuthServer(
                id = "server-1",
                name = "Server One",
                role = "member",
                hideHumansFromMembers = true,
            ).toServerSummary()

        assertTrue(summary.hideHumansFromMembers)
        assertEquals("member", summary.role)
    }

    @Test
    fun storedSessionExposesSharedSnapshotAndAuthTokens() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(sampleSession())

        val session = store.readSession()

        assertEquals("token-a", session?.toAuthTokens()?.accessToken)
        assertEquals("server-1", session?.toAuthTokens()?.selectedServerId)
        assertEquals("server-1", session?.toSessionSnapshot()?.currentServerId?.value)
        assertEquals("Artin", session?.toSessionSnapshot()?.currentMember?.displayName)
    }

    @Test
    fun updateSelectedServerAndClearSession() = runBlocking {
        val store = InMemoryRaftAuthSessionStore(sampleSession())

        store.updateSelectedServer("server-2", "work")
        assertEquals("server-2", store.readSession()?.currentServerId)
        assertEquals(
            StoredAuthServerSelection(serverId = "server-2", serverSlug = "work"),
            store.readRememberedServerSelection(sampleSession().user)
        )

        store.clearSession()
        assertNull(store.readSession())
        assertEquals(
            StoredAuthServerSelection(serverId = "server-2", serverSlug = "work"),
            store.readRememberedServerSelection(sampleSession().user)
        )
    }

    @Test
    fun mmkvAuthSessionStorePersistsThroughAuthNamespaceOnly() = runBlocking {
        val keyValueStore = InMemoryRaftKeyValueStore()
        val logs = mutableListOf<String>()
        val criticalLogs = mutableListOf<String>()
        val store =
            KeyValueAuthSessionStore(
                keyValueStore,
                diagnosticsLogger = logs::add,
                criticalDiagnosticsLogger = criticalLogs::add
            )

        store.saveSession(sampleSession())
        assertEquals("server-1", store.readSession()?.currentServerId)

        store.updateSelectedServer("server-2", "work")
        assertEquals("server-2", store.readSession()?.currentServerId)
        assertEquals(
            StoredAuthServerSelection(serverId = "server-2", serverSlug = "work"),
            store.readRememberedServerSelection(sampleSession().user)
        )

        store.clearSession(reason = "api environment changed!", source = "settings store")
        assertNull(store.readSession())
        assertTrue(
            criticalLogs.any {
                it == "auth_session_clear reason=api_environment_changed_ source=settings_store hadSession=true"
            }
        )
        assertFalse(logs.any { it.startsWith("auth_session_clear ") })
        assertEquals(
            StoredAuthServerSelection(serverId = "server-2", serverSlug = "work"),
            store.readRememberedServerSelection(sampleSession().user)
        )
    }

    @Test
    fun qaBootstrapMonotonicBoundariesPersistAsDecimalStringsBeyondJavascriptSafeInteger() = runBlocking {
        val keyValueStore = InMemoryRaftKeyValueStore()
        val store = KeyValueAuthSessionStore(keyValueStore)
        val session =
            sampleSession().copy(
                qaBootstrap =
                    StoredQaBootstrapMetadata(
                        ticketDigest = "sha256:ticket",
                        runCaseId = "run-1",
                        attempt = 1,
                        ciJobRef = "job-1",
                        principalId = "user-1",
                        serverId = "server-1",
                        sessionGeneration = "generation-1",
                        targetRoute = "compose_slock_app_shell",
                        applicationId = "build.raft.app.alpha",
                        signingCertSha256 = "sha256:cert",
                        deviceBootGeneration = "sha256:boot",
                        runtimeEnvelope =
                            StoredQaBootstrapRuntimeEnvelope(
                                envelopeDigest = "sha256:envelope",
                                readyReceiptDigest = "sha256:receipt",
                                pid = 42,
                                processInstanceId = "process-1",
                                processGeneration = "9007199254740992",
                                deviceBootGeneration = "sha256:boot",
                                deviceReadyElapsedRealtimeNanos = "9007199254740993",
                                routeReadyProofDigest = "sha256:route"
                            )
                    )
            )

        store.replaceAuthenticatedSession(session)

        val encoded = keyValueStore.getString(RAFT_AUTH_SESSION_RECORD_KEY).orEmpty()
        assertContains(encoded, "\"processGeneration\":\"9007199254740992\"")
        assertContains(encoded, "\"deviceReadyElapsedRealtimeNanos\":\"9007199254740993\"")
        assertEquals("9007199254740993", store.readSession()?.qaBootstrap?.runtimeEnvelope?.deviceReadyElapsedRealtimeNanos)
    }

    @Test
    fun successfulSessionReadsUseDecodedProcessCache() = runBlocking {
        val keyValueStore = CountingRaftKeyValueStore()
        val logs = mutableListOf<String>()
        val store = KeyValueAuthSessionStore(keyValueStore, diagnosticsLogger = logs::add)
        val secondStore = KeyValueAuthSessionStore(keyValueStore, diagnosticsLogger = logs::add)

        store.saveSession(sampleSession())
        logs.clear()
        keyValueStore.readCount = 0

        assertEquals("server-1", store.readSession()?.currentServerId)
        assertEquals("server-1", secondStore.readSession()?.currentServerId)
        assertEquals(0, keyValueStore.readCount)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun mmkvAuthSessionStoreBroadcastsChangedSessionFingerprint() = runBlocking {
        val keyValueStore = InMemoryRaftKeyValueStore()
        val store = KeyValueAuthSessionStore(keyValueStore)
        val firstChange = async(start = CoroutineStart.UNDISPATCHED) { store.sessionChanges.first() }

        store.saveSession(sampleSession())

        assertEquals(sampleSession().authSessionClearFingerprint(), firstChange.await())

        val duplicateChange = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(50) { store.sessionChanges.first() } }
        store.saveSession(sampleSession())
        assertNull(duplicateChange.await())

        val selectedServerChange = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(50) { store.sessionChanges.first() } }
        store.updateSelectedServer("server-2", "work")
        assertNull(selectedServerChange.await())

        val tokenChange = async(start = CoroutineStart.UNDISPATCHED) { store.sessionChanges.first() }
        store.updateTokens("token-b", "token-b-r")
        assertEquals(store.readSession()?.authSessionClearFingerprint(), tokenChange.await())
    }

    @Test
    fun authSessionFingerprintIgnoresSelectedServer() {
        val session = sampleSession()

        assertEquals(
            session.authSessionClearFingerprint(),
            session
                .copy(selectedServerId = "server-2", selectedServerSlug = "work")
                .authSessionClearFingerprint()
        )
    }

    @Test
    fun mmkvAuthSessionStoreClearsLocalSessionWithoutPeerNotify() = runBlocking {
        val keyValueStore = InMemoryRaftKeyValueStore()
        val store = KeyValueAuthSessionStore(keyValueStore)
        val staleSession = sampleSession()
        val staleFingerprint = staleSession.authSessionClearFingerprint()

        store.saveSession(staleSession)
        store.clearSession()
        assertNull(store.readSession())

        assertTrue(
            store.clearSessionFromSessionClearedNotify(
                reason = "remote",
                previousSessionFingerprint = staleFingerprint
            )
        )
        assertNull(store.readSession())

        store.saveSession(staleSession)
        assertTrue(
            store.clearSessionFromSessionClearedNotify(
                reason = "remote",
                previousSessionFingerprint = staleFingerprint
            )
        )
        assertNull(store.readSession())
    }

    @Test
    fun mmkvAuthSessionStoreSkipsStaleRemoteClearAfterNewLogin() = runBlocking {
        val keyValueStore = InMemoryRaftKeyValueStore()
        val store = KeyValueAuthSessionStore(keyValueStore)
        val staleSession = sampleSession()
        val freshSession = sampleSession().copy(accessToken = "token-new", refreshToken = "token-new-r")
        val staleFingerprint = staleSession.authSessionClearFingerprint()

        store.saveSession(staleSession)
        store.clearSession()
        store.saveSession(freshSession)

        assertFalse(
            store.clearSessionFromSessionClearedNotify(
                reason = "remote",
                previousSessionFingerprint = staleFingerprint
            )
        )
        assertEquals("token-new", store.readSession()?.accessToken)
    }

    // --- #466 auth-restore lost-update regressions ---

    @Test
    fun updateMetadataDoesNotClobberConcurrentTokenRotation() = runBlocking {
        // Reproduces #466: a metadata writer (currentUserWithSession) captured the OLD session,
        // the socket coordinator rotated the token in between, and the metadata write then wrote
        // the captured OLD token back — clobbering the successor. The fix: metadata writes go
        // through updateMetadata, which reads latest under lock and never touches tokens.
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        store.replaceAuthenticatedSession(sampleSession()) // access=token-a / refresh=token-r

        val captured = store.readSession()!! // metadata writer captures the OLD session
        assertEquals("token-a", captured.accessToken)

        // socket coordinator rotates to the successor; CAS returns the newly-persisted session
        val rotated = store.rotateTokensIfMatch("token-a", "token-r", "token-successor", "token-successor-r")
        assertEquals("token-successor", rotated?.accessToken)
        assertEquals("token-successor", store.readSession()?.accessToken)

        // metadata writer commits its (now stale-token) capture as a metadata-only update
        store.updateMetadata(expectedAccountId = captured.user.id) { latest ->
            latest.copy(user = captured.user.copy(displayName = "Renamed"))
        }

        val final = store.readSession()!!
        assertEquals("token-successor", final.accessToken) // successor preserved, NOT clobbered
        assertEquals("token-successor-r", final.refreshToken)
        assertEquals("Renamed", final.user.displayName) // metadata applied
    }

    @Test
    fun qaBootstrapBindingCasRejectsSameAccountSuccessorAttemptAndPreservesPendingState() = runBlocking {
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        val attemptA = sampleSession().copy(qaBootstrap = sampleQaBootstrap(attempt = 1))
        store.replaceAuthenticatedSession(attemptA)
        val capturedA = requireNotNull(store.readSession()?.qaBootstrap)

        val attemptB =
            attemptA.copy(
                qaBootstrap = sampleQaBootstrap(attempt = 2),
                qaBootstrapLaunchAuthorized = false,
            )
        store.replaceAuthenticatedSession(attemptB)

        val applied =
            store.updateQaBootstrapIfBindingMatches(capturedA) { latest ->
                latest.copy(
                    qaBootstrap =
                        requireNotNull(latest.qaBootstrap).copy(
                            state = "ready",
                            readyReceipt = StoredQaBootstrapReadyReceipt("receipt-a", "digest-a", "v1", "now"),
                        )
                )
            }

        assertNull(applied)
        val final = requireNotNull(store.readSession())
        assertEquals(2, final.qaBootstrap?.attempt)
        assertEquals("pending", final.qaBootstrap?.state)
        assertNull(final.qaBootstrap?.readyReceipt)
    }

    @Test
    fun qaBootstrapBindingCasUsesLatestRotatedTokensForSameBinding() = runBlocking {
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        store.replaceAuthenticatedSession(sampleSession().copy(qaBootstrap = sampleQaBootstrap(attempt = 1)))
        val captured = requireNotNull(store.readSession()?.qaBootstrap)
        store.rotateTokensIfMatch("token-a", "token-r", "token-successor", "token-successor-r")

        val applied =
            store.updateQaBootstrapIfBindingMatches(captured) { latest ->
                latest.copy(qaBootstrap = requireNotNull(latest.qaBootstrap).copy(state = "ready"))
            }

        assertEquals("token-successor", applied?.accessToken)
        assertEquals("token-successor-r", applied?.refreshToken)
        assertEquals("ready", store.readSession()?.qaBootstrap?.state)
    }

    @Test
    fun refreshOnlyRotationIsPreservedAgainstStaleMetadata() = runBlocking {
        // #496 (Artea Xiaomi, 2nd device): android_socket rotated only the REFRESH (access unchanged),
        // then a stale metadata write must not revert it. Mirrors the Xiaomi trace exactly.
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        store.replaceAuthenticatedSession(sampleSession()) // access=token-a / refresh=token-r
        val captured = store.readSession()!!

        // refresh-only rotation: access stays token-a, refresh token-r -> token-r2
        val rotated = store.rotateTokensIfMatch("token-a", "token-r", "token-a", "token-r2")
        assertEquals("token-r2", rotated?.refreshToken)
        assertEquals("token-a", store.readSession()?.accessToken)
        assertEquals("token-r2", store.readSession()?.refreshToken)

        store.updateMetadata(expectedAccountId = captured.user.id) { latest ->
            latest.copy(user = captured.user.copy(displayName = "Renamed"))
        }

        val final = store.readSession()!!
        assertEquals("token-a", final.accessToken)
        assertEquals("token-r2", final.refreshToken) // refresh successor preserved
        assertEquals("Renamed", final.user.displayName)
    }

    @Test
    fun updateMetadataSkipsWhenAccountSwitched() = runBlocking {
        // #466 account-generation guard: after a metadata writer captures account A, a login replaces
        // the session with account B; A's metadata write must NOT land on B.
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        store.replaceAuthenticatedSession(sampleSession()) // account user-1
        val capturedA = store.readSession()!!

        // account switch: B is a different account
        val accountB = sampleSession().copy(
            user = StoredAuthUser(id = "user-2", email = "b@slock.ai", displayName = "B"),
            accessToken = "b-access",
            refreshToken = "b-refresh",
        )
        store.replaceAuthenticatedSession(accountB)

        // A's stale metadata write (expected account = user-1) is a no-op on B (user-2)
        store.updateMetadata(expectedAccountId = capturedA.user.id) { latest ->
            latest.copy(user = capturedA.user.copy(displayName = "A-Renamed"))
        }

        val final = store.readSession()!!
        assertEquals("user-2", final.user.id) // still account B
        assertEquals("B", final.user.displayName) // A's metadata did NOT land
        assertEquals("b-access", final.accessToken)
    }

    @Test
    fun sessionWritesSerializeAcrossStoreInstancesUnderConcurrency() = runBlocking {
        // The serialization lock is shared across KeyValueAuthSessionStore INSTANCES backing the
        // same store, so concurrent rotate (instance b) + metadata write (instance a) can never
        // lose the rotation regardless of interleaving/instance identity.
        val kv = InMemoryRaftKeyValueStore()
        val a = KeyValueAuthSessionStore(kv)
        val b = KeyValueAuthSessionStore(kv)
        a.replaceAuthenticatedSession(sampleSession())
        val captured = a.readSession()!!

        val rotate = async { b.rotateTokensIfMatch("token-a", "token-r", "token-successor", "token-successor-r") }
        val meta = async {
            a.updateMetadata(expectedAccountId = captured.user.id) { latest ->
                latest.copy(user = captured.user.copy(displayName = "Renamed"))
            }
        }
        rotate.await()
        meta.await()

        // Either order is safe: metadata never changes tokens, so rotate's CAS still matches; and
        // metadata-after-rotate reads the successor. Final token is always the successor, never old.
        assertEquals("token-successor", b.readSession()?.accessToken)
        assertEquals("Renamed", b.readSession()?.user?.displayName)
    }

    @Test
    fun updateMetadataFailsClosedOnTokenChange() = runBlocking {
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        store.replaceAuthenticatedSession(sampleSession())
        assertFailsWith<IllegalArgumentException> {
            store.updateMetadata(expectedAccountId = null) { latest -> latest.copy(accessToken = "sneaky") }
        }
        assertEquals("token-a", store.readSession()?.accessToken) // unchanged
    }

    @Test
    fun rotateTokensIfMatchNoOpsOnStaleExpected() = runBlocking {
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        store.replaceAuthenticatedSession(sampleSession())
        assertNull(store.rotateTokensIfMatch("stale-a", "stale-r", "new-a", "new-r"))
        assertEquals("token-a", store.readSession()?.accessToken) // not overwritten
    }

    @Test
    fun authSessionEventsAreVisibleAcrossStoreInstances() = runBlocking {
        // #466 gate: an event from store instance B (e.g. the socket coordinator) must reach an
        // observer on instance A (e.g. AppShell) — the bus is process-wide shared, not per-instance.
        // Without the companion-shared flow, A's observer silently misses B's mutations (the exact
        // gap that would break #560's push seam).
        val kv = InMemoryRaftKeyValueStore()
        val a = KeyValueAuthSessionStore(kv)
        val b = KeyValueAuthSessionStore(kv)
        a.replaceAuthenticatedSession(sampleSession()) // emitted before A subscribes (replay=0)

        val eventOnA = async(start = CoroutineStart.UNDISPATCHED) { a.authSessionEvents.first() }
        b.rotateTokensIfMatch("token-a", "token-r", "token-a2", "token-r2")

        val event = withTimeoutOrNull(1000) { eventOnA.await() }
        assertTrue(event is AuthSessionEvent.TokensRotated) // B's rotation seen by A's observer
    }

    @Test
    fun authSessionEventGenerationsAreMonotonicAndTyped() = runBlocking {
        // generation is assigned INSIDE the store lock so consumers can order events by generation
        // regardless of delivery order (rapid rotate→clear never reorders semantically).
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        val collected = async(start = CoroutineStart.UNDISPATCHED) { store.authSessionEvents.take(3).toList() }
        store.replaceAuthenticatedSession(sampleSession()) // Authenticated
        store.rotateTokensIfMatch("token-a", "token-r", "token-a2", "token-r2") // TokensRotated
        store.clearSession(reason = "user_logout") // Cleared(Logout)

        val events = withTimeoutOrNull(1000) { collected.await() }!!
        assertTrue(events[0] is AuthSessionEvent.Authenticated)
        assertTrue(events[1] is AuthSessionEvent.TokensRotated)
        assertTrue(events[2] is AuthSessionEvent.Cleared)
        assertEquals(AuthSessionClearReason.Logout, (events[2] as AuthSessionEvent.Cleared).reason)
        assertTrue(events.map { it.generation }.zipWithNext().all { (x, y) -> y > x }) // strictly increasing
    }

    @Test
    fun clearNotifyEmitsTypedClearedOnMatchedRemoveAcrossInstances() = runBlocking {
        // #466 (AD2 blocker): the remote-clear path (clearSessionFromSessionClearedNotify — used by
        // Settings/AppShell/Provider) must publish to the typed process-wide bus too, else #560 misses
        // this real session mutation. A matched remove on instance B is seen by A's typed observer.
        val kv = InMemoryRaftKeyValueStore()
        val a = KeyValueAuthSessionStore(kv)
        val b = KeyValueAuthSessionStore(kv)
        val session = sampleSession()
        a.replaceAuthenticatedSession(session)

        val eventOnA = async(start = CoroutineStart.UNDISPATCHED) { a.authSessionEvents.first() }
        val removed = b.clearSessionFromSessionClearedNotify(
            reason = "remote_session_cleared",
            previousSessionFingerprint = session.authSessionClearFingerprint()
        )
        assertTrue(removed)
        val event = withTimeoutOrNull(1000) { eventOnA.await() }
        assertTrue(event is AuthSessionEvent.Cleared) // remote clear seen on typed bus by A
        assertEquals(AuthSessionClearReason.AuthLoss, (event as AuthSessionEvent.Cleared).reason)
        assertNull(b.readSession()) // actually removed
    }

    @Test
    fun clearNotifyOnEmptyStoreDoesNotFabricateTypedMutation() = runBlocking {
        // A duplicate remote-clear notify (current=null, nothing removed) must NOT fabricate a typed
        // Cleared event — the first typed event a subscriber sees is the subsequent REAL login.
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { store.authSessionEvents.first() }
        store.clearSessionFromSessionClearedNotify("remote_session_cleared", "nonexistent-fp")
        store.replaceAuthenticatedSession(sampleSession()) // real Authenticated
        val event = withTimeoutOrNull(1000) { firstEvent.await() }
        assertTrue(event is AuthSessionEvent.Authenticated) // NOT a fabricated Cleared from the no-op notify
    }

    @Test
    fun authenticatedReplacementEmitsCanonicalCredentialIdentityWithoutEmailOrTokens() = runBlocking {
        val logs = mutableListOf<String>()
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore(), diagnosticsLogger = logs::add)
        store.replaceAuthenticatedSession(sampleSession())
        val identity = logs.single { it.startsWith("QA_CREDENTIAL_IDENTITY ") }
        assertTrue(
            identity.matches(
                Regex("QA_CREDENTIAL_IDENTITY version=1 principalId=user-1 authGeneration=[1-9][0-9]*")
            )
        )
        assertTrue("artin@slock.ai" !in identity)
        assertTrue("token-a" !in identity)
        assertNull(qaCredentialIdentityDiagnostic(sampleSession().copy(user = sampleSession().user.copy(id = "bad id")), 2))
    }

    @Test
    fun terminalCleanupCommitRejectsSuccessorLoginAfterOldClearAdmission() = runBlocking {
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        store.replaceAuthenticatedSession(sampleSession())
        store.clearSession("refresh_http_401", "test")
        val admittedClear = store.readAuthSessionTerminalSnapshot()
        assertNull(admittedClear.session)

        store.replaceAuthenticatedSession(
            sampleSession().copy(accessToken = "successor-access", refreshToken = "successor-refresh")
        )
        var cleanupRan = false
        val committed =
            store.runIfAuthSessionTerminalRevision(admittedClear.generation) {
                cleanupRan = true
            }

        assertFalse(committed)
        assertFalse(cleanupRan)
        assertEquals("successor-access", store.readSession()?.accessToken)
    }

    @Test
    fun terminalAuthLossCommitClearsExactCapturedAuthorityLeftByEarlierClearFailure() = runBlocking {
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        val captured = sampleSession()
        store.replaceAuthenticatedSession(captured)
        val admitted = store.readAuthSessionTerminalSnapshot()
        val clearedEvent =
            async(start = CoroutineStart.UNDISPATCHED) {
                store.authSessionTerminalEvents.first { it is AuthSessionEvent.Cleared }
            }
        var terminalEffectRan = false

        val committed =
            store.runIfAuthSessionTerminalOrAuthorityRevision(
                expectedGeneration = admitted.generation,
                expectedSession = captured,
            ) {
                terminalEffectRan = true
            }

        assertNotNull(committed)
        assertTrue(terminalEffectRan)
        assertNull(store.readSession())
        val event = withTimeoutOrNull(1000) { clearedEvent.await() }
        assertTrue(event is AuthSessionEvent.Cleared)
        assertEquals(AuthSessionClearReason.AuthLoss, (event as AuthSessionEvent.Cleared).reason)
    }

    @Test
    fun terminalAuthLossCommitRejectsDifferentSuccessorAuthority() = runBlocking {
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        val captured = sampleSession()
        store.replaceAuthenticatedSession(captured)
        val admitted = store.readAuthSessionTerminalSnapshot()
        val successor =
            captured.copy(
                accessToken = "successor-access",
                refreshToken = "successor-refresh",
            )
        store.replaceAuthenticatedSession(successor)
        var terminalEffectRan = false

        val committed =
            store.runIfAuthSessionTerminalOrAuthorityRevision(
                expectedGeneration = admitted.generation,
                expectedSession = captured,
            ) {
                terminalEffectRan = true
            }

        assertNull(committed)
        assertFalse(terminalEffectRan)
        assertEquals(successor, store.readSession())
    }

    @Test
    fun terminalAuthLossCommitAcceptsExactAuthorityAfterMetadataGenerationDrift() = runBlocking {
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        val captured = sampleSession()
        store.replaceAuthenticatedSession(captured)
        val admitted = store.readAuthSessionTerminalSnapshot()
        store.updateMetadata(expectedAccountId = captured.user.id) { latest ->
            latest.copy(user = latest.user.copy(displayName = "Metadata changed"))
        }
        val drifted = store.readAuthSessionTerminalSnapshot()
        assertEquals(admitted.generation + 1, drifted.generation)
        assertEquals(captured.accessToken, drifted.session?.accessToken)
        assertEquals(captured.refreshToken, drifted.session?.refreshToken)
        var terminalEffectRan = false

        val committedGeneration =
            store.runIfAuthSessionTerminalOrAuthorityRevision(
                expectedGeneration = admitted.generation,
                expectedSession = captured,
            ) {
                terminalEffectRan = true
            }

        assertNotNull(committedGeneration)
        assertEquals(drifted.generation + 1, committedGeneration)
        assertTrue(terminalEffectRan)
        assertNull(store.readSession())
    }

    private class CountingRaftKeyValueStore : RaftKeyValueStore {
        private val delegate = InMemoryRaftKeyValueStore()
        var readCount: Int = 0

        override fun getString(key: String): String? {
            readCount += 1
            return delegate.getString(key)
        }

        override fun putString(key: String, value: String?) {
            delegate.putString(key, value)
        }
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user =
                StoredAuthUser(
                    id = "user-1",
                    email = "artin@slock.ai",
                    displayName = "Artin"
                ),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers =
                listOf(
                    StoredAuthServer(
                        id = "server-1",
                        name = "Slock Community",
                        slug = "slock"
                    ),
                    StoredAuthServer(
                        id = "server-2",
                        name = "Work",
                        slug = "work"
                    )
                )
        )

    private fun sampleQaBootstrap(attempt: Int): StoredQaBootstrapMetadata =
        StoredQaBootstrapMetadata(
            ticketDigest = "sha256:ticket-$attempt",
            runCaseId = "run-case",
            attempt = attempt,
            ciJobRef = "gha:job",
            principalId = "user-1",
            serverId = "server-1",
            sessionGeneration = "session-$attempt",
            targetRoute = "compose_slock_app_shell",
            applicationId = "build.raft.app.alpha",
            signingCertSha256 = "sha256:cert",
            deviceBootGeneration = "sha256:boot",
        )

}
