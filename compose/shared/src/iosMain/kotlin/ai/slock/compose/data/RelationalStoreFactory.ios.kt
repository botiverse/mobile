@file:OptIn(kotlinx.cinterop.UnsafeNumber::class)

package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

internal actual fun createSlockSqlDatabase(bridge: BridgeModule): SlockSqlDatabase =
    SlockSqlDatabase(
        NativeSqliteDriver(
            schema = SlockSqlDatabase.Schema,
            name = SlockSqlDatabase.DatabaseName,
            onConfiguration = { configuration ->
                configuration.copy(
                    extendedConfig =
                        configuration.extendedConfig.copy(
                            basePath = iosDatabaseBasePath()
                        )
                )
            }
        )
    )

private fun iosDatabaseBasePath(): String {
    val applicationSupportDirectory =
        NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
            ?: error("iOS Application Support directory is unavailable for Slock SQLDelight")
    val databaseDirectory = "$applicationSupportDirectory/Slock/databases"
    val fileManager = NSFileManager.defaultManager()
    if (!fileManager.fileExistsAtPath(databaseDirectory)) {
        val created = fileManager.createDirectoryAtPath(databaseDirectory, true, null, null)
        if (!created && !fileManager.fileExistsAtPath(databaseDirectory)) {
            error("Unable to create iOS Slock SQLDelight database directory")
        }
    }
    return databaseDirectory
}
