package com.tencent.kuiklybase.markdown.model

import kotlinx.coroutines.CoroutineDispatcher

internal const val RAFT_MARKDOWN_PARSE_DISPATCHER_LABEL = "MarkdownParse"
internal const val RAFT_MARKDOWN_PARSE_PARALLELISM = 1

internal expect val RaftMarkdownParseDispatcher: CoroutineDispatcher
