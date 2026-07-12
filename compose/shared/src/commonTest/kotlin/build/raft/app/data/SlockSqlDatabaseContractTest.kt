package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockSqlDatabaseContractTest {
    @Test
    fun databaseFilenameIsVersionedForTheSchemaReset() {
        assertEquals("slock_cache_v4.db", SlockSqlDatabase.DatabaseName)
    }
}
