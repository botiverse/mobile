package build.raft.app

import build.raft.app.data.AccountProfileUpdateRequest
import build.raft.app.data.AddComputerDialogState
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedSettingsDataSource
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsStore
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SettingsHonestStateTest {
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
            val sessionStore = InMemorySlockAuthSessionStore(session)
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
}

private data class AccountErrorCase(
    val name: String,
    val body: String,
    val expectedServerMessage: String?,
    val expectedVisibleMessage: String
)

private class AccountUpdateErrorNetworkClient(
    private val errorBody: String
) : SlockNetworkClient {
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
