package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadReactionSpriteCatalogTest {
    @Test
    fun quickReactionOrderComesFromSpriteCatalog() {
        assertEquals(listOf("👍", "❤️", "🎉", "👀", "🔥", "😂", "✅"), ThreadQuickReactionEmojis)
    }

    @Test
    fun catalogResolvesCanonicalValuesAndAliases() {
        assertNotNull(ThreadReactionSpriteCatalogV1.resolve("✅"))
        assertNull(ThreadReactionSpriteCatalogV1.resolve("☑️"))
        // "❤" (without VS16) is a canonical alias of "❤️" in reactions.json.
        val heart = assertNotNull(ThreadReactionSpriteCatalogV1.resolve("❤"))
        assertEquals("heart", heart.name)
        assertEquals(ThreadReactionSpriteCatalogV1.resolve("❤️"), heart)
    }

    @Test
    fun atlasRectsValidateAgainstAtlasBounds() {
        assertEquals(emptyList(), ThreadReactionSpriteCatalogV1.validate())
    }

    @Test
    fun labelsFallBackByLocaleTagAndEnglish() {
        val done = assertNotNull(ThreadReactionSpriteCatalogV1.resolve("✅"))
        assertEquals("完成", done.label("zh-Hans-CN"))
        assertEquals("Check mark", done.label("fr"))
    }

    @Test
    fun resourceAdapterUsesComposeResourcesFilePath() {
        // The atlas ships as a Compose resources file; the image loader reads
        // "assets://compose-resources/<path>" through Res.readBytes(<path>). A "common/"
        // segment (the old Kuikly asset URL) points at no file (silent legacy fallback).
        // task #421: the atlas resource stays nullable; on this JVM test target it
        // resolves, so assert it.
        val atlasResource = assertNotNull(ThreadReactionSpriteAtlasResource)
        assertTrue(atlasResource.imageUrl.startsWith("assets://"))
        assertTrue(!atlasResource.imageUrl.startsWith("assets://common/"))
        assertEquals(
            "assets://compose-resources/${atlasResource.image.path}",
            atlasResource.imageUrl
        )
        assertTrue(atlasResource.imageUrl.endsWith("reaction_atlas_v1.png"))
        assertTrue(atlasResource.manifest.path.endsWith("reaction_atlas_v1_manifest.json"))
    }
}
