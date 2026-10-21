package build.raft.app

import build.raft.app.data.SettingsComputerAcceptedUpgrade
import build.raft.app.data.SettingsComputerControlState
import build.raft.app.data.SettingsComputerReleaseNotesSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerUpgradeRequestSnapshot
import build.raft.app.data.settingsComputerUpgradeAvailabilityKey
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull

/*
 * Locale-free decisions of the computer detail page's Computer card, ported from the web
 * (botiverse/slock staging 6b367a0cd40adf0532ae0fe226091a8dbd395140):
 * packages/shared/src/index.ts (`isComputerSemver`, `compareComputerVersions`,
 * `isRemoteUpgradeSupported`), packages/web/src/utils/computerVersionFact.ts,
 * packages/web/src/utils/computerUpgradeInFlight.ts, packages/web/src/utils/computerVersionTier.ts,
 * packages/web/src/hooks/useComputerUpgradeReconnect.ts and MachineDetailPanel.tsx (`cardState`).
 */

private val raftComputerSemver =
    Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?$")

/** Full SemVer 2.0.0 (prerelease and build metadata allowed, no leading zeros). */
internal fun raftIsComputerSemver(value: String): Boolean {
    if (!raftComputerSemver.matches(value)) return false
    val pre = value.substringBefore('+').split("-").drop(1).joinToString("-")
    return pre.split(".").none { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }
}

private fun String.semverNumberCompare(other: String): Int {
    val a = trimStart('0')
    val b = other.trimStart('0')
    if (a.length != b.length) return a.length.compareTo(b.length)
    return a.compareTo(b).coerceIn(-1, 1)
}

/** SemVer precedence (-1 / 0 / 1); build metadata is ignored, a prerelease sorts below its release. */
internal fun raftCompareComputerVersions(left: String, right: String): Int {
    fun parse(value: String): Pair<List<String>, List<String>?> {
        val withoutBuild = value.substringBefore('+')
        val separator = withoutBuild.indexOf('-')
        val core = (if (separator < 0) withoutBuild else withoutBuild.substring(0, separator)).split(".")
        val pre = if (separator < 0) null else withoutBuild.substring(separator + 1).split(".")
        return core to pre
    }
    val (aCore, aPre) = parse(left)
    val (bCore, bPre) = parse(right)
    for (i in 0 until 3) {
        val x = aCore.getOrElse(i) { "0" }
        val y = bCore.getOrElse(i) { "0" }
        val order = x.semverNumberCompare(y)
        if (order != 0) return if (order < 0) -1 else 1
    }
    if (aPre == null || bPre == null) {
        return when {
            aPre == null && bPre == null -> 0
            aPre == null -> 1
            else -> -1
        }
    }
    for (i in 0 until maxOf(aPre.size, bPre.size)) {
        val x = aPre.getOrNull(i)
        val y = bPre.getOrNull(i)
        if (x == y) continue
        if (x == null) return -1
        if (y == null) return 1
        val numericX = x.all(Char::isDigit)
        val numericY = y.all(Char::isDigit)
        if (numericX && numericY) return if (x.semverNumberCompare(y) < 0) -1 else 1
        if (numericX != numericY) return if (numericX) -1 else 1
        return if (x < y) -1 else 1
    }
    return 0
}

/** Oldest Computer the web drives a remote upgrade on (shared `COMPUTER_REMOTE_UPGRADE_MIN_VERSION`). */
internal const val RAFT_COMPUTER_REMOTE_UPGRADE_MIN_VERSION = "1.0.45"

/** Shared `isRemoteUpgradeSupported`: null when the version is unknown. */
internal fun raftIsRemoteUpgradeSupported(version: String?): Boolean? {
    val trimmed = version?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (!raftIsComputerSemver(trimmed)) return false
    return raftCompareComputerVersions(trimmed, RAFT_COMPUTER_REMOTE_UPGRADE_MIN_VERSION) >= 0
}

/** Web `ComputerVersionFact`. */
internal sealed interface RaftComputerVersionFact {
    data object Unknown : RaftComputerVersionFact
    data object CannotCheck : RaftComputerVersionFact
    data object Current : RaftComputerVersionFact
    data class Outdated(val availableVersion: String) : RaftComputerVersionFact
}

private val raftComputerReleaseLookupFailedReasons =
    setOf("source_unparseable", "platform_unknown", "hands_unavailable", "hands_response_invalid", "hands_artifact_missing")

/** Web `getComputerVersionFact(machine, latestComputerVersion)`. */
internal fun raftComputerVersionFact(computer: SettingsComputerSnapshot): RaftComputerVersionFact {
    val version = computer.computerVersion?.trim()?.takeIf { it.isNotBlank() } ?: return RaftComputerVersionFact.Unknown
    if (!raftIsComputerSemver(version)) return RaftComputerVersionFact.CannotCheck
    val policy = computer.broadcastPolicy
    val policyTarget = policy?.takeIf { it.eligible }?.targetVersion?.takeIf(::raftIsComputerSemver)
    if (policyTarget != null && raftCompareComputerVersions(policyTarget, version) > 0) {
        return RaftComputerVersionFact.Outdated(policyTarget)
    }
    val latest = computer.latestComputerVersion?.trim()?.takeIf { it.isNotBlank() && raftIsComputerSemver(it) }
        ?: return RaftComputerVersionFact.CannotCheck
    if (raftCompareComputerVersions(latest, version) <= 0) return RaftComputerVersionFact.Current
    if (policy?.reasonCode?.let { it in raftComputerReleaseLookupFailedReasons } == true) return RaftComputerVersionFact.CannotCheck
    return RaftComputerVersionFact.Outdated(latest)
}

/** Web `ComputerVersionTier` from the server's `versionGate` (null gate: no notice). */
internal sealed interface RaftComputerVersionTier {
    data object None : RaftComputerVersionTier

    data class Notice(
        val reinstall: Boolean,
        /** `computer_version_unsupported` or `computer_version_unknown`. */
        val reason: String,
        val minVersion: String,
        val enforced: Boolean,
        val enforcedAt: String?,
    ) : RaftComputerVersionTier
}

/**
 * Web `computerVersionTier`, from the server gate only. A Computer online without a version yet is
 * [RaftComputerVersionTier.None] here: the web waits 20 s before calling that a reinstall, and the
 * app re-reads the row on the Computer's next status event.
 */
internal fun raftComputerVersionTier(computer: SettingsComputerSnapshot): RaftComputerVersionTier {
    if (!computer.isComputer) return RaftComputerVersionTier.None
    val gate = computer.versionGate ?: return RaftComputerVersionTier.None
    val version = computer.computerVersion?.trim()?.takeIf { it.isNotBlank() } ?: return RaftComputerVersionTier.None
    val webCanUpgrade = raftIsComputerSemver(version) && raftCompareComputerVersions(version, RAFT_COMPUTER_REMOTE_UPGRADE_MIN_VERSION) >= 0
    return RaftComputerVersionTier.Notice(
        reinstall = !webCanUpgrade,
        reason = gate.reason,
        minVersion = gate.minVersion,
        enforced = gate.enforced,
        enforcedAt = gate.enforcedAt,
    )
}

/** Server deadline of an upgrade request (`UPGRADE_REQUEST_DEADLINE_MS`). */
internal const val RAFT_COMPUTER_UPGRADE_REQUEST_DEADLINE_MILLIS = 30L * 60L * 1000L

/** How long an offline Computer with an upgrade in flight reads as "restarting for the upgrade". */
internal const val RAFT_COMPUTER_UPGRADE_RECONNECT_GRACE_MILLIS = 3L * 60L * 1000L

private fun raftComputerEpochMillis(iso: String?): Long? =
    iso?.trim()?.takeIf { it.isNotBlank() }?.let { parseRaftTimestampEpochMillisOrNull(it, localOffsetMinutes = 0) }

/** Web `computerUpgradeInFlight`: the server's pending row, else the request this page saw accepted. */
internal fun raftComputerUpgradeInFlight(
    computer: SettingsComputerSnapshot,
    accepted: SettingsComputerAcceptedUpgrade?,
    nowMillis: Long,
): SettingsComputerUpgradeRequestSnapshot? {
    val row = computer.upgradeRequest
    if (row?.state == "pending") return row
    val mine = accepted?.takeIf { it.machineId == computer.id } ?: return null
    if (row != null) {
        if (row.id == mine.request.id) return null
        val rowAt = raftComputerEpochMillis(row.requestedAt)
        val mineAt = raftComputerEpochMillis(mine.request.requestedAt)
        if (rowAt != null && mineAt != null && rowAt >= mineAt) return null
    }
    if (computer.online && computer.computerVersion == mine.request.targetVersion) return null
    if (nowMillis >= mine.acceptedAtMillis + RAFT_COMPUTER_UPGRADE_REQUEST_DEADLINE_MILLIS) return null
    return mine.request
}

/** Web `useComputerUpgradeReconnect`: offline while an upgrade is in flight is its restart, not an outage. */
internal data class RaftComputerUpgradeReconnect(
    val inFlight: SettingsComputerUpgradeRequestSnapshot?,
    val reconnecting: Boolean,
    val overdue: Boolean,
)

internal fun raftComputerUpgradeReconnect(
    computer: SettingsComputerSnapshot,
    accepted: SettingsComputerAcceptedUpgrade?,
    nowMillis: Long,
): RaftComputerUpgradeReconnect {
    val inFlight = raftComputerUpgradeInFlight(computer, accepted, nowMillis)
    val waitingOffline = computer.isComputer && !computer.online && inFlight != null
    if (!waitingOffline || inFlight == null) return RaftComputerUpgradeReconnect(inFlight, reconnecting = false, overdue = false)
    // The row carries no disconnect time: the last heartbeat before it, never earlier than the request.
    val offlineSince = maxOf(raftComputerEpochMillis(inFlight.requestedAt) ?: 0L, raftComputerEpochMillis(computer.lastHeartbeat) ?: 0L)
    val graceElapsed = nowMillis >= offlineSince + RAFT_COMPUTER_UPGRADE_RECONNECT_GRACE_MILLIS
    return RaftComputerUpgradeReconnect(inFlight, reconnecting = !graceElapsed, overdue = graceElapsed)
}

/** Web `UpgradeRefusal`, mapped from the route's code (never its text). */
internal enum class RaftComputerUpgradeRefusal { WebUpgradeOff, TooOld, NotAllowed, Unknown }

internal fun raftComputerUpgradeRefusal(code: String?): RaftComputerUpgradeRefusal =
    when (code) {
        "remote_upgrade_disabled" -> RaftComputerUpgradeRefusal.WebUpgradeOff
        "computer_remote_upgrade_unsupported" -> RaftComputerUpgradeRefusal.TooOld
        "computer_broadcast_not_eligible" -> RaftComputerUpgradeRefusal.NotAllowed
        else -> RaftComputerUpgradeRefusal.Unknown
    }

/** The Computer card's one state: a status sentence and exactly one next step (web `cardState`). */
internal sealed interface RaftComputerCardState {
    data object Reinstall : RaftComputerCardState
    data object Reading : RaftComputerCardState
    data object CannotCheck : RaftComputerCardState
    data object UpToDate : RaftComputerCardState
    data class Upgrading(val version: String) : RaftComputerCardState
    data class OneClick(val version: String) : RaftComputerCardState
    data class ManualUpgrade(val version: String) : RaftComputerCardState
    data class Commands(val version: String, val refusedNotAllowed: Boolean) : RaftComputerCardState
}

/** Everything the Computer card and the version notice decide from, resolved once per composition. */
internal data class RaftComputerUpgradeModel(
    val fact: RaftComputerVersionFact,
    val tier: RaftComputerVersionTier,
    val card: RaftComputerCardState,
    val refusal: RaftComputerUpgradeRefusal?,
    val reconnect: RaftComputerUpgradeReconnect,
    /** The last web upgrade failed or got no answer, and the Computer is still below its target. */
    val requestOutcome: SettingsComputerUpgradeRequestSnapshot?,
    /** The server's broadcast gate is open (it reads the same `remote_computer_upgrade_v2` flag). */
    val webUpgradeOn: Boolean,
    /** Release notes of exactly the version the card offers, when the server has them. */
    val releaseNotes: SettingsComputerReleaseNotesSnapshot?,
)

internal fun raftComputerUpgradeModel(
    computer: SettingsComputerSnapshot,
    control: SettingsComputerControlState,
    nowMillis: Long,
): RaftComputerUpgradeModel {
    val fact = raftComputerVersionFact(computer)
    val tier = raftComputerVersionTier(computer)
    val reconnect = raftComputerUpgradeReconnect(computer, control.upgradeAccepted, nowMillis)
    val refusal =
        control.upgradeRefusal
            ?.takeIf { it.machineId == computer.id && it.availabilityKey == settingsComputerUpgradeAvailabilityKey(computer) }
            ?.let { raftComputerUpgradeRefusal(it.code) }
    val submittingVersion = control.upgradeSubmitting?.takeIf { it.machineId == computer.id }?.targetVersion
    val remoteUpgradeSupported = (computer.remoteUpgradeSupported ?: raftIsRemoteUpgradeSupported(computer.computerVersion)) == true
    val policy = computer.broadcastPolicy
    val broadcastGated = policy?.reasonCode == "broadcast_disabled" || policy?.reasonCode == "broadcast_gate_unavailable"
    val webUpgradeOn = !broadcastGated
    val oneClickTarget = policy?.takeIf { computer.upgradeAvailable && it.eligible }?.targetVersion
    val inFlight = reconnect.inFlight
    val card =
        when {
            tier is RaftComputerVersionTier.Notice && tier.reinstall -> RaftComputerCardState.Reinstall
            fact is RaftComputerVersionFact.Unknown -> RaftComputerCardState.Reading
            fact is RaftComputerVersionFact.CannotCheck -> RaftComputerCardState.CannotCheck
            fact is RaftComputerVersionFact.Current -> RaftComputerCardState.UpToDate
            fact is RaftComputerVersionFact.Outdated -> {
                val version = fact.availableVersion
                when {
                    inFlight != null -> RaftComputerCardState.Upgrading(inFlight.targetVersion)
                    submittingVersion != null -> RaftComputerCardState.Upgrading(submittingVersion)
                    refusal == RaftComputerUpgradeRefusal.NotAllowed -> RaftComputerCardState.Commands(version, refusedNotAllowed = true)
                    refusal == RaftComputerUpgradeRefusal.WebUpgradeOff || refusal == RaftComputerUpgradeRefusal.TooOld ->
                        RaftComputerCardState.Commands(version, refusedNotAllowed = false)
                    !remoteUpgradeSupported -> RaftComputerCardState.ManualUpgrade(version)
                    webUpgradeOn && oneClickTarget != null -> RaftComputerCardState.OneClick(oneClickTarget)
                    else -> RaftComputerCardState.Commands(version, refusedNotAllowed = false)
                }
            }
            else -> RaftComputerCardState.Reading
        }
    val row = computer.upgradeRequest
    val version = computer.computerVersion?.trim()?.takeIf { it.isNotBlank() && raftIsComputerSemver(it) }
    val requestOutcome =
        if (row != null && version != null && (row.state == "failed" || row.state == "no_response") &&
            raftIsComputerSemver(row.targetVersion) && raftCompareComputerVersions(version, row.targetVersion) < 0
        ) {
            row
        } else {
            null
        }
    val releaseNotes =
        (fact as? RaftComputerVersionFact.Outdated)?.let { outdated ->
            computer.latestComputerReleaseNotes?.takeIf { it.version == outdated.availableVersion }
        }
    return RaftComputerUpgradeModel(
        fact = fact,
        tier = tier,
        card = card,
        refusal = refusal,
        reconnect = reconnect,
        requestOutcome = requestOutcome,
        webUpgradeOn = webUpgradeOn,
        releaseNotes = releaseNotes,
    )
}

/** Shared `pickComputerReleaseNotesText`: the UI language's notes first (Chinese for `zh*`), else the other. */
internal fun raftComputerReleaseNotesText(notes: SettingsComputerReleaseNotesSnapshot?, languageTag: String?): String? {
    if (notes == null) return null
    val chinese = languageTag?.trim()?.lowercase()?.startsWith("zh") == true
    return if (chinese) notes.zhCn ?: notes.en else notes.en ?: notes.zhCn
}
