package ai.slock.android.core.data.local

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

private const val SlockDatabaseName = "slock.db"

fun createSlockSqlDriver(context: Context): SqlDriver =
    AndroidSqliteDriver(SlockDatabase.Schema, context, SlockDatabaseName)

fun createSlockLocalStore(context: Context): SlockLocalStore =
    SqlDelightSlockLocalStore(SlockDatabase(createSlockSqlDriver(context)))
