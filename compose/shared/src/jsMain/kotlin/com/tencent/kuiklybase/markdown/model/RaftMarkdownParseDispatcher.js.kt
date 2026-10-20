package com.tencent.kuiklybase.markdown.model

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val RaftMarkdownParseDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
