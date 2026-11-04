package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Hands b19326ee / c2430c69 (Android 1.17.0): two rows with id `local-send-<scope>-1` reached the
 * thread message LazyColumn and crashed it with "Key ... was already used". These cases cover
 * the three guards: unique local ids across restarts, one restored row per local id, and a list
 * that never hands LazyColumn a repeated key.
 */
class ThreadLocalSendKeyUniquenessTest {
    @Test
    fun localSendIdsStayUniqueAcrossSimulatedProcessRestarts() {
        val scope = "shared-shell-thread-c8cac9c9"
        // Each generator is one process: its counter starts again at 1.
        val firstProcess = ThreadLocalSendIdGenerator()
        val secondProcess = ThreadLocalSendIdGenerator()

        val firstLaunchId = firstProcess.next(scope)
        val secondLaunchId = secondProcess.next(scope)
        val firstLaunchSecondSend = firstProcess.next(scope)

        assertNotEquals(firstLaunchId, secondLaunchId)
        assertNotEquals(firstLaunchId, firstLaunchSecondSend)
        for (id in listOf(firstLaunchId, secondLaunchId)) {
            assertTrue(id.startsWith("$ThreadTransientLocalSendIdPrefix$scope-"), id)
            // localSendOrder is parsed from the last `-` segment and must stay the counter.
            assertEquals(1L, id.substringAfterLast('-').toLongOrNull(), id)
        }
        assertEquals(2L, firstLaunchSecondSend.substringAfterLast('-').toLongOrNull())
    }

    @Test
    fun localSendIdFormatKeepsTheCounterLastWithAFixedNonce() {
        val generator = ThreadLocalSendIdGenerator(processNonce = "k3x9")

        assertEquals("local-send-thread-a-k3x9-1", generator.next("thread-a"))
        assertEquals("local-send-thread-k3x9-2", generator.next(" "))
        assertTrue(newThreadLocalSendProcessNonce().none { it == '-' })
    }

    @Test
    fun restoredRowsSharingALocalIdKeepOnlyTheNewest() {
        val newer = message("local-send-t-1", randomId = "rid-newer", createdAt = 20L)
        val older = message("local-send-t-1", randomId = "rid-older", createdAt = 10L)
        val other = message("local-send-t-2", randomId = "rid-other", createdAt = 5L)

        val unique = listOf(newer, other, older).newestPerLocalId()

        assertEquals(listOf(newer, other), unique)
        val alreadyUnique = listOf(newer, other)
        assertSame(alreadyUnique, alreadyUnique.newestPerLocalId())
    }

    @Test
    fun repeatedListKeysAreDroppedKeepingTheLastRow() {
        // The crash shape: the same local row twice (same id and randomId), so the randomId is
        // "duplicated" and both rows fall back to the same id key.
        val earlier = message("m-0", randomId = "rid-0")
        val first = message("local-send-t-1", randomId = "rid-1", body = "first copy")
        val second = message("local-send-t-1", randomId = "rid-1", body = "second copy")
        val messages = listOf(earlier, first, second)
        val duplicateRandomIds = messages.threadDuplicateRandomIds()
        assertEquals(
            first.messageListItemKey(duplicateRandomIds),
            second.messageListItemKey(duplicateRandomIds),
            "precondition: this list repeats a LazyColumn key"
        )

        val dedupe = messages.withUniqueMessageListKeys()

        assertEquals(listOf(earlier, second), dedupe.messages)
        assertEquals(listOf("thread-page:local-send-t-1"), dedupe.droppedKeys)
        val keys = dedupe.messages.map { it.messageListItemKey(dedupe.messages.threadDuplicateRandomIds()) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun uniqueListKeysPassThroughUntouched() {
        // A local send and its canonical echo share a randomId but have different ids: both keep
        // their (distinct) id keys and nothing is dropped.
        val messages =
            listOf(
                message("local-send-t-1", randomId = "rid-1"),
                message("server-1", randomId = "rid-1"),
                message("server-2", randomId = null),
            )

        val dedupe = messages.withUniqueMessageListKeys()

        assertSame(messages, dedupe.messages)
        assertTrue(dedupe.droppedKeys.isEmpty())
    }

    private fun message(
        id: String,
        randomId: String?,
        body: String = "body",
        createdAt: Long? = null,
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "u1",
            authorName = "Artin",
            body = body,
            randomId = randomId,
            createdAtEpochMillis = createdAt,
        )
}
