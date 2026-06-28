package com.tencent.kuiklybase.markdown.model

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val SlockMarkdownParseDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
