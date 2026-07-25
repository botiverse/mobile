package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SharedReplyThreadAccessFlightStoreTest {
    @Test
    fun sameIdentityReentryJoinsOneProducerAndInheritsItsTerminal() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SharedReplyThreadAccessFlightStore<String>(ownerScope)
            val key = key()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var starts = 0
            val first =
                async {
                    store.joinOrStart(key) {
                        starts += 1
                        started.complete(Unit)
                        release.await()
                        DataResult.Content("resolved")
                    }
                }
            started.await()
            val second = async { store.joinOrStart(key) { error("must join the original producer") } }
            delay(25L)

            assertEquals(1, starts)
            release.complete(Unit)
            assertEquals("resolved", assertIs<DataResult.Content<String>>(first.await()).data)
            assertEquals("resolved", assertIs<DataResult.Content<String>>(second.await()).data)
            assertNull(store.failure(key))
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun cancellingFirstPageWaiterDoesNotCancelTheAppScopeProducer() = runBlocking {
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SharedReplyThreadAccessFlightStore<String>(ownerScope)
            val key = key()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var starts = 0
            val first =
                async {
                    store.joinOrStart(key) {
                        starts += 1
                        started.complete(Unit)
                        release.await()
                        DataResult.Content("resolved")
                    }
                }
            started.await()
            first.cancelAndJoin()

            val reentry =
                async(start = CoroutineStart.UNDISPATCHED) {
                    store.joinOrStart(key) { error("reentry must join") }
                }
            release.complete(Unit)

            assertEquals("resolved", assertIs<DataResult.Content<String>>(withTimeout(250L) { reentry.await() }).data)
            assertEquals(1, starts)
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun terminalErrorIsRememberedBrieflyAndManualRetryStartsExactlyOneSuccessor() = runBlocking {
        var now = 1_000L
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store =
                SharedReplyThreadAccessFlightStore<String>(
                    scope = ownerScope,
                    failureTtlMillis = 10_000L,
                    nowMillis = { now }
                )
            val key = key()
            var starts = 0
            val failed =
                store.joinOrStart(key) {
                    starts += 1
                    DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
                }

            assertEquals("thread_reply_network", assertIs<DataResult.Error>(failed).error.code)
            assertEquals("thread_reply_network", store.failure(key)?.code)
            val remembered = store.joinOrStart(key) { error("remembered failure must not reopen POST") }
            assertEquals("thread_reply_network", assertIs<DataResult.Error>(remembered).error.code)
            assertEquals(1, starts)

            val retry =
                store.joinOrStart(key, replaceFailure = true) {
                    starts += 1
                    DataResult.Content("retry-success")
                }
            assertEquals("retry-success", assertIs<DataResult.Content<String>>(retry).data)
            assertEquals(2, starts)
            assertNull(store.failure(key))
        } finally {
            ownerScope.cancel()
        }
    }

    @Test
    fun expiredFailureCanStartANewFlightWithoutExtendingTheOldWindow() = runBlocking {
        var now = 5_000L
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store =
                SharedReplyThreadAccessFlightStore<String>(
                    scope = ownerScope,
                    failureTtlMillis = 1_000L,
                    nowMillis = { now }
                )
            val key = key()
            store.joinOrStart(key) {
                DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
            }
            now = 6_001L

            val result = store.joinOrStart(key) { DataResult.Content("new-flight") }

            assertEquals("new-flight", assertIs<DataResult.Content<String>>(result).data)
        } finally {
            ownerScope.cancel()
        }
    }

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
