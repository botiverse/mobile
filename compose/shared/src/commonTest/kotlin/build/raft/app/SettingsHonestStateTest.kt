package build.raft.app

import build.raft.app.data.AccountProfileUpdateRequest
import build.raft.app.data.AccountProfileSnapshot
import build.raft.app.data.AccountSettingsSnapshot
import build.raft.app.data.AddComputerDialogState
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsDataSource
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsStore
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ServerId
import build.raft.app.model.MemberKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SettingsHonestStateTest {
    @Test
    fun accountAvatarCacheIdentityPairsTheAuthoritativeProfileWithTheCurrentSessionServer() {
        val session = authSessionFixture(userId = "owner", selectedServerId = "server-2")
        val fallbackState: ScreenLoadState<AccountSettingsSnapshot> = ScreenLoadState.Loading
        val expected =
            RaftMemberAvatarCacheIdentity(
                subject = RaftAvatarCacheSubject.Member(MemberKind.Human, "owner"),
                serverId = "server-2"
            )
        assertNull(fallbackState.settingsAccountAvatarCacheIdentity(session))

        val content =
            ScreenLoadState.Content(
                AccountSettingsSnapshot(
                    profile = accountProfile("owner"),
                    connectedAccounts = emptyList()
                )
            )
        assertEquals(expected, content.settingsAccountAvatarCacheIdentity(session))

        val staleAccount =
            ScreenLoadState.Content(
                AccountSettingsSnapshot(
                    profile = accountProfile("previous-owner"),
                    connectedAccounts = emptyList()
                )
            )
        assertNull(staleAccount.settingsAccountAvatarCacheIdentity(session))
        assertNull(
            ScreenLoadState.Content(
                AccountSettingsSnapshot(
                    profile = accountProfile(" "),
                    connectedAccounts = emptyList()
                )
            ).settingsAccountAvatarCacheIdentity(session)
        )
        assertNull(
            fallbackState.settingsAccountAvatarCacheIdentity(
                authSessionFixture(userId = "owner", servers = emptyList(), selectedServerId = null)
            )
        )
    }

    @Test
    fun accountErrorEnvelopeSurvivesAdapterStoreAndVisiblePayload() = runBlocking {
        val errorStrings = englishErrorPresentationStringsForTest()
        val cases =
            listOf(
                AccountErrorCase(
                    name = "canonical",
                    body = """{"error":"Exact server reason"}""",
                    expectedServerMessage = "Exact server reason",
                    expectedVisibleMessage = "Exact server reason"
                ),
                AccountErrorCase(
                    name = "wrong-alias",
                    body = """{"message":"must not be guessed"}""",
                    expectedServerMessage = null,
                    expectedVisibleMessage = "Something went wrong. Please try again."
                )
            )

        cases.forEach { case ->
            val session = authSessionFixture()
            val sessionStore = InMemoryRaftAuthSessionStore(session)
            val repository =
                RealSettingsRepository(
                    sessionStore = sessionStore,
                    networkClient = AccountUpdateErrorNetworkClient(case.body)
                )
            val store =
                SharedSettingsStore(
                    dataSource =
                        SharedSettingsDataSource(
                            sessionStore = sessionStore,
                            settingsRepository = repository
                        ),
                    scope = this@runBlocking
                )

            store.accept(
                SharedSettingsIntent.UpdateProfile(
                    AccountProfileUpdateRequest(
                        displayName = "Ada",
                        handle = "ada",
                        email = "ada@example.com"
                    )
                )
            )

            val actionState = assertNotNull(store.state.value.actionState, case.name)
            assertEquals("http_409", actionState.errorCode, case.name)
            assertEquals(case.expectedServerMessage, actionState.screenError?.serverMessage, case.name)
            assertEquals(case.expectedVisibleMessage, actionState.userFacingErrorMessage(errorStrings), case.name)

            val payload = store.state.value.account.toAccountPayload(session, actionState, errorStrings)
            assertEquals(case.expectedVisibleMessage, payload.saveErrorCode, case.name)
            assertFalse(payload.saveErrorCode.orEmpty().contains("http_409"), case.name)
            assertFalse(payload.saveErrorCode.orEmpty().contains("must not be guessed"), case.name)
        }
    }

    @Test
    fun addComputerDialogUsesTypedFailureBeforeLocalHumanCopy() {
        val errorStrings = englishErrorPresentationStringsForTest()
        val typed =
            AddComputerDialogState(
                errorMessage = "stale local text",
                screenError =
                    ScreenError(
                        code = "http_409",
                        retryable = false,
                        serverMessage = "A computer with this name already exists"
                    )
            )
        val local = AddComputerDialogState(errorMessage = "Choose a computer name")

        assertEquals("A computer with this name already exists", typed.userFacingErrorMessage(errorStrings))
        assertEquals("Choose a computer name", local.userFacingErrorMessage(errorStrings))
        assertNull(AddComputerDialogState().userFacingErrorMessage(errorStrings))
    }

    private fun accountProfile(userId: String): AccountProfileSnapshot =
        AccountProfileSnapshot(
            userId = userId,
            displayName = "Owner",
            handle = "owner",
            email = "owner@example.com",
            avatarLabel = "O"
        )
}

private data class AccountErrorCase(
    val name: String,
    val body: String,
    val expectedServerMessage: String?,
    val expectedVisibleMessage: String
)

private class AccountUpdateErrorNetworkClient(
    private val errorBody: String
) : RaftNetworkClient {
    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        assertEquals("auth/me", path.trim('/'))
        assertEquals("PATCH", method)
        return HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = errorBody)
    }
}
