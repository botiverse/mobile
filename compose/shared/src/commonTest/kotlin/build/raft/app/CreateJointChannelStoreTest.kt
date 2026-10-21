package build.raft.app

import build.raft.app.data.CreateJointChannelOutcome
import build.raft.app.data.DataResult
import build.raft.app.data.JOINT_CHANNEL_FREE_LIMIT_REACHED
import build.raft.app.data.JOINT_FREE_SERVER_LIMIT
import build.raft.app.data.JOINT_INVITEE_NOT_FOUND
import build.raft.app.data.JOINT_SERVER_LIMIT
import build.raft.app.data.JOINT_TARGET_SERVER_INVALID
import build.raft.app.data.JointChannelAccepted
import build.raft.app.data.JointChannelErrorDetails
import build.raft.app.data.JointInviteTarget
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreateJointChannelStoreTest {
    @Test
    fun submitValidatesInWebOrderBeforeAnyRequest() = runTest {
        data class Case(val name: String, val slug: String, val people: String, val expected: CreateJointChannelError)
        listOf(
            Case("  ", "partner-co", "@ada", CreateJointChannelError.NameInvalid),
            Case("x".repeat(33), "partner-co", "@ada", CreateJointChannelError.NameInvalid),
            Case("launch", " ", "@ada", CreateJointChannelError.InviteSlugRequired),
            Case("launch", "partner-co", " ,\n", CreateJointChannelError.InviteePersonRequired),
        ).forEach { case ->
            val dataSource = FakeCreateJointDataSource()
            val store = filledStore(dataSource, case.name, case.slug, case.people)

            store.dispatch(CreateJointChannelIntent.Submit)
            runCurrent()

            assertEquals(case.expected, store.state.value.error, "case=$case")
            assertTrue(dataSource.creates.isEmpty())
            assertFalse(store.state.value.submitting)
        }
    }

    @Test
    fun createSendsEveryDraftAndSelectedMembersThenOpensTheChannel() = runTest {
        val gate = CompletableDeferred<CreateJointChannelOutcome>()
        val dataSource = FakeCreateJointDataSource(createGate = gate)
        val store = filledStore(dataSource, " launch ", "partner-co", "@ada, bo@example.com")
        store.dispatch(CreateJointChannelIntent.SetDescription("  "))
        store.dispatch(CreateJointChannelIntent.AddDraft)
        val second = store.state.value.drafts.last().id
        store.dispatch(CreateJointChannelIntent.UpdateDraft(second, targetServerSlug = "third-co", invitedPeopleText = "@cy"))
        store.dispatch(CreateJointChannelIntent.ToggleAgent("agent-1"))
        store.dispatch(CreateJointChannelIntent.ToggleUser("user-2"))
        store.dispatch(CreateJointChannelIntent.ToggleUser("user-3"))
        store.dispatch(CreateJointChannelIntent.ToggleUser("user-3"))

        store.dispatch(CreateJointChannelIntent.Submit)
        runCurrent()
        assertTrue(store.state.value.submitting)
        store.dispatch(CreateJointChannelIntent.Submit)
        runCurrent()

        val sent = dataSource.creates.single()
        assertEquals("launch", sent.name)
        assertNull(sent.description)
        assertEquals(listOf("agent-1"), sent.agentIds)
        assertEquals(listOf("user-2"), sent.userIds)
        assertEquals(
            listOf(JointInviteTarget("partner-co", listOf("@ada", "bo@example.com")), JointInviteTarget("third-co", listOf("@cy"))),
            sent.invites,
        )

        gate.complete(CreateJointChannelOutcome.Created(JointChannelAccepted(ChannelId("ch-new"), "launch")))
        runCurrent()
        val effect = assertIs<CreateJointChannelEffect.Open>(store.state.value.effect)
        assertEquals(ChannelId("ch-new"), effect.channel.channelId)
        assertFalse(store.state.value.submitting)
        store.dispatch(CreateJointChannelIntent.ConsumeEffect)
        assertNull(store.state.value.effect)
    }

    @Test
    fun draftsStayBetweenOneAndTwentyNineAndEditsClearTheSubmitError() = runTest {
        val store = CreateJointChannelStore(FakeCreateJointDataSource(), this)
        val only = store.state.value.drafts.single().id
        store.dispatch(CreateJointChannelIntent.RemoveDraft(only))
        assertEquals(1, store.state.value.drafts.size)

        repeat(40) { store.dispatch(CreateJointChannelIntent.AddDraft) }
        assertEquals(CREATE_JOINT_MAX_INVITE_DRAFTS, store.state.value.drafts.size)
        assertFalse(store.state.value.canAddDraft)

        store.dispatch(CreateJointChannelIntent.Submit)
        assertEquals(CreateJointChannelError.NameInvalid, store.state.value.error)
        store.dispatch(CreateJointChannelIntent.RemoveDraft(store.state.value.drafts.last().id))
        assertNull(store.state.value.error)
        assertTrue(store.state.value.canAddDraft)
    }

    @Test
    fun serverFailuresKeepTheFormAndFlagBillingLimits() = runTest {
        data class Case(val outcome: CreateJointChannelOutcome.Failed, val billing: Boolean)
        listOf(
            Case(failed(DataResult.NoPermission(PermissionBlocker(JOINT_CHANNEL_FREE_LIMIT_REACHED)), JOINT_CHANNEL_FREE_LIMIT_REACHED), billing = true),
            Case(failed(DataResult.NoPermission(PermissionBlocker(JOINT_FREE_SERVER_LIMIT)), JOINT_FREE_SERVER_LIMIT), billing = true),
            Case(failed(DataResult.NoPermission(PermissionBlocker(JOINT_SERVER_LIMIT)), JOINT_SERVER_LIMIT), billing = false),
            Case(failed(DataResult.Error(ScreenError(JOINT_INVITEE_NOT_FOUND)), JOINT_INVITEE_NOT_FOUND), billing = false),
            // routes/channels.ts maps "requires the Pro plan" to 403 { error } without a code.
            Case(
                failed(DataResult.NoPermission(PermissionBlocker("joint_forbidden", serverMessage = "Joint channels requires the Pro plan")), null),
                billing = true,
            ),
            Case(failed(DataResult.Error(ScreenError("joint_http_409", serverMessage = "Channel name already taken")), null), billing = false),
        ).forEach { case ->
            val store = filledStore(FakeCreateJointDataSource(createResult = case.outcome), "launch", "partner-co", "@ada")

            store.dispatch(CreateJointChannelIntent.Submit)
            runCurrent()

            val error = assertIs<CreateJointChannelError.Server>(store.state.value.error)
            assertEquals(case.outcome.details?.code, error.details?.code)
            assertEquals(case.billing, store.state.value.showsBillingLink, "case=$case")
            assertEquals("launch", store.state.value.name)
            assertNull(store.state.value.effect)
        }
    }

    @Test
    fun archivedNameCollisionOffersUnarchiveOrChangeName() = runTest {
        val collision = CreateJointChannelOutcome.ArchivedNameCollision(ChannelId("ch-old"), "launch", canUnarchive = true)
        val dataSource = FakeCreateJointDataSource(createResult = collision)
        val store = filledStore(dataSource, "launch", "partner-co", "@ada")

        store.dispatch(CreateJointChannelIntent.Submit)
        runCurrent()
        assertEquals(CreateJointArchivedCollision(ChannelId("ch-old"), "launch", true), store.state.value.collision)

        store.dispatch(CreateJointChannelIntent.UnarchiveCollision)
        runCurrent()
        assertEquals(listOf(ChannelId("ch-old")), dataSource.unarchived)
        assertEquals(ChannelId("ch-old"), assertIs<CreateJointChannelEffect.Open>(store.state.value.effect).channel.channelId)
        assertNull(store.state.value.collision)

        val noRights =
            filledStore(
                FakeCreateJointDataSource(createResult = collision.copy(canUnarchive = false)),
                "launch",
                "partner-co",
                "@ada",
            )
        noRights.dispatch(CreateJointChannelIntent.Submit)
        runCurrent()
        noRights.dispatch(CreateJointChannelIntent.UnarchiveCollision)
        runCurrent()
        assertNull(noRights.state.value.effect)
        noRights.dispatch(CreateJointChannelIntent.ChangeCollidingName)
        assertNull(noRights.state.value.collision)
        assertEquals("", noRights.state.value.name)
    }

    @Test
    fun errorCopyLocalizesInviteRejectionsLikeWeb() {
        val copy =
            CreateJointErrorCopy(
                nameInvalid = "name",
                slugRequired = "slug",
                peopleRequired = "people",
                freeServerLimit = "free",
                serverLimit = "max",
                freeHostLimit = { "host:$it" },
                freeHostLimitUnnamed = "host",
                inviteeNotFound = { invitee, server -> "notfound:$invitee@$server" },
                inviteeNotAdmin = { invitee, server -> "notadmin:$invitee@$server" },
                serverNotFound = { "noserver:$it" },
                cannotInviteSelf = "self",
                failedCreate = "failed",
            )
        fun text(code: String?, slug: String? = null, invitee: String? = null, blocking: String? = null, message: String? = null) =
            createJointChannelErrorText(
                CreateJointChannelError.Server(
                    DataResult.Error(ScreenError(code ?: "joint_http_400", serverMessage = message)),
                    JointChannelErrorDetails(code, slug, invitee, null, null, null, false, blocking),
                ),
                copy,
                currentServerSlug = "host-co",
            ) { "pipeline" }

        assertEquals("host:old-room", text(JOINT_CHANNEL_FREE_LIMIT_REACHED, blocking = "old-room"))
        assertEquals("host", text(JOINT_CHANNEL_FREE_LIMIT_REACHED))
        assertEquals("notfound:@ghost@partner-co", text(JOINT_INVITEE_NOT_FOUND, "partner-co", "@ghost"))
        assertEquals("self", text(JOINT_TARGET_SERVER_INVALID, "host-co"))
        assertEquals("noserver:nope", text(JOINT_TARGET_SERVER_INVALID, "nope"))
        assertEquals("Channel name already taken", text(null, message = "Channel name already taken"))
        assertEquals("pipeline", text(null))
        assertEquals("name", createJointChannelErrorText(CreateJointChannelError.NameInvalid, copy, null) { null })
    }

    @Test
    fun memberSearchMatchesNameAndHandle() {
        val members =
            listOf(
                MemberProfile(id = MemberId("a1"), displayName = "Ada Bot", handle = "ada", kind = MemberKind.Agent),
                MemberProfile(id = MemberId("u1"), displayName = "Bo", handle = "bo-r", kind = MemberKind.Human),
            )
        assertEquals(members, createJointMemberMatches(members, " "))
        assertEquals(listOf("a1"), createJointMemberMatches(members, "BOT").map { it.id.value })
        assertEquals(listOf("u1"), createJointMemberMatches(members, "bo-").map { it.id.value })
    }

    private fun TestScope.filledStore(
        dataSource: FakeCreateJointDataSource,
        name: String,
        slug: String,
        people: String,
    ): CreateJointChannelStore {
        val store = CreateJointChannelStore(dataSource, this)
        store.dispatch(CreateJointChannelIntent.SetName(name))
        val draft = store.state.value.drafts.single().id
        store.dispatch(CreateJointChannelIntent.UpdateDraft(draft, targetServerSlug = slug, invitedPeopleText = people))
        return store
    }

    private fun failed(result: DataResult<*>, code: String?): CreateJointChannelOutcome.Failed =
        CreateJointChannelOutcome.Failed(
            result,
            code?.let { JointChannelErrorDetails(it, null, null, null, null, null, false) },
        )
}

private class FakeCreateJointDataSource(
    private val createResult: CreateJointChannelOutcome =
        CreateJointChannelOutcome.Created(JointChannelAccepted(ChannelId("ch-new"), "launch")),
    private val createGate: CompletableDeferred<CreateJointChannelOutcome>? = null,
) : CreateJointChannelDataSource {
    data class Create(
        val name: String,
        val description: String?,
        val agentIds: List<String>,
        val userIds: List<String>,
        val invites: List<JointInviteTarget>,
    )

    val creates = mutableListOf<Create>()
    val unarchived = mutableListOf<ChannelId>()

    override suspend fun create(
        name: String,
        description: String?,
        agentIds: List<String>,
        userIds: List<String>,
        invites: List<JointInviteTarget>,
    ): CreateJointChannelOutcome {
        creates += Create(name, description, agentIds, userIds, invites)
        return createGate?.await() ?: createResult
    }

    override suspend fun unarchive(channelId: ChannelId): DataResult<JointChannelAccepted> {
        unarchived += channelId
        return DataResult.Content(JointChannelAccepted(channelId, "launch"))
    }
}
