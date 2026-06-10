@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.NULL
import platform.posix.PATH_MAX
import platform.posix.S_IFDIR
import platform.posix.W_OK
import platform.posix.access
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.stat

internal actual fun createSlockRelationalStore(bridge: BridgeModule): SlockRelationalStore =
    SqlDelightRelationalStore(createSlockSqlDatabase(bridge))

internal actual fun createSlockSqlDatabase(bridge: BridgeModule): SlockSqlDatabase =
    SlockSqlDatabase(
        NativeSqliteDriver(
            schema = SlockSqlDatabase.Schema,
            name = SlockSqlDatabase.DatabaseName,
            onConfiguration = { configuration ->
                configuration.copy(
                    extendedConfig =
                        configuration.extendedConfig.copy(
                            basePath = ohosDatabaseBasePath()
                        )
                )
            }
        )
    )

private fun ohosDatabaseBasePath(): String {
    val candidates =
        buildList {
            add("/data/storage/el2/base/haps/entry/files")
            add("/data/storage/el2/base/hap/entry/files")
            getenv("TMPDIR")?.toKString()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let(::add)
            getenv("HOME")?.toKString()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let(::add)
            memScoped {
                val buffer = ByteArray(PATH_MAX)
                if (getcwd(buffer.refTo(0), buffer.size.toULong()) != NULL) {
                    add(buffer.toKString().trimEnd('\u0000').trimEnd('/'))
                }
            }
        }
    return candidates.distinct().firstOrNull(::isWritableDirectory)
        ?: "/data/storage/el2/base/haps/entry/files"
}

private fun isWritableDirectory(path: String): Boolean =
    path.isNotBlank() &&
        memScoped {
            val status = alloc<stat>()
            if (stat(path, status.ptr) != 0) return@memScoped false
            val isDirectory = (status.st_mode.toInt() and S_IFDIR) == S_IFDIR
            isDirectory && access(path, W_OK) == 0
        }
