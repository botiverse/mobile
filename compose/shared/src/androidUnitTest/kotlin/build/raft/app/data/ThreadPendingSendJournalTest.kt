package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadPendingSendContext
import build.raft.app.ReplyCacheProvenance
import build.raft.app.thread.ThreadMessageDeliveryFailed
import build.raft.app.thread.ThreadMessageDeliverySending
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.ThreadRouteIdentity
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.model.ConversationKind
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.threadPendingSendOwnerScopeId
import build.raft.app.shared.data.local.RaftDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** JDBC coverage for the client-owned pending-send journal and its identity fences. */
class ThreadPendingSendJournalTest {
    @Test
    fun secondJournalInstanceRestoresOnlyTheExactIdentityAfterProcessRebuild() {
        withDatabase { path ->
            val provenance = provenance()
            val route = route()
            val message = message("local-send-1", "random-1")

            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                assertTrue(journal.persistSending(provenance, route, message))
                assertEquals(listOf(message), journal.restore(provenance, route))
            }

            JdbcSqliteDriver(url(path)).use { driver ->
                val restarted = journal(driver)
                assertEquals(listOf(message), restarted.restore(provenance, route))
                assertTrue(restarted.restore(provenance.copy(environmentId = "env-b"), route).isEmpty())
                assertTrue(restarted.restore(provenance.copy(principalId = "principal-b"), route).isEmpty())
                assertTrue(restarted.restore(provenance.copy(serverId = "server-b"), route).isEmpty())
                // Durable visibility keys on the stable identity only: generation columns
                // (selection epoch, auth generation) fence in-process writes, never whether
                // a journaled orphan can be seen after a restart or re-selection.
                assertEquals(
                    listOf(message),
                    restarted.restore(provenance.copy(selectionGeneration = 21L), route)
                )
                assertEquals(
                    listOf(message),
                    restarted.restore(provenance.copy(authGeneration = 11L), route)
                )
                assertTrue(restarted.restore(provenance, route(serverId = "channel-b")).isEmpty())
            }
        }
    }

    @Test
    fun drainPathAdoptsOrphansAcrossGenerationAndScopeChanges() {
        withDatabase { path ->
            val written = provenance(authGeneration = 10L, selectionGeneration = 20L)
            val route = route()
            val message = message("local-send-orphan", "random-orphan")

            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                assertTrue(journal.persistSending(written, route, message))
            }

            // Process restart + server re-selection + auth rotation: the row was written
            // under older generations, and this process cannot reproduce the old owner
            // scope. The drain path must still see, mark, and consume it (#1134 cell 3).
            JdbcSqliteDriver(url(path)).use { driver ->
                val restarted = journal(driver)
                val current = provenance(authGeneration = 11L, selectionGeneration = 21L)
                assertEquals(listOf(message), restarted.restore(current, route))
                assertTrue(
                    restarted.markFailedForDrain(
                        current,
                        route,
                        message.id,
                        "thread_send_adopted_stale",
                        null
                    )
                )
                assertEquals(
                    ThreadPendingSendJournal.ConsumeResult.Deleted,
                    restarted.consumeCanonicalRandomId(current, route, "random-orphan")
                )
                assertTrue(restarted.restore(current, route).isEmpty())
            }
        }
    }

    @Test
    fun failedRowsSurviveRebuildAndExplicitRetryReusesTheSameIdempotencyKey() {
        withDatabase { path ->
            val provenance = provenance()
            val route = route()
            val message = message("local-send-2", "random-2")

            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                assertTrue(journal.persistSending(provenance, route, message))
                assertTrue(journal.markFailed(provenance, route, message.id, "network_error", "offline"))
            }

            JdbcSqliteDriver(url(path)).use { driver ->
                val restarted = journal(driver)
                val failed = restarted.restore(provenance, route).single()
                assertEquals(ThreadMessageDeliveryFailed, failed.deliveryState)
                assertEquals("network_error", failed.pendingSendErrorCode)
                assertEquals("offline", failed.pendingSendErrorMessage)

                // A user-triggered retry changes only the durable state; it does not mint a new
                // local id or random id, so a late canonical response remains idempotent.
                assertTrue(restarted.persistSending(provenance, route, failed))
                val sending = restarted.restore(provenance, route).single()
                assertEquals(ThreadMessageDeliverySending, sending.deliveryState)
                assertEquals(message.id, sending.id)
                assertEquals(message.pendingSendContext?.randomId, sending.pendingSendContext?.randomId)
                assertEquals(null, sending.pendingSendErrorCode)
            }
        }
    }

    @Test
    fun canonicalRandomIdConsumptionIsExactAndIdempotent() {
        withDatabase { path ->
            val provenance = provenance()
            val route = route()
            val message = message("local-send-3", "random-3")
            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                assertTrue(journal.persistSending(provenance, route, message))
                assertEquals(
                    ThreadPendingSendJournal.ConsumeResult.NotFound,
                    journal.consumeCanonicalRandomId(provenance, route, "wrong-random")
                )
                assertEquals(
                    ThreadPendingSendJournal.ConsumeResult.NotFound,
                    journal.consumeCanonicalRandomId(provenance, route(serverId = "channel-b"), message.pendingSendContext!!.randomId!!)
                )
                assertEquals(
                    ThreadPendingSendJournal.ConsumeResult.Deleted,
                    journal.consumeCanonicalRandomId(provenance, route, message.pendingSendContext!!.randomId!!)
                )
                assertEquals(
                    ThreadPendingSendJournal.ConsumeResult.NotFound,
                    journal.consumeCanonicalRandomId(provenance, route, message.pendingSendContext!!.randomId!!)
                )
                assertTrue(journal.restore(provenance, route).isEmpty())
            }
        }
    }

    // task #1134: same-binding token rotation (401 -> silent refresh, authGeneration+1) rehomes
    // journaled rows into the successor partition instead of deleting them, so the drainer can
    // still adopt and resend with the same randomId after a reconnect-triggered refresh.
    @Test
    fun rehomeAuthRevisionMovesRowsIntoSuccessorPartitionKeepingRandomIdAndFailureState() {
        withDatabase { path ->
            val route = route()
            val old = provenance(authGeneration = 10L)
            val successor = provenance(authGeneration = 11L)
            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                assertTrue(journal.persistSending(old, route, message("local-send-old", "random-old")))
                assertTrue(journal.markFailed(old, route, "local-send-old", "network_unavailable", "offline"))

                assertEquals(
                    1,
                    journal.rehomeAuthRevision(
                        environmentId = old.environmentId,
                        principalId = old.principalId,
                        fromAuthGeneration = 10L,
                        toAuthGeneration = 11L,
                    ),
                )

                assertEquals(0L, countRowsAtAuthGeneration(driver, old, 10L))
                assertEquals(1L, countRowsAtAuthGeneration(driver, successor, 11L))
                val restored = journal.restore(successor, route)
                assertEquals(1, restored.size)
                assertEquals("local-send-old", restored.single().id)
                assertEquals("random-old", restored.single().pendingSendContext?.randomId)
                assertEquals(ThreadMessageDeliveryFailed, restored.single().deliveryState)
                assertEquals("network_unavailable", restored.single().pendingSendErrorCode)
                // The rehomed row remains terminally consumable under the successor partition.
                assertEquals(
                    ThreadPendingSendJournal.ConsumeResult.Deleted,
                    journal.consumeCanonicalRandomId(successor, route, "random-old"),
                )
            }
        }
    }

    @Test
    fun coldStartCleanupRemovesOnlyOlderGenerationsAndPreservesCurrentAndFutureRows() {
        withDatabase { path ->
            val route = route()
            val old = provenance(authGeneration = 10L)
            val current = provenance(authGeneration = 11L)
            val future = provenance(authGeneration = 12L)
            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                assertTrue(journal.persistSending(old, route, message("local-send-old-cold", "random-old-cold")))
                assertTrue(journal.persistSending(current, route, message("local-send-current-cold", "random-current-cold")))
                assertTrue(journal.persistSending(future, route, message("local-send-future-cold", "random-future-cold")))

                // This models a newly-created process observing auth generation 11 without an
                // in-memory predecessor.  Only rows strictly older than the observed revision
                // are stale; retaining a future row is the safety fence against a successor
                // login racing a late cleanup from another process.
                assertTrue(
                    journal.clearForPrincipalBeforeAuthRevision(
                        environmentId = current.environmentId,
                        principalId = current.principalId,
                        authGeneration = current.authGeneration,
                    )
                )

                assertEquals(0L, countRowsAtAuthGeneration(driver, old, 10L))
                assertEquals(1L, countRowsAtAuthGeneration(driver, current, 11L))
                assertEquals(1L, countRowsAtAuthGeneration(driver, future, 12L))
                // restore() intentionally sees every generation of the stable identity.
                assertEquals(2, journal.restore(current, route).size)
            }
        }
    }

    @Test
    fun corruptRowsAreRejectedWithoutAffectingValidRowsOrRestoreOrder() {
        withDatabase { path ->
            val provenance = provenance()
            val route = route()
            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                val valid = message("local-send-valid", "random-valid")
                assertTrue(journal.persistSending(provenance, route, valid))

                val json = Json { encodeDefaults = true }
                val context = valid.pendingSendContext ?: error("test context missing")
                val messageJson = json.encodeToString(valid)
                val contextJson = json.encodeToString(context)
                insertRaw(
                    driver = driver,
                    provenance = provenance,
                    route = route,
                    localId = "local-send-malformed-json",
                    randomId = "random-malformed-json",
                    messageJson = "{",
                    pendingContextJson = contextJson,
                    createdAtMs = 1L,
                )
                insertRaw(
                    driver = driver,
                    provenance = provenance,
                    route = route,
                    localId = "local-send-mismatched-context",
                    randomId = "random-mismatched-context",
                    messageJson = messageJson.replace("local-send-valid", "local-send-mismatched-context"),
                    pendingContextJson = contextJson.replace("random-valid", "random-context-other"),
                    createdAtMs = 2L,
                )
                insertRaw(
                    driver = driver,
                    provenance = provenance,
                    route = route,
                    localId = "local-send-mismatched-row-random",
                    randomId = "random-row-other",
                    messageJson = messageJson.replace("local-send-valid", "local-send-mismatched-row-random"),
                    pendingContextJson = contextJson.replace("random-valid", "random-context-row"),
                    createdAtMs = 3L,
                )
                insertRaw(
                    driver = driver,
                    provenance = provenance,
                    route = route.copy(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        channelKind = null,
                        parentConversationKind = ConversationKind.Channel,
                        instanceKey = "reply-stream:malformed-route",
                        timelineKey = "malformed-route",
                        parentKey = "parent-channel:parent-message",
                        canRenderTimelineCandidates = true,
                    ),
                    localId = "local-send-invalid-route-kind",
                    randomId = "random-invalid-route-kind",
                    messageJson = messageJson.replace("local-send-valid", "local-send-invalid-route-kind"),
                    pendingContextJson = contextJson.replace("random-valid", "random-invalid-route-kind"),
                    createdAtMs = 4L,
                    routeParentConversationKind = "not-a-conversation-kind",
                )

                assertEquals(listOf(valid), journal.restore(provenance, route))
            }
        }
    }

    @Test
    fun unresolvedReplyParentRestoresAfterStreamResolutionWithTheSameTypedParent() {
        withDatabase { path ->
            val provenance = provenance()
            val unresolved = resolveThreadRouteIdentity(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = null,
                instanceId = "reply-parent:channel-a:parent-a",
                sourceThreadId = "channel-a",
                streamChannelId = "channel-a",
                parentChannelId = "channel-a",
                parentMessageId = "parent-a",
                parentConversationKind = ConversationKind.Channel,
            )
            val resolved = resolveThreadRouteIdentity(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = null,
                instanceId = "reply-stream:thread-a",
                sourceThreadId = "thread-a",
                streamChannelId = "thread-a",
                parentChannelId = "channel-a",
                parentMessageId = "parent-a",
                parentConversationKind = ConversationKind.Channel,
            )
            val pending = message("local-send-reply", "random-reply").copy(
                pendingSendContext = message("local-send-reply", "random-reply").pendingSendContext?.copy(
                    channelId = "thread-a",
                    streamChannelId = "thread-a",
                    parentMessageId = "parent-a",
                ),
            )
            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                assertTrue(journal.persistSending(provenance, unresolved, pending))
                assertEquals(listOf(pending), journal.restore(provenance, resolved))
                assertTrue(
                    journal.restore(
                        provenance,
                        resolved.copy(parentKey = "channel-a:parent-other"),
                    ).isEmpty()
                )
                assertTrue(
                    journal.restore(
                        provenance,
                        resolved.copy(parentConversationKind = ConversationKind.DirectMessage),
                    ).isEmpty()
                )
            }
        }
    }

    private fun journal(driver: JdbcSqliteDriver): ThreadPendingSendJournal {
        val version =
            driver.executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0) ?: 0L)
                },
                parameters = 0,
                binders = null,
            ).value
        if (version == 0L) {
            RaftDatabase.Schema.create(driver).value
            // SQLDelight's Schema.create intentionally creates objects but does not own the
            // platform user_version marker.  The real preflight/migration layer writes it;
            // mirror that boundary here so a second JDBC connection represents a restarted
            // process instead of trying to create the schema a second time.
            driver.execute(
                identifier = null,
                sql = "PRAGMA user_version = ${RaftDatabase.Schema.version}",
                parameters = 0,
                binders = null,
            )
        }
        return ThreadPendingSendJournal(RaftSqlDatabase(driver), nowMillis = { 123L })
    }

    private fun insertRaw(
        driver: JdbcSqliteDriver,
        provenance: ReplyCacheProvenance,
        route: ThreadRouteIdentity,
        localId: String,
        randomId: String,
        messageJson: String,
        pendingContextJson: String,
        createdAtMs: Long,
        routeParentConversationKind: String? = route.parentConversationKind?.name,
    ) {
        val ownerScopeId = requireNotNull(provenance.threadPendingSendOwnerScopeId)
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO threadPendingSendEntity(
                  environmentId, principalId, serverId, ownerScopeId,
                  authGeneration, selectionGeneration, routeInstanceKey,
                  routeSurfaceKind, routeChannelKind, routeParentConversationKind,
                  routeTimelineKey, routeParentKey, localId, randomId,
                  messageJson, pendingContextJson, status, createdAtMs,
                  lastErrorCode, lastErrorMessage
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 20,
            binders = {
                bindString(0, provenance.environmentId)
                bindString(1, provenance.principalId)
                bindString(2, provenance.serverId)
                bindString(3, ownerScopeId)
                bindLong(4, provenance.authGeneration)
                bindLong(5, provenance.selectionGeneration)
                bindString(6, route.instanceKey)
                bindString(7, route.surfaceKind)
                bindString(8, route.channelKind)
                bindString(9, routeParentConversationKind)
                bindString(10, route.timelineKey)
                bindString(11, route.parentKey)
                bindString(12, localId)
                bindString(13, randomId)
                bindString(14, messageJson)
                bindString(15, pendingContextJson)
                bindString(16, "sending")
                bindLong(17, createdAtMs)
                bindString(18, null)
                bindString(19, null)
            },
        )
    }

    private fun withDatabase(block: (java.nio.file.Path) -> Unit) {
        val path = Files.createTempFile("slock-pending-send-", ".db")
        path.deleteIfExists()
        try {
            block(path)
        } finally {
            path.deleteIfExists()
        }
    }

    private fun url(path: java.nio.file.Path): String = "jdbc:sqlite:${path.toAbsolutePath()}"

    /**
     * Partition-content assertion: with durable visibility keyed on the stable identity,
     * "the old auth partition is gone" must be asserted on the authGeneration column
     * directly, not via restore() (which intentionally sees all generations).
     */
    private fun countRowsAtAuthGeneration(
        driver: JdbcSqliteDriver,
        provenance: ReplyCacheProvenance,
        authGeneration: Long,
    ): Long =
        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT COUNT(*) FROM threadPendingSendEntity
                WHERE environmentId = ? AND principalId = ? AND serverId = ? AND authGeneration = ?
            """.trimIndent(),
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 4,
            binders = {
                bindString(0, provenance.environmentId)
                bindString(1, provenance.principalId)
                bindString(2, provenance.serverId)
                bindLong(3, authGeneration)
            },
        ).value

    private fun provenance(
        environmentId: String = "env-a",
        principalId: String = "principal-a",
        serverId: String = "server-a",
        authGeneration: Long = 10L,
        selectionGeneration: Long = 20L,
    ) = ReplyCacheProvenance(
        environmentId = environmentId,
        principalId = principalId,
        serverId = serverId,
        generation = 1L,
        authGeneration = authGeneration,
        selectionGeneration = selectionGeneration,
    )

    private fun route(serverId: String = "channel-a"): ThreadRouteIdentity =
        resolveThreadRouteIdentity(
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = "channel",
            instanceId = serverId,
            sourceThreadId = serverId,
            streamChannelId = serverId,
            parentChannelId = null,
            parentMessageId = null,
        )

    private fun message(localId: String, randomId: String): RaftThreadMessageItem {
        val context =
            RaftThreadPendingSendContext(
                channelId = "channel-a",
                body = "hello",
                streamChannelId = "channel-a",
                randomId = randomId,
            )
        return RaftThreadMessageItem(
            id = localId,
            authorId = "principal-a",
            authorName = "A",
            isOwn = true,
            body = "hello",
            randomId = randomId,
            threadChannelId = "channel-a",
            deliveryState = ThreadMessageDeliverySending,
            pendingSendContext = context,
            createdAtEpochMillis = 123L,
        )
    }
}
