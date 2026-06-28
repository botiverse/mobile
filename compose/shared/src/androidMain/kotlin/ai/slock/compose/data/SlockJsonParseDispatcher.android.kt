package ai.slock.compose.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val jsonParseThreadId = AtomicInteger(1)

private val androidJsonParseDispatcher: CoroutineDispatcher by lazy {
    Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "slock-json-parse-${jsonParseThreadId.getAndIncrement()}").apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
}

internal actual val SlockJsonParseDispatcher: CoroutineDispatcher
    get() = androidJsonParseDispatcher
