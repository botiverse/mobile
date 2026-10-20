package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps a cold-start completion until the signed-out page is mounted. No credentials enter UI state. */
@RaftCoreInternalApi
object RaftWebLoginCompletion {
    private val mutableStatus = MutableStateFlow<String?>(null)
    val status = mutableStatus.asStateFlow()
    fun publish(status: String) { mutableStatus.value = status }
    fun clear() { mutableStatus.value = null }
}
