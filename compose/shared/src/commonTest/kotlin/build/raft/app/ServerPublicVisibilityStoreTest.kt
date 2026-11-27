package build.raft.app

import build.raft.app.data.ServerPublicExposedChannel
import build.raft.app.data.ServerPublicMutationResult
import build.raft.app.data.ServerPublicVisibilityLoadResult
import build.raft.app.data.ServerPublicVisibilityReadback
import build.raft.app.data.ServerPublicVisibilityRepository
import build.raft.app.data.ServerPublicVisibilityUpdate
import build.raft.app.model.ServerId
import build.raft.app.thread.dispatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Store behaviour mirrors web publicVisibilityUiReducer / PublicVisibilitySection
 * (botiverse/slock@9df0d1b3f packages/web/src/components/settings/SettingsPanel.tsx:2069-2436).
 * The fake repository completes inline (or on an explicit gate), and the store runs on an
 * unconfined scope, so every state transition is deterministic without virtual time.
 */
class ServerPublicVisibilityStoreTest {
    private class FakeRepository(
        var loadResult: ServerPublicVisibilityLoadResult = loaded(readback()),
    ) : ServerPublicVisibilityRepository {
        val loads = mutableListOf<ServerId>()
        val visibilityWrites = mutableListOf<Boolean>()
        val searchWrites = mutableListOf<Boolean>()
        var visibilityResult: (Boolean) -> ServerPublicMutationResult<ServerPublicVisibilityUpdate> = { visible ->
            ServerPublicMutationResult.Success(ServerPublicVisibilityUpdate(publiclyVisible = visible, publicSearchEnabled = true))
        }
        var searchResult: (Boolean) -> ServerPublicMutationResult<Boolean> = { ServerPublicMutationResult.Success(it) }
        var mutationGate: CompletableDeferred<Unit>? = null

        override suspend fun load(serverId: ServerId): ServerPublicVisibilityLoadResult {
            loads += serverId
            return loadResult
        }

        override suspend fun setPubliclyVisible(serverId: ServerId, publiclyVisible: Boolean): ServerPublicMutationResult<ServerPublicVisibilityUpdate> {
            visibilityWrites += publiclyVisible
            mutationGate?.await()
            return visibilityResult(publiclyVisible)
        }

        override suspend fun setPublicSearchEnabled(serverId: ServerId, publicSearchEnabled: Boolean): ServerPublicMutationResult<Boolean> {
            searchWrites += publicSearchEnabled
            mutationGate?.await()
            return searchResult(publicSearchEnabled)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var serverListRefreshes = 0

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun startedStore(repository: FakeRepository, role: String = "owner"): ServerPublicVisibilityStore =
        ServerPublicVisibilityStore(repository, scope, refreshServerList = { serverListRefreshes += 1 }).also {
            it.dispatch(ServerPublicVisibilityIntent.Start(serverId = "server-1", role = role))
        }

    @Test
    fun sectionIsOwnerOnlyAndHiddenWhenTheGateIsOff() {
        val memberRepository = FakeRepository()
        val memberStore = startedStore(memberRepository, role = "admin")
        assertFalse(memberStore.state.value.sectionVisible)
        assertTrue(memberRepository.loads.isEmpty(), "admins never ask: the routes are owner-only")

        val gatedOff = startedStore(FakeRepository(loadResult = ServerPublicVisibilityLoadResult.Unavailable))
        assertEquals(ServerPublicVisibilityAvailability.Unavailable, gatedOff.state.value.availability)
        assertFalse(gatedOff.state.value.sectionVisible)

        val failed = startedStore(FakeRepository(loadResult = ServerPublicVisibilityLoadResult.Failed(serverMessage = null)))
        assertTrue(failed.state.value.sectionVisible, "a load failure shows the section with failedLoad")
        assertEquals(ServerPublicVisibilityErrorKind.Load, failed.state.value.error?.kind)
        assertFalse(failed.state.value.visibilitySwitchEnabled)
    }

    @Test
    fun turningOnRequiresConfirmationAndRefreshesTheServerList() {
        val repository = FakeRepository()
        val store = startedStore(repository)

        store.dispatch(ServerPublicVisibilityIntent.VisibilityToggled(checked = true))
        assertTrue(store.state.value.confirmOpen)
        assertTrue(repository.visibilityWrites.isEmpty(), "nothing is written before confirmation")

        store.dispatch(ServerPublicVisibilityIntent.DismissConfirm)
        assertFalse(store.state.value.confirmOpen)
        assertTrue(repository.visibilityWrites.isEmpty())

        store.dispatch(ServerPublicVisibilityIntent.VisibilityToggled(checked = true))
        store.dispatch(ServerPublicVisibilityIntent.ConfirmMakePublic)

        assertEquals(listOf(true), repository.visibilityWrites)
        assertTrue(store.state.value.visibilityChecked)
        assertFalse(store.state.value.confirmOpen)
        assertEquals(1, serverListRefreshes)
        assertEquals("https://raft.example/s/acme", store.state.value.publicUrl("https://raft.example/"))
    }

    @Test
    fun searchWritesPatchOnlyItsSliceAndReportItsOwnError() {
        val repository = FakeRepository(loadResult = loaded(readback(publiclyVisible = true, publicSearchEnabled = true)))
        val store = startedStore(repository)

        store.dispatch(ServerPublicVisibilityIntent.SearchToggled(checked = false))
        assertEquals(listOf(false), repository.searchWrites)
        assertFalse(store.state.value.searchChecked)
        assertTrue(store.state.value.visibilityChecked)

        repository.searchResult = { ServerPublicMutationResult.Failed(code = 409, serverMessage = "Not public") }
        store.dispatch(ServerPublicVisibilityIntent.SearchToggled(checked = true))
        assertEquals(listOf(false, true), repository.searchWrites)
        assertFalse(store.state.value.searchChecked, "failure keeps the previous state")
        assertEquals(ServerPublicVisibilityError(ServerPublicVisibilityErrorKind.SearchUpdate, "Not public"), store.state.value.error)
    }

    @Test
    fun savingDisablesEverySwitchAndALateAnswerForAPreviousServerIsDropped() = runBlocking {
        val repository = FakeRepository(loadResult = loaded(readback(publiclyVisible = true)))
        val store = startedStore(repository)
        val gate = CompletableDeferred<Unit>()
        repository.mutationGate = gate

        store.dispatch(ServerPublicVisibilityIntent.VisibilityToggled(checked = false))
        assertTrue(store.state.value.saving)
        assertFalse(store.state.value.visibilitySwitchEnabled)
        assertFalse(store.state.value.subSwitchesEnabled)

        repository.loadResult = loaded(readback(publiclyVisible = true, slug = "other"))
        store.dispatch(ServerPublicVisibilityIntent.Start(serverId = "server-2", role = "owner"))
        gate.complete(Unit)

        assertEquals("server-2", store.state.value.serverId)
        assertTrue(store.state.value.visibilityChecked, "server-1's late PATCH answer must not touch server-2")
        assertFalse(store.state.value.saving)
        assertEquals(0, serverListRefreshes)
        assertEquals(listOf(ServerId("server-1"), ServerId("server-2")), repository.loads)
    }

    private companion object {
        fun readback(
            publiclyVisible: Boolean = false,
            publicSearchEnabled: Boolean = true,
            slug: String = "acme",
        ) = ServerPublicVisibilityReadback(
            publiclyVisible = publiclyVisible,
            publicSearchEnabled = publicSearchEnabled,
            slug = slug,
            exposedChannels = listOf(ServerPublicExposedChannel("c-1", "announcements", null)),
        )

        fun loaded(readback: ServerPublicVisibilityReadback) = ServerPublicVisibilityLoadResult.Loaded(readback)
    }
}
