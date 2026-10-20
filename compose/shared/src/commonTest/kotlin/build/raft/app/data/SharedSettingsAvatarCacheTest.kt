package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SharedSettingsAvatarCacheTest {
    private val fixtures = SharedSettingsStoreTest()

    @Test
    fun removeServerMemberCapturesTypedSubjectBeforeStoreEvictionAndRetiresNativeIndex() = runBlocking {
        val repository = fixtures.avatarSettingsRepository()
        val membersStore = SharedServerMembersStore(fixtures.avatarSettingsMemberRepository())
        membersStore.seed(
            MemberSnapshot(
                settingsServer("server-1"),
                listOf(settingsMember("member-2", "Member"))
            )
        )
        val owner = RaftAvatarCacheOwner("https://api.example", "user-1", "server-1")
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(fixtures.sampleSession()),
                settingsRepository = repository,
                serverMembersStore = membersStore
            ).withAvatarCacheRuntime(avatarRuntime(metadata) { owner }, native)

        assertIs<DataResult.Content<SettingsServerActionResult>>(
            dataSource.removeServerMember("member-2")
        )

        val expectedSubject: RaftAvatarCacheSubject =
            RaftAvatarCacheSubject.Member(MemberKind.Human, "member-2")
        assertEquals(
            listOf(owner.nativePartitionKey to raftAvatarNativeSubjectKey(owner, expectedSubject)),
            native.retiredSubjects
        )
        assertEquals(listOf(owner to expectedSubject), metadata.clearedSubjects)
        assertTrue(membersStore.currentServerMembers(ServerId("server-1")).isEmpty())
    }

    @Test
    fun nonHumanOrAgentRemovalNeverInventsAnAvatarCacheSubject() = runBlocking {
        val repository = fixtures.avatarSettingsRepository()
        val membersStore = SharedServerMembersStore(fixtures.avatarSettingsMemberRepository())
        membersStore.seed(
            MemberSnapshot(
                settingsServer("server-1"),
                listOf(settingsMember("system-1", "System").copy(kind = MemberKind.System))
            )
        )
        val owner = RaftAvatarCacheOwner("https://api.example", "user-1", "server-1")
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(fixtures.sampleSession()),
                settingsRepository = repository,
                serverMembersStore = membersStore
            ).withAvatarCacheRuntime(avatarRuntime(metadata) { owner }, native)

        assertIs<DataResult.Content<SettingsServerActionResult>>(
            dataSource.removeServerMember("system-1")
        )

        assertTrue(native.retiredSubjects.isEmpty())
        assertTrue(metadata.clearedSubjects.isEmpty())
    }

    @Test
    fun accountAvatarUploadRetiresCapturedHumanSubjectEvenWhenReturnedUrlIsUnchanged() = runBlocking {
        val avatarUrl = "https://avatars.example.test/users/user-1.webp"
        val session =
            fixtures.sampleSession().let { current ->
                current.copy(user = current.user.copy(avatarUrl = avatarUrl))
            }
        val resultSnapshot =
            AccountSettingsSnapshot(
                profile =
                    AccountProfileSnapshot(
                        userId = "user-1",
                        displayName = "Artin",
                        handle = "artin",
                        email = "artin@slock.ai",
                        avatarLabel = "A",
                        avatarUrl = avatarUrl
                    ),
                connectedAccounts = emptyList()
            )
        val owner = RaftAvatarCacheOwner("https://api.example", "user-1", "server-1")
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(session),
                settingsRepository = fixtures.avatarSettingsRepository(DataResult.Content(resultSnapshot)),
                platformCapabilities =
                    fixtures.avatarSettingsCapabilities(
                        DataResult.Content(
                            PlatformFile(
                                id = "avatar-1",
                                fileName = "avatar.webp",
                                mimeType = "image/webp",
                                bytes = "new-avatar-bytes".encodeToByteArray()
                            )
                        )
                    )
            ).withAvatarCacheRuntime(avatarRuntime(metadata) { owner }, native)

        val result = assertIs<DataResult.Content<AccountSettingsSnapshot>>(dataSource.uploadAccountAvatar())

        assertEquals(avatarUrl, result.data.profile.avatarUrl)
        val expectedSubject: RaftAvatarCacheSubject =
            RaftAvatarCacheSubject.Member(MemberKind.Human, "user-1")
        assertEquals(
            listOf(owner.nativePartitionKey to raftAvatarNativeSubjectKey(owner, expectedSubject)),
            native.retiredSubjects
        )
        assertEquals(listOf(owner to expectedSubject), metadata.clearedSubjects)
    }

    @Test
    fun failedAccountAvatarUploadDoesNotRetireCapturedSubject() = runBlocking {
        val owner = RaftAvatarCacheOwner("https://api.example", "user-1", "server-1")
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(fixtures.sampleSession()),
                settingsRepository =
                    fixtures.avatarSettingsRepository(
                        DataResult.Error(ScreenError("account_avatar_upload_failed"))
                    ),
                platformCapabilities =
                    fixtures.avatarSettingsCapabilities(
                        DataResult.Content(PlatformFile(id = "avatar-1", fileName = "avatar.webp"))
                    )
            ).withAvatarCacheRuntime(avatarRuntime(metadata) { owner }, native)

        assertIs<DataResult.Error>(dataSource.uploadAccountAvatar())

        assertTrue(native.retiredSubjects.isEmpty())
        assertTrue(metadata.clearedSubjects.isEmpty())
    }

    @Test
    fun agentAvatarUploadRetiresCapturedAgentSubjectWhenReturnedUrlIsUnchanged() = runBlocking {
        val avatarUrl = "https://avatars.example.test/agents/agent-1.webp"
        val profile =
            settingsMember("agent-1", "Agent").copy(
                kind = MemberKind.Agent,
                serverId = ServerId("server-1"),
                avatarUrl = avatarUrl
            )
        val store =
            SharedServerMembersStore(
                fixtures.avatarSettingsMemberRepository(DataResult.Content(profile))
            )
        store.seed(MemberSnapshot(settingsServer("server-1"), listOf(profile)))
        val owner = RaftAvatarCacheOwner("https://api.example", "user-1", "server-1")
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()

        val result =
            assertIs<DataResult.Content<build.raft.app.model.MemberProfile>>(
                store.uploadAgentAvatarWithAvatarCacheInvalidation(
                    serverId = ServerId("server-1"),
                    request =
                        AgentAvatarUploadRequest(
                            agentId = build.raft.app.model.MemberId("agent-1"),
                            file = PlatformFile(id = "avatar-1", fileName = "avatar.webp")
                        ),
                    avatarCacheRuntime = avatarRuntime(metadata) { owner },
                    avatarCacheNativeCache = native
                )
            )

        assertEquals(avatarUrl, result.data.avatarUrl)
        val expectedSubject: RaftAvatarCacheSubject =
            RaftAvatarCacheSubject.Member(MemberKind.Agent, "agent-1")
        assertEquals(
            listOf(owner.nativePartitionKey to raftAvatarNativeSubjectKey(owner, expectedSubject)),
            native.retiredSubjects
        )
        assertEquals(listOf(owner to expectedSubject), metadata.clearedSubjects)
    }

    @Test
    fun agentAvatarUploadIdentityMismatchDoesNotRetireCapturedSubject() = runBlocking {
        val mismatchedProfile =
            settingsMember("agent-2", "Other Agent").copy(
                kind = MemberKind.Agent,
                serverId = ServerId("server-1"),
                avatarUrl = "https://avatars.example.test/agents/agent-2.webp"
            )
        val store =
            SharedServerMembersStore(
                fixtures.avatarSettingsMemberRepository(DataResult.Content(mismatchedProfile))
            )
        val owner = RaftAvatarCacheOwner("https://api.example", "user-1", "server-1")
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()

        assertIs<DataResult.Content<build.raft.app.model.MemberProfile>>(
            store.uploadAgentAvatarWithAvatarCacheInvalidation(
                serverId = ServerId("server-1"),
                request =
                    AgentAvatarUploadRequest(
                        agentId = build.raft.app.model.MemberId("agent-1"),
                        file = PlatformFile(id = "avatar-1", fileName = "avatar.webp")
                    ),
                avatarCacheRuntime = avatarRuntime(metadata) { owner },
                avatarCacheNativeCache = native
            )
        )

        assertTrue(native.retiredSubjects.isEmpty())
        assertTrue(metadata.clearedSubjects.isEmpty())
    }

    @Test
    fun deleteAndLeaveServerRetireOnlyTheCapturedCurrentOwner() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
        val owner =
            RaftAvatarCacheOwner(
                environmentStore.current().apiBaseUrl.trimEnd('/'),
                "user-1",
                "server-1"
            )
        val repository = fixtures.avatarSettingsRepository()
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemoryRaftAuthSessionStore(fixtures.sampleSession()),
                settingsRepository = repository,
                apiEnvironmentStore = environmentStore
            ).withAvatarCacheRuntime(avatarRuntime(metadata) { owner }, native)

        assertIs<DataResult.Content<SettingsServerActionResult>>(
            dataSource.deleteServer(SettingsServerDeleteRequest("slock"))
        )
        assertIs<DataResult.Content<SettingsServerActionResult>>(dataSource.leaveServer())

        assertEquals(listOf(owner.nativePartitionKey, owner.nativePartitionKey), native.retiredOwners)
        assertEquals(listOf(owner, owner), metadata.clearedOwners)
    }

    @Test
    fun environmentChangeCapturesOldEnvironmentOwnerBeforeSessionAndEnvironmentMutation() = runBlocking {
        val sessionStore = InMemoryRaftAuthSessionStore(fixtures.sampleSession())
        val environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
        val oldEnvironment = environmentStore.current().apiBaseUrl.trimEnd('/')
        val oldOwner = RaftAvatarCacheOwner(oldEnvironment, "user-1", "server-1")
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()
        val runtime = avatarRuntime(metadata) { requested ->
            RaftAvatarCacheOwner(
                environmentStore.current().apiBaseUrl.trimEnd('/'),
                "user-1",
                requested ?: "server-1"
            )
        }
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = sessionStore,
                apiEnvironmentStore = environmentStore,
                platformCapabilities = fixtures.avatarSettingsCapabilities()
            ).withAvatarCacheRuntime(runtime, native)

        assertIs<DataResult.Content<SharedSettingsDeveloperSnapshot>>(
            dataSource.setApiEnvironment("staging")
        )

        assertNull(sessionStore.readSession())
        assertEquals("staging", environmentStore.current().key)
        assertEquals(listOf(oldOwner.nativePartitionKey), native.retiredOwners)
        assertEquals(listOf(oldOwner), metadata.clearedOwners)
    }

    @Test
    fun imageCacheClearRequiresNativeAndSharedSuccessAndAttemptsBothSides() = runBlocking {
        data class Case(val nativeSuccess: Boolean, val metadataSuccess: Boolean)
        listOf(
            Case(true, true),
            Case(false, true),
            Case(true, false),
            Case(false, false)
        ).forEach { case ->
            val metadata = RecordingAvatarMetadataStore().apply {
                failClearAll = !case.metadataSuccess
            }
            val capabilities = RecordingAvatarClearCapabilities(case.nativeSuccess)
            val dataSource =
                SharedSettingsDataSource(
                    sessionStore = InMemoryRaftAuthSessionStore(fixtures.sampleSession()),
                    platformCapabilities = capabilities
                ).withAvatarCacheRuntime(
                    avatarRuntime(metadata) { null },
                    RecordingAvatarNativeCache()
                )

            val result = dataSource.clearStorage("imageCache")

            assertEquals(1, capabilities.clearCalls)
            assertEquals(1, metadata.clearAllCalls)
            assertEquals(case.nativeSuccess && case.metadataSuccess, result is DataResult.Content)
        }
    }

    private fun avatarRuntime(
        metadata: RecordingAvatarMetadataStore,
        owner: suspend (String?) -> RaftAvatarCacheOwner?
    ): RaftAvatarCacheRuntime =
        RaftAvatarCacheRuntime(
            coordinator = RaftAvatarCacheCoordinator(metadata) { 100L },
            currentOwner = owner
        )

    private class RecordingAvatarMetadataStore : RaftAvatarCacheMetadataStore {
        val clearedOwners = mutableListOf<RaftAvatarCacheOwner>()
        val clearedSubjects = mutableListOf<Pair<RaftAvatarCacheOwner, RaftAvatarCacheSubject>>()
        var clearAllCalls = 0
        var failClearAll = false

        override suspend fun readCommittedForOwner(
            owner: RaftAvatarCacheOwner,
            nowMs: Long,
            limit: Long
        ): List<RaftAvatarCacheMetadata> = emptyList()

        override suspend fun read(key: RaftAvatarCacheKey): RaftAvatarCacheStoredState =
            RaftAvatarCacheStoredState(null, null)

        override suspend fun commitSuccess(
            metadata: RaftAvatarCacheMetadata,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult = RaftAvatarCacheCommitResult.Accepted

        override suspend fun commitFailure(
            failure: RaftAvatarCacheFailure,
            retireMatchingMetadata: Boolean,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult = RaftAvatarCacheCommitResult.Accepted

        override suspend fun restoreAfterNativeFinalizeFailure(
            key: RaftAvatarCacheKey,
            expectedCommittedState: RaftAvatarCacheStoredState,
            previousState: RaftAvatarCacheStoredState,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult = RaftAvatarCacheCommitResult.Accepted

        override suspend fun clearFailure(key: RaftAvatarCacheKey) = Unit

        override suspend fun clearTemporaryFailures(owner: RaftAvatarCacheOwner) = Unit

        override suspend fun clearSubject(
            owner: RaftAvatarCacheOwner,
            subject: RaftAvatarCacheSubject
        ) {
            clearedSubjects += owner to subject
        }

        override suspend fun clearOwner(owner: RaftAvatarCacheOwner) {
            clearedOwners += owner
        }

        override suspend fun clearAll() {
            clearAllCalls += 1
            if (failClearAll) error("clear failed")
        }
    }

    private class RecordingAvatarNativeCache : RaftAvatarNativeCache {
        val retiredOwners = mutableListOf<String>()
        val retiredSubjects = mutableListOf<Pair<String, String>>()

        override suspend fun inspect(
            nativePartitionKey: String,
            nativeSubjectKey: String,
            nativeBytesKey: String
        ): RaftAvatarNativeInspection = RaftAvatarNativeInspection.Unsupported

        override suspend fun awaitResult(request: RaftAvatarNativeRequest): RaftAvatarNativeLoadResult =
            RaftAvatarNativeLoadResult.Unsupported

        override suspend fun finalize(
            request: RaftAvatarNativeRequest,
            outcome: RaftAvatarNativeFinalizeOutcome
        ): RaftAvatarNativeMutationResult = RaftAvatarNativeMutationResult.Accepted

        override suspend fun rollback(request: RaftAvatarNativeRequest): RaftAvatarNativeMutationResult =
            RaftAvatarNativeMutationResult.Accepted

        override suspend fun retireOwner(nativePartitionKey: String): RaftAvatarNativeMutationResult {
            retiredOwners += nativePartitionKey
            return RaftAvatarNativeMutationResult.Accepted
        }

        override suspend fun retireSubject(
            nativePartitionKey: String,
            nativeSubjectKey: String
        ): RaftAvatarNativeMutationResult {
            retiredSubjects += nativePartitionKey to nativeSubjectKey
            return RaftAvatarNativeMutationResult.Accepted
        }
    }

    private class RecordingAvatarClearCapabilities(
        private val success: Boolean
    ) : SharedSettingsPlatformCapabilities {
        var clearCalls = 0

        override suspend fun clearStorage(target: String): DataResult<SharedSettingsStorageSnapshot> {
            clearCalls += 1
            return if (success) {
                DataResult.Content(
                    SharedSettingsStorageSnapshot(
                        totalSizeLabel = "0 B",
                        rows = emptyList(),
                        feedback = "Cleared $target"
                    )
                )
            } else {
                DataResult.Error(ScreenError("native_clear_failed"))
            }
        }
    }
}
