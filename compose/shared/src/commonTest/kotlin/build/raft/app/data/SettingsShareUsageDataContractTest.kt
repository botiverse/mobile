package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * RFC-067 "Share usage data" wire contract, verified against botiverse/slock origin/staging
 * 1b73e46c6559570c4bd5be642ef1ef496445b1ce:
 * - `GET` / `PATCH /api/auth/me` answer the bare public user (`userService.toPublicUser`), whose
 *   `shareUsageData` is `boolean | null` (null = not chosen yet);
 * - `PATCH /api/auth/me` takes `{ shareUsageData: boolean | null }` (`routes/auth.ts`
 *   `parseShareUsageData`); `auth.api.test.ts` "PATCH /api/auth/me shareUsageData: true/false/null
 *   round-trip" echoes the value back;
 * - a user who has not chosen counts as sharing (`packages/shared/src/productEvents.ts`
 *   `SHARE_USAGE_DATA_DEFAULT = true`).
 */
class SettingsShareUsageDataContractTest {
    @Test
    fun accountUserVectorKeepsNotChosenAndShowsItOn() {
        // toPublicUser fields as the server writes them (subset; unknown keys are ignored).
        val notChosen =
            """{"id":"user-1","email":"share-usage-data@slock.test","name":"share-usage-data","displayName":null,""" +
                """"emailVerified":true,"preferredTranslationMode":"auto","autoTranslationEnabled":true,""" +
                """"railTabOrder":null,"shareUsageData":null,"signupSurveyCompletedAt":null}"""

        val account = assertIs<AccountSettingsSnapshot>(parseSharedAccountSettingsSnapshot(notChosen, fallbackRole = null))
        val usageData = assertIs<AccountUsageDataSnapshot>(account.usageData)
        assertNull(usageData.choice, "null on the wire stays 'not chosen'")
        assertEquals(true, usageData.sharing, "not chosen is shown as on (SHARE_USAGE_DATA_DEFAULT)")

        for (value in listOf(true, false)) {
            val chosen = notChosen.replace(""""shareUsageData":null""", """"shareUsageData":$value""")
            val parsed = assertIs<AccountUsageDataSnapshot>(parseSharedAccountUsageDataSnapshot(chosen))
            assertEquals(value, parsed.choice)
            assertEquals(value, parsed.sharing)
        }

        // A server from before RFC-067 omits the field: same as not chosen.
        val legacy = """{"id":"user-1","email":"u@slock.test","name":"u","emailVerified":true}"""
        assertNull(assertIs<AccountUsageDataSnapshot>(parseSharedAccountUsageDataSnapshot(legacy)).choice)
    }

    @Test
    fun repositoryPatchesShareUsageDataInTheForegroundAndReadsBackTheChoice() = runTest {
        val network = RecordingShareUsageDataNetworkClient(responseJson = """{"id":"user-1","email":"u@slock.test","name":"u","shareUsageData":false}""")
        val repository = RealSettingsRepository(InMemoryRaftAuthSessionStore(settingsStoreSampleSession()), network)

        val result = repository.updateShareUsageData(enabled = false)

        assertEquals(false, assertIs<DataResult.Content<AccountUsageDataSnapshot>>(result).data.choice)
        val call = network.calls.single()
        assertEquals("auth/me", call.path)
        assertEquals("PATCH", call.method)
        assertEquals(DataRequestPriority.Foreground, call.priority)
        val body = Json.parseToJsonElement(call.body.orEmpty()).jsonObject
        assertEquals(setOf("shareUsageData"), body.keys)
        assertEquals(false, body.getValue("shareUsageData").jsonPrimitive.booleanOrNull)
    }

    @Test
    fun repositoryMapsARejectedSaveToAnError() = runTest {
        val network = RecordingShareUsageDataNetworkClient(responseJson = null, code = 400)
        val repository = RealSettingsRepository(InMemoryRaftAuthSessionStore(settingsStoreSampleSession()), network)

        val result = repository.updateShareUsageData(enabled = true)

        assertIs<DataResult.Error>(result)
    }

    private class RecordingShareUsageDataNetworkClient(
        private val responseJson: String?,
        private val code: Int = 200,
    ) : RaftNetworkClient {
        data class Call(val priority: DataRequestPriority, val path: String, val method: String, val body: String?)

        val calls = mutableListOf<Call>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            calls += Call(priority, path.trim('/'), method, bodyText)
            return if (code in 200..299) {
                HomeNetworkResponse(code = code, bodyText = responseJson)
            } else {
                HomeNetworkResponse(code = code, bodyText = null, errorBodyText = """{"error":"shareUsageData must be a boolean or null"}""")
            }
        }
    }
}
