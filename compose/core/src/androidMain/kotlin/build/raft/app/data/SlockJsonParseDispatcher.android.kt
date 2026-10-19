package build.raft.app.data

import build.raft.app.SlockCoreInternalApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@SlockCoreInternalApi
actual val SlockJsonParseDispatcher: CoroutineDispatcher by lazy {
    Dispatchers.Default.limitedParallelism(SLOCK_JSON_PARSE_PARALLELISM)
}
