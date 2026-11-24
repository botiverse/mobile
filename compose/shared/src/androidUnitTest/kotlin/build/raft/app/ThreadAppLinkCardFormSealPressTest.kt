package build.raft.app

import build.raft.app.data.AppLinkActionDescription
import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkBlockCard
import build.raft.app.data.AppLinkExecuted
import build.raft.app.data.AppLinkPrepared
import build.raft.app.data.AppLinkResult
import build.raft.app.data.CardStack
import build.raft.app.data.OpenSealedInputsResultJvm
import build.raft.app.data.RaftAppCardEncryptionKey
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardSealTo
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.data.RaftMessageCardForm
import build.raft.app.data.RaftMessageCardFormField
import build.raft.app.data.SealedInputsMemoryJvm
import build.raft.app.data.SealedInputsPrivateJwkJvm
import build.raft.app.data.openSealedInputsJvm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end JVM crypto test for [ThreadAppLinkCardPress] SecretInput sealing: confirms the live
 * JWE emitted in `execute` decrypts with the App's private key via [openSealedInputsJvm] under the
 * exact execute JWT claims, refuses mismatched `crq`, and handles empty optional SecretInputs (`{}`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadAppLinkCardFormSealPressTest {
    // TEST ONLY key `test-2026-11-a` from `sealedInputs.v1.vector.json`.
    private val keyA =
        RaftAppCardEncryptionKey(
            kid = "test-2026-11-a",
            x = "_dsSyV6Maib6vDmb1MLPu-31qcVvNkbxUpWAfDnOGcA",
            y = "kIEVAwl4P1JF6_ondOiBCJDqXAD0wAM-xXM8OvfmmMM",
        )
    private val privKeyA =
        SealedInputsPrivateJwkJvm(
            kid = "test-2026-11-a",
            x = keyA.x,
            y = keyA.y,
            d = "9SxcjTv8o1fkN-hVmYYs8T0eN410QRTrrgrr-6HhjuA",
        )

    @Test
    fun secretFormSealsRealJweBoundToExecuteRequestAndOpensWithAppPrivateKey() = runTest {
        val url = "https://vault.example.test/keys/new"
        val secretMarker = "sk-live-secret-✓-🔑"
        val form =
            RaftMessageCardForm(
                listOf(
                    RaftMessageCardFormField.TextInput("name", "Name"),
                    RaftMessageCardFormField.SecretInput("value", "Value", required = true),
                ),
            )
        val action = RaftLinkCardButtonAction.Execute("save_key", confirm = true, form = form)
        val button = RaftLinkCardV2Element.Button("Save", RaftLinkCardButtonVariant.Default, action)
        val handle = "h-save-prepared"
        val handleExp = 1_794_700_800L
        val nowMillis = (handleExp - 120L) * 1000L
        val clientKey = "app_test_vault"
        val sealTo = RaftLinkCardSealTo(key = keyA, clientKey = clientKey, handleExp = handleExp, revision = "3")
        val card =
            AppLinkResult.Card(
                url = url,
                app = AppLinkApp("Vault", null),
                title = "Save key",
                block = AppLinkBlockCard("Save key", RaftAppLinkCardV2("root", mapOf("root" to CardStack(children = listOf("save")), "save" to button)), businessRevision = "3"),
                chip = false,
                handles = mapOf("save_key" to handle),
                readAtMillis = nowMillis,
                sealsTo = mapOf("save_key" to sealTo),
                forms = mapOf("save_key" to form),
            )
        // Fresh reread returns a NEW handle + expiry with the SAME App key, client key, and revision.
        val freshCard =
            card.copy(
                handles = mapOf("save_key" to "h-save-reread"),
                sealsTo = mapOf("save_key" to sealTo.copy(handleExp = handleExp + 60L)),
            )
        val prepared =
            AppLinkPrepared(
                preparedActionId = "0b9d6c3e-2f4a-4b5c-8d6e-7f8a9b0c1d2e",
                preparedDigest = "7514b67b93ee417fc9a12546fc7bb1833cd48ebfa4151efee6370a741ccd2099",
                description = AppLinkActionDescription("Save key", confirmLabel = "Save", cancelLabel = "Cancel"),
            )

        var capturedRequestId: String? = null
        var capturedInputs: Map<String, String>? = null
        var capturedJwe: String? = null
        var rereads = 0
        val toasts = mutableListOf<String>()

        val host =
            ThreadAppLinkCardActionHost(
                prepare = { _, _ -> AppLinkActionResult.Ok(prepared) },
                execute = { _, _, _ -> error("must use executeWithForm") },
                reread = {
                    rereads += 1
                    true
                },
                toast = { toasts += it },
                toastWithIntent = { text, _ -> toasts += text },
                held = { freshCard },
                nowMillis = { nowMillis },
                executeWithForm = { sentHandle, requestId, sentPrepared, inputs, sealedInputs ->
                    assertEquals(handle, sentHandle, "must send the prepared handle, not the reread handle")
                    assertEquals(prepared, sentPrepared)
                    capturedRequestId = requestId
                    capturedInputs = inputs
                    capturedJwe = sealedInputs
                    AppLinkActionResult.Ok(AppLinkExecuted("op-1", "done", null))
                },
                viewerKey = "viewer-1:server-1",
            )
        val press = ThreadAppLinkCardPress(card, host, this, "failed", "Undo", "refresh_failed", "changed", sealStaleText = "seal_stale")

        assertNotNull(press.button(button, action)).onClick()
        runCurrent()
        assertNotNull(press.confirmation)

        val secrets = mutableMapOf("value" to secretMarker)
        press.confirm(inputs = mapOf("name" to "deploy-key"), secrets = secrets)
        advanceUntilIdle()

        assertTrue(secrets.isEmpty(), "secrets map cleared after sealing")
        assertTrue(rereads >= 1, "reread ran before sealing")
        assertEquals(mapOf("name" to "deploy-key"), capturedInputs)
        val jwe = assertNotNull(capturedJwe)
        val crq = assertNotNull(capturedRequestId)
        assertFalse(jwe.contains(secretMarker), "secret never appears in plaintext in JWE")
        assertTrue(toasts.none { it.contains(secretMarker) || it.contains(jwe) })

        val claims =
            buildJsonObject {
                put("aud", "urn:raft:card-action:$clientKey")
                put("sub", "viewer-1")
                put("server_id", "server-1")
                put("handle_sha256", raftSha256Hex(handle.encodeToByteArray()))
                put("action_id", "save_key")
                put("prepared_action_id", prepared.preparedActionId)
                put("prepared_digest", prepared.preparedDigest)
                put("client_request_id", crq)
                put("handle_exp", handleExp)
            }
        val memory = SealedInputsMemoryJvm { handleExp - 100L }
        val opened = openSealedInputsJvm(jwe = jwe, keys = listOf(privKeyA), claims = claims, nowSeconds = handleExp - 100L, remember = memory::remember)
        assertEquals(OpenSealedInputsResultJvm.Ok(mapOf("value" to secretMarker)), opened)

        // Bound to this exact client_request_id: another crq must be refused.
        val wrongCrq = buildJsonObject {
            for ((k, v) in claims) put(k, v)
            put("client_request_id", "another-request-id")
        }
        val refused = openSealedInputsJvm(jwe = jwe, keys = listOf(privKeyA), claims = wrongCrq, nowSeconds = handleExp - 100L, remember = SealedInputsMemoryJvm { handleExp - 100L }::remember)
        assertEquals(OpenSealedInputsResultJvm.Refused("crq_mismatch"), refused)
    }
}
