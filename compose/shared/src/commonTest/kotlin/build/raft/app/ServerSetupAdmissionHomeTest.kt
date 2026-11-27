package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.MOBILE_SERVER_SETUP_V1_FEATURE_FLAG_KEY
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.RaftServerSetupProjectionApi
import build.raft.app.data.RaftServerSetupProjectionRepository
import build.raft.app.model.ServerId
import build.raft.app.thread.key
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ServerSetupAdmissionHomeTest {
    private val memoryKey = ServerSetupAdmissionMemory.key("env", "account", "server")

    @BeforeTest
    fun clearMemory() = ServerSetupAdmissionMemory.clearForTest()

    @AfterTest
    fun clearMemoryAfter() = ServerSetupAdmissionMemory.clearForTest()

    /**
     * Setup shows only when the server's setup projection says `blocksChat`; no app feature flag
     * takes part (prod has no `enable_webview_onboarding_v0` row, which used to skip onboarding).
     * A failed check lets Home through.
     */
    @Test
    fun setupProjectionAloneDecidesBetweenSetupAndHome() = runTest {
        data class Case(val name: String, val expected: String, val allowsHome: Boolean, val response: () -> HomeNetworkResponse)
        val cases = listOf(
            Case("new owner", "Blocked", false) { projectionResponse(blocksChat = true, gateReason = "setup_required") },
            Case("set up", "Admitted", true) { projectionResponse(blocksChat = false, gateReason = "setup_complete") },
            Case("non-owner", "Admitted", true) { projectionResponse(blocksChat = false, gateReason = "insufficient_permission") },
            Case("server error", "Failed", true) { HomeNetworkResponse(code = 503, bodyText = null) },
            Case("timeout/offline", "Failed", true) { HomeNetworkResponse(code = 0, bodyText = null) },
            Case("bad body", "Failed", true) { HomeNetworkResponse(code = 200, bodyText = "<html>") },
            Case("transport threw", "Failed", true) { throw IllegalStateException("offline") },
        )
        for ((index, case) in cases.withIndex()) {
            val controller = controller { case.response() }
            val identity = ServerSetupAdmissionIdentity("env", "account", "server", "generation:$index")
            controller.begin(identity)
            val state = controller.load(identity)

            assertEquals(case.expected, state?.let { it::class.simpleName }, case.name)
            assertEquals(case.allowsHome, serverSetupAllowsHome(state), case.name)
            assertEquals(!case.allowsHome, serverSetupBlocksHome(state), case.name)
            assertTrue(serverSetupAdmissionResolved(state), case.name)
        }
        val loading = ServerSetupAdmissionState.Loading(identity(9))
        assertFalse(serverSetupAllowsHome(loading))
        assertFalse(serverSetupBlocksHome(loading))
        assertFalse(serverSetupAdmissionResolved(null))
    }

    /** Flag off, missing, not evaluated yet or failed: Home, and no setup-projection request at all. */
    @Test
    fun setupGateOffSendsNoProjectionRequestAndKeepsHome() = runTest {
        val flagResponses = listOf(
            null, // not evaluated yet
            HomeNetworkResponse(code = 200, bodyText = """{"evaluations":[]}"""),
            HomeNetworkResponse(code = 200, bodyText = flagBody(enabled = false)),
            HomeNetworkResponse(code = 503, bodyText = null),
            HomeNetworkResponse(code = 200, bodyText = flagBody(enabled = true)),
        )
        for ((index, flagResponse) in flagResponses.withIndex()) {
            ServerSetupAdmissionMemory.clearForTest()
            val flags = SharedServerFeatureFlags(
                object : RaftFeatureFlagsApi {
                    override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                        requireNotNull(flagResponse)
                }
            )
            if (flagResponse != null) flags.prefetch(ServerId("server"))
            val gateOn = serverSetupGateEnabled(flags, "server")
            var requests = 0
            val controller = controller {
                requests += 1
                projectionResponse(blocksChat = true, gateReason = "setup_required")
            }
            var state: ServerSetupAdmissionState? = null

            runServerSetupCheck(controller, identity(index), memoryKey, setupShowing = false, gateEnabled = gateOn) { state = it }

            val flagOn = index == flagResponses.lastIndex
            assertEquals(flagOn, gateOn, "case $index")
            assertEquals(if (flagOn) 1 else 0, requests, "case $index")
            assertEquals(!flagOn, serverSetupHostAllowsHome(gateOn, state, previouslyAllowed = false, homeShownBeforeGate = false), "case $index")
        }
    }

    @Test
    fun failedCheckLetsHomeThroughAndIsRetriedNextTimeWhileSetupFailureKeepsRetryScreen() = runTest {
        val responses = ArrayDeque(
            listOf(
                HomeNetworkResponse(code = 0, bodyText = null),
                projectionResponse(blocksChat = true, gateReason = "setup_required"),
                HomeNetworkResponse(code = 503, bodyText = null),
            )
        )
        var requests = 0
        val controller = controller {
            requests += 1
            responses.removeFirst()
        }
        var state: ServerSetupAdmissionState? = null

        runServerSetupCheck(controller, identity(1), memoryKey, setupShowing = false) { state = it }
        assertTrue(serverSetupAllowsHome(state))
        assertFalse(ServerSetupAdmissionMemory.contains(memoryKey))

        // Next foreground tries again; the owner is mid-setup.
        runServerSetupCheck(controller, identity(1), memoryKey, setupShowing = false) { state = it }
        assertIs<ServerSetupAdmissionState.Blocked>(state)

        // A failure while setup is on screen keeps the screen (Retry) instead of dropping to Home.
        runServerSetupCheck(controller, identity(1), memoryKey, setupShowing = true) { state = it }
        assertTrue(serverSetupBlocksHome(state))
        assertFalse(serverSetupAllowsHome(state))
        assertEquals(3, requests)
    }

    @Test
    fun recheckAfterFailedColdStartKeepsHomeMountedWithoutBlankingToLoading() = runTest {
        val responses = ArrayDeque(
            listOf(
                HomeNetworkResponse(code = 0, bodyText = null),
                projectionResponse(blocksChat = false, gateReason = "setup_complete"),
            )
        )
        val controller = controller { responses.removeFirst() }
        val id = identity(10)
        var state: ServerSetupAdmissionState? = null
        var setupShown = false
        var homeAlreadyShown = false

        fun applyPublished(next: ServerSetupAdmissionState) {
            when (next) {
                is ServerSetupAdmissionState.Blocked -> {
                    setupShown = true
                    homeAlreadyShown = false
                }
                is ServerSetupAdmissionState.Admitted -> homeAlreadyShown = true
                is ServerSetupAdmissionState.Failed -> if (!next.duringSetup) homeAlreadyShown = true
                is ServerSetupAdmissionState.Loading -> Unit
            }
            state = next
        }

        // Cold start times out: Home is allowed through, memory does not record the failure.
        runServerSetupCheck(controller, id, memoryKey, setupShowing = setupShown, homeAlreadyShown = homeAlreadyShown) {
            applyPublished(it)
        }
        assertIs<ServerSetupAdmissionState.Failed>(state)
        assertTrue(homeAlreadyShown)
        assertTrue(serverSetupHostAllowsHome(gateEnabled = true, state = state, previouslyAllowed = false, homeShownBeforeGate = homeAlreadyShown))

        // Return to Home (e.g. back navigation / foreground) triggers a background re-check:
        // Loading must NEVER be published and allowsHome must stay true throughout.
        val recheckPublished = mutableListOf<ServerSetupAdmissionState>()
        runServerSetupCheck(controller, id, memoryKey, setupShowing = setupShown, homeAlreadyShown = homeAlreadyShown) { next ->
            recheckPublished += next
            applyPublished(next)
            assertTrue(
                serverSetupHostAllowsHome(
                    gateEnabled = true,
                    state = state,
                    previouslyAllowed = ServerSetupAdmissionMemory.contains(memoryKey),
                    homeShownBeforeGate = homeAlreadyShown,
                )
            )
        }
        assertTrue(recheckPublished.none { it is ServerSetupAdmissionState.Loading })
        assertIs<ServerSetupAdmissionState.Admitted>(state)
        assertTrue(ServerSetupAdmissionMemory.contains(memoryKey))
    }

    @Test
    fun recheckAfterHomeShownStillSwitchesToSetupWhenServerBlocksChat() = runTest {
        val responses = ArrayDeque(
            listOf(
                HomeNetworkResponse(code = 0, bodyText = null),
                projectionResponse(blocksChat = true, gateReason = "setup_required"),
                HomeNetworkResponse(code = 503, bodyText = null),
            )
        )
        val controller = controller { responses.removeFirst() }
        val id = identity(11)
        var state: ServerSetupAdmissionState? = null
        var setupShown = false
        var homeAlreadyShown = false

        fun applyPublished(next: ServerSetupAdmissionState) {
            when (next) {
                is ServerSetupAdmissionState.Blocked -> {
                    setupShown = true
                    homeAlreadyShown = false
                }
                is ServerSetupAdmissionState.Admitted -> homeAlreadyShown = true
                is ServerSetupAdmissionState.Failed -> if (!next.duringSetup) homeAlreadyShown = true
                is ServerSetupAdmissionState.Loading -> Unit
            }
            state = next
        }

        // Initial check fails; Home is shown.
        runServerSetupCheck(controller, id, memoryKey, setupShowing = setupShown, homeAlreadyShown = homeAlreadyShown) {
            applyPublished(it)
        }
        assertTrue(homeAlreadyShown)

        // Background re-check returns Blocked: no Loading flash, then Blocked takes over Home.
        val blockedPublished = mutableListOf<ServerSetupAdmissionState>()
        runServerSetupCheck(controller, id, memoryKey, setupShowing = setupShown, homeAlreadyShown = homeAlreadyShown) { next ->
            blockedPublished += next
            applyPublished(next)
        }
        assertTrue(blockedPublished.none { it is ServerSetupAdmissionState.Loading })
        assertIs<ServerSetupAdmissionState.Blocked>(state)
        assertFalse(serverSetupHostAllowsHome(gateEnabled = true, state = state, previouslyAllowed = false, homeShownBeforeGate = homeAlreadyShown))

        // Retrying while setup is on screen still publishes Loading and never drops back to Home.
        val retryPublished = mutableListOf<ServerSetupAdmissionState>()
        runServerSetupCheck(controller, id, memoryKey, setupShowing = setupShown, homeAlreadyShown = homeAlreadyShown) { next ->
            retryPublished += next
            applyPublished(next)
            assertFalse(serverSetupHostAllowsHome(gateEnabled = true, state = state, previouslyAllowed = false, homeShownBeforeGate = homeAlreadyShown))
        }
        assertIs<ServerSetupAdmissionState.Loading>(retryPublished.first())
        assertTrue(serverSetupBlocksHome(state))
    }
    private fun identity(generation: Int) = ServerSetupAdmissionIdentity("env", "account", "server", "generation:$generation")

    private fun controller(response: () -> HomeNetworkResponse) =
        ServerSetupAdmissionController(
            RaftServerSetupProjectionRepository(
                object : RaftServerSetupProjectionApi {
                    override suspend fun projection(serverId: ServerId): HomeNetworkResponse = response()
                }
            )
        )

    private fun flagBody(enabled: Boolean) =
        """{"evaluations":[{"key":"$MOBILE_SERVER_SETUP_V1_FEATURE_FLAG_KEY","enabled":$enabled}]}"""

    private fun projectionResponse(blocksChat: Boolean, gateReason: String) = HomeNetworkResponse(
        code = 200,
        bodyText = """{"surface":"${if (blocksChat) "computer_runtime" else "complete"}","phase":"${if (blocksChat) "in_progress" else "complete"}","blocksChat":$blocksChat,"allowedExits":[],"gateReason":"$gateReason"}""",
    )

    private fun projection(blocksChat: Boolean) =
        build.raft.app.data.ServerSetupProjection(
            surface = if (blocksChat) "computer_runtime" else "complete",
            phase = if (blocksChat) "in_progress" else "complete",
            currentStep = null,
            blocksChat = blocksChat,
            allowedExits = emptySet(),
            gateReason = null,
        )
}
