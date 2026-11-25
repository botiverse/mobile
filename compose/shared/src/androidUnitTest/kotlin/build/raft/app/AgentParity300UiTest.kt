package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for 1.31.0 web parity (`#wg-mobile-sync:#300`):
 * - `#16606`: Copy Advanced env vars from another agent on the same Computer (<=8 & >8 searchable)
 * - `#16578`: Computer description in Create Agent (options + chosen-computer hint) & Move Agent pickers
 * - `#16589` & `#16573`: External agent dialog titles ("Connect an external agent")
 * - 8-locale string verification for all new & updated strings including Russian CLDR plurals
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AgentParity300UiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private val sampleCopySource =
        RuntimeEnvVarsCopySource(
            computerName = "Mia's MacBook Pro",
            resetKey = "machine-1:codex",
            candidates =
                listOf(
                    CopyableAgentEnvVars(
                        agentId = "atlas",
                        name = "Atlas",
                        avatarUrl = null,
                        entries =
                            listOf(
                                CopyableEnvVarEntry("HTTP_PROXY", "http://proxy.corp.internal:8080"),
                                CopyableEnvVarEntry("HTTPS_PROXY", "http://proxy.corp.internal:8080"),
                                CopyableEnvVarEntry("CODEX_HOME", "/Users/mia/.codex-work"),
                            ),
                    ),
                    CopyableAgentEnvVars(
                        agentId = "juniper",
                        name = "Juniper",
                        avatarUrl = null,
                        entries =
                            listOf(
                                CopyableEnvVarEntry("LOG_LEVEL", "info"),
                                CopyableEnvVarEntry("NO_PROXY", "localhost,127.0.0.1"),
                            ),
                    ),
                ),
        )

    @Test
    fun envVarsCopyButtonHiddenWhenNoCandidates() =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    EnvVarsEditor(
                        entries = emptyList(),
                        onChange = {},
                        enabled = true,
                        copySource = RuntimeEnvVarsCopySource(emptyList(), "Mia's MacBook Pro", "m1:codex"),
                    )
                }
            }
            onNodeWithTag("env-vars-copy-from-agent").assertDoesNotExist()
            onNodeWithText("+ Add env var").assertExists()
        }

    @Test
    fun envVarsCopyAppendsWithoutOverwritingAndResetsStatusOnKeyChange() =
        runRobolectricComposeUiTest {
            var currentEntries by mutableStateOf(listOf("LOG_LEVEL" to "debug"))
            var activeCopySource by mutableStateOf(sampleCopySource)
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().padding(16.dp)) {
                        EnvVarsEditor(
                            entries = currentEntries,
                            onChange = { currentEntries = it },
                            enabled = true,
                            copySource = activeCopySource,
                        )
                    }
                }
            }

            onNodeWithTag("env-vars-copy-from-agent").performClick()
            onNodeWithText("Agents on Mia's MacBook Pro").assertExists()
            onNodeWithText("3 variables: HTTP_PROXY, HTTPS_PROXY, CODEX_HOME").assertExists()

            onNodeWithTag("env-vars-copy-agent-atlas").performClick()
            onNodeWithTag("env-vars-copy-status").assertTextEquals("Copied 3 from Atlas.")
            assertEquals(
                listOf(
                    "LOG_LEVEL" to "debug",
                    "HTTP_PROXY" to "http://proxy.corp.internal:8080",
                    "HTTPS_PROXY" to "http://proxy.corp.internal:8080",
                    "CODEX_HOME" to "/Users/mia/.codex-work",
                ),
                currentEntries,
            )

            // Copying from Juniper keeps LOG_LEVEL=debug and appends NO_PROXY.
            onNodeWithTag("env-vars-copy-from-agent").performClick()
            onNodeWithTag("env-vars-copy-agent-juniper").performClick()
            onNodeWithTag("env-vars-copy-status").assertTextEquals("Copied 1 from Juniper. Kept your LOG_LEVEL.")
            assertEquals("debug", currentEntries.first { it.first == "LOG_LEVEL" }.second)
            assertTrue(currentEntries.any { it.first == "NO_PROXY" })

            // Re-copying Atlas adds nothing new and names kept keys.
            onNodeWithTag("env-vars-copy-from-agent").performClick()
            onNodeWithTag("env-vars-copy-agent-atlas").performClick()
            onNodeWithTag("env-vars-copy-status")
                .assertTextEquals("Nothing new to copy from Atlas. Kept your HTTP_PROXY, HTTPS_PROXY, CODEX_HOME.")

            // Switching Computer / runtime clears the status line and stays cleared on round-trip.
            activeCopySource = sampleCopySource.copy(resetKey = "machine-2:codex")
            waitForIdle()
            onNodeWithTag("env-vars-copy-status").assertDoesNotExist()
            activeCopySource = sampleCopySource
            waitForIdle()
            onNodeWithTag("env-vars-copy-status").assertDoesNotExist()
        }

    @Test
    fun envVarsCopySwitchesToSearchableMenuPastEightCandidates() =
        runRobolectricComposeUiTest {
            val manyCandidates =
                (1..9).map { idx ->
                    CopyableAgentEnvVars(
                        agentId = "agent-$idx",
                        name = if (idx == 9) "Zephyr" else "Worker $idx",
                        avatarUrl = null,
                        entries = listOf(CopyableEnvVarEntry(if (idx == 9) "ZEPHYR_FLAG" else "VAR_$idx", "1")),
                    )
                }
            var currentEntries by mutableStateOf<List<Pair<String, String>>>(emptyList())
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().padding(16.dp)) {
                        EnvVarsEditor(
                            entries = currentEntries,
                            onChange = { currentEntries = it },
                            enabled = true,
                            copySource = RuntimeEnvVarsCopySource(manyCandidates, "Build Box", "m-many:codex"),
                        )
                    }
                }
            }

            onNodeWithTag("env-vars-copy-from-agent").performClick()
            onNodeWithTag("env-vars-copy-search").assertExists()
            val searchInput = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("env-vars-copy-search")))
            searchInput.performTextInput("does-not-exist")
            onNodeWithText("No matching agents").assertExists()
            searchInput.performTextClearance()
            searchInput.performTextInput("ZEPHYR_FLAG")
            onNodeWithTag("env-vars-copy-agent-agent-9").performClick()
            onNodeWithTag("env-vars-copy-status").assertTextEquals("Copied 1 from Zephyr.")
            assertEquals(listOf("ZEPHYR_FLAG" to "1"), currentEntries)
        }

    @Test
    fun createAgentMachinePickerShowsWrappingComputerDescriptionAndHidesWhenHosted() =
        runRobolectricComposeUiTest {
            val note =
                "xx 的 macbook air，🚫使用 <b>not bold</b> — shared build box for the infra team, ask in #infra before parking a long-running agent here"
            val machines =
                listOf(
                    SharedCreateAgentMachineOption(id = "m-source", name = "Source", status = "online", description = null),
                    SharedCreateAgentMachineOption(id = "m-noted", name = "Air", status = "online", description = note),
                    SharedCreateAgentMachineOption(id = "m-plain", name = "Studio", status = "online", description = "   "),
                )
            // Pure options check: description present on noted computer, omitted on blank/null.
            val dropdownItems = createAgentMachineDropdownItems(machines, requiredComputer = null)
            val options = createAgentMachineChoiceOptions(dropdownItems, quotaLines = mapOf("m-noted" to "5h: 12% left"))
            assertEquals("$note\n5h: 12% left", options.first { it.value == "m-noted" }.description)
            assertEquals(null, options.first { it.value == "m-plain" }.description)

            var selectedId by mutableStateOf<String?>("m-source")
            var hostedSelected by mutableStateOf(false)
            var requiredComputer by mutableStateOf<String?>(null)
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().padding(16.dp)) {
                        MachinePicker(
                            machines = machines,
                            machinesLoading = false,
                            machinesErrorMessage = null,
                            onRetry = {},
                            selectedMachineId = selectedId,
                            requiredComputer = requiredComputer,
                            suggestedComputer = null,
                            enabled = true,
                            runtimeId = "codex",
                            usageAccess = null,
                            hostedOption = "Raft Cloud",
                            hostedSelected = hostedSelected,
                            onSelectHosted = { hostedSelected = true },
                            onSelect = {
                                hostedSelected = false
                                selectedId = it
                            },
                        )
                    }
                }
            }

            // Computer with no description draws nothing under the field.
            onNodeWithTag("create-agent-selected-computer-description").assertDoesNotExist()

            // Choosing noted computer shows its full plain-text description under the field.
            selectedId = "m-noted"
            waitForIdle()
            onNodeWithTag("create-agent-selected-computer-description").assertTextEquals(note)

            // Card-required computer still shows the chosen computer's note under the field.
            requiredComputer = "m-noted"
            waitForIdle()
            onNodeWithTag("create-agent-selected-computer-description").assertTextEquals(note)

            // Hosted selection hides the computer note.
            hostedSelected = true
            waitForIdle()
            onNodeWithTag("create-agent-selected-computer-description").assertDoesNotExist()
        }

    @Test
    fun moveAgentTargetListShowsFullWrappingDescriptionAndInfoLine() =
        runRobolectricComposeUiTest {
            val note =
                "xx 的 macbook air，🚫使用 <b>not bold</b> — shared build box for the infra team, ask in #infra before parking a long-running agent here"
            val targets =
                listOf(
                    AgentMigrationComputer(id = "m-noted", name = "Air", isComputer = true, online = true, agentCount = 2, description = note),
                    AgentMigrationComputer(id = "m-plain", name = "Studio", isComputer = true, online = true, agentCount = 1, description = "   "),
                )
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().padding(16.dp)) {
                        AgentMigrationTargetList(
                            targets = targets,
                            hostedOption = null,
                            selected = "m-noted",
                            enabled = true,
                            agentRuntime = "codex",
                            usage = null,
                            onSelect = {},
                        )
                    }
                }
            }

            onNodeWithTag("agent-migration-target-description-m-noted", useUnmergedTree = true).assertTextEquals(note)
            onNodeWithTag("agent-migration-target-info-m-noted", useUnmergedTree = true).assertTextEquals("2 agents")
            onNodeWithTag("agent-migration-target-description-m-plain", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("agent-migration-target-info-m-plain", useUnmergedTree = true).assertTextEquals("1 agent")
        }

    @Test
    fun externalAgentDialogsUseConnectAnExternalAgentTitle() =
        runRobolectricComposeUiTest {
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        SharedCreateAgentDialog(
                            submitting = false,
                            errorMessage = null,
                            external = true,
                            initialName = "mia",
                            onDismiss = {},
                            onSubmit = {},
                        )
                    }
                }
            }
            onNodeWithText("Connect an external agent").assertExists()
        }

    @Test
    fun allEightLocalesResolveNewAndUpdatedParityStringsIncludingRussianPlurals() =
        runRobolectricComposeUiTest {
            data class LocaleSnapshot(
                val appliedTitle: String,
                val appliesWhenIdle: String,
                val newSessionWhenIdle: String,
                val addAgent: String,
                val addAgentNamed: String,
                val hostedMeta: String,
                val externalMeta: String,
                val externalTitle: String,
                val createExternalTitle: String,
                val copyButton: String,
                val copyHeading: String,
                val copySummary1: String,
                val copySummary2: String,
                val copySummary5: String,
                val copyCopied1: String,
                val copyCopied3: String,
                val copyCopiedKept1: String,
                val copyNothingNew: String,
                val copyNothingNewKept: String,
                val copySearch: String,
                val copyNoMatch: String,
            )

            val expectedTitles =
                mapOf(
                    AppLocale.English to ("Otter's settings applied" to "Connect an external agent"),
                    AppLocale.Spanish to ("Se aplicó la configuración de Otter" to "Conectar un agente externo"),
                    AppLocale.Japanese to ("Otter の設定が有効になりました" to "外部エージェントを接続"),
                    AppLocale.Korean to ("Otter의 설정이 적용되었어요" to "외부 에이전트 연결"),
                    AppLocale.BrazilianPortuguese to ("Configurações de Otter aplicadas" to "Conectar um agente externo"),
                    AppLocale.Russian to ("Настройки Otter применены" to "Подключить внешнего агента"),
                    AppLocale.SimplifiedChinese to ("Otter 的设置已生效" to "连接外部 Agent"),
                    AppLocale.TraditionalChinese to ("Otter 的設定已生效" to "連結外部 Agent"),
                )

            var currentLocale by mutableStateOf(AppLocale.English)
            var snapshot by mutableStateOf<LocaleSnapshot?>(null)
            setContent {
                StringDesc.localeType = currentLocale.stringDescLocaleType
                RaftDesignSystemTheme {
                    snapshot =
                        LocaleSnapshot(
                            appliedTitle = stringResource(Res.string.member_runtime_config_applied_title, "Otter"),
                            appliesWhenIdle = stringResource(Res.string.member_runtime_config_applies_when_idle),
                            newSessionWhenIdle = stringResource(Res.string.member_runtime_config_new_session_when_idle),
                            addAgent = stringResource(Res.string.thread_add_member_add_agent),
                            addAgentNamed = stringResource(Res.string.thread_add_member_add_agent_named, "scout"),
                            hostedMeta = stringResource(Res.string.thread_add_member_add_agent_hosted_meta),
                            externalMeta = stringResource(Res.string.thread_add_member_add_agent_external_meta),
                            externalTitle = stringResource(Res.string.agent_create_title_external),
                            createExternalTitle = stringResource(Res.string.external_agent_create_title),
                            copyButton = stringResource(Res.string.agent_runtime_config_copy_from_agent),
                            copyHeading = stringResource(Res.string.agent_runtime_config_copy_from_agent_heading, "Mac"),
                            copySummary1 = stringResource(Res.string.agent_runtime_config_copy_from_agent_summary_one, 1, "A"),
                            copySummary2 = stringResource(Res.string.agent_runtime_config_copy_from_agent_summary_other, 2, "A, B"),
                            copySummary5 = stringResource(Res.string.agent_runtime_config_copy_from_agent_summary_other, 5, "A, B, C, D, E"),
                            copyCopied1 = stringResource(Res.string.agent_runtime_config_copy_from_agent_copied_one, 1, "Atlas"),
                            copyCopied3 = stringResource(Res.string.agent_runtime_config_copy_from_agent_copied_other, 3, "Atlas"),
                            copyCopiedKept1 = stringResource(Res.string.agent_runtime_config_copy_from_agent_copied_kept_one, 1, "Atlas", "LOG_LEVEL"),
                            copyNothingNew = stringResource(Res.string.agent_runtime_config_copy_from_agent_nothing_new, "Atlas"),
                            copyNothingNewKept = stringResource(Res.string.agent_runtime_config_copy_from_agent_nothing_new_kept, "Atlas", "LOG_LEVEL"),
                            copySearch = stringResource(Res.string.agent_runtime_config_copy_from_agent_search_placeholder),
                            copyNoMatch = stringResource(Res.string.agent_runtime_config_copy_from_agent_no_match),
                        )
                }
            }

            for ((locale, pair) in expectedTitles) {
                currentLocale = locale
                waitForIdle()
                val s = requireNotNull(snapshot)
                assertEquals(pair.first, s.appliedTitle, "appliedTitle for $locale")
                assertEquals(pair.second, s.externalTitle, "externalTitle for $locale")
                assertEquals(pair.second, s.createExternalTitle, "createExternalTitle for $locale")
                assertTrue(s.appliesWhenIdle.isNotBlank() && s.newSessionWhenIdle.isNotBlank())
                assertTrue(s.addAgent.isNotBlank() && s.addAgentNamed.contains("scout"))
                assertTrue(s.hostedMeta.isNotBlank() && s.externalMeta.isNotBlank())
                assertTrue(s.copyButton.isNotBlank() && s.copyHeading.contains("Mac"))
                assertTrue(s.copyCopied1.contains("Atlas") && s.copyCopied3.contains("Atlas"))
                assertTrue(s.copyCopiedKept1.contains("LOG_LEVEL") && s.copyNothingNew.contains("Atlas"))
                assertTrue(s.copyNothingNewKept.contains("LOG_LEVEL") && s.copySearch.isNotBlank() && s.copyNoMatch.isNotBlank())
                if (locale == AppLocale.Russian) {
                    assertEquals("1 переменная: A", s.copySummary1)
                    assertEquals("2 переменные: A, B", s.copySummary2)
                    assertEquals("5 переменных: A, B, C, D, E", s.copySummary5)
                }
                if (locale == AppLocale.Spanish) {
                    assertEquals("Se copió 1 de Atlas.", s.copyCopied1)
                    assertEquals("Se copiaron 3 de Atlas.", s.copyCopied3)
                }
                if (locale == AppLocale.BrazilianPortuguese) {
                    assertEquals("Copiada 1 de Atlas.", s.copyCopied1)
                    assertEquals("Copiadas 3 de Atlas.", s.copyCopied3)
                }
            }
        }
}
