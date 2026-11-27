package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import build.raft.app.appLifecycleEventTargetsShell
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.isRaftHostVisible
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.settings.observe
import build.raft.app.optLong
import build.raft.app.optString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The page's lifecycle and visible-resume effects: the resume plan (pointer session, visible refresh,
 * room rejoin), the resume drained when the route becomes visible, the freshness catch-up, the Agent
 * Lounge `channel:joined` catch-up, and the pager's app-lifecycle listener. Takes the page's states,
 * not their values, where the effects read or write them live.
 */
@Composable
@NonRestartableComposable
internal fun ThreadRouteLifecycleEffects(
    bridge: BridgeModule,
    threadViewModel: SharedThreadViewModel,
    sharedStores: RaftSharedStoreProvider,
    realtimeEvents: RaftRealtimeEvents,
    coroutineScope: CoroutineScope,
    storeScope: RaftShellStoreScope,
    threadInstanceKey: String,
    currentSession: StoredAuthSession?,
    payload: RaftThreadScreenPayload,
    active: Boolean,
    mutePageLounge: RaftAgentLounge?,
    threadVisibleResumeGate: ThreadVisibleResumeGate,
    threadPreviewOverlayLifecycleFilter: ThreadPreviewOverlayLifecycleFilter,
    threadHostForegroundState: MutableState<Boolean>,
    threadPointerSessionKeyState: MutableState<Int>,
    latestCurrentSessionState: State<StoredAuthSession?>,
    latestRouteActiveState: State<Boolean>,
    latestForegroundRejoinRoomIdsState: State<List<String>>,
) {
    var threadHostForeground by threadHostForegroundState
    var threadPointerSessionKey by threadPointerSessionKeyState
    val latestCurrentSession by latestCurrentSessionState
    val latestRouteActive by latestRouteActiveState
    val latestForegroundRejoinRoomIds by latestForegroundRejoinRoomIdsState

    fun applyThreadVisibleResumePlan(plan: ThreadVisibleResumePlan) {
        threadPointerSessionKey = nextThreadPointerSessionKey(threadPointerSessionKey)
        if (plan.refreshVisibleConversation) {
            threadViewModel.dispatch(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
        }
        if (plan.rejoinVisibleRooms) {
            realtimeEvents.rejoinChannels(
                latestForegroundRejoinRoomIds,
                latestCurrentSession?.currentServerId
            )
        }
    }

    LaunchedEffect(active, threadVisibleResumeGate, threadVisibleResumeGate.pendingVisibleResume) {
        if (active) {
            threadVisibleResumeGate.onRouteBecameVisible(latestCurrentSession?.currentServerId)
                ?.let {
                    bridge.log(
                        "thread_lifecycle_resume_drained refresh=${it.refreshVisibleConversation} " +
                            "rejoin=${it.rejoinVisibleRooms} shell=${storeScope.shellInstanceId}"
                    )
                    applyThreadVisibleResumePlan(it)
                }
        }
    }
    ThreadConversationFreshnessCatchUpEffect(
        threadViewModel = threadViewModel,
        serverId = currentSession?.currentServerId,
        active = active,
        contentReady = payload.status == "content" && !payload.mainLoadFailed,
        resumeGate = threadVisibleResumeGate,
        onCatchUp = { trigger ->
            val serverId = latestCurrentSession?.currentServerId
            bridge.log(
                "thread_freshness_catch_up trigger=${trigger.wireName} shell=${storeScope.shellInstanceId}"
            )
            if (trigger == build.raft.app.data.RaftFreshnessTrigger.Reconnect) {
                realtimeEvents.rejoinChannels(latestForegroundRejoinRoomIds, serverId)
            }
        },
    )
    // `slock #16493` (`agentLoungeRoomCatchUp.test.ts`): an open Agent Lounge joins its socket room
    // on demand; when the server confirms `channel:joined`, catch up from the visible tail so messages
    // sent while the socket was joining the room are not missed.
    val latestMutePageLoungeChannelId by rememberUpdatedState(mutePageLounge?.channelId?.value)
    val latestLoungeContentReady by rememberUpdatedState(payload.status == "content" && !payload.mainLoadFailed)
    LaunchedEffect(sharedStores.channelJoinedSignals, threadInstanceKey) {
        sharedStores.channelJoinedSignals.events.collect { signal ->
            if (
                latestRouteActive &&
                latestLoungeContentReady &&
                build.raft.app.data.shouldCatchUpOpenLoungeOnChannelJoined(
                    openServerId = latestCurrentSession?.currentServerId,
                    openChannelId = latestMutePageLoungeChannelId,
                    isOpenChannelLounge = latestMutePageLoungeChannelId != null,
                    ackServerId = signal.serverId,
                    ackChannelId = signal.channelId,
                )
            ) {
                bridge.log(
                    "thread_lounge_channel_joined_catch_up channelId=${signal.channelId.take(8)} shell=${storeScope.shellInstanceId}"
                )
                threadViewModel.dispatch(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
            }
        }
    }

    // Pager lifecycle notifications remain mounted with the route core. A
    // loading -> resolved transition must not reset foreground reconciliation.
    DisposableEffect(realtimeEvents) {
        val callbackRef =
            realtimeEvents.addListener { eventData ->
                val eventDataSnapshot = eventData.toString()
                coroutineScope.launch {
                    val lifecycleData = JSONObject(eventDataSnapshot)
                    if (lifecycleData.optString(RaftRealtimeNotifyContract.KeyAction) != RaftRealtimeNotifyContract.ActionAppLifecycle) {
                        return@launch
                    }
                    val event = lifecycleData.optString(RaftRealtimeNotifyContract.KeyLifecycleEvent)
                    val pageName = lifecycleData.optString(RaftRealtimeNotifyContract.KeyPageName)
                    bridge.log(
                        "thread_lifecycle_received event=$event page=$pageName " +
                            "routeActive=$latestRouteActive shell=${storeScope.shellInstanceId}"
                    )
                    val shellTargeted =
                        appLifecycleEventTargetsShell(
                            eventShellInstanceId = lifecycleData.optString(RaftRealtimeNotifyContract.KeyShellInstanceId),
                            routeShellInstanceId = storeScope.shellInstanceId
                        )
                    // The file preview is its own page: opening and closing it pauses and resumes the
                    // thread host. Track that before the page filter so the return is not a resume.
                    val previewOverlayVerdict =
                        if (shellTargeted) {
                            threadPreviewOverlayLifecycleFilter.observe(
                                event = event,
                                pageName = pageName,
                                foregroundEpoch = lifecycleData.optLong(RaftRealtimeNotifyContract.KeyForegroundEpoch, 0L)
                            )
                        } else {
                            ThreadLifecycleVisibilityVerdict.Counts
                        }
                    if (!threadLifecycleEventTargetsThread(pageName)) {
                        bridge.log("thread_lifecycle_drop reason=page_target")
                        return@launch
                    }
                    if (!shellTargeted) {
                        bridge.log("thread_lifecycle_drop reason=shell_target")
                        return@launch
                    }
                    threadHostForeground = isRaftHostVisible(storeScope.shellInstanceId)
                    bridge.log("thread_lifecycle_visibility hostForeground=$threadHostForeground routeActive=$latestRouteActive")
                    if (
                        previewOverlayVerdict == ThreadLifecycleVisibilityVerdict.CoveredByPreview
                    ) {
                        bridge.log("thread_lifecycle_resume_skipped reason=preview_overlay event=$event")
                    } else if (
                        event == RaftRealtimeNotifyContract.LifecycleVisibleResume ||
                        event == RaftRealtimeNotifyContract.LifecycleVisibleShow
                    ) {
                        // Foreground is a drainer trigger: re-evaluate journaled sends whose
                        // network may have returned while the app was backgrounded.
                        threadViewModel.triggerPendingSendDrain("foreground_$event")
                        val resumePlan =
                            threadVisibleResumeGate.onLifecycleVisibleResume(
                                routeActive = latestRouteActive,
                                serverId = latestCurrentSession?.currentServerId,
                            )
                        if (resumePlan == null) {
                            bridge.log(
                                "thread_lifecycle_resume_latched_or_skipped event=$event " +
                                    "routeActive=$latestRouteActive pending=${threadVisibleResumeGate.pendingVisibleResume} " +
                                    "shell=${storeScope.shellInstanceId}"
                            )
                        } else {
                            bridge.log(
                                "thread_lifecycle_refresh_dispatch refresh=${resumePlan.refreshVisibleConversation} " +
                                    "rejoin=${resumePlan.rejoinVisibleRooms}"
                            )
                            applyThreadVisibleResumePlan(resumePlan)
                        }
                    }
                }
            }
        onDispose { realtimeEvents.removeListener(callbackRef) }
    }
}
