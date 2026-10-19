package build.raft.app.data

import build.raft.app.SlockCoreInternalApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

// Blocking SQLite I/O belongs on the IO pool; parallelism 1 keeps avatar metadata writes ordered.
@OptIn(ExperimentalCoroutinesApi::class)
@SlockCoreInternalApi
actual val SlockAvatarDbDispatcher: CoroutineDispatcher by lazy {
    Dispatchers.IO.limitedParallelism(1)
}
