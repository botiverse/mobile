package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import build.raft.app.resources.*
import kotlinx.datetime.TimeZone

class HomeComputerVersionGateNotificationsTest {
    private val deadline = "2026-11-10T00:00:00.000Z"
    private val nowMillis = 1_795_521_600_000L // 2026-11-24T12:00:00.000Z
    private val recentRefusal = "2026-11-24T11:59:00.000Z"
    private val exactSevenDayRefusal = "2026-11-17T12:00:00.000Z"
    private val staleRefusal = "2026-11-17T11:59:59.000Z"

    private fun gate(
        reason: String = "computer_version_unsupported",
        minVersion: String = "1.0.60",
        enforcedAt: String? = deadline,
        enforced: Boolean = false,
    ) = SettingsComputerVersionGateSnapshot(reason = reason, minVersion = minVersion, enforcedAt = enforcedAt, enforced = enforced)

    private fun computer(
        id: String = "computer-1",
        name: String = "Studio Mac",
        version: String? = "1.0.55",
        status: ComputerStatus = ComputerStatus.Online,
        isComputer: Boolean = true,
        versionGate: SettingsComputerVersionGateSnapshot? = gate(),
        versionRefusedAt: String? = null,
        refusedComputerVersion: String? = null,
        revocation: SettingsComputerRevocationSnapshot? = null,
        creatorUserId: String? = null,
        attachedByCurrentUser: Boolean = false,
    ) = ComputerSummary(
        id = id,
        name = name,
        status = status,
        statusVersion = 1L,
        apiKeyPrefix = null,
        runtimes = emptyList(),
        daemonVersion = null,
        computerVersion = version,
        isComputer = isComputer,
        hostname = null,
        os = null,
        description = null,
        lastHeartbeat = null,
        createdAt = null,
        creatorUserId = creatorUserId,
        attachedByCurrentUser = attachedByCurrentUser,
        versionGate = versionGate,
        revocation = revocation,
        versionRefusedAt = versionRefusedAt,
        refusedComputerVersion = refusedComputerVersion,
    )

    private fun notices(
        vararg machines: ComputerSummary,
        role: String? = "admin",
        userId: String? = "user-1",
        now: Long = nowMillis,
    ) = homeComputerVersionGateNotifications(machines.toList(), role, userId, TimeZone.UTC, now)

    @Test
    fun noGateOrNoPendingDeadlineForUpgradableComputerYieldsNoNotice() {
        listOf(
            null,
            gate(enforcedAt = null),
            gate(enforcedAt = "not-a-date"),
            gate(enforced = true),
        ).forEach { versionGate ->
            assertEquals(emptyList(), notices(computer(version = "1.0.55", versionGate = versionGate)), "gate=$versionGate")
        }
        // Offline upgradable Computer is skipped even with a pending deadline (web machinesNeedingUpgradeByDeadline).
        assertEquals(emptyList(), notices(computer(version = "1.0.55", status = ComputerStatus.Offline)))
    }

    @Test
    fun gatedVersionAtOrAboveBuiltInFloorAsksForAnUpgradeBeforeTheDeadline() {
        listOf("1.0.53", "1.0.53-rc.1", "1.0.55").forEach { version ->
            val notice = notices(computer(version = version)).single()

            assertEquals("computer-version-gate:upgrade:$deadline:computer-1", notice.id, "version=$version")
            assertEquals("Studio Mac: update required before Nov 10, 2026", notice.title)
            assertEquals("After that, agents on this computer stop running.", notice.body)
            assertEquals("computer-1", notice.targetId)
            assertEquals(SharedHomeNotificationKind.Warning, notice.kind)
        }
    }

    @Test
    fun gatedVersionBelowBuiltInFloorAsksForAReinstallBeforeTheDeadline() {
        listOf("1.0.44", "1.0.52").forEach { version ->
            val notice = notices(computer(version = version)).single()

            assertEquals("computer-version-gate:reinstall:$deadline:computer-1", notice.id, "version=$version")
            assertEquals("Studio Mac: update required before Nov 10, 2026", notice.title)
            assertEquals(
                "After that, agents on this computer stop running. Your agents and files stay where they are.",
                notice.body,
            )
        }
    }

    @Test
    fun refusalWindowMatchesSevenDaysInclusiveAndRejectsStaleOrMalformedTimestamps() {
        assertTrue(raftComputerVersionRefusedRecently(recentRefusal, nowMillis))
        assertTrue(raftComputerVersionRefusedRecently(exactSevenDayRefusal, nowMillis))
        assertFalse(raftComputerVersionRefusedRecently(staleRefusal, nowMillis))
        assertFalse(raftComputerVersionRefusedRecently(null, nowMillis))
        assertFalse(raftComputerVersionRefusedRecently("   ", nowMillis))
        assertFalse(raftComputerVersionRefusedRecently("not-a-timestamp", nowMillis))

        assertTrue(raftComputerActiveForReinstallNotice(online = true, versionRefusedAt = null, nowMillis = nowMillis))
        assertTrue(raftComputerActiveForReinstallNotice(online = false, versionRefusedAt = recentRefusal, nowMillis = nowMillis))
        assertFalse(raftComputerActiveForReinstallNotice(online = false, versionRefusedAt = staleRefusal, nowMillis = nowMillis))
        assertFalse(raftComputerActiveForReinstallNotice(online = false, versionRefusedAt = null, nowMillis = nowMillis))
    }

    @Test
    fun offlineComputerRefusedWithinSevenDaysGetsOneTimeReinstallNoticeAndAbandonedStaysOut() {
        val enforcedGate = gate(minVersion = "1.0.53", enforcedAt = null, enforced = true)
        val recentComputer =
            computer(
                id = "refused-box",
                name = "Refused Box",
                version = "1.0.52",
                status = ComputerStatus.Offline,
                versionGate = enforcedGate,
                versionRefusedAt = recentRefusal,
            )
        val abandonedComputer =
            computer(
                id = "abandoned-box",
                name = "Abandoned Box",
                version = "1.0.40",
                status = ComputerStatus.Offline,
                versionGate = enforcedGate,
                versionRefusedAt = staleRefusal,
            )
        val neverRefusedOffline =
            computer(
                id = "quiet-box",
                name = "Quiet Box",
                version = "1.0.40",
                status = ComputerStatus.Offline,
                versionGate = enforcedGate,
                versionRefusedAt = null,
            )

        val notice = notices(recentComputer, abandonedComputer, neverRefusedOffline).single()
        assertEquals("computer-version-gate:reinstall:now:refused-box", notice.id)
        assertEquals("Refused Box needs a one-time reinstall", notice.title)
        assertEquals("Your agents and files stay where they are.", notice.body)
        assertEquals("refused-box", notice.targetId)
        val title = notice.titleDesc as ResourceFormattedStringDesc
        assertEquals(Res.string.home_notification_computer_reinstall_title_one, title.stringRes)
        assertEquals(listOf("Refused Box"), title.args)
    }

    @Test
    fun offlineLegacyDaemonRefusedRecentlyGetsMigrateNoticeWhileRevokedComputerStaysOut() {
        val refusedDaemon =
            computer(
                id = "daemon-1",
                name = "Legacy Box",
                version = null,
                status = ComputerStatus.Offline,
                isComputer = false,
                versionGate = null,
                versionRefusedAt = recentRefusal,
            )
        val staleDaemon =
            computer(
                id = "daemon-old",
                name = "Abandoned Daemon",
                version = null,
                status = ComputerStatus.Offline,
                isComputer = false,
                versionGate = null,
                versionRefusedAt = staleRefusal,
            )
        val revokedMachine =
            computer(
                id = "revoked-1",
                name = "Revoked Box",
                version = null,
                status = ComputerStatus.Offline,
                isComputer = false,
                versionGate = null,
                versionRefusedAt = recentRefusal,
                revocation = SettingsComputerRevocationSnapshot(at = "2026-11-20T00:00:00.000Z", via = SettingsComputerRevokedVia.MemberLeft),
            )

        val migrateOnly = notices(refusedDaemon, staleDaemon, revokedMachine).single()
        assertEquals("computer-version-gate:reinstall:now:daemon-1", migrateOnly.id)
        assertEquals("Legacy Box needs to be migrated to Raft Computer", migrateOnly.title)
        assertEquals(Res.string.home_notification_computer_migrate_title_one, (migrateOnly.titleDesc as ResourceFormattedStringDesc).stringRes)

        val refusedComputer =
            computer(
                id = "comp-1",
                name = "Alpha Box",
                version = "1.0.50",
                status = ComputerStatus.Offline,
                versionGate = gate(minVersion = "1.0.53", enforcedAt = null, enforced = true),
                versionRefusedAt = recentRefusal,
            )
        val mixed = notices(refusedDaemon, refusedComputer).single()
        assertEquals("computer-version-gate:reinstall:now:comp-1,daemon-1", mixed.id)
        assertEquals("2 machines need a one-time reinstall or migration", mixed.title)
        assertEquals(
            Res.string.home_notification_computer_reinstall_or_migrate_title_many,
            (mixed.titleDesc as ResourceFormattedStringDesc).stringRes,
        )
    }

    @Test
    fun unknownVersionReadsAsReinstallButAnUnreportedVersionWaits() {
        val unknown = gate(reason = "computer_version_unknown")

        val notice = notices(computer(version = "dev-build", versionGate = unknown)).single()
        assertTrue(notice.id.startsWith("computer-version-gate:reinstall:"))

        // Online with no version yet: the web waits before calling it a reinstall; no notice.
        assertEquals(emptyList(), notices(computer(version = null, versionGate = unknown)))
        assertEquals(emptyList(), notices(computer(version = " ", versionGate = unknown)))
    }

    @Test
    fun onlyPeopleWhoManageTheComputerSeeIt() {
        val gated = computer(creatorUserId = "someone-else")

        assertEquals(emptyList(), notices(gated, role = "member"))
        assertEquals(emptyList(), notices(gated, role = "guest"))
        assertEquals(1, notices(gated, role = "owner").size)
        assertEquals(1, notices(computer(creatorUserId = "user-1"), role = "member").size)
        assertEquals(1, notices(computer(attachedByCurrentUser = true), role = "member").size)
    }

    @Test
    fun severalComputersShareOneNoticePerTierUnderTheEarliestDeadline() {
        val result =
            notices(
                computer(id = "late", name = "Late", version = "1.0.55", versionGate = gate(enforcedAt = "2026-12-01T00:00:00.000Z")),
                computer(id = "early", name = "Early", version = "1.0.54", versionGate = gate(enforcedAt = "2026-11-20T00:00:00.000Z")),
                computer(id = "old", name = "Old", version = "1.0.52"),
            )

        assertEquals(
            listOf(
                "computer-version-gate:reinstall:$deadline:old",
                "computer-version-gate:upgrade:2026-11-20T00:00:00.000Z:early,late",
            ),
            result.map { it.id },
        )
        val upgrade = result[1]
        assertEquals("2 Computers: update required before Nov 20, 2026", upgrade.title)
        assertEquals("early", upgrade.targetId)
    }

    @Test
    fun dismissalKeepsCoveringFixedComputersUntilTheDeadlineOrSetChanges() {
        val dismissed = setOf("computer-version-gate:upgrade:$deadline:a,b", "computer-version-gate:reinstall:now:x,y")

        assertTrue(homeLocalNotificationDismissed("computer-version-gate:upgrade:$deadline:a", dismissed))
        assertTrue(homeLocalNotificationDismissed("computer-version-gate:upgrade:$deadline:a,b", dismissed))
        assertTrue(homeLocalNotificationDismissed("computer-version-gate:reinstall:now:x", dismissed))
        // A new Computer, a new deadline or the other tier brings it back.
        assertFalse(homeLocalNotificationDismissed("computer-version-gate:upgrade:$deadline:a,c", dismissed))
        assertFalse(homeLocalNotificationDismissed("computer-version-gate:upgrade:2026-12-01T00:00:00.000Z:a", dismissed))
        assertFalse(homeLocalNotificationDismissed("computer-version-gate:reinstall:$deadline:a", dismissed))
        assertFalse(homeLocalNotificationDismissed("computer-version-gate:reinstall:$deadline:x", dismissed))
    }

    @Test
    fun viewOpensTheFirstComputersPage() {
        val notice = notices(computer(id = "computer-9")).single()

        assertEquals("computer-9", homeComputerVersionGateTargetOrNull(notice.id, notice.targetId))
        assertTrue(notice.id.isLocalHomeNotificationId())
        assertTrue(notice.id.isMachineHomeNotificationId())
        assertNull(homeComputerVersionGateTargetOrNull("machine-offline:computer-9", "computer-9"))
    }

    @Test
    fun deadlineIsTheReadersLocalCalendarDay() {
        listOf(TimeZone.UTC to 10, TimeZone.of("America/Los_Angeles") to 9).forEach { (zone, day) ->
            val notice =
                homeComputerVersionGateNotifications(listOf(computer()), "admin", "user-1", zone, nowMillis).single()
            val title = notice.titleDesc as ResourceFormattedStringDesc

            assertEquals(Res.string.home_notification_computer_upgrade_deadline_title_one, title.stringRes)
            assertEquals("Studio Mac", title.args[0])
            assertEquals(
                Res.string.home_notification_computer_version_deadline_date.format(
                    listOf(Res.string.task_head_month_11.desc(), day, 2026)
                ),
                title.args[1],
                "zone=$zone",
            )
        }
    }

    @Test
    fun offlineNoVersionComputerRefusedWithinSevenDaysGetsReinstallNotice() {
        val unknownGate = gate(reason = "computer_version_unknown", minVersion = "1.0.53", enforcedAt = null, enforced = true)
        val offlineWithRefusedVersion =
            computer(
                id = "offline-refused",
                name = "Offline Refused",
                version = null,
                status = ComputerStatus.Offline,
                versionGate = unknownGate,
                versionRefusedAt = recentRefusal,
                refusedComputerVersion = "1.0.40",
            )
        val offlineStale =
            computer(
                id = "offline-stale",
                name = "Offline Stale",
                version = null,
                status = ComputerStatus.Offline,
                versionGate = unknownGate,
                versionRefusedAt = staleRefusal,
                refusedComputerVersion = "1.0.40",
            )
        val notice = notices(offlineWithRefusedVersion, offlineStale).single()
        assertEquals("computer-version-gate:reinstall:now:offline-refused", notice.id)
        assertEquals("Offline Refused needs a one-time reinstall", notice.title)
        assertEquals("offline-refused", notice.targetId)
    }
}
