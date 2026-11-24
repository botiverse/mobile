package build.raft.app

import build.raft.app.data.APP_LINK_HANDLE_FRESH_MILLIS
import build.raft.app.data.AppLinkActionDescription
import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkBlockCard
import build.raft.app.data.AppLinkExecuted
import build.raft.app.data.AppLinkPrepared
import build.raft.app.data.AppLinkResult
import build.raft.app.data.CardStack
import build.raft.app.data.RaftAppCardEncryptionKey
import build.raft.app.data.RaftAppLinkCardV2
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardSealTo
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.data.RaftMessageCardForm
import build.raft.app.data.RaftMessageCardFormField
import build.raft.app.data.RaftMessageCardFormProblem
import build.raft.app.data.messageCardFormInputs
import build.raft.app.data.messageCardFormProblem
import build.raft.app.data.messageCardFormProblems
import build.raft.app.data.messageCardFormSecrets
import build.raft.app.data.messageCardFormSubmissionFits
import build.raft.app.data.messageCardSecretInsertFits
import build.raft.app.data.messageCardSecretValueFits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Common tests for RFC 086 §5.1 / §5.3 / §5.4 card forms: validation rules, TextInput/SecretInput
 * extraction, prepare vs execute flows, undo-window forms, stale-handle form changes, and
 * fail-closed sealing guards (key rotation, client_key change, revision change, expiring handle,
 * failed reread, and secret map clearing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadAppLinkCardFormPressTest {
    private val url = "https://vault.example.test/keys/new"
    private val marker = "marker-secret-7f3c"
    private val keyA =
        RaftAppCardEncryptionKey(
            kid = "test-2026-11-a",
            x = "_dsSyV6Maib6vDmb1MLPu-31qcVvNkbxUpWAfDnOGcA",
            y = "kIEVAwl4P1JF6_ondOiBCJDqXAD0wAM-xXM8OvfmmMM",
        )
    private val keyB =
        RaftAppCardEncryptionKey(
            kid = "test-2026-11-b",
            x = "CqGCUqeePcCjakLbGfDmQ0-amywdywBRz3dQ1nLcQmQ",
            y = "oCifOChZnB6utMFEKdG4N3qziepsYEDSm3i9EzV3HrM",
        )
    private val textForm =
        RaftMessageCardForm(
            listOf(
                RaftMessageCardFormField.TextInput("name", "Name", required = true, maxLength = 20, defaultValue = "deploy-key"),
                RaftMessageCardFormField.TextInput("note", "Note"),
            ),
        )
    private val secretForm =
        RaftMessageCardForm(
            listOf(
                RaftMessageCardFormField.TextInput("name", "Name"),
                RaftMessageCardFormField.SecretInput("value", "Value", required = true),
            ),
        )
    private val optionalSecretForm =
        RaftMessageCardForm(
            listOf(
                RaftMessageCardFormField.SecretInput("token", "Token", required = false),
            ),
        )
    private val prepared =
        AppLinkPrepared(
            preparedActionId = "6b0a3c1e-2f4d-4a8b-9c7e-1d2e3f4a5b6c",
            preparedDigest = "a".repeat(64),
            description = AppLinkActionDescription(title = "Save key", confirmLabel = "Save", cancelLabel = "Cancel"),
        )

    private data class RecordedExecute(
        val handle: String,
        val clientRequestId: String,
        val prepared: AppLinkPrepared?,
        val inputs: Map<String, String>?,
        val sealedInputs: String?,
    )

    private class Recorder {
        val prepares = mutableListOf<String>()
        val executes = mutableListOf<RecordedExecute>()
        val toasts = mutableListOf<String>()
        var rereads = 0
        var sealerCalls = 0
    }

    private fun makeCard(
        action: RaftLinkCardButtonAction.Execute,
        handle: String = "h-save",
        sealTo: RaftLinkCardSealTo? = null,
        reviewed: Boolean? = null,
        readAtMillis: Long? = 1_000_000L,
    ): Pair<AppLinkResult.Card, RaftLinkCardV2Element.Button> {
        val button = RaftLinkCardV2Element.Button("Save", RaftLinkCardButtonVariant.Default, action)
        val card =
            AppLinkResult.Card(
                url = url,
                app = AppLinkApp("Vault", null, reviewed = reviewed),
                title = "Save key",
                block =
                    AppLinkBlockCard(
                        title = "Save key",
                        card = RaftAppLinkCardV2("root", mapOf("root" to CardStack(children = listOf("save")), "save" to button)),
                        businessRevision = sealTo?.revision ?: "3",
                    ),
                chip = false,
                handles = mapOf(action.actionId to handle),
                readAtMillis = readAtMillis,
                sealsTo = sealTo?.let { mapOf(action.actionId to it) }.orEmpty(),
                forms = action.form?.let { mapOf(action.actionId to it) }.orEmpty(),
            )
        return card to button
    }

    private fun makePress(
        scope: CoroutineScope,
        card: AppLinkResult.Card,
        recorder: Recorder,
        nowMillis: () -> Long = { 1_000_000L },
        rereadOk: () -> Boolean = { true },
        heldCard: () -> AppLinkResult.Card? = { card },
        viewerKey: String? = "viewer-1:server-1",
    ): ThreadAppLinkCardPress {
        val host =
            ThreadAppLinkCardActionHost(
                prepare = { handle, _ ->
                    recorder.prepares += handle
                    AppLinkActionResult.Ok(prepared)
                },
                execute = { _, _, _ -> error("executeWithForm should be called") },
                reread = {
                    recorder.rereads += 1
                    rereadOk()
                },
                toast = { recorder.toasts += it },
                toastWithIntent = { text, _ -> recorder.toasts += text },
                held = { heldCard() },
                nowMillis = nowMillis,
                executeWithForm = { handle, requestId, prep, inputs, sealed ->
                    recorder.executes += RecordedExecute(handle, requestId, prep, inputs, sealed)
                    AppLinkActionResult.Ok(AppLinkExecuted("op-1", "done", null))
                },
                viewerKey = viewerKey,
            )
        return ThreadAppLinkCardPress(
            card = card,
            actions = host,
            scope = scope,
            failedText = "failed",
            undoLabel = "Undo",
            refreshFailedText = "refresh_failed",
            changedText = "changed",
            sealStaleText = "seal_stale",
            sealer = { key, binding, _ ->
                recorder.sealerCalls += 1
                "hdr_${key.kid}_${binding.crq}..iv.ct.tag"
            },
        )
    }

    @Test
    fun formValidationCountsCodePointsForTextInputAndUtf8BytesForSecretInput() {
        val name = textForm.fields[0]
        val note = textForm.fields[1]
        assertEquals(RaftMessageCardFormProblem.Required, messageCardFormProblem(name, ""))
        assertNull(messageCardFormProblem(note, ""))
        assertEquals(RaftMessageCardFormProblem.OneLine, messageCardFormProblem(name, "a\u2028b"))
        assertEquals(RaftMessageCardFormProblem.OneLine, messageCardFormProblem(name, "a\nb"))
        assertNull(messageCardFormProblem(name, "😀".repeat(20)), "20 code points fit maxLength 20")
        assertEquals(RaftMessageCardFormProblem.TooLong, messageCardFormProblem(name, "😀".repeat(21)))
        // Both tooLong and multiline: tooLong wins (matching web linkCardForm.ts).
        assertEquals(RaftMessageCardFormProblem.TooLong, messageCardFormProblem(name, "a".repeat(20) + "\nb"))

        val secret = secretForm.fields[1]
        assertEquals(RaftMessageCardFormProblem.Required, messageCardFormProblem(secret, ""))
        assertNull(messageCardFormProblem(secret, "a".repeat(4096)))
        assertEquals(RaftMessageCardFormProblem.TooLong, messageCardFormProblem(secret, "a".repeat(4097)))
        assertEquals(RaftMessageCardFormProblem.TooLong, messageCardFormProblem(secret, "é".repeat(2049)), "4098 UTF-8 bytes")
        assertTrue(messageCardSecretValueFits("€".repeat(1365) + "a")) // 4096 bytes
        assertFalse(messageCardSecretValueFits("€".repeat(1365) + "aa")) // 4097 bytes
        assertTrue(messageCardSecretInsertFits("abc", 1, 2, "x".repeat(4094)))
        assertFalse(messageCardSecretInsertFits("abc", 1, 2, "x".repeat(4095)))
        assertEquals(mapOf("name" to RaftMessageCardFormProblem.Required), messageCardFormProblems(textForm, mapOf("name" to "")))
    }

    @Test
    fun formExtractionOmitsEmptyOptionalsAndKeepsSecretsSeparateFromPlainInputs() {
        assertEquals(mapOf("name" to "k"), messageCardFormInputs(textForm, mapOf("name" to "k", "note" to "")))
        assertEquals(mapOf("name" to "k", "note" to "n"), messageCardFormInputs(textForm, mapOf("name" to "k", "note" to "n")))
        assertNull(messageCardFormInputs(RaftMessageCardForm(listOf(textForm.fields[1])), mapOf("note" to "")))
        assertEquals(mapOf("name" to "k"), messageCardFormInputs(secretForm, mapOf("name" to "k", "value" to marker)))
        assertNull(messageCardFormInputs(optionalSecretForm, mapOf("token" to marker)))

        assertNull(messageCardFormSecrets(textForm, mapOf("name" to "k")))
        assertEquals<Map<String, String>?>(mapOf("value" to marker), messageCardFormSecrets(secretForm, mapOf("name" to "k", "value" to marker)))
        // Optional SecretInput left empty STILL returns a non-null empty map so sealed_inputs is sent encrypting `{}`.
        assertEquals<Map<String, String>?>(emptyMap(), messageCardFormSecrets(optionalSecretForm, mapOf("token" to "")))

        assertTrue(messageCardFormSubmissionFits(null, null, null))
        assertFalse(messageCardFormSubmissionFits(null, mapOf("a" to "b"), null))
        assertTrue(messageCardFormSubmissionFits(textForm, mapOf("name" to "ok"), null))
        assertFalse(messageCardFormSubmissionFits(textForm, mapOf("name" to "ok"), "a..b.c.d"))
        assertTrue(messageCardFormSubmissionFits(secretForm, mapOf("name" to "ok"), "a..b.c.d"))
        assertFalse(messageCardFormSubmissionFits(secretForm, mapOf("name" to "ok"), null))
    }

    @Test
    fun undoWindowButtonWithTextInputFormOpensDialogEvenWhenReviewedThenSendsInputsAfterWindow() = runTest {
        val action = RaftLinkCardButtonAction.Execute("save_key", confirm = false, undoWindowMs = 2000, form = textForm)
        val (card, button) = makeCard(action, reviewed = null)
        val recorder = Recorder()
        val press = makePress(this, card, recorder)

        assertNotNull(press.button(button, action)).onClick()
        runCurrent()
        val pending = assertNotNull(press.confirmation, "undo-window button with form opens dialog first")
        assertFalse(pending.unreviewed)
        assertNull(pending.prepared)
        assertEquals(textForm, pending.form)
        assertTrue(recorder.prepares.isEmpty())
        assertTrue(recorder.executes.isEmpty())

        press.confirm(inputs = mapOf("name" to "deploy-key"))
        runCurrent()
        assertNull(press.confirmation)
        assertEquals("Undo", assertNotNull(press.button(button, action)).label)
        advanceTimeBy(1999)
        runCurrent()
        assertTrue(recorder.executes.isEmpty())
        advanceTimeBy(2)
        runCurrent()

        assertEquals(1, recorder.executes.size)
        assertEquals(mapOf("name" to "deploy-key"), recorder.executes.single().inputs)
        assertNull(recorder.executes.single().sealedInputs)
        assertTrue(recorder.prepares.isEmpty())
    }

    @Test
    fun staleHandleRereadRefusesWhenFormChanged() = runTest {
        val action = RaftLinkCardButtonAction.Execute("save_key", confirm = true, form = textForm)
        val updatedAction =
            action.copy(form = RaftMessageCardForm(listOf(RaftMessageCardFormField.TextInput("other", "Other", required = true))))
        val now = 20 * 60_000L
        val (oldCard, button) = makeCard(action, handle = "h-old", readAtMillis = now - APP_LINK_HANDLE_FRESH_MILLIS - 1)
        val (freshCard, _) = makeCard(updatedAction, handle = "h-fresh", readAtMillis = now)
        val recorder = Recorder()
        val press = makePress(this, oldCard, recorder, nowMillis = { now }, heldCard = { freshCard })

        assertNotNull(press.button(button, action)).onClick()
        advanceUntilIdle()
        assertNull(press.confirmation)
        assertTrue(recorder.prepares.isEmpty())
        assertEquals(listOf("changed"), recorder.toasts)
    }

    @Test
    fun secretFormFailsClosedOnKeyRotationClientKeyChangeRevisionChangeReadFailureAndExpiry() = runTest {
        val action = RaftLinkCardButtonAction.Execute("save_key", confirm = true, form = secretForm)
        val now = 1_000_000L
        val validSealTo = RaftLinkCardSealTo(key = keyA, clientKey = "app_test_vault", handleExp = 2000L, revision = "3")

        // 1. Key rotation (kid / x / y changed)
        run {
            val (card, button) = makeCard(action, sealTo = validSealTo, readAtMillis = now)
            val rotated = makeCard(action, sealTo = validSealTo.copy(key = keyB), readAtMillis = now).first
            val recorder = Recorder()
            val press = makePress(this, card, recorder, nowMillis = { now }, heldCard = { rotated })
            assertNotNull(press.button(button, action)).onClick()
            runCurrent()
            val secrets = mutableMapOf("value" to marker)
            press.confirm(inputs = mapOf("name" to "k"), secrets = secrets)
            advanceUntilIdle()
            assertEquals(1, recorder.rereads)
            assertEquals(0, recorder.sealerCalls, "must not seal after key rotation")
            assertTrue(recorder.executes.isEmpty())
            assertTrue(secrets.isEmpty(), "secrets map must be cleared in finally")
            assertNull(press.confirmation)
            assertEquals(listOf("seal_stale"), recorder.toasts)
        }

        // 2. Client key change
        run {
            val (card, button) = makeCard(action, sealTo = validSealTo, readAtMillis = now)
            val changedClient = makeCard(action, sealTo = validSealTo.copy(clientKey = "other_app"), readAtMillis = now).first
            val recorder = Recorder()
            val press = makePress(this, card, recorder, nowMillis = { now }, heldCard = { changedClient })
            assertNotNull(press.button(button, action)).onClick()
            runCurrent()
            val secrets = mutableMapOf("value" to marker)
            press.confirm(inputs = null, secrets = secrets)
            advanceUntilIdle()
            assertEquals(0, recorder.sealerCalls)
            assertTrue(recorder.executes.isEmpty())
            assertTrue(secrets.isEmpty())
            assertEquals(listOf("seal_stale"), recorder.toasts)
        }

        // 3. Card revision change
        run {
            val (card, button) = makeCard(action, sealTo = validSealTo, readAtMillis = now)
            val changedRev = makeCard(action, sealTo = validSealTo.copy(revision = "4"), readAtMillis = now).first
            val recorder = Recorder()
            val press = makePress(this, card, recorder, nowMillis = { now }, heldCard = { changedRev })
            assertNotNull(press.button(button, action)).onClick()
            runCurrent()
            val secrets = mutableMapOf("value" to marker)
            press.confirm(inputs = null, secrets = secrets)
            advanceUntilIdle()
            assertEquals(0, recorder.sealerCalls)
            assertTrue(recorder.executes.isEmpty())
            assertTrue(secrets.isEmpty())
            assertEquals(listOf("seal_stale"), recorder.toasts)
        }

        // 4. Reread failed
        run {
            val (card, button) = makeCard(action, sealTo = validSealTo, readAtMillis = now)
            val recorder = Recorder()
            val press = makePress(this, card, recorder, nowMillis = { now }, rereadOk = { false })
            assertNotNull(press.button(button, action)).onClick()
            runCurrent()
            val secrets = mutableMapOf("value" to marker)
            press.confirm(inputs = null, secrets = secrets)
            advanceUntilIdle()
            assertEquals(0, recorder.sealerCalls)
            assertTrue(recorder.executes.isEmpty())
            assertTrue(secrets.isEmpty())
            assertEquals(listOf("refresh_failed"), recorder.toasts)
        }

        // 5. Handle expiring (< 5s left) before reread: refuses immediately without even rereading.
        run {
            val expiringSealTo = validSealTo.copy(handleExp = (now / 1000L) + 3L)
            val (card, button) = makeCard(action, sealTo = expiringSealTo, readAtMillis = now)
            val recorder = Recorder()
            val press = makePress(this, card, recorder, nowMillis = { now })
            assertNotNull(press.button(button, action)).onClick()
            runCurrent()
            val secrets = mutableMapOf("value" to marker)
            press.confirm(inputs = null, secrets = secrets)
            advanceUntilIdle()
            assertEquals(0, recorder.rereads, "expiring handle refuses before reread")
            assertEquals(0, recorder.sealerCalls)
            assertTrue(recorder.executes.isEmpty())
            assertTrue(secrets.isEmpty())
            assertEquals(listOf("seal_stale"), recorder.toasts)
        }
    }

    @Test
    fun optionalSecretFormLeftEmptyStillSealsAndSendsSealedInputs() = runTest {
        val action = RaftLinkCardButtonAction.Execute("save_key", confirm = true, form = optionalSecretForm)
        val now = 1_000_000L
        val sealTo = RaftLinkCardSealTo(key = keyA, clientKey = "app_test_vault", handleExp = 2000L, revision = "3")
        val (card, button) = makeCard(action, sealTo = sealTo, readAtMillis = now)
        val recorder = Recorder()
        val press = makePress(this, card, recorder, nowMillis = { now })

        assertNotNull(press.button(button, action)).onClick()
        runCurrent()
        val secrets = assertNotNull(messageCardFormSecrets(optionalSecretForm, emptyMap()))
        press.confirm(inputs = null, secrets = secrets)
        advanceUntilIdle()

        assertEquals(1, recorder.sealerCalls)
        assertEquals(1, recorder.executes.size)
        assertNull(recorder.executes.single().inputs)
        assertNotNull(recorder.executes.single().sealedInputs)
    }
}
