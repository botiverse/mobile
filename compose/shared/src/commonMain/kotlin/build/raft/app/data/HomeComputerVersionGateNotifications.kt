package build.raft.app.data

import build.raft.app.RaftComputerVersionTier
import build.raft.app.raftComputerVersionTier
import build.raft.app.resources.*
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/*
 * The Home notification center's Computer version-gate notices: a Computer below the server's
 * version floor (`versionGate`) that will stop running agents at `enforcedAt`.
 *
 * Mirrors the web (botiverse/slock staging): who sees it is `canManageMachine`
 * (packages/web/src/utils/canManageMachine.ts — here [settingsComputerPermissions], the same
 * owner/admin, creator or attaching-person rule), and the tier is `computerVersionTier`
 * (packages/web/src/utils/computerVersionTier.ts — here [raftComputerVersionTier]): a version the
 * web can upgrade (>= 1.0.45) needs an upgrade, anything older or not SemVer needs a reinstall.
 * Computer module rules (Archer): only while a deadline is set and not yet enforced; one notice per
 * tier, several Computers grouped under the earliest deadline; the dismissal key carries that
 * deadline, so a new date brings a dismissed notice back. Like web `reinstallDismissalKey`, a
 * dismissal that already covered every listed Computer keeps covering it when one is fixed
 * ([homeComputerVersionGateDismissalCovers]).
 */

internal const val COMPUTER_VERSION_GATE_HOME_NOTIFICATION_PREFIX = "computer-version-gate:"

internal fun String.isComputerVersionGateHomeNotificationId(): Boolean =
    startsWith(COMPUTER_VERSION_GATE_HOME_NOTIFICATION_PREFIX)

private const val NeedUpgrade = "upgrade"
private const val NeedReinstall = "reinstall"

private data class GatedComputer(
    val id: String,
    val name: String,
    val reinstall: Boolean,
    val enforcedAt: String,
    val deadlineMillis: Long,
)

/**
 * The version-gate notices for [machines] (the Home machine list), reinstall first. [serverRole]
 * and [currentUserId] decide which Computers the viewer manages; [timeZone] is the reader's zone
 * for the deadline's calendar day (web `formatDate`).
 */
internal fun homeComputerVersionGateNotifications(
    machines: List<ComputerSummary>,
    serverRole: String?,
    currentUserId: String?,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): List<SharedHomeNotification> {
    val gated =
        machines.mapNotNull { machine ->
            val computer = machine.toSettingsComputerSnapshot()
            val tier = raftComputerVersionTier(computer) as? RaftComputerVersionTier.Notice ?: return@mapNotNull null
            if (tier.enforced) return@mapNotNull null
            val enforcedAt = tier.enforcedAt?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val deadline = runCatching { Instant.parse(enforcedAt) }.getOrNull() ?: return@mapNotNull null
            val permissions = settingsComputerPermissions(serverRole, currentUserId, computer)
            if (!permissions.canEdit && !permissions.canRemove) return@mapNotNull null
            GatedComputer(machine.id, machine.name, tier.reinstall, enforcedAt, deadline.toEpochMilliseconds())
        }
    return listOf(true, false).mapNotNull { reinstall ->
        gated.filter { it.reinstall == reinstall }
            .sortedWith(compareBy<GatedComputer>({ it.deadlineMillis }, { it.name }))
            .takeIf { it.isNotEmpty() }
            ?.toNotification(reinstall, timeZone)
    }
}

private fun List<GatedComputer>.toNotification(reinstall: Boolean, timeZone: TimeZone): SharedHomeNotification {
    val first = first()
    val need = if (reinstall) NeedReinstall else NeedUpgrade
    val one = size == 1
    val local = Instant.fromEpochMilliseconds(first.deadlineMillis).toLocalDateTime(timeZone)
    val month = local.month.ordinal + 1
    val dateDesc =
        Res.string.home_notification_computer_version_deadline_date.format(
            listOf(homeComputerVersionGateMonths[month - 1].desc(), local.day, local.year)
        )
    val englishDate = "${homeComputerVersionGateEnglishMonths[month - 1]} ${local.day}, ${local.year}"
    val titleRes =
        when {
            reinstall && one -> Res.string.home_notification_computer_reinstall_deadline_title_one
            reinstall -> Res.string.home_notification_computer_reinstall_deadline_title_many
            one -> Res.string.home_notification_computer_upgrade_deadline_title_one
            else -> Res.string.home_notification_computer_upgrade_deadline_title_many
        }
    val bodyRes =
        when {
            reinstall && one -> Res.string.home_notification_computer_reinstall_deadline_body_one
            reinstall -> Res.string.home_notification_computer_reinstall_deadline_body_many
            one -> Res.string.home_notification_computer_version_deadline_body_one
            else -> Res.string.home_notification_computer_version_deadline_body_many
        }
    val subject: Any = if (one) first.name else size
    val englishTitle =
        when {
            reinstall && one -> "${first.name} needs a reinstall: finish before $englishDate"
            reinstall -> "$size Computers need a reinstall: finish before $englishDate"
            one -> "${first.name} needs to upgrade before $englishDate"
            else -> "$size Computers need to upgrade before $englishDate"
        }
    val englishBody =
        (if (one) "After that, agents on this computer stop running." else "After that, agents on these computers stop running.") +
            if (reinstall) " Your agents and files stay where they are." else ""
    return SharedHomeNotification(
        id = "$COMPUTER_VERSION_GATE_HOME_NOTIFICATION_PREFIX$need:${first.enforcedAt}:${map { it.id }.sorted().joinToString(",")}",
        title = englishTitle,
        body = englishBody,
        actionLabel = "View",
        titleDesc = titleRes.format(listOf(subject, dateDesc)),
        bodyDesc = bodyRes.desc(),
        actionLabelDesc = Res.string.home_action_view.desc(),
        // Web: the action opens the first one's page (it has the upgrade / reinstall commands).
        targetId = first.id,
        kind = SharedHomeNotificationKind.Warning,
        icon = SharedHomeNotificationIcon.Default,
    )
}

/**
 * A dismissed version-gate notice still covers [id] when both name the same tier and deadline and
 * every Computer [id] lists was already listed in [dismissedId] (web `reinstallDismissalKey`
 * reuse): fixing one Computer does not bring the notice back for the rest.
 */
internal fun homeComputerVersionGateDismissalCovers(dismissedId: String, id: String): Boolean {
    if (!dismissedId.isComputerVersionGateHomeNotificationId() || !id.isComputerVersionGateHomeNotificationId()) return false
    val dismissedHead = dismissedId.substringBeforeLast(':')
    if (dismissedHead != id.substringBeforeLast(':')) return false
    val covered = dismissedId.substringAfterLast(':').split(',').toSet()
    return id.substringAfterLast(':').split(',').all { it in covered }
}

/** Whether a local Home notice [id] is hidden by the session's [dismissed] ids. */
internal fun homeLocalNotificationDismissed(id: String, dismissed: Set<String>): Boolean =
    id in dismissed || (id.isComputerVersionGateHomeNotificationId() && dismissed.any { homeComputerVersionGateDismissalCovers(it, id) })

private val homeComputerVersionGateMonths =
    listOf(
        Res.string.task_head_month_1, Res.string.task_head_month_2, Res.string.task_head_month_3,
        Res.string.task_head_month_4, Res.string.task_head_month_5, Res.string.task_head_month_6,
        Res.string.task_head_month_7, Res.string.task_head_month_8, Res.string.task_head_month_9,
        Res.string.task_head_month_10, Res.string.task_head_month_11, Res.string.task_head_month_12,
    )

private val homeComputerVersionGateEnglishMonths =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** The Computer a version-gate notice ([id], [targetId]) opens with View (its page); null for any other notice. */
internal fun homeComputerVersionGateTargetOrNull(id: String, targetId: String?): String? =
    targetId?.trim()?.takeIf { id.isComputerVersionGateHomeNotificationId() && it.isNotEmpty() }
