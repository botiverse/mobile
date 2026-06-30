package build.raft.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
internal actual val SlockJsonParseDispatcher: CoroutineDispatcher by lazy {
    Dispatchers.Default.limitedParallelism(2)
}
