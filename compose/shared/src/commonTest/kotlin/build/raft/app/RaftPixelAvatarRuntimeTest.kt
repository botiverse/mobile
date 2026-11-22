package build.raft.app

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import build.raft.app.data.InMemoryRaftKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftPixelAvatarRuntimeTest {

    // Trimmed canonical pixelAvatars.json snapshot: palette-letter bg (robot),
    // literal #hex bg (mug, like finch), a reserved key, and a letter ('Z')
    // that is NOT in the bundled palette — proving specs resolve against the
    // document's own palette rather than the generated one.
    private fun sampleJson(
        version: Int = 1,
        defaultKey: String = "robot",
        reservedKeys: String = """["mug"]""",
        robotGridRows: String = "\"_KKKKKK_\", \"KCKWWKCK\", \"KWKWWKWK\", \"KKKKKKKK\", \"_KWKKWK_\", \"_KKKKKK_\", \"_KK__KK_\", \"_KK__KK_\""
    ): String =
        """
        {
          "version": $version,
          "comment": "unknown keys must be ignored",
          "palette": {
            "_": "transparent",
            "K": "#141111",
            "W": "#FFFFFF",
            "C": "#27CCF3",
            "Z": "#123456"
          },
          "avatars": {
            "robot": { "bg": "C", "grid": [$robotGridRows] },
            "mug": { "bg": "#F8EEDF", "grid": ["ZZZZZZZZ", "ZZZZZZZZ", "ZZZZZZZZ", "ZZZZZZZZ", "ZZZZZZZZ", "ZZZZZZZZ", "ZZZZZZZZ", "ZZZZZZZZ"] }
          },
          "reservedKeys": $reservedKeys,
          "defaultKey": "$defaultKey"
        }
        """.trimIndent()

    @Test
    fun parserAcceptsCanonicalSample() {
        val data = assertNotNull(parsePixelAvatarData(sampleJson()))

        assertEquals(1, data.version)
        assertEquals(listOf('_', 'K', 'W', 'C', 'Z'), data.palette.keys.toList())
        assertEquals(Color.Transparent, data.palette.getValue('_'))
        assertEquals(Color(0xFF27CCF3), data.palette.getValue('C'))
        assertEquals("#00000000", data.paletteArgb.getValue('_'))
        assertEquals("#FF27CCF3", data.paletteArgb.getValue('C'))
        assertEquals(listOf("robot", "mug"), data.specs.keys.toList())
        assertEquals(setOf("mug"), data.reservedKeys)
        assertEquals("robot", data.defaultKey)

        val robot = data.specs.getValue("robot")
        assertEquals(Color(0xFF27CCF3), robot.background)
        assertTrue(robot.spans.isNotEmpty())
        assertEquals("#FF27CCF3", robot.backgroundArgb)
    }

    @Test
    fun parserResolvesLiteralBackgroundAndDocumentPalette() {
        val data = assertNotNull(parsePixelAvatarData(sampleJson()))

        val mug = data.specs.getValue("mug")
        // Literal (non-palette) background resolves both the Color and its ARGB string.
        assertEquals(Color(0xFFF8EEDF), mug.background)
        assertEquals("#FFF8EEDF", mug.backgroundArgb)
        // 'Z' is not in the bundled palette: spans must come from the document palette.
        assertEquals(8, mug.spans.size)
        mug.spans.forEach { span ->
            assertEquals(Color(0xFF123456), span.color)
            assertEquals("#FF123456", span.colorArgb)
            assertEquals(8, span.columnCount)
        }
    }

    @Test
    fun parserRejectsMalformedGridRowCount() {
        val sevenRows = List(7) { "\"________\"" }.joinToString(", ")
        assertNull(parsePixelAvatarData(sampleJson(robotGridRows = sevenRows)))
    }

    @Test
    fun versionGateRejectsDocumentsOlderThanMinimum() {
        try {
            // Parseable but stale relative to an explicit minimum: rejected by the gate.
            assertNull(
                RaftPixelAvatarRuntime.applyRuntimeJson(
                    sampleJson(version = 1),
                    source = "test",
                    minimumVersion = 2
                )
            )
            // A document older than version 1 can never parse at all.
            assertNull(RaftPixelAvatarRuntime.applyRuntimeJson(sampleJson(version = 0), source = "test"))
            assertSame(RaftPixelAvatarRuntime.bundled, RaftPixelAvatarRuntime.current)
        } finally {
            RaftPixelAvatarRuntime.resetForTest()
        }
    }

    @Test
    fun holderFallsBackToBundledWhenNothingCached() {
        try {
            assertNull(RaftPixelAvatarRuntime.applyRuntimeJson(null, source = "cache"))
            assertNull(RaftPixelAvatarRuntime.applyRuntimeJson("", source = "cache"))
            assertNull(RaftPixelAvatarRuntime.applyRuntimeJson("{broken", source = "cache"))
            assertSame(RaftPixelAvatarRuntime.bundled, RaftPixelAvatarRuntime.current)
            // The bundled table is the generated one.
            assertEquals(PixelAvatarDataVersion, RaftPixelAvatarRuntime.current.version)
            assertEquals(PixelAvatarDefaultKey, RaftPixelAvatarRuntime.current.defaultKey)
            assertEquals(PixelAvatarFixedSpecs.keys, RaftPixelAvatarRuntime.current.specs.keys)
        } finally {
            RaftPixelAvatarRuntime.resetForTest()
        }
    }

    @Test
    fun holderSwapsToValidDocumentAndReflectsInReadThroughs() {
        try {
            val applied =
                RaftPixelAvatarRuntime.applyRuntimeJson(
                    sampleJson(version = RaftPixelAvatarRuntime.bundled.version + 1),
                    source = "fetch"
                )
            assertNotNull(applied)
            assertSame(applied, RaftPixelAvatarRuntime.current)
            assertEquals("robot", PixelAvatarActiveDefaultKey)
            // Read-through picker keys: canonical order minus reserved keys.
            assertEquals(listOf("robot"), PixelAvatarPickerKeys)
        } finally {
            RaftPixelAvatarRuntime.resetForTest()
        }
    }

    @Test
    fun keyValueStoreRoundTripsJsonVersionAndFetchedAt() {
        val backing = InMemoryRaftKeyValueStore()
        val store = KeyValuePixelAvatarStore(backing)

        assertNull(store.readJson())
        assertNull(store.readVersion())

        store.save(json = """{"version":3}""", version = 3, fetchedAtEpochMillis = 1234L)

        assertEquals("""{"version":3}""", store.readJson())
        assertEquals(3, store.readVersion())
        assertEquals("""{"version":3}""", backing.getString("avatars.pixelJson"))
        assertEquals("3", backing.getString("avatars.pixelJsonVersion"))
        assertEquals("1234", backing.getString("avatars.pixelJsonFetchedAt"))
    }

    @Test
    fun pixelRandomWireValueUsesSeededRandomAvatar() {
        val randomAvatar = assertNotNull(pixelAvatarSpecForWireValue("pixel:random:agent-1"))
        val nestedRandomAvatar = assertNotNull(pixelAvatarSpecForWireValue("pixel:random:agent-1"))
        val defaultAvatar = assertNotNull(pixelAvatarSpecForWireValue("pixel:robot"))

        assertEquals(randomAvatar.nativeCacheKey, nestedRandomAvatar.nativeCacheKey)
        assertFalse(nestedRandomAvatar.nativeCacheKey == defaultAvatar.nativeCacheKey)
        assertNull(pixelAvatarSpecForWireValue("random:agent-1"))
        assertNull(pixelAvatarSpecForWireValue("pixel:random:"))
    }

    @Test
    fun runtimeSwapNeverRollsBackToOlderVersionOrThrowsInsideOpenSnapshot() {
        try {
            val baseVersion = RaftPixelAvatarRuntime.bundled.version
            val openSnapshot = Snapshot.takeMutableSnapshot()
            try {
                val newer = assertNotNull(
                    RaftPixelAvatarRuntime.applyRuntimeJson(
                        sampleJson(version = baseVersion + 5),
                        source = "fetch"
                    )
                )
                assertNull(
                    RaftPixelAvatarRuntime.applyRuntimeJson(
                        sampleJson(version = baseVersion + 2),
                        source = "cache"
                    )
                )
                val observed = openSnapshot.enter { RaftPixelAvatarRuntime.current }
                assertSame(newer, observed)
            } finally {
                openSnapshot.dispose()
            }
        } finally {
            RaftPixelAvatarRuntime.resetForTest()
        }
    }
}
