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
    fun enrollmentIsEditableOnlyForAnOpenLabBehindAnOpenGateForAdmins() {
        val admin = SettingsLabsAuthority(canSetMasterAccess = false, canSetEnrollments = true)
        val member = SettingsLabsAuthority(canSetMasterAccess = false, canSetEnrollments = false)
        assertTrue(settingsLabEnrollmentEditable(openLab, admin, masterEnabled = true))
        assertNull(settingsLabDisabledReason(openLab, admin, masterEnabled = true))
        // The order of reasons is the web's: gate, role, lifecycle.
        assertEquals(SettingsLabDisabledReason.MasterDisabled, settingsLabDisabledReason(openLab, member, masterEnabled = false))
        assertEquals(SettingsLabDisabledReason.AdminOnly, settingsLabDisabledReason(openLab, member, masterEnabled = true))
        for (state in listOf(ServerLabState.Paused, ServerLabState.Draft, ServerLabState.Retired, ServerLabState.Unknown)) {
            val lab = openLab.copy(state = state)
            assertFalse(settingsLabEnrollmentEditable(lab, admin, masterEnabled = true), "$state")
            assertEquals(SettingsLabDisabledReason.LifecycleReadOnly, settingsLabDisabledReason(lab, admin, masterEnabled = true), "$state")
        }
    }

    @Test
    fun loadShowsTheReadbackOrUnavailableOrFailedLoad() = runTest {
        val repository = FakeRepository(ServerLabsLoadResult.Loaded(readback()))
        val store = SettingsLabsStore(repository, this)
        store.load("srv-1")
        assertTrue(store.state.value.loading)
        runCurrent()
        assertFalse(store.state.value.loading)
        assertEquals(readback(), store.state.value.readback)

        repository.load = ServerLabsLoadResult.Unavailable
        store.load("srv-1")
        runCurrent()
        assertTrue(store.state.value.unavailable)
        assertNull(store.state.value.readback)

        repository.load = ServerLabsLoadResult.Failed(ServerLabsFailure(ScreenError("http_500", traceId = TRACE_ID)))
        store.load("srv-1")
        runCurrent()
        assertEquals(SettingsLabsError(SettingsLabsErrorKind.FailedLoad, TRACE_ID), store.state.value.error)
        assertFalse(store.state.value.unavailable)

        repository.load = ServerLabsLoadResult.Loaded(readback(serverId = "srv-2"))
        store.load("srv-1")
        runCurrent()
        assertEquals(SettingsLabsErrorKind.ServerMismatch, store.state.value.error?.kind)
        assertNull(store.state.value.readback)
    }

    @Test
    fun onlyTheOwnerWritesTheMasterGate() = runTest {
        for (role in listOf("admin", "member", "guest")) {
            val repository = FakeRepository(ServerLabsLoadResult.Loaded(readback()))
            val store = loaded(repository)
            store.setMasterAccess(false, role)
            runCurrent()
            assertTrue(repository.writes.isEmpty(), role)
            assertNull(store.state.value.savingTarget, role)
        }
        val repository = FakeRepository(ServerLabsLoadResult.Loaded(readback(version = 5)))
        repository.answer = ServerLabsMutationOutcome.Done(ServerLabsMutationResult(true, readback(version = 6, masterEnabled = false)))
        val applied = mutableListOf<String>()
        val store = loaded(repository, applied)
        store.setMasterAccess(false, "owner")
        assertEquals(SettingsLabsMasterTarget, store.state.value.savingTarget)
        runCurrent()
        assertEquals(listOf("access" to ServerLabsMutationRequest(enabled = false, expectedVersion = 5)), repository.writes)
        assertEquals(6L, store.state.value.readback?.version)
        assertFalse(store.state.value.readback!!.masterEnabled)
        assertNull(store.state.value.savingTarget)
        assertEquals(listOf("srv-1"), applied)
    }

    @Test
    fun ownersAndAdminsWriteEnrollmentsMembersDoNot() = runTest {
        for (role in listOf("owner", "admin")) {
            val repository = FakeRepository(ServerLabsLoadResult.Loaded(readback(version = 2)))
            repository.answer =
                ServerLabsMutationOutcome.Done(
                    ServerLabsMutationResult(true, readback(version = 3, labs = listOf(openLab.copy(enrolled = true, effective = true), pausedLab))),
                )
            val store = loaded(repository)
            store.setEnrollment("open_lab", true, role)
            assertEquals(settingsLabTarget("open_lab"), store.state.value.savingTarget, role)
            runCurrent()
            assertEquals(listOf("open_lab" to ServerLabsMutationRequest(enabled = true, expectedVersion = 2)), repository.writes, role)
            assertTrue(store.state.value.readback!!.labs.first().enrolled, role)
        }
        for (role in listOf("member", "guest")) {
            val repository = FakeRepository(ServerLabsLoadResult.Loaded(readback()))
            val store = loaded(repository)
            store.setEnrollment("open_lab", true, role)
            runCurrent()
            assertTrue(repository.writes.isEmpty(), role)
        }
    }

    @Test
    fun aPausedLabOrAClosedGateSendsNothing() = runTest {
        val repository = FakeRepository(ServerLabsLoadResult.Loaded(readback()))
        val store = loaded(repository)
        store.setEnrollment("paused_lab", false, "owner")
        store.setEnrollment("missing_lab", true, "owner")
        runCurrent()
        assertTrue(repository.writes.isEmpty())

        val closed = FakeRepository(ServerLabsLoadResult.Loaded(readback(masterEnabled = false)))
        val closedStore = loaded(closed)
        closedStore.setEnrollment("open_lab", true, "owner")
        runCurrent()
        assertTrue(closed.writes.isEmpty())
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
