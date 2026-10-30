package build.raft.app.contract

import build.raft.app.data.RaftNotificationAudience
import build.raft.app.data.RaftNotificationLevel
import build.raft.app.data.RaftNotificationLevelSetting
import build.raft.app.data.raftNotificationNotifies
import build.raft.app.data.resolveEffectiveNotificationLevel
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Runs botiverse/slock's notification level fixture
 * (`docs/contracts/fixtures/notification-levels.json`, the cases `resolveEffectiveLevel` in
 * `packages/shared/src/notifications.ts` and the server policy agree with) against the app's
 * [resolveEffectiveNotificationLevel] and [raftNotificationNotifies]. The copy lives in
 * `src/commonTest/resources/contract-cases/notification-levels/` with the slock commit it was
 * taken from in `SOURCE_SHA`; it is read from the JVM classpath because commonTest has no
 * portable resource reader. Re-vendor by copying the file from that path at a newer commit.
 */
class NotificationLevelContractCasesTest {
    private val fixture: JsonObject by lazy {
        val url =
            checkNotNull(javaClass.getResource("/contract-cases/notification-levels/notification-levels.json")) {
                "contract-cases/notification-levels is not on the test classpath (src/commonTest/resources)"
            }
        Json.parseToJsonElement(File(url.toURI()).readText()).jsonObject
    }

    @Test
    fun sourceShaIsPinned() {
        val url = checkNotNull(javaClass.getResource("/contract-cases/notification-levels/SOURCE_SHA"))
        val sha = File(url.toURI()).readText().trim()
        assertTrue(sha.matches(Regex("[0-9a-f]{40}")), "SOURCE_SHA must be a full slock commit, was '$sha'")
    }

    @Test
    fun everyEffectiveLevelCaseAgrees() {
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty(), "the fixture lists no cases")
        val failures =
            cases.mapNotNull { case ->
                val name = case.getValue("name").jsonPrimitive.content
                val server = checkNotNull(RaftNotificationLevel.fromWire(case.string("server"))) { "$name: server" }
                val channel = case.setting("channel", name)
                val thread = case.setting("thread", name)
                val muted = case.getValue("muted").jsonPrimitive.boolean
                val expected = checkNotNull(RaftNotificationLevel.fromWire(case.string("effective"))) { "$name: effective" }
                val actual = resolveEffectiveNotificationLevel(server = server, channel = channel, thread = thread, muted = muted)
                if (actual == expected) null else "$name: expected ${expected.wireName}, got ${actual.wireName}"
            }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun everyCategoryDecisionAgrees() {
        val decisions = fixture.getValue("categoryDecisions").jsonObject
        val audiences =
            mapOf(
                "personal" to RaftNotificationAudience.Personal,
                "thread" to RaftNotificationAudience.Thread,
                "follower_task_status" to RaftNotificationAudience.FollowerTaskStatus,
                "channel" to RaftNotificationAudience.Channel,
            )
        val listed = decisions.keys - "description"
        assertEquals(audiences.keys, listed, "the fixture's audiences changed: map the new ones")
        val failures =
            audiences.flatMap { (key, audience) ->
                decisions.getValue(key).jsonObject.map { (levelWire, notifies) ->
                    val level = checkNotNull(RaftNotificationLevel.fromWire(levelWire)) { "$key: level $levelWire" }
                    val expected = notifies.jsonPrimitive.boolean
                    val actual = raftNotificationNotifies(audience, level)
                    if (actual == expected) null else "$key at $levelWire: expected $expected, got $actual"
                }.filterNotNull()
            }
        assertEquals(emptyList(), failures)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    /** null in the fixture means default (inherit), the same as an explicit "default". */
    private fun JsonObject.setting(key: String, name: String): RaftNotificationLevelSetting? {
        val value = getValue(key)
        if (value is JsonNull) return null
        return checkNotNull(RaftNotificationLevelSetting.fromWire(value.jsonPrimitive.content)) { "$name: $key" }
    }
}
