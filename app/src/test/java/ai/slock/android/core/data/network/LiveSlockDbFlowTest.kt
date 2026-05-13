package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthRepository
import ai.slock.android.core.data.auth.AuthResult
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.KtorAuthRepository
import ai.slock.android.core.data.local.SlockDatabase
import ai.slock.android.core.data.local.SlockLocalWriteThrough
import ai.slock.android.core.data.local.SqlDelightSlockLocalStore
import ai.slock.android.core.data.repository.CreateChannelRequest
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.ThreadId
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSlockDbFlowTest {
    @Test
    fun liveCreateThreadReplyAndPersistLocalDbScopes() {
        assumeTrue("Set SLOCK_LIVE_TESTS=true to run live Slock API tests.", env("SLOCK_LIVE_TESTS") == "true")
        val email = requireEnv("SLOCK_TEST_EMAIL")
        val password = requireEnv("SLOCK_TEST_PASSWORD")
        val baseUrl = env("SLOCK_BASE_URL") ?: KtorAuthRepository.BASE_URL

        val authStore = InMemoryAuthSessionStore()
        val authRepository: AuthRepository = KtorAuthRepository(
            store = authStore,
            api = KtorAuthRepository.createAuthApi(baseUrl)
        )

        val login = runSuspend { authRepository.login(email, password) }
        assertTrue(login is AuthResult.Success)
        val session = requireNotNull(authStore.session) { "Login succeeded but no session was stored." }
        val serverId = requireNotNull(session.currentServerId) { "Live account has no selected server." }

        val api = RealSlockApiFactory.create(authStore, baseUrl = baseUrl)
        val channelRepository = RealChannelRepository(authStore, api, warningLogger = {})
        val conversationRepository = RealConversationRepository(authStore, api, warningLogger = {})
        val localStore = SqlDelightSlockLocalStore(
            SlockDatabase(
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { driver ->
                    SlockDatabase.Schema.create(driver)
                }
            )
        )
        val localWriteThrough = SlockLocalWriteThrough(localStore)

        val suffix = System.currentTimeMillis().toString(36)
        val createdChannel = runSuspend {
            channelRepository.createChannel(
                CreateChannelRequest(
                    name = "android-live-db-$suffix",
                    description = "Temporary channel created by LiveSlockDbFlowTest"
                )
            )
        }.contentOrThrow("create channel")
        val parentChannelId = requireNotNull(createdChannel.channel.channelId) {
            "Create channel response did not include channelId."
        }

        val parentMessage = runSuspend {
            conversationRepository.sendMessage(
                channelId = parentChannelId,
                body = "live parent message $suffix"
            )
        }.contentOrThrow("send parent message").message
        assertEquals(parentChannelId, parentMessage.channelId)

        val threadAction = runSuspend {
            conversationRepository.createOrOpenThread(
                channelId = parentChannelId,
                parentMessageId = parentMessage.id
            )
        }.contentOrThrow("create/open reply thread")
        assertNotNull(threadAction.threadChannelId)
        assertEquals(parentMessage.id, threadAction.parentMessageId ?: parentMessage.id)

        val replyMessage = runSuspend {
            conversationRepository.sendMessage(
                channelId = threadAction.threadChannelId,
                body = "live reply message $suffix"
            )
        }.contentOrThrow("send thread reply").message
        assertEquals(threadAction.threadChannelId, replyMessage.channelId)

        val threadSnapshotResult = runSuspend {
            conversationRepository.getThread(ThreadId(threadAction.threadChannelId.value))
        }
        val threadSnapshot = threadSnapshotResult.contentOrThrow("load reply thread")
        localWriteThrough.writeThreadResult(threadSnapshotResult, source = "live_reply_thread")

        val localTimeline = localStore.getThreadTimeline(threadSnapshot.conversation.threadId)
        assertNotNull(localTimeline)
        assertEquals(parentChannelId, localTimeline?.conversation?.channelId)
        assertEquals(threadAction.threadChannelId, localTimeline?.conversation?.thread?.streamChannelId)
        assertTrue(
            "Expected the live reply to be stored under the thread channel.",
            localTimeline?.messages.orEmpty().any { it.id == replyMessage.id && it.channelId == threadAction.threadChannelId }
        )

        val cursor = localStore.getSyncCursor(threadAction.threadChannelId.value)
        assertNotNull("Expected cursor scoped by thread channel id.", cursor)
    }

    private fun env(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun requireEnv(name: String): String =
        requireNotNull(env(name)) { "Missing required environment variable $name" }

    private fun <T> DataResult<T>.contentOrThrow(label: String): T =
        when (this) {
            is DataResult.Content -> data
            is DataResult.Empty -> error("$label returned empty: ${reason.name}")
            is DataResult.Error -> error("$label failed: ${error.code}")
            is DataResult.NoPermission -> error("$label denied: ${blocker.code}")
        }

    private class InMemoryAuthSessionStore(
        var session: AuthSession? = null
    ) : AuthSessionStore {
        override suspend fun readSession(): AuthSession? = session

        override suspend fun saveSession(session: AuthSession) {
            this.session = session
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }

        override suspend fun clearSession() {
            session = null
        }
    }
}

private fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }
