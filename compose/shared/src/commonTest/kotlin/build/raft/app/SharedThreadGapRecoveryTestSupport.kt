package build.raft.app

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Records the gap catch-ups a [SharedThreadStore] launches on its scope (off the realtime authority lock),
 * in launch order, so tests can join exactly the catch-up whose result they assert.
 */
internal class GapRecoveryJobs(store: SharedThreadStore) {
    private val jobs = MutableStateFlow<List<Job>>(emptyList())

    init {
        store.onRealtimeGapRecoveryLaunchedForTest = { job -> jobs.update { launched -> launched + job } }
    }

    val count: Int
        get() = jobs.value.size

    operator fun get(index: Int): Job =
        jobs.value.getOrNull(index)
            ?: error("expected gap catch-up #$index to have been launched, launched=${jobs.value.size}")

    suspend fun join(index: Int) {
        get(index).join()
    }

    /** Joins every launched catch-up, including one launched while joining. */
    suspend fun joinAll() {
        var joined = 0
        while (joined < jobs.value.size) {
            jobs.value[joined].join()
            joined += 1
        }
    }
}

internal fun SharedThreadStore.recordGapRecoveryJobs(): GapRecoveryJobs = GapRecoveryJobs(this)
