package build.raft.app.data

/** Default duration for "Remind me later" update snooze (24 hours). */
const val RAFT_APP_UPDATE_SNOOZE_DURATION_MS: Long = 24L * 60L * 60L * 1_000L

/** Minimum cooldown between strong update modal prompts (24 hours). */
const val RAFT_APP_UPDATE_STRONG_PROMPT_COOLDOWN_MS: Long = 24L * 60L * 60L * 1_000L

/** Elapsed time after first seeing an update before escalating to a strong prompt (72 hours / 3 days). */
const val RAFT_APP_UPDATE_STALE_PROMPT_THRESHOLD_MS: Long = 72L * 60L * 60L * 1_000L

/**
 * Version-code gap representing >= 2 minor releases under Raft's version code formula
 * (`major * 10_000_000 + minor * 100_000 + patch * 1_000 + build`).
 */
const val RAFT_APP_UPDATE_MINOR_LAG_THRESHOLD: Long = 200_000L

/** Google Play in-app update priority threshold that triggers a strong update prompt. */
const val RAFT_APP_UPDATE_PLAY_HIGH_PRIORITY: Int = 4

/** Google Play client staleness threshold (in days) that triggers a strong update prompt. */
const val RAFT_APP_UPDATE_PLAY_STALE_DAYS: Int = 3

/** Device-local persistence record for app update prompts, snoozes, and post-update notices. */
data class RaftAppUpdatePromptPersistenceState(
    val firstSeenVersionCode: Long = 0L,
    val firstSeenAtMs: Long = 0L,
    val snoozedVersionCode: Long = 0L,
    val snoozedUntilMs: Long = 0L,
    val lastStrongPromptAtMs: Long = 0L,
    val lastLaunchedVersionCode: Long = 0L,
    val postUpdateNoticeVersionCode: Long = 0L,
    val dismissedPostUpdateVersionCode: Long = 0L,
)

/**
 * Device-local persistence for update reminder cadence, 24h snooze, and upgrade detection.
 *
 * Uses `appUpdate.prompt.*` keys and cleanly coexists with / migrates legacy
 * `appUpdate.skippedVersionCode` so a legacy skip never permanently silences newer versions
 * or outlives a 24h snooze window.
 */
class RaftAppUpdatePromptStore(private val store: SharedPreferenceStore) {
    fun readState(): RaftAppUpdatePromptPersistenceState =
        RaftAppUpdatePromptPersistenceState(
            firstSeenVersionCode = readLong(KeyFirstSeenVersionCode),
            firstSeenAtMs = readLong(KeyFirstSeenAtMs),
            snoozedVersionCode = readLong(KeySnoozedVersionCode),
            snoozedUntilMs = readLong(KeySnoozedUntilMs),
            lastStrongPromptAtMs = readLong(KeyLastStrongPromptAtMs),
            lastLaunchedVersionCode = readLong(KeyLastLaunchedVersionCode),
            postUpdateNoticeVersionCode = readLong(KeyPostUpdateNoticeVersionCode),
            dismissedPostUpdateVersionCode = readLong(KeyDismissedPostUpdateVersionCode),
        )

    /**
     * Records that an update targeting [versionCode] was observed at [nowMs].
     *
     * - Initializes `firstSeenVersionCode`/`firstSeenAtMs` when a new `versionCode > 0L` is seen.
     * - Clears stale snoozes belonging to a different version.
     * - Migrates a matching legacy `appUpdate.skippedVersionCode` into a bounded 24h snooze and
     *   clears the legacy key once expired or superseded by a newer version.
     */
    fun recordSeenUpdate(versionCode: Long, nowMs: Long): RaftAppUpdatePromptPersistenceState {
        if (versionCode <= 0L) return readState()
        val current = readState()
        val safeNow = nowMs.coerceAtLeast(0L)

        var firstSeenVersionCode = current.firstSeenVersionCode
        var firstSeenAtMs = current.firstSeenAtMs
        if (firstSeenVersionCode != versionCode || firstSeenAtMs <= 0L) {
            firstSeenVersionCode = versionCode
            firstSeenAtMs = safeNow
            writeLong(KeyFirstSeenVersionCode, firstSeenVersionCode)
            writeLong(KeyFirstSeenAtMs, firstSeenAtMs)
        }

        var snoozedVersionCode = current.snoozedVersionCode
        var snoozedUntilMs = current.snoozedUntilMs
        if (snoozedVersionCode != 0L && snoozedVersionCode != versionCode) {
            snoozedVersionCode = 0L
            snoozedUntilMs = 0L
            clearKey(KeySnoozedVersionCode)
            clearKey(KeySnoozedUntilMs)
        }

        val legacySkipped = readLong(LegacySkippedVersionCodeKey)
        if (legacySkipped > 0L) {
            when {
                legacySkipped != versionCode -> {
                    clearKey(LegacySkippedVersionCodeKey)
                }
                snoozedVersionCode != versionCode -> {
                    snoozedVersionCode = versionCode
                    snoozedUntilMs = safeNow + RAFT_APP_UPDATE_SNOOZE_DURATION_MS
                    writeLong(KeySnoozedVersionCode, snoozedVersionCode)
                    writeLong(KeySnoozedUntilMs, snoozedUntilMs)
                }
                snoozedUntilMs > 0L && safeNow >= snoozedUntilMs -> {
                    snoozedVersionCode = 0L
                    snoozedUntilMs = 0L
                    clearKey(KeySnoozedVersionCode)
                    clearKey(KeySnoozedUntilMs)
                    clearKey(LegacySkippedVersionCodeKey)
                }
            }
        }

        return current.copy(
            firstSeenVersionCode = firstSeenVersionCode,
            firstSeenAtMs = firstSeenAtMs,
            snoozedVersionCode = snoozedVersionCode,
            snoozedUntilMs = snoozedUntilMs,
        )
    }

    fun snoozeUpdate(
        versionCode: Long,
        nowMs: Long,
        durationMs: Long = RAFT_APP_UPDATE_SNOOZE_DURATION_MS,
    ): RaftAppUpdatePromptPersistenceState {
        if (versionCode <= 0L) return readState()
        val untilMs = nowMs.coerceAtLeast(0L) + durationMs.coerceAtLeast(0L)
        writeLong(KeySnoozedVersionCode, versionCode)
        writeLong(KeySnoozedUntilMs, untilMs)
        clearKey(LegacySkippedVersionCodeKey)
        return readState()
    }

    fun clearSnooze(): RaftAppUpdatePromptPersistenceState {
        clearKey(KeySnoozedVersionCode)
        clearKey(KeySnoozedUntilMs)
        clearKey(LegacySkippedVersionCodeKey)
        return readState()
    }

    fun recordStrongPromptShown(nowMs: Long): RaftAppUpdatePromptPersistenceState {
        writeLong(KeyLastStrongPromptAtMs, nowMs.coerceAtLeast(0L))
        return readState()
    }

    /**
     * Records a launch of [currentVersionCode].
     *
     * Distinguishes upgrades (`lastLaunchedVersionCode > 0L && currentVersionCode > lastLaunchedVersionCode`)
     * from fresh installs (`lastLaunchedVersionCode == 0L`), so fresh installs never trigger a
     * post-update "What's New" notice.
     */
    fun recordAppLaunch(currentVersionCode: Long): RaftAppUpdatePromptPersistenceState {
        if (currentVersionCode <= 0L) return readState()
        val current = readState()

        val legacySkipped = readLong(LegacySkippedVersionCodeKey)
        if (legacySkipped in 1L..currentVersionCode) {
            clearKey(LegacySkippedVersionCodeKey)
        }
        if (current.snoozedVersionCode in 1L..currentVersionCode) {
            clearKey(KeySnoozedVersionCode)
            clearKey(KeySnoozedUntilMs)
        }
        if (current.firstSeenVersionCode in 1L..currentVersionCode) {
            clearKey(KeyFirstSeenVersionCode)
            clearKey(KeyFirstSeenAtMs)
        }

        when {
            current.lastLaunchedVersionCode <= 0L -> {
                writeLong(KeyLastLaunchedVersionCode, currentVersionCode)
            }
            currentVersionCode > current.lastLaunchedVersionCode -> {
                writeLong(KeyPostUpdateNoticeVersionCode, currentVersionCode)
                writeLong(KeyLastLaunchedVersionCode, currentVersionCode)
            }
            currentVersionCode < current.lastLaunchedVersionCode -> {
                writeLong(KeyLastLaunchedVersionCode, currentVersionCode)
            }
        }
        return readState()
    }

    fun dismissPostUpdateNotice(versionCode: Long): RaftAppUpdatePromptPersistenceState {
        if (versionCode <= 0L) return readState()
        writeLong(KeyDismissedPostUpdateVersionCode, versionCode)
        return readState()
    }

    private fun readLong(key: String): Long =
        store.getString(key)?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: 0L

    private fun writeLong(key: String, value: Long) {
        if (value > 0L) {
            store.putString(key, value.toString())
        } else {
            store.putString(key, null)
        }
    }

    private fun clearKey(key: String) {
        store.putString(key, null)
    }

    companion object {
        const val KeyFirstSeenVersionCode = "appUpdate.prompt.firstSeenVersionCode"
        const val KeyFirstSeenAtMs = "appUpdate.prompt.firstSeenAtMs"
        const val KeySnoozedVersionCode = "appUpdate.prompt.snoozedVersionCode"
        const val KeySnoozedUntilMs = "appUpdate.prompt.snoozedUntilMs"
        const val KeyLastStrongPromptAtMs = "appUpdate.prompt.lastStrongPromptAtMs"
        const val KeyLastLaunchedVersionCode = "appUpdate.prompt.lastLaunchedVersionCode"
        const val KeyPostUpdateNoticeVersionCode = "appUpdate.prompt.postUpdateNoticeVersionCode"
        const val KeyDismissedPostUpdateVersionCode = "appUpdate.prompt.dismissedPostUpdateVersionCode"
        const val LegacySkippedVersionCodeKey = "appUpdate.skippedVersionCode"
    }
}

/** Reason an available update qualifies for an L2 strong prompt modal. */
enum class RaftAppUpdateStrongPromptReason {
    ForceUpdate,
    HighPriority,
    BehindMultipleReleases,
    UninstalledForDays,
    ReadyToInstall,
    ReadyToRestart,
}

/** L0 post-update "What's New" light notice state. */
data class RaftPostUpdateNoticeState(
    val versionCode: Long,
    val versionLabel: String,
)

/** Pure policy output consumed by the coordinator, global prompt arbiter, and UI surfaces. */
data class RaftAppUpdatePromptDecision(
    val hasAvailableUpdate: Boolean = false,
    val isReadyToComplete: Boolean = false,
    val isSnoozed: Boolean = false,
    val showAmbientPrompt: Boolean = false,
    val showStrongPromptCandidate: Boolean = false,
    val strongPromptReason: RaftAppUpdateStrongPromptReason? = null,
    val postUpdateNotice: RaftPostUpdateNoticeState? = null,
)

/**
 * Pure update reminder policy (Layer 2).
 *
 * - L3 (`minBuildGateDecision is Block`) suppresses all L0/L1/L2 prompts.
 * - Snoozing pauses strong prompt modals for 24h (including Ready-to-Install / Ready-to-Restart
 *   modals so "Later (24h)" actually dismisses the modal), while ready-to-complete updates keep
 *   their ambient button (`showAmbientPrompt = true`) so the user can finish installing/restarting
 *   at any time.
 */
fun decideRaftAppUpdatePrompt(
    snapshot: SharedSettingsUpdateCheckSnapshot?,
    minBuildGateDecision: RaftMinBuildGateDecision,
    persistence: RaftAppUpdatePromptPersistenceState,
    currentVersionCode: Long,
    currentVersionLabel: String,
    nowMs: Long,
): RaftAppUpdatePromptDecision {
    val effectiveCurrent =
        currentVersionCode.takeIf { it > 0L }
            ?: snapshot?.currentVersionCode?.takeIf { it > 0L }
            ?: 0L
    val hasAvailableUpdate =
        snapshot != null &&
            snapshot.supported &&
            snapshot.updateAvailable &&
            snapshot.latestVersionCode > 0L &&
            (effectiveCurrent <= 0L || snapshot.latestVersionCode > effectiveCurrent) &&
            snapshot.transactionState != SharedSettingsUpdateTransactionState.Installed
    val isReadyToComplete =
        hasAvailableUpdate &&
            (snapshot.transactionState == SharedSettingsUpdateTransactionState.ReadyToInstall ||
                snapshot.transactionState == SharedSettingsUpdateTransactionState.InstallerOpened ||
                snapshot.transactionState == SharedSettingsUpdateTransactionState.ReadyToRestart)
    val isSnoozed =
        hasAvailableUpdate &&
            persistence.snoozedVersionCode == snapshot.latestVersionCode &&
            persistence.snoozedUntilMs > 0L &&
            nowMs < persistence.snoozedUntilMs

    if (minBuildGateDecision is RaftMinBuildGateDecision.Block) {
        return RaftAppUpdatePromptDecision(
            hasAvailableUpdate = hasAvailableUpdate,
            isReadyToComplete = isReadyToComplete,
            isSnoozed = isSnoozed,
            showAmbientPrompt = false,
            showStrongPromptCandidate = false,
            strongPromptReason = null,
            postUpdateNotice = null,
        )
    }

    val strongPromptReason =
        if (!hasAvailableUpdate) {
            null
        } else {
            val staleByFirstSeen =
                persistence.firstSeenVersionCode == snapshot.latestVersionCode &&
                    persistence.firstSeenAtMs > 0L &&
                    (nowMs - persistence.firstSeenAtMs) >= RAFT_APP_UPDATE_STALE_PROMPT_THRESHOLD_MS
            val staleByChannel =
                snapshot.stalenessDays != null &&
                    snapshot.stalenessDays >= RAFT_APP_UPDATE_PLAY_STALE_DAYS
            when {
                snapshot.transactionState == SharedSettingsUpdateTransactionState.ReadyToRestart ->
                    RaftAppUpdateStrongPromptReason.ReadyToRestart
                snapshot.transactionState == SharedSettingsUpdateTransactionState.ReadyToInstall ->
                    RaftAppUpdateStrongPromptReason.ReadyToInstall
                snapshot.forceUpdate ->
                    RaftAppUpdateStrongPromptReason.ForceUpdate
                snapshot.updatePriority >= RAFT_APP_UPDATE_PLAY_HIGH_PRIORITY ->
                    RaftAppUpdateStrongPromptReason.HighPriority
                effectiveCurrent > 0L &&
                    (snapshot.latestVersionCode - effectiveCurrent) >= RAFT_APP_UPDATE_MINOR_LAG_THRESHOLD ->
                    RaftAppUpdateStrongPromptReason.BehindMultipleReleases
                staleByFirstSeen || staleByChannel ->
                    RaftAppUpdateStrongPromptReason.UninstalledForDays
                else -> null
            }
        }

    val strongCooldownElapsed =
        persistence.lastStrongPromptAtMs <= 0L ||
            (nowMs - persistence.lastStrongPromptAtMs) >= RAFT_APP_UPDATE_STRONG_PROMPT_COOLDOWN_MS
    val showAmbientPrompt = hasAvailableUpdate && (isReadyToComplete || !isSnoozed)
    val showStrongPromptCandidate =
        hasAvailableUpdate &&
            strongPromptReason != null &&
            !isSnoozed &&
            strongCooldownElapsed

    val postUpdateNotice =
        if (
            !showStrongPromptCandidate &&
            currentVersionCode > 0L &&
            persistence.postUpdateNoticeVersionCode == currentVersionCode &&
            persistence.dismissedPostUpdateVersionCode != currentVersionCode
        ) {
            val label =
                currentVersionLabel.trim()
                    .ifBlank { snapshot?.currentVersionLabel?.trim().orEmpty() }
                    .ifBlank { raftVersionNameOfBuildNumber(currentVersionCode).orEmpty() }
                    .ifBlank { currentVersionCode.toString() }
            RaftPostUpdateNoticeState(
                versionCode = currentVersionCode,
                versionLabel = label,
            )
        } else {
            null
        }

    return RaftAppUpdatePromptDecision(
        hasAvailableUpdate = hasAvailableUpdate,
        isReadyToComplete = isReadyToComplete,
        isSnoozed = isSnoozed,
        showAmbientPrompt = showAmbientPrompt,
        showStrongPromptCandidate = showStrongPromptCandidate,
        strongPromptReason = strongPromptReason,
        postUpdateNotice = postUpdateNotice,
    )
}
