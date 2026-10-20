package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@RaftCoreInternalApi
actual val RaftJsonParseDispatcher: CoroutineDispatcher by lazy {
    Dispatchers.Default.limitedParallelism(RAFT_JSON_PARSE_PARALLELISM)
}
