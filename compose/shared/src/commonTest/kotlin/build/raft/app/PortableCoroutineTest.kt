package build.raft.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

internal fun <T> runPortableCoroutineTest(
    block: suspend CoroutineScope.() -> T
): T = runBlocking(block = block)
