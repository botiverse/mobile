package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Message storage Phase 3b: the durable message store's blocking SQLite work for a `sync:resume` replay (up to
 * hundreds of rows) runs on this serialized dispatcher, never on the realtime caller's thread. JVM targets use the
 * IO pool (blocking I/O belongs there); iOS a single worker off the main / frame thread.
 */
@RaftCoreInternalApi
expect val RaftMessageDbDispatcher: CoroutineDispatcher
