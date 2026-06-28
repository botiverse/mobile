package com.tencent.kuiklybase.markdown.model

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val markdownParseThreadId = AtomicInteger(1)

private val androidMarkdownParseDispatcher: CoroutineDispatcher by lazy {
    Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "slock-markdown-parse-${markdownParseThreadId.getAndIncrement()}").apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
}

internal actual val SlockMarkdownParseDispatcher: CoroutineDispatcher
    get() = androidMarkdownParseDispatcher
