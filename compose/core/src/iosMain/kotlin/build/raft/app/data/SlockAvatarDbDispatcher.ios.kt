package build.raft.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

// Same pattern as SlockJsonParseDispatcher: one serialized worker off the UI/frame thread.
@OptIn(ExperimentalCoroutinesApi::class)
actual val SlockAvatarDbDispatcher: CoroutineDispatcher by lazy {
    Dispatchers.Default.limitedParallelism(1)
}
