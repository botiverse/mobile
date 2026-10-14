package build.raft.app.data

import build.raft.app.network.MultipartFile
import build.raft.app.network.SlockHttpExecutor
import build.raft.app.network.SlockHttpExecutorResponse
import build.raft.app.network.SlockRequestPriority
import build.raft.app.network.SlockTransferProgress
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileWebLoginGateTest {
    private val official = "https://api.raft.build/api/"
    private val selfHosted = "https://raft.example.com/api"

    @Test fun webLoginUntilTheLegacyFlagIsFetchedOn() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        var answer: Boolean? = null
        val gate = MobileWebLoginGate(store) { answer }
        assertTrue(gate.isEnabled(official))
        assertTrue(gate.refresh(official), "no answer ever → web login")
        answer = true
        assertFalse(gate.refresh(official))
        answer = null
        assertFalse(gate.refresh(official), "failed fetch keeps the cached answer")
        assertFalse(MobileWebLoginGate(store) { null }.isEnabled(official), "cache survives a new process")
        answer = false
        assertTrue(gate.refresh(official))
    }

    @Test fun eachApiServerKeepsItsOwnAnswer() = runBlocking {
        val gate = MobileWebLoginGate(InMemorySlockKeyValueStore()) { url -> url == official }
        assertFalse(gate.refresh(official))
        assertTrue(gate.refresh(selfHosted))
        assertFalse(gate.isEnabled("https://api.raft.build/api"), "trailing slash is the same server")
        assertTrue(gate.isEnabled(selfHosted))
    }

    @Test fun parsesOnlyTheDocumentedPublicResponse() {
        val key = MOBILE_LEGACY_LOGIN_FEATURE_FLAG_KEY
        assertEquals(true, parsePublicFeatureFlag(200, """{"flags":{"$key":true}}""", key))
        assertEquals(false, parsePublicFeatureFlag(200, """{"flags":{"$key":false,"other":true}}""", key))
        assertNull(parsePublicFeatureFlag(404, """{"error":"not found"}""", key))
        assertNull(parsePublicFeatureFlag(500, """{"flags":{"$key":true}}""", key))
        assertNull(parsePublicFeatureFlag(200, """{"flags":{}}""", key))
        assertNull(parsePublicFeatureFlag(200, """{"flags":{"$key":"true"}}""", key))
        assertNull(parsePublicFeatureFlag(200, "<html>", key))
        assertNull(parsePublicFeatureFlag(200, null, key))
    }

    @Test fun requestsThePublicEndpointWithoutCredentialsAndTreats404AsNoAnswer() = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var code = 200
        val executor = object : SlockHttpExecutor {
            override suspend fun execute(priority: SlockRequestPriority, url: String, method: String,
                headers: Map<String, String>, bodyText: String?, jsonBody: Boolean): SlockHttpExecutorResponse {
                requests += url to headers
                return SlockHttpExecutorResponse(code, """{"flags":{"$MOBILE_LEGACY_LOGIN_FEATURE_FLAG_KEY":true}}""", null)
            }
            override suspend fun executeMultipart(priority: SlockRequestPriority, url: String,
                fields: Map<String, String>, files: List<MultipartFile>, headers: Map<String, String>,
                onUploadProgress: ((SlockTransferProgress) -> Unit)?): SlockHttpExecutorResponse = error("Unexpected multipart")
        }
        assertEquals(true, fetchPublicLegacyLoginFlag(executor, official))
        assertEquals(
            "https://api.raft.build/api/public/feature-flags?keys=$MOBILE_LEGACY_LOGIN_FEATURE_FLAG_KEY&platform=mobile",
            requests.single().first,
        )
        assertTrue(requests.single().second.isEmpty(), "no Authorization header")
        code = 404
        assertNull(fetchPublicLegacyLoginFlag(executor, official))
    }
}
