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

    private fun gate(
        reason: String = "computer_version_unsupported",
        enforcedAt: String? = deadline,
        enforced: Boolean = false,
    ) = SettingsComputerVersionGateSnapshot(reason = reason, minVersion = "1.0.53", enforcedAt = enforcedAt, enforced = enforced)

    private fun computer(
        id: String = "computer-1",
        name: String = "Studio Mac",
        version: String? = "1.0.50",
        versionGate: SettingsComputerVersionGateSnapshot? = gate(),
        creatorUserId: String? = null,
        attachedByCurrentUser: Boolean = false,
    ) = ComputerSummary(
        id = id,
        name = name,
        status = ComputerStatus.Online,
        statusVersion = 1L,
        apiKeyPrefix = null,
        runtimes = emptyList(),
        daemonVersion = null,
        computerVersion = version,
        isComputer = true,
        hostname = null,
        os = null,
        description = null,
        lastHeartbeat = null,
        createdAt = null,
        creatorUserId = creatorUserId,
        attachedByCurrentUser = attachedByCurrentUser,
        versionGate = versionGate,
    )

    private fun notices(vararg machines: ComputerSummary, role: String? = "admin", userId: String? = "user-1") =
        homeComputerVersionGateNotifications(machines.toList(), role, userId, TimeZone.UTC)

    @Test
    fun noGateOrNoPendingDeadlineYieldsNoNotice() {
        listOf(
            null,
            gate(enforcedAt = null),
            gate(enforcedAt = "not-a-date"),
            gate(enforced = true),
        ).forEach { versionGate ->
            assertEquals(emptyList(), notices(computer(versionGate = versionGate)), "gate=$versionGate")
        }
    }

    @Test
    fun gatedVersionAtOrAboveRemoteUpgradeMinimumAsksForAnUpgradeBeforeTheDeadline() {
        listOf("1.0.45", "1.0.50").forEach { version ->
            val notice = notices(computer(version = version)).single()

            assertEquals("computer-version-gate:upgrade:$deadline:computer-1", notice.id, "version=$version")
            assertEquals("Studio Mac needs to upgrade before Nov 10, 2026", notice.title)
            assertEquals("After that, agents on this computer stop running.", notice.body)
            assertEquals("computer-1", notice.targetId)
            assertEquals(SharedHomeNotificationKind.Warning, notice.kind)
        }
    }

    @Test
    fun gatedVersionBelowRemoteUpgradeMinimumAsksForAReinstallBeforeTheDeadline() {
        val notice = notices(computer(version = "1.0.44")).single()

        assertEquals("computer-version-gate:reinstall:$deadline:computer-1", notice.id)
        assertEquals("Studio Mac needs a reinstall: finish before Nov 10, 2026", notice.title)
        assertEquals(
            "After that, agents on this computer stop running. Your agents and files stay where they are.",
            notice.body,
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
                computer(id = "late", name = "Late", version = "1.0.50", versionGate = gate(enforcedAt = "2026-12-01T00:00:00.000Z")),
                computer(id = "early", name = "Early", version = "1.0.51", versionGate = gate(enforcedAt = "2026-11-20T00:00:00.000Z")),
                computer(id = "old", name = "Old", version = "1.0.30"),
            )

        assertEquals(
            listOf(
                "computer-version-gate:reinstall:$deadline:old",
                "computer-version-gate:upgrade:2026-11-20T00:00:00.000Z:early,late",
            ),
            result.map { it.id },
        )
        val upgrade = result[1]
        assertEquals("2 Computers need to upgrade before Nov 20, 2026", upgrade.title)
        assertEquals("early", upgrade.targetId)
    }

    @Test
    fun dismissalKeepsCoveringFixedComputersUntilTheDeadlineOrSetChanges() {
        val dismissed = setOf("computer-version-gate:upgrade:$deadline:a,b")

        assertTrue(homeLocalNotificationDismissed("computer-version-gate:upgrade:$deadline:a", dismissed))
        assertTrue(homeLocalNotificationDismissed("computer-version-gate:upgrade:$deadline:a,b", dismissed))
        // A new Computer, a new deadline or the other tier brings it back.
        assertFalse(homeLocalNotificationDismissed("computer-version-gate:upgrade:$deadline:a,c", dismissed))
        assertFalse(homeLocalNotificationDismissed("computer-version-gate:upgrade:2026-12-01T00:00:00.000Z:a", dismissed))
        assertFalse(homeLocalNotificationDismissed("computer-version-gate:reinstall:$deadline:a", dismissed))
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
                homeComputerVersionGateNotifications(listOf(computer()), "admin", "user-1", zone).single()
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
}
