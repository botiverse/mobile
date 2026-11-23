package build.raft.app

import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SettingsComputerCreatorSnapshot
import build.raft.app.data.reconcileComputerOfflineObservedAtMs
import build.raft.app.format.RaftFormatLocale
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat12Hour
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.raftEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MembersAgentComputerOfflineTest {
    private fun machine(
        id: String = "m-1",
        name: String = "martin-mac",
        status: ComputerStatus = ComputerStatus.Offline,
        isComputer: Boolean = true,
        os: String? = "darwin",
        lastHeartbeat: String? = "2026-06-19T04:15:00Z",
        creatorUserId: String? = null,
        creator: SettingsComputerCreatorSnapshot? = null,
    ): ComputerSummary =
        ComputerSummary(
            id = id,
            name = name,
            status = status,
            statusVersion = 1L,
            apiKeyPrefix = null,
            runtimes = emptyList(),
            daemonVersion = "0.65.0",
            computerVersion = if (isComputer) "0.0.50" else null,
            isComputer = isComputer,
            hostname = null,
            os = os,
            description = null,
            lastHeartbeat = lastHeartbeat,
            createdAt = null,
            creatorUserId = creatorUserId,
            creator = creator,
        )

    private fun humanMember(
        id: String,
        displayName: String,
        handle: String = "@$id",
    ): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = displayName,
            handle = handle,
            kind = MemberKind.Human,
        )

    private val prodContext =
        SettingsComputerCommandContext(
            serverSlug = "acme",
            deploymentEnv = "production",
            serverUrl = RAFT_COMPUTER_DEFAULT_SERVER_URL,
        )

    @Test
    fun resolveReturnsNullWhenOnlineMissingOrGatedOut() {
        val online = machine(status = ComputerStatus.Online)
        val offline = machine(status = ComputerStatus.Offline)

        assertNull(resolveMembersAgentComputerOfflineCardState("m-1", listOf(online), commandContext = prodContext))
        assertNull(resolveMembersAgentComputerOfflineCardState(null, listOf(offline), commandContext = prodContext))
        assertNull(resolveMembersAgentComputerOfflineCardState("   ", listOf(offline), commandContext = prodContext))
        assertNull(resolveMembersAgentComputerOfflineCardState("other", listOf(offline), commandContext = prodContext))
        assertNull(resolveMembersAgentComputerOfflineCardState("m-1", listOf(offline), commandContext = prodContext, deleted = true))
        assertNull(resolveMembersAgentComputerOfflineCardState("m-1", listOf(offline), commandContext = prodContext, isExternalAgent = true))
        assertNull(
            resolveMembersAgentComputerOfflineCardState(
                "m-1",
                listOf(offline),
                commandContext = prodContext,
                isBoundedPublicProjection = true,
            ),
        )
    }

    @Test
    fun resolveBuildsStateForOfflineComputerWithTimestampPriorityAndRefusalSuppression() {
        val offline =
            machine(
                id = "m-1",
                name = "martin-mac",
                lastHeartbeat = "2026-06-19T03:00:00Z",
                creatorUserId = "u-martin",
            )
        val members = listOf(humanMember("u-martin", "Martin"))

        // Observed transition timestamp wins over lastHeartbeat.
        val withObserved =
            assertNotNull(
                resolveMembersAgentComputerOfflineCardState(
                    machineId = "m-1",
                    machines = listOf(offline),
                    offlineObservedAtMs = mapOf("m-1" to 1_781_845_000_000L),
                    serverMembers = members,
                    commandContext = prodContext,
                ),
            )
        assertEquals("m-1", withObserved.machineId)
        assertEquals("martin-mac", withObserved.computerName)
        assertEquals("Martin", withObserved.ownerName)
        assertEquals(1_781_845_000_000L, withObserved.sinceEpochMillis)
        assertNull(withObserved.sinceIso)
        assertEquals("raft-computer restart /acme", withObserved.restartCommand)

        // Without observed transition, falls back to lastHeartbeat.
        val withHeartbeat =
            assertNotNull(
                resolveMembersAgentComputerOfflineCardState(
                    machineId = "m-1",
                    machines = listOf(offline),
                    serverMembers = members,
                    commandContext = prodContext,
                ),
            )
        assertNull(withHeartbeat.sinceEpochMillis)
        assertEquals("2026-06-19T03:00:00Z", withHeartbeat.sinceIso)

        // Offline only from a refused Start suppresses both timestamps.
        val fromRefusal =
            assertNotNull(
                resolveMembersAgentComputerOfflineCardState(
                    machineId = "m-1",
                    machines = listOf(offline),
                    offlineObservedAtMs = mapOf("m-1" to 1_781_845_000_000L),
                    serverMembers = members,
                    commandContext = prodContext,
                    offlineFromRefusalIds = setOf("m-1"),
                ),
            )
        assertNull(fromRefusal.sinceEpochMillis)
        assertNull(fromRefusal.sinceIso)
    }

    @Test
    fun ownerResolutionMatchesWebComputerOwnerIdAndMemberLookup() {
        val creator = SettingsComputerCreatorSnapshot(id = "u-creator", name = "creator-handle", displayName = "Creator Name")
        val memberList =
            listOf(
                humanMember("u-owner", "Martin", "@martin"),
                humanMember("u-no-display", "   ", "@alex"),
                MemberProfile(id = MemberId("u-agent-same-id"), displayName = "Not A Human", handle = "@bot", kind = MemberKind.Agent),
            )

        // machines.userId wins over creator and resolves via serverMembers displayName.
        val ownedMachine = machine(creatorUserId = "u-owner", creator = creator)
        assertEquals("u-owner", membersComputerOwnerId(ownedMachine))
        assertEquals("Martin", membersComputerOwnerName(ownedMachine, memberList))

        // Blank displayName falls back to handle without leading '@'.
        val handleFallback = machine(creatorUserId = "u-no-display")
        assertEquals("alex", membersComputerOwnerName(handleFallback, memberList))

        // No userId falls back to creator id, then creator displayName if not in serverMembers.
        val creatorOnly = machine(creatorUserId = null, creator = creator)
        assertEquals("u-creator", membersComputerOwnerId(creatorOnly))
        assertEquals("Creator Name", membersComputerOwnerName(creatorOnly, memberList))

        // Departed owner (userId not in serverMembers and != creator.id) yields null.
        val departedOwner = machine(creatorUserId = "u-departed", creator = creator)
        assertEquals("u-departed", membersComputerOwnerId(departedOwner))
        assertNull(membersComputerOwnerName(departedOwner, memberList))

        // Non-human members with a matching id are ignored.
        val agentMemberCollision = machine(creatorUserId = "u-agent-same-id")
        assertNull(membersComputerOwnerName(agentMemberCollision, memberList))
    }

    @Test
    fun restartCommandMatchesPlatformEnvironmentAndRunKind() {
        val macComputer = machine(os = "darwin", isComputer = true)
        val winComputer = machine(os = "win32", isComputer = true)
        val bareDaemon = machine(id = "m-daemon", os = "linux", isComputer = false)
        val stagingContext =
            SettingsComputerCommandContext(
                serverSlug = "staging-lab",
                deploymentEnv = "staging",
                serverUrl = "https://staging.raft.build",
            )

        assertEquals("raft-computer restart /acme", membersAgentComputerRestartCommand(macComputer, prodContext))
        assertEquals("raft-computer restart /acme", membersAgentComputerRestartCommand(winComputer, prodContext))
        assertEquals(
            "RAFT_HOME=\"\$HOME/.raft-computer-staging-lab\" RAFT_COMPUTER_INSTALL_DIR=\"\$HOME/.raft-computer-staging-lab/bin\" \"\$HOME/.raft-computer-staging-lab/bin/raft-computer\" restart /staging-lab",
            membersAgentComputerRestartCommand(macComputer, stagingContext),
        )
        assertEquals(
            "\$env:RAFT_HOME = \"\$env:USERPROFILE\\.raft-computer-staging-lab\"; \$env:RAFT_COMPUTER_INSTALL_DIR = \"\$env:RAFT_HOME\\bin\"; & \"\$env:RAFT_COMPUTER_INSTALL_DIR\\raft-computer.exe\" restart /staging-lab",
            membersAgentComputerRestartCommand(winComputer, stagingContext),
        )
        assertEquals("raft-computer restart /acme", membersAgentComputerRestartCommand(bareDaemon, prodContext))
        assertNull(membersAgentComputerRestartCommand(macComputer, null))
        assertNull(
            membersAgentComputerRestartCommand(
                macComputer,
                prodContext.copy(serverSlug = "  "),
            ),
        )
    }

    @Test
    fun formattedSinceRespectsTimeContextClockFormatAndFallback() {
        val enFormat = RaftMessageTimeFormat(locale = RaftFormatLocale.English, yesterdayLabel = "Yesterday")
        val time24h =
            RaftTimeContext(
                localOffsetMinutes = 0,
                todayEpochDay = raftEpochDayForDate(2026, 6, 19),
                timeFormat = RaftTimeFormat24Hour,
            )
        val time12h = time24h.copy(timeFormat = RaftTimeFormat12Hour)

        val sameDayIso =
            MembersAgentComputerOfflineCardState(
                machineId = "m-1",
                computerName = "mac",
                ownerName = null,
                sinceEpochMillis = null,
                sinceIso = "2026-06-19T14:05:00Z",
                restartCommand = null,
            )
        assertEquals("14:05", sameDayIso.formattedSince(time24h, enFormat))
        assertEquals("02:05 PM", sameDayIso.formattedSince(time12h, enFormat))

        val yesterdayIso = sameDayIso.copy(sinceIso = "2026-06-18T09:30:00Z")
        assertEquals("Yesterday 09:30", yesterdayIso.formattedSince(time24h, enFormat))

        val earlierIso = sameDayIso.copy(sinceIso = "2026-06-10T09:30:00Z")
        assertEquals("06/10 09:30", earlierIso.formattedSince(time24h, enFormat))

        // sinceEpochMillis wins over sinceIso.
        // 2026-06-19T16:45:00Z = 1781887500000L.
        val both = sameDayIso.copy(sinceEpochMillis = 1_781_887_500_000L)
        assertEquals("16:45", both.formattedSince(time24h, enFormat))

        // Missing or unparseable returns null.
        assertNull(sameDayIso.copy(sinceIso = null).formattedSince(time24h, enFormat))
        assertNull(sameDayIso.copy(sinceIso = "not-a-timestamp").formattedSince(time24h, enFormat))
        assertNull(sameDayIso.formattedSince(null, enFormat))
    }

    @Test
    fun reconcileOfflineObservedAtTracksLiveTransitionsOnly() {
        val m1Online = machine(id = "m-1", status = ComputerStatus.Online)
        val m1Offline = machine(id = "m-1", status = ComputerStatus.Offline)
        val m2Offline = machine(id = "m-2", status = ComputerStatus.Offline)

        // Initial snapshot with offline machines does not fabricate an observed time.
        val initial =
            reconcileComputerOfflineObservedAtMs(
                previousMachines = emptyList(),
                nextMachines = listOf(m1Online, m2Offline),
                previousObserved = emptyMap(),
                nowMillis = 1_000L,
            )
        assertEquals(emptyMap(), initial)

        // Online -> Offline records nowMillis; already-offline m2 stays absent.
        val wentOffline =
            reconcileComputerOfflineObservedAtMs(
                previousMachines = listOf(m1Online, m2Offline),
                nextMachines = listOf(m1Offline, m2Offline),
                previousObserved = initial,
                nowMillis = 2_000L,
            )
        assertEquals(mapOf("m-1" to 2_000L), wentOffline)

        // Staying offline keeps the first observed timestamp.
        val stillOffline =
            reconcileComputerOfflineObservedAtMs(
                previousMachines = listOf(m1Offline, m2Offline),
                nextMachines = listOf(m1Offline, m2Offline),
                previousObserved = wentOffline,
                nowMillis = 5_000L,
            )
        assertEquals(mapOf("m-1" to 2_000L), stillOffline)

        // Offline -> Online clears the entry.
        val backOnline =
            reconcileComputerOfflineObservedAtMs(
                previousMachines = listOf(m1Offline, m2Offline),
                nextMachines = listOf(m1Online, m2Offline),
                previousObserved = stillOffline,
                nowMillis = 8_000L,
            )
        assertEquals(emptyMap(), backOnline)

        // Removed machine clears its entry.
        val removed =
            reconcileComputerOfflineObservedAtMs(
                previousMachines = listOf(m1Offline),
                nextMachines = emptyList(),
                previousObserved = mapOf("m-1" to 2_000L),
                nowMillis = 9_000L,
            )
        assertEquals(emptyMap(), removed)
    }

    @Test
    fun buildAnnotatedStringPreservesWordOrderAcrossLocales() {
        val sentinel = "__TOKEN__"
        val built =
            buildMembersOfflineCardAnnotatedString(
                template = "Enciende la computadora studio de __TOKEN__ y evita que entre en suspensión",
                sentinel = sentinel,
            ) {
                append("Martin")
            }
        assertEquals(
            "Enciende la computadora studio de Martin y evita que entre en suspensión",
            built.text,
        )

        val missing = buildMembersOfflineCardAnnotatedString("plain text", sentinel) { append("ignored") }
        assertEquals("plain text", missing.text)
    }
}
