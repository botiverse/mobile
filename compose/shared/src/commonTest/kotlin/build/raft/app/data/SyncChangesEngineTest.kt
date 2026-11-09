package build.raft.app.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pull loop of `GET /api/sync/changes` (botiverse/slock 0d030e341, PR #11459 client loop). */
class SyncChangesEngineTest {
    private val preferences = SyncTestPreferences()
    private val cursors = RaftSyncCursorStore(preferences)

    private fun engine(api: RaftSyncChangesApi, sink: RaftSyncChangesSink, maxPages: Int = SYNC_CHANGES_MAX_PAGES_PER_PULL) =
        RaftSyncChangesEngine(api = api, cursors = cursors, sink = sink, maxPagesPerPull = maxPages)

    @Test
    fun `first sync reads the cursor then takes every snapshot then pulls from that cursor`() = runTest {
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.10.100")),
                syncPage(syncChangesBody("c1.12.100", changes = listOf(syncDelete(11, "agent", "a-1")))),
            )
        val sink = RecordingSyncChangesSink()

        val result = engine(api, sink).pull(syncTestScope, trigger = "test")

        assertEquals(listOf(null, "c1.10.100"), api.sinces)
        assertEquals(listOf("snapshots:first_sync", "apply"), sink.log)
        assertEquals(RaftSyncSnapshot.All, sink.snapshotReloads.single().first)
        assertEquals(setOf("a-1"), sink.plans.single().deletesOf(RaftSyncRowEntity.Agent))
        assertEquals("c1.12.100", cursors.read(syncTestScope))
        assertEquals(RaftSyncPullResult.Synced(pages = 1, changes = 1, firstSync = true, resetReason = null, truncated = false), result)
    }

    @Test
    fun `a reconnect pull waits the jitter before its snapshots but not before its changes`() = runTest {
        val log = mutableListOf<String>()
        val sink = RecordingSyncChangesSink(calls = log)
        val engine =
            RaftSyncChangesEngine(
                api =
                    ScriptedSyncChangesApi(
                        syncPage(syncChangesBody("c1.10.100")),
                        syncPage(syncChangesBody("c1.11.100", changes = listOf(syncDelete(11, "agent", "a-1")))),
                        syncPage(syncChangesBody("c1.12.100", changes = listOf(syncDelete(12, "agent", "a-2")))),
                    ),
                cursors = cursors,
                sink = sink,
                snapshotJitterDelay = { log += "jitter" },
            )

        engine.pull(syncTestScope, trigger = "reconnect", jitterSnapshots = true)
        assertEquals(listOf("jitter", "snapshots:first_sync", "apply"), log)

        log.clear()
        engine.pull(syncTestScope, trigger = "reconnect", jitterSnapshots = true)
        assertEquals(listOf("apply"), log)
    }

    @Test
    fun `a reconnect pull waits the short pull jitter before its first request and others do not`() = runTest {
        val log = mutableListOf<String>()
        val api =
            object : RaftSyncChangesApi {
                val scripted =
                    ScriptedSyncChangesApi(
                        syncPage(syncChangesBody("c1.11.100")),
                        syncPage(syncChangesBody("c1.12.100")),
                        syncPage(syncChangesBody("c1.13.100")),
                    )

                override suspend fun changes(serverId: build.raft.app.model.ServerId, since: String?, limit: Int) =
                    scripted.changes(serverId, since, limit).also { log += "request:${since ?: "none"}" }
            }
        cursors.write(syncTestScope, "c1.10.100")
        val engine =
            RaftSyncChangesEngine(
                api = api,
                cursors = cursors,
                sink = RecordingSyncChangesSink(calls = log),
                snapshotJitterDelay = { log += "snapshot_jitter" },
                pullJitterDelay = { log += "pull_jitter" },
            )

        engine.pull(syncTestScope, trigger = "reconnect", jitterSnapshots = true, jitterPull = true)
        assertEquals(listOf("pull_jitter", "request:c1.10.100"), log)

        log.clear()
        engine.pull(syncTestScope, trigger = "foreground")
        engine.pull(syncTestScope, trigger = "server_selected")
        assertEquals(listOf("request:c1.11.100", "request:c1.12.100"), log)
    }

    @Test
    fun `the reconnect pull jitter is uniform up to two seconds`() {
        val random = kotlin.random.Random(7)
        val delays = List(500) { reconnectRefetchJitterMillis(random, SYNC_CHANGES_RECONNECT_PULL_JITTER_MAX_MILLIS) }
        assertTrue(delays.all { it in 0L..2_000L })
        assertTrue(delays.any { it < 500L } && delays.any { it > 1_500L })
    }

    @Test
    fun `one request past five seconds keeps the cursor and asks for no full refetch`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api = RaftSyncChangesApi { _, _, _ -> kotlinx.coroutines.awaitCancellation() }
        val engine = RaftSyncChangesEngine(api = api, cursors = cursors, sink = RecordingSyncChangesSink())

        val result = assertIs<RaftSyncPullResult.Failed>(engine.pull(syncTestScope, trigger = "reconnect"))

        assertEquals("timeout", result.code)
        assertTrue(result.cursorKept)
        // #proj-mobile:#112: a first failure (a deploy) must not turn into a full reload either.
        assertFalse(result.needsFullRefetchFallback)
        assertEquals(SYNC_CHANGES_REQUEST_TIMEOUT_MILLIS, testScheduler.currentTime)
        assertEquals("c1.1.1", cursors.read(syncTestScope))
        assertEquals(1, cursors.failures(syncTestScope))
        // A pull whose scope is gone (signed out or server switched) has nothing to fall back to.
        assertTrue(!RaftSyncPullResult.Failed(RaftSyncPullResult.Failed.SCOPE_RETIRED).needsFullRefetchFallback)
    }

    @Test
    fun `two failures in a row on the same cursor forget it and ask for the fallback`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        var calls = 0
        val api =
            RaftSyncChangesApi { _, _, _ ->
                calls += 1
                if (calls == 1) kotlinx.coroutines.awaitCancellation() else RaftSyncChangesFetch.Failed("http_0")
            }
        val engine = RaftSyncChangesEngine(api = api, cursors = cursors, sink = RecordingSyncChangesSink())

        assertTrue(assertIs<RaftSyncPullResult.Failed>(engine.pull(syncTestScope, trigger = "reconnect")).cursorKept)
        val second = assertIs<RaftSyncPullResult.Failed>(engine.pull(syncTestScope, trigger = "foreground"))

        assertEquals(RaftSyncPullResult.Failed("http_0"), second)
        assertTrue(second.needsFullRefetchFallback)
        assertNull(cursors.read(syncTestScope))
        assertEquals(0, cursors.failures(syncTestScope))
    }

    @Test
    fun `a successful pull between two failures resets the count`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(
                RaftSyncChangesFetch.Failed("http_503"),
                // Same cursor back (nothing changed): the success alone resets the count.
                syncPage(syncChangesBody("c1.1.1")),
                RaftSyncChangesFetch.Failed("http_502"),
            )
        val engine = engine(api, RecordingSyncChangesSink())

        assertTrue(assertIs<RaftSyncPullResult.Failed>(engine.pull(syncTestScope, trigger = "foreground")).cursorKept)
        assertIs<RaftSyncPullResult.Synced>(engine.pull(syncTestScope, trigger = "foreground"))
        assertEquals(0, cursors.failures(syncTestScope))
        val again = assertIs<RaftSyncPullResult.Failed>(engine.pull(syncTestScope, trigger = "foreground"))

        assertTrue(again.cursorKept)
        assertFalse(again.needsFullRefetchFallback)
        assertEquals("c1.1.1", cursors.read(syncTestScope))
        assertEquals(1, cursors.failures(syncTestScope))
    }

    @Test
    fun `the failure count survives a cursor store reload`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val first = engine(ScriptedSyncChangesApi(RaftSyncChangesFetch.Failed("http_500")), RecordingSyncChangesSink())
        assertTrue(assertIs<RaftSyncPullResult.Failed>(first.pull(syncTestScope, trigger = "foreground")).cursorKept)

        // An app restart: a new store over the same preferences and a new engine.
        val reloaded = RaftSyncCursorStore(preferences)
        assertEquals(1, reloaded.failures(syncTestScope))
        val restarted =
            RaftSyncChangesEngine(
                api = ScriptedSyncChangesApi(RaftSyncChangesFetch.Failed("timeout")),
                cursors = reloaded,
                sink = RecordingSyncChangesSink(),
            )
        val second = assertIs<RaftSyncPullResult.Failed>(restarted.pull(syncTestScope, trigger = "foreground"))

        assertTrue(second.needsFullRefetchFallback)
        assertNull(reloaded.read(syncTestScope))
    }

    @Test
    fun `an invalid cursor is forgotten at once and a failure other than network 5xx or timeout too`() = runTest {
        cursors.write(syncTestScope, "c0.bogus")
        val invalid =
            engine(
                ScriptedSyncChangesApi(RaftSyncChangesFetch.InvalidCursor, RaftSyncChangesFetch.InvalidCursor),
                RecordingSyncChangesSink(),
            ).pull(syncTestScope, trigger = "foreground")

        assertEquals(RaftSyncPullResult.Failed("invalid_cursor"), invalid)
        assertTrue(assertIs<RaftSyncPullResult.Failed>(invalid).needsFullRefetchFallback)
        assertNull(cursors.read(syncTestScope))

        cursors.write(syncTestScope, "c1.1.1")
        val unauthorized =
            engine(ScriptedSyncChangesApi(RaftSyncChangesFetch.Failed("http_401")), RecordingSyncChangesSink())
                .pull(syncTestScope, trigger = "foreground")

        assertEquals(RaftSyncPullResult.Failed("http_401"), unauthorized)
        assertNull(cursors.read(syncTestScope))
    }

    @Test
    fun `no answer timeouts 5xx 408 and 429 are the failures that count against the cursor`() {
        listOf("timeout", "http_0", "http_408", "http_429", "http_500", "http_503", "http_599")
            .forEach { assertTrue(isTransientSyncChangesFailure(it), it) }
        listOf(
            "http_400", "http_401", "http_403", "http_404", "http_409", "empty_body", "contract_mismatch",
            "snapshot_failed", "invalid_cursor", "http_x", "exception_IllegalStateException",
        ).forEach { assertFalse(isTransientSyncChangesFailure(it), it) }
    }

    @Test
    fun `a 408 or 429 keeps the cursor and asks for no fallback`() = runTest {
        listOf("http_408", "http_429").forEach { code ->
            cursors.write(syncTestScope, "c1.1.1")
            val result = engine(ScriptedSyncChangesApi(RaftSyncChangesFetch.Failed(code)), RecordingSyncChangesSink())
                .pull(syncTestScope, trigger = "foreground")

            assertEquals(RaftSyncPullResult.Failed(code, cursorKept = true), result, code)
            assertFalse(assertIs<RaftSyncPullResult.Failed>(result).needsFullRefetchFallback, code)
            assertEquals("c1.1.1", cursors.read(syncTestScope), code)
        }
    }

    @Test
    fun `a 401 403 400 or malformed answer of sync changes forgets the cursor at once`() = runTest {
        // 403 here is the sync/changes call itself; a snapshot list's 403 is "not readable, skip"
        // (SyncChangesStoreRouterTest, #4473) and keeps the cursor.
        val answers =
            listOf(
                RaftSyncChangesFetch.Failed("http_401"),
                RaftSyncChangesFetch.Failed("http_403"),
                RaftSyncChangesFetch.Failed("http_400"),
                syncChangesFetchOf(HomeNetworkResponse(code = 200, bodyText = "{not json")),
                syncChangesFetchOf(HomeNetworkResponse(code = 200, bodyText = "")),
            )
        answers.forEach { answer ->
            cursors.write(syncTestScope, "c1.1.1")
            val result = assertIs<RaftSyncPullResult.Failed>(
                engine(ScriptedSyncChangesApi(answer), RecordingSyncChangesSink()).pull(syncTestScope, trigger = "foreground"),
            )

            assertFalse(result.cursorKept, result.code)
            assertTrue(result.needsFullRefetchFallback, result.code)
            assertNull(cursors.read(syncTestScope), result.code)
        }
    }

    @Test
    fun `a multi page pull stores its cursor only once the whole pull is done`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val stored = mutableListOf<String?>()
        val sink =
            object : RaftSyncChangesSink {
                override suspend fun reloadSnapshots(scope: RaftSyncScope, snapshots: Set<RaftSyncSnapshot>, reason: String) = true

                override suspend fun apply(scope: RaftSyncScope, plan: RaftSyncApplyPlan) {
                    stored += cursors.read(scope)
                }
            }
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.2.1", hasMore = true, changes = listOf(syncDelete(2, "agent", "a-1")))),
                syncPage(syncChangesBody("c1.3.1", hasMore = true, changes = listOf(syncDelete(3, "agent", "a-2")))),
                RaftSyncChangesFetch.Failed("http_503"), // page 3 of 5
                // The next pull asks for pages 1 and 2 again, then finishes.
                syncPage(syncChangesBody("c1.2.1", hasMore = true, changes = listOf(syncDelete(2, "agent", "a-1")))),
                syncPage(syncChangesBody("c1.3.1", hasMore = true, changes = listOf(syncDelete(3, "agent", "a-2")))),
                syncPage(syncChangesBody("c1.4.1", hasMore = true, changes = listOf(syncDelete(4, "agent", "a-3")))),
                syncPage(syncChangesBody("c1.5.1", changes = listOf(syncDelete(5, "agent", "a-4")))),
            )
        val engine = engine(api, sink)

        val failed = assertIs<RaftSyncPullResult.Failed>(engine.pull(syncTestScope, trigger = "foreground"))
        assertTrue(failed.cursorKept)
        // Pages 1-2 were applied but not stored: the kept cursor still covers them.
        assertEquals("c1.1.1", cursors.read(syncTestScope))
        assertEquals(1, cursors.failures(syncTestScope))

        val synced = assertIs<RaftSyncPullResult.Synced>(engine.pull(syncTestScope, trigger = "foreground"))
        assertEquals(4, synced.pages)
        assertEquals(listOf<String?>("c1.1.1", "c1.2.1", "c1.3.1", "c1.1.1", "c1.2.1", "c1.3.1", "c1.4.1"), api.sinces)
        // Every page was applied while the stored cursor was still the one the pull started from.
        assertEquals(List<String?>(6) { "c1.1.1" }, stored)
        assertEquals("c1.5.1", cursors.read(syncTestScope))
        assertEquals(0, cursors.failures(syncTestScope))
    }

    @Test
    fun `a page failing after a first sync keeps the cursor the snapshots were taken at`() = runTest {
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.10.100")),
                RaftSyncChangesFetch.Failed("timeout"),
            )

        val result = assertIs<RaftSyncPullResult.Failed>(engine(api, RecordingSyncChangesSink()).pull(syncTestScope, trigger = "server_selected"))

        assertTrue(result.cursorKept)
        assertEquals("c1.10.100", cursors.read(syncTestScope))
    }

    @Test
    fun `reset snapshots wait the jitter whatever the trigger and a first sync only on reconnect`() = runTest {
        val log = mutableListOf<String>()
        val engine =
            RaftSyncChangesEngine(
                api =
                    ScriptedSyncChangesApi(
                        syncPage(syncChangesBody("c1.10.100")),
                        syncPage(syncChangesBody("c1.11.100")),
                        syncPage(syncChangesBody("c1.90.500", reset = """{"reason":"forced","snapshots":["agents"]}""")),
                    ),
                cursors = cursors,
                sink = RecordingSyncChangesSink(calls = log),
                snapshotJitterDelay = { log += "jitter" },
            )

        engine.pull(syncTestScope, trigger = "server_selected")
        assertEquals(listOf("snapshots:first_sync"), log)

        log.clear()
        engine.pull(syncTestScope, trigger = "foreground")
        assertEquals(listOf("jitter", "snapshots:reset_forced"), log)
    }

    @Test
    fun `switching the selected server stops the previous server pull between pages`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        var selectedServer = syncTestScope.serverId
        val sink =
            object : RaftSyncChangesSink {
                val applied = mutableListOf<RaftSyncApplyPlan>()

                override suspend fun reloadSnapshots(scope: RaftSyncScope, snapshots: Set<RaftSyncSnapshot>, reason: String) = true

                override suspend fun apply(scope: RaftSyncScope, plan: RaftSyncApplyPlan) {
                    applied += plan
                    selectedServer = "server-2" // the user switched while page 1 was applied
                }
            }
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.2.1", hasMore = true, changes = listOf(syncDelete(2, "agent", "a-1")))),
                syncPage(syncChangesBody("c1.3.1", changes = listOf(syncDelete(3, "agent", "a-2")))),
            )
        val engine =
            RaftSyncChangesEngine(api = api, cursors = cursors, sink = sink, isCurrent = { it.serverId == selectedServer })

        assertEquals(RaftSyncPullResult.Failed("scope_retired"), engine.pull(syncTestScope, trigger = "reconnect"))
        assertEquals(1, sink.applied.size)
        assertEquals(listOf<String?>("c1.1.1"), api.sinces)
        assertEquals("c1.1.1", cursors.read(syncTestScope))
    }

    @Test
    fun `a failed first snapshot keeps no cursor so the next pull starts over`() = runTest {
        val api = ScriptedSyncChangesApi(syncPage(syncChangesBody("c1.10.100")))
        val sink = RecordingSyncChangesSink(snapshotsSucceed = false)

        val result = engine(api, sink).pull(syncTestScope, trigger = "test")

        assertEquals(RaftSyncPullResult.Failed("snapshot_failed"), result)
        assertNull(cursors.read(syncTestScope))
    }

    @Test
    fun `hasMore pulls again at once with each page cursor`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.2.1.k", hasMore = true, changes = listOf(syncDelete(2, "machine", "m-1")))),
                syncPage(syncChangesBody("c1.3.1.k", hasMore = true, changes = listOf(syncDelete(3, "machine", "m-2")))),
                syncPage(syncChangesBody("c1.3.2")),
            )
        val sink = RecordingSyncChangesSink()

        val result = engine(api, sink).pull(syncTestScope, trigger = "test")

        assertEquals(listOf<String?>("c1.1.1", "c1.2.1.k", "c1.3.1.k"), api.sinces)
        assertEquals(2, sink.plans.size)
        assertEquals("c1.3.2", cursors.read(syncTestScope))
        assertEquals(3, assertIs<RaftSyncPullResult.Synced>(result).pages)
    }

    @Test
    fun `a pull stops at the page cap and keeps its cursor for the next trigger`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.2.1.k", hasMore = true)),
                syncPage(syncChangesBody("c1.3.1.k", hasMore = true)),
            )

        val result = engine(api, RecordingSyncChangesSink(), maxPages = 2).pull(syncTestScope, trigger = "test")

        val synced = assertIs<RaftSyncPullResult.Synced>(result)
        assertEquals(true, synced.truncated)
        assertTrue(synced.cursorAdvanced)
        // The provider goes on in a follow-up pull.
        assertTrue(synced.needsFollowUp)
        assertEquals("c1.3.1.k", cursors.read(syncTestScope))
    }

    @Test
    fun `a capped pull that left the cursor where it was asks for no follow-up`() = runTest {
        // A server answering hasMore with the cursor it was given (#proj-mobile:#112 should-fix a):
        // without the stop the follow-up pulls would chain forever, 40 requests each.
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.1.1", hasMore = true)),
                syncPage(syncChangesBody("c1.1.1", hasMore = true)),
            )

        val result = assertIs<RaftSyncPullResult.Synced>(engine(api, RecordingSyncChangesSink(), maxPages = 2).pull(syncTestScope, trigger = "test"))

        assertTrue(result.truncated)
        assertFalse(result.cursorAdvanced)
        assertFalse(result.needsFollowUp)
        assertEquals("c1.1.1", cursors.read(syncTestScope))
        // A first sync that stops at the cap did move the cursor (none before): it goes on.
        preferences.values.clear()
        val first =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.5.1")),
                syncPage(syncChangesBody("c1.5.1", hasMore = true)),
            )
        assertTrue(assertIs<RaftSyncPullResult.Synced>(engine(first, RecordingSyncChangesSink(), maxPages = 1).pull(syncTestScope, trigger = "test")).needsFollowUp)
    }

    @Test
    fun `a pull from a cursor first retakes what earlier reloads failed and a first sync does not`() = runTest {
        val log = mutableListOf<String>()
        val sink =
            object : RaftSyncChangesSink by RecordingSyncChangesSink(calls = log) {
                override suspend fun retakeFailedReloads(scope: RaftSyncScope) {
                    log += "retake"
                }
            }
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.10.100")),
                syncPage(syncChangesBody("c1.11.100")),
                syncPage(syncChangesBody("c1.12.100", changes = listOf(syncDelete(12, "agent", "a-1")))),
            )
        val engine = engine(api, sink)

        engine.pull(syncTestScope, trigger = "server_selected")
        assertEquals(listOf("snapshots:first_sync"), log)

        log.clear()
        engine.pull(syncTestScope, trigger = "foreground")
        assertEquals(listOf("retake", "apply"), log)
    }

    @Test
    fun `reset retakes the named snapshots and continues from the returned cursor`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.90.500", reset = """{"reason":"too_old","snapshots":["channels","agents"]}""")),
            )
        val sink = RecordingSyncChangesSink()

        val result = engine(api, sink).pull(syncTestScope, trigger = "test")

        assertEquals(listOf(setOf(RaftSyncSnapshot.Channels, RaftSyncSnapshot.Agents) to "reset_too_old"), sink.snapshotReloads)
        assertTrue(sink.plans.isEmpty())
        assertEquals("c1.90.500", cursors.read(syncTestScope))
        assertEquals("too_old", assertIs<RaftSyncPullResult.Synced>(result).resetReason)
    }

    @Test
    fun `a reset naming a snapshot this client does not know retakes every snapshot`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(syncPage(syncChangesBody("c1.90.500", reset = """{"reason":"forced","snapshots":["reactions"]}""")))
        val sink = RecordingSyncChangesSink()

        engine(api, sink).pull(syncTestScope, trigger = "test")

        assertEquals(listOf(RaftSyncSnapshot.All to "reset_forced"), sink.snapshotReloads)
        assertEquals("c1.90.500", cursors.read(syncTestScope))
    }

    @Test
    fun `a reset whose snapshots fail drops the cursor`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(syncPage(syncChangesBody("c1.90.500", reset = """{"reason":"forced","snapshots":["agents"]}""")))

        val result = engine(api, RecordingSyncChangesSink(snapshotsSucceed = false)).pull(syncTestScope, trigger = "test")

        assertEquals(RaftSyncPullResult.Failed("snapshot_failed"), result)
        assertNull(cursors.read(syncTestScope))
    }

    @Test
    fun `an invalid cursor is forgotten and the pull starts over as a jittered first sync`() = runTest {
        cursors.write(syncTestScope, "c0.bogus")
        val api =
            ScriptedSyncChangesApi(
                RaftSyncChangesFetch.InvalidCursor,
                syncPage(syncChangesBody("c1.5.5")),
                syncPage(syncChangesBody("c1.5.6")),
            )
        val log = mutableListOf<String>()
        val sink = RecordingSyncChangesSink(calls = log)
        val engine =
            RaftSyncChangesEngine(api = api, cursors = cursors, sink = sink, snapshotJitterDelay = { log += "jitter" })

        val result = engine.pull(syncTestScope, trigger = "server_selected")

        assertEquals(listOf("c0.bogus", null, "c1.5.5"), api.sinces)
        // The server may have invalidated every cursor at once: the restart's full lists are spread.
        assertEquals(listOf("jitter", "snapshots:first_sync"), sink.log)
        assertEquals("c1.5.6", cursors.read(syncTestScope))
        assertEquals(true, assertIs<RaftSyncPullResult.Synced>(result).firstSync)
    }

    @Test
    fun `a page failing twice drops the cursor and the next pull re-bootstraps after the jitter`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        val api =
            ScriptedSyncChangesApi(
                syncPage(syncChangesBody("c1.2.1.k", hasMore = true)),
                RaftSyncChangesFetch.Failed("http_500"),
                syncPage(syncChangesBody("c1.2.1.k", hasMore = true)),
                RaftSyncChangesFetch.Failed("http_500"),
                syncPage(syncChangesBody("c1.9.1")),
                syncPage(syncChangesBody("c1.9.2")),
            )
        val log = mutableListOf<String>()
        val engine =
            RaftSyncChangesEngine(
                api = api,
                cursors = cursors,
                sink = RecordingSyncChangesSink(calls = log),
                snapshotJitterDelay = { log += "jitter" },
            )

        // Page 2 fails: the pull did not finish, so the cursor from before page 1 stays (first failure).
        assertEquals(RaftSyncPullResult.Failed("http_500", cursorKept = true), engine.pull(syncTestScope, trigger = "foreground"))
        assertEquals("c1.1.1", cursors.read(syncTestScope))
        assertEquals(RaftSyncPullResult.Failed("http_500"), engine.pull(syncTestScope, trigger = "foreground"))
        assertNull(cursors.read(syncTestScope))

        // A cursor that keeps failing is not retried forever: the next pull starts over, spread out.
        val next = engine.pull(syncTestScope, trigger = "foreground")
        assertEquals(true, assertIs<RaftSyncPullResult.Synced>(next).firstSync)
        assertEquals(listOf("c1.1.1", "c1.2.1.k", "c1.1.1", "c1.2.1.k", null, "c1.9.1"), api.sinces)
        assertEquals(listOf("jitter", "snapshots:first_sync"), log)
        assertEquals("c1.9.2", cursors.read(syncTestScope))
    }

    @Test
    fun `a pull for a retired binding applies and stores nothing`() = runTest {
        cursors.write(syncTestScope, "c1.1.1")
        var current = true
        val api =
            object : RaftSyncChangesApi {
                override suspend fun changes(serverId: build.raft.app.model.ServerId, since: String?, limit: Int): RaftSyncChangesFetch {
                    current = false // signed out while the request was in flight
                    return syncPage(syncChangesBody("c1.2.1", changes = listOf(syncDelete(2, "agent", "a-1"))))
                }
            }
        val sink = RecordingSyncChangesSink()
        val engine = RaftSyncChangesEngine(api = api, cursors = cursors, sink = sink, isCurrent = { current })

        assertEquals(RaftSyncPullResult.Failed("scope_retired"), engine.pull(syncTestScope, trigger = "test"))
        assertTrue(sink.log.isEmpty())
        assertEquals("c1.1.1", cursors.read(syncTestScope))
    }

    @Test
    fun `a page coalesces to the newest change per entity and id`() {
        val plan =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.9.1",
                    changes =
                        listOf(
                            syncUpsert(1, "agent", "a-1", """{"id":"a-1","name":"old"}"""),
                            syncDelete(2, "agent", "a-2"),
                            syncUpsert(3, "agent", "a-1", """{"id":"a-1","name":"new"}"""),
                            syncUpsert(4, "agent", "a-2", """{"id":"a-2","name":"back"}"""),
                            syncDelete(5, "machine", "m-1"),
                            syncStale(6, "ch-1", 7),
                            syncStale(7, "ch-1", null),
                            syncStale(8, "ch-1", 5),
                            """{"seq":9,"entity":"sidebar_order","id":"server-1","op":"rebuilt"}""",
                        ),
                )
            )!!.changes.toSyncApplyPlan()

        assertEquals(listOf("a-1", "a-2"), plan.upsertsOf(RaftSyncRowEntity.Agent).keys.toList())
        assertEquals(JsonPrimitive("new"), plan.upsertsOf(RaftSyncRowEntity.Agent).getValue("a-1")["name"])
        assertTrue(plan.deletesOf(RaftSyncRowEntity.Agent).isEmpty())
        assertEquals(setOf("m-1"), plan.deletesOf(RaftSyncRowEntity.Machine))
        assertEquals(mapOf<String, Long?>("ch-1" to 7L), plan.staleConversations)
        assertEquals(setOf(RaftSyncSnapshot.SidebarOrder), plan.snapshots)
    }

    @Test
    fun `a removed scope drops the changes made under it before the removal`() {
        val plan =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.9.1",
                    changes =
                        listOf(
                            syncUpsert(1, "channel", "room-1", """{"id":"room-1"}"""),
                            syncUpsert(2, "channel_member", "room-1:user:u-2", """{"id":"u-2"}"""),
                            syncStale(3, "room-1", 4),
                            syncUpsert(4, "channel", "room-2", """{"id":"room-2"}"""),
                            syncScope(5, "channel", "room-1", "removed", syncChannelScopeSnapshots),
                            syncScope(6, "channel", "room-3", "added", syncChannelScopeSnapshots),
                        ),
                )
            )!!.changes.toSyncApplyPlan()

        assertEquals(setOf("room-2"), plan.upsertsOf(RaftSyncRowEntity.Channel).keys)
        assertTrue(plan.upsertsOf(RaftSyncRowEntity.ChannelMember).isEmpty())
        assertTrue(plan.staleConversations.isEmpty())
        assertEquals(listOf(RaftSyncChange.ScopeRemoved(5, RaftSyncScopeKind.Channel, "room-1")), plan.removedScopes)
        assertEquals(setOf("room-3"), plan.addedChannelIds)
        assertEquals(
            setOf(RaftSyncSnapshot.Channels, RaftSyncSnapshot.ChannelMembers, RaftSyncSnapshot.Inbox, RaftSyncSnapshot.Unread),
            plan.snapshots,
        )

        val serverGone =
            parseSyncChangesPage(
                syncChangesBody(
                    cursor = "c1.9.1",
                    changes = listOf(syncDelete(1, "agent", "a-1"), syncScope(2, "server", "server-1", "removed", syncAllSnapshotNames)),
                )
            )!!.changes.toSyncApplyPlan()
        assertTrue(serverGone.deletes.isEmpty())
        assertEquals(RaftSyncScopeKind.Server, serverGone.removedScopes.single().kind)
    }
}
