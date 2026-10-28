package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

// Same pattern as RaftAvatarDbDispatcher: one serialized worker off the UI/frame thread.
@OptIn(ExperimentalCoroutinesApi::class)
@RaftCoreInternalApi
actual val RaftMessageDbDispatcher: CoroutineDispatcher by lazy {
    Dispatchers.Default.limitedParallelism(1)
}
