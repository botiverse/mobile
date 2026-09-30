package build.raft.app.data

import com.tencent.tmm.knoi.annotation.KNExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val appLoginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

@KNExport
fun slockOhosAppLoginCompleteCallback(returnUri: String, code: String, error: String): Boolean {
    if (returnUri !in setOf("raft://login/callback", "raft-debug://login/callback") ||
        code.isBlank() == error.isBlank()) return false
    appLoginScope.launch {
        val result = runCatchingPreservingCancellation { SlockAppLogin.complete(returnUri, code, error) }
            .getOrDefault("login_error")
        if (result != "ignored") SlockWebLoginCompletion.publish(result)
    }
    return true
}
