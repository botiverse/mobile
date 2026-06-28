package ai.slock.compose.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val SlockJsonParseDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
