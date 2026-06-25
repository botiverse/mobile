package ai.slock.compose

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
        assertEquals(
            "assets://${ThreadReactionSpriteAtlasResource.image.originalPath}",
            ThreadReactionSpriteAtlasResource.imageUrl
        )
        assertTrue(ThreadReactionSpriteAtlasResource.imageUrl.endsWith("slock_reactions/reaction_atlas_v1.png"))
        assertTrue(ThreadReactionSpriteAtlasResource.manifest.originalPath.endsWith("reaction_atlas_v1_manifest.json"))
    }

    @Test
    fun spritesExposeDirectRenderableImageAssets() {
        ThreadReactionSpriteCatalogV1.reactions.forEach { sprite ->
            assertEquals("slock_reactions/reaction_${sprite.name}_v1.png", sprite.image.originalPath)
            assertNotNull(sprite.toDrawableResource())
        }
    }
}
