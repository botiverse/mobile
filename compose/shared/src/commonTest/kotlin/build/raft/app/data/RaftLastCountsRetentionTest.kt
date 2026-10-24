package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Task #164: the last-known copies (Home row counts, Activity first pages, Activity badge counts)
 * follow the web's `lastCounts.ts` limits (botiverse/slock #11156 da23419e): nothing older than
 * 7 days is shown, at most 5 servers per user are kept (least recently saved first), other users'
 * copies are never touched, and sign-out removes the whole family.
 */
class RaftLastCountsRetentionTest {
    private var now = 1_792_835_580_000L

    @Test
    fun aCopyOlderThanSevenDaysReadsAsAbsentAndIsRemoved() {
        val store = InMemoryRaftKeyValueStore()
        val storage = SharedActivityPersistStorage(store) { now }
        storage.write(key("u1", "s1"), "page")

        now += RaftLastCountsMaxAgeMillis
        assertEquals("page", storage.read(key("u1", "s1")), "exactly 7 days is still kept")
        now += 1
        assertNull(storage.read(key("u1", "s1")))
        assertNull(store.getString(key("u1", "s1")), "and removed from the device")
    }

    @Test
    fun atMostFiveServersPerUserLeastRecentlySavedGoFirstOtherUsersUntouched() {
        val store = InMemoryRaftKeyValueStore()
        val storage = SharedActivityPersistStorage(store) { now }
        storage.write(key("u2", "other"), "u2")
        (1..5).forEach { server ->
            storage.write(key("u1", "s$server", filter = "All"), "all-$server")
            storage.write(key("u1", "s$server", filter = "Unread"), "unread-$server")
            now += 1_000
        }
        storage.write(key("u1", "s1", filter = "All"), "all-1-again") // s1 is recent again
        now += 1_000

        storage.write(key("u1", "s6", filter = "All"), "all-6")

        assertNull(store.getString(key("u1", "s2", filter = "All")), "the least recently saved server goes")
        assertNull(store.getString(key("u1", "s2", filter = "Unread")), "with every key of it")
        listOf("s1", "s3", "s4", "s5", "s6").forEach { server ->
            assertNotNull(store.getString(key("u1", server, filter = "All")), server)
        }
        assertEquals("u2", store.getString(key("u2", "other")), "another user's copy stays")
    }

    @Test
    fun signOutClearsTheWholeFamilyIncludingCopiesWrittenBeforeTheIndexKeptTimes() {
        val store = InMemoryRaftKeyValueStore()
        // The task #166 index listed keys only.
        store.putString("mvi:persist:home:hints:index", """["${key("u1", "s1", prefix = "home:hints")}"]""")
        store.putString(key("u1", "s1", prefix = "home:hints"), "legacy")
        val hints = HomeRowHintPersistStorage(store) { now }
        val activity = SharedActivityPersistStorage(store) { now }
        hints.write(key("u1", "s2", prefix = "home:hints"), "new")
        activity.write(key("u1", "s1"), "page")
        store.putString("mvi:persist:home:identity:v1:user:u1:server:s1", "identity")

        hints.clearAll()
        activity.clearAll()

        assertNull(store.getString(key("u1", "s1", prefix = "home:hints")))
        assertNull(store.getString(key("u1", "s2", prefix = "home:hints")))
        assertNull(store.getString(key("u1", "s1")))
        assertEquals("identity", store.getString("mvi:persist:home:identity:v1:user:u1:server:s1"), "other families stay")
    }

    @Test
    fun theActivityBadgeCacheKeepsFiveServersPerAccountAndNothingOlderThanSevenDays() {
        val cache = ProcessActivityUnreadProjectionCache(InMemoryRaftKeyValueStore()) { now }
        fun owner(server: String, account: String = "u1") =
            ProcessActivityUnreadOwner(environmentId = "https://api.example.test", accountId = account, serverId = server)
        cache.write(owner("other", account = "u2"), totalUnreadCount = 1, totalCount = 1)
        (1..6).forEach { server ->
            cache.write(owner("s$server"), totalUnreadCount = server, totalCount = server)
            now += 1_000
        }

        assertNull(cache.read(owner("s1")), "the sixth server pushed out the least recent")
        assertEquals(6, cache.read(owner("s6"))?.totalUnreadCount)
        assertEquals(1, cache.read(owner("other", account = "u2"))?.totalUnreadCount, "another account stays")

        now += RaftLastCountsMaxAgeMillis + 1
        assertNull(cache.read(owner("s6")), "older than 7 days is not shown")
    }

    private fun key(user: String, server: String, filter: String = "All", prefix: String = "activity"): String =
        if (prefix == "activity") {
            "mvi:persist:activity:v1:user:$user:server:$server:filter:$filter"
        } else {
            "mvi:persist:$prefix:v1:user:$user:server:$server"
        }
}
