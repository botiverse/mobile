package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessActivityUnreadProductionWiringTest {
    @Test
    fun homeAndActivityConsumeProcessProjectionInsteadOfActivityListSummary() {
        val home = commonMainSource("build/raft/app/SettingsHomePage.kt")
        val activity = commonMainSource("build/raft/app/SharedInboxPanel.kt")
        val shell = commonMainSource("build/raft/app/data/SharedHomeShellStore.kt")

        assertTrue("activityUnreadCount = activityUnreadSummary.totalUnreadCount" in home)
        assertTrue("activityTotalCount = activityUnreadSummary.totalCount" in home)
        assertTrue("activityUnreadSummaryStore.projectionFlow" in activity)
        assertTrue("state = projectedState" in activity)
        assertTrue("projectActivityStateWithProcessSummary(state, activitySummary)" in activity)
        assertTrue("state.selectedFilter != InboxFilter.All" in activity)

        val foregroundStart = shell.indexOf("private fun refreshVisibleHomeDestination()")
        val foregroundEnd = shell.indexOf("private fun applyRealtimeEvent", foregroundStart)
        assertTrue(foregroundStart >= 0 && foregroundEnd > foregroundStart)
        val foreground = shell.substring(foregroundStart, foregroundEnd)
        assertFalse(
            "refreshActivitySummary(" in foreground,
            "Foreground Home refresh must not use the page-scoped Activity list as the unread owner."
        )
    }

    @Test
    fun everyHostPublishesOneMonotonicForegroundEpochAndSurfacesSubmitIt() {
        val contract = commonMainSource("build/raft/app/data/realtime/SlockRealtimeAppScopePolicy.kt")
        val home = commonMainSource("build/raft/app/SettingsHomePage.kt")
        val thread = commonMainSource("build/raft/app/ThreadPage.kt")
        val android = composeSource("androidApp/src/main/java/build/raft/app/shared/AndroidAppScopeRealtimeOwner.kt")
        val ios = composeSource("iosApp/iosApp/SlockIosRealtimeHost.swift")
        val ohos = composeSource("ohosApp/entry/src/main/ets/kuikly/SlockOhosRealtimeHostContext.ets")

        assertTrue("const val KeyForegroundEpoch = \"foregroundEpoch\"" in contract)
        assertTrue("activityUnreadSummaryStore.onForeground" in home)
        assertTrue("optLong(SlockRealtimeNotifyContract.KeyForegroundEpoch, 0L)" in home)
        assertTrue("activityUnreadSummaryStore.onForeground" in thread)
        assertTrue("optLong(SlockRealtimeNotifyContract.KeyForegroundEpoch, 0L)" in thread)

        assertTrue("foregroundEpoch = nextAndroidForegroundEpoch" in android)
        assertTrue("put(SlockRealtimeNotifyContract.KeyForegroundEpoch, lifecycleForegroundEpoch)" in android)
        assertTrue("self.foregroundEpoch += 1" in ios)
        assertTrue("\"foregroundEpoch\": foregroundEpoch" in ios)
        assertTrue("nextSlockOhosForegroundEpoch(this.foregroundEpoch, this.appVisible, true)" in ohos)
        assertTrue("this.appVisible = false;" in ohos)
        assertTrue("foregroundEpoch: this.foregroundEpoch" in ohos)
    }

    @Test
    fun providerOwnsRepositoryAuthPartitionFactsAndRealtimeInvalidation() {
        val provider = commonMainSource("build/raft/app/data/SlockSharedStoreProvider.kt")
        val activity = commonMainSource("build/raft/app/data/SharedActivityStore.kt")

        assertTrue("val activityUnreadSummaryStore: ProcessActivityUnreadStore" in provider)
        assertTrue("ownerResolver = resolveActivityUnreadOwner" in provider)
        assertTrue("ownerFlowResolver = resolveActivityUnreadOwnerFlow" in provider)
        assertTrue("owner.installActivityUnreadSink(activityUnreadRealtimeSink)" in provider)
        assertTrue("onSummaryFact = activityUnreadSummaryStore::submitFact" in provider)
        assertTrue("onSummaryInvalidated = activityUnreadSummaryStore::submitInvalidation" in provider)
        assertTrue("ActivityUnreadSummaryFact(" in activity)
        assertTrue("next.selectedFilter != InboxFilter.All" in activity)
        assertTrue("summaryAuthoritative" in activity)

        val processStore = commonMainSource("build/raft/app/data/ProcessActivityUnreadStore.kt")
        listOf(
            "MessageUpdated",
            "DirectMessageCreated",
            "TaskCreated",
            "TaskUpdated",
            "TaskDeleted",
            "RealtimeRefreshRequired"
        ).forEach { eventType ->
            assertTrue(eventType in processStore, "Process Activity predicate is missing $eventType")
        }
    }

    @Test
    fun processRestoreAndExternalWidgetsSharePersistedAllFilterSummarySemantics() {
        val provider = commonMainSource("build/raft/app/data/SlockSharedStoreProvider.kt")
        val processStore = commonMainSource("build/raft/app/data/ProcessActivityUnreadStore.kt")
        val androidWidget = composeSource("androidApp/src/main/java/build/raft/app/widget/SlockActivityWidgetProvider.kt")
        val ohosWidget = composeSource("ohosApp/entry/src/main/ets/forms/SlockWidgetData.ets")

        assertTrue("ProcessActivityUnreadProjectionCache(" in provider)
        assertTrue("projectionCache = processActivityUnreadProjectionCache" in provider)
        assertTrue("freshness = ActivityUnreadSummaryFreshness.Stale" in processStore)
        assertTrue("projectionCache?.clearAll()" in processStore)

        assertTrue("ProcessActivityUnreadProjectionCache(androidMMKVActivityUnreadKeyValueStore(context))" in androidWidget)
        assertTrue("RealInboxRepository(sessionStore, networkClient).loadInitial(serverId, InboxFilter.All, limit = 1)" in androidWidget)
        assertTrue("activityUnreadCount = inboxUnread" in androidWidget)

        assertTrue("slockActivityUnread:projections" in ohosWidget)
        assertTrue("channels/inbox?filter=all&limit=1&offset=0" in ohosWidget)
        assertTrue("export function activityUnreadCountOrNull" in ohosWidget)
        assertTrue("const liveUnread = activityUnreadCountOrNull(summary.totalUnreadCount)" in ohosWidget)
        assertTrue("throw new Error('activity_unread_widget_invalid_summary')" in ohosWidget)
        assertTrue("activityUnreadPersistedFallbackCount(persisted)" in ohosWidget)
        assertTrue("throw createActivityUnreadWidgetRefreshError(error)" in ohosWidget)
        assertTrue("return new Error('activity_unread_widget_refresh_failed')" in ohosWidget)
        assertFalse("summary.totalUnreadCount ?? 0" in ohosWidget)
        assertFalse("persisted.totalUnreadCount ?? 0" in ohosWidget)
        assertFalse("throw error;" in ohosWidget)
        assertFalse("servers/unread-summary" in ohosWidget)
    }

    private fun commonMainSource(path: String): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("commonMain source directory not found")
        return File(commonMain, path).readText().replace("\r\n", "\n")
    }

    private fun composeSource(path: String): String {
        val composeRoot =
            listOf(File("."), File(".."))
                .firstOrNull { root -> File(root, "androidApp").isDirectory }
                ?: error("compose source root not found")
        return File(composeRoot, path).readText().replace("\r\n", "\n")
    }
}
