package build.raft.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins Android sign-out / account-switch WebView auth clearing (#proj-mobile:#204):
 * Chromium's `WebStorage.deleteOrigin` omits `LOCAL_STORAGE` (`aw_quota_manager_bridge.cc`
 * `DeleteOriginFramework`), so WebStorage MUST always call `deleteAllData()` even when explicit
 * Raft origins are provided, while cookies stay scoped to Raft origins to preserve third-party
 * cookie logins in [InAppBrowserPage].
 */
class RaftAndroidWebViewAuthStorageClearTest {
    @Test
    fun nonEmptyOriginsAlwaysCallsDeleteAllDataAndClearsOnlyRaftOriginCookies() = runTest {
        val events = mutableListOf<String>()
        val origins = setOf("https://app.raft.build", "https://staging.raft.build")

        clearAndroidWebViewAuthStorage(
            origins = origins,
            dropKeepAlive = { events += "drop_keep_alive" },
            webStorage =
                object : RaftAndroidWebStorageClearTarget {
                    override fun deleteAllData() {
                        events += "webstorage:deleteAllData"
                    }
                },
            cookies =
                object : RaftAndroidCookieClearTarget {
                    override suspend fun clearOrigin(origin: String) {
                        events += "cookie:clearOrigin:$origin"
                    }

                    override suspend fun clearAll() {
                        events += "cookie:clearAll"
                    }

                    override fun flush() {
                        events += "cookie:flush"
                    }
                },
        )

        assertEquals(
            listOf(
                "drop_keep_alive",
                "webstorage:deleteAllData",
                "cookie:clearOrigin:https://app.raft.build",
                "cookie:clearOrigin:https://staging.raft.build",
                "cookie:flush",
            ),
            events,
        )
    }

    @Test
    fun webStorageFailureDoesNotPreventOriginCookieClearAndFlush() = runTest {
        val events = mutableListOf<String>()

        clearAndroidWebViewAuthStorage(
            origins = setOf("https://app.raft.build"),
            dropKeepAlive = { events += "drop_keep_alive" },
            webStorage =
                object : RaftAndroidWebStorageClearTarget {
                    override fun deleteAllData() {
                        events += "webstorage:deleteAllData:throw"
                        error("simulated WebStorage unavailable")
                    }
                },
            cookies =
                object : RaftAndroidCookieClearTarget {
                    override suspend fun clearOrigin(origin: String) {
                        events += "cookie:clearOrigin:$origin"
                    }

                    override suspend fun clearAll() {
                        events += "cookie:clearAll"
                    }

                    override fun flush() {
                        events += "cookie:flush"
                    }
                },
        )

        assertEquals(
            listOf(
                "drop_keep_alive",
                "webstorage:deleteAllData:throw",
                "cookie:clearOrigin:https://app.raft.build",
                "cookie:flush",
            ),
            events,
        )
    }

    @Test
    fun emptyOriginsClearsEntireDefaultStoreIncludingAllCookies() = runTest {
        val events = mutableListOf<String>()

        clearAndroidWebViewAuthStorage(
            origins = emptySet(),
            dropKeepAlive = { events += "drop_keep_alive" },
            webStorage =
                object : RaftAndroidWebStorageClearTarget {
                    override fun deleteAllData() {
                        events += "webstorage:deleteAllData"
                    }
                },
            cookies =
                object : RaftAndroidCookieClearTarget {
                    override suspend fun clearOrigin(origin: String) {
                        events += "cookie:clearOrigin:$origin"
                    }

                    override suspend fun clearAll() {
                        events += "cookie:clearAll"
                    }

                    override fun flush() {
                        events += "cookie:flush"
                    }
                },
        )

        assertEquals(
            listOf(
                "drop_keep_alive",
                "webstorage:deleteAllData",
                "cookie:clearAll",
                "cookie:flush",
            ),
            events,
        )
    }
}
