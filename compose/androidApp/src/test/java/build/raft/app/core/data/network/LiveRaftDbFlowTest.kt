package build.raft.app.core.data.network

import build.raft.app.data.StoredAuthSession as AuthSession
import build.raft.app.data.RaftAuthSessionStore as AuthSessionStore
import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.data.PlatformFile
import build.raft.app.data.AttachmentUploadRequest
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.model.parentChannelIdOrNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRaftDbFlowTest {
    @Test
    fun liveCreateThreadReplyAndPersistSharedLocalCacheScopes() {
        assumeTrue("Set SLOCK_LIVE_TESTS=true to run live Slock API tests.", env("SLOCK_LIVE_TESTS") == "true")
        val fixture = createFixture()
        val localStore = InMemoryComposeLocalDataStore()

        val suffix = System.currentTimeMillis().toString(36)
        var cleanupChannelId: ChannelId? = null
        try {
            val createdChannel = runSuspend {
                fixture.channelRepository.createChannel(
                    CreateChannelRequest(
                        name = "android-live-db-$suffix",
                        description = "Temporary channel created by LiveRaftDbFlowTest"
                    )
                )
            }.contentOrThrow("create channel")
            val parentChannelId = requireNotNull(createdChannel.channel.channelId) {
                "Create channel response did not include channelId."
            }
            cleanupChannelId = parentChannelId

            val parentMessage = runSuspend {
                fixture.conversationRepository.sendMessage(
                    channelId = parentChannelId,
                    body = "live parent message $suffix"
                )
            }.contentOrThrow("send parent message").message
            assertEquals(parentChannelId, parentMessage.channelId)

            val threadAction = runSuspend {
                fixture.conversationRepository.createOrOpenThread(
                    channelId = parentChannelId,
                    parentMessageId = parentMessage.id
                )
            }.contentOrThrow("create/open reply thread")
            assertNotNull(threadAction.threadChannelId)
            assertEquals(parentMessage.id, threadAction.parentMessageId ?: parentMessage.id)

            val replyMessage = runSuspend {
                fixture.conversationRepository.sendMessage(
                    channelId = threadAction.threadChannelId,
                    body = "live reply message $suffix"
                )
            }.contentOrThrow("send thread reply").message
            assertEquals(threadAction.threadChannelId, replyMessage.channelId)

            val threadSnapshotResult = runSuspend {
                fixture.conversationRepository.getThread(ThreadId(threadAction.threadChannelId.value))
            }
            val threadSnapshot = threadSnapshotResult.contentOrThrow("load reply thread")
            runSuspend { localStore.writeThread(threadSnapshot) }

            val localSnapshot = runSuspend {
                localStore.readThread(threadSnapshot.conversation.threadId)
            }
            assertNotNull(localSnapshot)
            assertEquals(threadSnapshot.conversation.threadId, localSnapshot?.conversation?.threadId)
            assertTrue(
                "Expected the live reply to be stored under the thread channel.",
                localSnapshot?.messages.orEmpty().any { it.id == replyMessage.id && it.channelId == threadAction.threadChannelId }
            )
        } finally {
            cleanupChannelId?.let { fixture.deleteTestChannel(it) }
        }
    }

    @Test
    fun liveChannelFilesListsUploadedAttachmentMetadata() {
        assumeTrue("Set SLOCK_LIVE_TESTS=true to run live Slock API tests.", env("SLOCK_LIVE_TESTS") == "true")
        val fixture = createFixture()
        val suffix = System.currentTimeMillis().toString(36)
        val fileName = "live-channel-files-$suffix.txt"
        val fileContent = "live channel files payload $suffix"
        val fileBytes = fileContent.encodeToByteArray()

        var cleanupChannelId: ChannelId? = null
        try {
            val createdChannel = runSuspend {
                fixture.channelRepository.createChannel(
                    CreateChannelRequest(
                        name = "android-live-files-$suffix",
                        description = "Temporary channel created by LiveRaftDbFlowTest channel files coverage"
                    )
                )
            }.contentOrThrow("create channel for files")
            val channelId = requireNotNull(createdChannel.channel.channelId) {
                "Create channel response did not include channelId."
            }
            cleanupChannelId = channelId

            val uploadResult = runSuspend {
                fixture.conversationRepository.uploadAttachments(
                    AttachmentUploadRequest(
                        channelId = channelId,
                        files = listOf(
                            PlatformFile(
                                id = "live-file-$suffix",
                                fileName = fileName,
                                mimeType = "text/plain",
                                sizeBytes = fileBytes.size.toLong(),
                                bytes = fileBytes
                            )
                        )
                    )
                )
            }.contentOrThrow("upload attachment")
            val uploadedAttachment = requireNotNull(uploadResult.attachments.firstOrNull()) {
                "Attachment upload response did not return an attachment."
            }

            val sentMessage = runSuspend {
                fixture.conversationRepository.sendMessage(
                    channelId = channelId,
                    body = "live file message $suffix",
                    attachmentIds = uploadResult.attachmentIds
                )
            }.contentOrThrow("send attachment message").message

            val matchedFile = waitForChannelFile(
                channelId = channelId,
                attachmentId = uploadedAttachment.id.value,
                fileName = fileName,
                accessToken = fixture.accessToken,
                serverId = fixture.serverId,
                baseUrl = fixture.baseUrl
            )

            assertEquals(fileName, matchedFile.string("filename"))
            assertEquals(sentMessage.id.value, matchedFile.string("messageId"))
            assertEquals(fileBytes.size.toLong(), matchedFile.long("sizeBytes"))
            assertTrue("Expected files row to include source object.", matchedFile.has("source"))
            assertTrue("Expected files row to include thumbnailUrl field.", matchedFile.has("thumbnailUrl"))
            assertTrue(
                "Expected source.type to be present for channel files row.",
                !matchedFile.objectValue("source")?.string("type").isNullOrBlank()
            )
        } finally {
            cleanupChannelId?.let { fixture.deleteTestChannel(it) }
        }
    }

    @Test
    fun liveInboxMentionsFilterReturnsInboxShape() {
        assumeTrue("Set SLOCK_LIVE_TESTS=true to run live Slock API tests.", env("SLOCK_LIVE_TESTS") == "true")
        val fixture = createFixture()
        val payload = liveGetJson(
            url = "${fixture.baseUrl.trimEnd('/')}/channels/inbox?filter=mentions&limit=30&offset=0",
            accessToken = fixture.accessToken,
            serverId = fixture.serverId
        )

        assertTrue("Expected mentions inbox payload to include items.", payload.has("items"))
        assertTrue("Expected mentions inbox payload to include hasMore.", payload.has("hasMore"))
        assertTrue("Expected mentions inbox payload to include totalCount.", payload.has("totalCount"))
        assertTrue("Expected mentions inbox payload to include totalUnreadCount.", payload.has("totalUnreadCount"))

        val firstItem = payload.getAsJsonArray("items")
            ?.firstOrNull { it.isJsonObject }
            ?.asJsonObject
        if (firstItem != null) {
            assertTrue("Expected mentions inbox row to include kind.", firstItem.has("kind"))
            assertTrue("Expected mentions inbox row to include unreadCount.", firstItem.has("unreadCount"))
            assertTrue(
                "Expected mentions inbox row to surface mention semantics.",
                firstItem.has("hasMention") || firstItem.has("mentionCount") || firstItem.has("mentions")
            )
        }
    }

    @Test
    fun liveSearchAcceptsWebFilterParams() {
        assumeTrue("Set SLOCK_LIVE_TESTS=true to run live Slock API tests.", env("SLOCK_LIVE_TESTS") == "true")
        val fixture = createFixture()
        val home = runSuspend {
            fixture.channelRepository.getHome(ServerId(fixture.serverId))
        }.contentOrThrow("load home for search filter fixture")
        val channelId =
            home.channels.firstOrNull { it.channelId != null }?.channelId?.value

        val params =
            listOfNotNull(
                "q" to "1",
                "limit" to "20",
                "offset" to "0",
                "sort" to "recent",
                "senderId" to fixture.userId,
                channelId?.let { "channelId" to it },
                "after" to "2026-05-14T16:00:00.000Z",
                "before" to "2026-05-15T15:04:43.904Z"
            ).joinToString("&") { (key, value) -> "${key.urlEncode()}=${value.urlEncode()}" }
        val payload = liveGetJson(
            url = "${fixture.baseUrl.trimEnd('/')}/messages/search?$params",
            accessToken = fixture.accessToken,
            serverId = fixture.serverId
        )

        assertTrue("Expected search payload to include results.", payload.has("results"))
        assertTrue("Expected search payload to include hasMore.", payload.has("hasMore"))
    }

    private fun createFixture(): LiveFixture {
        val email = requireEnv("SLOCK_TEST_EMAIL")
        val password = requireEnv("SLOCK_TEST_PASSWORD")
        val baseUrl = env("SLOCK_BASE_URL") ?: AndroidAuthApiFactory.BASE_URL

        val authStore = InMemoryAuthSessionStore()
        val authDataSource = build.raft.app.data.SharedAuthDataSource(
                api = AndroidAuthApiFactory.createAuthApi(baseUrl),
                sessionStore = authStore
            )

        val login = runSuspend { authDataSource.login(email, password) }
        assertTrue("Expected live login to succeed but got: $login", login is build.raft.app.data.SharedAuthResult.Success)
        val session = requireNotNull(authStore.session) { "Login succeeded but no session was stored." }
        val serverId = requireNotNull(session.currentServerId) { "Live account has no selected server." }

        val networkClient = RealRaftNetworkClientFactory.create(authStore, baseUrl = baseUrl)
        return LiveFixture(
            authStore = authStore,
            channelRepository =
                RealChannelRepository(
                    sessionStore = authStore,
                    networkClient = networkClient,
                    warningLogger = {}
                ),
            conversationRepository =
                RealConversationRepository(
                    sessionStore = authStore,
                    networkClient = networkClient,
                    warningLogger = {}
                ),
            baseUrl = baseUrl,
            accessToken = session.accessToken,
            serverId = serverId,
            userId = session.user.id
        )
    }

    private fun waitForChannelFile(
        channelId: ChannelId,
        attachmentId: String,
        fileName: String,
        accessToken: String,
        serverId: String,
        baseUrl: String
    ): JsonObject {
        repeat(15) { attempt ->
            val json = liveGetJson(
                url = "${baseUrl.trimEnd('/')}/channels/${channelId.value}/files?limit=50",
                accessToken = accessToken,
                serverId = serverId
            )
            val files = json.getAsJsonArray("files")
            val matched = files
                ?.mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject }
                ?.firstOrNull { row ->
                    row.string("id") == attachmentId || row.string("filename") == fileName
                }
            if (matched != null) return matched
            if (attempt < 14) Thread.sleep(1_000)
        }
        error("Timed out waiting for uploaded attachment to appear in /channels/{id}/files.")
    }

    private fun liveGetJson(
        url: String,
        accessToken: String,
        serverId: String
    ): JsonObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-Server-Id", serverId)
            setRequestProperty("Accept", "application/json")
        }
        return connection.useAndDisconnect { http ->
            val code = http.responseCode
            val body = (if (code in 200..299) http.inputStream else http.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            assertTrue("Expected successful GET $url but got HTTP $code with body: $body", code in 200..299)
            JsonParser.parseString(body).asJsonObject
        }
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

    private fun LiveFixture.deleteTestChannel(channelId: ChannelId) {
        runSuspend { channelRepository.deleteChannel(channelId) }
            .contentOrThrow("delete test channel ${channelId.value}")
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

        override suspend fun clearSession(reason: String, source: String) {
            session = null
        }
    }

    private data class LiveFixture(
        val authStore: InMemoryAuthSessionStore,
        val channelRepository: RealChannelRepository,
        val conversationRepository: RealConversationRepository,
        val baseUrl: String,
        val accessToken: String,
        val serverId: String,
        val userId: String
    )
}

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }

private fun JsonObject.string(name: String): String? =
    takeIf { has(name) && !get(name).isJsonNull }?.get(name)?.asString

private fun JsonObject.long(name: String): Long? =
    takeIf { has(name) && !get(name).isJsonNull }?.get(name)?.asLong

private fun JsonObject.objectValue(name: String): JsonObject? =
    takeIf { has(name) && get(name).isJsonObject }?.getAsJsonObject(name)

private inline fun <T> HttpURLConnection.useAndDisconnect(block: (HttpURLConnection) -> T): T =
    try {
        block(this)
    } finally {
        disconnect()
    }
