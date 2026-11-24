package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.RaftComputerVersionTier
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.raftComputerVersionTier
import build.raft.app.resources.*
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/*
 * The Home notification center's Computer version-gate notices:
 *
 * - Upgrade deadline: online managed Computers at or above the built-in 1.0.53 floor that fall
 *   below a deployment's raised floor (`versionGate`) with a pending `enforcedAt` deadline.
 * - Reinstall / migration (slock #16346, #16514 `utils/computersNeedingReinstall.ts`): managed
 *   Computers below the built-in 1.0.53 floor (or non-SemVer), plus offline legacy standalone
 *   daemons (never revoked Computers) that the server refused at connect within the last 7 days
 *   ([RAFT_COMPUTER_REINSTALL_REFUSED_RECENTLY_MILLIS]). Because the server refuses them at
 *   connect, they are never online: an offline one stays listed while its last `versionRefusedAt`
 *   is within 7 days, while long-abandoned machines stay out.
 *
 * Mirrors the web (botiverse/slock #16514): who sees it is `canManageMachine`
 * ([settingsComputerPermissions], the same owner/admin, creator, controller or attaching-person
 * rule), and the tier is `computerVersionTier` ([raftComputerVersionTier]). Dismissal keys reuse
 * earlier dismissals covering every listed Computer ([homeComputerVersionGateDismissalCovers]).
 */

@RaftCoreInternalApi const val COMPUTER_VERSION_GATE_HOME_NOTIFICATION_PREFIX = "computer-version-gate:"

/**
 * Web `REINSTALL_REFUSED_RECENTLY_MS` (7 days, slock #16514): how recent a connect refusal
 * (`versionRefusedAt`) keeps an offline machine in the reinstall notice.
 */
@RaftCoreInternalApi const val RAFT_COMPUTER_REINSTALL_REFUSED_RECENTLY_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L

@RaftCoreInternalApi fun String.isComputerVersionGateHomeNotificationId(): Boolean =
    startsWith(COMPUTER_VERSION_GATE_HOME_NOTIFICATION_PREFIX)

/**
 * Web `refusedRecently(versionRefusedAt, nowMs)` (slock #16514): true when [versionRefusedAt]
 * parses to a finite timestamp within [RAFT_COMPUTER_REINSTALL_REFUSED_RECENTLY_MILLIS] of
 * [nowMillis] (`nowMillis - at <= 7 days`).
 */
@RaftCoreInternalApi fun raftComputerVersionRefusedRecently(versionRefusedAt: String?, nowMillis: Long): Boolean {
    val at =
        versionRefusedAt
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { parseRaftTimestampEpochMillisOrNull(it, localOffsetMinutes = 0) }
            ?: return false
    return nowMillis - at <= RAFT_COMPUTER_REINSTALL_REFUSED_RECENTLY_MILLIS
}

/**
 * Web `machinesNeedingReinstall` active predicate (slock #16514): online now, or refused at
 * connect within the last 7 days.
 */
@RaftCoreInternalApi fun raftComputerActiveForReinstallNotice(
    online: Boolean,
    versionRefusedAt: String?,
    nowMillis: Long,
): Boolean = online || raftComputerVersionRefusedRecently(versionRefusedAt, nowMillis)

private const val NeedUpgrade = "upgrade"
private const val NeedReinstall = "reinstall"
private const val ImmediateReinstallSlot = "now"

private data class ReinstallCandidate(
    val id: String,
    val name: String,
    val isComputer: Boolean,
    val enforcedAt: String?,
    val deadlineMillis: Long?,
)

private data class UpgradeDeadlineCandidate(
    val id: String,
    val name: String,
    val enforcedAt: String,
    val deadlineMillis: Long,
)

private enum class ReinstallNoticeKind { Reinstall, Migrate, Mixed }

private fun List<ReinstallCandidate>.noticeKind(): ReinstallNoticeKind {
    val legacy = count { !it.isComputer }
    return when (legacy) {
        0 -> ReinstallNoticeKind.Reinstall
        size -> ReinstallNoticeKind.Migrate
        else -> ReinstallNoticeKind.Mixed
    }
}

/**
 * The version-gate notices for [machines] (the Home machine list), reinstall first. [serverRole]
 * and [currentUserId] decide which Computers the viewer manages; [timeZone] is the reader's zone
 * for any deadline's calendar day (web `formatDate`); [nowMillis] evaluates the 7-day refusal
 * window ([RAFT_COMPUTER_REINSTALL_REFUSED_RECENTLY_MILLIS]).
 */
@RaftCoreInternalApi fun homeComputerVersionGateNotifications(
    machines: List<ComputerSummary>,
    serverRole: String?,
    currentUserId: String?,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    nowMillis: Long = defaultNowMillis(),
): List<SharedHomeNotification> {
    val reinstallCandidates = mutableListOf<ReinstallCandidate>()
    val upgradeCandidates = mutableListOf<UpgradeDeadlineCandidate>()
    machines.forEach { machine ->
        val computer = machine.toSettingsComputerSnapshot()
        val permissions = settingsComputerPermissions(serverRole, currentUserId, computer)
        if (!permissions.canEdit && !permissions.canRemove) return@forEach
        if (!computer.isComputer) {
            // An offline legacy daemon (never a revoked Computer) that the server refused at
            // connect within 7 days surfaces in the reinstall/migration notice; online raw daemons
            // keep their own `machine-outdated:` notice in HomeDataSource.
            if (!computer.online && computer.revocation == null &&
                raftComputerVersionRefusedRecently(machine.versionRefusedAt, nowMillis)
            ) {
                reinstallCandidates +=
                    ReinstallCandidate(
                        id = machine.id,
                        name = machine.name,
                        isComputer = false,
                        enforcedAt = null,
                        deadlineMillis = null,
                    )
            }
            return@forEach
        }
        val tier = raftComputerVersionTier(computer) as? RaftComputerVersionTier.Notice ?: return@forEach
        val pendingDeadline =
            if (tier.enforced) {
                null
            } else {
                tier.enforcedAt?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                    runCatching { Instant.parse(raw) }.getOrNull()?.let { raw to it.toEpochMilliseconds() }
                }
            }
        if (tier.reinstall) {
            if (!raftComputerActiveForReinstallNotice(computer.online, machine.versionRefusedAt, nowMillis)) {
                return@forEach
            }
            reinstallCandidates +=
                ReinstallCandidate(
                    id = machine.id,
                    name = machine.name,
                    isComputer = true,
                    enforcedAt = pendingDeadline?.first,
                    deadlineMillis = pendingDeadline?.second,
                )
        } else {
            if (!computer.online || pendingDeadline == null) return@forEach
            upgradeCandidates +=
                UpgradeDeadlineCandidate(
                    id = machine.id,
                    name = machine.name,
                    enforcedAt = pendingDeadline.first,
                    deadlineMillis = pendingDeadline.second,
                )
        }
    }
    return buildList {
        if (reinstallCandidates.isNotEmpty()) {
            val sorted =
                reinstallCandidates.sortedWith(
                    compareBy<ReinstallCandidate>({ it.deadlineMillis ?: Long.MAX_VALUE }, { it.name })
                )
            add(sorted.toReinstallNotification(timeZone))
        }
        if (upgradeCandidates.isNotEmpty()) {
            val sorted = upgradeCandidates.sortedWith(compareBy<UpgradeDeadlineCandidate>({ it.deadlineMillis }, { it.name }))
            add(sorted.toUpgradeNotification(timeZone))
        }
    }
}

private fun List<ReinstallCandidate>.toReinstallNotification(timeZone: TimeZone): SharedHomeNotification {
    val first = first()
    val one = size == 1
    val earliest = filter { it.enforcedAt != null && it.deadlineMillis != null }.minByOrNull { it.deadlineMillis!! }
    val sortedIds = map { it.id }.sorted().joinToString(",")
    if (earliest != null) {
        val enforcedAt = earliest.enforcedAt!!
        val (dateDesc, englishDate) = formatDeadlineDate(earliest.deadlineMillis!!, timeZone)
        val titleRes =
            if (one) {
                Res.string.home_notification_computer_reinstall_deadline_title_one
            } else {
                Res.string.home_notification_computer_reinstall_deadline_title_many
            }
        val bodyRes =
            if (one) {
                Res.string.home_notification_computer_reinstall_deadline_body_one
            } else {
                Res.string.home_notification_computer_reinstall_deadline_body_many
            }
        val subject: Any = if (one) first.name else size
        val englishTitle =
            if (one) "${first.name}: update required before $englishDate" else "$size Computers: update required before $englishDate"
        val englishBody =
            (if (one) "After that, agents on this computer stop running." else "After that, agents on these computers stop running.") +
                " Your agents and files stay where they are."
        return SharedHomeNotification(
            id = "$COMPUTER_VERSION_GATE_HOME_NOTIFICATION_PREFIX$NeedReinstall:$enforcedAt:$sortedIds",
            title = englishTitle,
            body = englishBody,
            actionLabel = "View",
            titleDesc = titleRes.format(listOf(subject, dateDesc)),
            bodyDesc = bodyRes.desc(),
            actionLabelDesc = Res.string.home_action_view.desc(),
            targetId = first.id,
            kind = SharedHomeNotificationKind.Warning,
            icon = SharedHomeNotificationIcon.Default,
        )
    }
    val kind = noticeKind()
    val titleRes =
        when {
            one && kind == ReinstallNoticeKind.Migrate -> Res.string.home_notification_computer_migrate_title_one
            one -> Res.string.home_notification_computer_reinstall_title_one
            kind == ReinstallNoticeKind.Migrate -> Res.string.home_notification_computer_migrate_title_many
            kind == ReinstallNoticeKind.Mixed -> Res.string.home_notification_computer_reinstall_or_migrate_title_many
            else -> Res.string.home_notification_computer_reinstall_title_many
        }
    val englishTitle =
        when {
            one && kind == ReinstallNoticeKind.Migrate -> "${first.name} needs to be migrated to Raft Computer"
            one -> "${first.name} needs a one-time reinstall"
            kind == ReinstallNoticeKind.Migrate -> "$size machines need to be migrated to Raft Computer"
            kind == ReinstallNoticeKind.Mixed -> "$size machines need a one-time reinstall or migration"
            else -> "$size Computers need a one-time reinstall"
        }
    val subject: Any = if (one) first.name else size
    return SharedHomeNotification(
        id = "$COMPUTER_VERSION_GATE_HOME_NOTIFICATION_PREFIX$NeedReinstall:$ImmediateReinstallSlot:$sortedIds",
        title = englishTitle,
        body = "Your agents and files stay where they are.",
        actionLabel = "View",
        titleDesc = titleRes.format(listOf(subject)),
        bodyDesc = Res.string.home_notification_computer_reinstall_body.desc(),
        actionLabelDesc = Res.string.home_action_view.desc(),
        targetId = first.id,
        kind = SharedHomeNotificationKind.Warning,
        icon = SharedHomeNotificationIcon.Default,
    )
}

private fun List<UpgradeDeadlineCandidate>.toUpgradeNotification(timeZone: TimeZone): SharedHomeNotification {
    val first = first()
    val one = size == 1
    val (dateDesc, englishDate) = formatDeadlineDate(first.deadlineMillis, timeZone)
    val titleRes =
        if (one) {
            Res.string.home_notification_computer_upgrade_deadline_title_one
        } else {
            Res.string.home_notification_computer_upgrade_deadline_title_many
        }
    val bodyRes =
        if (one) {
            Res.string.home_notification_computer_version_deadline_body_one
        } else {
            Res.string.home_notification_computer_version_deadline_body_many
        }
    val subject: Any = if (one) first.name else size
    val englishTitle =
        if (one) "${first.name}: update required before $englishDate" else "$size Computers: update required before $englishDate"
    val englishBody =
        if (one) "After that, agents on this computer stop running." else "After that, agents on these computers stop running."
    return SharedHomeNotification(
        id = "$COMPUTER_VERSION_GATE_HOME_NOTIFICATION_PREFIX$NeedUpgrade:${first.enforcedAt}:${map { it.id }.sorted().joinToString(",")}",
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

private fun formatDeadlineDate(deadlineMillis: Long, timeZone: TimeZone): Pair<StringDesc, String> {
    val local = Instant.fromEpochMilliseconds(deadlineMillis).toLocalDateTime(timeZone)
    val month = local.month.ordinal + 1
    val dateDesc =
        Res.string.home_notification_computer_version_deadline_date.format(
            listOf(homeComputerVersionGateMonths[month - 1].desc(), local.day, local.year)
        )
    val englishDate = "${homeComputerVersionGateEnglishMonths[month - 1]} ${local.day}, ${local.year}"
    return dateDesc to englishDate
}

/**
 * A dismissed version-gate notice still covers [id] when both name the same tier and deadline (or
 * immediate reinstall slot) and every Computer [id] lists was already listed in [dismissedId]
 * (web `reinstallDismissalKey` reuse): fixing one Computer does not bring the notice back for the
 * rest.
 */
@RaftCoreInternalApi fun homeComputerVersionGateDismissalCovers(dismissedId: String, id: String): Boolean {
    if (!dismissedId.isComputerVersionGateHomeNotificationId() || !id.isComputerVersionGateHomeNotificationId()) return false
    val dismissedHead = dismissedId.substringBeforeLast(':')
    if (dismissedHead != id.substringBeforeLast(':')) return false
    val covered = dismissedId.substringAfterLast(':').split(',').toSet()
    return id.substringAfterLast(':').split(',').all { it in covered }
}

/** Whether a local Home notice [id] is hidden by the session's [dismissed] ids. */
@RaftCoreInternalApi fun homeLocalNotificationDismissed(id: String, dismissed: Set<String>): Boolean =
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
@RaftCoreInternalApi fun homeComputerVersionGateTargetOrNull(id: String, targetId: String?): String? =
    targetId?.trim()?.takeIf { id.isComputerVersionGateHomeNotificationId() && it.isNotEmpty() }
