package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.EncryptedAuthSessionStore
import ai.slock.compose.network.SlockRequestPriority
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveApiContractClosureTest {
    @Test
    fun liveHomeAndMemberContractsMatchServerShapes() = runBlocking {
        val fixture = signedInFixture()

        val channels = fixture.api.channels(SlockRequestPriority.Visible, archived = "exclude")
            .requireSuccessfulJson("GET /channels")
            .requireArray("GET /channels")
        channels.forEachObject("GET /channels item") { item ->
            item.requireString("id", "GET /channels item")
            item.requireString("name", "GET /channels item")
            item.requireString("type", "GET /channels item")
            assertTrue(
                "channel type should be canonical: $item",
                item.string("type") in setOf("channel", "private", "dm", "thread")
            )
        }

        val dms = fixture.api.directMessages(SlockRequestPriority.Visible)
            .requireSuccessfulJson("GET /channels/dm")
            .requireArray("GET /channels/dm")
        dms.forEachObject("GET /channels/dm item") { item ->
            item.requireString("id", "GET /channels/dm item")
            item.requireString("type", "GET /channels/dm item")
            assertEquals("GET /channels/dm item type", "dm", item.string("type"))
            item.requireString("peerId", "GET /channels/dm item")
            item.requireString("peerName", "GET /channels/dm item")
            item.requireString("peerType", "GET /channels/dm item")
            assertTrue(
                "DM peerType should be canonical: $item",
                item.string("peerType") in setOf("agent", "user")
            )
            assertTrue(
                "DM display must be derived from peer fields, not only root name: $item",
                item.hasNonBlank("peerDisplayName") || item.hasNonBlank("peerName")
            )
        }

        val members = fixture.api.serverMembers(SlockRequestPriority.Visible, fixture.serverId)
            .requireSuccessfulJson("GET /servers/{id}/members")
            .requireArray("GET /servers/{id}/members")
        members.forEachObject("GET /servers/{id}/members item") { item ->
            item.requireString("userId", "GET /servers/{id}/members item")
            item.requireString("name", "GET /servers/{id}/members item")
            item.requireString("role", "GET /servers/{id}/members item")
            assertTrue(
                "server member role should be canonical: $item",
                item.string("role") in setOf("owner", "admin", "member")
            )
            assertTrue(
                "server member should expose avatarUrl or gravatarHash for avatar parity: $item",
                item.has("avatarUrl") || item.hasNonBlank("gravatarHash")
            )
        }
    }

    @Test
    fun liveThreadContractsMatchServerShapes() = runBlocking {
        val fixture = signedInFixture()
        val channelId = fixture.firstReadableConversationId()
        assumeTrue("No readable channel or DM available for live thread API check.", channelId != null)

        val page = fixture.api.channelMessages(
            priority = SlockRequestPriority.Visible,
            channelId = requireNotNull(channelId),
            limit = 30
        ).requireSuccessfulJson("GET /messages/channel/{id}")
            .requireObject("GET /messages/channel/{id}")

        val messages = page.array("messages")
        assertNotNull("GET /messages/channel/{id} must return messages array", messages)
        assertTrue("GET /messages/channel/{id} must expose historyLimited flag", page.has("historyLimited"))

        val maxSeq = messages?.mapObjects("GET /messages/channel/{id} message") { item ->
            item.requireString("id", "message")
            item.requireString("channelId", "message")
            item.requireString("senderId", "message")
            item.requireString("senderType", "message")
            item.requireString("senderName", "message")
            item.requireString("createdAt", "message")
            assertTrue("senderType should be canonical: $item", item.string("senderType") in setOf("user", "agent", "system"))
            assertTrue("message seq should be positive: $item", (item.long("seq") ?: 0L) > 0L)
            assertNotNull("message attachments should be an array, even when empty: $item", item.array("attachments"))
            item.long("seq") ?: 0L
        }?.maxOrNull() ?: 0L

        val sync = fixture.api.messageSync(
            priority = SlockRequestPriority.Visible,
            sinceSeq = maxSeq,
            limit = 20,
            channelId = channelId
        ).requireSuccessfulJson("GET /messages/sync")
            .requireArray("GET /messages/sync")
        sync.forEachObject("GET /messages/sync item") { item ->
            item.requireString("id", "sync message")
            item.requireString("channelId", "sync message")
            assertTrue("sync message seq should be positive: $item", (item.long("seq") ?: 0L) > 0L)
        }

        val firstAttachmentId = messages?.firstObjectWithArrayItem("attachments")?.string("id")
        if (firstAttachmentId != null) {
            val target = fixture.api.attachmentUrl(SlockRequestPriority.Visible, firstAttachmentId)
                .requireSuccessfulJson("GET /attachments/{id}/url")
                .requireObject("GET /attachments/{id}/url")
            target.requireString("url", "GET /attachments/{id}/url")
            target.requireString("expiresAt", "GET /attachments/{id}/url")
        }
    }

    @Test
    fun liveTaskAndUnreadContractsMatchServerShapes() = runBlocking {
        val fixture = signedInFixture()

        val tasks = fixture.api.serverTasks(SlockRequestPriority.Visible)
            .requireSuccessfulJson("GET /tasks/server")
            .requireObject("GET /tasks/server")
            .array("tasks")
        assertNotNull("GET /tasks/server must return { tasks: [] }", tasks)
        tasks?.forEachObject("GET /tasks/server item") { item ->
            item.requireString("id", "task")
            item.requireString("channelId", "task")
            item.requireString("title", "task")
            item.requireString("status", "task")
            assertTrue("task status should be canonical: $item", item.string("status") in setOf("todo", "in_progress", "in_review", "done", "closed"))
        }

        val unreadCounts = fixture.api.channelUnreadCounts(SlockRequestPriority.Visible)
            .requireSuccessfulJson("GET /channels/unread")
            .requireObject("GET /channels/unread")
        assertTrue(
            "GET /channels/unread should expose counts as direct map or known wrapper: $unreadCounts",
            unreadCounts.entrySet().isNotEmpty() ||
                unreadCounts.has("counts") ||
                unreadCounts.has("items") ||
                unreadCounts.has("channels")
        )

        val serverUnread = fixture.api.serverUnreadSummary(SlockRequestPriority.Visible)
            .requireSuccessfulJson("GET /servers/unread-summary")
            .requireArray("GET /servers/unread-summary")
        serverUnread.forEachObject("GET /servers/unread-summary item") { item ->
            item.requireString("serverId", "server unread item")
            assertTrue("server unread count should be non-negative: $item", (item.long("unreadCount") ?: -1L) >= 0L)
        }
    }

    private suspend fun signedInFixture(): LiveFixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedAuthSessionStore(context)
        val session = store.readSession()
        assumeTrue("No signed-in app session available for live API contract checks.", session != null)
        val serverId = session?.currentServerId ?: session?.servers?.firstOrNull()?.id
        assumeTrue("Signed-in session has no selected server for live API contract checks.", !serverId.isNullOrBlank())
        return LiveFixture(api = RealSlockApiFactory.create(store), serverId = requireNotNull(serverId))
    }

    private data class LiveFixture(
        val api: SlockApi,
        val serverId: String
    )

    private suspend fun LiveFixture.firstReadableConversationId(): String? {
        val channels = api.channels(SlockRequestPriority.Visible, archived = "exclude")
            .requireSuccessfulJson("GET /channels")
            .requireArray("GET /channels")
        val joinedChannel = channels.firstObjectOrNull { it.boolean("joined") != false }?.string("id")
        if (joinedChannel != null) return joinedChannel

        return api.directMessages(SlockRequestPriority.Visible)
            .requireSuccessfulJson("GET /channels/dm")
            .requireArray("GET /channels/dm")
            .firstObjectOrNull { true }
            ?.string("id")
    }

    private fun ApiResponse.requireSuccessfulJson(label: String): JsonElement {
        val text = body?.readUtf8Once()
        if (!isSuccessful) {
            val errorText = errorBody?.readUtf8Once()
            throw AssertionError("$label failed with HTTP $code body=$errorText")
        }
        assertTrue("$label returned an empty body", !text.isNullOrBlank())
        return JsonParser.parseString(text)
    }

    private fun ResponseBody.readUtf8Once(): String = string()

    private fun JsonElement.requireObject(label: String): JsonObject {
        assertTrue("$label should be a JSON object: $this", isJsonObject)
        return asJsonObject
    }

    private fun JsonElement.requireArray(label: String): JsonArray {
        assertTrue("$label should be a JSON array: $this", isJsonArray)
        return asJsonArray
    }

    private fun JsonArray.forEachObject(label: String, block: (JsonObject) -> Unit) {
        forEachIndexed { index, element ->
            assertTrue("$label[$index] should be a JSON object: $element", element.isJsonObject)
            block(element.asJsonObject)
        }
    }

    private fun <T> JsonArray.mapObjects(label: String, block: (JsonObject) -> T): List<T> =
        mapIndexed { index, element ->
            assertTrue("$label[$index] should be a JSON object: $element", element.isJsonObject)
            block(element.asJsonObject)
        }

    private fun JsonArray.firstObjectOrNull(predicate: (JsonObject) -> Boolean): JsonObject? =
        firstOrNull { it.isJsonObject && predicate(it.asJsonObject) }?.asJsonObject

    private fun JsonArray.firstObjectWithArrayItem(name: String): JsonObject? =
        firstObjectOrNull { item ->
            val array = item.array(name)
            array != null && array.size() > 0 && array.first().isJsonObject
        }?.array(name)?.first()?.asJsonObject

    private fun JsonObject.requireString(name: String, label: String): String {
        val value = string(name)
        assertTrue("$label should contain non-blank `$name`: $this", !value.isNullOrBlank())
        return requireNotNull(value)
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(name: String): Long? =
        get(name)?.takeUnless { it.isJsonNull }?.runCatching { asLong }?.getOrNull()

    private fun JsonObject.boolean(name: String): Boolean? =
        get(name)?.takeUnless { it.isJsonNull }?.runCatching { asBoolean }?.getOrNull()

    private fun JsonObject.array(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.hasNonBlank(name: String): Boolean =
        !string(name).isNullOrBlank()
}
