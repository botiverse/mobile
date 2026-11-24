package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftAppUpdatePolicyTest {
    private val currentCode = 12_900_000L
    private val nextMinorCode = 13_000_000L
    private val twoMinorsAheadCode = 13_100_000L
    private val baseTimeMs = 1_700_000_000_000L

    private fun updateSnapshot(
        supported: Boolean = true,
        updateAvailable: Boolean = true,
        currentVersionCode: Long = currentCode,
        latestVersionCode: Long = nextMinorCode,
        transactionState: SharedSettingsUpdateTransactionState = SharedSettingsUpdateTransactionState.Idle,
        forceUpdate: Boolean = false,
        updatePriority: Int = 0,
        stalenessDays: Int? = null,
    ): SharedSettingsUpdateCheckSnapshot =
        SharedSettingsUpdateCheckSnapshot(
            supported = supported,
            updateAvailable = updateAvailable,
            currentVersionLabel = "1.29.0",
            currentVersionCode = currentVersionCode,
            latestVersionLabel = "1.30.0",
            latestVersionCode = latestVersionCode,
            transactionState = transactionState,
            forceUpdate = forceUpdate,
            updatePriority = updatePriority,
            stalenessDays = stalenessDays,
        )

    @Test
    fun freshInstallNeverShowsPostUpdateNotice() {
        val prefs = InMemoryRaftKeyValueStore()
        val store = RaftAppUpdatePromptStore(prefs)

        val launched = store.recordAppLaunch(currentCode)
        assertEquals(currentCode, launched.lastLaunchedVersionCode)
        assertEquals(0L, launched.postUpdateNoticeVersionCode)

        val decision =
            decideRaftAppUpdatePrompt(
                snapshot = null,
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = launched,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertNull(decision.postUpdateNotice)

        // Repeated launches on the same version remain silent.
        val relaunched = store.recordAppLaunch(currentCode)
        assertEquals(0L, relaunched.postUpdateNoticeVersionCode)
    }

    @Test
    fun upgradeShowsPostUpdateNoticeOnceUntilDismissed() {
        val prefs = InMemoryRaftKeyValueStore()
        val store = RaftAppUpdatePromptStore(prefs)

        store.recordAppLaunch(currentCode)
        val upgraded = store.recordAppLaunch(nextMinorCode)
        assertEquals(nextMinorCode, upgraded.lastLaunchedVersionCode)
        assertEquals(nextMinorCode, upgraded.postUpdateNoticeVersionCode)

        val shown =
            decideRaftAppUpdatePrompt(
                snapshot = null,
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = upgraded,
                currentVersionCode = nextMinorCode,
                currentVersionLabel = "1.30.0",
                nowMs = baseTimeMs,
            )
        assertEquals(RaftPostUpdateNoticeState(nextMinorCode, "1.30.0"), shown.postUpdateNotice)

        // Relaunching before dismissal keeps the notice available.
        val relaunched = store.recordAppLaunch(nextMinorCode)
        assertEquals(nextMinorCode, relaunched.postUpdateNoticeVersionCode)

        val dismissed = store.dismissPostUpdateNotice(nextMinorCode)
        val afterDismiss =
            decideRaftAppUpdatePrompt(
                snapshot = null,
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = dismissed,
                currentVersionCode = nextMinorCode,
                currentVersionLabel = "1.30.0",
                nowMs = baseTimeMs,
            )
        assertNull(afterDismiss.postUpdateNotice)
    }

    @Test
    fun recordAppLaunchIgnoresNonPositiveVersionCode() {
        val store = RaftAppUpdatePromptStore(InMemoryRaftKeyValueStore())
        val initial = store.recordAppLaunch(0L)
        assertEquals(RaftAppUpdatePromptPersistenceState(), initial)
        val negative = store.recordAppLaunch(-5L)
        assertEquals(RaftAppUpdatePromptPersistenceState(), negative)
    }

    @Test
    fun unavailableUnsupportedInstalledOrNotNewerSnapshotsOfferNoPrompt() {
        val cases =
            listOf(
                null,
                updateSnapshot(supported = false),
                updateSnapshot(updateAvailable = false),
                updateSnapshot(latestVersionCode = 0L),
                updateSnapshot(latestVersionCode = currentCode),
                updateSnapshot(latestVersionCode = currentCode - 1_000L),
                updateSnapshot(transactionState = SharedSettingsUpdateTransactionState.Installed),
            )
        cases.forEach { snap ->
            val decision =
                decideRaftAppUpdatePrompt(
                    snapshot = snap,
                    minBuildGateDecision = RaftMinBuildGateDecision.None,
                    persistence = RaftAppUpdatePromptPersistenceState(),
                    currentVersionCode = currentCode,
                    currentVersionLabel = "1.29.0",
                    nowMs = baseTimeMs,
                )
            assertFalse(decision.hasAvailableUpdate)
            assertFalse(decision.isReadyToComplete)
            assertFalse(decision.showAmbientPrompt)
            assertFalse(decision.showStrongPromptCandidate)
            assertNull(decision.strongPromptReason)
        }
    }

    @Test
    fun ordinaryUpdateShowsAmbientPromptWithoutStrongPrompt() {
        val decision =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = RaftAppUpdatePromptPersistenceState(firstSeenVersionCode = nextMinorCode, firstSeenAtMs = baseTimeMs),
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs + 1_000L,
            )
        assertTrue(decision.hasAvailableUpdate)
        assertFalse(decision.isReadyToComplete)
        assertFalse(decision.isSnoozed)
        assertTrue(decision.showAmbientPrompt)
        assertFalse(decision.showStrongPromptCandidate)
        assertNull(decision.strongPromptReason)
    }

    @Test
    fun strongPromptReasonTriggersAndPrecedenceOrder() {
        val stalePersistence =
            RaftAppUpdatePromptPersistenceState(
                firstSeenVersionCode = twoMinorsAheadCode,
                firstSeenAtMs = baseTimeMs - RAFT_APP_UPDATE_STALE_PROMPT_THRESHOLD_MS,
            )

        // All 6 triggers simultaneously -> ReadyToRestart wins.
        val restartDecision =
            decideRaftAppUpdatePrompt(
                snapshot =
                    updateSnapshot(
                        latestVersionCode = twoMinorsAheadCode,
                        transactionState = SharedSettingsUpdateTransactionState.ReadyToRestart,
                        forceUpdate = true,
                        updatePriority = 5,
                        stalenessDays = 5,
                    ),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = stalePersistence,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertEquals(RaftAppUpdateStrongPromptReason.ReadyToRestart, restartDecision.strongPromptReason)
        assertTrue(restartDecision.showStrongPromptCandidate)

        // ReadyToInstall beats ForceUpdate / HighPriority / BehindMultipleReleases / UninstalledForDays.
        val installDecision =
            decideRaftAppUpdatePrompt(
                snapshot =
                    updateSnapshot(
                        latestVersionCode = twoMinorsAheadCode,
                        transactionState = SharedSettingsUpdateTransactionState.ReadyToInstall,
                        forceUpdate = true,
                        updatePriority = 5,
                        stalenessDays = 5,
                    ),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = stalePersistence,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertEquals(RaftAppUpdateStrongPromptReason.ReadyToInstall, installDecision.strongPromptReason)
        assertTrue(installDecision.showStrongPromptCandidate)

        // ForceUpdate beats HighPriority / BehindMultipleReleases / UninstalledForDays.
        val forceDecision =
            decideRaftAppUpdatePrompt(
                snapshot =
                    updateSnapshot(
                        latestVersionCode = twoMinorsAheadCode,
                        forceUpdate = true,
                        updatePriority = 5,
                        stalenessDays = 5,
                    ),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = stalePersistence,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertEquals(RaftAppUpdateStrongPromptReason.ForceUpdate, forceDecision.strongPromptReason)
        assertTrue(forceDecision.showStrongPromptCandidate)

        // HighPriority beats BehindMultipleReleases / UninstalledForDays.
        val priorityDecision =
            decideRaftAppUpdatePrompt(
                snapshot =
                    updateSnapshot(
                        latestVersionCode = twoMinorsAheadCode,
                        updatePriority = RAFT_APP_UPDATE_PLAY_HIGH_PRIORITY,
                        stalenessDays = 5,
                    ),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = stalePersistence,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertEquals(RaftAppUpdateStrongPromptReason.HighPriority, priorityDecision.strongPromptReason)
        assertTrue(priorityDecision.showStrongPromptCandidate)

        // BehindMultipleReleases beats UninstalledForDays.
        val lagDecision =
            decideRaftAppUpdatePrompt(
                snapshot =
                    updateSnapshot(
                        latestVersionCode = twoMinorsAheadCode,
                        stalenessDays = 5,
                    ),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = stalePersistence,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertEquals(RaftAppUpdateStrongPromptReason.BehindMultipleReleases, lagDecision.strongPromptReason)
        assertTrue(lagDecision.showStrongPromptCandidate)

        // UninstalledForDays via 72h local firstSeenAtMs.
        val staleByClock =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(latestVersionCode = nextMinorCode),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence =
                    RaftAppUpdatePromptPersistenceState(
                        firstSeenVersionCode = nextMinorCode,
                        firstSeenAtMs = baseTimeMs - RAFT_APP_UPDATE_STALE_PROMPT_THRESHOLD_MS,
                    ),
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertEquals(RaftAppUpdateStrongPromptReason.UninstalledForDays, staleByClock.strongPromptReason)
        assertTrue(staleByClock.showStrongPromptCandidate)

        // UninstalledForDays via Play stalenessDays >= 3.
        val staleByPlay =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(latestVersionCode = nextMinorCode, stalenessDays = RAFT_APP_UPDATE_PLAY_STALE_DAYS),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = RaftAppUpdatePromptPersistenceState(),
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertEquals(RaftAppUpdateStrongPromptReason.UninstalledForDays, staleByPlay.strongPromptReason)
        assertTrue(staleByPlay.showStrongPromptCandidate)
    }

    @Test
    fun strongPromptRespects24HourCooldown() {
        val store = RaftAppUpdatePromptStore(InMemoryRaftKeyValueStore())
        val shown = store.recordStrongPromptShown(baseTimeMs)

        val insideCooldown =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(forceUpdate = true),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = shown,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs + RAFT_APP_UPDATE_STRONG_PROMPT_COOLDOWN_MS - 1L,
            )
        assertEquals(RaftAppUpdateStrongPromptReason.ForceUpdate, insideCooldown.strongPromptReason)
        assertFalse(insideCooldown.showStrongPromptCandidate)
        assertTrue(insideCooldown.showAmbientPrompt)

        val afterCooldown =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(forceUpdate = true),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = shown,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs + RAFT_APP_UPDATE_STRONG_PROMPT_COOLDOWN_MS,
            )
        assertTrue(afterCooldown.showStrongPromptCandidate)
    }

    @Test
    fun snoozingNormalUpdateHidesAmbientAndStrongPromptsUntil24hExpiryOrNewerVersion() {
        val store = RaftAppUpdatePromptStore(InMemoryRaftKeyValueStore())
        store.recordSeenUpdate(nextMinorCode, baseTimeMs)
        val snoozed = store.snoozeUpdate(nextMinorCode, baseTimeMs)

        val duringSnooze =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(forceUpdate = true),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = snoozed,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs + RAFT_APP_UPDATE_SNOOZE_DURATION_MS - 1L,
            )
        assertTrue(duringSnooze.isSnoozed)
        assertFalse(duringSnooze.showAmbientPrompt)
        assertFalse(duringSnooze.showStrongPromptCandidate)

        val afterSnooze =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(forceUpdate = true),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = snoozed,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs + RAFT_APP_UPDATE_SNOOZE_DURATION_MS,
            )
        assertFalse(afterSnooze.isSnoozed)
        assertTrue(afterSnooze.showAmbientPrompt)
        assertTrue(afterSnooze.showStrongPromptCandidate)

        // Discovering a newer version clears the stale snooze immediately.
        val newerSeen = store.recordSeenUpdate(twoMinorsAheadCode, baseTimeMs + 1_000L)
        assertEquals(0L, newerSeen.snoozedVersionCode)
        assertEquals(0L, newerSeen.snoozedUntilMs)
        assertEquals(twoMinorsAheadCode, newerSeen.firstSeenVersionCode)
        assertEquals(baseTimeMs + 1_000L, newerSeen.firstSeenAtMs)
    }

    @Test
    fun snoozingReadyToInstallOrRestartPausesModalWhileKeepingAmbientButtonVisible() {
        val store = RaftAppUpdatePromptStore(InMemoryRaftKeyValueStore())
        val snoozed = store.snoozeUpdate(nextMinorCode, baseTimeMs)

        listOf(
            SharedSettingsUpdateTransactionState.ReadyToInstall to RaftAppUpdateStrongPromptReason.ReadyToInstall,
            SharedSettingsUpdateTransactionState.ReadyToRestart to RaftAppUpdateStrongPromptReason.ReadyToRestart,
        ).forEach { (state, expectedReason) ->
            val duringSnooze =
                decideRaftAppUpdatePrompt(
                    snapshot = updateSnapshot(transactionState = state),
                    minBuildGateDecision = RaftMinBuildGateDecision.None,
                    persistence = snoozed,
                    currentVersionCode = currentCode,
                    currentVersionLabel = "1.29.0",
                    nowMs = baseTimeMs + 60_000L,
                )
            assertTrue(duringSnooze.isReadyToComplete)
            assertTrue(duringSnooze.isSnoozed)
            assertTrue(duringSnooze.showAmbientPrompt)
            assertFalse(duringSnooze.showStrongPromptCandidate)
            assertEquals(expectedReason, duringSnooze.strongPromptReason)

            val afterSnooze =
                decideRaftAppUpdatePrompt(
                    snapshot = updateSnapshot(transactionState = state),
                    minBuildGateDecision = RaftMinBuildGateDecision.None,
                    persistence = snoozed,
                    currentVersionCode = currentCode,
                    currentVersionLabel = "1.29.0",
                    nowMs = baseTimeMs + RAFT_APP_UPDATE_SNOOZE_DURATION_MS,
                )
            assertFalse(afterSnooze.isSnoozed)
            assertTrue(afterSnooze.showAmbientPrompt)
            assertTrue(afterSnooze.showStrongPromptCandidate)
        }
    }

    @Test
    fun installerOpenedKeepsAmbientVisibleWithoutStrongModalByItself() {
        val decision =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(transactionState = SharedSettingsUpdateTransactionState.InstallerOpened),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = RaftAppUpdatePromptPersistenceState(),
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertTrue(decision.isReadyToComplete)
        assertTrue(decision.showAmbientPrompt)
        assertFalse(decision.showStrongPromptCandidate)
        assertNull(decision.strongPromptReason)
    }

    @Test
    fun minBuildGateBlockSuppressesAmbientStrongAndPostUpdateNotices() {
        val persistence =
            RaftAppUpdatePromptPersistenceState(
                lastLaunchedVersionCode = currentCode,
                postUpdateNoticeVersionCode = currentCode,
            )
        val blocked =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(forceUpdate = true, transactionState = SharedSettingsUpdateTransactionState.ReadyToInstall),
                minBuildGateDecision =
                    RaftMinBuildGateDecision.Block(
                        threshold = nextMinorCode,
                        latestBuild = nextMinorCode,
                        updateUrl = "https://example.test/raft.apk",
                    ),
                persistence = persistence,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertTrue(blocked.hasAvailableUpdate)
        assertFalse(blocked.showAmbientPrompt)
        assertFalse(blocked.showStrongPromptCandidate)
        assertNull(blocked.strongPromptReason)
        assertNull(blocked.postUpdateNotice)

        // A non-blocking Banner does not suppress normal update prompts.
        val banner =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(forceUpdate = true),
                minBuildGateDecision = RaftMinBuildGateDecision.Banner(threshold = nextMinorCode, updateUrl = null),
                persistence = persistence,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertTrue(banner.showAmbientPrompt)
        assertTrue(banner.showStrongPromptCandidate)
        // Post-update notice is hidden while a strong update prompt candidate is active.
        assertNull(banner.postUpdateNotice)
    }

    @Test
    fun postUpdateNoticeSuppressedWhileStrongPromptCandidateIsActive() {
        val persistence =
            RaftAppUpdatePromptPersistenceState(
                lastLaunchedVersionCode = currentCode,
                postUpdateNoticeVersionCode = currentCode,
            )
        val withStrongPrompt =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(forceUpdate = true),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = persistence,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertTrue(withStrongPrompt.showStrongPromptCandidate)
        assertNull(withStrongPrompt.postUpdateNotice)

        // When only an ambient update is present, post-update notice is still allowed.
        val ambientOnly =
            decideRaftAppUpdatePrompt(
                snapshot = updateSnapshot(forceUpdate = false),
                minBuildGateDecision = RaftMinBuildGateDecision.None,
                persistence = persistence,
                currentVersionCode = currentCode,
                currentVersionLabel = "1.29.0",
                nowMs = baseTimeMs,
            )
        assertTrue(ambientOnly.showAmbientPrompt)
        assertFalse(ambientOnly.showStrongPromptCandidate)
        assertNotNull(ambientOnly.postUpdateNotice)
    }

    @Test
    fun legacySkippedVersionMigratesTo24hSnoozeAndDoesNotSilenceNewerVersions() {
        val prefs =
            InMemoryRaftKeyValueStore(
                mapOf(RaftAppUpdatePromptStore.LegacySkippedVersionCodeKey to nextMinorCode.toString())
            )
        val store = RaftAppUpdatePromptStore(prefs)

        // Same version migrates into a 24h snooze window.
        val seenSame = store.recordSeenUpdate(nextMinorCode, baseTimeMs)
        assertEquals(nextMinorCode, seenSame.snoozedVersionCode)
        assertEquals(baseTimeMs + RAFT_APP_UPDATE_SNOOZE_DURATION_MS, seenSame.snoozedUntilMs)

        // Once 24h elapses, observing the same version clears both the snooze and the legacy key.
        val seenExpired = store.recordSeenUpdate(nextMinorCode, baseTimeMs + RAFT_APP_UPDATE_SNOOZE_DURATION_MS)
        assertEquals(0L, seenExpired.snoozedVersionCode)
        assertEquals(0L, seenExpired.snoozedUntilMs)
        assertNull(prefs.getString(RaftAppUpdatePromptStore.LegacySkippedVersionCodeKey))

        // A legacy skipped version never silences a newer version and gets cleaned up immediately.
        prefs.putString(RaftAppUpdatePromptStore.LegacySkippedVersionCodeKey, nextMinorCode.toString())
        val seenNewer = store.recordSeenUpdate(twoMinorsAheadCode, baseTimeMs)
        assertEquals(0L, seenNewer.snoozedVersionCode)
        assertNull(prefs.getString(RaftAppUpdatePromptStore.LegacySkippedVersionCodeKey))

        // ClearSnooze and explicit snoozeUpdate also clean up legacy skipped key.
        prefs.putString(RaftAppUpdatePromptStore.LegacySkippedVersionCodeKey, nextMinorCode.toString())
        store.clearSnooze()
        assertNull(prefs.getString(RaftAppUpdatePromptStore.LegacySkippedVersionCodeKey))
    }
}
