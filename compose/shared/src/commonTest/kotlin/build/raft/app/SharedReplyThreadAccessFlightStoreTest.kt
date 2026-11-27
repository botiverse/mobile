package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.thread.SharedReplyThreadAccessFlightKey
import build.raft.app.thread.SharedReplyThreadAccessFlightStore
import build.raft.app.thread.key
import build.raft.app.thread.sharedReplyThreadAccessFlightKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SharedReplyThreadAccessFlightStoreTest {
    @Test
    fun accountServerEnvironmentAuthAndRouteMismatchesNeverJoin() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SharedReplyThreadAccessFlightStore<String>(ownerScope)
            val keys =
                listOf(
                    key(),
                    key(environment = "https://staging.example.test"),
                    key(principal = "user-2"),
                    key(server = "server-2"),
                    key(auth = "token-2"),
                    key(parentChannel = "channel-2"),
                    key(parentMessage = "message-2")
                )
            var starts = 0
            keys.forEachIndexed { index, candidate ->
                val result =
                    store.joinOrStart(candidate) {
                        starts += 1
                        DataResult.Content("result-$index")
                    }
                assertEquals("result-$index", assertIs<DataResult.Content<String>>(result).data)
            }

            assertEquals(keys.size, starts)
            assertContains(key(auth = "secret-token").toString(), "authBinding=[redacted]")
        } finally {
            ownerScope.cancel()
        }
    }

    private fun key(
        environment: String = "https://prod.example.test",
        principal: String = "user-1",
        server: String = "server-1",
        auth: String = "token-1",
        parentChannel: String = "channel-1",
        parentMessage: String = "message-1"
    ): SharedReplyThreadAccessFlightKey =
        requireNotNull(
            sharedReplyThreadAccessFlightKey(
                environmentId = environment,
                principalId = principal,
                serverId = server,
                authBinding = auth,
                parentChannelId = parentChannel,
                parentMessageId = parentMessage
            )
        )
}
