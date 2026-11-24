package build.raft.app.data

import build.raft.app.appUpdateCanSkipVersion
import build.raft.app.appUpdatePrimaryActionLabel
import build.raft.app.loadSignedOutAppUpdateNotification
import build.raft.app.withSkippedAppUpdateVersionApplied
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The store installs' new-version notice: a Google Play / App Store answer becomes the same update
 * snapshot (and Home notice, More dot, update page) as a Hands build's, skipping a version hides it
 * until a newer build, and the store is asked at most every few hours.
 */
class RaftStoreUpdateNoticeTest {
    private val page = "https://play.google.com/store/apps/details?id=build.raft.app"
    private val current = 12_600_000L

    private fun snapshot(
        latest: RaftInstallChannelLatest,
        fallbackUrl: String? = null,
        currentBuild: Long? = current,
        gateBlocking: Boolean = false,
    ): SharedSettingsUpdateCheckSnapshot =
        raftStoreUpdateCheckSnapshot(
            answer = RaftStoreUpdateAnswer(channel = "play", latest = latest, fallbackUrl = fallbackUrl),
            currentBuild = currentBuild,
            currentVersionLabel = "1.26.0",
            minBuildGateBlocking = gateBlocking,
        )

    @Test
    fun aNewerStoreBuildIsTheSameNoticeAsAHandsUpdate() {
        val update = snapshot(RaftInstallChannelLatest.Available(build = 12_700_000L, updateUrl = page))

        assertTrue(update.supported)
        assertTrue(update.updateAvailable)
        assertEquals("play", update.storeChannel)
        assertEquals(SharedSettingsUpdateTransactionState.Idle, update.transactionState)
        assertEquals(12_700_000L, update.latestVersionCode)
        assertEquals("1.27.0", update.latestVersionLabel)
        assertEquals(page, update.downloadUrl)
        assertTrue(appUpdateCanSkipVersion(update))
        assertEquals("Update", appUpdatePrimaryActionLabel(update, actionRunning = false))

        val notice = assertNotNull(update.toSharedHomeAppUpdateNotification())
        assertEquals(APP_UPDATE_HOME_NOTIFICATION_ID, notice.id)
        assertEquals("Update", notice.actionLabel)
        // A store lists a version name: no build number next to a different name.
        assertEquals("Current version 1.26.0. Latest version 1.27.0.", notice.body)
    }

    @Test
    fun theStorePageFallsBackWhenTheAnswerHasNone() {
        val update = snapshot(RaftInstallChannelLatest.Available(build = 12_700_000L, updateUrl = null), fallbackUrl = page)
        assertTrue(update.updateAvailable)
        assertEquals(page, update.downloadUrl)
    }

    @Test
    fun storeUpdateForwardsPriorityAndStalenessDays() {
        val update =
            snapshot(
                RaftInstallChannelLatest.Available(
                    build = 12_700_000L,
                    updateUrl = page,
                    updatePriority = 4,
                    stalenessDays = 5,
                )
            )
        assertTrue(update.updateAvailable)
        assertEquals(4, update.updatePriority)
        assertEquals(5, update.stalenessDays)
    }

    @Test
    fun noNoticeWhenUpToDateUnknownOrNotCertain() {
        listOf(
            "up to date" to snapshot(RaftInstallChannelLatest.UpToDate),
            "unknown" to snapshot(RaftInstallChannelLatest.Unknown, fallbackUrl = page),
            "not newer" to snapshot(RaftInstallChannelLatest.Available(build = current, updateUrl = page)),
            "no page" to snapshot(RaftInstallChannelLatest.Available(build = 12_700_000L, updateUrl = "http://insecure.test")),
            "current build unknown" to snapshot(RaftInstallChannelLatest.Available(12_700_000L, page), currentBuild = null),
            "min build gate blocking" to snapshot(RaftInstallChannelLatest.Available(12_700_000L, page), gateBlocking = true),
        ).forEach { (case, update) ->
            assertFalse(update.updateAvailable, case)
            assertNull(update.toSharedHomeAppUpdateNotification(), case)
            // The About row stays (Check for update), it just offers nothing to install.
            assertTrue(update.supported, case)
        }
        assertEquals(RAFT_STORE_UPDATE_UNKNOWN_ERROR_CODE, snapshot(RaftInstallChannelLatest.Unknown).errorCode)
    }

    @Test
    fun skippingAVersionHidesTheNoticeUntilANewerBuild() = runTest {
        val v127 = snapshot(RaftInstallChannelLatest.Available(12_700_000L, page))
        val v128 = snapshot(RaftInstallChannelLatest.Available(12_800_000L, page))

        assertNull(v127.withSkippedAppUpdateVersionApplied(12_700_000L).toSharedHomeAppUpdateNotification())
        assertNull(loadSignedOutAppUpdateNotification(skippedVersionCode = 12_700_000L) { DataResult.Content(v127) })
        assertNotNull(loadSignedOutAppUpdateNotification(skippedVersionCode = 12_700_000L) { DataResult.Content(v128) })
    }

    @Test
    fun versionNameOfABuildNumber() {
        assertEquals("1.26.1", raftVersionNameOfBuildNumber(12_601_003L))
        assertEquals("2.0.0", raftVersionNameOfBuildNumber(20_000_000L))
        assertNull(raftVersionNameOfBuildNumber(1_060_000L))
    }

    private class FakeStore(
        override val name: String = "play",
        override val offersStoreUpdateNotice: Boolean = true,
        var answer: RaftInstallChannelLatest = RaftInstallChannelLatest.Available(12_700_000L, "https://store.test/raft"),
    ) : RaftInstallChannelUpdates {
        var calls = 0

        override suspend fun latest(): RaftInstallChannelLatest {
            calls += 1
            return answer
        }
    }

    @Test
    fun notAStoreInstallIsNeverAsked() = runTest {
        listOf(
            FakeStore(name = "hands", offersStoreUpdateNotice = false), // Hands builds keep the host's own check
            FakeStore(offersStoreUpdateNotice = false), // a Play build Google Play did not install
        ).forEach { channel ->
            val checker = RaftStoreUpdateChecker(channel = { channel }, now = { 0L })
            assertNull(checker.answer(force = true))
            assertEquals(0, channel.calls)
        }
        assertNull(RaftStoreUpdateChecker(channel = { null }, now = { 0L }).answer())
    }
}
