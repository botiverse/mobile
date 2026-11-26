package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Wire parsing tests for Computer `attention` (`ReadinessAttention`) and `readiness`
 * (`ComputerReadiness`) from `GET /api/servers/:id/machines` (`#wg-mobile-sync:#337`,
 * botiverse/slock #14554, #14587, #14590, #14602).
 */
class ComputerReadinessAttentionParsingTest {
    private fun parseJson(raw: String) = Json.parseToJsonElement(raw)

    @Test
    fun parsesOwnerAttentionWithAllResourceKindsWaitingAndLoginSteps() {
        val attention =
            assertNotNull(
                parseComputerReadinessAttention(
                    parseJson(
                        """
                        {
                          "count": 3,
                          "items": [
                            {
                              "code": "verification_required",
                              "resource": {"kind": "login", "key": "shared:claude"},
                              "actor": "computer_owner",
                              "actorName": "Olive",
                              "action": "verify_account",
                              "agentIds": ["a1", "a2"],
                              "agentCount": 2
                            },
                            {
                              "code": "runtime_missing",
                              "resource": {"kind": "runtime", "runtime": "codex"},
                              "actor": "computer_owner",
                              "actorName": "Olive",
                              "action": "fix",
                              "agentIds": ["a3"],
                              "agentCount": 1
                            },
                            {
                              "code": "computer_update_available",
                              "resource": {"kind": "computer"},
                              "actor": "computer_owner",
                              "actorName": "Olive",
                              "action": "update_computer",
                              "agentIds": [],
                              "agentCount": 0
                            }
                          ],
                          "waitingAgents": 4,
                          "waitingUntil": 1794398400000
                        }
                        """.trimIndent(),
                    ),
                ),
            )

        assertEquals(3, attention.count)
        assertEquals(3, attention.items.size)
        assertEquals(4, attention.waitingAgents)
        assertEquals(1_794_398_400_000L, attention.waitingUntilMs)

        val first = attention.items[0]
        assertEquals("verification_required", first.code)
        assertEquals(ComputerReadinessResource.Login("shared:claude"), first.resource)
        assertEquals(ComputerReadinessActor.ComputerOwner, first.actor)
        assertEquals("Olive", first.actorName)
        assertEquals(ComputerReadinessAction.VerifyAccount, first.action)
        assertEquals(listOf("a1", "a2"), first.agentIds)
        assertEquals(2, first.agentCount)

        assertEquals(ComputerReadinessResource.Runtime("codex"), attention.items[1].resource)
        assertEquals(ComputerReadinessResource.Computer, attention.items[2].resource)
        assertEquals(ComputerReadinessAction.UpdateComputer, attention.items[2].action)
        assertEquals(
            listOf(ComputerLoginStep("claude", ComputerLoginStepAction.VerifyAccount)),
            attention.toLoginSteps(),
        )
    }

    @Test
    fun parsesMemberAndGuestTrimmedAttentionPreservingGuestPrivacy() {
        val member =
            assertNotNull(
                parseComputerReadinessAttention(
                    parseJson(
                        """
                        {
                          "count": 1,
                          "items": [
                            {"actor": "computer_owner", "actorName": "Olive", "action": "fix", "agentCount": 2}
                          ],
                          "waitingAgents": 0
                        }
                        """.trimIndent(),
                    ),
                ),
            )
        val memberItem = member.items.single()
        assertNull(memberItem.code)
        assertNull(memberItem.resource)
        assertNull(memberItem.agentIds)
        assertEquals("Olive", memberItem.actorName)
        assertEquals(ComputerReadinessAction.Fix, memberItem.action)
        assertEquals(2, memberItem.agentCount)
        assertEquals(emptyList(), member.toLoginSteps())

        // Guest viewer (#14602): server omits actorName, code, resource, agentIds.
        val guest =
            assertNotNull(
                parseComputerReadinessAttention(
                    parseJson(
                        """
                        {
                          "count": 1,
                          "items": [
                            {"actor": "computer_owner", "action": "fix", "agentCount": 1}
                          ],
                          "waitingAgents": 0
                        }
                        """.trimIndent(),
                    ),
                ),
            )
        val guestItem = guest.items.single()
        assertNull(guestItem.code)
        assertNull(guestItem.resource)
        assertNull(guestItem.actorName)
        assertEquals(ComputerReadinessActor.ComputerOwner, guestItem.actor)
        assertEquals(ComputerReadinessAction.Fix, guestItem.action)
        assertEquals(1, guestItem.agentCount)
    }

    @Test
    fun attentionParsingIsLenientForUnknownCodesActionsAndMalformedItems() {
        val parsed =
            assertNotNull(
                parseComputerReadinessAttention(
                    parseJson(
                        """
                        {
                          "count": 2,
                          "items": [
                            {
                              "code": "future_code_v2",
                              "resource": {"kind": "login_window", "key": "shared:claude", "window": "seven_day_opus"},
                              "actor": "agent_owner",
                              "action": "brand_new_action",
                              "agentCount": 1
                            },
                            {"actor": "unknown_actor", "action": "fix", "agentCount": 1},
                            "not-an-object",
                            {"actor": "server_admin", "action": "update_computer", "agentCount": 0}
                          ],
                          "waitingAgents": 0
                        }
                        """.trimIndent(),
                    ),
                ),
            )

        assertEquals(2, parsed.count)
        assertEquals(2, parsed.items.size)
        assertEquals("future_code_v2", parsed.items[0].code)
        assertEquals(ComputerReadinessResource.LoginWindow("shared:claude", "seven_day_opus"), parsed.items[0].resource)
        assertEquals(ComputerReadinessAction.Fix, parsed.items[0].action)
        assertEquals(ComputerReadinessActor.ServerAdmin, parsed.items[1].actor)
        assertEquals(ComputerReadinessAction.UpdateComputer, parsed.items[1].action)

        // Missing or unreadable count yields null so callers fall back cleanly.
        assertNull(parseComputerReadinessAttention(parseJson("""{"items":[]}""")))
        assertNull(parseComputerReadinessAttention(parseJson("null")))
    }

    @Test
    fun parsesComputerReadinessOwnFactsRuntimeMapAndDesktopParam() {
        val readiness =
            assertNotNull(
                parseComputerReadiness(
                    parseJson(
                        """
                        {
                          "state": "blocked",
                          "primary": {
                            "resource": {"kind": "computer"},
                            "code": "computer_too_old",
                            "behaviour": "blocked",
                            "since": 1794300000000,
                            "params": {"managedByDesktop": true}
                          },
                          "scope": "computer",
                          "actor": "computer_owner",
                          "actorName": "Olive",
                          "action": "update_computer",
                          "also": [
                            {
                              "resource": {"kind": "model", "key": "shared:claude", "model": "opus"},
                              "code": "quota_exhausted",
                              "behaviour": "waiting",
                              "since": 1794300000000,
                              "until": 1794398400000
                            },
                            {"resource": {"kind": "computer"}, "code": "INVALID CODE!", "behaviour": "blocked", "since": 1}
                          ],
                          "runtimes": {
                            "claude": {
                              "state": "blocked",
                              "primary": {
                                "resource": {"kind": "login", "key": "shared:claude"},
                                "code": "verification_required",
                                "behaviour": "blocked",
                                "since": 1794301000000
                              },
                              "scope": "computer",
                              "actor": "computer_owner",
                              "also": []
                            },
                            "codex": {
                              "state": "ready",
                              "primary": null,
                              "scope": null,
                              "actor": null,
                              "also": []
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
                ),
            )

        assertEquals(ComputerReadinessState.Blocked, readiness.own.state)
        assertEquals("computer_too_old", readiness.own.primary?.code)
        assertEquals(true, readiness.own.primary?.managedByDesktop)
        assertEquals(ComputerReadinessScope.Computer, readiness.own.scope)
        assertEquals(ComputerReadinessActor.ComputerOwner, readiness.own.actor)
        assertEquals("Olive", readiness.own.actorName)
        assertEquals(ComputerReadinessAction.UpdateComputer, readiness.own.action)
        assertEquals(1, readiness.own.also.size)
        assertEquals("quota_exhausted", readiness.own.also[0].code)
        assertEquals(ComputerReadinessResource.Model("shared:claude", "opus"), readiness.own.also[0].resource)
        assertEquals(1_794_398_400_000L, readiness.own.also[0].untilMs)

        val claude = assertNotNull(readiness.runtimes["claude"])
        assertTrue(claude.holds())
        assertEquals("verification_required", claude.primary?.code)
        assertEquals(ComputerReadinessResource.Login("shared:claude"), claude.primary?.resource)

        val codex = assertNotNull(readiness.runtimes["codex"])
        assertFalse(codex.holds())
        assertEquals(ComputerReadinessState.Ready, codex.state)
    }

    @Test
    fun restartingAndUpdatingWindowsHonorUntilExpiry() {
        val now = 1_794_312_000_000L
        val updating =
            ComputerReadinessSnapshot(
                own =
                    ComputerReadinessEntry(
                        state = ComputerReadinessState.Waiting,
                        primary =
                            ComputerReadinessFact(
                                resource = ComputerReadinessResource.Computer,
                                code = "computer_updating",
                                behaviour = ComputerReadinessBehaviour.Waiting,
                                sinceMs = now - 60_000L,
                                untilMs = now + 300_000L,
                            ),
                        scope = ComputerReadinessScope.Computer,
                    ),
            )
        assertTrue(updating.isComputerUpdatingWindow(nowMillis = now))
        assertFalse(updating.isComputerUpdatingWindow(nowMillis = now + 300_000L))
        assertFalse(updating.isComputerRestartingWindow(nowMillis = now))

        val restarting =
            ComputerReadinessSnapshot(
                own =
                    ComputerReadinessEntry(
                        state = ComputerReadinessState.Waiting,
                        primary =
                            ComputerReadinessFact(
                                resource = ComputerReadinessResource.Computer,
                                code = "computer_restarting",
                                behaviour = ComputerReadinessBehaviour.Waiting,
                                sinceMs = now - 60_000L,
                                untilMs = now + 240_000L,
                            ),
                        scope = ComputerReadinessScope.Computer,
                    ),
            )
        assertTrue(restarting.isComputerRestartingWindow(nowMillis = now))
        assertFalse(restarting.isComputerRestartingWindow(nowMillis = now + 240_000L))
        assertFalse(restarting.isComputerUpdatingWindow(nowMillis = now))
    }

    @Test
    fun settingsParserMapsAttentionAndReadinessAndFallsBackCleanlyWhenAbsent() {
        val snapshot =
            assertNotNull(
                parseSharedSettingsComputers(
                    """
                    {
                      "machines": [
                        {
                          "id": "m-new",
                          "name": "Oak Linux",
                          "status": "online",
                          "isComputer": true,
                          "attention": {
                            "count": 1,
                            "items": [{"actor": "computer_owner", "action": "fix", "agentCount": 1}],
                            "waitingAgents": 0
                          },
                          "readiness": {
                            "state": "ready",
                            "primary": null,
                            "scope": null,
                            "actor": null,
                            "also": [],
                            "runtimes": {}
                          }
                        },
                        {
                          "id": "m-old",
                          "name": "Legacy",
                          "status": "online",
                          "isComputer": true
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
        val byId = snapshot.computers.associateBy { it.id }
        assertEquals(1, byId.getValue("m-new").attention?.count)
        assertEquals(true, byId.getValue("m-new").serverNeedsAttention)
        assertEquals(ComputerReadinessState.Ready, byId.getValue("m-new").readiness?.own?.state)
        assertNull(byId.getValue("m-old").attention)
        assertNull(byId.getValue("m-old").readiness)
        assertNull(byId.getValue("m-old").serverNeedsAttention)
    }
}
