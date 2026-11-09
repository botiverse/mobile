package build.raft.app.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * PROTOTYPE capture: wire bodies whose DTOs are private to their parsers, written in the shapes
 * those parsers decode (MessageParser.MessagesWireMessage, MemberAgentParser.WireChannelMembers /
 * WireServerMember / WireAgent).
 */
internal object RaftCaptureFakeWire {
    data class Person(val id: String, val name: String, val kind: String, val description: String? = null, val status: String = "active")

    val people =
        listOf(
            Person(RaftCaptureFakeServer.UserId, "artin", "human", "Product"),
            Person("usr_capture_maya", "Maya", "human", "Product Designer"),
            Person("usr_capture_alex", "Alex", "human", "iOS"),
            Person("agt_capture_cindy", "Cindy", "agent", "Keeps visual testing honest"),
            Person("agt_capture_builder", "Builder", "agent", "Builds and ships"),
            Person("agt_capture_grace", "Grace", "agent", "Release notes"),
        )

    private fun agentJson(p: Person, serverId: String) =
        buildJsonObject {
            put("id", p.id)
            put("serverId", serverId)
            put("name", p.name.lowercase())
            put("displayName", p.name)
            p.description?.let { put("description", it) }
            put("status", p.status)
            put("runtime", "claude")
            put("computerName", "Jiacheng-MacBook-Pro")
        }

    fun channelMembers(serverId: String): String =
        buildJsonObject {
            put("humans", buildJsonArray {
                people.filter { it.kind == "human" }.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id)
                        put("serverId", serverId)
                        put("name", p.name.lowercase())
                        put("displayName", p.name)
                        p.description?.let { put("description", it) }
                        put("role", if (p.id == RaftCaptureFakeServer.UserId) "owner" else "member")
                    })
                }
            })
            put("agents", buildJsonArray { people.filter { it.kind == "agent" }.forEach { add(agentJson(it, serverId)) } })
        }.toString()

    fun serverMembers(serverId: String): String =
        buildJsonArray {
            people.filter { it.kind == "human" }.forEach { p ->
                add(buildJsonObject {
                    put("userId", p.id)
                    put("serverId", serverId)
                    put("name", p.name.lowercase())
                    put("displayName", p.name)
                    p.description?.let { put("description", it) }
                    put("role", if (p.id == RaftCaptureFakeServer.UserId) "owner" else "member")
                    put("joinedAt", "2026-01-12T10:00:00.000Z")
                })
            }
        }.toString()

    fun agents(serverId: String): String =
        buildJsonArray { people.filter { it.kind == "agent" }.forEach { add(agentJson(it, serverId)) } }.toString()

    private val lines =
        listOf(
            "agt_capture_cindy" to "Captured the native bar on the iPhone 17 simulator, switch on and off side by side.",
            RaftCaptureFakeServer.UserId to "Titles should sit on the left, like our own head.",
            "usr_capture_maya" to "Agreed. The server name with its chevron reads well on Home.",
            "agt_capture_builder" to "Build 1.18.0 (412) is green on iOS and Android.",
            "agt_capture_cindy" to "Next: members, channel settings, drafts and a Settings page.",
            RaftCaptureFakeServer.UserId to "Ship it to the prototype branch only. Never main.",
        )

    fun messages(channelId: String): String =
        buildJsonObject {
            put("messages", buildJsonArray {
                lines.forEachIndexed { index, (senderId, text) ->
                    val sender = people.first { it.id == senderId }
                    add(buildJsonObject {
                        put("id", "msg_capture_${channelId}_$index")
                        put("seq", index + 1)
                        put("channelId", channelId)
                        put("senderType", if (sender.kind == "agent") "agent" else "user")
                        put("senderId", sender.id)
                        put("senderName", sender.name)
                        put("messageType", "chat")
                        put("content", text)
                        val at = RaftCaptureFakeData.minutesAgoIso(30 - index * 5)
                        put("createdAt", at)
                        put("updatedAt", at)
                    })
                }
            })
            put("historyLimited", false)
        }.toString()

    fun JsonObject.with(key: String, value: String): JsonObject = JsonObject(this + (key to JsonPrimitive(value)))

    @Suppress("unused")
    private val empty = JsonArray(emptyList())
}
