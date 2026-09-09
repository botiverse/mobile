package build.raft.app

import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal fun activityLifecycleShouldRefresh(
    data: JSONObject,
    shellInstanceId: String,
    pageVisible: Boolean
): Boolean =
    data.optString(SlockRealtimeNotifyContract.KeyAction) == SlockRealtimeNotifyContract.ActionAppLifecycle &&
        data.optString(SlockRealtimeNotifyContract.KeyPageName) == HOME_ACTIVITY_PAGE &&
        appLifecycleEventTargetsShell(
            data.optString(SlockRealtimeNotifyContract.KeyShellInstanceId),
            shellInstanceId
        ) &&
        appShellLifecycleCanRefreshVisibleRoute(
            pageVisible,
            data.optString(SlockRealtimeNotifyContract.KeyLifecycleEvent)
        )
