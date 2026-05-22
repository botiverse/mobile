package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ThreadId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RelationalComposeLocalDataStoreTest {
    @Test
    fun persistsThreadSnapshotInRelationalStore() = runBlocking {
        val localStore = RelationalComposeLocalDataStore(SnapshotRelationalStore())
        val snapshot =
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("m1"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            author = member,
                            timestampLabel = "2026-05-22T02:00:00Z",
                            body = "cached body"
                        )
                    )
            )

        localStore.writeThread(snapshot)

        val restored = localStore.readThread(ThreadId("channel-1"))
        assertEquals("general", restored?.conversation?.name)
        assertEquals("cached body", restored?.messages?.single()?.body)
        assertEquals("Artin", restored?.messages?.single()?.author?.displayName)
    }

    private class SnapshotRelationalStore : SlockRelationalStore {
        private val values = mutableMapOf<Pair<String, String>, String>()

        override suspend fun execute(sql: String, bindArgs: List<RelationalValue>) {
            when {
                sql.startsWith("INSERT OR REPLACE INTO slock_local_snapshots") -> {
                    values[bindArgs[0].textValue to bindArgs[1].textValue] = bindArgs[2].textValue
                }
                sql.startsWith("DELETE FROM slock_local_snapshots") -> {
                    values.remove(bindArgs[0].textValue to bindArgs[1].textValue)
                }
            }
        }

        override suspend fun query(sql: String, bindArgs: List<RelationalValue>): RelationalRows =
            when {
                sql.startsWith("SELECT value FROM slock_local_snapshots") -> {
                    val value = values[bindArgs[0].textValue to bindArgs[1].textValue]
                    RelationalRows(
                        columns = listOf("value"),
                        rows = value?.let { listOf(listOf(RelationalValue.Text(it))) }.orEmpty()
                    )
                }
                sql.startsWith("SELECT id FROM slock_local_snapshots") -> {
                    val scope = bindArgs[0].textValue
                    RelationalRows(
                        columns = listOf("id"),
                        rows =
                            values.keys
                                .filter { it.first == scope }
                                .map { listOf(RelationalValue.Text(it.second)) }
                    )
                }
                else -> RelationalRows(emptyList(), emptyList())
            }

        override suspend fun transaction(block: suspend SlockRelationalStore.() -> Unit) {
            block()
        }

        private val RelationalValue.textValue: String
            get() = (this as RelationalValue.Text).value
    }

    private companion object {
        val member =
            MemberProfile(
                id = MemberId("u1"),
                displayName = "Artin",
                handle = "artin",
                kind = MemberKind.Human,
                presence = Presence.Active
            )
    }
}
