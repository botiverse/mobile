package ai.slock.compose.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockSqlDatabaseCompatibilityTest {
    @Test
    fun openOldChannelSchemaAddsJointChannelColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, oldChannelEntitySchema, 0)
        driver.execute(
            identifier = null,
            sql =
                """
                INSERT INTO channelEntity(
                  serverId,
                  channelId,
                  type,
                  name
                ) VALUES ('server-1', 'channel-1', 'channel', 'General')
                """.trimIndent(),
            parameters = 0
        )

        val database = SlockSqlDatabase(driver)

        val row = database.queries.selectChannel("server-1", "channel-1").executeAsOne()
        assertEquals("General", row.name)
        assertNull(row.jointChannelId)
        assertEquals(15L, row.capabilities)
        assertEquals(0L, row.lastSyncedAtMs)
    }

    private companion object {
        val oldChannelEntitySchema =
            """
            CREATE TABLE channelEntity (
              serverId TEXT NOT NULL,
              channelId TEXT NOT NULL,
              type TEXT NOT NULL,
              name TEXT NOT NULL,
              description TEXT,
              parentChannelId TEXT,
              parentMessageId TEXT,
              avatarUrl TEXT,
              presence TEXT,
              agentStatus TEXT,
              presenceMemberId TEXT,
              joined INTEGER NOT NULL DEFAULT 1,
              archived INTEGER NOT NULL DEFAULT 0,
              memberCount INTEGER,
              unreadCount INTEGER NOT NULL DEFAULT 0,
              mentionCount INTEGER NOT NULL DEFAULT 0,
              lastMessageId TEXT,
              firstUnreadMessageId TEXT,
              lastMessagePreview TEXT,
              lastMessageSenderName TEXT,
              hasDraft INTEGER NOT NULL DEFAULT 0,
              replyCount INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (serverId, channelId)
            )
            """.trimIndent()
    }
}
