package com.tencent.kuiklybase.markdown.model

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
internal actual val RaftMarkdownParseDispatcher: CoroutineDispatcher by lazy {
    Dispatchers.Default.limitedParallelism(RAFT_MARKDOWN_PARSE_PARALLELISM)
}
