package build.raft.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val jsonParseThreadId = AtomicInteger(1)

private val androidJsonParseDispatcher: CoroutineDispatcher by lazy {
    ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue()
    ) { runnable ->
        Thread(runnable, "slock-json-parse-${jsonParseThreadId.getAndIncrement()}").apply {
            isDaemon = true
        }
    }.apply {
        prestartAllCoreThreads()
    }.asCoroutineDispatcher()
}

internal actual val SlockJsonParseDispatcher: CoroutineDispatcher
    get() = androidJsonParseDispatcher

fun warmUpSlockJsonParseDispatcher() {
    androidJsonParseDispatcher
}
