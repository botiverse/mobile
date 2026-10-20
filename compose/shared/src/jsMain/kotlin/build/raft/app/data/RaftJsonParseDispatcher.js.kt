package build.raft.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val RaftJsonParseDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
