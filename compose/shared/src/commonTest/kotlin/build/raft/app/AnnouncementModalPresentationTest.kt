package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAnnouncementsRemindersApi
import build.raft.app.data.RaftAnnouncementsRemindersApiClient
import build.raft.app.data.RaftFreshnessTrigger
import build.raft.app.data.SharedAnnouncementStore
import build.raft.app.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AnnouncementModalPresentationTest {
    @Test
    fun announcementModalShowsOnlyWhenVisibleActiveAndNotCovered() {
        assertTrue(
            shouldShowAppShellAnnouncementModal(
                hasActiveAnnouncement = true,
                pageVisible = true,
                pageActive = true,
                suppressedByPageCover = false,
            )
        )
        assertFalse(
            shouldShowAppShellAnnouncementModal(
                hasActiveAnnouncement = false,
                pageVisible = true,
                pageActive = true,
                suppressedByPageCover = false,
            )
        )
        assertFalse(
            shouldShowAppShellAnnouncementModal(
                hasActiveAnnouncement = true,
                pageVisible = false,
                pageActive = true,
                suppressedByPageCover = false,
            )
        )
        assertFalse(
            shouldShowAppShellAnnouncementModal(
                hasActiveAnnouncement = true,
                pageVisible = true,
                pageActive = false,
                suppressedByPageCover = false,
            )
        )
        assertFalse(
            shouldShowAppShellAnnouncementModal(
                hasActiveAnnouncement = true,
                pageVisible = true,
                pageActive = true,
                suppressedByPageCover = true,
            )
        )
    }

    @Test
    fun coverStateSuppressesUntilReturningFromHiddenOrHostAppearTick() {
        val initial = AppShellAnnouncementCoverState()
        assertFalse(initial.suppressed)

        // Cover fires synchronously before the pushed page covers the shell.
        val covered = initial.onPageCover(pageVisibleTick = 2, hostDidAppearTick = 5)
        assertTrue(covered.suppressed)
        // A second cover signal (e.g. Kotlin openPage + iOS viewWillDisappear) is idempotent.
        assertEquals(covered, covered.onPageCover(pageVisibleTick = 2, hostDidAppearTick = 5))

        // While the shell has not disappeared yet, recomposition must NOT clear suppression.
        val stillCovering =
            covered.onHostState(
                pageVisible = true,
                pageActive = true,
                pageVisibleTick = 2,
                hostDidAppearTick = 5,
            )
        assertTrue(stillCovering.suppressed)

        // Shell becomes covered (`pageVisible = false`), then returns (`pageVisible = true`).
        val hidden =
            stillCovering.onHostState(
                pageVisible = false,
                pageActive = true,
                pageVisibleTick = 2,
                hostDidAppearTick = 5,
            )
        assertTrue(hidden.suppressed)
        assertTrue(hidden.sawInactiveSinceCover)
        val returnedAfterHidden =
            hidden.onHostState(
                pageVisible = true,
                pageActive = true,
                pageVisibleTick = 2,
                hostDidAppearTick = 5,
            )
        assertFalse(returnedAfterHidden.suppressed)

        // iOS native pop path: `hostDidAppearTick` advances even if Compose `pageVisible` never flipped.
        val returnedViaHostAppear =
            covered.onHostState(
                pageVisible = true,
                pageActive = true,
                pageVisibleTick = 2,
                hostDidAppearTick = 6,
            )
        assertFalse(returnedViaHostAppear.suppressed)

        // Advancing a tick while still inactive/covered must NOT unsuppress.
        val tickWhileInactive =
            covered.onHostState(
                pageVisible = true,
                pageActive = false,
                pageVisibleTick = 3,
                hostDidAppearTick = 6,
            )
        assertTrue(tickWhileInactive.suppressed)
    }

    @Test
    fun pageCoverKeepsPendingAnnouncementWhileExplicitDismissPersistsToServer() = runTest {
        val api = FakeAnnouncementApi()
        val store = SharedAnnouncementStore(RaftAnnouncementsRemindersApiClient(api)) { true }

        store.refresh()
        val active = assertNotNull(store.announcement.value)
        assertEquals("ann-1", active.id)

        // Covering the shell hides presentation without calling store.dismiss() or POST dismiss.
        val coverState = AppShellAnnouncementCoverState().onPageCover(pageVisibleTick = 0, hostDidAppearTick = 0)
        assertFalse(
            shouldShowAppShellAnnouncementModal(
                hasActiveAnnouncement = store.announcement.value != null,
                pageVisible = true,
                pageActive = true,
                suppressedByPageCover = coverState.suppressed,
            )
        )
        assertEquals(listOf("GET announcements/active"), api.calls)
        assertEquals("ann-1", store.announcement.value?.id)

        // Returning to Home restores visibility of the still-pending announcement.
        val restored =
            coverState
                .onHostState(pageVisible = false, pageActive = false, pageVisibleTick = 0, hostDidAppearTick = 0)
                .onHostState(pageVisible = true, pageActive = true, pageVisibleTick = 1, hostDidAppearTick = 1)
        assertTrue(
            shouldShowAppShellAnnouncementModal(
                hasActiveAnnouncement = store.announcement.value != null,
                pageVisible = true,
                pageActive = true,
                suppressedByPageCover = restored.suppressed,
            )
        )

        // Explicit user dismiss clears synchronously and persists server-side.
        assertTrue(store.dismiss("ann-1"))
        assertNull(store.announcement.value)
        store.persistDismissal("ann-1")
        assertEquals(
            listOf("GET announcements/active", "POST announcements/ann-1/dismiss"),
            api.calls,
        )

        // A later refresh still returning the dismissed id does not reopen it.
        store.refresh()
        assertNull(store.announcement.value)
    }

    @Test
    fun announcementImageRetriesOnRecoveryWithoutLoopingOfflineOrRestartingActiveLoad() {
        val url1 = "https://api.raft.test/announcements/images/asset-1"
        var state = AnnouncementImageLoadState(source = url1)
        assertFalse(state.failed)
        assertEquals(0, state.retryAttempt)

        // Freshness events while the initial load is in flight must not restart it.
        state = state.onRecoverySignal(isOnline = true)
        assertEquals(0, state.retryAttempt)

        // Transient failure marks failed without retrying immediately.
        state = state.onImageState(isError = true)
        assertTrue(state.failed)
        assertEquals(0, state.retryAttempt)

        // While offline, foreground/recovery signals must NOT trigger a retry loop.
        assertFalse(announcementImageOnlineForFreshnessEvent(RaftFreshnessTrigger.Foreground, isOnline = false))
        state = state.onRecoverySignal(isOnline = announcementImageOnlineForFreshnessEvent(RaftFreshnessTrigger.Foreground, isOnline = false))
        assertTrue(state.failed)
        assertEquals(0, state.retryAttempt)

        // NetworkRestored / Reconnect prove connectivity returned and trigger one retry.
        assertTrue(announcementImageOnlineForFreshnessEvent(RaftFreshnessTrigger.NetworkRestored, isOnline = false))
        assertTrue(announcementImageOnlineForFreshnessEvent(RaftFreshnessTrigger.Reconnect, isOnline = false))
        state = state.onRecoverySignal(isOnline = announcementImageOnlineForFreshnessEvent(RaftFreshnessTrigger.NetworkRestored, isOnline = false))
        assertFalse(state.failed)
        assertEquals(1, state.retryAttempt)

        // A second signal arriving while retry #1 is already in flight must not interrupt it.
        state = state.onRecoverySignal(isOnline = announcementImageOnlineForFreshnessEvent(RaftFreshnessTrigger.Reconnect, isOnline = true))
        assertFalse(state.failed)
        assertEquals(1, state.retryAttempt)

        // After a second failure, foreground return while online retries once more.
        state = state.onImageState(isError = true)
        assertTrue(state.failed)
        assertTrue(announcementImageOnlineForFreshnessEvent(RaftFreshnessTrigger.Foreground, isOnline = true))
        state = state.onRecoverySignal(isOnline = announcementImageOnlineForFreshnessEvent(RaftFreshnessTrigger.Foreground, isOnline = true))
        assertFalse(state.failed)
        assertEquals(2, state.retryAttempt)

        // Once succeeded, later recovery signals do not reload.
        state = state.onImageState(isError = false, isSuccess = true)
        assertFalse(state.failed)
        state = state.onRecoverySignal(isOnline = true)
        assertEquals(2, state.retryAttempt)

        // A new image URL resets state cleanly.
        val reset = AnnouncementImageLoadState(source = "https://api.raft.test/announcements/images/asset-2")
        assertFalse(reset.failed)
        assertEquals(0, reset.retryAttempt)
    }

    private class FakeAnnouncementApi : RaftAnnouncementsRemindersApi {
        val calls = mutableListOf<String>()

        override suspend fun activeAnnouncements(): HomeNetworkResponse {
            calls += "GET announcements/active"
            return HomeNetworkResponse(
                code = 200,
                bodyText =
                    """{"announcements":[{"id":"ann-1","title":"Release 1.29","pages":[{"body":"New feature"}],"publishedAt":"2026-11-23T00:00:00.000Z"}]}"""
            )
        }

        override suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse {
            calls += "POST announcements/$announcementId/dismiss"
            return HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
        }

        override suspend fun reminders(ownerAgentId: MemberId, status: String): HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"reminders":[]}""")
    }
}
