package build.raft.app.data

import build.raft.app.raftSha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the vendored shared sealing vectors (`sealedInputs.v1.vector.json`, pinned by
 * `sealedInputs.v1.vector.sha256` from `botiverse/slock`) against Kotlin's [sealedInputsBinding],
 * [sealInputs], and JVM reference verifier [openSealedInputsJvm].
 */
class SealedInputsVectorTest {
    private val vectorBytes by lazy { resource("/messagecard/sealedInputs.v1.vector.json") }
    private val vectorText by lazy { vectorBytes.decodeToString() }
    private val sha256Text by lazy { resource("/messagecard/sealedInputs.v1.vector.sha256").decodeToString().trim() }
    private val vectors by lazy { Json.parseToJsonElement(vectorText).jsonObject }

    private fun keysByKid(): Map<String, Pair<RaftAppCardEncryptionKey, SealedInputsPrivateJwkJvm>> {
        val result = LinkedHashMap<String, Pair<RaftAppCardEncryptionKey, SealedInputsPrivateJwkJvm>>()
        for (entry in vectors.getValue("keys").jsonArray) {
            val obj = entry.jsonObject
            val pubObj = obj.getValue("publicJwk").jsonObject
            val privObj = obj.getValue("privateJwk").jsonObject
            val pub = assertNotNull(readAppCardEncryptionKey(pubObj), "publicJwk must validate")
            val priv =
                SealedInputsPrivateJwkJvm(
                    kid = privObj.getValue("kid").jsonPrimitive.content,
                    x = privObj.getValue("x").jsonPrimitive.content,
                    y = privObj.getValue("y").jsonPrimitive.content,
                    d = privObj.getValue("d").jsonPrimitive.content,
                )
            result[pub.kid] = pub to priv
        }
        return result
    }

    @Test
    fun committedVectorFileMatchesPinnedSha256AndTestOnlyHeader() {
        val parts = sha256Text.split(Regex("\\s+"))
        assertEquals(2, parts.size)
        assertEquals("sealedInputs.v1.vector.json", parts[1])
        assertEquals(parts[0], raftSha256Hex(vectorBytes))
        assertEquals(true, vectors.getValue("testOnly").jsonPrimitive.boolean)
        assertTrue(vectors.getValue("warning").jsonPrimitive.content.startsWith("TEST ONLY:"))
        assertEquals(listOf("contract", "schemaVersion", "testOnly", "warning"), vectors.keys.take(4))
    }

    @Test
    fun everyOpenVectorProducesItsExpectedResultAndReplaysRefuseSecondDelivery() {
        val allKeys = keysByKid()
        val openCases = vectors.getValue("open").jsonArray
        assertTrue(openCases.isNotEmpty())
        val observedReasons = LinkedHashSet<String>()

        for (rawCase in openCases) {
            val c = rawCase.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            val jwe = c.getValue("jwe").jsonPrimitive.content
            val caseKeys = c.getValue("keys").jsonArray.map { allKeys.getValue(it.jsonPrimitive.content).second }
            val claims = c.getValue("claims").jsonObject
            val now = c.getValue("now").jsonPrimitive.long
            val memory = SealedInputsMemoryJvm { now }
            for (accepted in c.getValue("accepted").jsonArray) {
                val acc = accepted.jsonObject
                assertTrue(
                    memory.remember(
                        SealedInputsDeliveryJvm(
                            hnd = acc.getValue("hnd").jsonPrimitive.content,
                            crq = acc.getValue("crq").jsonPrimitive.content,
                            exp = acc.getValue("exp").jsonPrimitive.long,
                        ),
                    ),
                    "$name: accepted seed",
                )
            }
            val actual = openSealedInputsJvm(jwe = jwe, keys = caseKeys, claims = claims, nowSeconds = now, remember = memory::remember)
            val expect = c.getValue("expect").jsonObject
            if (expect.getValue("ok").jsonPrimitive.boolean) {
                val expectedValues = expect.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content }
                assertEquals(OpenSealedInputsResultJvm.Ok(expectedValues), actual, name)
                // Replaying the same JWE against the same memory must be refused as `replayed`.
                val replayed = openSealedInputsJvm(jwe = jwe, keys = caseKeys, claims = claims, nowSeconds = now, remember = memory::remember)
                assertEquals(OpenSealedInputsResultJvm.Refused("replayed"), replayed, "$name: replay")
            } else {
                val expectedReason = expect.getValue("reason").jsonPrimitive.content
                observedReasons += expectedReason
                assertEquals(OpenSealedInputsResultJvm.Refused(expectedReason), actual, name)
            }
        }

        val allRequiredReasons =
            listOf(
                "claims_invalid", "malformed", "compression_not_allowed", "unsupported_algorithm", "unknown_kid",
                "aud_mismatch", "srv_mismatch", "sub_mismatch", "hnd_mismatch", "act_mismatch",
                "pid_mismatch", "pdg_mismatch", "crq_mismatch", "exp_mismatch",
                "expired", "decryption_failed", "plaintext_invalid", "value_too_long", "replayed",
            )
        for (reason in allRequiredReasons) {
            assertTrue(reason in observedReasons, "vectors must cover refusal reason $reason")
        }
    }

    @Test
    fun everySealVectorBuildsExactBindingAndRoundTripsWithExactProtectedHeaderOrder() {
        val allKeys = keysByKid()
        val sealCases = vectors.getValue("seal").jsonArray
        assertTrue(sealCases.isNotEmpty())

        for (rawCase in sealCases) {
            val c = rawCase.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            val kid = c.getValue("key").jsonPrimitive.content
            val (pubKey, privKey) = allKeys.getValue(kid)
            val bi = c.getValue("bindingInputs").jsonObject
            val preparedObj = bi["prepared"] as? JsonObject
            val bindingInputs =
                RaftSealedInputsBindingInputs(
                    handle = bi.getValue("handle").jsonPrimitive.content,
                    serverId = bi.getValue("serverId").jsonPrimitive.content,
                    userId = bi.getValue("userId").jsonPrimitive.content,
                    appClientKey = bi.getValue("appClientKey").jsonPrimitive.content,
                    actionId = bi.getValue("actionId").jsonPrimitive.content,
                    preparedId = preparedObj?.getValue("id")?.jsonPrimitive?.content,
                    preparedDigest = preparedObj?.getValue("digest")?.jsonPrimitive?.content,
                    clientRequestId = bi.getValue("clientRequestId").jsonPrimitive.content,
                    handleExp = bi.getValue("handleExp").jsonPrimitive.long,
                )
            val binding = sealedInputsBinding(bindingInputs)
            val expectedBinding = c.getValue("binding").jsonObject
            assertEquals(expectedBinding.getValue("aud").jsonPrimitive.content, binding.aud, name)
            assertEquals(expectedBinding.getValue("srv").jsonPrimitive.content, binding.srv, name)
            assertEquals(expectedBinding.getValue("sub").jsonPrimitive.content, binding.sub, name)
            assertEquals(expectedBinding.getValue("hnd").jsonPrimitive.content, binding.hnd, name)
            assertEquals(expectedBinding.getValue("act").jsonPrimitive.content, binding.act, name)
            assertEquals(expectedBinding["pid"]?.jsonPrimitive?.content, binding.pid, name)
            assertEquals(expectedBinding["pdg"]?.jsonPrimitive?.content, binding.pdg, name)
            assertEquals(expectedBinding.getValue("crq").jsonPrimitive.content, binding.crq, name)
            assertEquals(expectedBinding.getValue("exp").jsonPrimitive.long, binding.exp, name)

            val expectedHeader = c.getValue("header").jsonObject
            val builtHeader = sealedInputsProtectedHeader(kid, binding)
            assertEquals(expectedHeader, builtHeader, "$name: protected header values")
            assertEquals(expectedHeader.keys.toList(), builtHeader.keys.toList(), "$name: protected header key order")

            val values = c.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content }
            val jwe1 = sealInputs(pubKey, binding, values)
            val jwe2 = sealInputs(pubKey, binding, values)
            assertNotEquals(jwe1, jwe2, "$name: fresh ephemeral key and IV each time")
            val parts = jwe1.split('.')
            assertEquals(5, parts.size, name)
            assertEquals("", parts[1], "$name: empty encrypted key under ECDH-ES")

            val decodedHeader =
                Json.parseToJsonElement(java.util.Base64.getUrlDecoder().decode(parts[0]).decodeToString()).jsonObject
            val headerWithoutEpk = JsonObject(decodedHeader.filterKeys { it != "epk" })
            assertEquals(expectedHeader, headerWithoutEpk, "$name: encoded header matches vector")
            assertEquals(expectedHeader.keys.toList() + "epk", decodedHeader.keys.toList(), "$name: epk is last")
            val epk = assertNotNull(decodedHeader["epk"] as? JsonObject, "$name: epk present")
            assertEquals("EC", epk["kty"].cardString())
            assertEquals("P-256", epk["crv"].cardString())
            assertTrue(P256_COORDINATE_BASE64URL.matches(epk["x"].cardString().orEmpty()))
            assertTrue(P256_COORDINATE_BASE64URL.matches(epk["y"].cardString().orEmpty()))

            val claims = c.getValue("claims").jsonObject
            val now = c.getValue("now").jsonPrimitive.long
            val memory = SealedInputsMemoryJvm { now }
            val opened = openSealedInputsJvm(jwe = jwe1, keys = listOf(privKey), claims = claims, nowSeconds = now, remember = memory::remember)
            assertEquals(OpenSealedInputsResultJvm.Ok(values), opened, "$name: round-trip open")
        }
    }

    @Test
    fun sealInputsRejectsInvalidKeysOffCurvePointsAndOversizeValues() {
        val (pubKey, _) = keysByKid().values.first()
        val binding =
            sealedInputsBinding(
                RaftSealedInputsBindingInputs(
                    handle = "v1.handle",
                    serverId = "srv-1",
                    userId = "usr-1",
                    appClientKey = "app_test",
                    actionId = "save_key",
                    preparedId = "pid-1",
                    preparedDigest = "pdg-1",
                    clientRequestId = "crq-1",
                    handleExp = 1794700800L,
                ),
            )
        // Oversize value (> 4096 UTF-8 bytes)
        assertFailsWith<IllegalArgumentException> {
            sealInputs(pubKey, binding, mapOf("value" to "a".repeat(4097)))
        }
        assertFailsWith<IllegalArgumentException> {
            sealInputs(pubKey, binding, mapOf("value" to "é".repeat(2049)))
        }
        // Wrong alg
        assertFailsWith<IllegalArgumentException> {
            sealInputs(pubKey.copy(alg = "ECDH-ES+A256KW"), binding, emptyMap())
        }
        // Off-curve P-256 point (0, 0)
        val zeroCoord = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        assertFailsWith<IllegalArgumentException> {
            sealInputs(pubKey.copy(x = zeroCoord, y = zeroCoord), binding, emptyMap())
        }
        // Bad binding inputs
        assertFailsWith<IllegalArgumentException> {
            sealedInputsBinding(
                RaftSealedInputsBindingInputs(
                    handle = "",
                    serverId = "srv-1",
                    userId = "usr-1",
                    appClientKey = "app_test",
                    actionId = "save_key",
                    preparedId = null,
                    preparedDigest = null,
                    clientRequestId = "crq-1",
                    handleExp = 1794700800L,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            sealedInputsBinding(
                RaftSealedInputsBindingInputs(
                    handle = "h",
                    serverId = "srv-1",
                    userId = "usr-1",
                    appClientKey = "app_test",
                    actionId = "save_key",
                    preparedId = "pid-only",
                    preparedDigest = null,
                    clientRequestId = "crq-1",
                    handleExp = 1794700800L,
                ),
            )
        }
    }

    private fun resource(path: String): ByteArray =
        requireNotNull(SealedInputsVectorTest::class.java.getResourceAsStream(path)) { "Missing test resource: $path" }.use { it.readBytes() }
}
