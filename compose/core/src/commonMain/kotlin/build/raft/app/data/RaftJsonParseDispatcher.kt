package build.raft.app.data

import kotlinx.coroutines.CoroutineDispatcher

const val RAFT_JSON_PARSE_DISPATCHER_LABEL = "JsonParse"
const val RAFT_JSON_PARSE_PARALLELISM = 1

expect val RaftJsonParseDispatcher: CoroutineDispatcher
