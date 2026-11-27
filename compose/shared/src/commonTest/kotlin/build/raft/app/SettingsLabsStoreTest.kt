package build.raft.app

import build.raft.app.data.ScreenError
import build.raft.app.data.ServerLab
import build.raft.app.data.ServerLabState
import build.raft.app.data.ServerLabsFailure
import build.raft.app.data.ServerLabsLoadResult
import build.raft.app.data.ServerLabsMutationOutcome
import build.raft.app.data.ServerLabsMutationRequest
import build.raft.app.data.ServerLabsMutationResult
import build.raft.app.data.ServerLabsReadback
import build.raft.app.data.ServerLabsRepository
import build.raft.app.settings.SettingsLabsAuthority
import build.raft.app.settings.SettingsLabsError
import build.raft.app.settings.SettingsLabsErrorKind
import build.raft.app.settings.SettingsLabsState
import build.raft.app.settings.SettingsLabsStore
import build.raft.app.settings.settingsLabsAuthority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings > Labs store and permission rules (SettingsLabsStore.kt) against the web's LabsSection,
 * serverLabsSettingsStore and serverLabsSettings (botiverse/slock staging aa270b415d44).
 */
class SettingsLabsStoreTest {
    private val openLab = ServerLab("open_lab", "Open Lab", "Available", ServerLabState.Open, enrolled = false, effective = false)
    private val pausedLab = ServerLab("paused_lab", "Paused Lab", "Paused", ServerLabState.Paused, enrolled = true, effective = false)

    private fun readback(
        version: Long = 1,
        masterEnabled: Boolean = true,
        canManageAccess: Boolean? = null,
        canManageEnrollments: Boolean? = null,
        labs: List<ServerLab> = listOf(openLab, pausedLab),
        serverId: String = "srv-1",
    ) = ServerLabsReadback(serverId, version, masterEnabled, canManageAccess, canManageEnrollments, labs)

    private class FakeRepository(var load: ServerLabsLoadResult) : ServerLabsRepository {
        val writes = mutableListOf<Pair<String, ServerLabsMutationRequest>>()
        var answer: ServerLabsMutationOutcome? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun load(serverId: String): ServerLabsLoadResult = load

        override suspend fun setAccess(serverId: String, request: ServerLabsMutationRequest): ServerLabsMutationOutcome {
            writes += "access" to request
            gate?.await()
            return answer ?: error("no answer for access write")
        }

        override suspend fun setEnrollment(serverId: String, labKey: String, request: ServerLabsMutationRequest): ServerLabsMutationOutcome {
            writes += labKey to request
            gate?.await()
            return answer ?: error("no answer for $labKey write")
        }
    }

    private fun TestScope.loaded(
        repository: FakeRepository,
        applied: MutableList<String> = mutableListOf(),
    ): SettingsLabsStore =
        SettingsLabsStore(repository, this, onApplied = { applied += it }).also {
            it.load("srv-1")
            runCurrent()
        }

    @Test
    fun authorityFollowsTheServersPermissionsElseTheRole() {
        // Server permissions win (web `permissions?.canSetMasterAccess ?? isOwnerRole(role)`).
        assertEquals(SettingsLabsAuthority(true, true), settingsLabsAuthority("member", readback(canManageAccess = true, canManageEnrollments = true)))
        assertEquals(SettingsLabsAuthority(false, false), settingsLabsAuthority("owner", readback(canManageAccess = false, canManageEnrollments = false)))
        // Without them: owner both, admin enrollments only, member and guest nothing.
        assertEquals(SettingsLabsAuthority(true, true), settingsLabsAuthority("owner", readback()))
        assertEquals(SettingsLabsAuthority(false, true), settingsLabsAuthority("Admin", readback()))
        assertEquals(SettingsLabsAuthority(false, false), settingsLabsAuthority("member", readback()))
        assertEquals(SettingsLabsAuthority(false, false), settingsLabsAuthority("guest", null))
        assertEquals(SettingsLabsAuthority(false, false), settingsLabsAuthority(null, null))
    }

    @Test
    fun aVersionConflictKeepsTheReadbackAndSaysFailedUpdateWithTheTraceId() = runTest {
        val repository = FakeRepository(ServerLabsLoadResult.Loaded(readback(version = 1)))
        repository.answer =
            ServerLabsMutationOutcome.Failed(
                ServerLabsFailure(ScreenError("http_409", serverCode = "server_labs_version_conflict", traceId = TRACE_ID)),
            )
        val applied = mutableListOf<String>()
        val store = loaded(repository, applied)
        store.setEnrollment("open_lab", true, "admin")
        runCurrent()
        val state = store.state.value
        assertEquals(readback(version = 1), state.readback)
        assertEquals(SettingsLabsError(SettingsLabsErrorKind.FailedUpdate, TRACE_ID), state.error)
        assertNull(state.savingTarget)
        assertTrue(applied.isEmpty())

        // The next write that goes through clears the line.
        repository.answer = ServerLabsMutationOutcome.Done(ServerLabsMutationResult(false, readback(version = 1)))
        store.setEnrollment("open_lab", true, "admin")
        runCurrent()
        assertNull(store.state.value.error)
    }

    @Test
    fun oneWriteAtATimeAndAnOlderReadbackNeverWins() = runTest {
        val repository = FakeRepository(ServerLabsLoadResult.Loaded(readback(version = 4)))
        repository.gate = CompletableDeferred()
        repository.answer = ServerLabsMutationOutcome.Done(ServerLabsMutationResult(true, readback(version = 3, masterEnabled = false)))
        val store = loaded(repository)
        store.setMasterAccess(false, "owner")
        runCurrent()
        assertTrue(store.state.value.mutationPending)
        store.setEnrollment("open_lab", true, "owner")
        store.setMasterAccess(true, "owner")
        runCurrent()
        assertEquals(1, repository.writes.size)
        repository.gate!!.complete(Unit)
        runCurrent()
        // version 3 < 4: kept.
        assertEquals(4L, store.state.value.readback?.version)
        assertTrue(store.state.value.readback!!.masterEnabled)
        assertFalse(store.state.value.mutationPending)
    }

    @Test
    fun anotherServerDropsTheOldOnesAnswer() = runTest {
        val repository = FakeRepository(ServerLabsLoadResult.Loaded(readback()))
        repository.gate = CompletableDeferred()
        repository.answer = ServerLabsMutationOutcome.Done(ServerLabsMutationResult(true, readback(version = 9)))
        val store = loaded(repository)
        store.setMasterAccess(false, "owner")
        runCurrent()
        repository.load = ServerLabsLoadResult.Loaded(readback(serverId = "srv-2", version = 1))
        store.load("srv-2")
        runCurrent()
        repository.gate!!.complete(Unit)
        runCurrent()
        assertEquals("srv-2", store.state.value.readback?.serverId)
        assertEquals(1L, store.state.value.readback?.version)
        assertFalse(store.state.value.mutationPending)

        store.load(null)
        assertEquals(SettingsLabsState(), store.state.value)
    }

    private companion object {
        const val TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
    }
}
