package build.raft.app.data

import build.raft.app.SlockCoreInternalApi
import kotlinx.coroutines.CancellationException

@SlockCoreInternalApi
suspend inline fun <T> runCatchingPreservingCancellation(
    crossinline block: suspend () -> T
): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

