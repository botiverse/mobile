package build.raft.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
actual val SlockJsonParseDispatcher: CoroutineDispatcher by lazy {
    Dispatchers.Default.limitedParallelism(SLOCK_JSON_PARSE_PARALLELISM)
}
