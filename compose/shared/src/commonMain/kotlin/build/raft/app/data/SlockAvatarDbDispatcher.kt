package build.raft.app.data

import kotlinx.coroutines.CoroutineDispatcher

/**
 * task #323: avatar cache metadata is stored in SQLite and its store is reached from composition
 * (`LaunchedEffect` → mounted binding). Running those blocking reads/writes on the caller's
 * dispatcher put them on the Kuikly frame thread; with a few hundred avatars finishing at once the
 * frame thread stayed busy for seconds while the Android main thread synchronously waited on it
 * for touch-move dispatch → ANR. All avatar metadata I/O is serialized onto this dispatcher instead.
 */
internal expect val SlockAvatarDbDispatcher: CoroutineDispatcher
