package build.raft.app.data

import kotlinx.coroutines.CoroutineDispatcher

internal const val SLOCK_JSON_PARSE_DISPATCHER_LABEL = "JsonParse"

internal expect val SlockJsonParseDispatcher: CoroutineDispatcher
