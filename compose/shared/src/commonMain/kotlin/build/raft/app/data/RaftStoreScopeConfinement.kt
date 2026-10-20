package build.raft.app.data

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * Runs [block] on the dispatcher of this store scope and suspends the caller until it finishes.
 *
 * Provider-owned stores run on [RaftStoreThreading.newScope] (the serial store dispatcher), so a
 * public suspend entry point that writes store state must not run those writes on whatever thread
 * its caller happens to be on: under CMP that is the main thread (composition, `LaunchedEffect`),
 * under Kuikly it was Kuikly's context thread. Unit tests construct stores on their own scope
 * (`Dispatchers.Unconfined`, test dispatchers), which this keeps.
 */
internal suspend fun <T> CoroutineScope.raftWithStoreScope(block: suspend CoroutineScope.() -> T): T =
    withContext(this.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext, block)
