package build.raft.app

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import build.raft.app.data.RaftModelLabelCatalogs
import build.raft.app.data.RaftRuntimeCatalogNames
import build.raft.app.data.RuntimeCatalogEntry
import build.raft.app.data.RuntimeCatalogForm
import build.raft.app.data.RuntimeCatalogSource
import build.raft.app.data.ServerModelLabelCatalog
import build.raft.app.data.ServerRuntimeCatalog
import build.raft.app.data.observeRuntimeCatalogDisplayName
import build.raft.app.data.observeRuntimeCatalogIsDeprecated
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that StateFlow-backed catalog & pixel-avatar registries recompose Composables
 * reactively when catalogs arrive asynchronously after initial composition, and switch servers
 * synchronously without leaking the previous server's catalog for a frame (Hands 25b91174 /
 * #proj-mobile-release:#35).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogRegistryReactiveUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @BeforeTest
    fun setUp() {
        RaftRuntimeCatalogNames.clear()
        RaftModelLabelCatalogs.clear()
        RaftPixelAvatarRuntime.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        RaftRuntimeCatalogNames.clear()
        RaftModelLabelCatalogs.clear()
        RaftPixelAvatarRuntime.resetForTest()
    }

    @Test
    fun runtimeAndModelLabelCatalogObserversRecomposeWhenPublishedAsynchronously() =
        runRobolectricComposeUiTest {
            setContent {
                val runtimeName = observeRuntimeCatalogDisplayName("future-rt", "srv-ui")
                val deprecated = observeRuntimeCatalogIsDeprecated("claude", "srv-ui")
                val modelLabel = RaftModelLabelCatalogs.observeLabel("srv-ui", "m-1", "claude", "fable") ?: "fallback"
                val avatarVersion = RaftPixelAvatarRuntime.observeCurrent().version
                Text("$runtimeName|$deprecated|$modelLabel|$avatarVersion", modifier = Modifier.testTag(LABEL))
            }
            val bundled = RaftPixelAvatarRuntime.bundled
            val bundledVersion = bundled.version
            waitForIdle()
            onNodeWithTag(LABEL).assertTextEquals("future-rt|false|fallback|$bundledVersion")

            runOnIdle {
                RaftRuntimeCatalogNames.publish(
                    "srv-ui",
                    ServerRuntimeCatalog(
                        catalogVersion = 1,
                        runtimes =
                            listOf(
                                RuntimeCatalogEntry(
                                    id = "future-rt",
                                    displayName = "Future Runtime",
                                    forms = listOf(RuntimeCatalogForm.V2),
                                ),
                                RuntimeCatalogEntry(
                                    id = "claude",
                                    displayName = "Claude Code",
                                    deprecated = true,
                                    forms = listOf(RuntimeCatalogForm.V2),
                                ),
                            ),
                        source = RuntimeCatalogSource.Server,
                    ),
                )
                RaftModelLabelCatalogs.publish(
                    "srv-ui",
                    ServerModelLabelCatalog(
                        machines = mapOf("m-1" to mapOf("claude" to mapOf("fable" to "Fable 5.2"))),
                    ),
                )
                RaftPixelAvatarRuntime.applyParsed(
                    PixelAvatarData(
                        version = bundledVersion + 1,
                        palette = bundled.palette,
                        paletteArgb = bundled.paletteArgb,
                        specs = bundled.specs,
                        reservedKeys = bundled.reservedKeys,
                        defaultKey = bundled.defaultKey,
                        series = bundled.series,
                    ),
                    source = "test",
                )
            }
            waitForIdle()
            onNodeWithTag(LABEL).assertTextEquals("Future Runtime|true|Fable 5.2|${bundledVersion + 1}")
        }

    @Test
    fun switchingObservedServerNeverLeaksPreviousServersCatalogForAFrame() =
        runRobolectricComposeUiTest {
            RaftRuntimeCatalogNames.publish(
                "srv-a",
                ServerRuntimeCatalog(
                    catalogVersion = 1,
                    runtimes =
                        listOf(
                            RuntimeCatalogEntry(
                                id = "claude",
                                displayName = "Claude Code",
                                deprecated = true,
                                forms = listOf(RuntimeCatalogForm.V2),
                            ),
                        ),
                    source = RuntimeCatalogSource.Server,
                ),
            )
            RaftModelLabelCatalogs.publish(
                "srv-a",
                ServerModelLabelCatalog(
                    machines = mapOf("m-1" to mapOf("claude" to mapOf("fable" to "Server A Fable"))),
                ),
            )
            var activeServer by mutableStateOf("srv-a")
            val observedDuringComposition = mutableListOf<String>()
            setContent {
                val deprecated = observeRuntimeCatalogIsDeprecated("claude", activeServer)
                val modelLabel = RaftModelLabelCatalogs.observeLabel(activeServer, "m-1", "claude", "fable") ?: "none"
                val line = "$activeServer|$deprecated|$modelLabel"
                observedDuringComposition += line
                Text(line, modifier = Modifier.testTag(LABEL))
            }
            waitForIdle()
            onNodeWithTag(LABEL).assertTextEquals("srv-a|true|Server A Fable")
            observedDuringComposition.clear()

            runOnIdle { activeServer = "srv-b" }
            waitForIdle()
            onNodeWithTag(LABEL).assertTextEquals("srv-b|false|none")
            // Every composition under srv-b must already see srv-b's values (never srv-a's).
            assertEquals(listOf("srv-b|false|none"), observedDuringComposition.distinct())
        }

    private companion object {
        const val LABEL = "catalog_label"
    }
}
