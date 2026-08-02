/*
 * Task #116 — pure-core teeth for the step-1 behavior machine skeleton.
 * Vera acceptance-matrix no-host subset. No source-scanning tests (artin rule 1).
 */
package build.raft.app.composer

import build.raft.app.data.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class InMemoryAuthority : ComposerSessionAuthority {
    private val terminals = LinkedHashMap<String, ComposerSessionAuthorityRecord>()

    override fun terminalOf(sessionId: String): ComposerSessionTerminal? =
        terminals[sessionId]?.terminal

    override fun tryTerminate(record: ComposerSessionAuthorityRecord): ComposerTerminalCasOutcome {
        val existing = terminals[record.sessionId]
        if (existing != null) {
            return if (existing.terminal == record.terminal) {
                ComposerTerminalCasOutcome.SameTerminalReplay
            } else {
                ComposerTerminalCasOutcome.OppositeTerminalConflict
            }
        }
        terminals[record.sessionId] = record
        return ComposerTerminalCasOutcome.Won
    }
}

private class FakeCapabilities(
    private val failPromotion: Boolean = false,
    private val onPromotion: suspend () -> Unit = {},
) : ComposerHostCapabilities {
    val releases = mutableListOf<Triple<List<DurableEditRef>, String, String>>()

    override suspend fun pickFiles(request: ComposerPickRequest): ComposerPickOutcome =
        ComposerPickOutcome(emptyList())

    override fun normalizePickedImage(file: PlatformFile): PlatformFile = file

    override suspend fun promoteToDurable(
        files: List<PlatformFile>,
        sessionId: String,
    ): ComposerPromotionOutcome {
        onPromotion()
        return if (failPromotion) {
            ComposerPromotionOutcome.Failed("io")
        } else {
            ComposerPromotionOutcome.Promoted(
                files.mapIndexed { index, file ->
                    DurableEditRef(
                        sessionId = sessionId,
                        groupId = "g",
                        index = index,
                        contentSha256 = "sha-${file.fileName}",
                        sizeBytes = file.sizeBytes ?: 0L,
                        mediaType = file.mimeType.orEmpty(),
                        durableRef = "durable://$sessionId/${file.fileName}",
                        previewRef = file.previewUri,
                        originalName = file.fileName,
                    )
                },
            )
        }
    }

    override suspend fun releaseDurableRefs(
        refs: List<DurableEditRef>,
        sessionId: String,
        reason: String,
    ) {
        releases += Triple(refs, sessionId, reason)
    }
}

private class FakeAcceptor(
    private val decision: ComposerAcceptDecision = ComposerAcceptDecision.Accepted,
) : ComposerEditResultAcceptor {
    val calls = mutableListOf<Pair<ComposerGenerationToken, List<DurableEditRef>>>()

    override suspend fun acceptEditResult(
        expectedGeneration: ComposerGenerationToken,
        refs: List<DurableEditRef>,
    ): ComposerAcceptDecision {
        calls += expectedGeneration to refs
        return decision
    }
}

class ComposerPickEditMachineTest {
    private val identity = ComposerSourceIdentity("feedback", "acct-1", "page-1")
    private val generation = ComposerGenerationToken("gen-1")

    private fun machine(
        authority: InMemoryAuthority = InMemoryAuthority(),
        valid: Boolean = true,
    ) = ComposerPickEditMachine(
        authority = authority,
        currentSessionValidator = { _, _ -> valid },
        sessionIdFactory = { "sess-1" },
    )

    private fun request(
        count: Int = 2,
        annotation: Boolean = true,
        media: ComposerMediaPolicy = ComposerMediaPolicy.ImageOnly,
    ) = ComposerPickRequest(
        mediaPolicy = media,
        limits = ComposerLimitsPolicy(maxSelected = 9, perFileBytes = 10_000_000L, dedupeByContent = true),
        identity = identity,
        generation = generation,
        annotationEnabled = annotation,
    )

    private fun image(name: String) =
        PlatformFile(
            id = name,
            fileName = "$name.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 100L,
            previewUri = "file://$name",
            fileRef = "file://$name",
        )

    private fun event(
        session: ComposerPickEditSession,
        index: Int,
        total: Int = session.expectedCount,
        itemKey: String = "key-$index",
        groupId: String = session.groupId,
    ) = ComposerImageEditEvent(
        sessionId = session.sessionId,
        groupId = groupId,
        index = index,
        total = total,
        itemKey = itemKey,
        file = image("edited-$index"),
    )

    @Test
    fun beginSessionFreezesGroupAndGatesValidator() {
        val m = machine()
        val session = m.beginSession(request(3), listOf(image("a"), image("b"), image("c")))
        assertNotNull(session)
        assertEquals(3, session.expectedCount)
        assertEquals("sess-1-group", session.groupId)
        assertNull(machine(valid = false).beginSession(request(1), listOf(image("a"))))
        assertNull(m.beginSession(request(0), emptyList()))
    }

    @Test
    fun resolverGateDelegatesAnnotationOnlyForImagePolicyWithToggle() {
        val m = machine()
        val images = ComposerPickOutcome(files = listOf(image("a"), image("b")))
        val open = m.resolvePickedAction(request(), images)
        assertEquals("OpenImageAnnotation", open.name)
        val toggleOff = m.resolvePickedAction(request(annotation = false), images)
        assertEquals("UploadToComposer", toggleOff.name)
        val filePick = m.resolvePickedAction(request(media = ComposerMediaPolicy.ImageAndVideo), images)
        assertEquals("UploadToComposer", filePick.name)
        assertEquals("None", m.resolvePickedAction(request(), ComposerPickOutcome(emptyList())).name)
    }

    @Test
    fun outOfOrderEventsCompleteInOriginalOrderOnlyAtNN() {
        val m = machine()
        var session = m.beginSession(request(2), listOf(image("a"), image("b")))!!
        val first = m.foldEvent(session, event(session, index = 1))
        assertIs<ComposerFoldOutcome.Pending>(first)
        session = (first as ComposerFoldOutcome.Pending).session
        val second = m.foldEvent(session, event(session, index = 0))
        assertIs<ComposerFoldOutcome.ReadyToCommit>(second)
        val ready = second as ComposerFoldOutcome.ReadyToCommit
        assertEquals(setOf(0, 1), ready.session.filesByIndex.keys)
        assertEquals("edited-0.jpg", ready.session.filesByIndex[0]?.fileName)
        assertEquals("edited-1.jpg", ready.session.filesByIndex[1]?.fileName)
    }

    @Test
    fun missingGroupEventNeverCommits() {
        val m = machine()
        val session = m.beginSession(request(2), listOf(image("a"), image("b")))!!
        val only = m.foldEvent(session, event(session, index = 0))
        assertIs<ComposerFoldOutcome.Pending>(only)
    }

    @Test
    fun identicalReplayIsIdempotentConflictPoisons() {
        val m = machine()
        var session = m.beginSession(request(2), listOf(image("a"), image("b")))!!
        session = (m.foldEvent(session, event(session, 0)) as ComposerFoldOutcome.Pending).session
        val replay = m.foldEvent(session, event(session, 0))
        assertIs<ComposerFoldOutcome.Pending>(replay)
        val conflicted = m.foldEvent(session, event(session, 0, itemKey = "different"))
        // conflicting replay poisons the group; once the second index arrives the
        // group completes but is not committable -> Failed, zero partial commit
        val after = m.foldEvent(
            (conflicted as ComposerFoldOutcome.Pending).session,
            event(session, 1),
        )
        assertIs<ComposerFoldOutcome.Failed>(after)
        assertTrue((after as ComposerFoldOutcome.Failed).reason.startsWith("group_rejected:"))
    }

    @Test
    fun wrongSessionAndTerminalSessionsIgnoreEvents() {
        val authority = InMemoryAuthority()
        val m2 = machine(authority)
        val session = m2.beginSession(request(1), listOf(image("a")))!!
        assertIs<ComposerFoldOutcome.Ignored>(m2.foldEvent(session, event(session, 0).copy(sessionId = "other")))
        m2.cancel(session, atEpochMs = 1L)
        assertIs<ComposerFoldOutcome.Ignored>(m2.foldEvent(session, event(session, 0)))
    }

    @Test
    fun staleGenerationDropsEventsAndSessions() {
        var valid = true
        val m = ComposerPickEditMachine(
            authority = InMemoryAuthority(),
            currentSessionValidator = { _, _ -> valid },
            sessionIdFactory = { "sess-1" },
        )
        val session = m.beginSession(request(1), listOf(image("a")))!!
        valid = false
        assertIs<ComposerFoldOutcome.Ignored>(m.foldEvent(session, event(session, 0)))
    }

    @Test
    fun firstTerminalCasCoversBothRacesAndReplays() {
        val authority = InMemoryAuthority()
        val m = machine(authority)
        val session = m.beginSession(request(1), listOf(image("a")))!!

        // cancel-first race: a later accept-side commit must lose with zero writes
        m.cancel(session, atEpochMs = 1L)
        val result = kotlinx.coroutines.runBlocking {
            m.commitSession(session, FakeCapabilities(), FakeAcceptor(), atEpochMs = 2L)
        }
        assertIs<ComposerEditResult.Cancelled>(result)
        assertEquals(ComposerSessionTerminal.Cancelled, authority.terminalOf(session.sessionId))

        // accept-first race on a fresh session: cancel afterwards must not clear
        val authority2 = InMemoryAuthority()
        val m2 = machine(authority2)
        var session2 = m2.beginSession(request(1), listOf(image("a")))!!
        session2 = when (val f = m2.foldEvent(session2, event(session2, 0))) {
            is ComposerFoldOutcome.ReadyToCommit -> f.session
            else -> error("expected ReadyToCommit")
        }
        val acceptor2 = FakeAcceptor()
        val result2 = kotlinx.coroutines.runBlocking {
            m2.commitSession(session2, FakeCapabilities(), acceptor2, atEpochMs = 1L)
        }
        assertIs<ComposerEditResult.Complete>(result2)
        m2.cancel(session2, atEpochMs = 2L)
        assertEquals(ComposerSessionTerminal.Accepted, authority2.terminalOf(session2.sessionId))

        // raw three-outcome CAS: Won / SameTerminalReplay / OppositeTerminalConflict
        val authority3 = InMemoryAuthority()
        val rec = ComposerSessionAuthorityRecord("s", identity, generation, ComposerSessionTerminal.Accepted, 1L)
        assertEquals(ComposerTerminalCasOutcome.Won, authority3.tryTerminate(rec))
        assertEquals(ComposerTerminalCasOutcome.SameTerminalReplay, authority3.tryTerminate(rec.copy(terminalAtEpochMs = 3L)))
        assertEquals(
            ComposerTerminalCasOutcome.OppositeTerminalConflict,
            authority3.tryTerminate(rec.copy(terminal = ComposerSessionTerminal.Cancelled)),
        )
        assertEquals(ComposerSessionTerminal.Accepted, authority3.terminalOf("s"))
    }

    @Test
    fun commitRequiresCompleteGroupAndSurvivesOnlyViaAccepted() {
        var sessionSeq = 0
        val m = ComposerPickEditMachine(
            authority = InMemoryAuthority(),
            currentSessionValidator = { _, _ -> true },
            sessionIdFactory = { "sess-${sessionSeq++}" },
        )
        val incomplete = m.beginSession(request(2), listOf(image("a"), image("b")))!!
        val acceptor = FakeAcceptor()
        val r1 = kotlinx.coroutines.runBlocking {
            m.commitSession(incomplete, FakeCapabilities(), acceptor, atEpochMs = 1L)
        }
        assertIs<ComposerEditResult.Failed>(r1)
        assertTrue(acceptor.calls.isEmpty())

        fun readySession(): ComposerPickEditSession {
            var s = m.beginSession(request(2), listOf(image("a"), image("b")))!!
            s = (m.foldEvent(s, event(s, 0)) as ComposerFoldOutcome.Pending).session
            return when (val f = m.foldEvent(s, event(s, 1))) {
                is ComposerFoldOutcome.ReadyToCommit -> f.session
                else -> error("expected ReadyToCommit")
            }
        }
        // Post-CAS Rejected is a host contract violation: refs released, Failed, no Complete.
        // The Accepted tombstone still stands (immutable first-terminal) — hence a fresh session per attempt.
        val rejecting = FakeAcceptor(ComposerAcceptDecision.Rejected)
        val rejectingCaps = FakeCapabilities()
        val r2 = kotlinx.coroutines.runBlocking {
            m.commitSession(readySession(), rejectingCaps, rejecting, atEpochMs = 1L)
        }
        assertIs<ComposerEditResult.Failed>(r2)
        assertEquals(generation, rejecting.calls.single().first)
        assertEquals("accept_rejected", rejectingCaps.releases.single().third)
        // promotion failure: Failed, no accept call
        val r3 = kotlinx.coroutines.runBlocking {
            m.commitSession(readySession(), FakeCapabilities(failPromotion = true), FakeAcceptor(), atEpochMs = 1L)
        }
        assertIs<ComposerEditResult.Failed>(r3)
        assertTrue((r3 as ComposerEditResult.Failed).reason.startsWith("promotion_failed:"))
        // only Accepted yields Complete with durable refs carrying content identity
        val accepting = FakeAcceptor()
        val ready = readySession()
        val r4 = kotlinx.coroutines.runBlocking {
            m.commitSession(ready, FakeCapabilities(), accepting, atEpochMs = 1L)
        }
        assertIs<ComposerEditResult.Complete>(r4)
        val refs = (r4 as ComposerEditResult.Complete).refs
        assertEquals(2, refs.size)
        assertTrue(refs.all { it.contentSha256.isNotBlank() && it.durableRef.startsWith("durable://") })
        assertEquals(listOf(0, 1), refs.map { it.index })
    }

    /** AD4 blocker 1: cancel landing during promotion ⇒ post-suspend terminal recheck stops the commit: ZERO acceptor calls (no SSOT write), refs released. */
    @Test
    fun cancelDuringPromotionReleasesAndWritesNothing() {
        val authority = InMemoryAuthority()
        val m = machine(authority = authority)
        var ready = m.beginSession(request(2), listOf(image("a"), image("b")))!!
        ready = (m.foldEvent(ready, event(ready, 0)) as ComposerFoldOutcome.Pending).session
        ready = when (val f = m.foldEvent(ready, event(ready, 1))) {
            is ComposerFoldOutcome.ReadyToCommit -> f.session
            else -> error("expected ReadyToCommit")
        }
        val acceptor = FakeAcceptor()
        val caps = FakeCapabilities(onPromotion = { m.cancel(ready, atEpochMs = 0L) })
        val r = kotlinx.coroutines.runBlocking {
            m.commitSession(ready, caps, acceptor, atEpochMs = 1L)
        }
        assertIs<ComposerEditResult.Cancelled>(r)
        assertTrue(acceptor.calls.isEmpty())
        assertEquals("session_terminal", caps.releases.single().third)
        assertEquals(ComposerSessionTerminal.Cancelled, authority.terminalOf(ready.sessionId))
    }

    /** AD4 blocker 3: validator flipping during promotion ⇒ stale, refs released, acceptor never called. */
    @Test
    fun validatorFlipDuringPromotionReleasesAndCancels() {
        var valid = true
        val authority = InMemoryAuthority()
        val m = ComposerPickEditMachine(
            authority = authority,
            currentSessionValidator = { _, _ -> valid },
            sessionIdFactory = { "sess-1" },
        )
        var ready = m.beginSession(request(2), listOf(image("a"), image("b")))!!
        ready = (m.foldEvent(ready, event(ready, 0)) as ComposerFoldOutcome.Pending).session
        ready = when (val f = m.foldEvent(ready, event(ready, 1))) {
            is ComposerFoldOutcome.ReadyToCommit -> f.session
            else -> error("expected ReadyToCommit")
        }
        val acceptor = FakeAcceptor()
        val caps = FakeCapabilities(onPromotion = { valid = false })
        val r = kotlinx.coroutines.runBlocking {
            m.commitSession(ready, caps, acceptor, atEpochMs = 1L)
        }
        assertIs<ComposerEditResult.Cancelled>(r)
        assertTrue(acceptor.calls.isEmpty())
        assertEquals("stale_generation", caps.releases.single().third)
    }

    /** AD4 blocker 1 (CAS fork): opposite terminal pre-recorded ⇒ commit short-circuits: release + Cancelled, acceptor never called, no SSOT write. */
    @Test
    fun casConflictReleasesRefsAndCancels() {
        val authority = InMemoryAuthority()
        val m = machine(authority = authority)
        var ready = m.beginSession(request(2), listOf(image("a"), image("b")))!!
        ready = (m.foldEvent(ready, event(ready, 0)) as ComposerFoldOutcome.Pending).session
        ready = when (val f = m.foldEvent(ready, event(ready, 1))) {
            is ComposerFoldOutcome.ReadyToCommit -> f.session
            else -> error("expected ReadyToCommit")
        }
        authority.tryTerminate(
            ComposerSessionAuthorityRecord(
                sessionId = ready.sessionId,
                identity = ready.identity,
                generation = ready.generation,
                terminal = ComposerSessionTerminal.Cancelled,
                terminalAtEpochMs = 0L,
            ),
        )
        val acceptor = FakeAcceptor()
        val caps = FakeCapabilities()
        val r = kotlinx.coroutines.runBlocking {
            m.commitSession(ready, caps, acceptor, atEpochMs = 1L)
        }
        assertIs<ComposerEditResult.Cancelled>(r)
        assertTrue(acceptor.calls.isEmpty())
        // Pre-existing terminal short-circuits before promotion: zero refs promoted, nothing to release.
        assertTrue(caps.releases.isEmpty())
    }
}
