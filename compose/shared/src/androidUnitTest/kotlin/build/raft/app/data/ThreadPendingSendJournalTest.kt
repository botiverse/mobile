package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.RaftThreadMessageItem
import build.raft.app.RaftThreadPendingSendContext
import build.raft.app.ReplyCacheProvenance
import build.raft.app.ThreadMessageDeliveryFailed
import build.raft.app.ThreadMessageDeliverySending
import build.raft.app.ThreadRouteIdentity
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.RaftThreadScreenPayload
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** JDBC coverage for the client-owned pending-send journal and its identity fences. */
class ThreadPendingSendJournalTest {
    @Test
    fun missingEnvironmentFailsClosedForEveryDurableOperationWithoutLegacyFallback() {
        withDatabase { path ->
            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                val missingEnvironment = provenance(environmentId = "")
                val route = route()
                val pending = message("local-send-no-environment", "random-no-environment")

                assertFalse(journal.persistSending(missingEnvironment, route, pending))
                assertTrue(journal.restore(missingEnvironment, route).isEmpty())
                assertFalse(
                    journal.markFailed(
                        missingEnvironment,
                        route,
                        pending.id,
                        "network_error",
                        "offline",
                    )
                )
                assertEquals(
                    ThreadPendingSendJournal.ConsumeResult.Failed,
                    journal.consume(missingEnvironment, route, pending.id),
                )
                assertEquals(
                    ThreadPendingSendJournal.ConsumeResult.Failed,
                    journal.consumeCanonicalRandomId(missingEnvironment, route, pending.pendingSendContext!!.randomId!!),
                )
            }
        }
    }

    @Test
    fun blankIdentityKeysFailClosedAndEqualFirstGenerationSurvivesColdCleanup() {
        withDatabase { path ->
            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                val route = route()
                val pending = message("local-send-blank-key", "random-blank-key")
                listOf(
                    provenance(environmentId = ""),
                    provenance(principalId = ""),
                    provenance(serverId = ""),
                    provenance(authGeneration = -1L),
                    // RealtimeAuthorityRegistry's Empty revision is generation 0 with no session;
                    // every real session starts at 1, so 0 is a sentinel, not a first revision.
                    provenance(authGeneration = 0L),
                ).forEach { invalid ->
                    assertFalse(journal.persistSending(invalid, route, pending))
                    assertTrue(journal.restore(invalid, route).isEmpty())
                }
                assertFalse(journal.clearForAuthRevision("", "principal-a", 0L))
                assertFalse(journal.clearForAuthRevision("env-a", "", 0L))

                // Generation 1 is the first real revision.  The cold-start cleanup is strictly
                // older-than, so an equal generation survives; only the exact terminal cleanup
                // retires it.
                val firstGeneration = provenance(authGeneration = 1L)
                assertTrue(journal.persistSending(firstGeneration, route, pending))
                assertTrue(
                    journal.clearForPrincipalBeforeAuthRevision(
                        firstGeneration.environmentId,
                        firstGeneration.principalId,
                        1L,
                    )
                )
                assertEquals(1, journal.restore(firstGeneration, route).size)
                assertTrue(
                    journal.clearForAuthRevision(
                        firstGeneration.environmentId,
                        firstGeneration.principalId,
                        1L,
                    )
                )
                assertTrue(journal.restore(firstGeneration, route).isEmpty())
            }
        }
    }

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

    @Test
    fun conflictingRandomIdCannotReplaceAnExistingRowAcrossJournalInstances() {
        withDatabase { path ->
            val provenance = provenance()
            val route = route()
            JdbcSqliteDriver(url(path)).use { firstDriver ->
                JdbcSqliteDriver(url(path)).use { secondDriver ->
                    val first = journal(firstDriver)
                    val second = journal(secondDriver)
                    val original = message("local-send-4", "random-shared")
                    assertTrue(first.persistSending(provenance, route, original))

                    val conflictingLocalId = message("local-send-5", "random-shared")
                    assertFalse(second.persistSending(provenance, route, conflictingLocalId))
                    assertEquals(listOf(original), first.restore(provenance, route))

                    val conflictingRoute = message("local-send-4", "random-other")
                    assertFalse(second.persistSending(provenance, route(serverId = "channel-b"), conflictingRoute))
                    assertEquals(listOf(original), first.restore(provenance, route))
                }
            }
        }
    }

    @Test
    fun authRevisionCleanupDoesNotEraseASuccessorRevision() {
        withDatabase { path ->
            val route = route()
            val old = provenance(authGeneration = 10L)
            val successor = provenance(authGeneration = 11L)
            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                assertTrue(journal.persistSending(old, route, message("local-send-old", "random-old")))
                assertTrue(journal.persistSending(successor, route, message("local-send-new", "random-new")))
                assertTrue(journal.clearForAuthRevision(old.environmentId, old.principalId, old.authGeneration))
                assertEquals(0L, countRowsAtAuthGeneration(driver, old, 10L))
                assertEquals(1L, countRowsAtAuthGeneration(driver, successor, 11L))
                assertEquals(1, journal.restore(successor, route).size)
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
    fun rehomeAuthRevisionRefusesForeignPrincipalsAndNonForwardMoves() {
        withDatabase { path ->
            val route = route()
            val old = provenance(authGeneration = 10L)
            val successor = provenance(authGeneration = 11L)
            val foreign = provenance(principalId = "principal-b", authGeneration = 10L)
            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                assertTrue(journal.persistSending(old, route, message("local-send-old", "random-old")))
                assertTrue(journal.persistSending(foreign, route, message("local-send-foreign", "random-foreign")))

                // Only the named principal's partition moves; the foreign principal's row stays
                // exactly where a binding switch would expect to find (and refuse) it.
                assertEquals(
                    1,
                    journal.rehomeAuthRevision(old.environmentId, old.principalId, 10L, 11L),
                )
                assertEquals(0L, countRowsAtAuthGeneration(driver, old, 10L))
                assertEquals(1L, countRowsAtAuthGeneration(driver, successor, 11L))
                assertEquals(1L, countRowsAtAuthGeneration(driver, foreign, 10L))
                assertEquals(1, journal.restore(successor, route).size)
                assertEquals(1, journal.restore(foreign, route).size)

                // Same/backwards generation and blank identities are rejected without writes.
                assertEquals(0, journal.rehomeAuthRevision(old.environmentId, old.principalId, 11L, 11L))
                assertEquals(0, journal.rehomeAuthRevision(old.environmentId, old.principalId, 11L, 10L))
                assertEquals(0, journal.rehomeAuthRevision("", old.principalId, 10L, 12L))
                assertEquals(0, journal.rehomeAuthRevision(old.environmentId, old.principalId, 0L, 12L))
                assertEquals(1, journal.restore(successor, route).size)
                assertEquals(1, journal.restore(foreign, route).size)
            }
        }
    }

    @Test
    fun terminalCleanupIsBoundToIdentityAndGenerationAcrossSuccessorAndForeignRows() {
        withDatabase { path ->
            val route = route()
            val terminal = provenance(
                environmentId = "env-a",
                principalId = "principal-a",
                authGeneration = 10L,
            )
            val successor = terminal.copy(authGeneration = 11L)
            val foreignPrincipal = terminal.copy(principalId = "principal-b")
            val foreignEnvironment = terminal.copy(environmentId = "env-b")

            JdbcSqliteDriver(url(path)).use { driver ->
                val journal = journal(driver)
                assertTrue(journal.persistSending(terminal, route, message("local-send-terminal", "random-terminal")))
                assertTrue(journal.persistSending(successor, route, message("local-send-successor", "random-successor")))
                assertTrue(journal.persistSending(foreignPrincipal, route, message("local-send-foreign-principal", "random-foreign-principal")))
                assertTrue(journal.persistSending(foreignEnvironment, route, message("local-send-foreign-environment", "random-foreign-environment")))

                // This is the terminal path's complete authority: exact
                // environment + principal + auth generation.  A previous
                // database-wide clearAll would also delete the three rows
                // below, which are outside the logout intent.
                assertTrue(
                    journal.clearForAuthRevision(
                        environmentId = terminal.environmentId,
                        principalId = terminal.principalId,
                        authGeneration = terminal.authGeneration,
                    )
                )

                assertEquals(0L, countRowsAtAuthGeneration(driver, terminal, 10L))
                assertEquals(1L, countRowsAtAuthGeneration(driver, successor, 11L))
                assertEquals(1, journal.restore(successor, route).size)
                assertEquals(1, journal.restore(foreignPrincipal, route).size)
                assertEquals(1, journal.restore(foreignEnvironment, route).size)
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
