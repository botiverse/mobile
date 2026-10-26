package build.raft.app

import build.raft.app.base.putIfAbsent
import build.raft.app.compat.ui.DrawableResource
import build.raft.app.compat.ui.InternalResourceApi
import build.raft.app.resources.RaftBundledFile
import build.raft.app.resources.RaftBundledFiles

internal data class ThreadReactionAtlasResource(
    val image: RaftBundledFile,
    val manifest: RaftBundledFile,
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
    val labels: Map<String, String>,
    // Alternate wire values that resolve to this sprite (e.g. "❤" for "❤️"),
    // mirroring the canonical web reactions.json aliases.
    val aliases: List<String> = emptyList()
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
    // Nullable (task #421): the former kmmresource atlas asset could not
    // initialize on iOS; the guard stays so a missing file degrades to null.
    // The sprite `reactions`/`atlas` metadata is pure data and always present;
    // `resource` is only needed to load the atlas image.
    val resource: ThreadReactionAtlasResource?,
    val atlas: ThreadReactionAtlas,
    val reactions: List<ThreadReactionSprite>
) {
    private val reactionsByValue: Map<String, ThreadReactionSprite> =
        buildMap {
            reactions.forEach { sprite ->
                put(sprite.value, sprite)
                sprite.aliases.forEach { alias ->
                    putIfAbsent(alias, sprite)
                }
            }
        }

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

// The reaction atlas ships as a Compose resources file
// (composeResources/files/reactions/). The image loader resolves
// "assets://compose-resources/<path>" through Res.readBytes(<path>); the URL
// must NOT carry the Kuikly "common/" segment — ImageUri.commonAssets produced
// "assets://common/slock_reactions/..." which exists nowhere and made the atlas
// silently fall back to legacy glyphs (task #335 follow-up).
// task #421: touching the former kmmresource MR.assets could throw on iOS. The
// probe stays lazy and guarded, so class initialization never crashes; everything
// that needs the atlas image checks this first and otherwise falls back to
// emoji glyphs (see ThreadQuickReactionGlyph), matching Android's legacy-glyph
// fallback semantics (task #379).
internal val spriteAtlasAvailable: Boolean by lazy {
    runCatching { RaftBundledFiles.reactionAtlasV1.path }.isSuccess
}

private val ThreadReactionSpriteAtlasImageUrl: String? by lazy {
    runCatching { RaftBundledFiles.reactionAtlasV1.imageUrl }.getOrNull()
}

@OptIn(InternalResourceApi::class)
internal val ThreadReactionSpriteAtlasDrawableResource: DrawableResource? by lazy {
    ThreadReactionSpriteAtlasImageUrl?.let { DrawableResource(it) }
}

internal val ThreadReactionSpriteAtlasResource: ThreadReactionAtlasResource? by lazy {
    val url = ThreadReactionSpriteAtlasImageUrl ?: return@lazy null
    runCatching {
        ThreadReactionAtlasResource(
            image = RaftBundledFiles.reactionAtlasV1,
            manifest = RaftBundledFiles.reactionAtlasV1Manifest,
            imageUrl = url
        )
    }.getOrNull()
}

internal val ThreadReactionSpriteCatalogV1 = ThreadReactionSpriteCatalog(
    schemaVersion = 1,
    resource = ThreadReactionSpriteAtlasResource,
    atlas = ThreadReactionAtlas(
        imageName = "reaction_atlas_v1.png",
        width = 672,
        height = 96,
        pixelRatio = 4,
        defaultDisplaySizeDp = 18
    ),
    reactions = listOf(
        ThreadReactionSprite(
            name = "thumbs_up",
            value = "👍",
            rect = ThreadReactionSpriteRect(x = 0, y = 0, width = 96, height = 96),
            labels = mapOf("en" to "Thumbs up", "zh-Hans" to "点赞", "zh-Hant" to "讚")
        ),
        ThreadReactionSprite(
            name = "heart",
            value = "❤️",
            rect = ThreadReactionSpriteRect(x = 96, y = 0, width = 96, height = 96),
            labels = mapOf("en" to "Heart", "zh-Hans" to "爱心", "zh-Hant" to "愛心"),
            aliases = listOf("❤")
        ),
        ThreadReactionSprite(
            name = "party_popper",
            value = "🎉",
            rect = ThreadReactionSpriteRect(x = 192, y = 0, width = 96, height = 96),
            labels = mapOf("en" to "Party popper", "zh-Hans" to "庆祝", "zh-Hant" to "慶祝")
        ),
        ThreadReactionSprite(
            name = "eyes",
            value = "👀",
            rect = ThreadReactionSpriteRect(x = 288, y = 0, width = 96, height = 96),
            labels = mapOf("en" to "Eyes", "zh-Hans" to "关注", "zh-Hant" to "關注")
        ),
        ThreadReactionSprite(
            name = "fire",
            value = "🔥",
            rect = ThreadReactionSpriteRect(x = 384, y = 0, width = 96, height = 96),
            labels = mapOf("en" to "Fire", "zh-Hans" to "火", "zh-Hant" to "火")
        ),
        ThreadReactionSprite(
            name = "joy",
            value = "😂",
            rect = ThreadReactionSpriteRect(x = 480, y = 0, width = 96, height = 96),
            labels = mapOf("en" to "Face with tears of joy", "zh-Hans" to "大笑", "zh-Hant" to "大笑")
        ),
        ThreadReactionSprite(
            name = "check_mark",
            value = "✅",
            rect = ThreadReactionSpriteRect(x = 576, y = 0, width = 96, height = 96),
            labels = mapOf("en" to "Check mark", "zh-Hans" to "完成", "zh-Hant" to "完成")
        )
    )
)

internal val ThreadQuickReactionEmojis: List<String> =
    ThreadReactionSpriteCatalogV1.reactions.map { it.value }
