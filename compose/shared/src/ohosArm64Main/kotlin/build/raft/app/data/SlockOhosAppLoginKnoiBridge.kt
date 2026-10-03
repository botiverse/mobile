package build.raft.app.data

import com.tencent.tmm.knoi.annotation.KNExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val appLoginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

@KNExport
fun slockOhosAppLoginCompleteCallback(returnUri: String, requestId: String, code: String, error: String): Boolean {
    if (returnUri !in setOf("raft://login/callback", "raft-debug://login/callback") ||
        requestId.isBlank() || code.isBlank() == error.isBlank()) return false
    appLoginScope.launch {
        // Page-content readiness can precede installation of the shared store on cold startup.
        // Retain this callback only in memory while that short initialization completes.
        repeat(50) {
            val result = runCatchingPreservingCancellation { SlockAppLogin.complete(returnUri, requestId, code, error) }
                .getOrDefault("login_error")
            if (result != "retry") {
                if (result != "ignored") SlockWebLoginCompletion.publish(result)
                return@launch
            }
            delay(100)
        }
        SlockWebLoginCompletion.publish("login_error")
    }
    return true
}
