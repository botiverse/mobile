package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLoginTransitionCoordinatorTest {
    private val coordinator = AndroidLoginTransitionCoordinator("test-process")
    private val sources = mutableListOf<Source>()
    private val launches = mutableListOf<Pair<Int, String>>()

    @Test
    fun accountAndShellCoalesceBeforeLoginHasBeenCreated() {
        val account = source("account")
        val shell = source("shell")

        replace(account)
        replace(shell)
        replace(account)
        replace(shell)

        assertEquals(1, launches.size)
        assertEquals(1, account.finishes)
        assertEquals(1, shell.finishes)
    }

    @Test
    fun currentLoginIsNeitherRelaunchedNorFinishedByDuplicateReplacement() {
        val token = reserve()
        val login = source("login")
        assertEquals(token, coordinator.bind(1, token, login.owner))

        replace(login)
        replace(source("late-shell"))

        assertTrue(launches.isEmpty())
        assertEquals(0, login.finishes)
        assertFalse(login.retired)
        assertEquals(AndroidLoginTransitionCoordinator.Admission.Reuse(false), coordinator.reserve(1, "login"))
    }

    @Test
    fun firstRouteFromOldAccountAfterSuccessfulLoginCannotBeginAnotherCycle() {
        val account = source("account") // Exists before logout but has never routed.
        replace(source("shell"))
        val token = launches.single().second
        coordinator.bind(1, token, "login")
        val authentication = coordinator.captureAuthentication(1)
        var destinations = 0
        assertTrue(completeAndroidAuthenticationAfterLaunch(coordinator, authentication, false) { destinations++ })

        replace(account)
        assertEquals(1, account.finishes)
        assertEquals(1, launches.size)
        replace(source("new-account"))
        assertEquals(2, launches.size)
        assertNotEquals(token, launches.last().second)
        assertEquals(1, destinations)
    }

    @Test
    fun unavailableSourceDoesNotReserveOrFinishAgain() {
        val source = source("destroyed", unavailable = true)
        replace(source)
        assertTrue(launches.isEmpty())
        assertEquals(0, source.finishes)
        assertTrue(coordinator.reserve(1, "live") is AndroidLoginTransitionCoordinator.Admission.Start)
    }

    @Test
    fun failedStartRollsBackTicketAndRetirementAndAllowsImmediateRetry() {
        val account = source("account")
        val shell = source("shell")
        var rejectedToken: String? = null
        val failure = runCatching {
            replace(account) { token ->
                rejectedToken = token
                throw IllegalStateException("startActivity failed")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(account.retired)
        assertFalse(shell.retired)
        assertEquals(0, account.finishes)
        assertNull(coordinator.bind(1, rejectedToken, "cancelled-login"))

        replace(account)
        assertEquals(1, launches.size)
        assertEquals(1, account.finishes)
        assertNotEquals(rejectedToken, launches.single().second)
    }

    @Test
    fun documentTasksHaveIndependentAdmissionAndAuthentication() {
        replace(source("account-a", taskId = 1))
        val firstAuthentication = coordinator.captureAuthentication(1)
        replace(source("account-b", taskId = 2))
        assertEquals(listOf(1, 2), launches.map { it.first })
        assertTrue(coordinator.completeAuthentication(firstAuthentication))
        assertTrue(coordinator.reserve(1, "next-a") is AndroidLoginTransitionCoordinator.Admission.Start)
        assertTrue(coordinator.reserve(2, "late-b") is AndroidLoginTransitionCoordinator.Admission.Reuse)
    }

    @Test
    fun recreationTransfersOwnerAndOldDestroyCannotReleaseNewOwner() {
        val token = reserve()
        coordinator.bind(1, token, "old-login")
        val authentication = coordinator.captureAuthentication(1)
        coordinator.destroyed(1, token, "old-login", changingConfigurations = true)
        assertEquals(token, coordinator.bind(1, token, "recreated-login"))
        assertEquals(authentication, coordinator.captureAuthentication(1))
        coordinator.destroyed(1, token, "old-login", changingConfigurations = false)
        assertEquals(AndroidLoginTransitionCoordinator.Admission.Reuse(false), coordinator.reserve(1, "recreated-login"))

        coordinator.destroyed(1, token, "recreated-login", changingConfigurations = false)
        assertTrue(coordinator.reserve(1, "next-account") is AndroidLoginTransitionCoordinator.Admission.Start)
    }

    @Test
    fun explicitLoginWithoutTicketSupersedesOldOwnerWithoutOldDestroyClearingIt() {
        val oldToken = reserve()
        coordinator.bind(1, oldToken, "old-login")
        val newToken = coordinator.bind(1, null, "explicit-login")
        assertNotEquals(oldToken, newToken)
        coordinator.destroyed(1, oldToken, "old-login", changingConfigurations = false)
        coordinator.cancelPending(1, oldToken)
        assertNull(coordinator.bind(1, oldToken, "late-old-login"))
        assertEquals(AndroidLoginTransitionCoordinator.Admission.Reuse(false), coordinator.reserve(1, "explicit-login"))
    }

    @Test
    fun restoredForeignTicketIsRebasedAndCannotBeReplayedAfterCompletion() {
        val restoredToken = coordinator.bind(1, "previous-process:1", "restored-login")
        assertTrue(requireNotNull(restoredToken).startsWith("test-process:"))
        assertTrue(coordinator.completeAuthentication(coordinator.captureAuthentication(1)))
        assertNull(coordinator.bind(1, restoredToken, "late-restoration"))
        assertNull(coordinator.bind(1, "previous-process:1", "old-intent-replay"))
    }

    @Test
    fun staleAuthenticationNeverLaunchesOrClearsNewerLoginEvenAfterItIsDestroyed() {
        val firstToken = reserve()
        coordinator.bind(1, firstToken, "first-login")
        val staleAuthentication = coordinator.captureAuthentication(1)
        coordinator.completeAuthentication(staleAuthentication)
        val nextToken = reserve()
        coordinator.bind(1, nextToken, "next-login")
        var destinations = 0

        assertFalse(completeAndroidAuthenticationAfterLaunch(coordinator, staleAuthentication, false) { destinations++ })
        assertEquals(AndroidLoginTransitionCoordinator.Admission.Reuse(false), coordinator.reserve(1, "next-login"))
        coordinator.destroyed(1, nextToken, "next-login", changingConfigurations = false)
        assertFalse(completeAndroidAuthenticationAfterLaunch(coordinator, staleAuthentication, false) { destinations++ })
        assertEquals(0, destinations)
    }

    @Test
    fun retiredAuthenticationCannotLaunchAndFailedDestinationKeepsReservation() {
        reserve()
        val authentication = coordinator.captureAuthentication(1)
        var destinations = 0
        assertFalse(completeAndroidAuthenticationAfterLaunch(coordinator, authentication, true) { destinations++ })
        val failure = runCatching {
            completeAndroidAuthenticationAfterLaunch(coordinator, authentication, false) {
                throw IllegalStateException("destination failed")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(coordinator.reserve(1, "late-shell") is AndroidLoginTransitionCoordinator.Admission.Reuse)
        assertTrue(completeAndroidAuthenticationAfterLaunch(coordinator, authentication, false) { destinations++ })
        assertEquals(1, destinations)
        assertTrue(coordinator.reserve(1, "next-account") is AndroidLoginTransitionCoordinator.Admission.Start)
    }

    @Test
    fun authChildCanFinishAfterLoginOwnerDestructionWhenNoNewCycleStarted() {
        val token = reserve()
        coordinator.bind(1, token, "login")
        val childAuthentication = coordinator.captureAuthentication(1)
        coordinator.destroyed(1, token, "login", changingConfigurations = false)
        assertTrue(completeAndroidAuthenticationAfterLaunch(coordinator, childAuthentication, false) {})
    }

    private fun reserve(): String =
        (coordinator.reserve(1, "account") as AndroidLoginTransitionCoordinator.Admission.Start).token

    private class Source(val owner: String, val taskId: Int, var unavailable: Boolean) {
        var retired = false
        var finishes = 0
    }

    private fun source(owner: String, taskId: Int = 1, unavailable: Boolean = false): Source =
        Source(owner, taskId, unavailable).also(sources::add)

    private fun replace(source: Source, launch: (String) -> Unit = { launches += source.taskId to it }) {
        replaceAndroidLogin(
            coordinator = coordinator,
            taskId = source.taskId,
            sourceOwner = source.owner,
            sourceUnavailable = source.unavailable,
            sourceRetired = source.retired,
            setSourceRetired = { source.retired = it },
            startLogin = launch,
            retireTaskSources = { sources.filter { it.taskId == source.taskId }.forEach { it.retired = true } },
            finishSource = { source.finishes++; source.unavailable = true },
        )
    }
}
