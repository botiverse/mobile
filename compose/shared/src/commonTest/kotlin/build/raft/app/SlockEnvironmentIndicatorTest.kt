package build.raft.app

import build.raft.app.data.InMemorySlockKeyValueStore
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedSettingsApiEnvironmentRequest
import build.raft.app.data.SharedSettingsApiEnvironmentOption
import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockEnvironmentIndicatorTest {
    @Test
    fun productionEndpointVariantsDoNotRenderAnIndicator() {
        val production = SharedApiEnvironmentStore.Production
        val variants =
            listOf(
                production,
                production.copy(
                    key = SharedApiEnvironmentStore.CustomKey,
                    label = "Production alias",
                    apiBaseUrl = production.apiBaseUrl.trimEnd('/'),
                ),
            )

        variants.forEach { environment ->
            assertNull(slockEnvironmentIndicatorLabel(environment))
        }
    }

    @Test
    fun nonProductionEnvironmentsRenderTheirConfiguredNames() {
        val cases =
            listOf(
                SharedApiEnvironmentStore.Staging to "Staging",
                SharedSettingsApiEnvironmentOption(
                    key = SharedApiEnvironmentStore.CustomKey,
                    label = "  Local Dev  ",
                    apiBaseUrl = "http://10.0.2.2:3000/api/",
                    socketBaseUrl = "http://10.0.2.2:3000",
                    webUrl = "http://10.0.2.2:3000",
                ) to "Local Dev",
            )

        cases.forEach { (environment, expectedLabel) ->
            assertEquals(expectedLabel, slockEnvironmentIndicatorLabel(environment))
        }
    }

    @Test
    fun environmentStoreStateTracksSelectionsForTheMountedGlobalHost() {
        val store = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())

        assertEquals(SharedApiEnvironmentStore.Production, store.state.value)

        store.select(SharedApiEnvironmentStore.Staging.key)

        assertEquals(SharedApiEnvironmentStore.Staging, store.state.value)

        val custom =
            store.save(
                SharedSettingsApiEnvironmentRequest(
                    key = SharedApiEnvironmentStore.CustomKey,
                    label = "Local Dev",
                    apiBaseUrl = "http://10.0.2.2:3000/api/",
                    socketBaseUrl = "http://10.0.2.2:3000",
                    webUrl = "http://10.0.2.2:3000",
                ),
            )

        assertEquals(custom, store.state.value)
    }

    @Test
    fun embeddedChildMarkerUsesACopyAndSuppressesDuplicateIndicator() {
        val original =
            JSONObject().apply {
                put("threadId", "thread-1")
            }

        val embedded = slockEmbeddedChildPageData(original)

        assertFalse(original.optBoolean(SLOCK_EMBEDDED_CHILD_PAGE_PARAM, false))
        assertTrue(embedded.optBoolean(SLOCK_EMBEDDED_CHILD_PAGE_PARAM, false))
        assertEquals("thread-1", embedded.optString("threadId"))
        assertTrue(slockEnvironmentIndicatorEnabledForPage(original))
        assertFalse(slockEnvironmentIndicatorEnabledForPage(embedded))
    }

    @Test
    fun draggableIndicatorDefaultsToSafeTopRightAndClampsToEveryVisibleEdge() {
        val bounds =
            SlockEnvironmentIndicatorDragBounds(
                hostWidthPx = 720f,
                hostHeightPx = 1280f,
                badgeWidthPx = 96f,
                badgeHeightPx = 40f,
                topSafeAreaPx = 48f,
                bottomSafeAreaPx = 72f,
                edgeMarginPx = 12f,
            )

        assertEquals(
            SlockEnvironmentIndicatorPosition(xPx = 612f, yPx = 60f),
            slockDefaultEnvironmentIndicatorPosition(bounds),
        )
        assertEquals(
            SlockEnvironmentIndicatorPosition(xPx = 12f, yPx = 60f),
            slockClampEnvironmentIndicatorPosition(
                SlockEnvironmentIndicatorPosition(xPx = -1_000f, yPx = -1_000f),
                bounds,
            ),
        )
        assertEquals(
            SlockEnvironmentIndicatorPosition(xPx = 612f, yPx = 1_156f),
            slockClampEnvironmentIndicatorPosition(
                SlockEnvironmentIndicatorPosition(xPx = 10_000f, yPx = 10_000f),
                bounds,
            ),
        )
    }

    @Test
    fun draggableIndicatorRemainsVisibleWhenInsetsExceedTheTinyHost() {
        val bounds =
            SlockEnvironmentIndicatorDragBounds(
                hostWidthPx = 80f,
                hostHeightPx = 60f,
                badgeWidthPx = 100f,
                badgeHeightPx = 40f,
                topSafeAreaPx = 30f,
                bottomSafeAreaPx = 30f,
                edgeMarginPx = 12f,
            )

        assertEquals(
            SlockEnvironmentIndicatorPosition(xPx = 0f, yPx = 10f),
            slockClampEnvironmentIndicatorPosition(
                SlockEnvironmentIndicatorPosition(xPx = 500f, yPx = 500f),
                bounds,
            ),
        )
    }
}
