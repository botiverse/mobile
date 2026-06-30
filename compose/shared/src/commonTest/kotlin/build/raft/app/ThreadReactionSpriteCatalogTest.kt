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
    fun catalogResolvesOnlyExactReactionValues() {
        assertNotNull(ThreadReactionSpriteCatalogV1.resolve("✅"))
        assertNull(ThreadReactionSpriteCatalogV1.resolve("☑️"))
        assertNull(ThreadReactionSpriteCatalogV1.resolve("❤"))
    }

    @Test
    fun atlasRectsValidateAgainstAtlasBounds() {
        assertEquals(emptyList(), ThreadReactionSpriteCatalogV1.validate())
    }

    @Test
    fun labelsFallBackByLocaleTagAndEnglish() {
        val done = assertNotNull(ThreadReactionSpriteCatalogV1.resolve("✅"))
        assertEquals("完成", done.label("zh-Hans-CN"))
        assertEquals("Done", done.label("fr"))
    }

    @Test
    fun resourceAdapterUsesGeneratedKmmResourcePath() {
        assertTrue(ThreadReactionSpriteAtlasResource.imageUrl.startsWith("assets://common/"))
        assertTrue(ThreadReactionSpriteAtlasResource.imageUrl.endsWith("reaction_atlas_v1.png"))
        assertTrue(ThreadReactionSpriteAtlasResource.manifest.originalPath.endsWith("reaction_atlas_v1_manifest.json"))
    }
}
