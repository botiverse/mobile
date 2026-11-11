package build.raft.app

/**
 * A person's built pixel avatar: port of raft-shared `humanAvatar.ts` (packages/shared/src, the
 * reader and the layer composition; the web's `PersonAvatar` → `HumanPixelAvatar` draws it).
 * Pinned to [RAFT_HUMAN_PIXEL_AVATAR_SLOCK_SHA]; vectors in `RaftHumanPixelAvatarVectorTest`,
 * guarded by `scripts/check_human_pixel_avatar_vector.sh`. Contract: slock
 * docs/contracts/human-pixel-avatar.md.
 *
 * The account stores the options the person chose, never an image:
 * `avatarUrl = "pixel-human:v1:<background>.<skin>.<hair>.<hairColor>.<expression>.<head>.<face>"`,
 * optionally followed by `/auto` (an avatar the Server picked; it draws the same).
 *
 * Drawing is lenient ([readRaftHumanPixelAvatarRecipe]): a missing or unknown field draws its
 * default, trailing fields this build doesn't know are ignored, and another version draws the
 * default avatar. A value that is not of the scheme at all (wrong alphabet, too long) is no
 * built avatar: the person placeholder. Storing is strict ([parseRaftHumanPixelAvatarRecipe]).
 * Whether such values exist is the server's `human_avatar_builder_v0` flag; drawing is never
 * gated.
 */
internal const val RAFT_HUMAN_PIXEL_AVATAR_SLOCK_SHA = "853c6998bcef45df5c25e63b1035e2388608721d"

internal const val RAFT_HUMAN_PIXEL_AVATAR_SCHEME = "pixel-human:"
internal const val RAFT_HUMAN_PIXEL_AVATAR_RECIPE_VERSION = "v1"
private const val Prefix = RAFT_HUMAN_PIXEL_AVATAR_SCHEME + RAFT_HUMAN_PIXEL_AVATAR_RECIPE_VERSION + ":"

/** The marker of an avatar the Server picked (a new account's random one), after every field. */
private const val AutoSuffix = "/auto"

/** Longest value a reader looks at; a real recipe is far shorter. */
private const val MaxValueLength = 128
private val ValuePattern = Regex("^pixel-human:v[0-9]{1,3}:[a-z0-9.]*(?:/auto)?$")

/** A recipe field, in wire order. Append only. */
internal enum class RaftHumanPixelAvatarField(
    /** Option ids in display order. Stable: stored values hold them. */
    val optionIds: List<String>,
) {
    Background(listOf("yellow", "pink", "sky", "mint", "lilac", "cream")),
    Skin(listOf("1", "2", "3", "4", "5")),
    Hair(listOf("short", "side", "long", "bun", "curly", "buzz")),
    HairColor(listOf("ink", "brown", "blonde", "ginger", "grey")),

    /** `wink` is the base's own face (an empty layer). */
    Expression(listOf("wink", "grin", "oh", "smirk", "happy", "calm", "blush")),

    /** One thing on the head, or none. */
    Head(listOf("none", "cap", "beanie", "headphones")),

    /** One thing on the face, or none. Combines with [Head]. */
    Face(listOf("none", "glasses")),
}

/** One known option id per field (web `HumanAvatarRecipe`). */
internal data class RaftHumanPixelAvatarRecipe(
    val background: String,
    val skin: String,
    val hair: String,
    val hairColor: String,
    val expression: String,
    val head: String,
    val face: String,
) {
    operator fun get(field: RaftHumanPixelAvatarField): String =
        when (field) {
            RaftHumanPixelAvatarField.Background -> background
            RaftHumanPixelAvatarField.Skin -> skin
            RaftHumanPixelAvatarField.Hair -> hair
            RaftHumanPixelAvatarField.HairColor -> hairColor
            RaftHumanPixelAvatarField.Expression -> expression
            RaftHumanPixelAvatarField.Head -> head
            RaftHumanPixelAvatarField.Face -> face
        }

    companion object {
        internal fun fromFields(read: (RaftHumanPixelAvatarField) -> String) =
            RaftHumanPixelAvatarRecipe(
                background = read(RaftHumanPixelAvatarField.Background),
                skin = read(RaftHumanPixelAvatarField.Skin),
                hair = read(RaftHumanPixelAvatarField.Hair),
                hairColor = read(RaftHumanPixelAvatarField.HairColor),
                expression = read(RaftHumanPixelAvatarField.Expression),
                head = read(RaftHumanPixelAvatarField.Head),
                face = read(RaftHumanPixelAvatarField.Face),
            )
    }
}

/** The art option each id draws (humanAvatarLayers.json); null draws nothing. */
internal val RaftHumanPixelAvatarOptions: Map<RaftHumanPixelAvatarField, Map<String, String?>> =
    mapOf(
        RaftHumanPixelAvatarField.Background to
            mapOf("yellow" to "yellow", "pink" to "pink", "sky" to "sky", "mint" to "mint", "lilac" to "lilac", "cream" to "cream"),
        RaftHumanPixelAvatarField.Skin to mapOf("1" to "1", "2" to "2", "3" to "3", "4" to "4", "5" to "5"),
        RaftHumanPixelAvatarField.Hair to
            mapOf("short" to "short", "side" to "side part", "long" to "long", "bun" to "bun", "curly" to "curly", "buzz" to "buzz"),
        RaftHumanPixelAvatarField.HairColor to
            mapOf("ink" to "ink", "brown" to "brown", "blonde" to "blonde", "ginger" to "ginger", "grey" to "grey"),
        RaftHumanPixelAvatarField.Expression to
            mapOf(
                "wink" to "wink", "grin" to "grin", "oh" to "oh", "smirk" to "smirk",
                "happy" to "happy", "calm" to "calm", "blush" to "blush",
            ),
        RaftHumanPixelAvatarField.Head to mapOf("none" to null, "cap" to "cap", "beanie" to "beanie", "headphones" to "headphones"),
        RaftHumanPixelAvatarField.Face to mapOf("none" to null, "glasses" to "glasses"),
    )

private fun RaftHumanPixelAvatarField.artOption(id: String): String? = RaftHumanPixelAvatarOptions.getValue(this)[id]

/** The art's `default` (yellow, cap, short black hair, wink): what a field that can't be read draws. */
internal val DefaultRaftHumanPixelAvatarRecipe =
    RaftHumanPixelAvatarRecipe(
        background = "yellow",
        skin = "2",
        hair = "short",
        hairColor = "ink",
        expression = "wink",
        head = "cap",
        face = "none",
    )

/** The stored value. [auto] marks one the Server picked. */
internal fun serializeRaftHumanPixelAvatarRecipe(recipe: RaftHumanPixelAvatarRecipe, auto: Boolean = false): String =
    Prefix + RaftHumanPixelAvatarField.entries.joinToString(".") { recipe[it] } + if (auto) AutoSuffix else ""

/**
 * True when [value] is a built pixel avatar (any version, any fields): a short value of the
 * `pixel-human:` scheme in its own alphabet. Exact, like the web: no trimming.
 */
internal fun isRaftHumanPixelAvatarUrl(value: String?): Boolean =
    value != null && value.length <= MaxValueLength && ValuePattern.matches(value)

/** True when the avatar is one the Server picked, not one the person built. */
internal fun isAutoRaftHumanPixelAvatarUrl(value: String?): Boolean =
    isRaftHumanPixelAvatarUrl(value) && value!!.endsWith(AutoSuffix)

/** The v1 fields of a value of this version, or null for another version. */
private fun v1Parts(value: String): List<String>? {
    if (!value.startsWith(Prefix)) return null
    val fields = if (value.endsWith(AutoSuffix)) value.substring(Prefix.length, value.length - AutoSuffix.length) else value.substring(Prefix.length)
    return fields.split(".")
}

/**
 * Strict: the recipe when [value] is exactly a v1 recipe this build knows (every field present
 * and known, nothing after them but the auto marker), else null.
 */
internal fun parseRaftHumanPixelAvatarRecipe(value: String): RaftHumanPixelAvatarRecipe? {
    val parts = (if (isRaftHumanPixelAvatarUrl(value)) v1Parts(value) else null) ?: return null
    val fields = RaftHumanPixelAvatarField.entries
    if (parts.size != fields.size) return null
    if (fields.indices.any { parts[it] !in fields[it].optionIds }) return null
    return RaftHumanPixelAvatarRecipe.fromFields { parts[it.ordinal] }
}

/**
 * Lenient: what to draw for a stored avatarUrl. Null when it isn't a built avatar at all.
 * Otherwise a full recipe: a missing or unknown field draws its default, extra trailing fields
 * are ignored, and another version draws the default avatar.
 */
internal fun readRaftHumanPixelAvatarRecipe(value: String?): RaftHumanPixelAvatarRecipe? {
    if (value == null || !isRaftHumanPixelAvatarUrl(value)) return null
    val parts = v1Parts(value).orEmpty()
    return RaftHumanPixelAvatarRecipe.fromFields { field ->
        parts.getOrNull(field.ordinal)?.takeIf { it in field.optionIds } ?: DefaultRaftHumanPixelAvatarRecipe[field]
    }
}

// ── Drawing ──

internal class RaftHumanPixelAvatarArtLayer(
    val layer: String,
    val option: String,
    val group: String?,
    val z: Int?,
    /** `size` strings of `size` role characters (0 transparent). */
    val rows: List<String>,
)

internal class RaftHumanPixelAvatarArt(
    val artVersion: String,
    val size: Int,
    /** Role character → `fixed:<key>` or `palette:<skin|hair>`. */
    val roleColors: Map<Char, String>,
    val fixed: Map<String, String>,
    val backgroundPalette: Map<String, String>,
    val skinPalette: Map<String, String>,
    val hairPalette: Map<String, String>,
    val layers: List<RaftHumanPixelAvatarArtLayer>,
) {
    internal val layerIndex: Map<String, RaftHumanPixelAvatarArtLayer> by lazy {
        // Later duplicates win, as in the web's `new Map(...)`.
        layers.associateBy { "${it.layer}:${it.option}" }
    }
}

/** The composed picture: [pixels] is row-major `size * size` `#rrggbb` colours; null shows the background. */
internal class RaftHumanPixelAvatarComposed(
    val size: Int,
    val background: String,
    val pixels: List<String?>,
)

private const val FallbackBackground = "#ffd440"

private fun roleColorLookup(art: RaftHumanPixelAvatarArt, recipe: RaftHumanPixelAvatarRecipe): Map<Char, String> {
    val skin = RaftHumanPixelAvatarField.Skin.artOption(recipe.skin)
    val hair = RaftHumanPixelAvatarField.HairColor.artOption(recipe.hairColor)
    val colors = HashMap<Char, String>()
    for ((role, source) in art.roleColors) {
        val kind = source.substringBefore(':')
        val key = source.substringAfter(':', missingDelimiterValue = "")
        val color =
            if (kind == "palette") {
                when (key) {
                    "skin" -> art.skinPalette[skin.orEmpty()]
                    "hair" -> art.hairPalette[hair.orEmpty()]
                    else -> null
                }
            } else {
                art.fixed[key]
            }
        if (!color.isNullOrEmpty()) colors[role] = color
    }
    return colors
}

/** The layers a recipe draws, in draw order: base, expression, hair, accessories by ascending z. */
private fun recipeLayers(art: RaftHumanPixelAvatarArt, recipe: RaftHumanPixelAvatarRecipe): List<RaftHumanPixelAvatarArtLayer> {
    val index = art.layerIndex
    val accessories =
        listOfNotNull(
            RaftHumanPixelAvatarField.Head.artOption(recipe.head),
            RaftHumanPixelAvatarField.Face.artOption(recipe.face),
        )
            .mapNotNull { index["accessory:$it"] }
            // Stable, like Array.prototype.sort.
            .sortedBy { it.z ?: 0 }
    return listOfNotNull(
        index["base:default"],
        index["expression:${RaftHumanPixelAvatarField.Expression.artOption(recipe.expression)}"],
        index["hair:${RaftHumanPixelAvatarField.Hair.artOption(recipe.hair)}"],
    ) + accessories
}

internal fun composeRaftHumanPixelAvatar(
    recipe: RaftHumanPixelAvatarRecipe,
    art: RaftHumanPixelAvatarArt = RaftHumanPixelAvatarArtData,
): RaftHumanPixelAvatarComposed {
    val size = art.size
    val colors = roleColorLookup(art, recipe)
    val pixels = arrayOfNulls<String>(size * size)
    for (layer in recipeLayers(art, recipe)) {
        for (y in 0 until size) {
            val row = layer.rows.getOrNull(y).orEmpty()
            for (x in 0 until minOf(size, row.length)) {
                val role = row[x]
                if (role == '0') continue
                val color = colors[role] ?: continue
                pixels[y * size + x] = color
            }
        }
    }
    val background =
        art.backgroundPalette[RaftHumanPixelAvatarField.Background.artOption(recipe.background).orEmpty()] ?: FallbackBackground
    return RaftHumanPixelAvatarComposed(size, background, pixels.asList())
}

/** `#rrggbb` → opaque ARGB. */
internal fun raftHumanPixelAvatarArgb(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()

/**
 * The composed picture as opaque ARGB pixels, the background filled in: what the app and the
 * home-screen widgets draw, scaled nearest-neighbour.
 */
internal class RaftHumanPixelAvatarRaster(val size: Int, val backgroundArgb: Int, val argb: IntArray)

internal fun RaftHumanPixelAvatarComposed.toRaster(): RaftHumanPixelAvatarRaster {
    val backgroundArgb = raftHumanPixelAvatarArgb(background)
    val cache = HashMap<String, Int>()
    return RaftHumanPixelAvatarRaster(
        size = size,
        backgroundArgb = backgroundArgb,
        argb = IntArray(size * size) { index -> pixels[index]?.let { cache.getOrPut(it) { raftHumanPixelAvatarArgb(it) } } ?: backgroundArgb },
    )
}

/** The raster a stored avatarUrl draws, or null when it is not a built avatar. */
internal fun raftHumanPixelAvatarRaster(value: String?): RaftHumanPixelAvatarRaster? =
    readRaftHumanPixelAvatarRecipe(value)?.let { composeRaftHumanPixelAvatar(it).toRaster() }
