package build.raft.app.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Settings › Account › Signed-in devices. Bodies are the server's, botiverse/slock staging
 * 33f441958f82ce0178cc7f48232d41b20907e50c: routes/mySessions.ts (GET / DELETE / revoke-others and
 * their refusals, as routes/mySessions.api.test.ts asserts), routes/secondFactor.ts
 * POST /reauth/password, and the step-up refusal of stepUpGate.ts.
 */
class SignedInDevicesTest {
    private val list =
        """{"sessions":[
            {"id":"fam-current","platform":"android","version":"1.27.0","createdAt":"2026-11-01T08:00:00.000Z","lastActiveAt":"2026-11-18T09:00:00.000Z","current":true,"secondFactorVerified":true,"lostDeviceReach":{"computers":1,"agentKeys":0}},
            {"id":"fam-web","platform":"browser","version":null,"createdAt":"2026-10-02T08:00:00.000Z","lastActiveAt":"2026-11-17T09:00:00.000Z","current":false,"secondFactorVerified":false,"lostDeviceReach":{"computers":1,"agentKeys":2}},
            {"id":"fam-old","platform":null,"version":null,"createdAt":"2025-01-02T08:00:00.000Z","lastActiveAt":"2025-02-17T09:00:00.000Z","current":false,"lostDeviceReach":{"computers":0,"agentKeys":0}},
            {"id":"broken","platform":"ios"}
        ],"othersLostDeviceReach":{"computers":1,"agentKeys":2}}"""
    private val onlyThisOne =
        """{"sessions":[{"id":"fam-current","platform":"ios","version":"1.27.0","createdAt":"2026-11-01T08:00:00.000Z","lastActiveAt":"2026-11-18T09:00:00.000Z","current":true,"lostDeviceReach":{"computers":0,"agentKeys":0}}],"othersLostDeviceReach":{"computers":2,"agentKeys":0}}"""
    private val reauthRequired = """{"error":"Confirm it's you again before signing out a sign-in.","code":"reauth_required"}"""
    private val stepUpRequired =
        """{"error":"Confirm it's you with your second step before signing out a lost device.","code":"second_factor_step_up_required","methods":["totp","backup_code","passkey"]}"""
    private val notFound = """{"error":"That sign-in was not found.","code":"session_not_found"}"""

    @Test
    fun parsesTheListAsTheWebDoes() {
        val parsed = assertNotNull(parseSharedSignedInDevices(list))
        assertEquals(listOf("fam-current", "fam-web", "fam-old"), parsed.sessions.map { it.id }, "a row without times is left out")
        val current = parsed.sessions[0]
        assertTrue(current.current)
        assertEquals(SignedInDevicePlatform.Android, current.platform)
        assertEquals("1.27.0", current.version)
        assertEquals(SignedInDeviceLostReach(1, 2), parsed.sessions[1].lostDeviceReach)
        assertEquals(SignedInDevicePlatform.Web, parsed.sessions[1].platform)
        assertNull(parsed.sessions[1].version)
        assertEquals(SignedInDevicePlatform.Unknown, parsed.sessions[2].platform)
        assertEquals(SignedInDeviceLostReach(1, 2), parsed.othersLostDeviceReach)
        assertTrue(parsed.othersListed)
        assertTrue(parsed.canSignOutOthers)

        val alone = assertNotNull(parseSharedSignedInDevices(onlyThisOne))
        assertFalse(alone.othersListed)
        assertTrue(alone.canSignOutOthers, "what earlier signed-out ones approved can still be disconnected")
        assertNull(parseSharedSignedInDevices("[]"))
        assertEquals(SignedInDeviceLostReach(0, 0), parseSharedSignedInDevices("""{"sessions":[],"othersLostDeviceReach":{"computers":-1,"agentKeys":"2"}}""")?.othersLostDeviceReach)
    }

    @Test
    fun platformsAreTheClientsOwnClaims() {
        assertEquals(SignedInDevicePlatform.Web, signedInDevicePlatform("pwa"))
        assertEquals(SignedInDevicePlatform.Desktop, signedInDevicePlatform("desktop_app"))
        assertEquals(SignedInDevicePlatform.Ios, signedInDevicePlatform("ios"))
        assertEquals(SignedInDevicePlatform.HarmonyOs, signedInDevicePlatform("harmonyos"))
        assertEquals(SignedInDevicePlatform.Unknown, signedInDevicePlatform("raft_cli"))
    }

    @Test
    fun parsesTheWritesAnswers() {
        assertEquals(SignedInDeviceCascade.Done, parseSharedSignedInDeviceCascade("""{"cascade":"done"}"""))
        assertEquals(SignedInDeviceCascade.Pending, parseSharedSignedInDeviceCascade("""{"cascade":"pending"}"""))
        assertEquals(SignedInDeviceCascade.None, parseSharedSignedInDeviceCascade("""{"cascade":"none"}"""))
        assertEquals(SignedInDeviceCascade.None, parseSharedSignedInDeviceCascade(null))
        assertEquals(SignedInDevicesRevokedOthers(3, SignedInDeviceCascade.Done), parseSharedSignedInDevicesRevokedOthers("""{"revoked":3,"cascade":"done"}"""))
        val body = Json.parseToJsonElement(buildSharedSignedInDeviceRevokeBody(SignedInDeviceRevokeReason.LostDevice)).jsonObject
        assertEquals("lost_device", body["reason"]?.jsonPrimitive?.content)
        assertEquals(setOf("reason"), body.keys)
    }

    @Test
    fun parsesThePasswordReauthentication() {
        val lockedUntil = "2026-11-18T10:15:00.000Z"
        assertEquals(SignedInDevicesReauthResult.Verified, parseSharedSignedInDevicesReauthResult(204, null))
        assertEquals(
            SignedInDevicesReauthResult.PasswordIncorrect,
            parseSharedSignedInDevicesReauthResult(400, """{"error":"That password is incorrect.","code":"password_incorrect"}""")
        )
        assertEquals(
            SignedInDevicesReauthResult.Locked(Instant.parse(lockedUntil).toEpochMilliseconds()),
            parseSharedSignedInDevicesReauthResult(429, """{"error":"Too many wrong passwords.","code":"reauth_locked","lockedUntil":"$lockedUntil"}""")
        )
        assertEquals(SignedInDevicesReauthResult.Failed, parseSharedSignedInDevicesReauthResult(500, "oops"))
        assertEquals(SecondFactorRecoveryEmailResult.Sent, parseSharedRecoveryEmailResult(200))
        assertEquals(SecondFactorRecoveryEmailResult.TooManyRequests, parseSharedRecoveryEmailResult(429))
        assertEquals(SecondFactorRecoveryEmailResult.Failed, parseSharedRecoveryEmailResult(500))
    }

    @Test
    fun aSignOutAsksForThePasswordThenSignsOutAndReadsAgain() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", "me/sessions", 200, list)
                respond("DELETE", "me/sessions/fam-old", 403, reauthRequired)
                respond("DELETE", "me/sessions/fam-old", 200, """{"cascade":"none"}""")
                respond("POST", "auth/second-factor/reauth/password", 400, """{"error":"That password is incorrect.","code":"password_incorrect"}""")
                respond("POST", "auth/second-factor/reauth/password", 204, "")
            }
        val store = store(network)
        store.dispatch(SharedSignedInDevicesIntent.AccountKnown(passwordConfigured = true, email = "me@raft.build"))
        store.dispatch(SharedSignedInDevicesIntent.Refresh)
        runCurrent()
        val old = store.state.value.list!!.sessions.single { it.id == "fam-old" }

        store.dispatch(SharedSignedInDevicesIntent.OpenSignOut(old))
        val question = assertNotNull(store.state.value.signOut)
        assertFalse(question.hasChoice, "nothing to disconnect: one question")
        store.dispatch(SharedSignedInDevicesIntent.Confirm)
        runCurrent()
        val reauth = assertNotNull(store.state.value.reauth, "reauth_required asks for the password")
        assertTrue(reauth.password)
        assertFalse(reauth.canSubmit)

        store.dispatch(SharedSignedInDevicesIntent.ReauthInput("wrong"))
        store.dispatch(SharedSignedInDevicesIntent.ReauthSubmit)
        runCurrent()
        assertTrue(store.state.value.reauth!!.passwordIncorrect)

        store.dispatch(SharedSignedInDevicesIntent.ReauthInput("right"))
        store.dispatch(SharedSignedInDevicesIntent.ReauthSubmit)
        runCurrent()

        assertNull(store.state.value.reauth)
        assertNull(store.state.value.signOut)
        assertEquals(SignedInDevicesNotice.SignedOut(SignedInDevicePlatform.Unknown, lost = false, cascadePending = false), store.state.value.notice)
        val deletes = network.requests.filter { it.method == "DELETE" }
        assertEquals(2, deletes.size, "sent again once after the password")
        deletes.forEach { assertEquals("""{"reason":"sign_out"}""", it.bodyText) }
        assertEquals(2, network.requests.count { it.method == "GET" && it.path == "me/sessions" }, "the list is read again")
    }

    @Test
    fun closingThePasswordKeepsTheQuestionOpen() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", "me/sessions", 200, list)
                respond("DELETE", "me/sessions/fam-old", 403, reauthRequired)
            }
        val store = store(network)
        store.dispatch(SharedSignedInDevicesIntent.Refresh)
        runCurrent()
        store.dispatch(SharedSignedInDevicesIntent.OpenSignOut(store.state.value.list!!.sessions.single { it.id == "fam-old" }))
        store.dispatch(SharedSignedInDevicesIntent.Confirm)
        runCurrent()
        assertNotNull(store.state.value.reauth)

        store.dispatch(SharedSignedInDevicesIntent.ReauthClose)
        runCurrent()
        assertNull(store.state.value.reauth)
        val question = assertNotNull(store.state.value.signOut)
        assertFalse(question.submitting)
        assertFalse(question.failed, "closing is not a failure")
        assertNull(store.state.value.notice)
    }

    @Test
    fun withoutAPasswordTheDialogOnlySaysToSignInAgain() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", "me/sessions", 200, list)
                respond("DELETE", "me/sessions/fam-old", 403, reauthRequired)
            }
        val store = store(network)
        store.dispatch(SharedSignedInDevicesIntent.AccountKnown(passwordConfigured = false, email = "me@raft.build"))
        store.dispatch(SharedSignedInDevicesIntent.Refresh)
        runCurrent()
        store.dispatch(SharedSignedInDevicesIntent.OpenSignOut(store.state.value.list!!.sessions.single { it.id == "fam-old" }))
        store.dispatch(SharedSignedInDevicesIntent.Confirm)
        runCurrent()
        val reauth = assertNotNull(store.state.value.reauth)
        assertFalse(reauth.password)
        store.dispatch(SharedSignedInDevicesIntent.ReauthInput("anything"))
        store.dispatch(SharedSignedInDevicesIntent.ReauthSubmit)
        runCurrent()
        assertEquals(0, network.requests.count { it.path == "auth/second-factor/reauth/password" })

        // Done: the waiting sign-out ends quietly (and no coroutine is left waiting on the dialog).
        store.dispatch(SharedSignedInDevicesIntent.ReauthClose)
        runCurrent()
        assertNull(store.state.value.reauth)
        assertFalse(store.state.value.signOut!!.submitting)
    }

    @Test
    fun aLostDeviceStepsUpAndCanSignItOutWithThePasswordInstead() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", "me/sessions", 200, list)
                respond("DELETE", "me/sessions/fam-web", 403, stepUpRequired)
                respond("DELETE", "me/sessions/fam-web", 200, """{"cascade":"none"}""")
            }
        val store = store(network)
        store.dispatch(SharedSignedInDevicesIntent.AccountKnown(passwordConfigured = true, email = "me@raft.build"))
        store.dispatch(SharedSignedInDevicesIntent.Refresh)
        runCurrent()
        store.dispatch(SharedSignedInDevicesIntent.OpenSignOut(store.state.value.list!!.sessions.single { it.id == "fam-web" }))
        val question = assertNotNull(store.state.value.signOut)
        assertTrue(question.hasChoice)
        assertEquals(SignedInDeviceRevokeReason.SignOut, question.reason, "Sign out is the default")
        store.dispatch(SharedSignedInDevicesIntent.ChooseReason(SignedInDeviceRevokeReason.LostDevice))
        assertTrue(store.state.value.signOut!!.disconnects)
        store.dispatch(SharedSignedInDevicesIntent.Confirm)
        runCurrent()

        val stepUp = assertNotNull(store.state.value.stepUp, "lost_device asks for the second step")
        assertEquals(
            SecondFactorStepUpRecovery("me@raft.build", SecondFactorStepUpLostDevice(SecondFactorStepUpSignOutScope.One, password = true)),
            stepUp.recovery
        )
        assertTrue(stepUp.passkeyListed)
        store.dispatch(SharedSignedInDevicesIntent.StepUp(SecondFactorStepUpIntent.ShowLost))
        runCurrent()
        assertEquals(SecondFactorStepUpView.Lost, store.state.value.stepUp?.view)
        store.dispatch(SharedSignedInDevicesIntent.StepUp(SecondFactorStepUpIntent.SignOutInstead))
        runCurrent()

        assertNull(store.state.value.stepUp)
        assertNull(store.state.value.signOut)
        assertEquals(SignedInDevicesNotice.SignedOut(SignedInDevicePlatform.Web, lost = false, cascadePending = false), store.state.value.notice)
        assertEquals(
            listOf("""{"reason":"lost_device"}""", """{"reason":"sign_out"}"""),
            network.requests.filter { it.method == "DELETE" }.map { it.bodyText }
        )
    }

    @Test
    fun aSignInGoneMeanwhileClosesQuietlyAndOtherFailuresStay() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", "me/sessions", 200, list)
                respond("DELETE", "me/sessions/fam-old", 500, """{"error":"Failed to sign out that sign-in"}""")
                respond("DELETE", "me/sessions/fam-old", 404, notFound)
            }
        val store = store(network)
        store.dispatch(SharedSignedInDevicesIntent.Refresh)
        runCurrent()
        store.dispatch(SharedSignedInDevicesIntent.OpenSignOut(store.state.value.list!!.sessions.single { it.id == "fam-old" }))
        store.dispatch(SharedSignedInDevicesIntent.Confirm)
        runCurrent()
        assertTrue(store.state.value.signOut!!.failed)

        store.dispatch(SharedSignedInDevicesIntent.Confirm)
        runCurrent()
        assertNull(store.state.value.signOut)
        assertNull(store.state.value.notice)
    }

    @Test
    fun withNoOtherSignInListedItOnlyDisconnectsTheLeftovers() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", "me/sessions", 200, onlyThisOne)
                respond("POST", "me/sessions/revoke-others", 200, """{"revoked":0,"cascade":"pending"}""")
            }
        val store = store(network)
        store.dispatch(SharedSignedInDevicesIntent.Refresh)
        runCurrent()
        store.dispatch(SharedSignedInDevicesIntent.OpenSignOutOthers)
        val question = assertNotNull(store.state.value.signOut)
        assertEquals(SignedInDevicesSignOutTarget.Leftovers, question.target)
        assertFalse(question.hasChoice)
        assertEquals(SignedInDeviceRevokeReason.LostDevice, question.reasonToSend)
        store.dispatch(SharedSignedInDevicesIntent.Confirm)
        runCurrent()
        assertEquals(SignedInDevicesNotice.LeftoversDone(cascadePending = true), store.state.value.notice)
        assertEquals("""{"reason":"lost_device"}""", network.requests.single { it.path == "me/sessions/revoke-others" }.bodyText)
    }

    @Test
    fun theSectionShowsRowsAFailureOrNothing() = runTest {
        val missing = store(ScriptedAgentControlsNetworkClient().apply { respond("GET", "me/sessions", 404, """{"error":"Not found"}""") })
        missing.dispatch(SharedSignedInDevicesIntent.Refresh)
        runCurrent()
        assertTrue(missing.state.value.unavailable)
        assertFalse(missing.state.value.visible)

        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", "me/sessions", 500, """{"error":"Failed to list your sign-ins"}""")
                respond("GET", "me/sessions", 200, list)
                respond("GET", "me/sessions", 500, """{"error":"Failed to list your sign-ins"}""")
            }
        val store = store(network)
        assertFalse(store.state.value.visible, "nothing while the first read runs")
        store.dispatch(SharedSignedInDevicesIntent.Refresh)
        runCurrent()
        assertTrue(store.state.value.visible)
        assertNull(store.state.value.list, "the failure, with Try again")
        store.dispatch(SharedSignedInDevicesIntent.Refresh)
        runCurrent()
        assertEquals(3, store.state.value.list?.sessions?.size)
        store.dispatch(SharedSignedInDevicesIntent.Refresh)
        runCurrent()
        assertEquals(3, store.state.value.list?.sessions?.size, "a failed re-read keeps the rows")
        assertIs<SignedInDeviceList>(store.state.value.list)
    }

    @Test
    fun parsesDisconnectPreviewAndBuildsQueryPaths() {
        val parsed =
            assertNotNull(
                parseSharedDisconnectPreview(
                    """{"computers":[
                        {"kind":"computer","id":"comp-1","name":"Studio Mac","serverId":"srv-1","serverName":"Botiverse","agentCount":3},
                        {"kind":"legacy_machine","id":"mach-2","name":"Old Box","serverId":"srv-2","serverName":"Lab","agentCount":0},
                        {"id":"broken","name":"Missing server"}
                    ],"agentKeys":4}"""
                )
            )
        assertEquals(2, parsed.computers.size)
        assertEquals(SecurityRevokePreviewComputerKind.Computer, parsed.computers[0].kind)
        assertEquals("Studio Mac", parsed.computers[0].name)
        assertEquals("Botiverse", parsed.computers[0].serverName)
        assertEquals(3, parsed.computers[0].agentCount)
        assertEquals(SecurityRevokePreviewComputerKind.LegacyMachine, parsed.computers[1].kind)
        assertEquals(0, parsed.computers[1].agentCount)
        assertEquals(4, parsed.agentKeys)
        assertNull(parseSharedDisconnectPreview("[]"))
        assertEquals(
            "me/sessions/disconnect-preview?action=lost_device_one&familyId=fam%2F1",
            signedInDisconnectPreviewPath(SignedInDisconnectPreviewTarget.LostDeviceOne("fam/1")),
        )
        assertEquals(
            "me/sessions/disconnect-preview?action=lost_device_others",
            signedInDisconnectPreviewPath(SignedInDisconnectPreviewTarget.LostDeviceOthers),
        )
        assertEquals(
            "me/sessions/disconnect-preview?action=password_change",
            signedInDisconnectPreviewPath(SignedInDisconnectPreviewTarget.PasswordChange),
        )
    }

    @Test
    fun openingSignOutFetchesDisconnectPreviewOnlyWhenComputersAreCounted() = runTest {
        val keysOnlyList =
            """{"sessions":[
                {"id":"fam-current","platform":"android","version":"1.27.0","createdAt":"2026-11-01T08:00:00.000Z","lastActiveAt":"2026-11-18T09:00:00.000Z","current":true,"lostDeviceReach":{"computers":0,"agentKeys":0}},
                {"id":"fam-keys","platform":"browser","version":null,"createdAt":"2026-10-02T08:00:00.000Z","lastActiveAt":"2026-11-17T09:00:00.000Z","current":false,"lostDeviceReach":{"computers":0,"agentKeys":3}},
                {"id":"fam-comp","platform":"desktop_app","version":"1.27.0","createdAt":"2026-10-03T08:00:00.000Z","lastActiveAt":"2026-11-17T09:00:00.000Z","current":false,"lostDeviceReach":{"computers":1,"agentKeys":1}}
            ],"othersLostDeviceReach":{"computers":1,"agentKeys":4}}"""
        val previewBody =
            """{"computers":[{"kind":"computer","id":"comp-1","name":"Studio Mac","serverId":"srv-1","serverName":"Botiverse","agentCount":2}],"agentKeys":1}"""
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", "me/sessions", 200, keysOnlyList)
                respond("GET", "me/sessions/disconnect-preview?action=lost_device_one&familyId=fam-comp", 200, previewBody)
                respond("GET", "me/sessions/disconnect-preview?action=lost_device_others", 200, previewBody)
            }
        val store = store(network)
        store.dispatch(SharedSignedInDevicesIntent.Refresh)
        runCurrent()

        // Keys-only device must NOT fetch disconnect preview (`slock#17081`).
        val keysOnly = store.state.value.list!!.sessions.single { it.id == "fam-keys" }
        store.dispatch(SharedSignedInDevicesIntent.OpenSignOut(keysOnly))
        runCurrent()
        assertEquals(0, network.requests.count { it.path.startsWith("me/sessions/disconnect-preview") })
        assertNull(store.state.value.signOut?.disconnectPreview)
        store.dispatch(SharedSignedInDevicesIntent.CloseSignOut)

        // Device with computers fetches lost_device_one preview and discards it on close.
        val withComputer = store.state.value.list!!.sessions.single { it.id == "fam-comp" }
        store.dispatch(SharedSignedInDevicesIntent.OpenSignOut(withComputer))
        runCurrent()
        assertEquals(1, network.requests.count { it.path == "me/sessions/disconnect-preview?action=lost_device_one&familyId=fam-comp" })
        assertEquals("Studio Mac", store.state.value.signOut?.disconnectPreview?.computers?.single()?.name)
        store.dispatch(SharedSignedInDevicesIntent.CloseSignOut)
        assertNull(store.state.value.signOut)

        // Others fetches lost_device_others preview.
        store.dispatch(SharedSignedInDevicesIntent.OpenSignOutOthers)
        runCurrent()
        assertEquals(1, network.requests.count { it.path == "me/sessions/disconnect-preview?action=lost_device_others" })
        assertEquals(1, store.state.value.signOut?.disconnectPreview?.computers?.size)
    }

    private fun TestScope.store(network: ScriptedAgentControlsNetworkClient) =
        SharedSignedInDevicesStore(NetworkSignedInDevicesDataSource(network), this)
}
