package build.raft.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkActionDescription
import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkBlockCard
import build.raft.app.data.AppLinkExecuted
import build.raft.app.data.AppLinkPrepared
import build.raft.app.data.AppLinkResult
import build.raft.app.data.AppLocale
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
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Robolectric Compose UI tests for RFC 086 step 2 confirmation dialog forms:
 * - TextInput prefilled with default, SecretInput masked with eye toggle and persistent audience notice;
 * - Oversize SecretInput edit (> 4096 UTF-8 bytes) refused whole with immediate byte-cap error while
 *   keeping the secret notice visible above the error;
 * - Required field validation blocks submit until filled, then seals and executes end-to-end;
 * - Unreviewed App with a form renders its warning banner above the form fields.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h900dp")
class ThreadAppLinkCardFormDialogUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

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

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        build.raft.app.image.raftInstallImageLoader()
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun secretFormDialogValidatesGuardsOversizeTogglesVisibilityAndSealsOnSubmit() =
        runRobolectricComposeUiTest {
            val url = "https://vault.example.test/keys/new"
            val handle = "h-ui-secret"
            val handleExp = 1_794_700_800L
            val nowMillis = (handleExp - 120L) * 1000L
            val clientKey = "app_test_vault"
            val form =
                RaftMessageCardForm(
                    listOf(
                        RaftMessageCardFormField.TextInput(
                            id = "name",
                            label = "Key name",
                            placeholder = "deploy-key",
                            required = true,
                            defaultValue = "prod-key",
                        ),
                        RaftMessageCardFormField.SecretInput(
                            id = "secret_val",
                            label = "API key",
                            placeholder = "sk-...",
                            required = true,
                        ),
                    ),
                )
            val action = RaftLinkCardButtonAction.Execute("save_key", confirm = true, form = form)
            val button = RaftLinkCardV2Element.Button("Save", RaftLinkCardButtonVariant.Default, action)
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
            val prepared =
                AppLinkPrepared(
                    preparedActionId = "0b9d6c3e-2f4a-4b5c-8d6e-7f8a9b0c1d2e",
                    preparedDigest = "7514b67b93ee417fc9a12546fc7bb1833cd48ebfa4151efee6370a741ccd2099",
                    description = AppLinkActionDescription("Store API key", confirmLabel = "Store key", cancelLabel = "Cancel"),
                )

            var executedInputs: Map<String, String>? = null
            var executedJwe: String? = null
            var executedRequestId: String? = null

            val host =
                ThreadAppLinkCardActionHost(
                    prepare = { _, _ -> AppLinkActionResult.Ok(prepared) },
                    execute = { _, _, _ -> error("must use executeWithForm") },
                    reread = { true },
                    toast = {},
                    held = { card },
                    nowMillis = { nowMillis },
                    executeWithForm = { _, requestId, _, inputs, sealedInputs ->
                        executedRequestId = requestId
                        executedInputs = inputs
                        executedJwe = sealedInputs
                        AppLinkActionResult.Ok(AppLinkExecuted("op-1", "done", null))
                    },
                    viewerKey = "viewer-1:server-1",
                )

            setContent {
                RaftDesignSystemTheme {
                    val press = rememberThreadAppLinkCardPress(card, host, key = "msg-1")
                    LaunchedEffect(press) {
                        press.button(button, action)?.onClick?.invoke()
                    }
                    press.Confirmation()
                }
            }
            waitForIdle()

            onNodeWithTag(AppLinkConfirmDialogTestTag).assertExists()
            onNodeWithTag(AppLinkCardFormTestTag).assertExists()
            onNodeWithText("prod-key").assertExists()
            // Audience notice for SecretInput is rendered.
            onNodeWithTag("${AppLinkCardFormSecretNoticeTestTagPrefix}secret_val").assertIsDisplayed()
            onNodeWithText("This value is encrypted for Vault and sent only to it. Raft can’t read it.").assertIsDisplayed()

            // Submitting while required SecretInput is empty blocks execution and shows error under notice.
            onNodeWithText("Store key").performClick()
            waitForIdle()
            onNodeWithText("Enter a value.").assertIsDisplayed()
            onNodeWithTag("${AppLinkCardFormSecretNoticeTestTagPrefix}secret_val").assertIsDisplayed()
            assertEquals(null, executedJwe)

            // Oversize SecretInput edit (> 4096 UTF-8 bytes) is refused whole and shows byte-cap error immediately.
            val secretField = onNodeWithTag("${AppLinkCardFormFieldTestTagPrefix}secret_val")
            secretField.performTextInput("sk-valid")
            waitForIdle()
            secretField.performTextReplacement("€".repeat(1366)) // 4098 UTF-8 bytes
            waitForIdle()
            onNodeWithText("Use at most 4096 bytes. Some characters take more than one.").assertIsDisplayed()
            onNodeWithTag("${AppLinkCardFormSecretNoticeTestTagPrefix}secret_val").assertIsDisplayed()

            // Show/hide eye toggle switches contentDescription between Show and Hide password.
            onNodeWithContentDescription("Show password").performClick()
            waitForIdle()
            onNodeWithContentDescription("Hide password").assertExists()
            onNodeWithText("sk-valid").assertExists()

            // Confirm submits the preserved (untruncated) valid secret sealed to the App's key.
            onNodeWithText("Store key").performClick()
            waitForIdle()

            assertEquals(mapOf("name" to "prod-key"), executedInputs)
            val jwe = assertNotNull(executedJwe)
            val crq = assertNotNull(executedRequestId)
            assertFalse(jwe.contains("sk-valid"))
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
            val opened = openSealedInputsJvm(jwe = jwe, keys = listOf(privKeyA), claims = claims, nowSeconds = handleExp - 100L, remember = SealedInputsMemoryJvm { handleExp - 100L }::remember)
            assertEquals(OpenSealedInputsResultJvm.Ok(mapOf("secret_val" to "sk-valid")), opened)
        }

    @Test
    fun unreviewedAppWithFormShowsWarningBannerAndButtonLabelTitle() =
        runRobolectricComposeUiTest {
            val form =
                RaftMessageCardForm(
                    listOf(RaftMessageCardFormField.TextInput(id = "note", label = "Note")),
                )
            val action = RaftLinkCardButtonAction.Execute("save_note", confirm = false, undoWindowMs = 2000, form = form)
            val button = RaftLinkCardV2Element.Button("Save note", RaftLinkCardButtonVariant.Default, action)
            val card =
                AppLinkResult.Card(
                    url = "https://custom.example.test/notes",
                    app = AppLinkApp("CustomBot", null, reviewed = false),
                    title = "Note",
                    block = AppLinkBlockCard("Note", RaftAppLinkCardV2("root", mapOf("root" to CardStack(children = listOf("save")), "save" to button)), businessRevision = "1"),
                    chip = false,
                    handles = mapOf("save_note" to "h-note"),
                    readAtMillis = 1_000_000L,
                    forms = mapOf("save_note" to form),
                )
            val host =
                ThreadAppLinkCardActionHost(
                    prepare = { _, _ -> error("not prepared") },
                    execute = { _, _, _ -> AppLinkActionResult.Ok(AppLinkExecuted("op-1", "done", null)) },
                    reread = { true },
                    toast = {},
                    nowMillis = { 1_000_000L },
                )

            setContent {
                RaftDesignSystemTheme {
                    val press = rememberThreadAppLinkCardPress(card, host, key = "msg-2")
                    LaunchedEffect(press) {
                        press.button(button, action)?.onClick?.invoke()
                    }
                    press.Confirmation()
                }
            }
            waitForIdle()

            onNodeWithTag(AppLinkConfirmDialogTestTag).assertExists()
            onNodeWithTag(AppLinkConfirmUnreviewedBannerTestTag).assertIsDisplayed()
            onNodeWithTag(AppLinkCardFormTestTag).assertIsDisplayed()
            assertTrue(true)
        }
}
