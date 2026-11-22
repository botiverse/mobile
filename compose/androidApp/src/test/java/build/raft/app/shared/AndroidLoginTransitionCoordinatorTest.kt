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
