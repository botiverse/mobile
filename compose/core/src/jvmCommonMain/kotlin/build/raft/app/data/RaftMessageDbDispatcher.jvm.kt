package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

// Blocking SQLite I/O belongs on the IO pool; parallelism 1 keeps resume writes ordered.
@OptIn(ExperimentalCoroutinesApi::class)
@RaftCoreInternalApi
actual val RaftMessageDbDispatcher: CoroutineDispatcher by lazy {
    Dispatchers.IO.limitedParallelism(1)
}
