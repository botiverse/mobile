package build.raft.app.data

import build.raft.app.nav.RaftIosNativeNavFlag
import build.raft.app.raftPlatformSupportsConversationForwardGesture
import build.raft.app.raftPlatformSupportsInAppBrowser
import build.raft.app.raftPlatformSupportsMultiShellHost
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which Settings > Mobile cards this platform shows: a hidden card's setting is absent from the snapshot. */
internal data class RaftAppSettingsPlatform(
    val linkOpenMode: Boolean,
    val conversationForwardGesture: Boolean,
    val iosNativeNav: Boolean,
    val multiShellHost: Boolean,
)

internal fun currentRaftAppSettingsPlatform(): RaftAppSettingsPlatform =
    RaftAppSettingsPlatform(
        linkOpenMode = raftPlatformSupportsInAppBrowser(),
        conversationForwardGesture = raftPlatformSupportsConversationForwardGesture(),
        iosNativeNav = RaftIosNativeNavFlag.hostSupported,
        multiShellHost = raftPlatformSupportsMultiShellHost(),
    )

internal fun MyMessageAlignmentPreference.toRaftAppSetting(): RaftAppSettingMessageAlignment =
    when (this) {
        MyMessageAlignmentPreference.Left -> RaftAppSettingMessageAlignment.Left
        MyMessageAlignmentPreference.Right -> RaftAppSettingMessageAlignment.Right
    }

internal fun LinkOpenMode.toRaftAppSetting(): RaftAppSettingLinkOpenMode =
    when (this) {
        LinkOpenMode.SystemBrowser -> RaftAppSettingLinkOpenMode.SystemBrowser
        LinkOpenMode.InAppBrowser -> RaftAppSettingLinkOpenMode.InAppBrowser
    }

/** `app_settings_snapshot` from the stored preferences (the same keys the Settings > Mobile cards write). */
internal fun raftAppSettingsSnapshotProperties(
    store: SharedPreferenceStore,
    platform: RaftAppSettingsPlatform = currentRaftAppSettingsPlatform(),
): RaftAppSettingsSnapshotProperties {
    val chat = KeyValueChatLayoutPreferenceStore(store)
    return RaftAppSettingsSnapshotProperties(
        messageAlignment = chat.readMyMessageAlignmentSync().toRaftAppSetting(),
        linkOpenMode =
            if (platform.linkOpenMode) KeyValueLinkOpenPreferenceStore(store).readLinkOpenModeSync().toRaftAppSetting() else null,
        compactMessages = chat.readCompactConsecutiveMessagesSync(),
        longMessageCollapse = chat.readLongMessageCollapseEnabledSync(),
        newMessageMenu = chat.readNewMessageMenuEnabledSync(),
        conversationForwardGesture =
            if (platform.conversationForwardGesture) chat.readConversationForwardGestureEnabledSync() else null,
        iosNativeNav = if (platform.iosNativeNav) RaftIosNativeNavFlag.stored(store, supported = true) else null,
        iosNativeNavChosen = if (platform.iosNativeNav) RaftIosNativeNavFlag.localOverrideSetting(store) != null else null,
        activityTaskStatusBadges = chat.readActivityTaskStatusBadgesEnabledSync(),
        composerEnterSends = chat.readComposerSendKeyEnabledSync(),
        multiShellHost = if (platform.multiShellHost) chat.readMultiShellHostEnabledSync() else null,
        desktopModeLargeScreen = chat.readDesktopModeOnLargeScreenSync(),
        serverDrawer = chat.readServerDrawerEnabledSync(),
        homeServerAvatar = chat.readHomeServerAvatarEnabledSync(),
    )
}

/**
 * `app_settings_snapshot`, at most once per install per UTC day (Nova, 2026-11-14): tried after
 * sign-in and on each return to the app, sent alone with [RaftProductEvents.sendAppSettingsSnapshot].
 * The day is marked only when the server took it (or the account does not share usage data), so an
 * offline start tries again later the same day. Per install, not per account: another account the
 * same day sends nothing new; the row goes to the server signed in when it is sent.
 */
object RaftAppSettingsSnapshotReport {
    private const val SentDayKey = "productEvents:appSettingsSnapshot:utcDay"
    private const val DayMillis = 86_400_000L
    private const val RetryBaseMillis = 30_000L
    private const val MaxAttemptsPerRun = 3
    /** After a run that ended pending (offline, analytics off), the next one waits this long. */
    private const val RunGapMillis = 30 * 60_000L

    private val lock = SynchronizedObject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var signedIn = false
    private var sendJob: Job? = null
    private var lastRunStartedAtMillis: Long? = null

    internal fun onSignedIn() {
        synchronized(lock) { signedIn = true }
        maybeSend()
    }

    internal fun onSignedOut() {
        synchronized(lock) { signedIn = false }
    }

    internal fun onAppForeground() {
        maybeSend()
    }

    private fun preferenceStore(): SharedPreferenceStore? = runCatching { raftSharedPreferenceStore() }.getOrNull()

    private fun now(): Long = ProcessRaftDatetimeFoundationOwner.nowEpochMillis()

    private fun utcDay(millis: Long): String = (millis / DayMillis).toString()

    private fun maybeSend() {
        val now = now()
        synchronized(lock) {
            if (!signedIn || sendJob?.isActive == true) return
            val last = lastRunStartedAtMillis
            if (last != null && now - last < RunGapMillis) return
            val store = preferenceStore() ?: return
            if (store.getString(SentDayKey) == utcDay(now)) return
            lastRunStartedAtMillis = now
            sendJob = scope.launch { attempt(store) }
        }
    }

    private suspend fun attempt(store: SharedPreferenceStore) {
        repeat(MaxAttemptsPerRun) { index ->
            if (index > 0) delay(RetryBaseMillis shl (index - 1))
            val day = utcDay(now())
            if (synchronized(lock) { !signedIn } || store.getString(SentDayKey) == day) return
            val result =
                runCatching {
                    val properties = raftAppSettingsSnapshotProperties(store)
                    RaftProductEvents.sendAppSettingsSnapshot(properties) { synchronized(lock) { signedIn } }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    RaftProductEventOnceResult.Pending
                }
            when (result) {
                RaftProductEventOnceResult.Delivered,
                RaftProductEventOnceResult.NotShared -> {
                    store.putString(SentDayKey, day)
                    synchronized(lock) { lastRunStartedAtMillis = null }
                    build.raft.app.raftLogInfo("RaftAppSettingsSnapshot", "app_settings_snapshot_reported result=$result")
                    return
                }
                RaftProductEventOnceResult.Pending ->
                    build.raft.app.raftLogInfo("RaftAppSettingsSnapshot", "app_settings_snapshot_pending attempt=${index + 1}")
            }
        }
    }
}
