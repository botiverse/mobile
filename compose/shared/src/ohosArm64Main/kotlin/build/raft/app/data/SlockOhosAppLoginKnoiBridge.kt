package build.raft.app.data

import com.tencent.tmm.knoi.annotation.KNExport

/** HarmonyOS entry for the browser login callback; the retry and completion live in [SlockAppLoginBridge]. */
@KNExport
fun slockOhosAppLoginCompleteCallback(returnUri: String, requestId: String, code: String, error: String): Boolean =
    SlockAppLoginBridge.completeCallback(returnUri, requestId, code, error)
