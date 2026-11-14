package build.raft.app.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.RaftThreadMessageItem
import build.raft.app.RaftThreadPendingSendContext
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.ReplyCacheProvenance
import build.raft.app.ThreadMessageDeliverySending
import build.raft.app.ThreadRouteIdentity
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.shared.data.local.RaftDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Open gap from #4784 (architecture review B): a cold start whose stored session cannot be decoded
 * publishes [RaftAccountBinding.Corrupt] and keeps every pending send, but the next sign-in bound
 * the account like a fresh cold start and [ThreadPendingSendJournal.clearForPrincipalBeforeAuthRevision]
 * deleted the sends journaled under the older auth generation. Signing back in as the SAME account
 * must keep them (moved to the new generation); only a different account, or an explicit sign-out,
 * clears them.
 */
class RaftPendingSendCorruptSessionRecoveryTest {
    private val environment = "https://api.raft.build"
    private val oldGeneration = 3L

    private class Process(val journal: ThreadPendingSendJournal) {
        val context = MutableStateFlow<ProcessPresenceAuthContext?>(null)
        val binding = MutableStateFlow<RaftAccountBinding<ProcessPresenceAuthContext>>(RaftAccountBinding.Unknown)
        val logs = mutableListOf<String>()
        val transition =
            processAccountBindingTransitionWithPendingSends(
                presenceAuthContext = context,
                accountBinding = binding,
                registry = ProcessMemberPresenceRegistry(),
                journal = journal,
                currentEnvironmentId = { "https://api.raft.build" },
                diagnosticsLogger = { logs += it },
            )
    }

    private fun revision(generation: Long, principal: String?) =
        RealtimeAuthRevision(
            generation = generation,
            session =
                principal?.let {
                    RealtimeAuthoritySession(principalId = it, serverIds = listOf("server-a"), environmentId = environment)
                },
        )

    private fun session(principal: String) =
        StoredAuthSession(
            user = StoredAuthUser(id = principal, email = "$principal@example.test", displayName = principal),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-a",
            servers = listOf(StoredAuthServer(id = "server-a", name = "server-a", slug = "server-a")),
        )

    private val corrupt = RaftAuthSessionState.Corrupt("SerializationException")

    @Test
    fun sameAccountSignInAfterUnreadableColdStartKeepsPendingSends() = withJournal { driver, journal ->
        assertTrue(journal.persistSending(provenance("user-a", oldGeneration), route(), message("local-send-1", "random-1")))

        // Next process: the stored session cannot be decoded; then the user signs in as user-a again.
        val next = Process(journal)
        next.transition(revision(oldGeneration + 1, principal = null), corrupt)
        assertEquals(RaftAccountBinding.Corrupt("SerializationException"), next.binding.value)
        next.transition(revision(oldGeneration + 2, principal = "user-a"), RaftAuthSessionState.SignedIn(session("user-a")))

        assertEquals(
            1L,
            countRows(driver, "user-a"),
            "the same account signing back in must find its unsent message: ${next.logs}",
        )
        // ...and it belongs to the new auth revision, so drain/restore/terminal cleanup reach it.
        assertEquals(1L, countRows(driver, "user-a", authGeneration = oldGeneration + 2))
        assertEquals(1, journal.restore(provenance("user-a", oldGeneration + 2), route()).size)
    }

    @Test
    fun differentAccountSignInAfterUnreadableColdStartClearsThePreviousAccountsPendingSends() =
        withJournal { driver, journal ->
            assertTrue(journal.persistSending(provenance("user-a", oldGeneration), route(), message("local-send-1", "random-1")))

            val next = Process(journal)
            next.transition(revision(oldGeneration + 1, principal = null), corrupt)
            next.transition(revision(oldGeneration + 2, principal = "user-b"), RaftAuthSessionState.SignedIn(session("user-b")))

            assertEquals(0L, countRows(driver, "user-a"), "another account signing in clears what the unreadable one left")
        }

    @Test
    fun explicitSignOutAfterUnreadableColdStartStillClearsOnTheNextSignIn() = withJournal { driver, journal ->
        assertTrue(journal.persistSending(provenance("user-a", oldGeneration), route(), message("local-send-1", "random-1")))

        val next = Process(journal)
        next.transition(revision(oldGeneration + 1, principal = null), corrupt)
        // An explicit sign-out (no stored session) ends the "unreadable" state.
        next.transition(revision(oldGeneration + 2, principal = null), RaftAuthSessionState.SignedOut)
        assertEquals(RaftAccountBinding.SignedOut, next.binding.value)
        next.transition(revision(oldGeneration + 3, principal = "user-a"), RaftAuthSessionState.SignedIn(session("user-a")))

        assertEquals(0L, countRows(driver, "user-a"))
    }

    @Test
    fun ordinaryColdStartStillRetiresOlderGenerationsOfTheAccount() = withJournal { driver, journal ->
        assertTrue(journal.persistSending(provenance("user-a", oldGeneration), route(), message("local-send-1", "random-1")))

        val next = Process(journal)
        next.transition(revision(oldGeneration + 1, principal = "user-a"), RaftAuthSessionState.SignedIn(session("user-a")))

        assertEquals(0L, countRows(driver, "user-a"))
    }

    // --- fixtures ---

    private fun withJournal(block: (JdbcSqliteDriver, ThreadPendingSendJournal) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            RaftDatabase.Schema.create(driver).value
            block(driver, ThreadPendingSendJournal(RaftSqlDatabase(driver), nowMillis = { 123L }))
        }
    }

    private fun countRows(driver: JdbcSqliteDriver, principal: String, authGeneration: Long? = null): Long =
        driver.executeQuery(
            identifier = null,
            sql =
                "SELECT COUNT(*) FROM threadPendingSendEntity WHERE principalId = ?" +
                    if (authGeneration != null) " AND authGeneration = ?" else "",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = if (authGeneration != null) 2 else 1,
            binders = {
                bindString(0, principal)
                if (authGeneration != null) bindLong(1, authGeneration)
            },
        ).value

    private fun provenance(principal: String, authGeneration: Long) =
        ReplyCacheProvenance(
            environmentId = environment,
            principalId = principal,
            serverId = "server-a",
            generation = 1L,
            authGeneration = authGeneration,
            selectionGeneration = 20L,
        )

    private fun route(): ThreadRouteIdentity =
        resolveThreadRouteIdentity(
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = "channel",
            instanceId = "channel-a",
            sourceThreadId = "channel-a",
            streamChannelId = "channel-a",
            parentChannelId = null,
            parentMessageId = null,
        )

    private fun message(localId: String, randomId: String): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = localId,
            authorId = "user-a",
            authorName = "A",
            isOwn = true,
            body = "hello",
            randomId = randomId,
            threadChannelId = "channel-a",
            deliveryState = ThreadMessageDeliverySending,
            pendingSendContext =
                RaftThreadPendingSendContext(
                    channelId = "channel-a",
                    body = "hello",
                    streamChannelId = "channel-a",
                    randomId = randomId,
                ),
            createdAtEpochMillis = 123L,
        )
}
