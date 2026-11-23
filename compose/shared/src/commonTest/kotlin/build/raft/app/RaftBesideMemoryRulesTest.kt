package build.raft.app

import build.raft.app.RaftBesideKind.Artifact
import build.raft.app.RaftBesideKind.Profile
import build.raft.app.RaftBesideKind.Thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What was open beside a conversation (RaftBesideMemory.kt; web docs/frontend/beside-memory.md): the pure rules, after
 * the web's tests/besideMemory.test.ts (botiverse/slock staging 51c1f706a) where they apply, and the visit's rules of
 * its useBesideMemory.ts.
 */
class RaftBesideMemoryRulesTest {
    private val artifactA = RaftBesideArtifact("pt_a", version = 2)
    private val agent = RaftBesideProfile(RaftBesideProfileKind.Agent, "a1")
    private val human = RaftBesideProfile(RaftBesideProfileKind.Human, "h1")

    @Test
    fun aStateKeepsOnlyWellFormedPartsAndItsFrontIsOneOfTheOpenOnes() {
        assertEquals(RaftBesideState(), RaftBesideState(thread = "m 1", artifact = RaftBesideArtifact(""), front = Thread).normalized())
        val full = RaftBesideState("m1", RaftBesideArtifact("pt_a", 3), agent, agentTab = "activity", front = Profile)
        assertEquals(full, full.normalized())
        // A version that is no positive whole number is the latest; a tab belongs to an agent's profile only.
        assertEquals(
            RaftBesideState(artifact = RaftBesideArtifact("pt_a", null), profile = human, front = Artifact),
            RaftBesideState(artifact = RaftBesideArtifact("pt_a", 0), profile = human, agentTab = "activity", front = Thread).normalized(),
        )
        // An external author's or a computer's profile is no profile beside a conversation.
        assertNull(RaftBesideProfile.fromWireValue("external:c:m"))
        assertNull(RaftBesideProfile.fromWireValue("computer:c1"))
        assertEquals(agent, RaftBesideProfile.fromWireValue("agent:a1"))
    }

    @Test
    fun whatJustOpenedIsInFrontAPickedKindIsAndClosingTheFrontOneLeavesAnotherInFront() {
        val thread = raftNextBesideState(null, RaftBesideState(thread = "m1"))
        assertEquals(Thread, thread.front)
        val open = RaftBesideState(thread = "m1", artifact = RaftBesideArtifact("pt_a"))
        val both = raftNextBesideState(thread, open)
        assertEquals(Artifact, both.front)
        assertEquals(Artifact, raftNextBesideState(both, open).front, "nothing changed: the front stays")
        assertEquals(Thread, raftNextBesideState(both, open, picked = Thread).front)
        assertEquals(Thread, raftNextBesideState(both, open.copy(thread = "m2")).front, "another thread took its place")
        assertEquals(RaftBesideState(thread = "m1", front = Thread), raftNextBesideState(both, RaftBesideState(thread = "m1")))
        assertEquals(RaftBesideState(), raftNextBesideState(both, RaftBesideState()))
        assertEquals(
            Thread,
            raftNextBesideState(both, RaftBesideState(thread = "m1"), picked = Profile).front,
            "a pick of something not open is ignored",
        )
    }

    @Test
    fun theEntryWinsWhatItNamesStaysInFrontTheRememberedRestIsRestoredBehind() {
        val remembered = RaftBesideState("m1", artifactA, agent, agentTab = "activity", front = Artifact)
        assertEquals(
            RaftBesideRestorePlan(RaftBesideState("m1", artifactA, agent, agentTab = "activity"), front = Artifact),
            raftPlanBesideRestore(remembered, RaftBesideState()),
        )
        // A notification or a link to another thread keeps it, in front; the Artifact and the profile open behind it.
        assertEquals(
            RaftBesideRestorePlan(RaftBesideState(artifact = artifactA, profile = agent, agentTab = "activity"), front = Thread),
            raftPlanBesideRestore(remembered, RaftBesideState(thread = "m9")),
        )
        // The entry's profile keeps its own tab: the remembered one belongs to the remembered profile.
        assertEquals(
            RaftBesideState(thread = "m1", artifact = artifactA),
            raftPlanBesideRestore(remembered, RaftBesideState(profile = human)).restore,
        )
        assertEquals(RaftBesideRestorePlan(RaftBesideState(), front = Thread), raftPlanBesideRestore(null, RaftBesideState(thread = "m9")))
        assertEquals(RaftBesideRestorePlan(RaftBesideState(), front = null), raftPlanBesideRestore(null, RaftBesideState()))
    }

    @Test
    fun anEntryThatTakesTheThreadsPlaceRestoresNoThreadOverIt() {
        val remembered = RaftBesideState(thread = "m1", profile = human, front = Thread)
        assertEquals(
            RaftBesideRestorePlan(RaftBesideState(profile = human), front = Profile),
            raftPlanBesideRestore(remembered, RaftBesideState(), threadTaken = true),
        )
    }

    @Test
    fun droppingWhatIsNoLongerVisibleMovesTheFrontOn() {
        val threadAndAgent = RaftBesideState(thread = "m1", profile = agent, agentTab = "activity", front = Profile)
        assertEquals(RaftBesideState(thread = "m1", front = Thread), raftWithoutBesideKinds(threadAndAgent, listOf(Profile)))
        assertEquals(RaftBesideState(), raftWithoutBesideKinds(RaftBesideState(thread = "m1", front = Thread), listOf(Thread)))
        assertEquals(
            RaftBesideState(profile = agent, agentTab = "activity", front = Profile),
            raftOnlyBesideKinds(threadAndAgent.copy(front = Thread), listOf(Profile)),
        )
        assertEquals(RaftBesideState(), raftOnlyBesideKinds(null, listOf(Thread)))
    }

    @Test
    fun aVisitForgetsOnlyWhatThePersonClosesAndKeepsWhatItHasNotShown() {
        val remembered = RaftBesideState(thread = "m1", artifact = artifactA, front = Artifact)
        // Entering forgets nothing, before anything is restored.
        val entered = RaftBesideVisit.enter(remembered, RaftBesideState())
        assertEquals(remembered, entered.remembered)
        // The Artifact is restored, the thread not (no answer): the thread stays remembered.
        val restored = entered.shows(RaftBesideState(artifact = artifactA), picked = Artifact)
        assertEquals(remembered, restored.remembered)
        // The person closes the Artifact: forgotten; the thread they did not see in this visit is not.
        val closed = restored.shows(RaftBesideState())
        assertEquals(RaftBesideState(thread = "m1", front = Thread), closed.remembered)
        // A notification to another thread: it takes the remembered one's place, and is in front.
        val notified = RaftBesideVisit.enter(remembered, RaftBesideState(thread = "m9"))
        assertEquals(RaftBesideState(thread = "m9", artifact = artifactA, front = Thread), notified.remembered)
        assertEquals(
            RaftBesideRestorePlan(RaftBesideState(artifact = artifactA), front = Thread),
            notified.restorePlan(RaftBesideState(thread = "m9")),
        )
    }

    @Test
    fun aRestoreOpensWhatIsVisibleForgetsWhatIsGoneAndKeepsWhatHadNoAnswer() {
        val remembered = RaftBesideState("m1", artifactA, agent, agentTab = "activity", front = Artifact)
        val visit = RaftBesideVisit.enter(remembered, RaftBesideState())
        val answers =
            mapOf(Thread to RaftBesideVisibility.Unknown, Artifact to RaftBesideVisibility.Visible, Profile to RaftBesideVisibility.Gone)
        val step = visit.restoreStep(visit.restorePlan(RaftBesideState()), answers, now = RaftBesideState())
        // Only what is visible opens: never a pane for something gone.
        assertEquals(RaftBesideState(artifact = artifactA, front = Artifact), step.open)
        assertEquals(Artifact, step.front)
        val opened = step.visit.shows(step.open, picked = step.front)
        // The profile is forgotten; the thread (offline) stays for the next visit to ask again.
        assertEquals(RaftBesideState(thread = "m1", artifact = artifactA, front = Artifact), opened.remembered)
    }

    @Test
    fun aRestoreLeavesAloneWhatThePersonOpenedMeanwhile() {
        val visit = RaftBesideVisit.enter(RaftBesideState(profile = agent, agentTab = "activity"), RaftBesideState())
        val plan = visit.restorePlan(RaftBesideState())
        // While the answer was on its way the person opened someone else's profile.
        val meanwhile = visit.shows(RaftBesideState(profile = human))
        val step = meanwhile.restoreStep(plan, mapOf(Profile to RaftBesideVisibility.Visible), now = RaftBesideState(profile = human))
        assertEquals(RaftBesideState(), step.open)
        assertNull(step.front)
        assertEquals(RaftBesideState(profile = human, front = Profile), step.visit.remembered)
    }

    @Test
    fun theMemoryAppliesBesideChatsAndTheDmTabsOwnConversationOnly() {
        val channel = RaftAdaptiveDetailIdentity(serverId = "s1", threadId = "c1", surfaceKind = RaftThreadSurfaceKind.Channel)
        val dm = RaftAdaptiveDetailIdentity(serverId = "s1", threadId = "d1", surfaceKind = RaftThreadSurfaceKind.DirectMessage)
        val reply =
            RaftAdaptiveDetailIdentity(
                serverId = "s1",
                threadId = "t1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "c1",
                parentMessageId = "m1",
            )
        fun shown(surface: String, identity: RaftAdaptiveDetailIdentity, channelKind: String? = null) =
            RaftWideNavEntry("s1", surface, RaftWideNavDetail.Conversation(identity, WireThreadLaunchPayload(channelKind = channelKind)))

        // Off the wide layout nothing shows (a phone, the narrow layout).
        assertNull(raftBesideConversationOf(null))
        assertEquals(RaftBesideConversation("s1", "c1"), raftBesideConversationOf(shown(RaftRailChatTab, channel)))
        assertEquals(RaftBesideConversation("s1", "d1"), raftBesideConversationOf(shown("dms", dm)))
        // A focused message takes the thread's place (web `?msg=`).
        assertEquals(
            RaftBesideConversation("s1", "c1", threadTaken = true),
            raftBesideConversationOf(shown(RaftRailChatTab, channel.copy(focusedMessageId = "m5"))),
        )
        // A notification or a link to a thread opened it in the detail: the entry names that thread of its conversation.
        assertEquals(
            RaftBesideConversation("s1", "c1", named = RaftBesideState(thread = "m1", front = Thread)),
            raftBesideConversationOf(shown(RaftRailChatTab, reply)),
        )
        // Activity's and Search's detail slot, Saved and the Tasks page show no conversation's own page; nor does a task's.
        for (surface in listOf("activity", AppShellRailSearchKey, AppShellRailSavedKey, "tasks")) {
            assertNull(raftBesideConversationOf(shown(surface, channel)), surface)
        }
        assertNull(raftBesideConversationOf(shown(RaftRailChatTab, channel, channelKind = RaftTaskConversationChannelKind)))
    }
}
