package build.raft.app

import build.raft.app.resources.MR
import com.tencent.kuikly.compose.resources.DrawableResource
import com.tencent.kuikly.compose.resources.InternalResourceApi
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.tmm.kmmresource.resource.AssetResource

internal data class ThreadReactionAtlasResource(
    val image: AssetResource,
    val manifest: AssetResource,
    val imageUrl: String
)

internal data class ThreadReactionAtlas(
    val imageName: String,
    val width: Int,
    val height: Int,
    val pixelRatio: Int,
    val defaultDisplaySizeDp: Int
)

internal data class ThreadReactionSpriteRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

internal data class ThreadReactionSprite(
    val name: String,
    val value: String,
    val rect: ThreadReactionSpriteRect,
    val labels: Map<String, String>
) {
    fun label(localeTag: String = "en"): String {
        var candidate = localeTag
        while (candidate.isNotBlank()) {
            labels[candidate]?.let { return it }
            val separator = candidate.lastIndexOf('-')
            if (separator < 0) {
                break
            }
            candidate = candidate.substring(0, separator)
        }
        return labels["en"] ?: value
    }
}

internal data class ThreadReactionSpriteCatalog(
    val schemaVersion: Int,
    val resource: ThreadReactionAtlasResource,
    val atlas: ThreadReactionAtlas,
    val reactions: List<ThreadReactionSprite>
) {
    private val reactionsByValue: Map<String, ThreadReactionSprite> = reactions.associateBy { it.value }

    fun resolve(value: String): ThreadReactionSprite? = reactionsByValue[value]

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (schemaVersion != 1) {
            errors += "Unsupported reaction sprite schema version: $schemaVersion"
        }
        val duplicateValues = reactions.groupBy { it.value }.filterValues { it.size > 1 }.keys
        if (duplicateValues.isNotEmpty()) {
            errors += "Duplicate reaction values: ${duplicateValues.joinToString()}"
        }
        val duplicateNames = reactions.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicateNames.isNotEmpty()) {
            errors += "Duplicate reaction names: ${duplicateNames.joinToString()}"
        }
        reactions.forEach { sprite ->
            val rect = sprite.rect
            if (rect.width <= 0 || rect.height <= 0) {
                errors += "Reaction ${sprite.name} has a non-positive rect"
            }
            if (rect.x < 0 || rect.y < 0 || rect.x + rect.width > atlas.width || rect.y + rect.height > atlas.height) {
                errors += "Reaction ${sprite.name} rect is outside the atlas"
            }
        }
        return errors
    }
}

@OptIn(InternalResourceApi::class)
internal val ThreadReactionSpriteAtlasDrawableResource: DrawableResource =
    DrawableResource(ImageUri.commonAssets(MR.assets.reactions.reaction_atlas_v1.originalPath).toUrl(""))

internal val ThreadReactionSpriteAtlasResource = ThreadReactionAtlasResource(
    image = MR.assets.reactions.reaction_atlas_v1,
    manifest = MR.assets.reactions.reaction_atlas_v1_manifest,
    imageUrl = ImageUri.commonAssets(MR.assets.reactions.reaction_atlas_v1.originalPath).toUrl("")
)

internal val ThreadReactionSpriteCatalogV1 = ThreadReactionSpriteCatalog(
    schemaVersion = 1,
    resource = ThreadReactionSpriteAtlasResource,
    atlas = ThreadReactionAtlas(
        imageName = "reaction_atlas_v1.png",
        width = 504,
        height = 72,
        pixelRatio = 3,
        defaultDisplaySizeDp = 18
    ),
    reactions = listOf(
        ThreadReactionSprite(
            name = "thumbs_up",
            value = "👍",
            rect = ThreadReactionSpriteRect(x = 0, y = 0, width = 72, height = 72),
            labels = mapOf("en" to "Thumbs up", "zh-Hans" to "点赞", "zh-Hant" to "讚")
        ),
        ThreadReactionSprite(
            name = "heart",
            value = "❤️",
            rect = ThreadReactionSpriteRect(x = 72, y = 0, width = 72, height = 72),
            labels = mapOf("en" to "Heart", "zh-Hans" to "爱心", "zh-Hant" to "愛心")
        ),
        ThreadReactionSprite(
            name = "party",
            value = "🎉",
            rect = ThreadReactionSpriteRect(x = 144, y = 0, width = 72, height = 72),
            labels = mapOf("en" to "Celebrate", "zh-Hans" to "庆祝", "zh-Hant" to "慶祝")
        ),
        ThreadReactionSprite(
            name = "eyes",
            value = "👀",
            rect = ThreadReactionSpriteRect(x = 216, y = 0, width = 72, height = 72),
            labels = mapOf("en" to "Eyes", "zh-Hans" to "关注", "zh-Hant" to "關注")
        ),
        ThreadReactionSprite(
            name = "fire",
            value = "🔥",
            rect = ThreadReactionSpriteRect(x = 288, y = 0, width = 72, height = 72),
            labels = mapOf("en" to "Fire", "zh-Hans" to "火", "zh-Hant" to "火")
        ),
        ThreadReactionSprite(
            name = "laugh",
            value = "😂",
            rect = ThreadReactionSpriteRect(x = 360, y = 0, width = 72, height = 72),
            labels = mapOf("en" to "Laugh", "zh-Hans" to "大笑", "zh-Hant" to "大笑")
        ),
        ThreadReactionSprite(
            name = "done",
            value = "✅",
            rect = ThreadReactionSpriteRect(x = 432, y = 0, width = 72, height = 72),
            labels = mapOf("en" to "Done", "zh-Hans" to "完成", "zh-Hant" to "完成")
        )
    )
)

internal val ThreadQuickReactionEmojis: List<String> =
    ThreadReactionSpriteCatalogV1.reactions.map { it.value }
