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
        val owner = SlockAvatarCacheOwner("https://api.example", "user-1", "server-1")
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(fixtures.sampleSession()),
                settingsRepository = repository,
                serverMembersStore = membersStore
            ).withAvatarCacheRuntime(avatarRuntime(metadata) { owner }, native)

        assertIs<DataResult.Content<SettingsServerActionResult>>(
            dataSource.removeServerMember("member-2")
        )

        val expectedSubject: SlockAvatarCacheSubject =
            SlockAvatarCacheSubject.Member(MemberKind.Human, "member-2")
        assertEquals(
            listOf(owner.nativePartitionKey to slockAvatarNativeSubjectKey(owner, expectedSubject)),
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
        val owner = SlockAvatarCacheOwner("https://api.example", "user-1", "server-1")
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(fixtures.sampleSession()),
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
    fun deleteAndLeaveServerRetireOnlyTheCapturedCurrentOwner() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())
        val owner =
            SlockAvatarCacheOwner(
                environmentStore.current().apiBaseUrl.trimEnd('/'),
                "user-1",
                "server-1"
            )
        val repository = fixtures.avatarSettingsRepository()
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()
        val dataSource =
            SharedSettingsDataSource(
                sessionStore = InMemorySlockAuthSessionStore(fixtures.sampleSession()),
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
        val sessionStore = InMemorySlockAuthSessionStore(fixtures.sampleSession())
        val environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())
        val oldEnvironment = environmentStore.current().apiBaseUrl.trimEnd('/')
        val oldOwner = SlockAvatarCacheOwner(oldEnvironment, "user-1", "server-1")
        val metadata = RecordingAvatarMetadataStore()
        val native = RecordingAvatarNativeCache()
        val runtime = avatarRuntime(metadata) { requested ->
            SlockAvatarCacheOwner(
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
                    sessionStore = InMemorySlockAuthSessionStore(fixtures.sampleSession()),
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
        owner: suspend (String?) -> SlockAvatarCacheOwner?
    ): SlockAvatarCacheRuntime =
        SlockAvatarCacheRuntime(
            coordinator = SlockAvatarCacheCoordinator(metadata) { 100L },
            currentOwner = owner
        )

    private class RecordingAvatarMetadataStore : SlockAvatarCacheMetadataStore {
        val clearedOwners = mutableListOf<SlockAvatarCacheOwner>()
        val clearedSubjects = mutableListOf<Pair<SlockAvatarCacheOwner, SlockAvatarCacheSubject>>()
        var clearAllCalls = 0
        var failClearAll = false

        override suspend fun read(key: SlockAvatarCacheKey): SlockAvatarCacheStoredState =
            SlockAvatarCacheStoredState(null, null)

        override suspend fun commitSuccess(
            metadata: SlockAvatarCacheMetadata,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult = SlockAvatarCacheCommitResult.Accepted

        override suspend fun commitFailure(
            failure: SlockAvatarCacheFailure,
            retireMatchingMetadata: Boolean,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult = SlockAvatarCacheCommitResult.Accepted

        override suspend fun restoreAfterNativeFinalizeFailure(
            key: SlockAvatarCacheKey,
            expectedCommittedState: SlockAvatarCacheStoredState,
            previousState: SlockAvatarCacheStoredState,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult = SlockAvatarCacheCommitResult.Accepted

        override suspend fun clearFailure(key: SlockAvatarCacheKey) = Unit

        override suspend fun clearTemporaryFailures(owner: SlockAvatarCacheOwner) = Unit

        override suspend fun clearSubject(
            owner: SlockAvatarCacheOwner,
            subject: SlockAvatarCacheSubject
        ) {
            clearedSubjects += owner to subject
        }

        override suspend fun clearOwner(owner: SlockAvatarCacheOwner) {
            clearedOwners += owner
        }

        override suspend fun clearAll() {
            clearAllCalls += 1
            if (failClearAll) error("clear failed")
        }
    }

    private class RecordingAvatarNativeCache : SlockAvatarNativeCache {
        val retiredOwners = mutableListOf<String>()
        val retiredSubjects = mutableListOf<Pair<String, String>>()

        override suspend fun inspect(
            nativePartitionKey: String,
            nativeSubjectKey: String,
            nativeBytesKey: String,
            commitGeneration: Long
        ): SlockAvatarNativeInspection = SlockAvatarNativeInspection.Unsupported

        override suspend fun awaitResult(request: SlockAvatarNativeRequest): SlockAvatarNativeLoadResult =
            SlockAvatarNativeLoadResult.Unsupported

        override suspend fun finalize(
            request: SlockAvatarNativeRequest,
            outcome: SlockAvatarNativeFinalizeOutcome
        ): SlockAvatarNativeMutationResult = SlockAvatarNativeMutationResult.Accepted

        override suspend fun rollback(request: SlockAvatarNativeRequest): SlockAvatarNativeMutationResult =
            SlockAvatarNativeMutationResult.Accepted

        override suspend fun retireOwner(nativePartitionKey: String): SlockAvatarNativeMutationResult {
            retiredOwners += nativePartitionKey
            return SlockAvatarNativeMutationResult.Accepted
        }

        override suspend fun retireSubject(
            nativePartitionKey: String,
            nativeSubjectKey: String
        ): SlockAvatarNativeMutationResult {
            retiredSubjects += nativePartitionKey to nativeSubjectKey
            return SlockAvatarNativeMutationResult.Accepted
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
